package com.takashi.dungeons.mob;

import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.Vec3i;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Finds places inside a room where a mob can stand.
 *
 * <h2>Two steps, two different jobs</h2>
 * <ol>
 *   <li><b>Seed — square rings outward from the centre.</b> The centre column is tried first; if it
 *       will not do, the perimeter of the 3×3 ring is walked, then the 5×5, until a column that can
 *       hold a mob turns up. Deterministic, cheap, and biased to the middle of the room.</li>
 *   <li><b>Spread — flood fill from that seed.</b> The walkable surface is walked breadth-first
 *       from the seed and points are taken in that order.</li>
 * </ol>
 *
 * <h2>Why the second step is not more rings</h2>
 * Rings assume the walkable area is a blob centred on the bounding box. Rooms are not required to
 * be rectangles: an L-shaped hall, a room built around a central pool, the cross-shaped
 * {@code test_cross} — in all of them a ring at radius 6 crosses walls as if they were not there.
 * Two things go wrong: the centre of the box can land inside a pillar or a pit, and worse, a ring
 * will happily place a mob in a <b>sealed alcove</b> on the far side of a wall, where the player
 * never meets it.
 *
 * <p>A flood fill only reaches what can be walked to from the seed, so the alcove is excluded
 * without a separate reachability test, and the breadth-first order still fills outward from the
 * centre — around corners rather than through them.
 *
 * <h2>Pure Java on purpose</h2>
 * Everything the world knows arrives through {@link ColumnProbe}, so this class can be tested
 * without a server ({@code scripts/geo-probe/SpawnProbe.java}). Geometry that is wrong only on
 * unusual room shapes is exactly the kind a server test does not catch: every room still gets its
 * mobs, they are simply in the wrong places.
 *
 * <h2>Spawn points are never hand-measured</h2>
 * They are not written into the room's {@code .yml}. Room size is read from the schematic and door
 * facing is derived for one reason ({@code generation.md} §9: door anchors are "the one place
 * these systems break"), and spawn points follow the same rule. Forty rooms times five points
 * would have been two hundred chances to be quietly wrong.
 */
public final class RoomSpawnFinder {

    /**
     * How much clear space a mob needs above the block it stands on.
     *
     * <p>Two blocks is the vanilla mob envelope. A ravager is taller, but requiring three would
     * rule out every low-ceilinged corridor, and a mob that spawns slightly clipped settles itself;
     * one that never spawns leaves an empty room.
     */
    static final int HEADROOM = 2;

    /**
     * Minimum distance between two chosen points, in blocks.
     *
     * <p>Without it the fill hands the second mob the cell next to the first, and the group reads
     * as one mob with a rendering fault rather than as a group. Three blocks is the smallest gap
     * that still looks deliberate in a nine-wide room.
     */
    static final int MIN_SPACING = 3;

    /**
     * How far a neighbouring column's floor may sit above or below this one and still count as
     * connected.
     *
     * <p>One block: stairs, slabs and a raised podium stay walkable, while the floor above stays a
     * separate storey. Allowing two would merge a mezzanine into the ground floor and let a mob
     * spawn on a balcony the fill had no business reaching.
     */
    static final int STEP_TOLERANCE = 1;

    private final ColumnProbe probe;

    public RoomSpawnFinder(ColumnProbe probe) {
        this.probe = probe;
    }

    /**
     * Picks up to {@code count} standing positions, filling outward from the room's centre along
     * the floor that is actually there.
     *
     * <p>A short list is information rather than failure: a room full of pillars genuinely has
     * fewer places to put a mob.
     *
     * @param box    the room's world bounds
     * @param count  how many points are wanted
     * @param random used only to break directional ties; the result stays reproducible for a seed
     * @return the <b>floor blocks</b> to stand a mob on — the mob itself goes one block above
     */
    public List<Vec3i> find(Aabb box, int count, RandomGenerator random) {
        return spread(survey(box, random), count);
    }

    /**
     * Every column of the room that can be walked to from the centre, in breadth-first order.
     *
     * <p>Separate from {@link #spread} because the <b>size</b> of this list is itself an answer:
     * phase 3B sizes a room's mob count by how much floor it actually has, and a cross-shaped room
     * has far less walkable floor than its bounding box suggests. Surveying once and using the
     * result twice is also cheaper than measuring the room and then searching it.
     *
     * <p>The list is ordered outward from the seed, so taking a prefix of it means "fill from the
     * middle".
     */
    public List<Vec3i> survey(Aabb box, RandomGenerator random) {
        Vec3i seed = findSeed(box, random);
        if (seed == null) {
            return List.of();
        }
        return floodFrom(seed, box, random);
    }

    /**
     * Takes up to {@code count} points from a surveyed surface, keeping them apart.
     *
     * <p>A short list is information rather than failure: a room whose floor is mostly furniture
     * genuinely has fewer places to put a mob, and reporting three where five were asked for is
     * more useful than quietly stacking two of them on one block.
     */
    public List<Vec3i> spread(List<Vec3i> surface, int count) {
        List<Vec3i> found = new ArrayList<>(Math.max(0, count));
        if (count <= 0) {
            return found;
        }
        for (Vec3i column : surface) {
            if (found.size() >= count) {
                break;
            }
            if (isFarEnough(column, found)) {
                found.add(column);
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ step 1: the seed

    /**
     * Square rings outward from the centre of the box until a standable column is found.
     *
     * <p>The centre of the <b>box</b>, not the room's origin: anchor-based placement dropped the
     * odd-edge rule ({@code generation.md} §9), so a room may sit asymmetrically around its origin,
     * and then "origin" is off to one side rather than in the middle.
     *
     * @return the floor block to start from, or {@code null} if nothing in the room can be stood on
     */
    Vec3i findSeed(Aabb box, RandomGenerator random) {
        int centreX = (box.minX() + box.maxX()) / 2;
        int centreZ = (box.minZ() + box.maxZ()) / 2;
        int maxRadius = Math.max(box.sizeX(), box.sizeZ()) / 2 + 1;

        for (int radius = 0; radius <= maxRadius; radius++) {
            for (int[] offset : ringOffsets(radius, random)) {
                int x = centreX + offset[0];
                int z = centreZ + offset[1];
                if (x < box.minX() || x > box.maxX() || z < box.minZ() || z > box.maxZ()) {
                    continue;
                }
                int floorY = lowestFloorIn(x, z, box);
                if (floorY != Integer.MIN_VALUE) {
                    return new Vec3i(x, floorY, z);
                }
            }
        }
        return null;
    }

    /**
     * The cells of the square ring at {@code radius}, walked clockwise from a rotated start.
     *
     * <p>The rotation is why a random generator is passed in. With a fixed start every room's seed
     * lands on the same side of centre whenever the centre itself is blocked, and a party walking
     * through twenty rooms notices the pattern. Rotating by a seeded amount keeps the dungeon
     * reproducible while removing the tell.
     */
    private List<int[]> ringOffsets(int radius, RandomGenerator random) {
        if (radius == 0) {
            return List.of(new int[]{0, 0});
        }
        List<int[]> cells = new ArrayList<>(8 * radius);
        for (int dx = -radius; dx <= radius; dx++) {
            cells.add(new int[]{dx, -radius});
        }
        for (int dz = -radius + 1; dz <= radius; dz++) {
            cells.add(new int[]{radius, dz});
        }
        for (int dx = radius - 1; dx >= -radius; dx--) {
            cells.add(new int[]{dx, radius});
        }
        for (int dz = radius - 1; dz >= -radius + 1; dz--) {
            cells.add(new int[]{-radius, dz});
        }
        int pivot = random.nextInt(cells.size());
        List<int[]> rotated = new ArrayList<>(cells.size());
        rotated.addAll(cells.subList(pivot, cells.size()));
        rotated.addAll(cells.subList(0, pivot));
        return rotated;
    }

    // ------------------------------------------------------------------ step 2: the spread

    /**
     * Breadth-first walk of the walkable surface, collecting columns in the order they are reached.
     *
     * <p>Breadth-first is not an implementation detail: it is what makes a prefix of the result
     * fill outward from the seed. A depth-first walk would run down one corridor and put every mob
     * in it while the rest of the hall stayed empty.
     */
    private List<Vec3i> floodFrom(Vec3i seed, Aabb box, RandomGenerator random) {
        List<Vec3i> reached = new ArrayList<>();
        Deque<Vec3i> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(seed);
        seen.add(key(seed.x(), seed.z()));

        while (!queue.isEmpty()) {
            Vec3i cell = queue.poll();
            reached.add(cell);
            for (int[] step : neighbours(random)) {
                int nx = cell.x() + step[0];
                int nz = cell.z() + step[1];
                if (nx < box.minX() || nx > box.maxX() || nz < box.minZ() || nz > box.maxZ()) {
                    continue;
                }
                if (!seen.add(key(nx, nz))) {
                    continue;
                }
                int floorY = floorNear(nx, nz, cell.y(), box);
                if (floorY != Integer.MIN_VALUE) {
                    queue.add(new Vec3i(nx, floorY, nz));
                }
            }
        }
        return reached;
    }

    /**
     * The four cardinal steps, in a shuffled order.
     *
     * <p>Diagonals are left out deliberately: a diagonal step slips between two blocks that meet at
     * a corner, and the fill would leak through a wall a player cannot walk through.
     *
     * <p>The shuffle removes the same directional tell the ring rotation removes — with a fixed
     * order the second mob is north of the first in every room in the game.
     */
    private List<int[]> neighbours(RandomGenerator random) {
        List<int[]> steps = new ArrayList<>(List.of(
                new int[]{1, 0}, new int[]{-1, 0}, new int[]{0, 1}, new int[]{0, -1}));
        for (int i = steps.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] swap = steps.get(i);
            steps.set(i, steps.get(j));
            steps.set(j, swap);
        }
        return steps;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    // ------------------------------------------------------------------ column tests

    /**
     * The floor of a neighbouring column, if it is within one step of {@code fromY}.
     *
     * <p>Searching around the previous column's height rather than from the bottom of the box is
     * what keeps the fill on one storey. Started from the floor every time, a room with a cellar
     * would have its ground floor and its cellar merge into one connected region.
     */
    int floorNear(int x, int z, int fromY, Aabb box) {
        for (int delta = 0; delta <= STEP_TOLERANCE; delta++) {
            for (int sign : delta == 0 ? new int[]{0} : new int[]{1, -1}) {
                int y = fromY + sign * delta;
                if (y < box.minY() || y > box.maxY() - HEADROOM) {
                    continue;
                }
                if (canStandOn(x, y, z, box.maxY())) {
                    return y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * The lowest floor in a column that has headroom above it.
     *
     * <p>Bottom-up rather than top-down on purpose: in a two-storey room the lower floor is the one
     * the player walks in on, and top-down would seed the search on the roof.
     *
     * @return the floor's Y, or {@link Integer#MIN_VALUE} when the column has nowhere to stand
     */
    int lowestFloorIn(int x, int z, Aabb box) {
        for (int y = box.minY(); y <= box.maxY() - HEADROOM; y++) {
            if (canStandOn(x, y, z, box.maxY())) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** A solid block with {@link #HEADROOM} clear blocks above it, all inside the box. */
    private boolean canStandOn(int x, int y, int z, int maxY) {
        if (!probe.isFloor(x, y, z)) {
            return false;
        }
        for (int offset = 1; offset <= HEADROOM; offset++) {
            int above = y + offset;
            if (above > maxY || !probe.isClear(x, above, z)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Keeps the outward fill from stacking mobs on adjacent blocks.
     *
     * <p>Compared on the horizontal plane only. Two mobs one above the other on a staircase are
     * three blocks apart by distance but read as one column of mobs; the player sees a footprint,
     * not a sphere.
     */
    private boolean isFarEnough(Vec3i candidate, List<Vec3i> taken) {
        for (Vec3i other : taken) {
            int dx = other.x() - candidate.x();
            int dz = other.z() - candidate.z();
            if (dx * dx + dz * dz < MIN_SPACING * MIN_SPACING) {
                return false;
            }
        }
        return true;
    }
}
