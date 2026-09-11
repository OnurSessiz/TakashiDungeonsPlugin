package com.takashi.dungeons.api;

/**
 * What a player has done in dungeons.
 *
 * <h2>Why there is no XP, rank or coin here</h2>
 * Those belong to the addons — TakashiRanks and TakashiMarket — and a core that kept its own copy
 * would be the second source of truth that makes the two disagree. Every number below is something
 * the core itself observes and nothing else can: it happens inside an instance.
 *
 * <p>This is what an addon builds its own numbers <i>from</i>. The usual shape is to listen to
 * {@link com.takashi.dungeons.api.event.DungeonMobKillEvent} and
 * {@link com.takashi.dungeons.api.event.DungeonCompleteEvent} and keep your own totals; these
 * counters are here for the times you want the whole history rather than the moment.
 */
public interface DungeonStats {

    /**
     * Dungeons stepped into. Counted <b>once per dungeon</b>, not once per entry: leaving and
     * coming back, or dying and returning, is one run.
     */
    long runsEntered();

    /** Of those, how many ended with the boss dead <b>while this player was inside</b>. */
    long runsCleared();

    /**
     * Bosses this player struck the killing blow on.
     *
     * <p>A party clearing a dungeon gives everyone a clear and one person a boss kill. The two
     * answer different questions and neither replaces the other.
     */
    long bossKills();

    /** Ordinary dungeon mobs killed. A mob that died to fall damage belongs to nobody. */
    long mobKills();

    /** Deaths inside a dungeon. A death out in the world is not this plugin's business. */
    long deaths();

    /** Seconds spent registered as inside an instance. */
    long secondsInside();
}
