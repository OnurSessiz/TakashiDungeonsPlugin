package com.takashi.dungeons.storage;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * The {@code storage:} block of {@code config.yml}, read once at enable.
 *
 * <p>A record rather than repeated {@code getConfig().getString(...)} calls, for the reason every
 * other registry in this plugin gives: a bad value has to be <b>reported by name and refused</b>,
 * not silently replaced. An unknown backend name does not quietly become SQLite here — it is
 * reported as unknown and persistence stays off, because an operator who typed {@code mysql} and
 * got a local file would find out months later, from the wrong data.
 *
 * <p>The connection settings are <b>not</b> re-read by {@code /tdungeons reload}. A live connection
 * cannot be repointed at another host mid-session without deciding what happens to the writes
 * already queued against the old one; changing where the data lives is a restart.
 */
public record StorageSettings(SqlDialect dialect, String sqliteFile, String host, int port,
                              String database, String username, String password,
                              String properties, int flushSeconds) {

    /** What the shipped config says when the operator has not touched it. */
    public static final String DEFAULT_FILE = "data.db";

    /**
     * Reads the block. {@code dialect} is {@code null} when the configured type is not a name this
     * plugin knows — the caller logs that and runs without persistence.
     */
    public static StorageSettings from(FileConfiguration config) {
        return new StorageSettings(
                SqlDialect.from(config.getString("storage.type", "sqlite")),
                config.getString("storage.sqlite.file", DEFAULT_FILE),
                config.getString("storage.mysql.host", "localhost"),
                config.getInt("storage.mysql.port", 3306),
                config.getString("storage.mysql.database", "takashi_dungeons"),
                config.getString("storage.mysql.username", "root"),
                config.getString("storage.mysql.password", ""),
                config.getString("storage.mysql.properties", ""),
                // Below a second the flush would cost more in scheduling than the handful of rows
                // it writes; the default is a minute and the cap is there to stop a typo.
                Math.max(5, config.getInt("storage.flush-seconds", 60)));
    }

    /** What {@code /tdungeons db} prints — never the password, not even masked to a length. */
    public String describe() {
        if (dialect == null) {
            return "unknown backend";
        }
        return dialect == SqlDialect.MYSQL
                ? "MySQL " + host + ":" + port + "/" + database + " as " + username
                : "SQLite " + sqliteFile;
    }
}
