package com.takashi.dungeons.editor;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.generation.RoomType;
import com.takashi.dungeons.gui.Click;
import com.takashi.dungeons.gui.Icons;
import com.takashi.dungeons.gui.Menu;
import com.takashi.dungeons.loot.CountRange;
import com.takashi.dungeons.loot.ItemClass;
import com.takashi.dungeons.loot.LootRegistry;
import com.takashi.dungeons.loot.LootTable;
import com.takashi.dungeons.loot.RarityWeights;
import com.takashi.dungeons.mob.MobClass;
import com.takashi.dungeons.yaml.DataFiles;
import com.takashi.dungeons.yaml.YamlPatch;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A loot table (draws + rarity split), or the base split at the top of {@code loot.yml}.
 *
 * <h2>A table either has its own split or uses the base one</h2>
 * That is a real state in the file — a {@code rarity:} block, or none — and the editor keeps it one.
 * "Use the base split" removes the block rather than copying today's base numbers into it: a copy
 * would stop following the base the day somebody edits it, which is the opposite of what the operator
 * asked for. Touching a weight on a table that has no block gives it one, starting from the base.
 */
final class LootTableEditMenu extends DraftMenu {

    /** Rarity weights move in fives: a 1000-based split has no use for single units. */
    private static final int STEP = 5;
    private static final RangeField ROLLS = RangeField.whole("rolls", 1, 1, 1);
    private static final int[] CLASS_SLOTS = {19, 20, 21, 22, 23, 24};

    /** {@code null} for the base split. */
    private final @Nullable String id;
    private final String prefix;

    private LootTableEditMenu(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent,
                              @Nullable String id, Draft draft) {
        super(plugin, viewer, parent, Component.text(id == null ? "Loot - base rarity split"
                : "Loot table - " + id, NamedTextColor.DARK_GRAY), LootRegistry.FILE_NAME, draft);
        this.id = id;
        this.prefix = id == null ? "" : "rarity.";
    }

    static LootTableEditMenu table(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent,
                                   String id, Map<String, Object> fields) {
        return new LootTableEditMenu(plugin, viewer, parent, id, tableDraft(id, fields));
    }

    static LootTableEditMenu base(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent) {
        return new LootTableEditMenu(plugin, viewer, parent, null, baseDraft(plugin));
    }

    private static Draft tableDraft(String id, Map<String, Object> fields) {
        Map<String, Object> values = new HashMap<>();
        values.put("rolls", fields.get("rolls"));
        Object rarity = fields.get("rarity");
        values.put("rarity", rarity);
        for (ItemClass itemClass : ItemClass.values()) {
            values.put("rarity." + itemClass.key(), rarity instanceof Map<?, ?> map
                    ? map.get(itemClass.key()) : null);
        }
        return new Draft(List.of("tables", id), values);
    }

    private static Draft baseDraft(TakashiDungeonsPlugin plugin) {
        Object rarity;
        try {
            rarity = DataFiles.read(plugin, LootRegistry.FILE_NAME).get("rarity");
        } catch (IOException | RuntimeException error) {
            rarity = null;
        }
        Map<String, Object> values = new HashMap<>();
        for (ItemClass itemClass : ItemClass.values()) {
            values.put(itemClass.key(), rarity instanceof Map<?, ?> map ? map.get(itemClass.key()) : null);
        }
        return new Draft(List.of("rarity"), values);
    }

    // ------------------------------------------------------------------ state

    private boolean usesBase() {
        if (id == null) {
            return false;
        }
        if (draft.isChanged("rarity") && draft.get("rarity") == null) {
            return true;
        }
        if (draft.get("rarity") instanceof Map<?, ?>) {
            return false;
        }
        for (ItemClass itemClass : ItemClass.values()) {
            if (draft.isChanged(prefix + itemClass.key())) {
                return false;
            }
        }
        return true;
    }

    private RarityWeights weights() {
        if (usesBase()) {
            return plugin.getLootRegistry().baseWeights();
        }
        return RaritySplit.of(RaritySplit.read(draft, prefix));
    }

    /** Gives a table its own block, starting from what it had (or the base split). */
    private void override() {
        draft.set("rarity", draft.original("rarity"));
        boolean hadBlock = draft.original("rarity") instanceof Map<?, ?>;
        RarityWeights base = plugin.getLootRegistry().baseWeights();
        for (ItemClass itemClass : ItemClass.values()) {
            String field = prefix + itemClass.key();
            draft.set(field, hadBlock ? draft.original(field) : base.weight(itemClass));
        }
    }

