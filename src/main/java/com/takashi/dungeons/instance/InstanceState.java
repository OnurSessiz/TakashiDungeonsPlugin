package com.takashi.dungeons.instance;

/**
 * The life of one instance, as a one-way sequence.
 *
 * <p>{@code BUILDING → ACTIVE → CLOSING → CLOSED} — a state never moves backwards, and the
 * transition is enforced rather than assumed. The reason is the same one that made
 * {@code LayoutNode}'s door states one-way: closing runs across several ticks (evict → clear →
 * unload → release) and a second {@code close} landing in the middle of the first would release
 * the slot twice and hand the same square to two parties.
 */
public enum InstanceState {

    /** Rooms are being generated and pasted. Nobody may enter yet. */
    BUILDING,

    /** Standing, walkable, players may be inside. */
    ACTIVE,

    /** Teardown started: players are out, blocks are being wiped. */
    CLOSING,

    /** Gone. The slot has been returned to the pool and may already hold another dungeon. */
    CLOSED
}
