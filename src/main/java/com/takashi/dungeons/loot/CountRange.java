package com.takashi.dungeons.loot;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * An inclusive {@code [min, max]} whole-number range — how many items a stack holds, how many
 * draws a chest gets.
 *
 * <h2>Why not StatRange</h2>
 * {@link com.takashi.dungeons.mob.StatRange} is the same shape but carries doubles, because an
 * attribute is a continuous value. A count is not: rounding a rolled {@code 2.6} into a stack size
 * is a decision, and making it once here is better than making it at every call site. Two small
 * honest types beat one that is right about half its uses.
 *
 * <p>A single number in YAML is accepted and becomes a zero-width range, so an operator who wants
 * exactly one of something never has to write {@code [1, 1]}.
 *
 * @param min lower bound, inclusive; never negative
 * @param max upper bound, inclusive; never below {@code min}
 */
public record CountRange(int min, int max) {

    public CountRange {
        if (min < 0) {
            throw new IllegalArgumentException("a count range cannot be negative: " + min);
        }
        if (max < min) {
            throw new IllegalArgumentException("max < min in a count range: [" + min + ", " + max + "]");
        }
    }

    /** A range with no variance. */
    public static CountRange fixed(int value) {
        return new CountRange(value, value);
    }

    public boolean isFixed() {
        return min == max;
    }

    /** Draws a value from the range, both ends included. */
    public int roll(RandomGenerator random) {
        return isFixed() ? min : min + random.nextInt(max - min + 1);
    }

    /**
     * Parses either {@code 3} or {@code [2, 4]}.
     *
     * <p>Throws rather than defaulting: a mistyped amount produces chests that are quietly wrong
     * for weeks. The message names the field so the fix is one line away.
     *
     * @param raw      the YAML value, or {@code null} when the key is absent
     * @param where    {@code "items.rusty_sword -> amount"}, used in the error message
     * @param fallback returned when {@code raw} is {@code null}
     */
    public static CountRange parse(Object raw, String where, CountRange fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return fixed(whole(number, where, "value"));
        }
        if (raw instanceof List<?> list) {
            if (list.size() != 2) {
                throw new IllegalArgumentException(where + ": a range must hold exactly 2 whole "
                        + "numbers ([min, max]) - found: " + list.size());
            }
            int min = whole(list.get(0), where, "min");
            int max = whole(list.get(1), where, "max");
            if (max < min) {
                throw new IllegalArgumentException(where + ": max < min ([" + min + ", " + max + "])");
            }
            return new CountRange(min, max);
        }
        throw new IllegalArgumentException(where + ": expected a whole number or a [min, max] list "
                + "- found: " + raw);
    }

    private static int whole(Object value, String where, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(where + " " + field
                    + ": expected a whole number - found: " + value);
        }
        double exact = number.doubleValue();
        if (exact != Math.rint(exact) || !Double.isFinite(exact)) {
            throw new IllegalArgumentException(where + " " + field
                    + ": expected a whole number - found: " + value);
        }
        if (exact < 0) {
            throw new IllegalArgumentException(where + " " + field
                    + ": cannot be negative - found: " + value);
        }
        return (int) exact;
    }

    @Override
    public String toString() {
        return isFixed() ? String.valueOf(min) : min + "-" + max;
    }
}
