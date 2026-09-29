package com.takashi.dungeons.editor;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.gui.Click;
import com.takashi.dungeons.gui.Icons;
import com.takashi.dungeons.gui.Menu;
import com.takashi.dungeons.loot.ItemClass;
import com.takashi.dungeons.loot.LootItem;
import com.takashi.dungeons.loot.LootRegistry;
import com.takashi.dungeons.loot.LootTable;
import com.takashi.dungeons.mob.Difficulty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code items:} entry — the numbers that decide how often it turns up and how many.
 *
 * <p>Enchantments, lore and hide flags stay in the file: they are authored once, not tuned, and a
 * chest window is a poor place to write a paragraph of lore.
 */
final class LootItemEditMenu extends DraftMenu {

    private static final List<String> FIELDS = List.of("material", "class", "weight", "amount",
            "enabled", "glow", "unbreakable", "name");

    private static final RangeField AMOUNT = RangeField.whole("amount", 1, 1, 1);

    private final String id;

    LootItemEditMenu(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent, String id,
                     Map<String, Object> fields) {
        super(plugin, viewer, parent, Component.text("Loot item - " + id, NamedTextColor.DARK_GRAY),
                LootRegistry.FILE_NAME, draftOf(id, fields));
        this.id = id;
    }

    private static Draft draftOf(String id, Map<String, Object> fields) {
        Map<String, Object> values = new HashMap<>();
        for (String field : FIELDS) {
            values.put(field, fields.get(field));
        }
        return new Draft(List.of("items", id), values);
    }

    @Override
    protected void renderBody() {
        Map<String, Object> current = new HashMap<>();
        for (String field : FIELDS) {
            current.put(field, draft.get(field));
        }
        ItemStack shown = LootItemListMenu.display(plugin, id, current);
        Material material = material();
        List<Component> header = new ArrayList<>();
        header.add(Icons.line("<gray>Id <white>" + id));
        header.add(Icons.line("<gray>Material <white>" + (material == null ? draft.get("material")
                : material.getKey().getKey())));
        header.add(Icons.line(LootItemListMenu.status(plugin, id,
                Map.of("enabled", draft.original("enabled") == null ? true : draft.original("enabled")))));
        header.add(Icons.line("<dark_gray>The picture is the loaded item - it updates after Save."));
        header.add(Component.empty());
        header.add(Icons.line("<gray>Q: change the material"));
        button(4, mark(Icons.icon(shown.getType(), shown.getItemMeta() != null
                && shown.getItemMeta().displayName() != null
                ? shown.getItemMeta().displayName() : Icons.line("<white>" + id), header), "material"),
                click -> {
                    if (click.drop()) {
                        askText(this::setMaterial, "<yellow>Type the Minecraft item id.",
                                "<gray>For example <white>DIAMOND_SWORD</white>.");
                    }
                });

        renderClass(10);
        renderWeight(11);
        renderToggle(12, "enabled", true, Material.LIME_CONCRETE, Material.GRAY_CONCRETE,
                "Enabled", "Off keeps it in the file but out of every pool.");
        renderToggle(13, "glow", false, Material.GLOWSTONE_DUST, Material.GUNPOWDER,
                "Glow", "The enchantment shimmer, without an enchantment.");
        renderToggle(14, "unbreakable", false, Material.OBSIDIAN, Material.COBBLESTONE,
                "Unbreakable", "Never loses durability.");
        renderName(16);
        renderAmount(19, material);
        decor(24, chances());
        for (int row = 0; row < 5; row++) {
            frameRow(row);
        }
    }

    private @Nullable Material material() {
        return Material.matchMaterial(String.valueOf(draft.get("material")));
    }

    private void renderClass(int slot) {
        ItemClass current = ItemClass.parse(String.valueOf(draft.get("class") == null
                ? "common" : draft.get("class")));
        List<String> lore = new ArrayList<>();
        for (ItemClass itemClass : ItemClass.values()) {
            lore.add((itemClass == current ? "<green>▶ " : "<dark_gray>  ") + itemClass.key());
        }
        lore.add("");
        lore.add("<gray>A pool tag: the rarity split picks the");
        lore.add("<gray>class, then weight picks the item in it.");
        lore.add("<gray>Left: next  ·  Right: previous");
        button(slot, mark(Icons.icon(Material.NAME_TAG, "<yellow>Class: <white>"
                + (current == null ? draft.get("class") : current.key()), lore), "class"), click -> {
            ItemClass[] classes = ItemClass.values();
            int index = current == null ? 0 : current.ordinal() + (click.right() ? -1 : 1);
            draft.set("class", classes[Math.floorMod(index, classes.length)].key());
            refresh();
        });
    }

