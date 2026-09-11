package com.takashi.dungeons.storage;

import java.util.List;

/**
 * What the database contains, and every step that ever led to it.
 *
 * <h2>Migrations are append-only</h2>
 * A released version is a fact about somebody else's disk. Editing migration 1 after it has shipped
 * changes nothing on the servers that already ran it — their schema keeps whatever the old text
 * said — so the two halves of the world quietly stop agreeing. A change to the schema is therefore
 * always a <b>new</b> entry at the end of {@link #MIGRATIONS}, never an edit to an old one.
 *
 * <h2>Why every statement is written to be re-runnable</h2>
 * SQLite runs DDL inside a transaction; MySQL does not — it commits implicitly at every
 * {@code CREATE TABLE}. A migration that fails halfway on MySQL is therefore half applied, and the
 * next start has to be able to walk over the half that exists. {@code IF NOT EXISTS} on every
 * statement is what makes that walk harmless.
 *
 * <h2>No table prefix, deliberately</h2>
 * Two servers pointed at one MySQL database are the reason the MySQL option exists, and they are
 * meant to <b>share</b> these rows — that is what makes a player's HUD setting follow them across a
 * network. A prefix exists to keep installations apart, which is the opposite of the intent, so the
 * names are fixed and carry the plugin's own {@code td_} instead.
 */
public final class Schema {

    /** Which migrations have been applied. Its own version is implicit: it never changes. */
    public static final String VERSIONS = "td_schema_version";

    /** One row per player the server has seen. Settings live here. */
    public static final String PLAYERS = "td_players";

    /** One row per player, counters only — see {@code PlayerStats} for why it is separate. */
    public static final String STATS = "td_player_stats";

    /**
     * The schema, in order.
     *
     * <p>Version 1 creates both tables at once because both ship in the same release; splitting
     * them into two migrations would be a fiction about a history that never happened.
     *
     * <p>The settings columns are <b>nullable on purpose</b>. {@code NULL} means "this player has
     * never made a choice", which is a different fact from "this player turned it off", and only
     * the first one is allowed to follow {@code config.yml} when the operator changes the default.
     * Storing the default at first join would freeze every existing player onto whatever the switch
     * happened to say that day.
     */
    public static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "players and stats", List.of(
                    "CREATE TABLE IF NOT EXISTS " + PLAYERS + " ("
                            + "uuid ${uuid} NOT NULL PRIMARY KEY, "
                            + "name ${name}, "
                            + "hud ${bool}, "
                            + "party_hud ${bool}, "
                            + "first_seen ${long} NOT NULL, "
                            + "last_seen ${long} NOT NULL"
                            + ")${table-options}",
                    "CREATE TABLE IF NOT EXISTS " + STATS + " ("
                            + "uuid ${uuid} NOT NULL PRIMARY KEY, "
                            + "runs_entered ${long} NOT NULL DEFAULT 0, "
                            + "runs_cleared ${long} NOT NULL DEFAULT 0, "
                            + "boss_kills ${long} NOT NULL DEFAULT 0, "
                            + "mob_kills ${long} NOT NULL DEFAULT 0, "
                            + "deaths ${long} NOT NULL DEFAULT 0, "
                            + "seconds_inside ${long} NOT NULL DEFAULT 0"
                            + ")${table-options}")));

    /** The version a fully migrated database reports. */
    public static int latestVersion() {
        return MIGRATIONS.get(MIGRATIONS.size() - 1).version();
    }

    private Schema() {
    }
}
