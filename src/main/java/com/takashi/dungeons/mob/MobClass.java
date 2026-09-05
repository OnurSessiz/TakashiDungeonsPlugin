package com.takashi.dungeons.mob;

import java.util.Locale;

/**
 * What role a mob definition plays when the spawner picks candidates.
 *
 * <h2>This is a pool tag, not a multiplier</h2>
 * A class does <b>not</b> scale anything. It answers one question — "which pool may this mob be
 * drawn from" — and the stats come from the definition's own ranges ({@link MobDefinition}).
 *
 * <p>The alternative, giving each class a stat multiplier, was rejected for the same reason
 * {@code generation.md} §5.4 keeps room weight on the template: an operator who writes
 * {@code health: [40, 50]} and sees 120 in game has no way to find out where the number came
 * from. One number, one place.
 *
 * <p>Phase 3B is what connects rooms to classes: room type and {@code LayoutNode.depth()} decide
 * which class to draw, the class decides the candidate pool, and the weighted draw inside that
 * pool picks the mob.
 */
public enum MobClass {

    WEAK("weak"),
    NORMAL("normal"),
    STRONG("strong"),
    SUPER_STRONG("super_strong"),
    /**
     * Drawn only for the boss room, and never by the ordinary room spawner — a boss that can
     * turn up in a corridor is not a boss.
     */
    BOSS("boss");

    private final String key;

    MobClass(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /**
     * Parses a YAML/command value. Tolerates {@code super strong} and {@code super-strong}
     * alongside {@code super_strong}: the value is typed by hand and the separator is the one
     * thing nobody remembers.
     *
     * @return {@code null} when there is no match
     */
    public static MobClass parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (MobClass mobClass : values()) {
            if (mobClass.key.equals(value)) {
                return mobClass;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return key;
    }
}
