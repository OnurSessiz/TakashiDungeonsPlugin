package com.takashi.dungeons.generation;

import java.util.Locale;

/**
 * The room's role in the graph — the {@code type} field from {@code generation.md} §8.
 *
 * <p>{@link #ENTRANCE} and {@link #BOSS} are <b>assigned</b> during graph generation, never
 * drawn at random: the critical path starts at the entrance room and its last node is the boss
 * room ({@code generation.md} §6.2). This is what puts a floor under playtime — if rooms were
 * scattered randomly and the furthest one declared the boss, some dungeons would end after two
 * rooms.
 */
public enum RoomType {

    ENTRANCE,
    NORMAL,
    BOSS;

    /** Parses the {@code type} value from YAML; tolerant of case and surrounding whitespace. */
    public static RoomType parse(String raw, String templateName) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        for (RoomType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException(templateName + ": invalid type '" + raw
                + "' — valid values: entrance, normal, boss");
    }

    public String yamlValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
