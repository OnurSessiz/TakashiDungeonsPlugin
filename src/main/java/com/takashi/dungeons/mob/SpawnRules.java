package com.takashi.dungeons.mob;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * How many mobs a room gets and which classes they are drawn from — the {@code spawn:} block of
 * {@code mobs.yml}.
 *
 * <h2>Count comes from floor area, not from depth</h2>
 * The number of mobs is the room's <b>walkable column count</b> divided by {@link #density}. Room
 * size is the thing the player can see: a great hall standing nearly empty and a corridor packed
 * shoulder to shoulder both read as bugs, and neither is fixed by knowing how deep the room is.
 * Depth changes <i>what</i> is in the room, not how many.
 *
 * <p>Walkable columns, not bounding-box area: a cross-shaped room's box is mostly wall, and sizing
 * by the box would put a hall's worth of mobs in the four arms of a cross.
 *
 * <h2>Class comes from RELATIVE depth</h2>
 * {@code depth / maxDepth}, not the raw depth. On absolute thresholds a four-room small dungeon
 * would be weak mobs from end to end — "small" means short, not harmless. Relative bands give
 * every size the same shape of difficulty curve over its own length.
 *
 * @param density      walkable columns per mob; higher means emptier rooms
 * @param minPerRoom   floor on the count, so a tiny room is not empty
 * @param maxPerRoom   ceiling on the count, so a great hall is not a mob farm
 * @param entranceMobs whether the entrance room is populated
 * @param bands        difficulty bands, in ascending order of {@link Band#until}
 */
public record SpawnRules(int density, int minPerRoom, int maxPerRoom, boolean entranceMobs,
                         List<Band> bands) {

    /**
     * One depth band and the class mix drawn inside it.
     *
     * @param until   upper bound of {@code depth / maxDepth} this band covers, inclusive
     * @param weights class → share; the same weighted-draw convention as room templates and loot
     */
    public record Band(double until, Map<MobClass, Integer> weights) {

        public Band {
            weights = Map.copyOf(weights);
        }

        /** Draws a class from this band; {@code null} only when the band is empty. */
        public MobClass pick(RandomGenerator random) {
            int total = 0;
            for (int weight : weights.values()) {
                total += weight;
            }
            if (total <= 0) {
                return null;
            }
            int roll = random.nextInt(total);
            for (Map.Entry<MobClass, Integer> entry : weights.entrySet()) {
                roll -= entry.getValue();
                if (roll < 0) {
                    return entry.getKey();
                }
            }
            return weights.keySet().iterator().next();
        }
    }

    /**
     * Used when {@code mobs.yml} has no {@code spawn:} block at all.
     *
     * <p>A working default rather than "no mobs": a server that upgrades the plugin and keeps its
     * old {@code mobs.yml} should get a populated dungeon, not an empty one it has to debug.
     */
    public static final SpawnRules DEFAULT = new SpawnRules(45, 1, 8, false, List.of(
            new Band(0.25, Map.of(MobClass.WEAK, 70, MobClass.NORMAL, 30)),
            new Band(0.55, Map.of(MobClass.WEAK, 25, MobClass.NORMAL, 60, MobClass.STRONG, 15)),
            new Band(0.80, Map.of(MobClass.NORMAL, 45, MobClass.STRONG, 45,
                    MobClass.SUPER_STRONG, 10)),
            new Band(1.00, Map.of(MobClass.NORMAL, 20, MobClass.STRONG, 55,
                    MobClass.SUPER_STRONG, 25))));

    public SpawnRules {
        density = Math.max(1, density);
        minPerRoom = Math.max(0, minPerRoom);
        maxPerRoom = Math.max(minPerRoom, maxPerRoom);
        bands = List.copyOf(bands);
    }

    /**
     * How many mobs a room with this much walkable floor gets.
     *
     * <p>Clamped at both ends, and the floor only applies to a room that has <b>somewhere</b> to
     * put a mob: a sealed or floorless room gets nothing rather than one mob wedged into it.
     */
    public int countFor(int walkableColumns) {
        if (walkableColumns <= 0) {
            return 0;
        }
        return Math.clamp(walkableColumns / density, minPerRoom, maxPerRoom);
    }

    /**
     * The band covering this relative depth.
     *
     * @param ratio {@code depth / maxDepth}, 0 at the entrance and 1 at the deepest room
     */
    public Band bandFor(double ratio) {
        for (Band band : bands) {
            if (ratio <= band.until()) {
                return band;
            }
        }
        return bands.isEmpty() ? null : bands.get(bands.size() - 1);
    }

    /**
     * Reads the {@code spawn:} block. A malformed band is skipped with a message rather than
     * taking the whole file down — the rest of the mob set is still usable.
     */
    public static SpawnRules parse(ConfigurationSection section) {
        if (section == null) {
            return DEFAULT;
        }
        List<Band> bands = new ArrayList<>();
        List<?> raw = section.getList("bands");
        if (raw != null) {
            for (Object entry : raw) {
                Band band = parseBand(entry);
                if (band != null) {
                    bands.add(band);
                }
            }
        }
        bands.sort((a, b) -> Double.compare(a.until(), b.until()));
        return new SpawnRules(
                section.getInt("density", DEFAULT.density()),
                section.getInt("min-per-room", DEFAULT.minPerRoom()),
                section.getInt("max-per-room", DEFAULT.maxPerRoom()),
                section.getBoolean("entrance", DEFAULT.entranceMobs()),
                bands.isEmpty() ? DEFAULT.bands() : bands);
    }

    private static Band parseBand(Object entry) {
        if (!(entry instanceof Map<?, ?> map)) {
            return null;
        }
        Object until = map.get("until");
        Object classes = map.get("classes");
        if (!(until instanceof Number bound) || !(classes instanceof Map<?, ?> mix)) {
            return null;
        }
        Map<MobClass, Integer> weights = new LinkedHashMap<>();
        for (Map.Entry<?, ?> pair : mix.entrySet()) {
            MobClass mobClass = MobClass.parse(String.valueOf(pair.getKey()));
            if (mobClass != null && pair.getValue() instanceof Number weight
                    && weight.intValue() > 0) {
                weights.put(mobClass, weight.intValue());
            }
        }
        return weights.isEmpty() ? null : new Band(bound.doubleValue(), weights);
    }
}
