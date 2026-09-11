package com.takashi.dungeons.api.event;

import com.takashi.dungeons.api.Dungeon;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A mob belonging to a dungeon has died.
 *
 * <p>Fires for every dungeon mob, boss included — {@link #boss()} is the one thing most listeners
 * branch on, so it is answered here rather than making each of them read a tag back. The boss's
 * death <b>also</b> raises {@link DungeonCompleteEvent}, and that one arrives <b>first</b>; see the
 * note on it for why. They are different facts about the same moment and neither replaces the
 * other.
 *
 * <p>By the time this reaches a listener the plugin has already counted the kill, so
 * {@code api.stats(killer).mobKills()} read from here includes it.
 *
 * <p>Only mobs this plugin spawned into an instance. A zombie an operator summoned into the dungeon
 * world is not one of ours and is not reported.
 *
 * <p><b>Read {@link #entity()} inside the listener.</b> It still carries its equipment and position
 * now; it is removed from the world as soon as the death finishes.
 *
 * <p>Not cancellable: the mob is already dead. To change what it drops, edit {@code loot.yml} — and
 * to add your own drops, listen to Bukkit's own {@code EntityDeathEvent}, which is where dropping
 * is decided.
 */
public final class DungeonMobKillEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LivingEntity entity;
    private final @Nullable Player killer;
    private final boolean boss;
    private final @Nullable String mobId;

    public DungeonMobKillEvent(Dungeon dungeon, LivingEntity entity, @Nullable Player killer,
                               boolean boss, @Nullable String mobId) {
        super(dungeon);
        this.entity = entity;
        this.killer = killer;
        this.boss = boss;
        this.mobId = mobId;
    }

    /** The mob itself — see the class note about reading it now rather than later. */
    public LivingEntity entity() {
        return entity;
    }

    /**
     * The player credited with the kill, or {@code null}.
     *
     * <p>A mob can die to fall damage, to fire, or to another mob. Award nothing for those: the
     * kill is real but it belongs to nobody, and crediting the nearest player invents a fact.
     */
    public @Nullable Player killer() {
        return killer;
    }

    /** Whether this was the boss room's boss. */
    public boolean boss() {
        return boss;
    }

    /**
     * The {@code mobs.yml} id it was drawn from, or {@code null}.
     *
     * <p>Null when the operator edited the file and reloaded while the mob was standing in a room.
     * The kill still happened, which is why it is nullable rather than a reason to drop the event.
     */
    public @Nullable String mobId() {
        return mobId;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
