package com.takashi.dungeons.editor;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * Numbers as the editors handle them: {@link BigDecimal} while being adjusted, the plainest
 * {@code Long}/{@code Double} when written.
 *
 * <p>Why not doubles all the way: a speed stepper that adds 0.01 to 0.23 produces
 * 0.24000000000000002, and an editor that writes that into the operator's file has made it worse
 * than the hand edit it replaced. Decimal steps are exact.
 */
public final class Num {

    private Num() {
    }

    /** A YAML number as a decimal; {@code null} for anything that is not a number. */
    public static @Nullable BigDecimal of(@Nullable Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return BigDecimal.valueOf(((Number) raw).longValue());
        }
        if (raw instanceof Number number) {
            // valueOf goes through Double.toString, so 0.23 stays 0.23 rather than its binary tail.
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    /** What gets written: a {@code Long} for a whole number, a {@code Double} otherwise. */
    public static Object clean(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            return stripped.longValueExact();
        }
        return stripped.doubleValue();
    }

    public static String show(@Nullable BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    /** Parses typed input; throws with a sentence the operator can act on. */
    public static BigDecimal parse(String text) {
        try {
            return new BigDecimal(text.strip().replace(',', '.'));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("'" + text + "' is not a number.");
        }
    }

    /**
     * Comparable form of a YAML value: numbers by value (so 20 equals 20.0), lists element by
     * element, everything else as it is.
     */
    public static @Nullable Object canonical(@Nullable Object raw) {
        BigDecimal number = of(raw);
        if (number != null) {
            return number.stripTrailingZeros();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(item -> {
                Object canon = canonical(item);
                return canon == null ? "null" : canon;
            }).toList();
        }
        return raw;
    }

    /** Readable form for a change summary: {@code [16, 22]}, {@code 0.25}, {@code -}. */
    public static String describe(@Nullable Object raw) {
        if (raw == null) {
            return "-";
        }
        BigDecimal number = of(raw);
        if (number != null) {
            return show(number);
        }
        if (raw instanceof List<?> list) {
            return "[" + String.join(", ", list.stream().map(Num::describe).toList()) + "]";
        }
        return raw.toString();
    }
}
