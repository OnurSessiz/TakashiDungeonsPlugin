package com.takashi.dungeons.loot;

import java.util.random.RandomGenerator;

/**
 * A named recipe for "roll some loot" — how many draws, and how those draws are split between the
 * rarity classes.
 *
 * <h2>One concept with two consumers</h2>
 * A room chest and a boss's reward differ in <i>how many</i> draws and <i>how rare</i> they lean —
 * not in how a draw works. Naming that pair once and letting both sides reference it by id keeps
 * the operator reading one vocabulary, and keeps a future third consumer (phase 4C's mob drops,
 * phase 6's supply mob) from growing a fourth parallel block in {@code loot.yml}.
 *
 * <h2>A table may override the rarity split, and then difficulty may do nothing</h2>
 * A boss table written as {@code rare: 500, ultra_rare: 350, legendary: 150} has no common weight,
 * and common is the only thing the difficulty multiplier can spend ({@link RarityWeights#scaled}).
 * That table is therefore the same on easy and on hard. It is a legitimate thing to want — the
 * boss reward being flat is a design choice — so it is allowed, and {@link LootRegistry} names the
 * tables it applies to at load time so nobody finds out by comparing two runs.
 *
 * <p>Pure Java: parsing happens in {@link LootRegistry}, which has Bukkit's config reader; what is
 * left here is arithmetic that {@code scripts/geo-probe/LootProbe.java} can run without a server.
 *
 * @param id     the name written in {@code loot.yml}, referenced by chests and drops
 * @param rolls  how many draws this table makes; each draw picks a class, then an item in it
 * @param rarity the class split for this table
 */
public record LootTable(String id, CountRange rolls, RarityWeights rarity) {

    public LootTable {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a loot table needs an id");
        }
        if (rolls == null) {
            throw new IllegalArgumentException(id + ": a loot table needs a 'rolls' count");
        }
        if (rarity == null || rarity.isEmpty()) {
            throw new IllegalArgumentException(id + ": a loot table needs a non-empty rarity split");
        }
    }

    /** How many draws this roll of the table makes. */
    public int rollCount(RandomGenerator random) {
        return rolls.roll(random);
    }

    /**
     * The class split this table uses at a given difficulty.
     *
     * <p>Computed once per chest rather than once per draw — the arithmetic is the same for every
     * draw of the same chest, and doing it inside the loop would be the kind of waste that only
     * shows up when twenty instances open at once.
     */
    public RarityWeights weightsFor(double difficultyMultiplier) {
        return rarity.scaled(difficultyMultiplier);
    }

    @Override
    public String toString() {
        return id + " (rolls=" + rolls + ", " + rarity + ")";
    }
}
