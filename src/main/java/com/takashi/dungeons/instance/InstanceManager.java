package com.takashi.dungeons.instance;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.DungeonGenerator;
import com.takashi.dungeons.generation.DungeonSize;
import com.takashi.dungeons.generation.LayoutNode;
import com.takashi.dungeons.generation.PlacedRoom;
import com.takashi.dungeons.generation.RoomLibrary;
import com.takashi.dungeons.generation.RoomTemplateStore;
import com.takashi.dungeons.generation.Vec3i;
import com.takashi.dungeons.schematic.DoorPlugger;
import com.takashi.dungeons.schematic.RegionCleaner;
import com.takashi.dungeons.schematic.SchematicService;
import com.takashi.dungeons.world.GridSlot;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Creates, tracks and tears down dungeon instances.
 *
 * <h2>What this layer adds over phase 1</h2>
 * Phase 1 could paste a dungeon into a slot; nobody owned the result and nothing ever removed it.
 * A slot released by {@code GridSlotManager} kept its blocks, so the second party to be handed
 * that square walked into the previous party's dungeon. This class is the owner: one object per
 * live dungeon, and a teardown that runs in a fixed order.
 *
 * <h2>The teardown order is the whole design</h2>
 * <ol>
 *   <li><b>Players out</b> — a player still standing there when the floor is deleted falls
 *       through the void of a world that is about to be reused</li>
 *   <li><b>Entities out</b> — WorldEdit wipes blocks only; mobs, dropped items and item frames
 *       survive it and would greet the next dungeon in that slot</li>
 *   <li><b>Blocks wiped</b> — the union of the placed rooms, not the 512-block slot</li>
 *   <li><b>Chunks unloaded</b> — the memory is the point; an instance that closes without
 *       unloading leaves its chunks resident and the server's footprint only grows</li>
 *   <li><b>Slot released</b> — <b>last</b>. Released first, an allocation racing the wipe would
 *       get a square whose blocks are still being deleted</li>
 * </ol>
 *
 * <p><b>Nothing survives a restart.</b> Instances live in memory only; the dungeon world is
 * derived data and {@code DungeonWorldManager} resets it on start. Persistence is phase 7's
 * problem, and even then only four columns are needed — slot, theme, size, seed.
 */
public final class InstanceManager {

    private final TakashiDungeonsPlugin plugin;

    /** Live instances, insertion-ordered so listings read as "oldest first". */
    private final Map<Integer, DungeonInstance> instances = new LinkedHashMap<>();

    /**
     * Instance ids are never reused, unlike slot indices.
     *
     * <p>A slot is a place and may be handed out again; an instance is an event. Reusing the
     * number would make a log line about "instance#3" ambiguous between two different dungeons.
     */
    private int nextId = 1;

    /** The once-a-second tick driving expiry, warnings and the boss bar. */
    private BukkitTask clock;

    /** Players whose current teleport the plugin itself started — see {@link #teleportInternal}. */
    private final Set<UUID> internalTeleports = new HashSet<>();

