package com.takashi.dungeons.world;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Dungeon dünyasındaki bir instance'a ayrılmış kare alan.
 *
 * @param index    slot numarası (0'dan başlar, serbest kalınca yeniden kullanılır)
 * @param originX  slot'un kuzeybatı köşesinin dünya X'i
 * @param originY  slot'un taban Y'si
 * @param originZ  slot'un kuzeybatı köşesinin dünya Z'si
 * @param size     slot'un bir kenarı (blok)
 */
public record GridSlot(int index, int originX, int originY, int originZ, int size) {

    /** Slot içindeki (0,0,0) göreli koordinatı dünya konumuna çevirir. */
    public Location toLocation(World world, int relX, int relY, int relZ) {
        return new Location(world, originX + relX, originY + relY, originZ + relZ);
    }

    /** Slot'un merkezindeki taban konumu — test/teleport için. */
    public Location center(World world) {
        return new Location(world, originX + size / 2.0, originY, originZ + size / 2.0);
    }

    @Override
    public String toString() {
        return "slot#" + index + " @ " + originX + "," + originY + "," + originZ;
    }
}
