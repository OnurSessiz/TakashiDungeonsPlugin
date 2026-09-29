package com.takashi.dungeons.editor;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.gui.Click;
import com.takashi.dungeons.gui.Icons;
import com.takashi.dungeons.gui.Menu;
import com.takashi.dungeons.gui.PagedMenu;
import com.takashi.dungeons.loot.LootRegistry;
import com.takashi.dungeons.loot.LootTable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Every entry under {@code tables:} in {@code loot.yml}. */
final class LootTableListMenu extends PagedMenu<LootTableListMenu.Row> {

    record Row(String id, Map<String, Object> fields) {
    }

    private @Nullable String problem;

    LootTableListMenu(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent) {
        super(plugin, viewer, parent, Component.text("Loot tables - loot.yml", NamedTextColor.DARK_GRAY));
    }

    @Override
    public String permission() {
        return EditorHub.PERMISSION;
    }

    @Override
    protected List<Row> entries() {
        Entries.Read read = Entries.read(plugin, LootRegistry.FILE_NAME, "tables");
        problem = read.problem();
        List<Row> rows = new ArrayList<>();
        read.entries().forEach((id, fields) -> rows.add(new Row(id, fields)));
        return rows;
    }

    @Override
    protected ItemStack iconOf(Row row) {
        LootRegistry registry = plugin.getLootRegistry();
        LootTable table = registry.table(row.id());
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Draws <white>" + Num.describe(row.fields().getOrDefault("rolls", 1)));
        lore.add("<gray>Split: <white>" + (row.fields().get("rarity") instanceof Map<?, ?>
                ? "its own" : "the base split"));
        if (table == null) {
            lore.add("<red>Not loaded - see /tdungeons loot tables");
        } else {
            lore.add("");
            lore.addAll(RaritySplit.preview(table.rarity(), registry));
        }
        lore.add("");
        lore.add("<yellow>Click to edit");
        return Icons.icon(Material.CHEST, "<gold>" + row.id(), lore);
    }

    @Override
    protected void onPick(Row row, Click click) {
        LootTableEditMenu.table(plugin, viewer, this, row.id(), row.fields()).open();
    }

    @Override
    protected void renderFooter() {
        if (problem != null) {
            decor(52, Icons.icon(Material.BARRIER, "<red>loot.yml could not be read", "<gray>" + problem));
        }
    }
}