    public InstanceManager(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ creation

    /**
     * Generates a dungeon and registers it as a live instance.
     *
     * <p>The instance is registered <b>only once it stands</b>. Publishing a half-pasted dungeon
     * would let a player be sent into rooms that do not exist yet; and on failure there would be
     * a registered instance whose teardown has to undo a teardown that never started. Until the
     * paste is done the only thing held is the slot, and the failure path returns it.
     */
    public CompletableFuture<DungeonInstance> create(String theme, DungeonSize size, long seed) {
        World world = plugin.getWorldManager() == null ? null : plugin.getWorldManager().getWorld();
        RoomTemplateStore store = plugin.getTemplateStore();
        SchematicService service = plugin.getSchematicService();
        if (world == null || store == null || service == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Dungeon dünyası ya da schematic servisi hazır değil."));
        }

        double turnBias = plugin.getConfig().getDouble("generation.turn-bias", 2.0);
        int maxAttempts = plugin.getConfig().getInt("generation.max-attempts", 8);
        boolean doPlug = plugin.getConfig().getBoolean("generation.plug-open-doors", true);

        GridSlot slot = plugin.getSlotManager().allocate();
        Aabb slotBounds = slot.bounds(world);
        Vec3i center = new Vec3i(slot.originX() + slot.size() / 2, slot.originY(),
                slot.originZ() + slot.size() / 2);

        return store.loadAll(store.list(theme))
                .thenApply(templates -> {
                    RoomLibrary library = new RoomLibrary(templates);
                    if (!library.isUsable()) {
                        throw new IllegalStateException(library.describeProblem());
                    }
                    return new DungeonGenerator(library, turnBias, maxAttempts)
                            .generate(slotBounds, center, size, seed);
                })
                .thenCompose(result -> paste(service, world, result).thenApply(ms -> result))
                .thenCompose(result -> plug(world, result, doPlug)
                        .thenApply(report -> register(slot, theme, result, report, world)))
                .whenComplete((instance, error) -> {
                    if (error != null) {
                        // Nothing was registered, so there is nothing to tear down — but the slot
                        // is held and would leak. Blocks may have been pasted before the failure;
                        // they are wiped so the slot goes back clean.
                        wipeAndRelease(world, slot, slotBounds);
                    }
                });
    }

    /** Pastes the rooms one after another — chained so the order stays deterministic. */
    private CompletableFuture<Long> paste(SchematicService service, World world,
                                          DungeonGenerator.Result result) {
        CompletableFuture<Long> chain = CompletableFuture.completedFuture(0L);
        for (LayoutNode node : result.layout().nodes()) {
            PlacedRoom room = node.room();
            chain = chain.thenCompose(ignored -> service.load(room.template().name())
                    .thenCompose(clip -> {
                        Vec3i o = room.origin();
                        return service.paste(clip, world, o.x(), o.y(), o.z(),
                                room.rotation().degrees(), false);
                    }));
        }
        return chain;
    }

    /**
     * Plugging — only AFTER the pastes are done. Done earlier, the next room's paste would
     * overwrite the plug; and whether a door is left open is only known once the graph is
     * complete.
     */
    private CompletableFuture<DoorPlugger.Report> plug(World world, DungeonGenerator.Result result,
                                                       boolean enabled) {
        DoorPlugger plugger = plugin.getDoorPlugger();
        if (!enabled || plugger == null || result.layout().isEmpty()) {
            return CompletableFuture.completedFuture(new DoorPlugger.Report(0, 0, 0, List.of()));
        }
        return plugger.plugAll(world, result.plugTargets());
    }

    private synchronized DungeonInstance register(GridSlot slot, String theme,
                                                  DungeonGenerator.Result result,
                                                  DoorPlugger.Report plugReport, World world) {
        long duration = plugin.getConfig().getLong("instance.duration-seconds", 1800) * 1000L;
        DungeonInstance instance = new DungeonInstance(nextId++, slot, theme, result, plugReport,
                DungeonInstance.boundsOf(result, slot, world), duration);
        instance.advanceTo(InstanceState.ACTIVE);
        instance.bossBar(createBossBar(instance));
        instances.put(instance.id(), instance);
        return instance;
    }

    /**
     * Cleanup for a build that never became an instance.
     *
     * <p>The whole slot is wiped rather than the rooms' union: the failure may have happened
     * mid-paste, so which rooms landed is exactly what is not known. This is the one path that
     * pays the cost of a full-slot clear, and it is the rare one.
     */
    private void wipeAndRelease(World world, GridSlot slot, Aabb slotBounds) {
        RegionCleaner cleaner = plugin.getRegionCleaner();
        if (cleaner == null) {
            plugin.getSlotManager().release(slot.index());
            return;
        }
        cleaner.clear(world, slotBounds).whenComplete((report, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Başarısız üretim sonrası slot temizlenemedi ("
                        + slot + "): " + error);
            }
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> plugin.getSlotManager().release(slot.index()));
        });
    }

    // ------------------------------------------------------------------ the clock

    /**
     * Starts the once-a-second tick that drives expiry, warnings and the boss bar.
     *
     * <p>One shared task rather than a timer per instance: the work per instance is a subtraction
     * and a bar title, and twenty scheduled tasks doing that would cost more in scheduling than
     * in work. A second is also the finest resolution that matters — the bar shows whole seconds.
     */
    public void startClock() {
        if (clock != null) {
            return;
        }
        clock = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stopClock() {
        if (clock != null) {
            clock.cancel();
            clock = null;
        }
    }

    private void tick() {
        long emptyTimeout = plugin.getConfig().getLong("instance.empty-timeout-seconds", 300) * 1000L;
        for (DungeonInstance instance : all()) {
            if (!instance.isActive()) {
                continue;
            }
            if (instance.isExpired()) {
                expire(instance, "Süre doldu");
                continue;
            }
            // An instance nobody is in is holding a slot for nothing. Only armed once somebody
            // has been inside: a dungeon an operator generated to look at has been empty since
            // birth, and closing it on that basis would delete the rooms they are standing in.
            if (emptyTimeout > 0 && instance.everOccupied() && instance.playerCount() == 0
                    && instance.emptyMillis() >= emptyTimeout) {
                expire(instance, "İçeride kimse kalmadı");
                continue;
            }
            updateBossBar(instance);
            warnIfDue(instance);
        }
    }

    /** Sends everyone home, then tears the instance down. */
    private void expire(DungeonInstance instance, String reason) {
        for (UUID uuid : instance.players()) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.sendMessage(Component.text(reason + " — dungeon kapanıyor.",
                        NamedTextColor.YELLOW));
            }
        }
        // Players first, and to THEIR OWN return location — close() only knows the generic way
        // out and would drop everyone at world spawn.
        sendEveryoneHome(instance);
        close(instance).exceptionally(error -> {
            plugin.getLogger().warning("instance#" + instance.id()
                    + " süre bitiminde kapatılamadı: " + error);
            return null;
        });
    }

    private void sendEveryoneHome(DungeonInstance instance) {
        for (UUID uuid : instance.players()) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                leave(player, instance);
            } else {
                // Offline: nothing to teleport. Deregistering is still needed so the instance
                // does not count them, and the join safety net will move them when they return.
                instance.removePlayer(uuid);
            }
        }
    }

    // ------------------------------------------------------------------ boss bar

    private BossBar createBossBar(DungeonInstance instance) {
        if (!plugin.getConfig().getBoolean("instance.boss-bar.enabled", true)) {
            return null;
        }
        BossBar bar = BossBar.bossBar(barTitle(instance), 1.0f,
                BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        return bar;
    }

    /**
     * Repaints the countdown.
     *
     * <p>The bar drains rather than fills, and the colour follows the fraction left — blue, then
     * yellow under a quarter, then red under a tenth. The colour is the part a player reads
     * without looking: the number tells them how long, the colour tells them whether to care.
     */
    private void updateBossBar(DungeonInstance instance) {
        BossBar bar = instance.bossBar();
        if (bar == null) {
            return;
        }
        float fraction = Math.max(0f, Math.min(1f,
                (float) instance.remainingMillis() / instance.totalMillis()));
        bar.progress(fraction);
        bar.name(barTitle(instance));
        BossBar.Color color = fraction <= 0.10f ? BossBar.Color.RED
                : fraction <= 0.25f ? BossBar.Color.YELLOW
                : BossBar.Color.BLUE;
        if (bar.color() != color) {
            bar.color(color);
        }
    }

    private Component barTitle(DungeonInstance instance) {
        String format = plugin.getConfig().getString("instance.boss-bar.title",
                "<gold>Dungeon</gold> <dark_gray>|</dark_gray> <white><time></white>");
        try {
            return MiniMessage.miniMessage().deserialize(format,
                    Placeholder.unparsed("time", formatDuration(instance.remainingMillis())),
                    Placeholder.unparsed("theme", instance.theme()),
                    Placeholder.unparsed("size", instance.result().size().key()),
                    Placeholder.unparsed("rooms", String.valueOf(instance.result().rooms())));
        } catch (RuntimeException e) {
            // The title is repainted every second; a broken tag must not fill the console once
            // per second per instance. Fall back to plain text and carry on.
            return Component.text("Dungeon | " + formatDuration(instance.remainingMillis()),
                    NamedTextColor.GOLD);
        }
    }

    /** {@code mm:ss}, or {@code h:mm:ss} once an hour is involved. */
    public static String formatDuration(long millis) {
        long total = millis / 1000L;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
    }

    /**
     * Announces the configured thresholds, each exactly once.
     *
     * <p>Fires when the remaining time drops <b>below</b> the threshold rather than equals it: a
     * one-second tick can be late, and an equality test would silently skip the warning whenever
     * the server hitched.
     */
    private void warnIfDue(DungeonInstance instance) {
        if (instance.playerCount() == 0) {
            return;
        }
        long remainingSeconds = instance.remainingMillis() / 1000L;
        for (int threshold : plugin.getConfig().getIntegerList("instance.warn-seconds")) {
            if (remainingSeconds > threshold || !instance.markWarned(threshold)) {
                continue;
            }
            broadcast(instance, Component.text("Dungeon " + formatDuration(threshold * 1000L)
                    + " içinde kapanacak.", NamedTextColor.YELLOW));
        }
    }

    /** Takes every countdown bar off every screen — for shutdown and reload. */
    public void hideAllBars() {
        for (DungeonInstance instance : all()) {
            for (UUID uuid : instance.players()) {
                Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    instance.hideBar(player);
                }
            }
        }
    }

    private void broadcast(DungeonInstance instance, Component message) {
        for (UUID uuid : instance.players()) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    // ------------------------------------------------------------------ entering and leaving

    /**
     * Puts a player inside: registers them, remembers where they came from, teleports them to the
     * entrance and shows them the countdown.
     *
     * @return {@code false} if the instance cannot be entered
     */
    public boolean enter(Player player, DungeonInstance instance) {
        if (!instance.isActive()) {
            return false;
        }
        World world = plugin.getWorldManager().getWorld();
        Location spawn = world == null ? null : instance.entranceSpawn(world);
        if (spawn == null) {
            return false;
        }
        // Leave whatever they were in first — being a member of two dungeons would send the
        // expiry of either one after them.
        DungeonInstance current = instanceOf(player);
        if (current != null && current != instance) {
            leave(player, current);
        }
        instance.addPlayer(player.getUniqueId(), player.getLocation().clone());
        teleportInternal(player, spawn);
        instance.showBar(player);
        return true;
    }

    /** Takes a player out and sends them back where they came from. */
    public boolean leave(Player player, DungeonInstance instance) {
        if (!instance.removePlayer(player.getUniqueId())) {
            return false;
        }
        instance.hideBar(player);
        Location home = instance.returnLocation(player.getUniqueId());
        teleportInternal(player, home == null ? fallbackExit() : home);
        return true;
    }

    /** The instance a player is registered in, or {@code null}. */
    public synchronized @Nullable DungeonInstance instanceOf(Player player) {
        for (DungeonInstance instance : instances.values()) {
            if (instance.contains(player.getUniqueId())) {
                return instance;
            }
        }
        return null;
    }

    /**
     * Deregisters a player without teleporting them.
     *
     * <p>For the paths where the player is already elsewhere — they quit, or they walked out of
     * the dungeon world on their own. Teleporting them "home" there would be the plugin yanking
     * somebody who never asked.
     */
    public void forget(Player player) {
        DungeonInstance instance = instanceOf(player);
        if (instance != null) {
            instance.removePlayer(player.getUniqueId());
            instance.hideBar(player);
        }
    }

    // ------------------------------------------------------------------ teleport marking

    /**
     * Teleports on the plugin's own behalf, past the teleport block.
     *
     * <p>The block in {@link InstanceListener} cancels {@code PLUGIN}-caused teleports in and out
     * of the dungeon world, and our own moves have exactly that cause. Rather than let the rule
     * guess whose teleport it is looking at, every internal move is announced here for the length
     * of the call. A time window is not used: the event fires inside {@code teleport()}, so the
     * mark is set and cleared around a synchronous call and cannot leak into the next tick.
     */
    public void teleportInternal(Player player, Location target) {
        UUID uuid = player.getUniqueId();
        internalTeleports.add(uuid);
        try {
            player.teleport(target);
        } finally {
            internalTeleports.remove(uuid);
        }
    }

    /** Whether this player's in-flight teleport was started by the plugin itself. */
    boolean isInternalTeleport(UUID uuid) {
        return internalTeleports.contains(uuid);
    }

    // ------------------------------------------------------------------ teardown

    /** What a close actually did. */
    public record CloseReport(int id, int playersEvicted, int entitiesRemoved, int blocksCleared,
                              int chunksUnloaded, long millis) {
    }

    /**
     * Tears the instance down and returns its slot to the pool.
     *
     * <p>Fails rather than no-ops when the instance is not {@code ACTIVE}: a second close landing
     * in the middle of the first would release the slot twice, and the pool would hand the same
     * square to two parties. The caller sees an error instead of a silent double-free.
     */
    public CompletableFuture<CloseReport> close(DungeonInstance instance) {
        if (!instance.advanceTo(InstanceState.CLOSING)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "instance#" + instance.id() + " kapatılabilir durumda değil: "
                            + instance.state()));
        }
        World world = plugin.getWorldManager().getWorld();
        RegionCleaner cleaner = plugin.getRegionCleaner();
        if (world == null || cleaner == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Temizlik için dünya ya da WorldEdit yok — instance kapatılamıyor."));
        }

        long start = System.currentTimeMillis();
        CompletableFuture<int[]> evicted = onMainThread(() -> {
            // Registered members go to their own return location; the positional sweep that
            // follows is for anyone standing in the slot without being a member — an operator
            // who walked in, or a player mid-fall.
            int home = instance.playerCount();
            sendEveryoneHome(instance);
            return new int[]{home + evictPlayers(world, instance), removeEntities(world, instance)};
        });

        return evicted
                .thenCompose(counts -> cleaner.clear(world, instance.bounds())
                        .thenApply(report -> new int[]{counts[0], counts[1], report.blocks()}))
                .thenCompose(counts -> onMainThread(() -> {
                    int chunks = unloadChunks(world, instance);
                    plugin.getSlotManager().release(instance.slot().index());
                    unregister(instance);
                    instance.advanceTo(InstanceState.CLOSED);
                    return new CloseReport(instance.id(), counts[0], counts[1], counts[2], chunks,
                            System.currentTimeMillis() - start);
                }));
    }

    /** Closes every live instance; the returned future settles when the last one is done. */
    public CompletableFuture<Void> closeAll() {
        List<DungeonInstance> live = all();
        CompletableFuture<?>[] futures = live.stream()
                .map(instance -> close(instance).exceptionally(error -> {
                    plugin.getLogger().warning("instance#" + instance.id()
                            + " kapatılamadı: " + error);
                    return null;
                }))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    /**
     * Sends every player standing in the slot back out.
     *
     * <p>Phase 2B replaces the destination with the entry object's location — where the player
     * came in is where they belong when the dungeon expires. Until then the main world's spawn is
     * the honest answer: anywhere is better than a floor that is one tick from being deleted.
     */
    private int evictPlayers(World world, DungeonInstance instance) {
        Location exit = fallbackExit();
        int count = 0;
        for (Player player : world.getPlayers()) {
            Location loc = player.getLocation();
            if (!instance.slot().contains(loc.getX(), loc.getZ())) {
                continue;
            }
            instance.hideBar(player);
            teleportInternal(player, exit);
            count++;
        }
        return count;
    }

    /**
     * The way out while there is no entry object yet — the first world that is not the dungeon
     * world. Index 0 is the main world on every server, but the check is by name: an operator who
     * makes the dungeon world their level-name would otherwise be teleported into the very slot
     * being wiped.
     */
    public Location fallbackExit() {
        String dungeonWorld = plugin.getWorldManager().getWorldName();
        for (World w : plugin.getServer().getWorlds()) {
            if (!w.getName().equals(dungeonWorld)) {
                return w.getSpawnLocation();
            }
        }
        return plugin.getServer().getWorlds().get(0).getSpawnLocation();
    }

    /**
     * Removes everything the block wipe would leave behind.
     *
     * <p><b>The chunks are loaded first, and that is the whole point.</b> FAWE does not leave the
     * chunks it wrote resident — the same fact that makes {@code execute if block} need a
     * {@code forceload} in manual testing. An entity search only sees loaded chunks, so without
     * this the mobs of a closed dungeon would stay asleep in the region files and wake up inside
     * the next party's dungeon. Loading is not extra work either: the wipe that follows has to
     * load exactly these chunks anyway, and {@link #unloadChunks} then has something real to
     * release.
     *
     * <p>Players are excluded — they were teleported out a step earlier, and anyone who slipped
     * in since is a bug to notice, not an entity to delete.
     */
    private int removeEntities(World world, DungeonInstance instance) {
        Aabb box = instance.bounds();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                world.getChunkAt(cx, cz);
            }
        }
        BoundingBox region = new BoundingBox(
                box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0);
        Collection<Entity> found = world.getNearbyEntities(region, e -> !(e instanceof Player));
        found.forEach(Entity::remove);
        return found.size();
    }

    /**
     * Releases the chunks the instance covered.
     *
     * <p><b>The return value is deliberately ignored.</b> Paper's chunk system is ticket-based:
     * {@code unloadChunk} is a request, it answers {@code false} whenever something still holds a
     * ticket — including the one the entity sweep just took — and the chunk is dropped when the
     * last holder lets go. Counting only the calls that returned {@code true} therefore reported
     * a flat zero on every close while the chunks did in fact come out of memory. What is counted
     * is what was asked for, which is the part this method controls.
     *
     * <p>Saved rather than dropped: with {@code reset-on-start} off, dropping them would leave
     * the pre-wipe blocks on disk and the deleted dungeon would come back on the next load. The
     * chunks are pure air by this point, so the write is cheap.
     */
    private int unloadChunks(World world, DungeonInstance instance) {
        Aabb box = instance.bounds();
        int count = 0;
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(cx, cz);
                // A forceloaded chunk was pinned by the operator (the block-verification trap in
                // CLAUDE.md); silently unpinning it would break their diagnosis.
                if (chunk.isForceLoaded()) {
                    continue;
                }
                world.unloadChunk(cx, cz, true);
                count++;
            }
        }
        return count;
    }

    private synchronized void unregister(DungeonInstance instance) {
        instances.remove(instance.id());
    }

    // ------------------------------------------------------------------ lookup

    public synchronized List<DungeonInstance> all() {
        return new ArrayList<>(instances.values());
    }

    public synchronized int count() {
        return instances.size();
    }

    public synchronized @Nullable DungeonInstance get(int id) {
        return instances.get(id);
    }

    /**
     * The instance a location falls into, or {@code null}.
     *
     * <p>Matched on the <b>slot</b>, not the rooms' bounds: a player who fell off a walkway is
     * still inside that dungeon and must be treated as such — by the teleport block, by the
     * expiry eviction, and later by the party system.
     */
    public synchronized @Nullable DungeonInstance instanceAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        if (!location.getWorld().getName().equals(plugin.getWorldManager().getWorldName())) {
            return null;
        }
        for (DungeonInstance instance : instances.values()) {
            if (instance.slot().contains(location.getX(), location.getZ())) {
                return instance;
            }
        }
        return null;
    }

    /** Runs the body on the main thread and hands back its result. */
    private <T> CompletableFuture<T> onMainThread(java.util.function.Supplier<T> body) {
        if (plugin.getServer().isPrimaryThread()) {
            return CompletableFuture.completedFuture(body.get());
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                future.complete(body.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
