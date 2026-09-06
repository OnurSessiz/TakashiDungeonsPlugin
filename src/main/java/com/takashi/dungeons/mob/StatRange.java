package com.takashi.dungeons.mob;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * An inclusive {@code [min, max]} attribute range, rolled once per spawn.
 *
 * <h2>Why a range rather than a number</h2>
 * Two zombies in the same room with the same health bar read as copies of one object. A range
 * costs nothing and makes a group of mobs look like a group of individuals. The range is also
 * the unit phase 9's GUI will edit — two sliders, not a text field.
 *
 * <p>A single number in YAML is accepted and becomes a zero-width range, so an operator who does
 * not want variance never has to write {@code [20, 20]}.
 *
 * @param min lower bound, inclusive
 * @param max upper bound, inclusive; never less than {@code min}
 */
public record StatRange(double min, double max) {

    public StatRange {
        if (min < 0) {
            throw new IllegalArgumentException("a stat range cannot be negative: " + min);
        }
        if (max < min) {
            throw new IllegalArgumentException("max < min in a stat range: [" + min + ", " + max + "]");
        }
    }

    /** A range with no variance. */
    public static StatRange fixed(double value) {
        return new StatRange(value, value);
    }

    public boolean isFixed() {
        return min == max;
    }

    /** Draws a value from the range. */
    public double roll(RandomGenerator random) {
        return isFixed() ? min : min + random.nextDouble() * (max - min);
    }

    /**
     * Parses either {@code 20} or {@code [18, 24]}.
     *
     * <p>Throws rather than falling back to a default: a mistyped stat produces a mob that is
     * wrong in a way nobody notices until a player complains it hits too hard. The message names
     * the file and the field so the fix is one line away.
     *
     * @param raw   the YAML value, or {@code null} when the key is absent
     * @param where {@code "<mob id> -> health"}, used in the error message
     * @return {@code null} when {@code raw} is {@code null} — an absent stat is not an error, it
     *         means "leave whatever the entity naturally has"
     */
    public static StatRange parse(Object raw, String where) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return fixed(requireFinite(number.doubleValue(), where));
        }
        if (raw instanceof List<?> list) {
            if (list.size() != 2) {
                throw new IllegalArgumentException(where + ": a range must hold exactly 2 numbers "
                        + "([min, max]) — bulunan: " + list.size());
            }
            double min = number(list.get(0), where, "min");
            double max = number(list.get(1), where, "max");
            if (max < min) {
                throw new IllegalArgumentException(where + ": max < min ([" + min + ", " + max + "])");
            }
            return new StatRange(min, max);
        }
        throw new IllegalArgumentException(where + ": expected a number or a [min, max] list - "
                + "bulunan: " + raw);
    }

    private static double number(Object value, String where, String field) {
        if (value instanceof Number number) {
            return requireFinite(number.doubleValue(), where + " (" + field + ")");
        }
        throw new IllegalArgumentException(where + " " + field + ": expected a number - found: " + value);
    }

    private static double requireFinite(double value, String where) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(where + ": must be a finite, non-negative number "
                    + "— bulunan: " + value);
        }
        return value;
    }

    @Override
    public String toString() {
        return isFixed() ? trim(min) : trim(min) + "-" + trim(max);
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
