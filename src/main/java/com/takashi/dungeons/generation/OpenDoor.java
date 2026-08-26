package com.takashi.dungeons.generation;

/**
 * A door waiting to be filled: which door of which room, where it is in the world, and which
 * way it faces.
 *
 * <p>This is the input to the placement algorithm ({@code generation.md} §5.1). Facing and
 * position are derived from {@link LayoutNode} and frozen here, so callers don't redo the
 * rotation maths on every access.
 *
 * @param nodeId    id of the room this door belongs to, within the layout
 * @param doorIndex index in the room's door list — the "address" from {@code generation.md} §8
 * @param anchor    the door anchor's WORLD coordinate
 * @param outward   the door's outward facing in the WORLD frame
 */
public record OpenDoor(int nodeId, int doorIndex, Vec3i anchor, Direction outward) {

    /**
     * Where the anchor of the child door attaching here will land.
     * One block further out, per the back-to-back convention.
     */
    public Vec3i mate() {
        return anchor.plus(outward.step());
    }

    @Override
    public String toString() {
        return "room#" + nodeId + " door#" + doorIndex + " " + anchor + " " + outward.displayName();
    }
}
