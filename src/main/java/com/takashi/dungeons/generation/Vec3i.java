package com.takashi.dungeons.generation;

/**
 * Integer 3D vector — used for door anchors, room origins and box corners.
 *
 * <p><b>Why not WorldEdit's {@code BlockVector3}:</b> so the geometry in this package stays
 * pure. Rotation and wall derivation can then be tested without WorldEdit on the classpath,
 * and no third-party type leaks into the API exposed in phase 8 (a breaking-change risk).
 * The conversion happens only at the paste boundary, on one line.
 */
public record Vec3i(int x, int y, int z) {

    public static final Vec3i ZERO = new Vec3i(0, 0, 0);

    public Vec3i plus(Vec3i other) {
        return new Vec3i(x + other.x, y + other.y, z + other.z);
    }

    public Vec3i minus(Vec3i other) {
        return new Vec3i(x - other.x, y - other.y, z - other.z);
    }

    public Vec3i times(int scalar) {
        return new Vec3i(x * scalar, y * scalar, z * scalar);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + ")";
    }
}
