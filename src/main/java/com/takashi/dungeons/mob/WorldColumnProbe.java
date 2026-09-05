package com.takashi.dungeons.mob;

import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * {@link ColumnProbe} over a live world. The whole Bukkit surface of the spawn search is these two
 * methods; everything else in {@link RoomSpawnFinder} is pure geometry.
 *
 * <p>Main thread only — reading a block is not thread safe.
 */
public final class WorldColumnProbe implements ColumnProbe {

    private final World world;

    public WorldColumnProbe(World world) {
        this.world = world;
    }

    @Override
    public boolean isFloor(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType().isSolid();
    }

    @Override
    public boolean isClear(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        // isPassable alone lets fluids through — water and lava are both passable. A mob spawned
        // in waist-deep lava is a mob the player never meets.
        return block.isPassable() && !block.isLiquid();
    }
}
