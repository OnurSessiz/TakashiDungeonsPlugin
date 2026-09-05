package com.takashi.dungeons.mob;

import java.util.Locale;

/**
 * The dungeon's difficulty setting — the one thing in the mob system that <b>is</b> a multiplier.
 *
 * <p>Where {@link MobClass} says which mob turns up, difficulty says how hard the same mob hits.
 * Keeping the two apart means an operator can raise difficulty without the room composition
 * changing under them, and can rebalance a single mob without touching difficulty.
 *
 * <p>The numbers themselves are not here — they live in {@code mobs.yml} and are carried by
 * {@link DifficultyScaling}. An enum constant with a hard-coded 2.5 in it is a number nobody can
 * tune without a recompile, and "everything is configurable" is a project rule, not a preference.
 */
public enum Difficulty {

    EASY("easy"),
    MEDIUM("medium"),
    HARD("hard");

    private final String key;

    Difficulty(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** Parses a YAML/command value; {@code null} when there is no match. */
    public static Difficulty parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (Difficulty difficulty : values()) {
            if (difficulty.key.equals(value)) {
                return difficulty;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return key;
    }
}
