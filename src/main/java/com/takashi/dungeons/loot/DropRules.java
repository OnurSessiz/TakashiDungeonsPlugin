package com.takashi.dungeons.loot;

import com.takashi.dungeons.mob.MobClass;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a dungeon mob leaves behind — the {@code drops:} block of {@code loot.yml}.
 *
 * <h2>Two different things happen when a dungeon mob dies</h2>
 * An ordinary mob may add a roll of a table to the items on the floor. The <b>boss</b> does not:
 * its reward is a chest that appears where it died. That decision is not about size, it is about
 * what a floor drop does to a party — a boss's hoard scattered on the ground goes into whoever
 * swung last, can fall into lava or the void, and despawns on a timer nobody is watching. A chest
 * waits, and everyone can open it.
 *
 * <h2>Vanilla drops are suppressed by default, and that is one switch for the whole dungeon</h2>
 * A dungeon zombie's rotten flesh is the mob's own drop, not a reward anyone designed. Leaving it
 * in means the loot table is only ever part of the answer to "what is farming this worth", and the
 * part nobody configured scales with how many mobs a room holds. {@code vanilla-drops: true} puts
 * it back for operators who want the dungeon to feed a survival economy.
 *
 * <p>Note this is <b>not</b> the same question as {@link com.takashi.dungeons.mob.MobProvider
 * #defaultDungeonDrops()}. That one asks "may this mob receive the dungeon's table"; this one asks
 * "does any dungeon mob keep its own". The first is about where a mob came from, the second about
 * the dungeon's economy, and an operator changes them for unrelated reasons.
 *
 * @param vanillaDrops whether a dungeon mob keeps the drops it would have had outside
 * @param tables       mob class to the table its kills roll; a class absent from here drops nothing
 * @param chances      mob class to the chance a kill rolls at all, 0..1
 * @param bossTable    the table the boss's reward chest is filled from
 * @param bossMaterial what that chest is made of
 */
public record DropRules(boolean vanillaDrops, Map<MobClass, String> tables,
                        Map<MobClass, Double> chances, String bossTable, Material bossMaterial) {

    /**
     * What ships, and what an absent {@code drops:} block means.
     *
     * <p>The chances climb with the class rather than the table getting richer, because a rarer
     * mob should be worth killing more <i>often</i>, not worth more <i>each time</i> — a table per
     * class would mean five tables to balance against each other and five places to get it wrong.
     * The boss is not in the map at all: its reward is a chest, not a drop.
     */
    public static final DropRules DEFAULT = new DropRules(false,
            Map.of(MobClass.WEAK, "mob_drop", MobClass.NORMAL, "mob_drop",
                    MobClass.STRONG, "mob_drop", MobClass.SUPER_STRONG, "mob_drop"),
            Map.of(MobClass.WEAK, 0.08, MobClass.NORMAL, 0.14,
                    MobClass.STRONG, 0.28, MobClass.SUPER_STRONG, 0.5),
            "boss_chest", Material.CHEST);

    public DropRules {
        tables = Map.copyOf(tables);
        chances = Map.copyOf(chances);
        bossMaterial = bossMaterial == null ? Material.CHEST : bossMaterial;
    }

    /** The table this class's kills roll, or {@code null} when they drop nothing. */
    public @Nullable String tableFor(MobClass mobClass) {
        String id = tables.get(mobClass);
        return id == null || id.isBlank() ? null : id;
    }

    /** How often a kill of this class rolls at all, 0..1. */
    public double chanceFor(MobClass mobClass) {
        Double chance = chances.get(mobClass);
        return chance == null ? 0 : chance;
    }

    /**
     * Reads the {@code drops:} block. An absent block means {@link #DEFAULT}; a broken field falls
     * back to its default with a line for the caller to log.
     *
     * @param problems collects what was wrong — this record does no logging of its own, so it can
     *                 be read without a server
     */
    public static DropRules parse(@Nullable ConfigurationSection section, List<String> problems) {
        if (section == null) {
            return DEFAULT;
        }
        ConfigurationSection classes = section.getConfigurationSection("classes");
        Map<MobClass, String> tables = new EnumMap<>(MobClass.class);
        Map<MobClass, Double> chances = new EnumMap<>(MobClass.class);
        if (classes == null) {
            tables.putAll(DEFAULT.tables());
            chances.putAll(DEFAULT.chances());
        } else {
            readClasses(classes, tables, chances, problems);
        }

        ConfigurationSection boss = section.getConfigurationSection("boss");
        String bossTable = boss == null
                ? DEFAULT.bossTable() : boss.getString("table", DEFAULT.bossTable());
        Material bossMaterial = boss == null ? DEFAULT.bossMaterial()
                : readMaterial(boss.getString("material"), DEFAULT.bossMaterial(), problems);

        return new DropRules(
                section.getBoolean("vanilla-drops", DEFAULT.vanillaDrops()),
                tables, chances,
                bossTable == null || bossTable.isBlank() ? "" : bossTable.trim(),
                bossMaterial);
    }

    private static void readClasses(ConfigurationSection section, Map<MobClass, String> tables,
                                    Map<MobClass, Double> chances, List<String> problems) {
        for (String key : section.getKeys(false)) {
            MobClass mobClass = MobClass.parse(key);
            if (mobClass == null) {
                problems.add("drops.classes: unknown mob class '" + key + "'");
                continue;
            }
            if (mobClass == MobClass.BOSS) {
                // Not an error worth refusing the file over, but it would do nothing at all: the
                // boss never reaches the drop path. Said out loud so the operator does not spend
                // an evening tuning a number that is never read.
                problems.add("drops.classes.boss is ignored - the boss's reward is the chest that "
                        + "appears where it dies, configured under drops.boss.");
                continue;
            }
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                problems.add("drops.classes." + key + ": expected a block with 'table' and 'chance'");
                continue;
            }
            String table = entry.getString("table", "");
            double chance = entry.getDouble("chance", 0);
            if (chance < 0 || chance > 1) {
                problems.add("drops.classes." + key + ".chance must be between 0 and 1 (found: "
                        + chance + ") - treated as 0.");
                chance = 0;
            }
            if (table != null && !table.isBlank()) {
                tables.put(mobClass, table.trim());
            }
            chances.put(mobClass, chance);
        }
    }

    private static Material readMaterial(@Nullable String raw, Material fallback,
                                         List<String> problems) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw.trim());
        if (material == null || !material.isBlock()) {
            problems.add("drops.boss.material: '" + raw + "' is not a block material - using "
                    + fallback + " instead.");
            return fallback;
        }
        return material;
    }
}
