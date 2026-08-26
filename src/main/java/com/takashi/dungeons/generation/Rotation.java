package com.takashi.dungeons.generation;

/**
 * A 90° clockwise rotation step — {@code R ∈ {0,1,2,3}} from {@code generation.md} §3.
 *
 * <p><b>The sign was MEASURED, not assumed.</b> The exact call {@code SchematicService} makes
 * during paste, {@code AffineTransform().rotateY(-degrees)}, was run directly:
 * <pre>
 *   rotateY(-90):  (x,y,z) -> (-z,y,x)     NORTH -> EAST -> SOUTH -> WEST
 * </pre>
 * So {@code R=1} is clockwise and {@code d' = (d + R) mod 4} holds. All four steps matched the
 * point formulas in {@code generation.md} §3 exactly. Had this sign been wrong, every generated
 * dungeon would have had mismatched doors and the failure would have been silent — which is why
 * it was measured before any of this was written.
 *
 * <p><b>The Y axis is preserved.</b> {@code rotateY} only turns the X-Z plane. That is why a
 * multi-storey room (enter from below, continue above) needs no extra code.
 */
public enum Rotation {

    NONE(0),
    CW_90(1),
    CW_180(2),
    CW_270(3);

    private static final Rotation[] VALUES = values();

    private final int steps;

    Rotation(int steps) {
        this.steps = steps;
    }

    /** Number of 90° clockwise steps (0-3). */
    public int steps() {
        return steps;
    }

    /** The equivalent in clockwise degrees — {@code SchematicService.paste} expects this. */
    public int degrees() {
        return steps * 90;
    }

    public static Rotation ofSteps(int steps) {
        return VALUES[Math.floorMod(steps, 4)];
    }

    public static Rotation ofDegrees(int degrees) {
        if (Math.floorMod(degrees, 90) != 0) {
            throw new IllegalArgumentException("Rotation must be a multiple of 90: " + degrees);
        }
        return ofSteps(Math.floorMod(degrees, 360) / 90);
    }

    /** {@code d' = (d + R) mod 4}. */
    public Direction apply(Direction direction) {
        return Direction.byIndex(direction.index() + steps);
    }

    /**
     * Rotates a point around the origin — {@code generation.md} §3.
     *
     * <pre>
     *   R=0 -> ( x, y,  z)      R=1 -> (-z, y,  x)
     *   R=2 -> (-x, y, -z)      R=3 -> ( z, y, -x)
     * </pre>
     */
    public Vec3i apply(Vec3i v) {
        return switch (this) {
            case NONE -> v;
            case CW_90 -> new Vec3i(-v.z(), v.y(), v.x());
            case CW_180 -> new Vec3i(-v.x(), v.y(), -v.z());
            case CW_270 -> new Vec3i(v.z(), v.y(), -v.x());
        };
    }

    /**
     * The rotation that brings a child room's door <b>back to back</b> with the parent's door —
     * {@code generation.md} §5.2 step 2: {@code R = (d_p + 2 - d_c) mod 4}.
     *
     * <p>This value is <b>computed, not searched for</b>. The child's door wall ends up facing
     * the opposite of the parent door's outward direction, so the two walls face each other.
     * That is why the candidate pool never has to be narrowed to "rooms with a door facing this
     * way": every room is a candidate for every connection, and rotation closes the gap.
     *
     * @param parentOutward the parent door's outward facing in the WORLD frame
     * @param childWall     the child door's wall in the room's LOCAL frame
     */
    public static Rotation align(Direction parentOutward, Direction childWall) {
        return ofSteps(parentOutward.index() + 2 - childWall.index());
    }
}