    private void useBase() {
        for (ItemClass itemClass : ItemClass.values()) {
            String field = prefix + itemClass.key();
            draft.set(field, draft.original(field));
        }
        draft.set("rarity", null);
    }

    // ------------------------------------------------------------------ body

    @Override
    protected void renderBody() {
        LootRegistry registry = plugin.getLootRegistry();
        decor(4, Icons.icon(id == null ? Material.EXPERIENCE_BOTTLE : Material.CHEST,
                id == null ? "<light_purple><bold>Base rarity split" : "<gold><bold>" + id,
                id == null ? usedByBase(registry) : usedBy(registry, id)));

        if (id != null) {
            renderRolls(10);
            boolean base = usesBase();
            button(13, mark(Icons.icon(base ? Material.BOOK : Material.WRITABLE_BOOK,
                    base ? "<yellow>Split: <white>the base split" : "<yellow>Split: <white>its own",
                    base ? "<gray>No rarity: block - follows the base split,"
                            : "<gray>Has a rarity: block of its own.",
                    base ? "<gray>including future edits to it."
                            : "<gray>Base-split edits no longer reach it.",
                    "",
                    base ? "<gray>Click: give it its own (starts from the base)"
                            : "<gray>Click: remove the block, follow the base"), "rarity"), click -> {
                if (usesBase()) {
                    override();
                } else {
                    useBase();
                }
                refresh();
            });
        }

        RarityWeights weights = weights();
        ItemClass[] classes = ItemClass.values();
        for (int i = 0; i < classes.length; i++) {
            renderClass(CLASS_SLOTS[i], classes[i], weights);
        }

        List<String> lore = new ArrayList<>(RaritySplit.preview(weights, registry));
        decor(31, Icons.icon(Material.WRITTEN_BOOK, "<aqua>Per draw, by difficulty", lore));
        for (int row = 0; row < 5; row++) {
            frameRow(row);
        }
    }

    private void renderRolls(int slot) {
        String shown = ROLLS.present(draft) ? ROLLS.describe(draft) : "1";
        decor(slot, mark(Icons.icon(Material.HOPPER, "<yellow>Draws: <white>" + shown,
                "<gray>How many items one chest (or one kill) rolls."), "rolls"));
        BigDecimal[] range = ROLLS.read(draft);
        BigDecimal min = range == null ? BigDecimal.ONE : range[0];
        BigDecimal max = range == null ? BigDecimal.ONE : range[1];
        List<String> help = Icons.stepperHelp("1", "10");
        button(slot + 1, Icons.icon(Material.PAPER, "<white>min " + Num.show(min), help),
                click -> adjustRolls(click, true));
        button(slot + 2, Icons.icon(Material.PAPER, "<white>max " + Num.show(max), help),
                click -> adjustRolls(click, false));
    }

    private void adjustRolls(Click click, boolean min) {
        if (click.drop()) {
            askNumber("draws " + (min ? "min" : "max"), typed -> {
                ROLLS.check(typed);
                if (min) {
                    ROLLS.setMin(draft, typed);
                } else {
                    ROLLS.setMax(draft, typed);
                }
                return null;
            });
            return;
        }
        BigDecimal step = click.step(ROLLS.step());
        if (step.signum() != 0) {
            if (min) {
                ROLLS.adjustMin(draft, step);
            } else {
                ROLLS.adjustMax(draft, step);
            }
            refresh();
        }
    }

