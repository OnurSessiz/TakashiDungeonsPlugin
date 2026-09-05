package com.takashi.dungeons.mob;

import com.takashi.dungeons.TakashiDungeonsPlugin;
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
 * The mob catalogue: what {@code mobs.yml} declares, which providers can actually deliver it, and
 * the weighted pools the spawner draws from.
 *
 * <h2>Its own file, not config.yml</h2>
 * The mob set is the thing an operator edits most, and phase 9's GUI editor will <b>write</b> it.
 * A program that rewrites {@code config.yml} destroys the comments in it — every explanation this
 * project puts next to a setting would be gone after the first GUI save. A separate file the
 * editor owns keeps that from ever being a question.
 *
 * <h2>A definition is disabled, never silently redirected</h2>
 * When the named provider is absent or does not know the key, the entry is dropped into
 * {@link #disabled()} with a sentence saying why, and {@code /tdungeons mob list} shows it. The
 * tempting alternative — falling back to a vanilla zombie — turns a missing plugin into a balance
 * complaint three weeks later.
 *
 * <p>The out-of-box guarantee ({@code anahedef.md} §4) is met by the shipped file being entirely
 * vanilla, so a server with no mob plugin has a full set with nothing disabled.
 */
public final class MobRegistry {

    public static final String FILE_NAME = "mobs.yml";

    /** A definition that was read but cannot be used, and the reason a human needs to see. */
    public record Disabled(String id, String address, String reason) {
    }

    private final TakashiDungeonsPlugin plugin;

    /** Provider id → provider, in registration order. */
    private final Map<String, MobProvider> providers = new LinkedHashMap<>();

    private final Map<String, MobDefinition> definitions = new LinkedHashMap<>();
    private final Map<MobClass, List<MobDefinition>> pools = new EnumMap<>(MobClass.class);
    private final Map<Difficulty, DifficultyScaling> scalings = new EnumMap<>(Difficulty.class);
    private final List<Disabled> disabled = new ArrayList<>();

    private Difficulty defaultDifficulty = Difficulty.MEDIUM;

    /** Problems that stopped the file being read at all — shown by {@code /tdungeons mob list}. */
    private @Nullable String loadError;

    public MobRegistry(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ providers

    /**
     * Registers a provider. Later registrations replace an earlier one with the same id, so an
     * addon can supersede a built-in source without the core knowing about it (phase 8).
     */
    public void register(MobProvider provider) {
        providers.put(provider.id(), provider);
    }

    public @Nullable MobProvider provider(String id) {
        return providers.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public Collection<MobProvider> providers() {
        return List.copyOf(providers.values());
    }

    // ------------------------------------------------------------------ loading

    /**
     * Reads {@code mobs.yml}, writing the bundled copy first if the file is not there.
     *
     * <p>Never throws. A broken mob file must not take the plugin down with it: generation,
     * instances and portals all still work, and the reason sits in {@link #loadError()} where
     * {@code /tdungeons mob list} shows it.
     */
    public void load() {
        definitions.clear();
        pools.clear();
        scalings.clear();
        disabled.clear();
        loadError = null;

        for (MobProvider provider : providers.values()) {
            if (provider instanceof MythicMobsProvider mythic) {
                mythic.reset();
                mythic.refresh();
            }
        }

        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        if (!file.exists()) {
            loadError = FILE_NAME + " oluşturulamadı — mob sistemi boş çalışıyor.";
            plugin.getLogger().warning(loadError);
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        readDifficulties(yaml.getConfigurationSection("difficulty"));

        Difficulty parsed = Difficulty.parse(yaml.getString("default-difficulty", "medium"));
        if (parsed == null) {
            plugin.getLogger().warning(FILE_NAME + ": geçersiz default-difficulty '"
                    + yaml.getString("default-difficulty") + "' — medium kullanılıyor.");
            parsed = Difficulty.MEDIUM;
        }
        defaultDifficulty = parsed;

        ConfigurationSection mobs = yaml.getConfigurationSection("mobs");
        if (mobs == null) {
            loadError = FILE_NAME + ": 'mobs' bölümü yok — hiçbir mob tanımlı değil.";
            plugin.getLogger().warning(loadError);
            return;
        }
        for (String id : mobs.getKeys(false)) {
            readDefinition(mobs.getConfigurationSection(id), id);
        }
        buildPools();
        logSummary();
    }

    private void readDifficulties(@Nullable ConfigurationSection section) {
        for (Difficulty difficulty : Difficulty.values()) {
            ConfigurationSection block = section == null
                    ? null : section.getConfigurationSection(difficulty.key());
            try {
                scalings.put(difficulty, DifficultyScaling.parse(block,
                        "difficulty." + difficulty.key()));
            } catch (IllegalArgumentException error) {
                plugin.getLogger().warning(FILE_NAME + ": " + error.getMessage()
                        + " — bu zorluk ölçeklenmeden kullanılacak.");
                scalings.put(difficulty, DifficultyScaling.NEUTRAL);
            }
        }
    }

    /** Reads one entry; a bad entry is skipped with a message, it does not abort the file. */
    private void readDefinition(@Nullable ConfigurationSection section, String id) {
        if (section == null) {
            disabled.add(new Disabled(id, "?", "girdi bir bölüm değil (altında alanlar yok)"));
            return;
        }
        if (!section.getBoolean("enabled", true)) {
            return;
        }
        MobDefinition definition;
        try {
            definition = MobDefinition.parse(section, id);
        } catch (IllegalArgumentException error) {
            disabled.add(new Disabled(id, String.valueOf(section.getString("mob")),
                    error.getMessage()));
            plugin.getLogger().warning(FILE_NAME + ": " + error.getMessage());
            return;
        }

        MobProvider provider = providers.get(definition.providerId());
        if (provider == null) {
            disabled.add(new Disabled(id, definition.address(), "bilinmeyen sağlayıcı '"
                    + definition.providerId() + "' — tanımlı olanlar: " + String.join(", ",
                    providers.keySet())));
            return;
        }
        if (!provider.isAvailable()) {
            disabled.add(new Disabled(id, definition.address(),
                    provider.displayName() + " kurulu değil"));
            return;
        }
        if (!provider.supports(definition.mobKey())) {
            disabled.add(new Disabled(id, definition.address(),
                    provider.displayName() + " böyle bir mob tanımıyor"));
            return;
        }
        definitions.put(id, definition);
    }

    private void buildPools() {
        for (MobClass mobClass : MobClass.values()) {
            pools.put(mobClass, new ArrayList<>());
        }
        for (MobDefinition definition : definitions.values()) {
            pools.get(definition.mobClass()).add(definition);
        }
    }

    private void logSummary() {
        StringBuilder counts = new StringBuilder();
        for (MobClass mobClass : MobClass.values()) {
            if (!counts.isEmpty()) {
                counts.append(", ");
            }
            counts.append(mobClass.key()).append('=').append(pool(mobClass).size());
        }
        plugin.getLogger().info("Mob kaydı yüklendi: " + definitions.size() + " mob ("
                + counts + ")" + (disabled.isEmpty() ? "" : ", " + disabled.size()
                + " devre dışı — /tdungeons mob list"));
        // Each disabled entry gets its own line: the count alone tells an operator that something
        // is wrong without telling them what, which is the worst of both.
        for (Disabled entry : disabled) {
            plugin.getLogger().warning("  devre dışı: " + entry.id() + " (" + entry.address()
                    + ") — " + entry.reason());
        }
    }

    // ------------------------------------------------------------------ lookup

    public @Nullable MobDefinition definition(String id) {
        return definitions.get(id);
    }

    /** Every usable definition, in file order. */
    public Collection<MobDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    /** The usable definitions in one class, in file order. */
    public List<MobDefinition> pool(MobClass mobClass) {
        return List.copyOf(pools.getOrDefault(mobClass, List.of()));
    }

    /** Entries that were read but cannot be spawned, with the reason for each. */
    public List<Disabled> disabled() {
        return List.copyOf(disabled);
    }

    public @Nullable String loadError() {
        return loadError;
    }

    public boolean isEmpty() {
        return definitions.isEmpty();
    }

    public DifficultyScaling scaling(Difficulty difficulty) {
        return scalings.getOrDefault(difficulty, DifficultyScaling.NEUTRAL);
    }

    public Difficulty defaultDifficulty() {
        return defaultDifficulty;
    }

    /**
     * Draws one definition from a class pool, weighted — the same 1000-based convention as room
     * templates and (phase 4) loot, so an operator learns the meaning of {@code weight} once.
     *
     * @return {@code null} when the pool is empty; the caller decides whether that is a problem
     */
    public @Nullable MobDefinition pick(MobClass mobClass, RandomGenerator random) {
        List<MobDefinition> pool = pools.getOrDefault(mobClass, List.of());
        if (pool.isEmpty()) {
            return null;
        }
        int total = 0;
        for (MobDefinition definition : pool) {
            total += definition.weight();
        }
        int roll = random.nextInt(total);
        for (MobDefinition definition : pool) {
            roll -= definition.weight();
            if (roll < 0) {
                return definition;
            }
        }
        // Unreachable while weights are positive, which parsing guarantees. Returning the last
        // entry rather than null keeps a future weight bug from becoming a NullPointerException
        // three layers up.
        return pool.get(pool.size() - 1);
    }
}
