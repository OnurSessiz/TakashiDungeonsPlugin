package com.takashi.dungeons.loot;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.generation.RoomType;
import com.takashi.dungeons.mob.Difficulty;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * The loot catalogue: what {@code loot.yml} declares, which entries are actually usable, the
 * weighted pools a draw comes from, and the named tables that describe a draw.
 *
 * <h2>Its own file, and the reasons are the ones mobs.yml already gave</h2>
 * Phase 9's GUI editor will <b>write</b> this file, and a program that rewrites a file destroys the
 * comments in it. Keeping loot out of {@code config.yml} is what lets both files be explained in
 * place without the explanations being one GUI save away from deletion.
 *
 * <h2>An entry is disabled, never silently replaced</h2>
 * A misspelled material or enchantment drops the entry into {@link #disabled()} with a sentence
 * saying why, and {@code /tdungeons loot list} shows it. This is the same rule
 * {@link com.takashi.dungeons.mob.MobRegistry} follows, for the same reason: a legendary that
 * quietly became a stone sword is a balance complaint nobody traces back to a typo.
 *
 * <h2>An empty class pool is reported at load, not discovered in play</h2>
 * A draw that lands on a class with no items in it produces nothing — the alternative, sliding down
 * to the next class, is exactly the silent substitution above. So a pool that is empty while the
 * weights still point at it gets a warning line at load: that combination means a measurable share
 * of every chest is quietly empty.
 */
public final class LootRegistry {

    public static final String FILE_NAME = "loot.yml";

    /** An entry that was read but cannot be used, and the reason a human needs to see. */
    public record Disabled(String id, String what, String reason) {
    }

    private final TakashiDungeonsPlugin plugin;

    private final Map<String, LootItem> items = new LinkedHashMap<>();
    private final Map<ItemClass, List<LootItem>> pools = new EnumMap<>(ItemClass.class);
    private final Map<String, LootTable> tables = new LinkedHashMap<>();
    private final Map<Difficulty, Double> multipliers = new EnumMap<>(Difficulty.class);
    private final List<Disabled> disabled = new ArrayList<>();

    private RarityWeights baseWeights = RarityWeights.DEFAULT;
    private ChestRules chestRules = ChestRules.DEFAULT;

    /** Problems that stopped the file being read at all — shown by {@code /tdungeons loot list}. */
    private @Nullable String loadError;

    public LootRegistry(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ loading

    /**
     * Reads {@code loot.yml}, writing the bundled copy first if the file is not there.
     *
     * <p>Never throws. A broken loot file must not take the plugin down with it: generation,
     * instances, portals and mobs all still work, and the reason sits in {@link #loadError()}.
     */
    public void load() {
        items.clear();
        pools.clear();
        tables.clear();
        multipliers.clear();
        disabled.clear();
        baseWeights = RarityWeights.DEFAULT;
        chestRules = ChestRules.DEFAULT;
        loadError = null;

        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        if (!file.exists()) {
            loadError = FILE_NAME + " could not be created - the loot system is running empty.";
            plugin.getLogger().warning(loadError);
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        readRarity(yaml.getConfigurationSection("rarity"));
        readDifficulties(yaml.getConfigurationSection("difficulty"));

        ConfigurationSection itemSection = yaml.getConfigurationSection("items");
        if (itemSection == null) {
            loadError = FILE_NAME + ": no 'items' section - no loot is defined.";
            plugin.getLogger().warning(loadError);
        } else {
            for (String id : itemSection.getKeys(false)) {
                readItem(itemSection.getConfigurationSection(id), id);
            }
        }
        buildPools();
        readTables(yaml.getConfigurationSection("tables"));
        readChests(yaml.getConfigurationSection("chests"));
        logSummary();
    }

    /**
     * Reads the {@code chests:} block — where chests come from and which table each room type
     * draws.
     *
     * <p>{@link ChestRules} does no logging of its own so that it stays readable without a server;
     * it collects what was wrong and this method says it.
     */
    private void readChests(@Nullable ConfigurationSection section) {
        List<String> problems = new ArrayList<>();
        chestRules = ChestRules.parse(section, problems);
        // A table id that names nothing is the quietest mistake in this file: rooms of that type
        // simply have no loot, which looks exactly like a generation that went thin. Said here
        // because it can only be checked once the tables themselves have been read.
        for (RoomType type : RoomType.values()) {
            String id = chestRules.tableFor(type);
            if (id != null && !tables.containsKey(id)) {
                problems.add("chests.tables." + type.yamlValue() + " names table '" + id
                        + "', which is not defined - " + type.yamlValue()
                        + " rooms get no loot at all.");
            }
        }
        for (String problem : problems) {
            plugin.getLogger().warning(FILE_NAME + ": " + problem);
        }
    }

    private void readRarity(@Nullable ConfigurationSection section) {
        if (section == null) {
            return;
        }
        try {
            baseWeights = RarityWeights.parse(section.getValues(false), "rarity",
                    RarityWeights.DEFAULT);
        } catch (IllegalArgumentException error) {
            plugin.getLogger().warning(FILE_NAME + ": " + error.getMessage()
                    + " - the default weights are used instead.");
            baseWeights = RarityWeights.DEFAULT;
        }
    }

    /**
     * Reads the {@code difficulty:} block — one multiplier per level.
     *
     * <p>A missing level means 1.0 rather than an error: an operator who only wants to change hard
     * should be able to write that one line.
     */
    private void readDifficulties(@Nullable ConfigurationSection section) {
        for (Difficulty difficulty : Difficulty.values()) {
            double value = section == null ? 1.0 : section.getDouble(difficulty.key(), 1.0);
            if (!Double.isFinite(value) || value < 0) {
                plugin.getLogger().warning(FILE_NAME + ": difficulty." + difficulty.key()
                        + " must be a finite, non-negative number (found: " + value + ") - using 1.0.");
                value = 1.0;
            }
            multipliers.put(difficulty, value);
        }
    }

    private void readItem(@Nullable ConfigurationSection section, String id) {
        if (section == null) {
            disabled.add(new Disabled(id, "?", "the entry is not a section (it has no fields under it)"));
            return;
        }
        if (!section.getBoolean("enabled", true)) {
            return;
        }
        try {
            LootItem item = LootItem.parse(section, id);
            items.put(id, item);
        } catch (IllegalArgumentException error) {
            disabled.add(new Disabled(id, String.valueOf(section.getString("material")),
                    error.getMessage()));
            plugin.getLogger().warning(FILE_NAME + ": " + error.getMessage());
        }
    }

    private void buildPools() {
        for (ItemClass itemClass : ItemClass.values()) {
            pools.put(itemClass, new ArrayList<>());
        }
        for (LootItem item : items.values()) {
            pools.get(item.itemClass()).add(item);
        }
    }

    /**
     * Reads the {@code tables:} block. A malformed table is disabled with its reason rather than
     * taking the file down — the rest of the loot set is still usable.
     */
    private void readTables(@Nullable ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection block = section.getConfigurationSection(id);
            if (block == null) {
                disabled.add(new Disabled(id, "table", "the entry is not a section"));
                continue;
            }
            try {
                ConfigurationSection rarity = block.getConfigurationSection("rarity");
                tables.put(id, new LootTable(id,
                        CountRange.parse(block.get("rolls"), "tables." + id + " -> rolls",
                                CountRange.fixed(1)),
                        RarityWeights.parse(rarity == null ? null : rarity.getValues(false),
                                "tables." + id + " -> rarity", baseWeights)));
            } catch (IllegalArgumentException error) {
                disabled.add(new Disabled(id, "table", error.getMessage()));
                plugin.getLogger().warning(FILE_NAME + ": tables." + id + ": " + error.getMessage());
            }
        }
    }

    private void logSummary() {
        StringBuilder counts = new StringBuilder();
        for (ItemClass itemClass : ItemClass.values()) {
            if (!counts.isEmpty()) {
                counts.append(", ");
            }
            counts.append(itemClass.key()).append('=').append(pool(itemClass).size());
        }
        plugin.getLogger().info("Loot registry loaded: " + items.size() + " items (" + counts
                + "), " + tables.size() + " tables"
                + (disabled.isEmpty() ? "" : ", " + disabled.size()
                        + " disabled - /tdungeons loot list"));
        for (Disabled entry : disabled) {
            plugin.getLogger().warning("  disabled: " + entry.id() + " (" + entry.what() + ") - "
                    + entry.reason());
        }
        warnAboutGaps();
    }

    /**
     * The two configuration gaps that are invisible in play, said out loud once at load.
     *
     * <p>Neither is an error and neither stops anything; both produce a dungeon that is subtly not
     * what the operator wrote, and both are otherwise found by comparing dozens of chests.
     */
    private void warnAboutGaps() {
        for (LootTable table : tables.values()) {
            for (ItemClass itemClass : ItemClass.values()) {
                if (table.rarity().weight(itemClass) > 0 && pool(itemClass).isEmpty()) {
                    plugin.getLogger().warning("  table '" + table.id() + "' draws "
                            + Math.round(table.rarity().share(itemClass) * 100) + "% "
                            + itemClass.key() + ", but no item has that class - that share of every "
                            + "roll produces nothing.");
                }
            }
            warnAboutCapping(table);
        }
    }

    /**
     * Says so when a table's difficulty multiplier cannot be paid for.
     *
     * <p>Common is the only weight the multiplier may spend, so a table needs
     * {@code common >= (multiplier - 1) x rareTotal} for that multiplier to apply in full. Below
     * that line common bottoms out at zero and <b>every multiplier past it produces the same
     * table</b> — medium and hard become the same reward and nothing in play says so. The shipped
     * {@code boss_chest} was written wrong in exactly this way once, which is why this check
     * exists rather than a sentence in a comment.
     */
    private void warnAboutCapping(LootTable table) {
        int common = table.rarity().weight(ItemClass.COMMON);
        List<String> capped = new ArrayList<>();
        for (Difficulty difficulty : Difficulty.values()) {
            double multiplier = multiplier(difficulty);
            if (multiplier > 1.0 && table.weightsFor(multiplier).weight(ItemClass.COMMON) == 0) {
                capped.add(difficulty.key());
            }
        }
        if (capped.isEmpty()) {
            return;
        }
        if (common == 0) {
            plugin.getLogger().info("  table '" + table.id() + "' has no common weight, so the "
                    + "difficulty multiplier has nothing to move - it is the same at every "
                    + "difficulty.");
            return;
        }
        plugin.getLogger().warning("  table '" + table.id() + "' runs out of common weight at: "
                + String.join(", ", capped) + " - those difficulties produce the same table. "
                + "Needs common >= (multiplier - 1) x " + table.rarity().rareTotal()
                + ", has " + common + ".");
    }

    // ------------------------------------------------------------------ lookup

    public @Nullable LootItem item(String id) {
        return items.get(id);
    }

    /** Every usable item, in file order. */
    public Collection<LootItem> items() {
        return List.copyOf(items.values());
    }

    /** The usable items in one class, in file order. */
    public List<LootItem> pool(ItemClass itemClass) {
        return List.copyOf(pools.getOrDefault(itemClass, List.of()));
    }

    public @Nullable LootTable table(String id) {
        return tables.get(id);
    }

    public Collection<LootTable> tables() {
        return List.copyOf(tables.values());
    }

    /** The class split used by a table that does not override it. */
    public RarityWeights baseWeights() {
        return baseWeights;
    }

    /** Where chests come from and which table each room type draws — the {@code chests:} block. */
    public ChestRules chestRules() {
        return chestRules;
    }

    /** The multiplier applied to rare and above at this difficulty. */
    public double multiplier(Difficulty difficulty) {
        return multipliers.getOrDefault(difficulty, 1.0);
    }

    /** Entries that were read but cannot be used, with the reason for each. */
    public List<Disabled> disabled() {
        return List.copyOf(disabled);
    }

    public @Nullable String loadError() {
        return loadError;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * Draws one item from a class pool, weighted — the same 1000-based convention as room templates
     * and {@code mobs.yml}, so an operator learns the meaning of {@code weight} once.
     *
     * @return {@code null} when the pool is empty; the caller decides whether that is a problem.
     *         It never slides to another class: that is the silent substitution this registry
     *         refuses on principle
     */
    public @Nullable LootItem pick(ItemClass itemClass, RandomGenerator random) {
        List<LootItem> pool = pools.getOrDefault(itemClass, List.of());
        if (pool.isEmpty()) {
            return null;
        }
        int total = 0;
        for (LootItem item : pool) {
            total += item.weight();
        }
        int roll = random.nextInt(total);
        for (LootItem item : pool) {
            roll -= item.weight();
            if (roll < 0) {
                return item;
            }
        }
        // Unreachable while weights are positive, which parsing guarantees. Returning the last
        // entry rather than null keeps a future weight bug from becoming a NullPointerException
        // three layers up.
        return pool.get(pool.size() - 1);
    }
}
