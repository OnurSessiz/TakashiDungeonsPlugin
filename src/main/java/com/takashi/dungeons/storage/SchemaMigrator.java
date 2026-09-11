package com.takashi.dungeons.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Brings a database up to {@link Schema#latestVersion()}.
 *
 * <h2>Why a version table and not "create if not exists" alone</h2>
 * {@code CREATE TABLE IF NOT EXISTS} answers the question "does this table exist", which is not the
 * question that matters on somebody else's server. The question is "which shape is it in" — a table
 * created by version 1 exists just as hard as one created by version 4, and adding a column to it
 * is not something {@code IF NOT EXISTS} can be asked to do. The version table is what lets a later
 * release know what it is looking at.
 *
 * <h2>A database from the future is refused, not downgraded</h2>
 * If the recorded version is higher than this jar knows about, the operator has rolled the plugin
 * back while the data stayed forward. Carrying on would mean writing to a schema this code has
 * never seen — the one case where refusing to start the storage layer is kinder than continuing.
 */
public final class SchemaMigrator {

    private final Logger logger;

    public SchemaMigrator(Logger logger) {
        this.logger = logger;
    }

    /**
     * Applies everything newer than what the database says it has.
     *
     * @return the version the database is on afterwards
     * @throws SQLException if a statement fails, or if the database is newer than this jar
     */
    public int migrate(Connection connection, SqlDialect dialect) throws SQLException {
        createVersionTable(connection, dialect);
        int current = currentVersion(connection);
        int latest = Schema.latestVersion();

        if (current > latest) {
            throw new SQLException("The database is at schema version " + current
                    + " but this build only knows version " + latest
                    + ". It was written by a newer TakashiDungeons; upgrade the plugin again "
                    + "rather than letting an older build write to it.");
        }
        if (current == latest) {
            return current;
        }

        for (Migration migration : Schema.MIGRATIONS) {
            if (migration.version() <= current) {
                continue;
            }
            apply(connection, dialect, migration);
            logger.info("Schema migration " + migration.version() + " applied ("
                    + migration.name() + ").");
            current = migration.version();
        }
        return current;
    }

    /**
     * Runs one migration and records it.
     *
     * <p>Wrapped in a transaction where the backend allows one. SQLite honours it for DDL and gets
     * all-or-nothing; MySQL commits implicitly at each {@code CREATE TABLE} and does not, which is
     * exactly why {@link Schema} insists every statement be safe to re-run. The version row is
     * written <b>last</b> either way: a half-applied migration that had already claimed its number
     * would never be retried.
     */
    private void apply(Connection connection, SqlDialect dialect, Migration migration)
            throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                for (String sql : migration.statements()) {
                    statement.execute(dialect.resolve(sql));
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + Schema.VERSIONS + " (version, name, applied_at) VALUES (?, ?, ?)")) {
                insert.setInt(1, migration.version());
                insert.setString(2, migration.name());
                insert.setLong(3, System.currentTimeMillis());
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException error) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Nothing to add: the original failure below is the one worth reporting.
            }
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void createVersionTable(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.resolve("CREATE TABLE IF NOT EXISTS " + Schema.VERSIONS + " ("
                    + "version INTEGER NOT NULL PRIMARY KEY, "
                    + "name ${label}, "
                    + "applied_at ${long} NOT NULL"
                    + ")${table-options}"));
        }
    }

    /** {@code 0} for a database that has never been migrated — including an empty one. */
    private int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT MAX(version) FROM " + Schema.VERSIONS)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }
}
