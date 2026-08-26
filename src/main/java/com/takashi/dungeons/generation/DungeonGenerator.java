package com.takashi.dungeons.generation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Builds the dungeon graph — {@code generation.md} §6.
 *
 * <h2>The critical path comes first; the boss is ASSIGNED last</h2>
 *
 * If you scatter rooms randomly and then say "put the boss in the furthest one", you have no
 * control over how many rooms away from the entrance the boss ends up. Building the path first
 * <b>puts a floor under playtime</b>: randomness for variety, skeleton for guarantees.
 *
 * <h2>No single-door rooms in the path pool</h2>
 *
 * Measured in 1C (the callout in {@code generation.md} §6.2): the "fill every open door"
 * strategy only reaches a 12-room target <b>70%</b> of the time, and 86% of the stalls are not
 * collisions but the door frontier running dry — a branching process going extinct. When a
 * single-door room is drawn, that branch dies on the spot.
 *
 * <p>Filtering single-door templates out while building the path lifts the same measurement to
 * <b>97%</b>. They stay allowed on side branches — ending there is what you want.
 *
 * <h2>A stall means a retry</h2>
 *
 * If the path cannot reach its target length, the whole attempt is thrown away and generation
 * restarts with a new derived seed. Silently accepting a short dungeon would break the promise
 * made to the user: if they asked for "medium", they should get medium. When the attempts run
 * out the <b>best</b> one is used and the result is reported with a warning — a small dungeon is
 * never handed over silently.
 */
public final class DungeonGenerator {

    private final RoomLibrary library;
    private final double turnBias;
    private final int maxAttempts;

