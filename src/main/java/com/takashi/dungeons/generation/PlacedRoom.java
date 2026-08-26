package com.takashi.dungeons.generation;

/**
 * A room seated in the world: template + rotation + the world coordinate of its origin.
 *
 * <p>Immutable, and carries <b>geometry only</b>. Whether a door is connected, open or dead is
 * NOT here — that is runtime state held by the graph layer (1C/1D), per
 * {@code generation.md} §8. The reason for the split: geometry is computed before a placement
 * is accepted (the collision test needs the box), whereas graph state only comes into
 * existence once it has been accepted. Merging the two into one object would create a
 * "half-built room" state.
 *
 * @param template the room's template
 * @param rotation the clockwise rotation applied
 * @param origin   the WORLD coordinate the template's origin sits on — this is the paste target
 * @param bounds   the bounding box, rotated and translated into the world
 */
public record PlacedRoom(RoomTemplate template, Rotation rotation, Vec3i origin, Aabb bounds) {

    public static PlacedRoom of(RoomTemplate template, Rotation rotation, Vec3i origin) {
        Aabb bounds = template.localBox().rotate(rotation).translate(origin);
        return new PlacedRoom(template, rotation, origin, bounds);
    }

    /** The door anchor's WORLD coordinate. */
    public Vec3i doorAnchor(int index) {
        return origin.plus(rotation.apply(template.door(index).local()));
    }

    /** The door's outward facing in the WORLD frame — {@code d' = (d + R) mod 4}. */
    public Direction doorOutward(int index) {
        return rotation.apply(template.door(index).wall());
    }

    /**
     * Where the anchor of the child door attaching here will land.
     * One block further out, per the back-to-back convention ({@code generation.md} §5.2).
     */
    public Vec3i doorMate(int index) {
        return doorAnchor(index).plus(doorOutward(index).step());
    }

    public int doorCount() {
        return template.doorCount();
    }

    @Override
    public String toString() {
        return template.name() + " rot=" + rotation.degrees() + " origin=" + origin;
    }
}
