package com.takashi.dungeons.api.event;

import com.takashi.dungeons.api.Dungeon;
import org.bukkit.event.Event;

/**
 * The base of everything that happens to a dungeon.
 *
 * <h2>All of these fire on the main thread</h2>
 * Every one of them is raised from the moment the thing actually happened — a click, a death, a
 * teardown — and all of those are main-thread moments. That means a listener may touch the world
 * freely, and it also means a listener that blocks is holding up the server. Do the slow half
 * somewhere else.
 *
 * <h2>Accessors are named without {@code get}</h2>
 * {@code event.dungeon()}, not {@code event.getDungeon()}. It differs from Bukkit's own events and
 * matches the rest of this API, which is the consistency that costs less to live with.
 *
 * <h2>What is guaranteed about the dungeon</h2>
 * It is the real one, not a snapshot: read it during the event and it tells you the truth about
 * that moment. Held past the event it keeps answering, but about a dungeon that may since have
 * closed — {@link Dungeon#isActive()} is how you ask.
 */
public abstract class DungeonEvent extends Event {

    private final Dungeon dungeon;

    protected DungeonEvent(Dungeon dungeon) {
        this.dungeon = dungeon;
    }

    /** The dungeon this is about. Never {@code null}. */
    public Dungeon dungeon() {
        return dungeon;
    }
}
