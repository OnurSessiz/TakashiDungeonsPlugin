package com.takashi.dungeons.generation;

/**
 * A room's door connection point — {@code generation.md} §2.
 *
 * <p>The anchor is the <b>base-center block of the door opening</b>, stored as a local
 * coordinate relative to the room's origin.
 *
 * <p><b>Facing is not stored, it is derived.</b> {@link #wall} is not read from metadata; it is
 * computed from the anchor vector by {@link Direction#ofAnchor}. The reason fits in one
 * sentence: leave nowhere for the inconsistency "metadata says north but the anchor is in the
 * east wall" to arise. The map team will hand-write metadata for 40+ rooms, and every field
 * that can be written is a field that can be written wrong.
 *
 * @param index position in the room's door list — the "address" from {@code generation.md} §8.
 *              NOT a fill order (geometry decides that); it tracks which door got connected and
 *              which one gets plugged.
 * @param local the anchor's coordinate relative to the origin
 * @param wall  the wall derived from the anchor (= the door's outward facing, before the room
 *              is rotated)
 */
public record DoorAnchor(int index, Vec3i local, Direction wall) {

    /** Builds the door by deriving its wall from the anchor. */
    public static DoorAnchor of(int index, Vec3i local, Aabb localBox) {
        return new DoorAnchor(index, local, Direction.ofAnchor(local, localBox));
    }

    @Override
    public String toString() {
        return "door#" + index + " " + local + " " + wall.displayName();
    }
}
