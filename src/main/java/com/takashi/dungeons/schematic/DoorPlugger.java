package com.takashi.dungeons.schematic;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.Direction;
import com.takashi.dungeons.generation.PlugTarget;
import com.takashi.dungeons.generation.Vec3i;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Seals doors that open into the void — {@code generation.md} §7 (plugging).
 *
 * <h2>Why plugs rather than room variants</h2>
 * The idea "give every room a 1/2/3-door version" was rejected: a variant is not defined by the
 * door <b>count</b> but by the door <b>set</b> ({N,S} is not {N,E}). Even collapsed to 5 shapes
 * by rotation that is 40 rooms × 5 = 200 schematics — and the map team is this project's
 * tightest bottleneck. Plugging delivers almost all of the same visual result for
 * <b>zero files</b>.
 *
 * <h2>The opening's size is measured, not declared</h2>
 * There is <b>no</b> "door is 3×3" field in the metadata. The engine starts at the anchor and
 * scans air blocks in the room's wall plane to find the opening itself. This follows the spirit
 * of §9: every field that can be written is a field that can be written wrong. If a mapper draws
 * a 3×4 door the plug still fits, and arched, stepped or asymmetric openings work too.
 *
 * <h2>The material is measured too</h2>
 * The plug block does not come from config — the <b>wall block at the edge of the opening</b> is
 * sampled. A Nether room therefore yields nether brick and an End room end stone, with no
 * per-biome setting. That delivers the benefit of {@code generation.md} §7's "biome plug" option
 * at no file cost.
 *
 * <h2>Why the scan is bounded by the room's box</h2>
 * A wall plane (say {@code z = Z0}) is mathematically infinite, and outside the room that plane
 * is void — that is, air. Without the bound the scan would leak out of the opening into empty
 * space and fill half the dungeon with stone wall.
 */
public final class DoorPlugger {

    /**
     * The most blocks one opening may scan. A 3×3 door is 9 blocks; 64 leaves plenty of slack.
     *
     * <p>The limit exists mainly <b>as a guard against broken metadata</b>: if the anchor sits
     * inside the room rather than on a wall ({@code RoomTemplateStore} warns about this but does
     * not throw), the scan starts on an empty interior plane instead of a wall plane and would
     * fill the room's entire cross-section. When the limit is exceeded, that door is skipped and
     * a warning is logged.
     */
    private static final int MAX_OPENING_BLOCKS = 64;

    private final Plugin plugin;
    private final boolean async;

    public DoorPlugger(Plugin plugin, boolean async) {
        this.plugin = plugin;
        this.async = async;
    }

    /** The outcome of a plugging pass. */
    public record Report(int plugged, int skipped, int blocks, List<String> warnings) {
    }

    /**
     * Seals every door in the list.
     *
     * <p>A single {@link EditSession} is used: opening one per door would break FAWE's batching
     * and become visibly slow on a large dungeon.
     */
    public CompletableFuture<Report> plugAll(World world, List<PlugTarget> targets) {
        CompletableFuture<Report> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(plugBlocking(world, targets));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        if (async) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
        return future;
    }

    private Report plugBlocking(World world, List<PlugTarget> targets) {
        int plugged = 0;
        int skipped = 0;
        int blocks = 0;
        List<String> warnings = new ArrayList<>();

        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {

            for (PlugTarget target : targets) {
                Opening opening = scan(session, target);
                if (opening == null) {
                    skipped++;
                    warnings.add(target.anchor() + " " + target.outward().displayName()
                            + ": opening is larger than " + MAX_OPENING_BLOCKS
                            + " blocks, skipped (is the anchor really on a wall?)");
                    continue;
                }
                if (opening.cells.isEmpty()) {
                    // Already sealed: happens when the same dungeon was plugged twice, or the
                    // mapper drew the door closed. Not an error.
                    continue;
                }
                if (opening.material == null) {
                    skipped++;
                    warnings.add(target.anchor() + " " + target.outward().displayName()
                            + ": no wall block at the edge of the opening, could not sample a material");
                    continue;
                }
                for (BlockVector3 cell : opening.cells) {
                    session.setBlock(cell, opening.material);
                }
                plugged++;
                blocks += opening.cells.size();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Plugging failed: " + e.getMessage(), e);
        }
        return new Report(plugged, skipped, blocks, warnings);
    }

    /** A scanned opening: the cells to fill plus the wall material sampled from its edge. */
    private record Opening(List<BlockVector3> cells, BlockState material) {
    }

    /**
     * Scans the opening within the wall plane and samples a material from its edge.
     *
     * <p>The plane is obtained by holding the axis of {@code outward} fixed: for a north/south
     * door Z is fixed (the X-Y plane), for an east/west door X is fixed (the Z-Y plane).
     *
     * @return the opening, or {@code null} if the block limit was exceeded
     */
    private static Opening scan(EditSession session, PlugTarget target) {
        Vec3i anchor = target.anchor();
        Aabb bounds = target.roomBounds();
        boolean alongX = target.outward() == Direction.NORTH || target.outward() == Direction.SOUTH;

        List<BlockVector3> cells = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<BlockVector3> queue = new ArrayDeque<>();
        // Materials sampled from the edge — the most frequent one wins. Looking at a single
        // neighbour would be misleading: right beside a door there may be a torch, a window or
        // some other decorative block.
        Map<BlockState, Integer> materials = new HashMap<>();

        BlockVector3 start = BlockVector3.at(anchor.x(), anchor.y(), anchor.z());
        queue.add(start);
        seen.add(key(start));

        while (!queue.isEmpty()) {
            BlockVector3 pos = queue.poll();

            if (!contains(bounds, pos)) {
                continue;   // don't leak outside the room
            }
            BlockState state = session.getBlock(pos);
            if (!state.getBlockType().getMaterial().isAir()) {
                materials.merge(state, 1, Integer::sum);   // the edge of the opening is wall
                continue;
            }

            cells.add(pos);
            if (cells.size() > MAX_OPENING_BLOCKS) {
                return null;
            }
            for (BlockVector3 next : neighbours(pos, alongX)) {
                if (seen.add(key(next))) {
                    queue.add(next);
                }
            }
        }

        BlockState material = materials.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        return new Opening(cells, material);
    }

    /** The 4 neighbours within the wall plane: up/down plus the two sides along the wall. */
    private static BlockVector3[] neighbours(BlockVector3 p, boolean alongX) {
        if (alongX) {
            return new BlockVector3[]{
                    p.add(1, 0, 0), p.add(-1, 0, 0), p.add(0, 1, 0), p.add(0, -1, 0)};
        }
        return new BlockVector3[]{
                p.add(0, 0, 1), p.add(0, 0, -1), p.add(0, 1, 0), p.add(0, -1, 0)};
    }

    private static boolean contains(Aabb box, BlockVector3 p) {
        return p.x() >= box.minX() && p.x() <= box.maxX()
                && p.y() >= box.minY() && p.y() <= box.maxY()
                && p.z() >= box.minZ() && p.z() <= box.maxZ();
    }

    /** Packs a coordinate into a single long — for the visited set. */
    private static long key(BlockVector3 p) {
        return ((long) (p.x() & 0x3FFFFF) << 42)
                | ((long) (p.y() & 0xFFFFF) << 22)
                | (p.z() & 0x3FFFFF);
    }
}
