package com.takashi.dungeons.storage;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The way in to the database, and the only thread that touches it.
 *
 * <h2>The main thread never waits on SQL — except once</h2>
 * Every query goes onto a single worker thread and comes back as a {@link CompletableFuture}. The
 * exception is {@link #enable()}, which connects and migrates <b>synchronously</b>, and that is
 * deliberate: enable runs before the server accepts a single connection, so nothing is being
 * blocked that a player could notice, and the alternative is worse — an enable that returns before
 * the schema exists lets the first join race a {@code CREATE TABLE}. An operator expects a database
 * to be reached at boot; they do not expect a player to be the one who finds out it was not.
 *
 * <h2>One thread is the concurrency control</h2>
 * Not a simplification of one — the whole answer. Statements cannot interleave if they cannot run
 * at the same time, so there is no connection to synchronise, no transaction to isolate from a
 * sibling and no ordering to reason about: the writes land in the order they were asked for.
 * Concurrency <i>between servers</i> is a different question, and it is answered in
 * {@link SqlDialect#increment} — relative updates, never read-modify-write.
 *
 * <h2>Failure is survivable by design</h2>
 * A database that will not open does not stop the plugin. Dungeons generate, mobs spawn and loot
 * drops with no persistence at all; what is lost is that settings and counters stop outliving the
 * session, which is precisely where this plugin stood one phase ago. The refusal is logged with its
 * reason and reported by {@code /tdungeons db} — never papered over, never fatal.
 */
public final class StorageService {

    /** How long shutdown waits for the queue to drain before giving up on it. */
    private static final long SHUTDOWN_SECONDS = 10;

    private final TakashiDungeonsPlugin plugin;

    private @Nullable ThreadPoolExecutor executor;
    private @Nullable Database database;
    private StorageSettings settings;
    private int schemaVersion;
    private boolean ready;
    private @Nullable String problem;

    public StorageService(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
        this.settings = StorageSettings.from(plugin.getConfig());
    }

    /**
     * Opens the connection and migrates the schema.
     *
     * @return {@code true} if the storage layer is usable; {@code false} leaves the plugin running
     *         without persistence and {@link #problem()} holding the reason
     */
    public boolean enable() {
        settings = StorageSettings.from(plugin.getConfig());
        if (settings.dialect() == null) {
            fail("storage.type is '" + plugin.getConfig().getString("storage.type")
                    + "', which is not a backend this plugin knows (valid: sqlite, mysql). "
                    + "Nothing is being saved - fix the value and restart.");
            return false;
        }

        database = new Database(settings, plugin.getDataFolder(), plugin.getLogger());
        try {
            Connection connection = database.connection();
            schemaVersion = new SchemaMigrator(plugin.getLogger()).migrate(connection,
                    settings.dialect());
        } catch (Throwable error) {
            // Throwable, not SQLException, and the distinction is not theoretical: a JDBC driver
            // whose native library will not load throws UnsatisfiedLinkError, which is an Error.
            // Caught as SQLException it escapes onEnable and takes the WHOLE PLUGIN down over a
            // feature the plugin is designed to run without. Measured, not guessed - it happened
            // on the first run of this layer.
            database.close();
            database = null;
            fail(settings.describe() + " could not be opened: " + error);
            return false;
        }

        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "TakashiDungeons-Storage");
                    // Not a daemon: a daemon thread is killed when the last player-facing thread
                    // stops, and the queue it was holding would go with it. Shutdown is explicit.
                    thread.setDaemon(false);
                    return thread;
                });
        ready = true;
        problem = null;
        plugin.getLogger().info("Storage: " + settings.describe() + " (schema v" + schemaVersion
                + ", flushing every " + settings.flushSeconds() + "s)");
        return true;
    }

    private void fail(String reason) {
        ready = false;
        problem = reason;
        plugin.getLogger().warning("Persistence is disabled: " + reason);
    }

    /** Drains the queue, then closes the connection. */
    public void disable() {
        ready = false;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                    // Saying how many is the point: "2 writes were dropped" is a bug report, while
                    // a silent shutdown is a mystery about missing data weeks later.
                    plugin.getLogger().warning("The storage queue did not drain within "
                            + SHUTDOWN_SECONDS + "s - " + executor.getQueue().size()
                            + " pending writes were dropped.");
                    executor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            executor = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
    }

    /**
     * Runs a piece of SQL on the storage thread.
     *
     * <p>The connection handed to the body is validated and, if it had died, freshly reopened. The
     * body must not hold on to it: the next call may be given a different one.
     */
    public <T> CompletableFuture<T> submit(SqlCall<T> work) {
        ThreadPoolExecutor running = executor;
        Database db = database;
        if (!ready || running == null || db == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Persistence is disabled: "
                            + (problem == null ? "the storage layer is not running" : problem)));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            running.execute(() -> {
                try {
                    future.complete(work.run(db.connection()));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RuntimeException rejected) {
            // The executor was shut down between the check above and here - a /reload racing a
            // player's quit. The write is lost and the caller is told, rather than the exception
            // surfacing from a thread nobody is watching.
            future.completeExceptionally(rejected);
        }
        return future;
    }

    /** {@link #submit} for work with nothing to return. */
    public CompletableFuture<Void> run(SqlRun work) {
        return submit(connection -> {
            work.run(connection);
            return null;
        });
    }

    /**
     * Waits for a future on the calling thread.
     *
     * <p>For shutdown only, where there is no later tick to come back on and the alternative is
     * dropping the write. Every other caller stays asynchronous.
     */
    public boolean awaitQuietly(CompletableFuture<?> future, long seconds) {
        try {
            future.get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception error) {
            plugin.getLogger().warning("A database write did not finish: " + error.getMessage());
            return false;
        }
    }

    /** Whether the storage layer is open for business. */
    public boolean isReady() {
        return ready;
    }

    /** Why persistence is off, or {@code null} when it is on. */
    public @Nullable String problem() {
        return problem;
    }

    public StorageSettings settings() {
        return settings;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    /** How many pieces of work are waiting for the storage thread — {@code /tdungeons db}. */
    public int queued() {
        return executor == null ? 0 : executor.getQueue().size();
    }

    /** Work that reads or writes and hands something back. */
    @FunctionalInterface
    public interface SqlCall<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Work that only writes. */
    @FunctionalInterface
    public interface SqlRun {
        void run(Connection connection) throws SQLException;
    }
}
