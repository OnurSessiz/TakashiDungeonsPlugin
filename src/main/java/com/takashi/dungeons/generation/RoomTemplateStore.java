package com.takashi.dungeons.generation;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.takashi.dungeons.schematic.SchematicService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Loads room templates: the {@code .schem} (geometry) plus the {@code .yml} beside it
 * (metadata).
 *
 * <p><b>Two sources, one object.</b> The box comes from the schematic, the door anchors from
 * the {@code .yml}, and the wall facing is derived from the combination
 * ({@code generation.md} §4). With no metadata file, the room counts as a doorless NORMAL room:
 * it loads but cannot be connected to the graph. It is not skipped silently — it shows up as
 * "doorless" in the {@code /tdungeons rooms} listing.
 *
 * <p><b>Everything is async.</b> Both NBT parsing and YAML reading are disk I/O; done on the
 * main thread they would freeze the server during dungeon generation.
 */
public final class RoomTemplateStore {

    private final Plugin plugin;
    private final SchematicService schematics;

    /** Name (lower-cased) → loaded template. */
    private final Map<String, CompletableFuture<RoomTemplate>> cache = new ConcurrentHashMap<>();

    /**
     * An explicit executor, so metadata reading never falls back onto the main thread.
     *
     * <p>It is necessary because when {@code SchematicService.load} returns from its cache, the
     * future comes back <b>already completed</b>; a plain {@code thenApply} would then run on
     * the caller's thread — the main thread — and read the YAML file there. The kind of trap
     * that only appears on the second load, never the first.
     */
    private final Executor async;

    public RoomTemplateStore(Plugin plugin, SchematicService schematics) {
        this.plugin = plugin;
        this.schematics = schematics;
        this.async = task -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    /** Every room key across every theme, with or without metadata. */
    public List<String> list() {
        return schematics.list();
    }

    /** One theme's room keys. A dungeon is generated from exactly one theme's list. */
    public List<String> list(String theme) {
        return schematics.list(theme);
    }

    /** The themes that hold at least one schematic. */
    public List<String> themes() {
        return schematics.themes();
    }

    public void invalidateCache() {
        cache.clear();
    }

    /**
     * Loads a template; a second call for the same name comes from the cache.
     *
     * @param name file name without extension
     */
    public CompletableFuture<RoomTemplate> load(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        CompletableFuture<RoomTemplate> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        // The future goes into the cache FIRST and the chain is built after. The other order
        // (chaining inside computeIfAbsent) has a hole: if the executor rejects the task —
        // which happens while the plugin is being disabled — the chain fails synchronously and
        // the cleanup runs before computeIfAbsent has inserted anything, so the failed future
        // lands in the cache afterwards and stays there. This order has no such window.
        CompletableFuture<RoomTemplate> created = new CompletableFuture<>();
        CompletableFuture<RoomTemplate> raced = cache.putIfAbsent(key, created);
        if (raced != null) {
            return raced;
        }

        schematics.load(key)
                .thenApplyAsync(clipboard -> build(key, clipboard), async)
                .whenComplete((template, error) -> {
                    if (error != null) {
                        // Don't leave a failed load in the cache: once the file is fixed it
                        // should be retried. The two-argument remove is deliberate — if
                        // invalidateCache() ran in the meantime and someone installed a new
                        // future, don't delete theirs.
                        cache.remove(key, created);
                        created.completeExceptionally(error);
                    } else {
                        created.complete(template);
                    }
                });
        return created;
    }

    /** Loads all the given names in parallel; if one fails, the result fails. */
    public CompletableFuture<List<RoomTemplate>> loadAll(List<String> names) {
        List<CompletableFuture<RoomTemplate>> futures = names.stream().map(this::load).toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    /** The template's metadata file — beside its schematic, inside its own theme's folder. */
    public File metadataFile(String key) {
        return new File(schematics.directoryFor(SchematicService.themeOf(key)),
                SchematicService.nameOf(key) + ".yml");
    }

    private RoomTemplate build(String name, Clipboard clipboard) {
        Aabb localBox = localBoxOf(clipboard);
        RoomMetadata metadata = readMetadata(name);

        List<DoorAnchor> doors = new ArrayList<>(metadata.doorLocal().size());
        for (int i = 0; i < metadata.doorLocal().size(); i++) {
            Vec3i local = metadata.doorLocal().get(i);
            if (!localBox.contains(local)) {
                throw new IllegalArgumentException(name + ": doors[" + i + "] " + local
                        + " is outside the room. Room box (relative to origin): " + localBox
                        + ". Anchors are written relative to the ORIGIN, not to the schematic's"
                        + " corner.");
            }
            DoorAnchor door = DoorAnchor.of(i, local, localBox);
            warnIfNotOnWall(name, door, localBox);
            doors.add(door);
        }

        return new RoomTemplate(name, metadata.type(), metadata.weight(), doors, localBox);
    }

    /**
     * Warns when an anchor is not on the surface of the wall it was derived from.
     *
     * <p>It does not throw: a recessed door can be a deliberate design choice. But as
     * {@code generation.md} §9 puts it, "this is the single place where these systems break" —
     * an anchor off by one block makes walls interpenetrate. The warning catches the mistake at
     * load time instead of leaving it to be spotted by eye after the paste.
     */
    private void warnIfNotOnWall(String name, DoorAnchor door, Aabb box) {
        Vec3i local = door.local();
        int actual = switch (door.wall()) {
            case NORTH, SOUTH -> local.z();
            case EAST, WEST -> local.x();
        };
        int expected = switch (door.wall()) {
            case NORTH -> box.minZ();
            case SOUTH -> box.maxZ();
            case WEST -> box.minX();
            case EAST -> box.maxX();
        };
        if (actual != expected) {
            plugin.getLogger().warning(name + ": " + door + " duvarın yüzeyinde değil — "
                    + door.wall().displayName() + " duvarı " + expected + ", anchor " + actual
                    + " (" + Math.abs(actual - expected) + " blok içeride). Bilerek yapılmadıysa "
                    + "bağlanan odalar arasında boşluk kalır.");
        }
    }

    /** {@link RoomMetadata#DEFAULT} if there is no metadata file; otherwise parsed, or thrown. */
    private RoomMetadata readMetadata(String name) {
        File file = metadataFile(name);
        if (!file.isFile()) {
            return RoomMetadata.DEFAULT;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return RoomMetadata.parse(yaml, name);
    }

    /**
     * Converts the clipboard's bounds into a box <b>relative to the origin</b>.
     *
     * <p>Relative to the origin is essential: rotation turns around the origin, and the paste
     * target is where the origin will land. Keeping the box in the clipboard's own coordinates
     * would mean subtracting the origin in every calculation — and it would get forgotten
     * somewhere.
     */
    private static Aabb localBoxOf(Clipboard clipboard) {
        BlockVector3 origin = clipboard.getOrigin();
        BlockVector3 min = clipboard.getMinimumPoint().subtract(origin);
        BlockVector3 max = clipboard.getMaximumPoint().subtract(origin);
        return new Aabb(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }
}
