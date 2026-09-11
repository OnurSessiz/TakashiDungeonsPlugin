package com.takashi.dungeons.api.event;

import com.takashi.dungeons.api.Dungeon;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A dungeon has been built and is ready to be walked into.
 *
 * <p>Fired <b>after</b> the rooms are pasted and after the mobs, chests and merchant are placed —
 * the same tick the door opens for whoever asked for it. Not earlier: a listener that ran against a
 * half-pasted dungeon would be reading rooms that do not exist yet.
 *
 * <p>Not cancellable. By the time this fires the blocks are in the world and a slot is held;
 * "cancelling" would mean a teardown pretending to be a veto. To stop a particular player from
 * getting in, cancel {@link DungeonEnterEvent} instead.
 */
public final class DungeonCreateEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public DungeonCreateEvent(Dungeon dungeon) {
        super(dungeon);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
