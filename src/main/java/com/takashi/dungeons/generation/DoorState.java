package com.takashi.dungeons.generation;

/**
 * Runtime state of a door on a placed room — {@code generation.md} §8.
 *
 * <p>Never stored in a file, only in memory. The template says "this room has 3 doors";
 * which of them gets filled depends on where the room lands in the graph.
 */
public enum DoorState {

    /** Not tried yet, or still to be tried — a side branch can grow from here. */
    OPEN,

    /** Connected to another room; the passage is open. */
    CONNECTED,

    /**
     * Tried, and no candidate fit (all of them collided or exceeded the slot bounds).
     * It opens into the void, so {@code generation.md} §7 requires it to be plugged.
     */
    DEAD
}
