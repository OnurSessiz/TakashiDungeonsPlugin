package com.takashi.dungeons.api.event;

import com.takashi.dungeons.api.Dungeon;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A dungeon is gone: players out, entities removed, blocks wiped, slot released.
 *
 * <p>Fires <b>after</b> the teardown, not before it. There is nothing left to read from the world
 * by then — no mobs, no chests, no rooms — which is deliberate: an event that fired earlier would
 * invite listeners to do work inside a volume that is one tick from being deleted. What survives is
 * the {@link Dungeon} object itself, and it still answers: id, theme, seed, whether it was cleared.
 *
 * <p>{@link Dungeon#players()} is <b>empty</b> here. Everyone was sent home first, each one through
 * their own {@link DungeonLeaveEvent}. If you want to know who was in it, remember them from those
 * — or from {@link DungeonCompleteEvent}, which fires while they are all still inside.
 *
 * <p>This is the event to clean up anything an addon kept keyed on the dungeon's id. Every run ends
 * here, cleared or not.
 */
public final class DungeonCloseEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public DungeonCloseEvent(Dungeon dungeon) {
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
