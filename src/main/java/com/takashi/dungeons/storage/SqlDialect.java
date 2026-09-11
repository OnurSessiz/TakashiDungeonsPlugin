package com.takashi.dungeons.storage;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.StringJoiner;

/**
 * The two SQL flavours, and every difference between them in one place.
 *
 * <h2>Why the differences are collected here rather than branched on at each call site</h2>
 * There are exactly three of them — column types, the upsert clause and the table options — and
 * each one is a single line of syntax. Spread across the repository they would be three {@code if}
 * statements per statement written, and the day a third backend appears (or MariaDB turns out to
 * disagree about one of them) the search for "where does the SQL differ" would have no answer.
 * Here the answer is the file.
 *
 * <h2>The upsert clause</h2>
 * SQLite speaks {@code ON CONFLICT(key) DO UPDATE SET col = excluded.col}; MySQL speaks
 * {@code ON DUPLICATE KEY UPDATE col = VALUES(col)}. MySQL 8.0.20 deprecated {@code VALUES()} in
 * favour of a row alias, and the alias form is used here <b>deliberately not at all</b>: MariaDB
 * does not support it, and an operator pointing this plugin at MariaDB — which the MySQL driver
 * connects to perfectly well — would get a syntax error instead of a deprecation notice in a log
 * nobody reads.
 *
 * <h2>{@code increment} is not a convenience</h2>
 * Statistics are written as {@code col = col + excluded.col}, never read-modify-write. Two servers
 * sharing one MySQL database is the entire reason the MySQL option exists, and a read-modify-write
 * there silently loses whichever kill landed second. A relative update cannot lose one.
 */
public enum SqlDialect {

    SQLITE("sqlite"),
    MYSQL("mysql");

    private final String key;

    SqlDialect(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** The dialect named in the config, or {@code null} — the caller reports the bad value. */
    public static @Nullable SqlDialect from(String raw) {
        if (raw == null) {
            return null;
        }
        String wanted = raw.trim().toLowerCase(Locale.ROOT);
        for (SqlDialect dialect : values()) {
            if (dialect.key.equals(wanted)) {
                return dialect;
            }
        }
        return null;
    }

    /**
     * Fills the type placeholders in a DDL statement.
     *
     * <p>Placeholders rather than two copies of every {@code CREATE TABLE}: a migration is a fact
     * about the schema, and writing it twice is how the two copies drift apart one column at a
     * time. What differs between the backends is the vocabulary, not the statement.
     */
    public String resolve(String sql) {
        return sql
                .replace("${uuid}", this == MYSQL ? "CHAR(36)" : "TEXT")
                .replace("${name}", this == MYSQL ? "VARCHAR(16)" : "TEXT")
                .replace("${label}", this == MYSQL ? "VARCHAR(64)" : "TEXT")
                .replace("${bool}", this == MYSQL ? "TINYINT(1)" : "INTEGER")
                .replace("${long}", "BIGINT")
                .replace("${table-options}", this == MYSQL
                        ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "");
    }

    /**
     * The tail of an "insert, or overwrite what is already there" statement.
     *
     * @param key     the column the conflict is detected on — the primary key
     * @param columns the columns whose new value replaces the old one
     */
    public String upsert(String key, String... columns) {
        StringJoiner assignments = new StringJoiner(", ");
        for (String column : columns) {
            assignments.add(this == MYSQL
                    ? column + " = VALUES(" + column + ")"
                    : column + " = excluded." + column);
        }
        return clause(key) + assignments;
    }

    /**
     * The tail of an "insert, or add to what is already there" statement — see the class note on
     * why statistics are never read-modify-write.
     */
    public String increment(String key, String... columns) {
        StringJoiner assignments = new StringJoiner(", ");
        for (String column : columns) {
            assignments.add(this == MYSQL
                    ? column + " = " + column + " + VALUES(" + column + ")"
                    : column + " = " + column + " + excluded." + column);
        }
        return clause(key) + assignments;
    }

    private String clause(String key) {
        return this == MYSQL
                ? " ON DUPLICATE KEY UPDATE "
                : " ON CONFLICT(" + key + ") DO UPDATE SET ";
    }
}
