package com.takashi.dungeons.generation;

import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * A dungeon size and the room-count range it maps to — {@code generation.md} §6.1.
 *
 * <p>The count is drawn at <b>random</b> from the range rather than fixed, so two players
 * picking the same size don't get dungeons of identical length. The range is kept narrow so
 * that the word "medium" keeps its meaning.
 */
public enum DungeonSize {

    SMALL("small", 3, 6),
    MEDIUM("medium", 7, 12),
    LARGE("large", 13, 20);

    private final String key;
    private final int minRooms;
    private final int maxRooms;

    DungeonSize(String key, int minRooms, int maxRooms) {
        this.key = key;
        this.minRooms = minRooms;
        this.maxRooms = maxRooms;
    }

    public String key() {
        return key;
    }

    public int minRooms() {
        return minRooms;
    }

    public int maxRooms() {
        return maxRooms;
    }

    /** Draws a target room count for this size. */
    public int pickRoomCount(RandomGenerator random) {
        return minRooms + random.nextInt(maxRooms - minRooms + 1);
    }

    /**
     * The critical path's target length — {@code generation.md} §6.2:
     * {@code round(target × 0.65)}, minimum 2.
     *
     * <p>The length <b>includes the entrance and the boss</b>. The minimum of 2 guarantees they
     * are always separate rooms; at 1 the boss would be the entrance itself.
     *
     * <p>The 65% ratio: roughly two thirds of the rooms sit on the mandatory path and one third
     * on side branches. Give side branches too much and the dungeon feels wide but shallow;
     * too little and it becomes a single corridor.
     */
    public static int criticalPathLength(int targetRooms) {
        return Math.max(2, Math.round(targetRooms * 0.65f));
    }

    /** Parses a YAML/command value; returns {@code null} if there is no match. */
    public static DungeonSize parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (DungeonSize size : values()) {
            if (size.key.equals(value)) {
                return size;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return key + " (" + minRooms + "-" + maxRooms + " rooms)";
    }
}
