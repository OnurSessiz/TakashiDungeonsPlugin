package com.takashi.dungeons.generation;

/**
 * A door opening that has to be sealed — {@code generation.md} §7 (plugging).
 *
 * <p>When the graph is finished some doors will be opening into the void: either the side
 * branch quota ran out before they were ever tried (OPEN), or they were tried and every
 * candidate collided (DEAD). Both have to be sealed, otherwise the player walks out of a room
 * and falls into the void.
 *
 * <p><b>The size of the opening is deliberately not stored here.</b> The engine finds it by
 * starting at the anchor and scanning air blocks in the room's wall plane
 * (see {@code schematic/DoorPlugger}). This follows the spirit of §9: every field that can be
 * written into metadata is a field that can be written wrong. If a mapper draws a 3×4 door
 * instead of 3×3, the plug still fits.
 *
 * @param anchor      the door anchor's WORLD coordinate
 * @param outward     the door's outward facing — this identifies the wall plane it lies in
 * @param roomBounds  the room's world box, which bounds the scan; without it the scan would
 *                    leak along the wall plane past the room and out into the void
 * @param dead        {@code true} for DEAD (tried, nothing fit), {@code false} for OPEN
 */
public record PlugTarget(Vec3i anchor, Direction outward, Aabb roomBounds, boolean dead) {

    @Override
    public String toString() {
        return anchor + " " + outward.displayName() + (dead ? " (dead)" : " (open)");
    }
}
