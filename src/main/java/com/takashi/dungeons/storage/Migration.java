package com.takashi.dungeons.storage;

import java.util.List;

/**
 * One step of the schema's history.
 *
 * @param version    the number written into {@code td_schema_version} once every statement below
 *                   has run. Strictly increasing across {@link Schema#MIGRATIONS}
 * @param name       what the step did, for the log line and the version table. Read by a human
 *                   trying to work out which release their database is stuck on
 * @param statements the SQL, with the type placeholders {@link SqlDialect#resolve} fills in. Each
 *                   one has to be harmless to re-run — see the note in {@link Schema}
 */
public record Migration(int version, String name, List<String> statements) {
}
