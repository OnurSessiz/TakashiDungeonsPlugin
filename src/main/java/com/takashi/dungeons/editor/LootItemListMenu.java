package com.takashi.dungeons.editor;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.gui.Click;
import com.takashi.dungeons.gui.Icons;
import com.takashi.dungeons.gui.Menu;
import com.takashi.dungeons.gui.PagedMenu;
import com.takashi.dungeons.loot.ItemClass;
import com.takashi.dungeons.loot.LootItem;
import com.takashi.dungeons.loot.LootRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Every entry under {@code items:} in {@code loot.yml}, filterable by class. */
final class LootItemListMenu extends PagedMenu<LootItemListMenu.Row> {

    record Row(String id, Map<String, Object> fields) {
    }

    private @Nullable ItemClass filter;
    private @Nullable String problem;

    LootItemListMenu(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent) {
        super(plugin, viewer, parent, Component.text("Loot items - loot.yml", NamedTextColor.DARK_GRAY));
    }

    @Override
    public String permission() {
        return EditorHub.PERMISSION;
    }

    @Override
    protected List<Row> entries() {
        Entries.Read read = Entries.read(plugin, LootRegistry.FILE_NAME, "items");
        problem = read.problem();
        List<Row> rows = new ArrayList<>();
        read.entries().forEach((id, fields) -> {
            ItemClass itemClass = ItemClass.parse(String.valueOf(fields.getOrDefault("class", "common")));
            if (filter == null || filter == itemClass) {
                rows.add(new Row(id, fields));
            }
        });
        return rows;
    }

    @Override
    protected ItemStack iconOf(Row row) {
        ItemStack stack = display(plugin, row.id(), row.fields());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Icons.line("<gray>Id <white>" + row.id()));
            lore.add(Icons.line("<gray>Class <white>" + row.fields().getOrDefault("class", "common")
                    + "</white>  ·  weight <white>" + Num.describe(row.fields().getOrDefault("weight", 100))
                    + "</white>  ·  amount <white>" + Num.describe(row.fields().getOrDefault("amount", 1))));
            lore.add(Icons.line(status(plugin, row.id(), row.fields())));
            lore.add(Icons.line("<yellow>Click to edit"));
            List<Component> existing = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            existing.addAll(lore);
            meta.lore(existing);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    @Override
    protected void onPick(Row row, Click click) {
        new LootItemEditMenu(plugin, viewer, this, row.id(), row.fields()).open();
    }

    @Override
    protected void renderFooter() {
        ItemClass[] classes = ItemClass.values();
        button(46, Icons.icon(Material.HOPPER, "<yellow>Class: <white>"
                + (filter == null ? "all" : filter.key()), "<gray>Left: next  ·  Right: previous"), click -> {
            int index = filter == null ? -1 : filter.ordinal();
            index += click.right() ? -1 : 1;
            if (index < -1) {
                index = classes.length - 1;
            } else if (index >= classes.length) {
                index = -1;
            }
            filter = index < 0 ? null : classes[index];
            firstPage();
            refresh();
        });
        if (problem != null) {
            decor(52, Icons.icon(Material.BARRIER, "<red>loot.yml could not be read", "<gray>" + problem));
        }
    }

    /**
     * The item as a chest would hand it out when it is loaded — name, lore, enchantments and all —
     * or a bare material icon when it is not.
     */
    static ItemStack display(TakashiDungeonsPlugin plugin, String id, Map<String, Object> fields) {
        LootItem loaded = plugin.getLootRegistry().item(id);
        if (loaded != null) {
            ItemStack stack = plugin.getLootService().build(loaded, new Random(0));
            stack.setAmount(1);
            return stack;
        }
        Material material = Material.matchMaterial(String.valueOf(fields.getOrDefault("material", "")));
        return Icons.icon(material == null ? Material.BARRIER : material, "<white>" + id, List.of());
    }

    static String status(TakashiDungeonsPlugin plugin, String id, Map<String, Object> fields) {
        LootRegistry registry = plugin.getLootRegistry();
        if (Boolean.FALSE.equals(fields.get("enabled"))) {
            return "<dark_gray>Switched off (enabled: false)";
        }
        if (registry.item(id) != null) {
            return "<green>Loaded";
        }
        for (LootRegistry.Disabled disabled : registry.disabled()) {
            if (disabled.id().equals(id)) {
                return "<red>Disabled: " + disabled.reason().replace("<", "\\<");
            }
        }
        return "<gold>Not loaded yet - it was added by hand; /tdungeons reload";
    }
}