    private void renderClass(int slot, ItemClass itemClass, RarityWeights weights) {
        int weight = weights.weight(itemClass);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Weight <white>" + weight + "</white>  ·  " + RaritySplit.percent(weights.share(itemClass))
                + " of a draw (before difficulty)");
        int pool = plugin.getLootRegistry().pool(itemClass).size();
        if (weight > 0 && pool == 0) {
            lore.add("<red>No item has this class - this share produces nothing.");
        }
        if (usesBase()) {
            lore.add("<dark_gray>From the base split - a click gives the table its own.");
        }
        lore.addAll(Icons.stepperHelp(String.valueOf(STEP), String.valueOf(STEP * 10)));
        button(slot, mark(Icons.icon(classIcon(itemClass), "<yellow>" + itemClass.key() + ": <white>"
                + weight, lore), prefix + itemClass.key()), click -> {
            String field = prefix + itemClass.key();
            if (click.drop()) {
                askNumber(itemClass.key() + " weight", typed -> {
                    if (typed.stripTrailingZeros().scale() > 0 || typed.signum() < 0) {
                        return "A rarity weight is a whole number, 0 or more.";
                    }
                    if (usesBase()) {
                        override();
                    }
                    draft.set(field, typed.intValueExact());
                    return null;
                });
                return;
            }
            int step = click.step(STEP);
            if (step == 0) {
                return;
            }
            if (usesBase()) {
                override();
            }
            BigDecimal current = Num.of(draft.get(field));
            draft.set(field, Math.max(0, (current == null ? 0 : current.intValue()) + step));
            refresh();
        });
    }

    private static Material classIcon(ItemClass itemClass) {
        return switch (itemClass) {
            case COMMON -> Material.WHITE_WOOL;
            case UNCOMMON -> Material.LIME_WOOL;
            case RARE -> Material.LIGHT_BLUE_WOOL;
            case ULTRA_RARE -> Material.PURPLE_WOOL;
            case LEGENDARY -> Material.ORANGE_WOOL;
            case MYTHIC -> Material.RED_WOOL;
        };
    }

    /** Who draws from this table — the thing to know before changing it. */
    private static List<String> usedBy(LootRegistry registry, String id) {
        List<String> users = new ArrayList<>();
        for (RoomType type : RoomType.values()) {
            if (id.equals(registry.chestRules().tableFor(type))) {
                users.add(type.yamlValue() + " room chests");
            }
        }
        for (MobClass mobClass : MobClass.values()) {
            if (id.equals(registry.dropRules().tableFor(mobClass))) {
                users.add(mobClass.key() + " kills");
            }
        }
        if (id.equals(registry.dropRules().bossTable())) {
            users.add("the boss reward chest");
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Used by: <white>" + (users.isEmpty() ? "nothing at the moment" : String.join(", ", users)));
        if (registry.table(id) == null) {
            lore.add("<red>Not loaded - see /tdungeons loot tables");
        }
        lore.add("<dark_gray>Comments in the file describe the shipped numbers;");
        lore.add("<dark_gray>'# 62.5%' style comments are kept in step on save.");
        return lore;
    }

    private static List<String> usedByBase(LootRegistry registry) {
        List<String> following = new ArrayList<>();
        for (LootTable table : registry.tables()) {
            if (table.rarity().equals(registry.baseWeights())) {
                following.add(table.id());
            }
        }
        return List.of("<gray>Every table without a rarity: block uses this.",
                "<gray>Right now: <white>" + (following.isEmpty() ? "none" : String.join(", ", following)),
                "<dark_gray>'# 62.5%' comments beside the weights are kept in step.");
    }

    // ------------------------------------------------------------------ saving

    @Override
    protected void touchUp(YamlPatch patch) {
        RaritySplit.refreshComments(patch, id == null ? "rarity" : "tables." + id + ".rarity");
    }

    @Override
    protected @Nullable String validate(YamlConfiguration patched) {
        try {
            if (id == null) {
                ConfigurationSection section = patched.getConfigurationSection("rarity");
                RarityWeights weights = RarityWeights.parse(section == null ? null : section.getValues(false),
                        "rarity", RarityWeights.DEFAULT);
                return weights.isEmpty() ? "every weight is 0 - the base split needs at least one" : null;
            }
            ConfigurationSection section = patched.getConfigurationSection("tables." + id);
            if (section == null) {
                return "tables." + id + " is no longer a section";
            }
            ConfigurationSection rarity = section.getConfigurationSection("rarity");
            new LootTable(id,
                    CountRange.parse(section.get("rolls"), "tables." + id + " -> rolls", CountRange.fixed(1)),
                    RarityWeights.parse(rarity == null ? null : rarity.getValues(false),
                            "tables." + id + " -> rarity", plugin.getLootRegistry().baseWeights()));
            return null;
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
    }

    @Override
    protected void reloadRegistry() {
        LootItemEditMenu.reloadLoot(plugin);
    }

    @Override
    protected @Nullable String afterSave() {
        String capped = RaritySplit.cappedAt(weights(), plugin.getLootRegistry());
        return "<gray>loot.yml reloaded." + (capped == null ? "" : " " + capped);
    }

    @Override
    protected @Nullable Draft reread() {
        if (id == null) {
            return baseDraft(plugin);
        }
        Map<String, Object> fields = Entries.read(plugin, LootRegistry.FILE_NAME, "tables").entries().get(id);
        return fields == null ? null : tableDraft(id, fields);
    }
}
