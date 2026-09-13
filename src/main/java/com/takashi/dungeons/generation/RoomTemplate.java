package com.takashi.dungeons.generation;

import java.util.List;
import java.util.Map;

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
 * @param maxPerDungeon per-size cap on how many copies one dungeon may contain; a size that is
 *                      absent from the map is uncapped ({@code generation.md} §8)
 */
public record RoomTemplate(String name, RoomType type, int weight,
                           List<DoorAnchor> doors, Aabb localBox,
                           Map<DungeonSize, Integer> maxPerDungeon) {

    public RoomTemplate {
        doors = List.copyOf(doors);
        maxPerDungeon = Map.copyOf(maxPerDungeon);
    }

    /** An uncapped template — the form every room had before {@code max-per-dungeon} existed. */
    public RoomTemplate(String name, RoomType type, int weight,
                        List<DoorAnchor> doors, Aabb localBox) {
        this(name, type, weight, doors, localBox, Map.of());
    }

    /**
     * How many copies of this room a dungeon of the given size may hold.
     *
     * <p>{@link Integer#MAX_VALUE} when no cap is written for that size — which is the default,
     * so an existing {@code .yml} that has never heard of the field behaves exactly as before.
     *
     * <p>The cap is a <b>separate question from {@link #weight()}</b> and deliberately does not
     * touch it: weight answers "how often is it drawn", the cap answers "how many at most".
     * Folding the cap into the weight would re-create the very thing {@code generation.md} §5.4
     * rejected — a property the mapper did not write silently overriding the one they did.
     */
    public int maxPerDungeon(DungeonSize size) {
        return maxPerDungeon.getOrDefault(size, Integer.MAX_VALUE);
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
