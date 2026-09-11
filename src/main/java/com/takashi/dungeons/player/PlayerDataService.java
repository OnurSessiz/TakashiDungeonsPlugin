package com.takashi.dungeons.player;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.instance.DungeonInstance;
import com.takashi.dungeons.mob.MobKill;
import com.takashi.dungeons.storage.SqlDialect;
import com.takashi.dungeons.storage.StorageService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Who a player is, as far as this plugin is concerned, and when that is read and written.
 *
 * <h2>The rule that shapes everything here</h2>
 * A profile is <b>always</b> handed out, whether or not the database answered. Callers — the HUD,
 * above all — never branch on "is persistence working"; they read a setting and get one. When the
 * storage layer is off, the profile is a plain in-memory object and the session behaves exactly as
 * this plugin did before phase 7. That is what keeps a failed database from becoming a second code
 * path nobody tests.
 *
 * <h2>Where the reads and writes happen</h2>
 * <ul>
 *   <li><b>Load</b> at {@code AsyncPlayerPreLoginEvent} — already off the main thread, and early
 *       enough that the first sidebar tick after the join has the real setting. A load on join
 *       would paint the default for one frame and then correct itself, which reads as a bug.</li>
 *   <li><b>Settings</b> are written the moment they change. A toggle is a rare event and a tiny
 *       row; batching it would trade nothing for the chance of losing it in a crash.</li>
 *   <li><b>Counters</b> are batched. A party clearing a dungeon produces hundreds of kills, and a
 *       row per kill would turn a fight into a write storm for numbers nobody reads mid-fight.</li>
 *   <li><b>Flush</b> on quit, on the interval, and on shutdown.</li>
 * </ul>
 *
 * <h2>Ordering is free</h2>
 * Every statement goes through {@link StorageService}'s one thread in submission order, so a quit's
 * flush cannot overtake — or be overtaken by — the load of the same player reconnecting a second
 * later. There is no lock here for that, because there is no race to lock against.
 */
public final class PlayerDataService {

    /**
     * How long a login waits for its profile.
     *
     * <p>The login thread is the right place to wait — the player is looking at a connecting screen
     * either way — but not the right place to wait forever. Past this the player is let in with an
     * unloaded profile, which costs them their settings for that session and costs the server
     * nothing.
     */
    private static final long LOGIN_TIMEOUT_SECONDS = 5;

    /** How long shutdown gives the final flush. */
    private static final long SHUTDOWN_FLUSH_SECONDS = 10;

    private final TakashiDungeonsPlugin plugin;
    private final StorageService storage;
    private final @Nullable PlayerDataRepository repository;

    /** Online players, plus anyone whose flush has not finished yet. */
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();

    /**
     * Which players have already been counted as having entered which instance.
     *
     * <p>Per instance, not per player: re-entering the same dungeon after a death or a reconnect is
     * the same run, while the next dungeon is a new one. Dropped when the instance closes, which is
     * also what keeps this map from growing for the lifetime of the server.
     */
    private final Map<Integer, Set<UUID>> counted = new ConcurrentHashMap<>();

    /** When each player currently inside an instance last had their time settled. */
    private final Map<UUID, Long> insideSince = new ConcurrentHashMap<>();

    private @Nullable BukkitTask flushTask;

    public PlayerDataService(TakashiDungeonsPlugin plugin, StorageService storage) {
        this.plugin = plugin;
        this.storage = storage;
        SqlDialect dialect = storage.settings().dialect();
        this.repository = storage.isReady() && dialect != null
                ? new PlayerDataRepository(dialect) : null;
    }

    /** Whether anything written here will outlive the session. */
    public boolean isPersistent() {
        return repository != null && storage.isReady();
    }

