package com.takashi.dungeons.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * One connection, opened on demand and reopened when it has died.
 *
 * <h2>Why one connection and not a pool</h2>
 * Every statement this plugin issues is funnelled through {@link StorageService}'s single thread,
 * so a second connection could never be in use at the same time as the first. A pool would add a
 * dependency (HikariCP plus its logging facade), a shaded 150 KB, and a second set of timeouts to
 * tune — in exchange for parallelism that the design deliberately does not have. What a pool would
 * genuinely give is automatic revalidation of a connection that went stale overnight, and that is
 * what {@link #connection()} does in six lines.
 *
 * <h2>Why the driver is instantiated rather than looked up through {@code DriverManager}</h2>
 * {@code DriverManager} refuses drivers the <i>calling</i> class loader cannot see. A plugin's
 * class loader and the server's library loader are not the same loader, and MySQL's driver lives in
 * the latter — the lookup would fail on a server where the driver is plainly present. Constructing
 * the {@link Driver} and calling {@link Driver#connect} on it skips the question entirely.
 *
 * <p>SQLite's driver is referenced <b>as a class, not as a name string</b>. The shade plugin
 * relocates {@code org.sqlite} into {@code com.takashi.dungeons.libs.sqlite} so that a second
 * plugin shipping its own SQLite cannot collide with ours; a class reference is rewritten by that
 * relocation, a string built at runtime is not.
 */
public final class Database {

    /** Resolved reflectively: MySQL's driver is not a dependency of this project. */
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";

    private final StorageSettings settings;
    private final File dataFolder;
    private final Logger logger;

    private @Nullable Connection connection;

    public Database(StorageSettings settings, File dataFolder, Logger logger) {
        this.settings = settings;
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    public SqlDialect dialect() {
        return settings.dialect();
    }

    /**
     * The live connection, opened or reopened as needed.
     *
     * <p>Validated rather than assumed: MySQL closes idle connections after eight hours by default,
     * and a server that is quiet overnight would otherwise wake up to a stream of "communications
     * link failure" on the first player to log in.
     */
    public Connection connection() throws SQLException {
        if (connection != null && !connection.isClosed() && connection.isValid(2)) {
            return connection;
        }
        close();
        connection = open();
        return connection;
    }

    private Connection open() throws SQLException {
        Driver driver = driver();
        Properties properties = new Properties();
        String url;
        if (settings.dialect() == SqlDialect.MYSQL) {
            properties.setProperty("user", settings.username());
            properties.setProperty("password", settings.password());
            url = "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/"
                    + settings.database()
                    + (settings.properties().isBlank() ? "" : "?" + settings.properties());
        } else {
            File file = new File(dataFolder, settings.sqliteFile());
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new SQLException("Could not create the folder for " + file.getPath());
            }
            url = "jdbc:sqlite:" + file.getAbsolutePath();
        }

        Connection opened = driver.connect(url, properties);
        if (opened == null) {
            // Driver.connect answers null for a URL the driver does not recognise. Left unchecked
            // the next line would throw an NPE that says nothing about the cause.
            throw new SQLException("The driver did not accept the connection URL: " + url);
        }
        opened.setAutoCommit(true);
        if (settings.dialect() == SqlDialect.SQLITE) {
            applySqlitePragmas(opened);
        }
        return opened;
    }

    /**
     * Write-ahead logging and a busy timeout.
     *
     * <p>WAL is what keeps a write from blocking on the server's own file system flush; the busy
     * timeout is what keeps a lock held by something else — an operator with the file open in a
     * viewer, or a backup — from failing a write outright. Both are per-connection settings, so
     * they are reapplied every time the connection is reopened.
     */
    private void applySqlitePragmas(Connection opened) {
        try (Statement statement = opened.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException error) {
            // Not fatal: the database works without them, it merely works less well. Saying so is
            // better than refusing to start over a tuning statement.
            logger.warning("SQLite pragmas could not be applied: " + error.getMessage());
        }
    }

    private Driver driver() throws SQLException {
        if (settings.dialect() == SqlDialect.SQLITE) {
            return new org.sqlite.JDBC();
        }
        try {
            return (Driver) Class.forName(MYSQL_DRIVER).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException error) {
            throw new SQLException("The MySQL driver (" + MYSQL_DRIVER + ") is not on the "
                    + "classpath. Paper ships one; on a server that does not, either install a "
                    + "plugin that provides it or set storage.type to sqlite.", error);
        }
    }

    /** Closes the connection if one is open. Safe to call twice. */
    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException error) {
            logger.warning("The database connection did not close cleanly: " + error.getMessage());
        } finally {
            connection = null;
        }
    }
}
