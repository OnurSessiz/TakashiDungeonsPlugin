package com.takashi.dungeons.generation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The pool of loaded room templates and the <b>weighted selection</b> over it —
 * {@code generation.md} §5.4.
 *
 * <h2>Weight belongs to the TEMPLATE, not to the {@code (template × door)} pair</h2>
 *
 * Since a candidate is a pair, a 4-door room produces four candidates; if the weight belonged
 * to the pair, that room would count its weight <b>four times</b>. The result is not merely a
 * shifted distribution but an <b>inverted ordering</b> relative to what the mapper wrote:
 *
 * <pre>
 *   test_corridor  2 doors  weight 150  →  pair-owned 21.4% | template-owned 25.4%
 *   test_cross     4 doors  weight 100  →  pair-owned 28.6% | template-owned 16.9%
 * </pre>
 *
 * The mapper gave the corridor the higher weight, yet under pair ownership the cross would
 * outrank it. The config would mean the opposite of what it says — and the distortion would
 * compound: the more multi-door rooms are placed, the more open doors appear, and every open
 * door again favours multi-door rooms.
 *
 * <p>The user-facing side requires this too: {@code weight} is edited by server owners in YAML,
 * and the expectation "if I write 200 it comes up twice as often as 100" has to hold. Door
 * count silently overriding it would be a bug nobody could diagnose, because the cause is
 * written down nowhere.
 *
 * <p><b>If we ever want to encourage branching</b>, that gets its own switchable config knob
 * rather than being buried inside the weight. The turn bias in §6.4 follows the same pattern.
 *
 * <h2>Pool filter</h2>
 * {@link RoomType#ENTRANCE} and {@link RoomType#BOSS} are <b>not</b> in the normal pool. Both
 * are assigned in {@code generation.md} §6.2; left in the pool, a boss room could materialize
 * in the middle of a dungeon.
 */
public final class RoomLibrary {

    private final List<RoomTemplate> all;
    private final List<RoomTemplate> normal;
    private final List<RoomTemplate> branching;
    private final List<RoomTemplate> entrances;
    private final List<RoomTemplate> bosses;

    public RoomLibrary(List<RoomTemplate> templates) {
        this.all = List.copyOf(templates);

        List<RoomTemplate> normalPool = new ArrayList<>();
        List<RoomTemplate> entrancePool = new ArrayList<>();
        List<RoomTemplate> bossPool = new ArrayList<>();
        for (RoomTemplate t : templates) {
            // A doorless room cannot be attached to the graph. Keeping it in the pool is not an
            // infinite loop, but it produces wasted attempts. Filtered out up front.
            if (t.doorCount() == 0) {
                continue;
            }
            switch (t.type()) {
                case ENTRANCE -> entrancePool.add(t);
                case BOSS -> bossPool.add(t);
                case NORMAL -> normalPool.add(t);
            }
        }
        this.normal = List.copyOf(normalPool);
        this.entrances = List.copyOf(entrancePool);
        this.bosses = List.copyOf(bossPool);
        this.branching = normalPool.stream().filter(t -> t.doorCount() > 1).toList();
    }

    public List<RoomTemplate> all() {
        return all;
    }

    /** The pool side branches and intermediate rooms are drawn from — no entrance, no boss. */
    public List<RoomTemplate> normalPool() {
        return normal;
    }

    /**
     * The <b>critical path</b> pool: {@link #normalPool()} with single-door rooms removed.
     *
     * <p>When a single-door room enters the path, that branch dies immediately — it has no door
     * to continue through beyond the one it arrived by. Measured in 1C
     * ({@code generation.md} §6.2): the "fill every open door" strategy only hits a 12-room
     * target <b>70%</b> of the time, and 86% of the stalls are not collisions but the
     * <b>door frontier running dry</b>. With single-door rooms filtered out of the path pool,
     * the same measurement rises to <b>97%</b>.
     *
     * <p>They stay allowed on side branches ({@link #normalPool()}): ending there is exactly
     * what you want. The same room is a problem in one pool and a feature in the other.
     */
    public List<RoomTemplate> branchingPool() {
        return branching;
    }

    public List<RoomTemplate> entrances() {
        return entrances;
    }

    public List<RoomTemplate> bosses() {
        return bosses;
    }

    /** Without at least one normal room that has doors, nothing can be generated. */
    public boolean isUsable() {
        return !normal.isEmpty();
    }

    /** Explains why the pool is unusable — shown in command output. */
    public String describeProblem() {
        if (isUsable()) {
            return null;
        }
        if (all.isEmpty()) {
            return "no room templates loaded (use /tdungeons gen to create test rooms)";
        }
        long doorless = all.stream().filter(t -> t.doorCount() == 0).count();
        return "no 'normal' room with doors — " + all.size() + " templates loaded, "
                + doorless + " of them doorless, the rest entrance/boss";
    }

    /**
     * Draws a template by weight and <b>removes it from the pool</b> (no replacement).
     *
     * <p>No replacement is essential for the backing-off in {@code generation.md} §5.3: if
     * every door of a template collides, that template must be out, otherwise we would keep
     * drawing the same candidate and retrying forever.
     *
     * @param pool   a <b>mutable</b> copy of the pool being worked on
     * @param random the randomness source
     * @return the drawn template, or {@code null} if the pool is empty
     */
    public static RoomTemplate drawWeighted(List<RoomTemplate> pool, RandomGenerator random) {
        if (pool.isEmpty()) {
            return null;
        }
        long total = 0;
        for (RoomTemplate t : pool) {
            total += t.weight();
        }
        // RoomMetadata already guarantees a positive weight; this is defence in depth.
        if (total <= 0) {
            return pool.remove(random.nextInt(pool.size()));
        }
        long roll = random.nextLong(total);
        long cumulative = 0;
        for (int i = 0; i < pool.size(); i++) {
            cumulative += pool.get(i).weight();
            if (roll < cumulative) {
                return pool.remove(i);
            }
        }
        return pool.remove(pool.size() - 1);   // integer maths, so this should be unreachable
    }

    /** A one-off weighted pick that leaves the pool untouched (for entrance / boss rooms). */
    public static RoomTemplate pickWeighted(List<RoomTemplate> pool, RandomGenerator random) {
        if (pool.isEmpty()) {
            return null;
        }
        List<RoomTemplate> copy = new ArrayList<>(pool);
        return drawWeighted(copy, random);
    }

    /** Dumps the pool's weight distribution as percentages — decision check and command output. */
    public static List<String> describeDistribution(List<RoomTemplate> pool) {
        long total = pool.stream().mapToLong(RoomTemplate::weight).sum();
        if (total <= 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (RoomTemplate t : pool) {
            double percent = 100.0 * t.weight() / total;
            lines.add(String.format("%-16s doors=%d  weight=%-4d  %%%.1f",
                    t.name(), t.doorCount(), t.weight(), percent));
        }
        return Collections.unmodifiableList(lines);
    }
}
