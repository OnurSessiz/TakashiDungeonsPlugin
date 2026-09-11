package com.takashi.dungeons.api.event;

import com.takashi.dungeons.api.Dungeon;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A player has stopped being inside a dungeon.
 *
 * <p>Fires for <b>every</b> way out — walking out with {@code /tdungeons leave}, disconnecting,
 * leaving the dungeon world by any means, being sent home when the clock runs out, and being
 * evicted when the dungeon closes under them. There is one place in the plugin where membership
 * ends and this comes from it, so no route can quietly skip it.
 *
 * <p>Not cancellable: by the time it fires they are already out, and the commonest reason is that
 * they are no longer on the server to keep in.
 *
 * <p><b>{@link #player()} is often {@code null}</b> — a disconnect is the most frequent exit there
 * is. {@link #playerId()} always answers. A listener that only handles the online case is a
 * listener that misses most of them.
 */
public final class DungeonLeaveEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final @Nullable Player player;

    public DungeonLeaveEvent(Dungeon dungeon, UUID playerId, @Nullable Player player) {
        super(dungeon);
        this.playerId = playerId;
        this.player = player;
    }

    /** Who left. Always answers, online or not. */
    public UUID playerId() {
        return playerId;
    }

    /** The player, or {@code null} when they are no longer online — see the class note. */
    public @Nullable Player player() {
        return player;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
