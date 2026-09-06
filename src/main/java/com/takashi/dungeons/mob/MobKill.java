package com.takashi.dungeons.mob;

import com.takashi.dungeons.instance.DungeonInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * A dungeon mob died — the signal phase 4 (loot) and phase 8 (events) hang off.
 *
 * <h2>Why a record and a listener list rather than a Bukkit event, today</h2>
 * A custom event is phase 8's job and phase 8 owes the API a promise of no breaking changes
 * ({@code anahedef.md} §5). Publishing {@code MobKillEvent} now would fix its shape before there
 * is a single consumer to shape it against. The seam is what matters and it is the same one
 * {@code InstanceManager.onClosed} already uses; when phase 8 arrives, the event is fired from one
 * handler registered here and nothing else moves.
 *
 * @param instance   the dungeon the mob belonged to — always live at the moment of the signal
 * @param definition the {@code mobs.yml} entry it was drawn from, or {@code null} if that entry no
 *                   longer exists (an operator edited the file and reloaded while the mob stood in
 *                   a room). The kill is still real, which is why this is nullable rather than a
 *                   reason to drop the signal
 * @param entity     the mob itself, still holding its equipment and position — read it here, it is
 *                   removed from the world as soon as the death event finishes
 * @param boss       whether this was the boss room's boss. The one thing every consumer branches
 *                   on, so it is answered here instead of making each of them read the tag back
 * @param killer     the player credited with the kill, or {@code null} — a mob can die to fall
 *                   damage, to fire, or to another mob, and phase 11 must not award XP for those
 */
public record MobKill(DungeonInstance instance, @Nullable MobDefinition definition,
                      LivingEntity entity, boolean boss, @Nullable Player killer) {
}
