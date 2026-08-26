package com.takashi.dungeons.generation;

/**
 * Horizontal directions, in the index order from {@code generation.md} §3: N=0, E=1, S=2, W=3.
 *
 * <p>The order is <b>clockwise</b>. That reduces {@link Rotation}'s {@code (d + R) mod 4}
 * formula to plain {@code ordinal()} arithmetic — which also means that reordering this enum
 * silently breaks the rotation maths.
 *
 * <p>Minecraft axes: {@code +X = East}, {@code +Z = South}, therefore North is {@code -Z}.
 */
public enum Direction {

    NORTH(0, 0, -1, "kuzey"),
    EAST(1, 0, 0, "doğu"),
    SOUTH(0, 0, 1, "güney"),
    WEST(-1, 0, 0, "batı");

    private static final Direction[] VALUES = values();

    private final Vec3i step;
    private final String displayName;

    Direction(int dx, int dy, int dz, String displayName) {
        this.step = new Vec3i(dx, dy, dz);
        this.displayName = displayName;
    }

    /** A one-block displacement in this direction. */
    public Vec3i step() {
        return step;
    }

    /** The direction index (N=0, E=1, S=2, W=3). */
    public int index() {
        return ordinal();
    }

    /** {@code opposite(d) = (d + 2) mod 4}. */
    public Direction opposite() {
        return VALUES[(ordinal() + 2) & 3];
    }

    /**
     * Name shown in messages. Currently Turkish; changing the language is a job for this text,
     * not for the API name.
     */
    public String displayName() {
        return displayName;
    }

    public static Direction byIndex(int index) {
        return VALUES[Math.floorMod(index, 4)];
    }

    /**
     * Derives which wall a door anchor lies in — {@code generation.md} §4.
     *
     * <p><b>Why not the raw {@code |dx| > |dz|} rule:</b> that rule is only correct for square
     * rooms. In a 9-wide × 25-long corridor, a door in the east wall near the south end gives
     * {@code (dx=+4, dz=+11)}; since {@code |dz| > |dx|}, the naive rule answers "south wall",
     * which is wrong. The correct approach normalizes each component against
     * <b>the half-extent in its own direction</b> and asks which one reaches ±1: that is the
     * wall the point is touching.
     *
     * <p>Normalization is done per direction (east and west extents are read separately),
     * because {@code generation.md} §9 dropped the odd-side-length rule: the origin no longer
     * has to sit at the exact middle of the room, so a room may be asymmetric about it.
     *
     * @param local    the anchor's local coordinate relative to the origin
     * @param localBox the room's bounding box relative to the origin
     * @throws IllegalArgumentException if the anchor sits exactly on the origin (no wall can be
     *                                  derived)
     */
    public static Direction ofAnchor(Vec3i local, Aabb localBox) {
        double nx = ratio(local.x(), localBox.maxX(), -localBox.minX());
        double nz = ratio(local.z(), localBox.maxZ(), -localBox.minZ());

        if (nx == 0.0 && nz == 0.0) {
            throw new IllegalArgumentException(
                    "Door anchor coincides with the room origin — no wall can be derived: " + local);
        }
        // X wins ties (generation.md §4: |nx| >= |nz|). The rule is fixed so that a corner
        // anchor never leaves the choice ambiguous.
        if (nx >= nz) {
            return local.x() > 0 ? EAST : WEST;
        }
        return local.z() > 0 ? SOUTH : NORTH;
    }

    /**
     * The component's ratio against the extent in the direction it points.
     *
     * <p>If the extent is 0 (the origin sits right on that edge) the ratio counts as infinite:
     * the point is already on that wall. That is why division by zero is handled separately.
     */
    private static double ratio(int delta, int positiveExtent, int negativeExtent) {
        if (delta == 0) {
            return 0.0;
        }
        int extent = delta > 0 ? positiveExtent : negativeExtent;
        if (extent <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.abs(delta) / (double) extent;
    }
}
