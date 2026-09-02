package com.takashi.dungeons.schematic;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.takashi.dungeons.generation.Aabb;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

/**
 * Wipes a box back to air — the counterpart of {@link SchematicService#paste}.
 *
 * <h2>Why a box and not the whole slot</h2>
 * A slot is 512×384×512, roughly 100 million blocks; a medium dungeon occupies well under one
 * percent of that. The instance therefore remembers the <b>union of its placed rooms</b> and only
 * that volume is cleared. This is not merely an optimisation: clearing a whole slot takes long
 * enough that "close the instance" would become a visible server stall.
 *
 * <h2>Threading follows paste</h2>
 * The same rule as {@link SchematicService}: FAWE's edit session batches and is safe off the main
 * thread, plain WorldEdit's is not. Whoever built the paste side decided this once
 * ({@code schematics.force-sync-paste}) and the cleaner is handed the same answer — the two must
 * never disagree, or a sync paste would race an async wipe of the same slot.
 *
 * <p><b>Entities are not this class's job.</b> WorldEdit clears blocks; mobs, dropped items and
 * item frames survive a block wipe and would haunt the next instance in that slot. Removing them
 * needs the Bukkit API on the main thread, so {@code InstanceManager} does it before calling here.
 */
public final class RegionCleaner {

    private final Plugin plugin;
    private final boolean async;

    public RegionCleaner(Plugin plugin, boolean async) {
        this.plugin = plugin;
        this.async = async;
    }

    /** What one wipe did. */
    public record Report(int blocks, long millis) {
    }

    /** Sets every block in the box to air. */
    public CompletableFuture<Report> clear(World world, Aabb box) {
        CompletableFuture<Report> future = new CompletableFuture<>();
        Runnable task = () -> {
            long start = System.nanoTime();
            try {
                int changed = clearBlocking(world, box);
                future.complete(new Report(changed, (System.nanoTime() - start) / 1_000_000L));
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

    private int clearBlocking(World world, Aabb box) {
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        CuboidRegion region = new CuboidRegion(weWorld,
                BlockVector3.at(box.minX(), box.minY(), box.minZ()),
                BlockVector3.at(box.maxX(), box.maxY(), box.maxZ()));
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {
            return session.setBlocks(region, BlockTypes.AIR.getDefaultState());
        } catch (Exception e) {
            throw new IllegalStateException("Region clear failed: " + e.getMessage(), e);
        }
    }
}
