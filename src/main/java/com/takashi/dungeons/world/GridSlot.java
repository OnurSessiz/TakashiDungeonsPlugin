package com.takashi.dungeons.world;

import com.takashi.dungeons.generation.Aabb;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * The square area reserved for one instance in the dungeon world.
 *
 * @param index    slot number (starts at 0, reused once released)
 * @param originX  world X of the slot's north-west corner
 * @param originY  the slot's floor Y
 * @param originZ  world Z of the slot's north-west corner
 * @param size     the slot's edge length, in blocks
 */
public record GridSlot(int index, int originX, int originY, int originZ, int size) {

    /** Converts a coordinate relative to the slot's (0,0,0) into a world location. */
    public Location toLocation(World world, int relX, int relY, int relZ) {
        return new Location(world, originX + relX, originY + relY, originZ + relZ);
    }

    /** The floor location at the slot's centre — for testing and teleports. */
    public Location center(World world) {
        return new Location(world, originX + size / 2.0, originY, originZ + size / 2.0);
    }

    /**
     * The slot's 3D bounds. X/Z come from the slot, Y from the world's height limits.
     *
     * <p>The X/Z bound is a hard requirement: a room that overflows reaches into a neighbouring
     * instance's blocks. There is no slot concept on Y, so the world limits suffice.
     */
    public Aabb bounds(World world) {
        return new Aabb(
                originX, world.getMinHeight(), originZ,
                originX + size - 1, world.getMaxHeight() - 1, originZ + size - 1);
    }

    /**
     * Whether a horizontal position falls inside this slot.
     *
     * <p>Deliberately X/Z only: "which instance is this player in" must answer the same whether
     * they stand on a room's floor or in the void above it — a Y test would lose them the moment
     * they fall through a hole.
     */
    public boolean contains(double x, double z) {
        return x >= originX && x < originX + size && z >= originZ && z < originZ + size;
    }

    @Override
    public String toString() {
        return "slot#" + index + " @ " + originX + "," + originY + "," + originZ;
    }
}
