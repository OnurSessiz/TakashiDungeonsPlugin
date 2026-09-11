package com.takashi.dungeons.player;

import com.takashi.dungeons.storage.Schema;
import com.takashi.dungeons.storage.SqlDialect;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Every statement that touches a player's two rows.
 *
 * <p>Separate from {@link PlayerDataService} because the service is about <i>when</i> to read and
 * write — joins, quits, flush intervals — and this is about <i>what</i> the SQL is. Mixing them
 * makes the caching rules impossible to read past the string literals.
 *
 * <p>Every method here runs on the storage thread and takes the connection it was handed. None of
 * them keeps it: the connection may be replaced between calls when it has to be reopened.
 */
public final class PlayerDataRepository {

    private final SqlDialect dialect;

    public PlayerDataRepository(SqlDialect dialect) {
        this.dialect = dialect;
    }

    /**
     * What one player's rows say.
     *
     * @param exists {@code false} for a player the database has never seen. Not an error — it is
     *               every player's first join, and it is still a successful load
     */
    public record Loaded(boolean exists, @Nullable Boolean hud, @Nullable Boolean partyHud,
                         long firstSeen, PlayerStats stats) {
    }

    /**
     * Reads settings and counters.
     *
     * <p>Two queries rather than a {@code LEFT JOIN}: the tables are keyed identically and a join
     * would save one round trip on a call that happens once per login. What it would cost is a
     * result set where "no stats row" and "no player row" are told apart by which columns came back
     * null — the kind of read that is wrong the first time somebody adds a nullable column.
     */
    public Loaded load(Connection connection, UUID uuid) throws SQLException {
        boolean exists = false;
        Boolean hud = null;
        Boolean partyHud = null;
        long firstSeen = 0;

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT hud, party_hud, first_seen FROM " + Schema.PLAYERS + " WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    exists = true;
                    hud = readBoolean(row, "hud");
                    partyHud = readBoolean(row, "party_hud");
                    firstSeen = row.getLong("first_seen");
                }
            }
        }

        PlayerStats stats = PlayerStats.ZERO;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT runs_entered, runs_cleared, boss_kills, mob_kills, deaths, seconds_inside "
                        + "FROM " + Schema.STATS + " WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    stats = new PlayerStats(
                            row.getLong("runs_entered"),
                            row.getLong("runs_cleared"),
                            row.getLong("boss_kills"),
                            row.getLong("mob_kills"),
                            row.getLong("deaths"),
                            row.getLong("seconds_inside"));
                }
            }
        }
        return new Loaded(exists, hud, partyHud, firstSeen, stats);
    }

    /**
     * Writes the settings row.
     *
     * <p>{@code first_seen} is supplied by the insert and never by the update: it is the one column
     * whose whole meaning is that it does not change. {@code last_seen} is the opposite and is
     * rewritten every time.
     */
    public void saveSettings(Connection connection, UUID uuid, String name, @Nullable Boolean hud,
                             @Nullable Boolean partyHud, long firstSeen) throws SQLException {
        String sql = "INSERT INTO " + Schema.PLAYERS
                + " (uuid, name, hud, party_hud, first_seen, last_seen) VALUES (?, ?, ?, ?, ?, ?)"
                + dialect.upsert("uuid", "name", "hud", "party_hud", "last_seen");
        try (PreparedStatement upsert = connection.prepareStatement(sql)) {
            upsert.setString(1, uuid.toString());
            upsert.setString(2, name);
            writeBoolean(upsert, 3, hud);
            writeBoolean(upsert, 4, partyHud);
            upsert.setLong(5, firstSeen);
            upsert.setLong(6, System.currentTimeMillis());
            upsert.executeUpdate();
        }
    }

    /**
     * Adds a delta to the counters, creating the row if it is the player's first anything.
     *
     * <p>The insert supplies the delta as the initial value and the conflict clause adds it to
     * what is there — one statement that is correct both times, and correct in the face of another
     * server writing the same row a millisecond earlier.
     */
    public void addStats(Connection connection, UUID uuid, PlayerStats delta) throws SQLException {
        String sql = "INSERT INTO " + Schema.STATS
                + " (uuid, runs_entered, runs_cleared, boss_kills, mob_kills, deaths, seconds_inside)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                + dialect.increment("uuid", "runs_entered", "runs_cleared", "boss_kills",
                        "mob_kills", "deaths", "seconds_inside");
        try (PreparedStatement upsert = connection.prepareStatement(sql)) {
            upsert.setString(1, uuid.toString());
            upsert.setLong(2, delta.runsEntered());
            upsert.setLong(3, delta.runsCleared());
            upsert.setLong(4, delta.bossKills());
            upsert.setLong(5, delta.mobKills());
            upsert.setLong(6, delta.deaths());
            upsert.setLong(7, delta.secondsInside());
            upsert.executeUpdate();
        }
    }

    /**
     * Finds a player by the name last seen on this server.
     *
     * <p>For {@code /tdungeons stats <name>} against somebody offline. Names are not unique over
     * time — a player may rename and free the old one — so this answers "the account that carried
     * this name when it last logged in", which is the honest answer and is labelled as such by the
     * command.
     */
    public @Nullable UUID findByName(Connection connection, String name) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT uuid FROM " + Schema.PLAYERS + " WHERE name = ? ORDER BY last_seen DESC")) {
            select.setString(1, name);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                try {
                    return UUID.fromString(row.getString("uuid"));
                } catch (IllegalArgumentException malformed) {
                    // Somebody edited the table by hand. Reported as "not found" rather than
                    // thrown: the command asked a question, not for a stack trace.
                    return null;
                }
            }
        }
    }

    /** How many players the database knows about — the one number {@code /tdungeons db} shows. */
    public long countPlayers(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + Schema.PLAYERS);
             ResultSet row = select.executeQuery()) {
            return row.next() ? row.getLong(1) : 0;
        }
    }

    /** {@code null} when the column holds SQL NULL — "no choice made", not "off". */
    private static @Nullable Boolean readBoolean(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value != 0;
    }

    private static void writeBoolean(PreparedStatement statement, int index, @Nullable Boolean value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value ? 1 : 0);
        }
    }
}
