package com.takashi.dungeons.loot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * How a single loot draw is split between the rarity classes, and what difficulty does to it.
 *
 * <h2>Weights, not percentages</h2>
 * The shipped split is 1000-based — 600 / 250 / 100 / 40 / 10 — because adding a class or nudging
 * one share should not force every other number to be retyped. It is the same convention room
 * templates and {@code mobs.yml} already use, so an operator learns {@code weight} once.
 *
 * <h2>The difficulty rule, and why it is not the obvious one</h2>
 * Multiplying <b>every</b> class by the difficulty multiplier changes nothing: a draw normalises,
 * so 2.5× across the board is the same distribution it started with. This is the trap the project
 * wrote down before writing any loot code ({@code isleyis.md} § Loot).
 *
 * <p>The rule that works: <b>the multiplier is applied to rare and above, and the weight it adds is
 * taken out of common.</b> Uncommon is left alone and the total stays where it was, so the shares
 * still read against the same denominator. The worked example, at hard (2.5×):
 *
 * <pre>
 *   rare       100 -> 250      uncommon 250 (untouched)
 *   ultra       40 -> 100      common   1000 - 375 - 250 = 375
 *   legendary   10 ->  25
 *   rare total 150 -> 375      legendary: 1% -> 2.5%, common: 60% -> 37.5%
 * </pre>
 *
 * <h2>Common is the only source, and it can run out</h2>
 * When the increase is larger than the common weight on hand, the increments are scaled back
 * proportionally so common lands exactly at zero rather than going negative. A table written with
 * no common at all therefore <b>does not scale with difficulty</b> — there is nothing to move. That
 * is a real consequence of the rule rather than a bug, so {@link LootRegistry} says so at load time
 * instead of leaving the operator to discover that hard and easy give identical rewards.
 *
 * <p>Pure Java on purpose: this is the one piece of loot that is easy to get wrong and impossible
 * to see on a server — a distribution that is 8% off looks exactly like luck. It is verified over
 * 200,000 draws by {@code scripts/geo-probe/LootProbe.java}, with no server running.
 *
 * @param weights class → share; classes left out are zero. Never negative
 */
public record RarityWeights(Map<ItemClass, Integer> weights) {

    /** What the shipped weights add up to. Nothing enforces it — it is a convention, not a rule. */
    public static final int TOTAL = 1000;

    /**
     * The shipped split: 60 / 25 / 10 / 4 / 1 percent, and <b>no mythic</b>.
     *
     * <p>Mythic is left at zero here on purpose. It is the reward for killing a boss, so only the
     * shipped {@code boss_chest} declares a weight for it; every table that does not override this
     * split — an ordinary room chest included — therefore cannot produce one. Nothing in the code
     * enforces that, and an operator who writes a mythic weight into their own table gets exactly
     * what they wrote.
     */
    public static final RarityWeights DEFAULT = of(600, 250, 100, 40, 10, 0);

    public RarityWeights {
        EnumMap<ItemClass, Integer> copy = new EnumMap<>(ItemClass.class);
        for (Map.Entry<ItemClass, Integer> entry : weights.entrySet()) {
            int weight = entry.getValue() == null ? 0 : entry.getValue();
            if (weight < 0) {
                throw new IllegalArgumentException("a rarity weight cannot be negative: "
                        + entry.getKey() + " = " + weight);
            }
            if (weight > 0) {
                copy.put(entry.getKey(), weight);
            }
        }
        weights = Collections.unmodifiableMap(copy);
    }

    /**
     * Builds a set in declaration order — common, uncommon, rare, ultra rare, legendary, mythic.
     *
     * <p>Every class is named, including the ones you mean to leave at zero. An overload that let
     * the rarest be omitted would be the convenient thing to write and the wrong thing to have: a
     * class added later would then be silently absent from every existing call site.
     */
    public static RarityWeights of(int common, int uncommon, int rare, int ultraRare, int legendary,
            int mythic) {
        EnumMap<ItemClass, Integer> map = new EnumMap<>(ItemClass.class);
        map.put(ItemClass.COMMON, common);
        map.put(ItemClass.UNCOMMON, uncommon);
        map.put(ItemClass.RARE, rare);
        map.put(ItemClass.ULTRA_RARE, ultraRare);
        map.put(ItemClass.LEGENDARY, legendary);
        map.put(ItemClass.MYTHIC, mythic);
        return new RarityWeights(map);
    }

    public int weight(ItemClass itemClass) {
        return weights.getOrDefault(itemClass, 0);
    }

    /** The denominator every share is read against. */
    public int total() {
        int total = 0;
        for (int weight : weights.values()) {
            total += weight;
        }
        return total;
    }

    /** This class's share of a draw, 0..1. */
    public double share(ItemClass itemClass) {
        int total = total();
        return total == 0 ? 0 : (double) weight(itemClass) / total;
    }

    public boolean isEmpty() {
        return total() == 0;
    }

    /** Sum of the classes the difficulty multiplier is allowed to touch. */
    public int rareTotal() {
        int total = 0;
        for (ItemClass itemClass : ItemClass.values()) {
            if (itemClass.isRare()) {
                total += weight(itemClass);
            }
        }
        return total;
    }

