package com.takashi.dungeons.generation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * A dungeon's layout: the accepted rooms, their door links, and the collision test.
 *
 * <p><b>Why this class exists:</b> with anchor-based placement rooms do not sit on fixed cells,
 * so overlap is <b>mathematically possible</b> ({@code generation.md} §2). A cell grid needed no
 * such class; this is the price of free placement.
 *
 * <p><b>Brute force is enough.</b> The largest dungeon is 20 rooms
 * ({@code generation.md} §6.1), so 20 box tests per new candidate. A spatial hash or octree
 * would be pointless complexity — a linear scan over a 20-element list costs less than the hash
 * itself.
 *
 * <p>The class is <b>independent of Bukkit</b>: the slot bounds are passed in as an
 * {@link Aabb}. That keeps the whole layout logic testable without starting a server.
 */
public final class DungeonLayout {

    private final Aabb bounds;
    private final List<LayoutNode> nodes = new ArrayList<>();

    /**
     * @param bounds the instance slot's bounds — no room may extend beyond them, so that two
     *               parties' blocks never intersect
     */
    public DungeonLayout(Aabb bounds) {
        this.bounds = bounds;
    }

    public Aabb bounds() {
        return bounds;
    }

    public List<LayoutNode> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public int size() {
        return nodes.size();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public LayoutNode node(int id) {
        return nodes.get(id);
    }

    /** The graph's root — the entrance room. {@code null} when empty. */
    public LayoutNode root() {
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    /**
     * Whether a candidate room fits here — {@code generation.md} §5.2 step 5.
     *
     * <p>There are two distinct rejection reasons and both are needed: exceeding the slot bounds
     * (it would reach into a neighbouring instance) and intersecting a placed room (the dungeon
     * would break against itself).
     *
     * @return {@code null} if it fits, otherwise a short explanation of why it does not
     */
    public String rejectReason(Aabb candidate) {
        if (!contains(bounds, candidate)) {
            return "exceeds the slot bounds";
        }
        for (LayoutNode node : nodes) {
            if (node.bounds().intersects(candidate)) {
                return "collides with room#" + node.id() + " (" + node.template().name() + ")";
            }
        }
        return null;
    }

    public boolean fits(Aabb candidate) {
        return rejectReason(candidate) == null;
    }

    /** Accepts the room into the layout. The collision test is the CALLER's responsibility. */
    public LayoutNode add(PlacedRoom room, int depth) {
        LayoutNode node = new LayoutNode(nodes.size(), room, depth);
        nodes.add(node);
        return node;
    }

    /**
     * Places the first room — the entrance, seated on the given origin without rotation.
     *
     * @throws IllegalStateException if the room does not fit the slot (for the entrance room,
     *                               silent failure is unacceptable: the dungeon never gets built
     *                               at all)
     */
    public LayoutNode addRoot(RoomTemplate template, Vec3i origin, Rotation rotation) {
        if (!nodes.isEmpty()) {
            throw new IllegalStateException("A root room already exists: " + root());
        }
        PlacedRoom room = PlacedRoom.of(template, rotation, origin);
        String reason = rejectReason(room.bounds());
        if (reason != null) {
            throw new IllegalStateException("Entrance room '" + template.name()
                    + "' does not fit the slot: " + reason + ". Room is " + template.describeSize()
                    + ", slot is " + bounds.sizeX() + "×" + bounds.sizeZ() + ".");
        }
        return add(room, 0);
    }

    /** Links two doors to each other — a link is always recorded in both directions. */
    public void link(int nodeA, int doorA, int nodeB, int doorB) {
        nodes.get(nodeA).link(doorA, nodeB, doorB);
        nodes.get(nodeB).link(doorB, nodeA, doorA);
    }

    public void markDead(int nodeId, int doorIndex) {
        nodes.get(nodeId).markDead(doorIndex);
    }

    /** Every room's doors that are still waiting to be filled, in room order. */
    public List<OpenDoor> openDoors() {
        List<OpenDoor> open = new ArrayList<>();
        for (LayoutNode node : nodes) {
            open.addAll(node.openDoors());
        }
        return open;
    }

    public int openDoorCount() {
        int count = 0;
        for (LayoutNode node : nodes) {
            count += node.openDoors().size();
        }
        return count;
    }

    public int deadDoorCount() {
        int count = 0;
        for (LayoutNode node : nodes) {
            count += node.deadDoors().size();
        }
        return count;
    }

    /**
     * Checks that the layout is internally consistent — for tests and debugging.
     *
     * <p>Not called on the generation path; it exists to say <i>which</i> invariant broke when
     * something is wrong. In procedural generation the most expensive failure is broken output
     * being accepted silently.
     *
     * @return the problems found; an empty list means the layout is consistent
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();

        for (int i = 0; i < nodes.size(); i++) {
            LayoutNode a = nodes.get(i);

            if (!contains(bounds, a.bounds())) {
                problems.add("room#" + a.id() + " exceeds the slot bounds: " + a.bounds());
            }
            for (int j = i + 1; j < nodes.size(); j++) {
                LayoutNode b = nodes.get(j);
                if (a.bounds().intersects(b.bounds())) {
                    problems.add("room#" + a.id() + " collides with room#" + b.id());
                }
            }

            for (int d = 0; d < a.doorCount(); d++) {
                if (a.doorState(d) != DoorState.CONNECTED) {
                    continue;
                }
                int otherId = a.linkedNode(d);
                if (otherId < 0 || otherId >= nodes.size()) {
                    problems.add("room#" + a.id() + " door#" + d + " links to an invalid node: "
                            + otherId);
                    continue;
                }
                // Back to back: the two anchors must be exactly one block apart and face
                // EACH OTHER.
                LayoutNode other = nodes.get(otherId);
                Vec3i mine = a.room().doorAnchor(d);
                Vec3i expected = mine.plus(a.room().doorOutward(d).step());
                boolean matched = false;
                for (int e = 0; e < other.doorCount(); e++) {
                    if (other.linkedNode(e) == a.id() && other.room().doorAnchor(e).equals(expected)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    problems.add("the passage between room#" + a.id() + " door#" + d
                            + " and room#" + otherId + " is misaligned (expected " + expected + ")");
                }
            }
        }

        if (!nodes.isEmpty()) {
            int reached = reachableCount();
            if (reached != nodes.size()) {
                problems.add("graph is disconnected: " + reached + " of " + nodes.size()
                        + " rooms are reachable from the entrance");
            }
        }
        return problems;
    }

    /** How many rooms are reachable from the entrance by following door links. */
    private int reachableCount() {
        boolean[] seen = new boolean[nodes.size()];
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        seen[0] = true;
        int count = 0;
        while (!queue.isEmpty()) {
            LayoutNode node = nodes.get(queue.poll());
            count++;
            for (int d = 0; d < node.doorCount(); d++) {
                int next = node.linkedNode(d);
                if (next >= 0 && !seen[next]) {
                    seen[next] = true;
                    queue.add(next);
                }
            }
        }
        return count;
    }

    /** Whether the {@code outer} box fully contains {@code inner}. */
    private static boolean contains(Aabb outer, Aabb inner) {
        return inner.minX() >= outer.minX() && inner.maxX() <= outer.maxX()
                && inner.minY() >= outer.minY() && inner.maxY() <= outer.maxY()
                && inner.minZ() >= outer.minZ() && inner.maxZ() <= outer.maxZ();
    }
}