    private void renderWeight(int slot) {
        BigDecimal value = Num.of(draft.get("weight"));
        int weight = value == null ? 100 : value.intValue();
        List<String> lore = new ArrayList<>(List.of(
                "<gray>Share inside the SAME class only -",
                "<gray>it never competes with another class."));
        lore.addAll(Icons.stepperHelp("10", "100"));
        button(slot, mark(Icons.icon(Material.GOLD_NUGGET, "<yellow>Weight: <white>" + weight, lore),
                "weight"), click -> {
            if (click.drop()) {
                askNumber("weight", typed -> {
                    if (typed.stripTrailingZeros().scale() > 0 || typed.signum() <= 0) {
                        return "Weight is a whole number above 0. To switch an item off use Enabled.";
                    }
                    draft.set("weight", typed.intValueExact());
                    return null;
                });
                return;
            }
            int step = click.step(10);
            if (step != 0) {
                draft.set("weight", Math.max(1, weight + step));
                refresh();
            }
        });
    }

    private void renderToggle(int slot, String field, boolean fallback, Material on, Material off,
                              String label, String help) {
        boolean value = draft.get(field) == null ? fallback : Boolean.TRUE.equals(draft.get(field));
        button(slot, mark(Icons.icon(value ? on : off, "<yellow>" + label + ": <white>"
                + (value ? "yes" : "no"), "<gray>" + help, "", "<gray>Click to toggle"), field), click -> {
            // Back to the default is written as "absent", not as the default spelled out.
            draft.set(field, !value == fallback ? null : !value);
            refresh();
        });
    }

    private void renderName(int slot) {
        Object name = draft.get("name");
        List<Component> lore = new ArrayList<>();
        lore.add(Icons.line("<gray>MiniMessage. Empty = the vanilla name."));
        lore.add(name == null ? Icons.line("<dark_gray>none") : Icons.safeLine(String.valueOf(name)));
        lore.add(Component.empty());
        lore.add(Icons.line("<gray>Click: type a name  ·  Right-click: remove"));
        button(slot, mark(Icons.icon(Material.OAK_SIGN, Icons.line("<yellow>Name"), lore), "name"),
                click -> {
                    if (click.right()) {
                        draft.set("name", null);
                        refresh();
                        return;
                    }
                    askText(text -> {
                        draft.set("name", text);
                        return null;
                    }, "<yellow>Type the item's name.",
                            "<gray>MiniMessage works: <white>\\<gold>Crypt Blade</white>");
                });
    }

    private void renderAmount(int slot, @Nullable Material material) {
        int stack = material == null ? 64 : material.getMaxStackSize();
        String shown = AMOUNT.present(draft) ? AMOUNT.describe(draft) : "1";
        button(slot, mark(Icons.icon(Material.BUNDLE, "<yellow>Amount: <white>" + shown,
                "<gray>Stack size a draw hands out, rolled each time.",
                "<gray>At most " + stack + " (a " + (material == null ? "?" : material.getKey().getKey())
                        + " stack).",
                "", AMOUNT.present(draft) ? "<gray>Right-click: back to 1" : ""), "amount"), click -> {
            if (click.right()) {
                AMOUNT.clear(draft);
                refresh();
            }
        });
        BigDecimal[] range = AMOUNT.read(draft);
        BigDecimal min = range == null ? BigDecimal.ONE : range[0];
        BigDecimal max = range == null ? BigDecimal.ONE : range[1];
        List<String> help = Icons.stepperHelp("1", "10");
        button(slot + 1, Icons.icon(Material.PAPER, "<white>min " + Num.show(min), help),
                click -> adjust(click, true, stack));
        button(slot + 2, Icons.icon(Material.PAPER, "<white>max " + Num.show(max), help),
                click -> adjust(click, false, stack));
    }

