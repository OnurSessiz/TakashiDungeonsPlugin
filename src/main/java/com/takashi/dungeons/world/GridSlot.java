package com.takashi.dungeons.world;

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

    @Override
    public String toString() {
        return "slot#" + index + " @ " + originX + "," + originY + "," + originZ;
    }
}
