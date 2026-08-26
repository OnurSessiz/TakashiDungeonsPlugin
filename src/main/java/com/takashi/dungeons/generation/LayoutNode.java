package com.takashi.dungeons.generation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A room accepted into the layout — its geometry plus the graph state of its doors.
 *
 * <p><b>Why this is separate from {@link PlacedRoom}:</b> {@code PlacedRoom} is immutable pure
 * geometry and is computed <i>before</i> a placement is accepted (the collision test needs its
 * box). Whether a door is connected, open or dead only comes into existence <i>after</i>
 * acceptance, and changes over time. Merging the two would create a "half-built room" state —
 * a rejected candidate would have door states too.
 *
 * <p>{@link #depth} is how many rooms away from the entrance this one is. 1C does not use it;
 * it is kept here so 1D can guarantee the critical path length ({@code generation.md} §6.2).
 */
public final class LayoutNode {

    private final int id;
    private final PlacedRoom room;
    private final int depth;
    private final DoorState[] doorStates;
    /** Door index → the node id it links to, or -1 if not connected. */
    private final int[] linkedNode;
    /** Door index → the door index on the far room, or -1 if not connected. */
    private final int[] linkedDoor;

    LayoutNode(int id, PlacedRoom room, int depth) {
        this.id = id;
        this.room = room;
        this.depth = depth;
        int doors = room.doorCount();
        this.doorStates = new DoorState[doors];
        this.linkedNode = new int[doors];
        this.linkedDoor = new int[doors];
        Arrays.fill(doorStates, DoorState.OPEN);
        Arrays.fill(linkedNode, -1);
        Arrays.fill(linkedDoor, -1);
    }

    public int id() {
        return id;
    }

    public PlacedRoom room() {
        return room;
    }

    public int depth() {
        return depth;
    }

    public Aabb bounds() {
        return room.bounds();
    }

    public RoomTemplate template() {
        return room.template();
    }

    public int doorCount() {
        return doorStates.length;
    }

    public DoorState doorState(int index) {
        return doorStates[index];
    }

    public int linkedNode(int index) {
        return linkedNode[index];
    }

    /** This room's untried doors — side branches grow from these. */
    public List<OpenDoor> openDoors() {
        List<OpenDoor> open = new ArrayList<>();
        for (int i = 0; i < doorStates.length; i++) {
            if (doorStates[i] == DoorState.OPEN) {
                open.add(new OpenDoor(id, i, room.doorAnchor(i), room.doorOutward(i)));
            }
        }
        return open;
    }

    /** Doors that need plugging — {@code generation.md} §7. */
    public List<Integer> deadDoors() {
        List<Integer> dead = new ArrayList<>();
        for (int i = 0; i < doorStates.length; i++) {
            if (doorStates[i] == DoorState.DEAD) {
                dead.add(i);
            }
        }
        return dead;
    }

    void markDead(int index) {
        require(index, DoorState.OPEN);
        doorStates[index] = DoorState.DEAD;
    }

    void link(int index, int otherNode, int otherDoor) {
        require(index, DoorState.OPEN);
        doorStates[index] = DoorState.CONNECTED;
        linkedNode[index] = otherNode;
        linkedDoor[index] = otherDoor;
    }

    /**
     * State transitions are one-way: OPEN → CONNECTED or OPEN → DEAD. There is no way back.
     * Attaching two rooms to the same door would silently lose the second one, so we throw.
     */
    private void require(int index, DoorState expected) {
        if (doorStates[index] != expected) {
            throw new IllegalStateException("room#" + id + " door#" + index + " is "
                    + doorStates[index] + ", expected " + expected
                    + " (" + template().name() + ")");
        }
    }

    @Override
    public String toString() {
        return "#" + id + " " + room + " depth=" + depth + " doors=" + Arrays.toString(doorStates);
    }
}
