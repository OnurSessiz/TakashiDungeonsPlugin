package com.takashi.dungeons.generation;

/**
 * An axis-aligned 3D box with <b>both ends inclusive</b> (block coordinates, not a range).
 *
 * <p>Used in two places:
 * <ul>
 *   <li><b>Local box</b> — the room's bounds relative to its origin ({@link RoomTemplate})</li>
 *   <li><b>World box</b> — the volume a placed room occupies ({@link PlacedRoom})</li>
 * </ul>
 *
 * <p><b>Why 3D rather than a 2D footprint:</b> {@code generation.md} §5.2 step 5. In a
 * multi-storey dungeon two rooms can overlap in footprint without overlapping in volume — the
 * upper room passes over the lower one. A footprint test would reject that, i.e. block the very
 * thing the architecture was designed to allow.
 */
public record Aabb(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Aabb {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Invalid box: min > max");
        }
    }

    public static Aabb of(Vec3i a, Vec3i b) {
        return new Aabb(
                Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    public Vec3i min() {
        return new Vec3i(minX, minY, minZ);
    }

    public Vec3i max() {
        return new Vec3i(maxX, maxY, maxZ);
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public boolean contains(Vec3i p) {
        return p.x() >= minX && p.x() <= maxX
                && p.y() >= minY && p.y() <= maxY
                && p.z() >= minZ && p.z() <= maxZ;
    }

    /**
     * Whether two boxes share any block.
     *
     * <p>Because the ends are inclusive the comparison is {@code <=}: a box with
     * {@code maxX=10} and one with {@code minX=10} <b>share</b> the x=10 column, so they
     * intersect. This is why rooms connected back to back do not collide — there is exactly one
     * block of clearance between them ({@code generation.md} §5.2 step 3).
     */
    public boolean intersects(Aabb other) {
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    /** Rotates the box around the origin: the corners are rotated and min/max retaken. */
    public Aabb rotate(Rotation rotation) {
        return Aabb.of(rotation.apply(min()), rotation.apply(max()));
    }

    public Aabb translate(Vec3i offset) {
        return new Aabb(
                minX + offset.x(), minY + offset.y(), minZ + offset.z(),
                maxX + offset.x(), maxY + offset.y(), maxZ + offset.z());
    }

    /** Shrinks the box by {@code amount} on every side — used to leave slack in bounds tests. */
    public Aabb shrink(int amount) {
        return new Aabb(minX + amount, minY + amount, minZ + amount,
                maxX - amount, maxY - amount, maxZ - amount);
    }

    @Override
    public String toString() {
        return "[" + minX + "," + minY + "," + minZ + " → " + maxX + "," + maxY + "," + maxZ
                + "] " + sizeX() + "×" + sizeY() + "×" + sizeZ();
    }
}
