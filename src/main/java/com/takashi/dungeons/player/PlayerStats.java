package com.takashi.dungeons.player;

/**
 * What a player has done in dungeons, as six numbers.
 *
 * <h2>Why these six and not more</h2>
 * Each one is a fact the <b>core</b> owns — it happens inside an instance, and nothing but this
 * plugin can observe it. XP, rank and coin are deliberately absent: they belong to TakashiRanks and
 * TakashiMarket (phases 11 and 12), and a core that kept its own copy would be the second source of
 * truth that makes the two disagree. What the addons get from here is the raw event, through the
 * phase 8 API; what they do with it is theirs.
 *
 * <h2>The same record is used for a total and for a delta</h2>
 * Deliberate, and it is what makes the write safe: a delta is sent to the database as
 * {@code column = column + value}, so the total is never read, modified and written back. Two
 * servers on one MySQL therefore cannot overwrite each other's kills — see
 * {@link com.takashi.dungeons.storage.SqlDialect#increment}.
 *
 * @param runsEntered   how many dungeons this player has stepped into. Counted once per instance,
 *                      not once per entry: leaving and coming back to the same dungeon, or being
 *                      teleported out by a death and returning, is one run
 * @param runsCleared   how many of those ended with the boss dead <b>while the player was inside</b>
 * @param bossKills     bosses this player struck the killing blow on. A dungeon cleared by a party
 *                      gives everyone a clear and one person a boss kill; they answer two different
 *                      questions and neither replaces the other
 * @param mobKills      ordinary dungeon mobs killed by this player. Kills with no killer — fall
 *                      damage, fire, another mob — belong to nobody and are not counted
 * @param deaths        deaths inside a dungeon. A death out in the world is not this plugin's
 *                      business to record
 * @param secondsInside time spent registered as inside an instance. Settled when the player leaves
 *                      and again at every flush, so a crash costs at most one flush interval
 */
public record PlayerStats(long runsEntered, long runsCleared, long bossKills, long mobKills,
                          long deaths, long secondsInside) {

    public static final PlayerStats ZERO = new PlayerStats(0, 0, 0, 0, 0, 0);

    public PlayerStats plus(PlayerStats other) {
        return new PlayerStats(
                runsEntered + other.runsEntered,
                runsCleared + other.runsCleared,
                bossKills + other.bossKills,
                mobKills + other.mobKills,
                deaths + other.deaths,
                secondsInside + other.secondsInside);
    }

    /** Nothing happened — the flush skips the write entirely rather than adding six zeroes. */
    public boolean isZero() {
        return runsEntered == 0 && runsCleared == 0 && bossKills == 0
                && mobKills == 0 && deaths == 0 && secondsInside == 0;
    }

    // The named constructors below exist so that a caller reads as what it means — recordDeath()
    // sends PlayerStats.death(), not a row of five zeroes and a one that has to be counted.

    public static PlayerStats entry() {
        return new PlayerStats(1, 0, 0, 0, 0, 0);
    }

    public static PlayerStats clear() {
        return new PlayerStats(0, 1, 0, 0, 0, 0);
    }

    public static PlayerStats bossKill() {
        return new PlayerStats(0, 0, 1, 0, 0, 0);
    }

    public static PlayerStats mobKill() {
        return new PlayerStats(0, 0, 0, 1, 0, 0);
    }

    public static PlayerStats death() {
        return new PlayerStats(0, 0, 0, 0, 1, 0);
    }

    public static PlayerStats seconds(long seconds) {
        return new PlayerStats(0, 0, 0, 0, 0, seconds);
    }
}
