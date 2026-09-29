package com.takashi.dungeons.editor;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.gui.Icons;
import com.takashi.dungeons.gui.Menu;
import com.takashi.dungeons.loot.RarityWeights;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** {@code loot.yml}: the items, the tables, and the base split every table falls back to. */
final class LootHubMenu extends Menu {

    LootHubMenu(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent) {
        super(plugin, viewer, parent, 3, Component.text("Loot - loot.yml", NamedTextColor.DARK_GRAY));
    }

    @Override
    public String permission() {
        return EditorHub.PERMISSION;
    }

    @Override
    protected void render() {
        var registry = plugin.getLootRegistry();
        button(11, Icons.icon(Material.DIAMOND, "<aqua><bold>Items",
                "<gray>" + registry.items().size() + " loaded"
                        + (registry.disabled().isEmpty() ? "" : ", <red>" + registry.disabled().size()
                        + " disabled"),
                "<gray>Class, weight inside the class, amount.",
                "", "<yellow>Click to open"), click -> new LootItemListMenu(plugin, viewer, this).open());

        button(13, Icons.icon(Material.CHEST, "<gold><bold>Tables",
                "<gray>" + registry.tables().size() + " tables",
                "<gray>How many draws, and how they lean.",
                "", "<yellow>Click to open"), click -> new LootTableListMenu(plugin, viewer, this).open());

        RarityWeights base = registry.baseWeights();
        List<String> lore = new ArrayList<>(List.of(
                "<gray>The split every table uses unless it",
                "<gray>writes its own <white>rarity:</white> block.", ""));
        lore.addAll(RaritySplit.preview(base, registry));
        lore.add("");
        lore.add("<yellow>Click to edit");
        button(15, Icons.icon(Material.EXPERIENCE_BOTTLE, "<light_purple><bold>Base rarity split", lore),
                click -> LootTableEditMenu.base(plugin, viewer, this).open());

        backButton(18);
        frameRow(0);
        frameRow(1);
        frameRow(2);
    }
}
