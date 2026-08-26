package com.takashi.dungeons.generation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Attaches a room to an open door: picks candidates, tests for collision, places the first one
 * that passes — {@code generation.md} §5.2 and §5.3.
 *
 * <p><b>How this differs from 1B.</b> {@link RoomTemplate#attachTo} is pure geometry: "this
 * room seats here, at this angle." The question this class asks is different:
 * <b>"may it seat, and which room should be chosen?"</b> The split keeps the geometry testable
 * without access to a world or to randomness.
 *
 * <p><b>Two-stage selection</b> ({@code generation.md} §5.4):
 * <ol>
 *   <li>A template is drawn <b>by weight</b> and removed from the pool (no replacement)</li>
 *   <li>That template's doors are ordered and tried one by one</li>
 *   <li>If none fit, the template is out and we go back to step 1</li>
 *   <li>If the pool empties, the door is marked <b>DEAD</b> → §7 plug</li>
 * </ol>
 */
public final class RoomPlacer {

    /**
     * Strength of the turn bias — {@code generation.md} §6.4.
     *
     * <p>Applied at the door selection stage, <b>not</b> at template selection. The reason is
     * §5.4: touching the template stage would corrupt the meaning of {@code weight} and
     * undermine the decision to keep it independent of door count.
     *
     * <p>1.0 = no penalty. Higher values push straight continuations further back.
     */
    private final double turnBias;

    private final RandomGenerator random;
    /**
     * {@link Collections#shuffle} wants the legacy {@link java.util.Random}. A single instance
     * is kept: creating a new one per call would give different results from the same seed and
     * make generation non-reproducible — which would make it undebuggable.
     */
    private final java.util.Random shuffleRandom;

    public RoomPlacer(RandomGenerator random, double turnBias) {
        this.random = random;
        this.turnBias = Math.max(1.0, turnBias);
        this.shuffleRandom = (random instanceof java.util.Random legacy)
                ? legacy
                : new java.util.Random(random.nextLong());
    }

    /** The outcome of one placement attempt. */
    public record Attempt(LayoutNode placed, int childDoor, int candidatesTried, String failReason) {

        public boolean success() {
            return placed != null;
        }
    }

    /**
     * Tries to attach a room to the given open door.
     *
     * <p>On success the room joins the layout and the two doors are linked to each other. On
     * failure the door is marked DEAD — it is never retried, and it gets plugged in 1D.
     *
     * @param layout the layout (mutated)
     * @param door   the open door to fill
     * @param pool   candidate templates — <b>this method does not modify the list</b>, it works
     *               on a copy
     */
    public Attempt fill(DungeonLayout layout, OpenDoor door, List<RoomTemplate> pool) {
        List<RoomTemplate> remaining = new ArrayList<>(pool);
        LayoutNode parent = layout.node(door.nodeId());
        int tried = 0;
        String lastReason = "candidate pool was empty";

        RoomTemplate template;
        while ((template = RoomLibrary.drawWeighted(remaining, random)) != null) {
            for (int childDoor : orderDoors(template, door.outward())) {
                tried++;
                PlacedRoom candidate = template.attachTo(childDoor, door.anchor(), door.outward());
                String reason = layout.rejectReason(candidate.bounds());
                if (reason != null) {
                    lastReason = template.name() + " door#" + childDoor + ": " + reason;
                    continue;
                }
                LayoutNode child = layout.add(candidate, parent.depth() + 1);
                layout.link(parent.id(), door.doorIndex(), child.id(), childDoor);
                return new Attempt(child, childDoor, tried, null);
            }
        }

        layout.markDead(door.nodeId(), door.doorIndex());
        return new Attempt(null, -1, tried, lastReason);
    }

    /**
     * Puts the template's doors into the order they will be tried.
     *
     * <p>The shuffle provides variety; the turn bias then pushes "straight continuation"
     * options back. A door option counts as <b>straight</b> when, connecting through it, some
     * <i>other</i> door of the room points along the parent's outward direction — that is, the
     * chain would carry on in the same heading.
     *
     * <p>In a room with two opposite doors (a corridor) both options are straight, so the
     * penalty has no effect. That is an honest outcome: that room continues straight no matter
     * what you do. The real mechanism behind §6.4 is the door offsets anyway.
     */
    private List<Integer> orderDoors(RoomTemplate template, Direction parentOutward) {
        int count = template.doorCount();
        List<Integer> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            order.add(i);
        }
        Collections.shuffle(order, shuffleRandom);

        if (turnBias <= 1.0 || count < 2) {
            return order;
        }
        // Stable partition: turning options in front, straight ones behind; the shuffled order
        // is preserved within each group.
        List<Integer> turning = new ArrayList<>(count);
        List<Integer> straight = new ArrayList<>(count);
        for (int index : order) {
            (continuesStraight(template, index, parentOutward) ? straight : turning).add(index);
        }
        // The penalty is not absolute: the larger turnBias gets, the smaller the chance a
        // straight option jumps back to the front.
        if (!turning.isEmpty() && !straight.isEmpty() && random.nextDouble() < 1.0 / turnBias) {
            turning.addAll(0, straight);
            return turning;
        }
        turning.addAll(straight);
        return turning;
    }

    /**
     * Whether, connecting through {@code childDoor}, another of the room's doors points along
     * the parent's direction.
     */
    private static boolean continuesStraight(RoomTemplate template, int childDoor,
                                             Direction parentOutward) {
        Rotation rotation = Rotation.align(parentOutward, template.door(childDoor).wall());
        for (DoorAnchor other : template.doors()) {
            if (other.index() == childDoor) {
                continue;
            }
            if (rotation.apply(other.wall()) == parentOutward) {
                return true;
            }
        }
        return false;
    }
}
