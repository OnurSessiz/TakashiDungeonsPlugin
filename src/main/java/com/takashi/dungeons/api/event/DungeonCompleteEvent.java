package com.takashi.dungeons.api.event;

import com.takashi.dungeons.api.Dungeon;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The boss is dead — the dungeon has been cleared.
 *
 * <p>Fires <b>once</b> per dungeon, at the kill, while everyone is still registered inside. That
 * ordering is the point: {@link #participants()} is the membership as it stood at the moment of the
 * win, not after the room empties. This is the event to hand out a reward on.
 *
 * <p>Who counts as having cleared it: <b>everyone inside when the boss died</b>. A player who left
 * before the fight did not clear it; one who joined for the last hit did. The same rule the reward
 * chest already follows.
 *
 * <p>The dungeon does not close here. Killing the boss shortens the clock to a grace period so the
 * party can pick up what the fight dropped; {@link DungeonCloseEvent} comes later.
 *
 * <h2>This arrives BEFORE the boss's own {@link DungeonMobKillEvent}</h2>
 * Measured, documented, and not going to change. The reason is the rule that makes the rest of the
 * API trustworthy: the plugin subscribes to its own signals before the API does, so that by the
 * time any event reaches an addon the plugin has already finished reacting to it — the statistics
 * are counted, the reward chest is placed, the clock is shortened. Clearing is handled inside the
 * plugin's own kill handler, so it completes, and raises this, before the bridge that reports the
 * kill is reached.
 *
 * <p>What it means in practice: both events fire for a boss, this one first, and neither is a
 * substitute for the other. Do not write a listener that depends on the reverse order.
 */
public final class DungeonCompleteEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final List<UUID> participants;
    private final @Nullable Player killer;
    private final @Nullable String bossId;

    public DungeonCompleteEvent(Dungeon dungeon, List<UUID> participants,
                                @Nullable Player killer, @Nullable String bossId) {
        super(dungeon);
        this.participants = List.copyOf(participants);
        this.killer = killer;
        this.bossId = bossId;
    }

    /** Everyone registered inside at the kill, in entry order. Immutable. */
    public List<UUID> participants() {
        return participants;
    }

    /**
     * Who struck the killing blow, or {@code null}.
     *
     * <p>A boss can die to fall damage, to fire, or to another mob. The clear is real either way,
     * which is why this is nullable rather than a reason to withhold the event.
     */
    public @Nullable Player killer() {
        return killer;
    }

    /** The {@code mobs.yml} id of the boss, or {@code null} if that entry no longer exists. */
    public @Nullable String bossId() {
        return bossId;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