    /**
     * Applies a difficulty multiplier to rare and above, paying for it out of common.
     *
     * <p>The total is preserved, so the result can be compared share for share against the
     * original. When common cannot cover the whole increase the increments are scaled back
     * proportionally and common lands at zero — see the class comment.
     *
     * @param multiplier 1.0 changes nothing; below 1.0 moves weight the other way, back into common
     * @return a new set; {@code this} when nothing would move
     */
    public RarityWeights scaled(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier < 0) {
            throw new IllegalArgumentException("a difficulty multiplier must be finite and "
                    + "non-negative - found: " + multiplier);
        }
        int rareBefore = rareTotal();
        if (multiplier == 1.0 || rareBefore == 0) {
            return this;
        }

        // What each rare class would like to become, and what that costs in total.
        EnumMap<ItemClass, Integer> increments = new EnumMap<>(ItemClass.class);
        int wanted = 0;
        for (ItemClass itemClass : ItemClass.values()) {
            if (!itemClass.isRare()) {
                continue;
            }
            int before = weight(itemClass);
            int after = (int) Math.round(before * multiplier);
            increments.put(itemClass, after - before);
            wanted += after - before;
        }
        if (wanted == 0) {
            return this;
        }

        int common = weight(ItemClass.COMMON);
        if (wanted > common) {
            // Common is the only source and it is short. Scale the increments back so it lands
            // exactly at zero; a table with no common at all keeps its distribution unchanged.
            trimTo(increments, common, wanted);
        }

        EnumMap<ItemClass, Integer> result = new EnumMap<>(ItemClass.class);
        int spent = 0;
        for (ItemClass itemClass : ItemClass.values()) {
            Integer increment = increments.get(itemClass);
            if (increment == null) {
                result.put(itemClass, weight(itemClass));
            } else {
                result.put(itemClass, weight(itemClass) + increment);
                spent += increment;
            }
        }
        result.put(ItemClass.COMMON, common - spent);
        return new RarityWeights(result);
    }

    /**
     * Cuts the increments down to a budget, in proportion, then hands the rounding remainder to the
     * largest increments first.
     *
     * <p>Proportional rather than "spend it on legendary until it runs out": the ratios <i>between</i>
     * the rare classes are what the operator wrote, and a budget shortfall must not quietly rewrite
     * them into something else.
     */
    private static void trimTo(EnumMap<ItemClass, Integer> increments, int budget, int wanted) {
        int handed = 0;
        List<ItemClass> order = new ArrayList<>();
        for (Map.Entry<ItemClass, Integer> entry : increments.entrySet()) {
            int scaled = (int) Math.floor((double) entry.getValue() * budget / wanted);
            entry.setValue(scaled);
            handed += scaled;
            order.add(entry.getKey());
        }
        // Descending by what was asked for, so the remainder goes where it was most wanted.
        order.sort((a, b) -> Integer.compare(increments.get(b), increments.get(a)));
        for (int i = 0; handed < budget && !order.isEmpty(); i++) {
            ItemClass itemClass = order.get(i % order.size());
            increments.put(itemClass, increments.get(itemClass) + 1);
            handed++;
        }
    }

    /**
     * Draws one class.
     *
     * @return {@code null} only when every weight is zero — the caller decides whether that is a
     *         problem, exactly as {@code MobRegistry.pick} does with an empty pool
     */
    public ItemClass pick(RandomGenerator random) {
        int total = total();
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (ItemClass itemClass : ItemClass.values()) {
            roll -= weight(itemClass);
            if (roll < 0) {
                return itemClass;
            }
        }
        // Unreachable while the weights are what total() just added up. Returning the rarest
        // present class rather than null keeps a future arithmetic slip from becoming a
        // NullPointerException three layers up.
        for (int i = ItemClass.values().length - 1; i >= 0; i--) {
            if (weight(ItemClass.values()[i]) > 0) {
                return ItemClass.values()[i];
            }
        }
        return null;
    }

    /**
     * Reads a {@code rarity:} block — {@code {common: 600, rare: 100, ...}}.
     *
     * <p>Strict about names: an unknown key is a typo, and a typo that is skipped produces a table
     * whose numbers do not add up to what the operator wrote.
     *
     * @param raw      the YAML map, or {@code null} when the block is absent
     * @param where    used in the error message
     * @param fallback returned when {@code raw} is {@code null}
     */
    public static RarityWeights parse(Map<?, ?> raw, String where, RarityWeights fallback) {
        if (raw == null) {
            return fallback;
        }
        EnumMap<ItemClass, Integer> weights = new EnumMap<>(ItemClass.class);
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            ItemClass itemClass = ItemClass.parse(String.valueOf(entry.getKey()));
            if (itemClass == null) {
                throw new IllegalArgumentException(where + ": unknown rarity class '"
                        + entry.getKey() + "' - valid: " + ItemClass.keyList());
            }
            if (!(entry.getValue() instanceof Number weight)) {
                throw new IllegalArgumentException(where + "." + itemClass.key()
                        + ": expected a number - found: " + entry.getValue());
            }
            if (weight.intValue() < 0) {
                throw new IllegalArgumentException(where + "." + itemClass.key()
                        + ": cannot be negative - found: " + weight);
            }
            weights.put(itemClass, weight.intValue());
        }
        if (weights.isEmpty()) {
            throw new IllegalArgumentException(where + ": the block is empty - remove it to use the "
                    + "default weights, or write at least one class");
        }
        return new RarityWeights(weights);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (ItemClass itemClass : ItemClass.values()) {
            if (weight(itemClass) == 0) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(itemClass.key()).append('=').append(weight(itemClass));
        }
        return text.isEmpty() ? "(empty)" : text.toString();
    }
}
