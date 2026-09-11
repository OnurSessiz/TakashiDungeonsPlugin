package com.takashi.dungeons.api;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * A group of players, read-only.
 *
 * <p><b>A party lives in memory and a disconnect removes the player from it.</b> That is worth
 * knowing before an addon builds anything on party membership: there is no reconnect grace, and
 * nothing here survives a restart.
 *
 * <p>The leader is a member like any other — {@link #members()} includes them, and
 * {@link #size()} counts them. Reading the leader out of the list is what {@link #isLeader}
 * is for.
 */
public interface DungeonParty {

    /** The party number. Unique while it exists; not reused afterwards. */
    int id();

    /** The leader. Leadership passes to the longest-serving member when a leader leaves. */
    UUID leader();

    boolean isLeader(UUID player);

    /** Everyone in the party, the leader included. A copy. */
    List<UUID> members();

    /** The same list with the leader first — the order the sidebar shows. */
    List<UUID> ordered();

    boolean contains(UUID player);

    /** How many members, leader included. */
    int size();

    /** When it was formed, in epoch millis. */
    long createdAt();

    /**
     * The dungeon the party is currently bound to.
     *
     * <p>A party has <b>one</b> current dungeon: the first member through a gateway opens it and
     * every other gateway then leads the rest to the same one. Empty once that dungeon closes.
     */
    OptionalInt dungeonId();
}
