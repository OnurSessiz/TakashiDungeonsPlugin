package com.takashi.dungeons.generation;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Turns generation seeds into a source of randomness.
 *
 * <h2>Why this is its own class — there is a measured trap here</h2>
 *
 * {@code new java.util.Random(seed)} is <b>correlated across consecutive seeds</b>. Java's
 * {@code Random} is an LCG whose first output is a direct function of the seed's high bits, so
 * adjacent seeds produce adjacent internal state. Measured:
 *
 * <pre>
 *   new Random(seed).nextInt(4), seed = 1..40:
 *     2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2
 *
 *   Distribution over 4000 consecutive seeds: [0, 0, 1857, 2143]   <- 0 and 1 never appear
 * </pre>
 *
 * <p><b>The concrete cost:</b> a {@code small} dungeon's room count is drawn from the 3-6
 * range. If the first draw always came out the same on consecutive seeds, every small dungeon
 * would have 5-6 rooms and 3-4 would never be seen. The promise of a "range" would not hold.
 *
 * <p><b>Why it really would have happened:</b> in phase 7 instances get written to the
 * database, and the natural things to seed with are an incrementing id or
 * {@code System.currentTimeMillis()} — both consecutive. The failure would also have been
 * silent: dungeons still generate, they just always come out the same size.
 *
 * <h2>The fix</h2>
 * The seed is first mixed through <b>splitmix64</b>, then handed to a {@link SplittableRandom}.
 * The splitmix64 bit mixer spreads a 1-bit difference in the seed across all output bits, and
 * {@code SplittableRandom} is a far better generator than {@code Random}. The same measurement
 * after mixing: {@code [977, 1010, 973, 1040]} — uniform.
 *
 * <p>Reproducibility is preserved: the mixing is <b>deterministic</b>, so the same seed still
 * gives the same dungeon.
 */
public final class Seeds {

    private Seeds() {
    }

    /**
     * The splitmix64 final mixing step — spreads a 1-bit input difference across half the output.
     *
     * <p>The constants come from the reference splitmix64 implementation and must not be
     * changed; the quality of the mixing depends on these multipliers.
     */
    public static long mix(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Builds a randomness source from a seed — consecutive seeds give independent streams. */
    public static RandomGenerator from(long seed) {
        return new SplittableRandom(mix(seed));
    }

    /**
     * The {@code n}-th derivative of the same master seed — used for retries.
     *
     * <p>The derivation is deterministic too, so the whole generation stays reproducible from a
     * single seed. If a retry drew a fresh random seed instead, bringing back "that broken
     * dungeon" would be impossible.
     */
    public static RandomGenerator derive(long seed, int index) {
        return derive(seed, index, 0);
    }

    /**
     * An independent stream from the same seed and index, for a second system that indexes the
     * same things.
     *
     * <h2>Why this exists</h2>
     * Two systems both index rooms by node id: {@code MobPopulator} and {@code LootPopulator}. Had
     * loot called {@link #derive(long, int)} it would have been handed the <b>identical sequence
     * of numbers</b> the mob populator had already consumed for that room — same seed, same index,
     * same generator. Nothing would look broken: each system uses its numbers for its own thing.
     * But the two would be locked together, so the room that drew high for its mob class would
     * draw high for its loot rarity, in every dungeon, forever.
     *
     * <p>That is the kind of fault this project treats as expensive: invisible in play, impossible
     * to attribute, and it would survive every test that checks each system on its own.
     *
     * @param stream which system is asking. Multiplied by an odd 64-bit constant before mixing, so
     *               two streams cannot land on the same state by having nearby numbers
     */
    public static RandomGenerator derive(long seed, int index, long stream) {
        return new SplittableRandom(mix(seed + index * 0x9E3779B97F4A7C15L
                + stream * 0xD1B54A32D192ED03L));
    }
}
