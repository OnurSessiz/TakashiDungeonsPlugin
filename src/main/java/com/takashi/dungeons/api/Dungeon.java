package com.takashi.dungeons.api;

import java.util.List;
import java.util.UUID;

/**
 * One live dungeon, read-only.
 *
 * <p>A view onto the plugin's own instance object — see the note in {@link TakashiDungeonsAPI}
 * about not implementing these. What it deliberately does <b>not</b> expose is the room graph, the
 * grid slot and the generator's result: those are the shape of today's generation algorithm, and
 * freezing them would freeze the algorithm.
 *
 * <p>The identity of a dungeon is {@link #theme()} + {@link #sizeKey()} + {@link #seed()}. Those
 * three regenerate the same rooms in the same places, which is the one fact about generation that
 * is stable enough to promise.
 */
public interface Dungeon {

    /**
     * The instance number. <b>Never reused</b> — a slot is a place and may be handed out again, an
     * instance is an event. Safe to use as a key for anything an addon remembers per run.
     */
    int id();

    /** Which theme (room folder) it was drawn from. */
    String theme();

    /** {@code "small"}, {@code "medium"} or {@code "large"}. A string, not an enum: the set of
     * sizes belongs to the generator and is free to grow. */
    String sizeKey();

    /** The seed it was generated from. Same theme + size + seed, same dungeon. */
    long seed();

    /** How many rooms were actually placed. */
    int roomCount();

    /** Who is registered as inside, in the order they entered. A copy — changing it changes nothing. */
    List<UUID> players();

    /** Whether this player is registered inside. See {@link TakashiDungeonsAPI#dungeonOf}. */
    boolean contains(UUID player);

    /** Whether the boss has been killed. A cleared dungeon is still alive, on a shortened clock. */
    boolean isCleared();

    /** When the boss died, in epoch millis, or {@code -1}. */
    long clearedAt();

    /** Milliseconds until it closes. Killing the boss can only ever shorten this. */
    long remainingMillis();

    /** What the countdown bar measures against — the full run, or the grace period after a clear. */
    long totalMillis();

    /** Whether it is still open for business. A dungeon being torn down answers {@code false}. */
    boolean isActive();
}
