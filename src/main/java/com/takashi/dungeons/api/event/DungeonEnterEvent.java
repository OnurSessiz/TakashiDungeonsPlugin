package com.takashi.dungeons.api.event;

import com.takashi.dungeons.api.Dungeon;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to be put inside a dungeon.
 *
 * <h2>The one cancellable event, and why</h2>
 * Fired <b>before</b> anything happens — before the membership, before the teleport. Cancel it and
 * the player stays where they are, the entry fails cleanly, and whatever asked for it (a gateway
 * click, {@code /tdungeons enter}) reports the refusal the same way it reports any other.
 *
 * <p>It is cancellable because the addons this API exists for need exactly this and nothing else:
 * "players below rank 5 cannot enter" is a TakashiRanks feature, and the alternative — letting them
 * in and throwing them out a tick later — is a worse answer that also has to be written. Every
 * other event is a report of something already true, which is why none of them can be cancelled.
 *
 * <p><b>Tell the player why.</b> A cancelled entry says nothing on its own; a gateway that silently
 * does nothing reads as a broken gateway. Send your own message from the listener.
 *
 * <p>A player joining a dungeon their party already opened fires this too, once per player.
 */
public final class DungeonEnterEvent extends DungeonEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private boolean cancelled;

    public DungeonEnterEvent(Dungeon dungeon, Player player) {
        super(dungeon);
        this.player = player;
    }

    /** The player going in. Still wherever they were when this fires. */
    public Player player() {
        return player;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
