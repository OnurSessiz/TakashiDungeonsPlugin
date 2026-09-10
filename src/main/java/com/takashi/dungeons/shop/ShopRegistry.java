package com.takashi.dungeons.shop;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code shop.yml}: the merchant, the currency and the stock.
 *
 * <h2>One self-contained file</h2>
 * Merchant, rules and goods all live in {@code shop.yml} and {@code config.yml} gets nothing. That
 * is the shape {@code mobs.yml} and {@code loot.yml} already have — a catalogue carries the rules
 * that only make sense next to it, and phase 9's GUI editor will open one file rather than two.
 * The file is extracted once and <b>never overwritten</b>: the same promise made to whoever edits
 * a mob set or a loot table.
 *
 * <h2>Never throws</h2>
 * A broken shop file must not take the plugin down. Generation, instances, mobs, loot and parties
 * have nothing to do with a merchant, and a server with dungeons and no shop is better than a
 * server with neither. What went wrong is kept in {@link #loadError()} and {@link #disabled()},
 * which is where {@code /tdungeons shop list} reads it from.
 */
public final class ShopRegistry {

    public static final String FILE_NAME = "shop.yml";

    /**
     * How many entries the window can show — five rows of six, the sixth row being the footer.
     *
     * <p>Kept here rather than only in {@code ShopMenu} so that an over-long stock list is
     * reported when the file is READ. Found at open time it would be a merchant that silently
     * stops selling the last few things, which is the sort of thing nobody notices for a month.
     */
    public static final int MAX_SHOWN = 45;

    /** An entry that was read but cannot be used, and the reason a human needs to see. */
    public record Disabled(String id, String reason) {
    }

    private final TakashiDungeonsPlugin plugin;

    /** Insertion-ordered: the file's order is the order the goods sit in the menu. */
    private final Map<String, ShopEntry> stock = new LinkedHashMap<>();

    private final List<Disabled> disabled = new ArrayList<>();

    private boolean enabled;
    private @Nullable KeeperSpec keeper;
    private @Nullable ShopCurrency currency;
    private @Nullable String loadError;

    public ShopRegistry(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ loading

    public void load() {
        stock.clear();
        disabled.clear();
        enabled = false;
        keeper = null;
        currency = null;
        loadError = null;

        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        if (!file.exists()) {
            loadError = FILE_NAME + " could not be created - the shop is switched off.";
            plugin.getLogger().warning(loadError);
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        enabled = yaml.getBoolean("enabled", true);

        // The keeper and the currency are read even when the shop is off, so that a typo in
        // either is reported at start rather than the first time somebody switches it on.
        try {
            keeper = KeeperSpec.parse(yaml.getConfigurationSection("keeper"));
        } catch (RuntimeException error) {
            fail("keeper", error);
        }
        try {
            currency = ItemCurrency.parse(yaml.getConfigurationSection("currency"), "currency");
        } catch (RuntimeException error) {
            fail("currency", error);
        }

        ConfigurationSection stockSection = yaml.getConfigurationSection("stock");
        if (stockSection == null) {
            loadError = FILE_NAME + ": no 'stock' section - the merchant has nothing to sell.";
            plugin.getLogger().warning(loadError);
        } else {
            for (String id : stockSection.getKeys(false)) {
                readEntry(stockSection.getConfigurationSection(id), id);
            }
        }
        logSummary();
    }

    private void fail(String what, RuntimeException error) {
        // Recorded rather than thrown: a bad currency block must not also cost the operator the
        // stock listing, which is where they will look to work out what happened.
        disabled.add(new Disabled(what, error.getMessage()));
        plugin.getLogger().warning(FILE_NAME + ": " + error.getMessage());
    }

    private void readEntry(@Nullable ConfigurationSection section, String id) {
        if (section == null) {
            disabled.add(new Disabled(id, "the entry is empty"));
            return;
        }
        if (!section.getBoolean("enabled", true)) {
            return;   // switched off on purpose; not a problem to report
        }
        try {
            stock.put(id, ShopEntry.parse(section, id, plugin.getLootRegistry()));
        } catch (RuntimeException error) {
            disabled.add(new Disabled(id, error.getMessage()));
            plugin.getLogger().warning(FILE_NAME + ": " + error.getMessage());
        }
    }

    /**
     * One line at start, like the mob and loot registries.
     *
     * <p>An operator cannot see a merchant from the console, so this line is the only proof the
     * file was read at all — and the only place a shop that loaded empty announces itself.
     */
    private void logSummary() {
        if (!enabled) {
            plugin.getLogger().info("Shop is switched off (" + FILE_NAME + " -> enabled: false).");
            return;
        }
        if (stock.isEmpty()) {
            plugin.getLogger().warning("Shop loaded with NO stock - the merchant would open an "
                    + "empty menu, so it will not be spawned.");
            return;
        }
        if (stock.size() > MAX_SHOWN) {
            plugin.getLogger().warning("Shop has " + stock.size() + " entries but the window shows "
                    + MAX_SHOWN + "; the rest will not be sold. Trim " + FILE_NAME + " -> stock.");
        }
        plugin.getLogger().info("Shop loaded: " + stock.size() + " for sale, paid in "
                + (currency instanceof ItemCurrency item ? item.material().name() : "?")
                + ", keeper " + (keeper == null ? "?" : keeper.address())
                + (disabled.isEmpty() ? "" : " (" + disabled.size() + " disabled)"));
    }

    // ------------------------------------------------------------------ lookup

    /**
     * Whether a merchant can actually be put in a dungeon.
     *
     * <p>Everything has to be there — the switch, a keeper, a currency and something to sell. An
     * empty menu is worse than no merchant: the player walks up to it, and the dungeon looks
     * broken rather than plain.
     */
    public boolean isUsable() {
        return enabled && keeper != null && currency != null && !stock.isEmpty();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public @Nullable KeeperSpec keeper() {
        return keeper;
    }

    public @Nullable ShopCurrency currency() {
        return currency;
    }

    public @Nullable ShopEntry entry(String id) {
        return stock.get(id);
    }

    /** The goods, in the order the file lists them. */
    public Collection<ShopEntry> stock() {
        return List.copyOf(stock.values());
    }

    public List<Disabled> disabled() {
        return List.copyOf(disabled);
    }

    public @Nullable String loadError() {
        return loadError;
    }
}
