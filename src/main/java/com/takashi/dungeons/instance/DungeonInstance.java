package com.takashi.dungeons.instance;

import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.DungeonGenerator;
import com.takashi.dungeons.generation.LayoutNode;
import com.takashi.dungeons.generation.Vec3i;
import com.takashi.dungeons.schematic.DoorPlugger;
import com.takashi.dungeons.world.GridSlot;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * One live dungeon: the slot it occupies, what was generated into it, and how far through its
 * life it is.
 *
 * <h2>What is stored, and what is deliberately not</h2>
 * The identity of a dungeon is the quadruple <b>slot + theme + size + seed</b> — hand those four
 * back to {@link DungeonGenerator} and the same rooms come out in the same places
 * ({@code generation.md} §13). So phase 7 will write four columns, not a layout dump.
 *
 * <p>The generated {@link DungeonGenerator.Result} is kept in memory anyway, for two reasons that
 * only apply while the instance is alive: {@link #bounds()} is the exact volume to wipe on close,
 * and phase 3 needs the room graph to know where to spawn what. Neither survives a restart, and
 * neither needs to.
 */
public final class DungeonInstance {

    /**
     * How far the wiped box reaches past the rooms themselves.
     *
     * <p>Doors are plugged inside their own room's box, so in principle the union of the rooms is
     * exactly what was written. One block of slack costs nothing on a volume this size and covers
     * the off-by-one class of mistake — a wipe that misses is far more expensive than one that
     * clears a shell of air.
     */
    private static final int CLEANUP_MARGIN = 1;

    private final int id;
    private final GridSlot slot;
    private final String theme;
    private final DungeonGenerator.Result result;
    private final DoorPlugger.Report plugReport;
    private final Aabb bounds;
    private final long createdAt;

    private volatile InstanceState state;

    DungeonInstance(int id, GridSlot slot, String theme, DungeonGenerator.Result result,
                    DoorPlugger.Report plugReport, Aabb bounds) {
        this.id = id;
        this.slot = slot;
        this.theme = theme;
        this.result = result;
        this.plugReport = plugReport;
        this.bounds = bounds;
        this.createdAt = System.currentTimeMillis();
        this.state = InstanceState.BUILDING;
    }

    /**
     * The volume the generator actually wrote to, clamped to the slot.
     *
     * <p>The clamp is not cosmetic: without it a room that overran its slot would have its
     * cleanup overrun too, and the wipe would eat the neighbouring instance's blocks. Generation
     * already refuses to place such a room, so this is the second lock on the same door.
     */
    static Aabb boundsOf(DungeonGenerator.Result result, GridSlot slot, World world) {
        Aabb union = null;
        for (LayoutNode node : result.layout().nodes()) {
            union = union == null ? node.bounds() : union.union(node.bounds());
        }
        Aabb slotBounds = slot.bounds(world);
        if (union == null) {
            // No rooms at all — nothing was written, so nothing needs wiping. An empty box at
            // the slot corner keeps every caller free of null checks.
            return new Aabb(slot.originX(), slot.originY(), slot.originZ(),
                    slot.originX(), slot.originY(), slot.originZ());
        }
        Aabb clamped = union.grow(CLEANUP_MARGIN).clampTo(slotBounds);
        return clamped == null ? union : clamped;
    }

    public int id() {
        return id;
    }

    public GridSlot slot() {
        return slot;
    }

    public String theme() {
        return theme;
    }

    public DungeonGenerator.Result result() {
        return result;
    }

    public DoorPlugger.Report plugReport() {
        return plugReport;
    }

    /** The box to wipe on close — the union of the placed rooms, plus a block of slack. */
    public Aabb bounds() {
        return bounds;
    }

    public long createdAt() {
        return createdAt;
    }

    /** Milliseconds since the dungeon was generated. */
    public long ageMillis() {
        return System.currentTimeMillis() - createdAt;
    }

    public InstanceState state() {
        return state;
    }

    public boolean isActive() {
        return state == InstanceState.ACTIVE;
    }

    /**
     * Moves the instance forward one state.
     *
     * <p>Refuses to go backwards or skip: the return value is what tells a second
     * {@code close()} that teardown is already running, instead of letting it release the slot a
     * second time.
     */
    synchronized boolean advanceTo(InstanceState next) {
        if (next.ordinal() != state.ordinal() + 1) {
            return false;
        }
        state = next;
        return true;
    }

    /** Where a player entering this dungeon lands: on the entrance room's floor. */
    public @Nullable Location entranceSpawn(World world) {
        LayoutNode root = result.layout().root();
        if (root == null) {
            return slot.center(world).add(0, 1, 0);
        }
        Vec3i o = root.room().origin();
        return new Location(world, o.x() + 0.5, o.y() + 1, o.z() + 0.5);
    }

    @Override
    public String toString() {
        return "instance#" + id + " (" + theme + "/" + result.size().key()
                + ", " + result.rooms() + " oda, " + slot + ")";
    }
}
