package com.takashi.dungeons.editor;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * A {@code [min, max]} field — a mob stat, a loot amount, a table's draw count.
 *
 * <h2>min can never pass max</h2>
 * Raising the minimum above the maximum drags the maximum along; lowering the maximum below the
 * minimum drags the minimum down. The alternative — refusing the click — makes "move the whole
 * range up by ten" a four-step dance, and letting it through produces a file the parser rejects.
 *
 * <h2>The file's spelling is kept</h2>
 * A field written as one number stays one number while min and max are equal, and one written as a
 * list stays a list even when they meet. {@code health: 20} does not turn into {@code [20, 20]}
 * because somebody nudged it up and back.
 *
 * @param floor    lowest allowed value (health cannot be 0, a speed can)
 * @param fallback what an absent field starts from when it is switched on
 * @param whole    integers only — loot amounts, draw counts
 */
public record RangeField(String field, BigDecimal step, BigDecimal floor, BigDecimal fallback,
                  boolean whole) {

    public static RangeField decimal(String field, String step, String floor, String fallback) {
        return new RangeField(field, new BigDecimal(step), new BigDecimal(floor),
                new BigDecimal(fallback), false);
    }

    public static RangeField whole(String field, int step, int floor, int fallback) {
        return new RangeField(field, BigDecimal.valueOf(step), BigDecimal.valueOf(floor),
                BigDecimal.valueOf(fallback), true);
    }

    /** {@code [min, max]}, or {@code null} when the field is absent. */
    public @Nullable BigDecimal[] read(Draft draft) {
        Object raw = draft.get(field);
        BigDecimal single = Num.of(raw);
        if (single != null) {
            return new BigDecimal[]{single, single};
        }
        if (raw instanceof List<?> list && list.size() == 2) {
            BigDecimal min = Num.of(list.get(0));
            BigDecimal max = Num.of(list.get(1));
            if (min != null && max != null) {
                return new BigDecimal[]{min, max};
            }
        }
        return null;
    }

    public boolean present(Draft draft) {
        return read(draft) != null;
    }

    /** Turns an absent field on at its fallback. */
    public void create(Draft draft) {
        write(draft, fallback, fallback);
    }

    public void clear(Draft draft) {
        draft.set(field, null);
    }

    public void adjustMin(Draft draft, BigDecimal delta) {
        BigDecimal[] range = orFallback(draft);
        setMin(draft, range[0].add(delta));
    }

    public void adjustMax(Draft draft, BigDecimal delta) {
        BigDecimal[] range = orFallback(draft);
        setMax(draft, range[1].add(delta));
    }

    public void setMin(Draft draft, BigDecimal value) {
        BigDecimal[] range = orFallback(draft);
        BigDecimal min = bound(value);
        write(draft, min, range[1].max(min));
    }

    public void setMax(Draft draft, BigDecimal value) {
        BigDecimal[] range = orFallback(draft);
        BigDecimal max = bound(value);
        write(draft, range[0].min(max), max);
    }

    /** Validates typed input against the field's rules; throws with the reason. */
    public BigDecimal check(BigDecimal value) {
        if (whole && value.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(field + " takes whole numbers only.");
        }
        if (value.compareTo(floor) < 0) {
            throw new IllegalArgumentException(field + " cannot go below " + Num.show(floor) + ".");
        }
        return value;
    }

    public String describe(Draft draft) {
        BigDecimal[] range = read(draft);
        if (range == null) {
            return "-";
        }
        return range[0].compareTo(range[1]) == 0 ? Num.show(range[0])
                : Num.show(range[0]) + " - " + Num.show(range[1]);
    }

    private BigDecimal bound(BigDecimal value) {
        BigDecimal bounded = value.max(floor);
        return whole ? bounded.setScale(0, java.math.RoundingMode.HALF_UP) : bounded;
    }

    private BigDecimal[] orFallback(Draft draft) {
        BigDecimal[] range = read(draft);
        return range != null ? range : new BigDecimal[]{fallback, fallback};
    }

    private void write(Draft draft, BigDecimal min, BigDecimal max) {
        boolean wasList = draft.original(field) instanceof List<?>;
        if (min.compareTo(max) == 0 && !wasList) {
            draft.set(field, Num.clean(min));
        } else {
            draft.set(field, List.of(Num.clean(min), Num.clean(max)));
        }
    }
}