    public DungeonGenerator(RoomLibrary library, double turnBias, int maxAttempts) {
        this.library = library;
        this.turnBias = turnBias;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /**
     * The generation result.
     *
     * @param layout           the layout that was built
     * @param size             the requested size
     * @param targetRooms      the target room count
     * @param targetPathLength the target critical path length (entrance and boss included)
     * @param pathLength       the path length actually reached
     * @param bossNodeId       node id of the boss room, or -1 if there is none
     * @param attemptsUsed     how many attempts were spent
     * @param seed             the seed needed to reproduce this generation
     * @param warning          an explanation if something did not come out as asked, else
     *                         {@code null}
     */
    public record Result(DungeonLayout layout, DungeonSize size, int targetRooms,
                         int targetPathLength, int pathLength, int bossNodeId,
                         int attemptsUsed, long seed, String warning) {

        public int rooms() {
            return layout.size();
        }

        public boolean perfect() {
            return warning == null;
        }

        /** Doors to plug — every OPEN and DEAD one ({@code generation.md} §7). */
        public List<PlugTarget> plugTargets() {
            List<PlugTarget> targets = new ArrayList<>();
            for (LayoutNode node : layout.nodes()) {
                for (int i = 0; i < node.doorCount(); i++) {
                    DoorState state = node.doorState(i);
                    if (state == DoorState.CONNECTED) {
                        continue;
                    }
                    targets.add(new PlugTarget(node.room().doorAnchor(i),
                            node.room().doorOutward(i), node.bounds(),
                            state == DoorState.DEAD));
                }
            }
            return targets;
        }
    }

    /**
     * Generates a dungeon. Failed attempts are discarded and the best one is returned.
     *
     * @param bounds the instance slot's bounds
     * @param origin the entrance room's origin (usually the slot centre)
     * @param size   the requested size
     * @param seed   the master seed — the same seed gives the same dungeon
     */
    public Result generate(Aabb bounds, Vec3i origin, DungeonSize size, long seed) {
        if (!library.isUsable()) {
            return new Result(new DungeonLayout(bounds), size, 0, 0, 0, -1, 0, seed,
                    library.describeProblem());
        }

        Result best = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            // The attempt seed is DERIVED from the master seed, so the whole generation stays
            // reproducible from a single seed. If a retry drew a fresh random seed, bringing
            // back "that broken dungeon" would be impossible.
            Result candidate = attemptOnce(bounds, origin, size, seed, attempt);
            if (candidate.perfect()) {
                return candidate;
            }
            if (best == null || isBetter(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    /** A longer path wins; on a tie, more rooms wins. */
    private static boolean isBetter(Result a, Result b) {
        if (a.pathLength() != b.pathLength()) {
            return a.pathLength() > b.pathLength();
        }
        return a.rooms() > b.rooms();
    }

    private Result attemptOnce(Aabb bounds, Vec3i origin, DungeonSize size,
                               long seed, int attempt) {
        // Seeds.derive breaks the correlation between consecutive seeds. new Random(seed)
        // CANNOT be used here -- its first nextInt(small) call returns the same value across
        // consecutive seeds, which would pin a small dungeon's room count to one value instead
        // of a range. The measurement and the numbers are in the Seeds class comment.
        RandomGenerator random = Seeds.derive(seed, attempt);
        RoomPlacer placer = new RoomPlacer(random, turnBias);

        int targetRooms = size.pickRoomCount(random);
        int targetPath = DungeonSize.criticalPathLength(targetRooms);
        DungeonLayout layout = new DungeonLayout(bounds);

        // ---- 1) Entrance room
        RoomTemplate entrance = pickEntrance(random);
        if (entrance == null) {
            return new Result(layout, size, targetRooms, targetPath, 0, -1,
                    attempt + 1, seed, library.describeProblem());
        }
        try {
            layout.addRoot(entrance, origin, Rotation.NONE);
        } catch (IllegalStateException e) {
            return new Result(layout, size, targetRooms, targetPath, 0, -1,
                    attempt + 1, seed, e.getMessage());
        }

        // ---- 2) Critical path — entrance + (targetPath-2) normal rooms + boss
        // Single-door templates are NOT in this pool: drawing one kills the branch on the
        // spot (the §6.2 measurement).
        List<RoomTemplate> pathPool = library.branchingPool();
        if (pathPool.isEmpty()) {
            // Out of the box: if the mapper hasn't drawn a multi-door room yet, don't stop
            // generating — fall back to the full pool. The path guarantee weakens, but a
            // dungeon still comes out.
            pathPool = library.normalPool();
        }

        // targetPath-1 rooms go on the path including the entrance; the boss completes the last.
        int pathRooms = 1;                  // the entrance room is the path's first node
        LayoutNode tip = layout.root();
        String warning = null;

        while (pathRooms < targetPath - 1) {
            OpenDoor door = pickOpenDoor(tip, random);
            if (door == null) {
                warning = "critical path stalled at " + pathRooms + "/" + targetPath
                        + " rooms: no open door left on " + tip.template().name();
                break;
            }
            RoomPlacer.Attempt placed = placer.fill(layout, door, pathPool);
            if (!placed.success()) {
                warning = "critical path stalled at " + pathRooms + "/" + targetPath
                        + " rooms: " + placed.failReason();
                break;
            }
            tip = placed.placed();
            pathRooms++;
        }

        // ---- 3) Side branches — the remaining quota, but ONE ROOM IS RESERVED FOR THE BOSS
        // (§6.3).
        //
        // Side branches grow BEFORE the boss. The order is deliberate and fixes two things at
        // once:
        //
        // (a) When targetPath == 2 (small, target 3 rooms) the path is nothing but the
        //     entrance. Had the boss attached first, the single-door entrance would spend its
        //     door on the boss, and since the boss is terminal there would be NO open door left
        //     for a side branch — a 3-room dungeon could not be built. Measured: the small
        //     target of 3 never appeared, it always came out 4-6.
        //
        // (b) The boss gets far more candidate doors. Attaching first, it tried a single door,
        //     and if the 33x33 boss room collided the dungeon came out WITH NO BOSS (4 times in
        //     2000 medium generations). A bossless dungeon gives the player nothing to aim at.
        growBranches(layout, placer, random, targetRooms - 1, -1);

        // ---- 4) The boss — not random, ASSIGNED: to the open door FURTHEST from the entrance.
        //
        // This is what the "last node of the path" rule means in practice. Picking the deepest
        // door maximizes the boss's distance from the entrance, and that distance is exactly
        // what §6.2 wants to guarantee. Rather than committing to one door, all of them are
        // tried in depth order — which removes the case where a single collision drops the boss.
        int bossNodeId = -1;
        if (library.bosses().isEmpty()) {
            // Out-of-the-box guarantee: with no boss room drawn, generation still completes.
            warning = "no boss room template — the dungeon was generated without one";
        } else {
            bossNodeId = attachBoss(layout, placer, random);
            if (bossNodeId < 0) {
                warning = "the boss room did not fit any open door ("
                        + layout.openDoorCount() + " doors tried)";
            }
        }

        // Critical path length = the number of rooms on the route from entrance to boss.
        // With no boss, the route to the deepest room.
        int pathLength = bossNodeId >= 0
                ? layout.node(bossNodeId).depth() + 1
                : deepestDepth(layout) + 1;

        if (warning == null && pathLength < targetPath) {
            warning = "critical path came up short: " + pathLength + "/" + targetPath;
        }
        if (warning == null && layout.size() < targetRooms) {
            warning = "room quota not met: " + layout.size() + "/" + targetRooms
                    + " (ran out of space)";
        }
        return new Result(layout, size, targetRooms, targetPath, pathLength,
                bossNodeId, attempt + 1, seed, warning);
    }

    /**
     * Attaches the boss room to the open door furthest from the entrance.
     *
     * <p>Doors are tried in <b>descending depth</b> order and the first one that seats wins. The
     * boss therefore goes as far away as possible, and a single door colliding no longer
     * produces a bossless dungeon.
     *
     * @return the boss node id, or -1 if it fit no door at all
     */
    private int attachBoss(DungeonLayout layout, RoomPlacer placer, RandomGenerator random) {
        List<OpenDoor> doors = new ArrayList<>(layout.openDoors());
        doors.sort((a, b) -> Integer.compare(
                layout.node(b.nodeId()).depth(), layout.node(a.nodeId()).depth()));

        for (OpenDoor door : doors) {
            if (layout.node(door.nodeId()).doorState(door.doorIndex()) != DoorState.OPEN) {
                continue;
            }
            RoomPlacer.Attempt placed = placer.fill(layout, door, library.bosses());
            if (placed.success()) {
                return placed.placed().id();
            }
            // When fill() fails it has already marked the door DEAD — it won't be tried again.
        }
        return -1;
    }

    /** The greatest depth in the layout. */
    private static int deepestDepth(DungeonLayout layout) {
        int max = 0;
        for (LayoutNode node : layout.nodes()) {
            max = Math.max(max, node.depth());
        }
        return max;
    }

    /**
     * Fills the remaining quota with side branches — {@code generation.md} §6.3.
     *
     * <p>When the critical path enters a room, that room's other doors are left unused; branching
     * starts from those. The player walks in and sees three exits: one to the boss, two to loot.
     * Not knowing which is which is what produces the maze feeling.
     *
     * <p>The <b>full pool</b> is used here, dead ends included. A side branch ending is a
     * desirable thing; what is a problem on the path is a feature here.
     *
     * <p>Breadth first: depth first would produce a single long tail and the layout would tangle
     * itself quickly.
     */
    private void growBranches(DungeonLayout layout, RoomPlacer placer,
                              RandomGenerator random, int targetRooms, int bossNodeId) {
        Deque<OpenDoor> queue = new ArrayDeque<>();
        for (LayoutNode node : layout.nodes()) {
            if (node.id() != bossNodeId) {
                queue.addAll(node.openDoors());
            }
        }

        while (layout.size() < targetRooms && !queue.isEmpty()) {
            OpenDoor door = queue.poll();
            if (layout.node(door.nodeId()).doorState(door.doorIndex()) != DoorState.OPEN) {
                continue;
            }
            RoomPlacer.Attempt placed = placer.fill(layout, door, library.normalPool());
            if (placed.success()) {
                queue.addAll(placed.placed().openDoors());
            }
        }
    }

    private RoomTemplate pickEntrance(RandomGenerator random) {
        // With no entrance room drawn, one from the normal pool is used — the out-of-the-box
        // guarantee.
        List<RoomTemplate> pool = library.entrances().isEmpty()
                ? library.normalPool()
                : library.entrances();
        return RoomLibrary.pickWeighted(pool, random);
    }

    /** A random one of the room's open doors; {@code null} if it has none. */
    private static OpenDoor pickOpenDoor(LayoutNode node, RandomGenerator random) {
        List<OpenDoor> open = new ArrayList<>(node.openDoors());
        if (open.isEmpty()) {
            return null;
        }
        return open.get(random.nextInt(open.size()));
    }

    /** Summarizes the layout line by line — for command output. */
    public static List<String> describe(DungeonLayout layout, int bossNodeId) {
        List<String> lines = new ArrayList<>();
        for (LayoutNode node : layout.nodes()) {
            StringBuilder doors = new StringBuilder();
            for (int i = 0; i < node.doorCount(); i++) {
                doors.append(switch (node.doorState(i)) {
                    case CONNECTED -> "→#" + node.linkedNode(i);
                    case DEAD -> "dead";
                    case OPEN -> "open";
                }).append(i == node.doorCount() - 1 ? "" : " ");
            }
            String tag = node.id() == 0 ? " [ENTRANCE]" : (node.id() == bossNodeId ? " [BOSS]" : "");
            lines.add("#" + node.id() + " " + node.template().name()
                    + " rot=" + node.room().rotation().degrees()
                    + " d=" + node.depth()
                    + " @" + node.room().origin()
                    + "  [" + doors + "]" + tag);
        }
        return Collections.unmodifiableList(lines);
    }
}
