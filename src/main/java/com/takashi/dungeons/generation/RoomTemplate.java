package com.takashi.dungeons.generation;

import java.util.List;

/**
 * A room template: the geometry of a {@code .schem} file plus the metadata of the {@code .yml}
 * beside it.
 *
 * <p><b>Size is not written in the metadata</b> — {@link #localBox} is read from the schematic
 * itself. Like door facing, it is a derived value: a mapper who enlarges a room in the editor
 * and re-exports the schematic can easily forget to update the {@code .yml}, and a wrong box
 * makes the collision test silently wrong. The schematic is the single source.
 *
 * <p>A template is used by <b>rotating</b> it, never by duplicating it into door variants
 * ({@code generation.md} §7 — this is why the 40 rooms × 5 shapes = 200 schematics option was
 * rejected).
 *
 * @param name     file name without extension (the {@code .schem} and {@code .yml} share it)
 * @param type     role in the graph
 * @param weight   share in the weighted candidate draw — loot-weight semantics
 * @param doors    door anchors, in {@code .yml} order; may be empty (a decorative dead end)
 * @param localBox the room's bounding box relative to its origin, read from the schematic
 */
public record RoomTemplate(String name, RoomType type, int weight,
                           List<DoorAnchor> doors, Aabb localBox) {

    public RoomTemplate {
        doors = List.copyOf(doors);
    }

    public int doorCount() {
        return doors.size();
    }

    public DoorAnchor door(int index) {
        if (index < 0 || index >= doors.size()) {
            throw new IndexOutOfBoundsException(name + ": no door#" + index + " (this room has "
                    + doors.size() + " doors, valid range 0-" + (doors.size() - 1) + ")");
        }
        return doors.get(index);
    }

    /**
     * Attaches this template to an open door of a parent room — {@code generation.md} §5.2
     * steps 2-4.
     *
     * <p>The collision test (step 5) is <b>not</b> done here. This method is pure geometry, the
     * answer to "where does it seat". The question "may it seat" belongs to the graph layer
     * (1C) and needs the list of already-placed rooms. Keeping them apart is what makes the
     * placement maths testable without touching a world.
     *
     * @param doorIndex     which of this template's doors connects
     * @param parentAnchor  the parent door's WORLD coordinate
     * @param parentOutward the parent door's outward facing in the WORLD frame
     */
    public PlacedRoom attachTo(int doorIndex, Vec3i parentAnchor, Direction parentOutward) {
        DoorAnchor door = door(doorIndex);
        Rotation rotation = Rotation.align(parentOutward, door.wall());

        // Back to back: the child's door anchor sits exactly one block outside the parent's.
        // If the walls overlapped, the second paste would overwrite the first one's wall and
        // the result would depend on paste ORDER — and order-dependent generation cannot be
        // debugged.
        Vec3i childAnchorWorld = parentAnchor.plus(parentOutward.step());
        Vec3i origin = childAnchorWorld.minus(rotation.apply(door.local()));

        return PlacedRoom.of(this, rotation, origin);
    }

    /** Summarizes the box size without applying rotation — for command output. */
    public String describeSize() {
        return localBox.sizeX() + "×" + localBox.sizeY() + "×" + localBox.sizeZ();
    }
}