    private void adjust(Click click, boolean min, int stack) {
        if (click.drop()) {
            askNumber("amount " + (min ? "min" : "max"), typed -> {
                AMOUNT.check(typed);
                if (typed.intValue() > stack) {
                    return "At most " + stack + " - that is a full stack of this item.";
                }
                if (min) {
                    AMOUNT.setMin(draft, typed);
                } else {
                    AMOUNT.setMax(draft, typed);
                }
                return null;
            });
            return;
        }
        BigDecimal step = click.step(AMOUNT.step());
        if (step.signum() == 0) {
            return;
        }
        if (min) {
            AMOUNT.adjustMin(draft, step);
        } else {
            AMOUNT.adjustMax(draft, step);
        }
        // A stepper that walks past a full stack builds a file the loader refuses; stop at the top.
        BigDecimal[] range = AMOUNT.read(draft);
        if (range != null && range[1].intValue() > stack) {
            AMOUNT.setMax(draft, BigDecimal.valueOf(stack));
        }
        refresh();
    }

    /** How often this item actually comes out of each table, with the draft's class and weight. */
    private ItemStack chances() {
        LootRegistry registry = plugin.getLootRegistry();
        ItemClass itemClass = ItemClass.parse(String.valueOf(draft.get("class") == null
                ? "common" : draft.get("class")));
        BigDecimal weightValue = Num.of(draft.get("weight"));
        int weight = weightValue == null ? 100 : weightValue.intValue();
        boolean enabled = !Boolean.FALSE.equals(draft.get("enabled"));
        List<String> lore = new ArrayList<>();
        if (itemClass == null || !enabled) {
            lore.add("<gray>" + (enabled ? "Unknown class." : "Switched off - it drops from nothing."));
            return Icons.icon(Material.WRITTEN_BOOK, "<aqua>Chance per draw", lore);
        }
        int pool = weight;
        int others = 0;
        for (LootItem item : registry.pool(itemClass)) {
            if (!item.id().equals(id)) {
                pool += item.weight();
                others++;
            }
        }
        double within = (double) weight / pool;
        lore.add("<gray>Within <white>" + itemClass.key() + "</white>: <white>"
                + RaritySplit.percent(within) + "</white> of that class's draws");
        lore.add("<dark_gray>(" + others + " other item" + (others == 1 ? "" : "s") + " in the class)");
        lore.add("");
        for (LootTable table : registry.tables()) {
            StringBuilder line = new StringBuilder("<white>" + table.id() + "<gray>  ");
            for (Difficulty difficulty : Difficulty.values()) {
                double share = table.weightsFor(registry.multiplier(difficulty)).share(itemClass);
                line.append(difficulty.key(), 0, 1).append(' ')
                        .append(RaritySplit.percent(share * within)).append("  ");
            }
            lore.add(line.toString().stripTrailing());
        }
        lore.add("");
        lore.add("<dark_gray>Per single draw, at e(asy) / m(edium) / h(ard).");
        return Icons.icon(Material.WRITTEN_BOOK, "<aqua>Chance per draw", lore);
    }

    private @Nullable String setMaterial(String text) {
        Material material = Material.matchMaterial(text.strip());
        if (material == null || !material.isItem()) {
            return "'" + text + "' is not an item id.";
        }
        draft.set("material", material.name());
        return null;
    }

    @Override
    protected @Nullable String validate(YamlConfiguration patched) {
        ConfigurationSection section = patched.getConfigurationSection("items." + id);
        if (section == null) {
            return "items." + id + " is no longer a section";
        }
        try {
            LootItem.parse(section, id);
            return null;
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
    }

    @Override
    protected void reloadRegistry() {
        reloadLoot(plugin);
    }

    /**
     * Loot, then the shop — in that order, the same as {@code /tdungeons reload}: a stock entry
     * may name a loot item by id, and it has to be resolved against the catalogue just written.
     */
    static void reloadLoot(TakashiDungeonsPlugin plugin) {
        plugin.getLootRegistry().load();
        if (plugin.getShopRegistry() != null) {
            plugin.getShopRegistry().load();
        }
    }

    @Override
    protected @Nullable String afterSave() {
        Object enabled = draft.get("enabled");
        return "<gray>loot.yml reloaded - " + id + ": " + LootItemListMenu.status(plugin, id,
                Map.of("enabled", enabled == null ? true : enabled));
    }

    @Override
    protected @Nullable Draft reread() {
        Map<String, Object> fields = Entries.read(plugin, LootRegistry.FILE_NAME, "items").entries().get(id);
        return fields == null ? null : draftOf(id, fields);
    }
}