    public void enable() {
        int seconds = storage.settings().flushSeconds();
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushTick,
                seconds * 20L, seconds * 20L);
        // Anyone already online is a /reload: their profile was dropped with the old service and
        // has to be read again, or their next toggle would be written on top of defaults.
        for (Player player : Bukkit.getOnlinePlayers()) {
            handleJoin(player);
        }
    }

    /**
     * Settles the clock for everyone still inside, then writes everything, waiting for it.
     *
     * <p>Waiting is the point: this is the last moment the data exists. A shutdown that returns
     * before the queue drains is a shutdown that loses the session — which is the one thing phase 7
     * was added to stop.
     */
    public void disable() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        for (UUID uuid : Set.copyOf(insideSince.keySet())) {
            settleTime(uuid);
        }
        if (!isPersistent()) {
            profiles.clear();
            insideSince.clear();
            counted.clear();
            return;
        }
        CompletableFuture<?>[] writes = profiles.values().stream()
                .map(this::flush)
                .toArray(CompletableFuture[]::new);
        storage.awaitQuietly(CompletableFuture.allOf(writes), SHUTDOWN_FLUSH_SECONDS);
        profiles.clear();
        insideSince.clear();
        counted.clear();
    }

    // ------------------------------------------------------------------ the cache

    /**
     * This player's profile, creating an unloaded one if the login never got to read it.
     *
     * <p>Never {@code null}: see the class note. An unloaded profile answers with the config
     * defaults and is never written back.
     */
    public PlayerProfile profile(Player player) {
        return profiles.computeIfAbsent(player.getUniqueId(),
                uuid -> new PlayerProfile(uuid, player.getName()));
    }

    /** The cached profile, or {@code null} for a player who is not online. */
    public @Nullable PlayerProfile cached(UUID uuid) {
        return profiles.get(uuid);
    }

    /**
     * Reads a player's rows on the login thread.
     *
     * <p>Called from {@code AsyncPlayerPreLoginEvent}. A failure is <b>not</b> a reason to refuse
     * the login: the server is playable without a saved HUD setting, and a player kicked at the
     * door by a database hiccup is a far worse outcome than a session of defaults.
     */
    public void preload(UUID uuid, String name) {
        PlayerProfile profile = profiles.computeIfAbsent(uuid, id -> new PlayerProfile(id, name));
        profile.name(name);
        if (!isPersistent()) {
            return;
        }
        CompletableFuture<Void> load = storage.submit(connection ->
                        repository.load(connection, uuid))
                .thenAccept(row -> profile.loaded(row.hud(), row.partyHud(),
                        row.exists() ? row.firstSeen() : System.currentTimeMillis(), row.stats()))
                .exceptionally(error -> {
                    profile.loadFailed();
                    plugin.getLogger().warning("Player data for " + name + " could not be read - "
                            + "this session's settings will not be saved: " + rootMessage(error));
                    return null;
                });
        storage.awaitQuietly(load, LOGIN_TIMEOUT_SECONDS);
    }

    /** Fills in for a login the preload missed — a {@code /reload}, or a timed-out read. */
    public void handleJoin(Player player) {
        PlayerProfile profile = profile(player);
        profile.name(player.getName());
        if (!isPersistent() || profile.isPersisted()) {
            // Already loaded, or there is nothing to load from. Either way the row has to be
            // touched so last_seen moves and a first-time player gets one.
            flush(profile);
            return;
        }
        UUID uuid = player.getUniqueId();
        storage.submit(connection -> repository.load(connection, uuid))
                .thenAccept(row -> {
                    profile.loaded(row.hud(), row.partyHud(),
                            row.exists() ? row.firstSeen() : System.currentTimeMillis(),
                            row.stats());
                    flush(profile);
                    // The sidebar was painted from the defaults a moment ago; repaint it now that
                    // the player's own choice has arrived.
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline() && plugin.getHudService() != null) {
                            plugin.getHudService().refresh(Set.of(uuid));
                        }
                    });
                })
                .exceptionally(error -> {
                    profile.loadFailed();
                    plugin.getLogger().warning("Player data for " + player.getName()
                            + " could not be read: " + rootMessage(error));
                    return null;
                });
    }

    /** Writes what this player accumulated, then lets go of them. */
    public void handleQuit(Player player) {
        UUID uuid = player.getUniqueId();
        settleTime(uuid);
        PlayerProfile profile = profiles.remove(uuid);
        if (profile != null) {
            flush(profile);
        }
    }

    // ------------------------------------------------------------------ writing

    /**
     * Sends this profile's changes to the database.
     *
     * <p>The deltas are taken out <b>before</b> the write is submitted, so kills that land while it
     * is in flight belong to the next flush rather than being wiped by this one. If the write
     * fails they are put back and the next flush tries again.
     */
    public CompletableFuture<Void> flush(PlayerProfile profile) {
        if (!isPersistent() || !profile.isPersisted()) {
            // Not persisted means the load failed: writing now would replace the player's real row
            // with the defaults this session has been running on.
            return CompletableFuture.completedFuture(null);
        }
        boolean settings = profile.isSettingsDirty();
        PlayerStats delta = profile.drain();
        if (!settings && delta.isZero()) {
            return CompletableFuture.completedFuture(null);
        }
        if (settings) {
            profile.settingsWritten();
        }
        return storage.run(connection -> {
            if (settings) {
                repository.saveSettings(connection, profile.uuid(), profile.name(), profile.hud(),
                        profile.partyHud(), profile.firstSeen());
            }
            if (!delta.isZero()) {
                repository.addStats(connection, profile.uuid(), delta);
            }
        }).exceptionally(error -> {
            if (settings) {
                profile.settingsFailed();
            }
            profile.restore(delta);
            plugin.getLogger().warning("Player data for " + profile.name()
                    + " could not be saved: " + rootMessage(error));
            return null;
        });
    }

    /** Writes every cached profile. Returns how many had something to write. */
    public int flushAll() {
        int written = 0;
        for (PlayerProfile profile : profiles.values()) {
            if (profile.isSettingsDirty() || !profile.stats().isZero()) {
                written++;
            }
            flush(profile);
        }
        return written;
    }

    private void flushTick() {
        accrueTime();
        for (PlayerProfile profile : profiles.values()) {
            flush(profile);
        }
    }

    // ------------------------------------------------------------------ the instance hooks

    /**
     * A player stepped into a dungeon.
     *
     * <p>Hung off {@code InstanceManager.onEnter} rather than called from it, the way every other
     * cross-layer signal in this plugin is: the instance layer publishes a fact and does not know
     * that anybody is counting.
     */
    public void onEnter(DungeonInstance instance, Player player) {
        UUID uuid = player.getUniqueId();
        insideSince.put(uuid, System.currentTimeMillis());
        boolean first = counted.computeIfAbsent(instance.id(),
                id -> ConcurrentHashMap.newKeySet()).add(uuid);
        if (first) {
            profile(player).add(PlayerStats.entry());
        }
    }

    /** A player stopped being a member — they left, quit, or the dungeon closed under them. */
    public void onExit(DungeonInstance instance, UUID uuid) {
        settleTime(uuid);
    }

    /**
     * The boss died. Everyone inside at that moment gets the clear.
     *
     * <p>Inside <b>at that moment</b>, not everyone who ever entered: a player who left before the
     * fight did not clear the dungeon, and one who joined for the last hit did. That is the same
     * rule the reward chest already follows.
     */
    public void onCleared(DungeonInstance instance) {
        for (UUID uuid : instance.players()) {
            PlayerProfile profile = profiles.get(uuid);
            if (profile != null) {
                profile.add(PlayerStats.clear());
            }
        }
    }

    /** A dungeon mob died — the same {@code MobService.onKill} seam loot and clearing use. */
    public void onKill(MobKill kill) {
        Player killer = kill.killer();
        if (killer == null) {
            // Fall damage, fire, another mob. The kill is real but it belongs to nobody, and
            // crediting the nearest player would be inventing a fact.
            return;
        }
        profile(killer).add(kill.boss() ? PlayerStats.bossKill() : PlayerStats.mobKill());
    }

    /** A player died inside a dungeon. */
    public void onDeath(Player player) {
        profile(player).add(PlayerStats.death());
    }

    /** The instance is gone; stop remembering who had been counted for it. */
    public void onInstanceClosed(int instanceId) {
        counted.remove(instanceId);
    }

    // ------------------------------------------------------------------ time inside

    /**
     * Moves each player's time-inside watermark forward.
     *
     * <p>Run on every flush rather than only when they leave, so a crash or a kill -9 costs at most
     * one interval of playtime instead of the whole run. The remainder below a second is left on
     * the watermark rather than rounded away — otherwise a one-minute flush would lose up to a
     * second per flush, which over a long session is minutes.
     */
    private void accrueTime() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : insideSince.entrySet()) {
            long seconds = (now - entry.getValue()) / 1000L;
            if (seconds <= 0) {
                continue;
            }
            PlayerProfile profile = profiles.get(entry.getKey());
            if (profile != null) {
                profile.add(PlayerStats.seconds(seconds));
            }
            entry.setValue(entry.getValue() + seconds * 1000L);
        }
    }

    private void settleTime(UUID uuid) {
        Long since = insideSince.remove(uuid);
        if (since == null) {
            return;
        }
        long seconds = (System.currentTimeMillis() - since) / 1000L;
        PlayerProfile profile = profiles.get(uuid);
        if (seconds > 0 && profile != null) {
            profile.add(PlayerStats.seconds(seconds));
        }
    }

    // ------------------------------------------------------------------ reading, for the command

    /**
     * Looks a player up by name for {@code /tdungeons stats}.
     *
     * <p>Online players are answered from the cache, including the counters that have not been
     * flushed yet — an operator who kills ten mobs and immediately checks the number would
     * otherwise be shown a total that is a flush interval out of date and conclude it is broken.
     */
    public CompletableFuture<@Nullable PlayerStats> lookup(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(profile(online).stats());
        }
        if (!isPersistent()) {
            return CompletableFuture.completedFuture(null);
        }
        return storage.submit(connection -> {
            UUID uuid = repository.findByName(connection, name);
            return uuid == null ? null : repository.load(connection, uuid).stats();
        });
    }

    /** How many players the database has a row for. */
    public CompletableFuture<Long> countPlayers() {
        if (!isPersistent()) {
            return CompletableFuture.completedFuture(0L);
        }
        return storage.submit(repository::countPlayers);
    }

    /** How many profiles are held in memory right now. */
    public int cachedCount() {
        return profiles.size();
    }

    /** Futures wrap their cause; the wrapper's message is never the one worth printing. */
    private static String rootMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
