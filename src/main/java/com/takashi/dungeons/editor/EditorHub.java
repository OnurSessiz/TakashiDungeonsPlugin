package com.takashi.dungeons.editor;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.gui.Icons;
import com.takashi.dungeons.gui.Menu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * {@code /tdungeons edit} — the three editors.
 *
 * <p>Everything in the {@code editor} package is English and in the source, not in {@code lang/}:
 * the same decision as {@code DungeonsCommand} ({@code isleyis.md} § Dil Katmanı). An editor is an
 * administration surface a player cannot open, and a line quoted from it in a bug report has to be
 * greppable. The shop window, which players do see, stays in {@code lang/}.
 */
public final class EditorHub extends Menu {

    /**
     * Separate from {@code takashidungeons.admin}: the editors write files on disk, and a server
     * should be able to give staff the read-only admin commands without that.
     */
    public static final String PERMISSION = "takashidungeons.edit";

    /** Sub-editors {@code /tdungeons edit <name>} can open directly. */
    public static final List<String> SECTIONS = List.of("mobs", "loot", "dungeon");

    public EditorHub(TakashiDungeonsPlugin plugin, Player viewer) {
        super(plugin, viewer, null, 3, Component.text("TakashiDungeons - Editors",
                NamedTextColor.DARK_GRAY));
    }

    /**
     * Opens the hub, or one editor straight away with the hub behind its Back button.
     *
     * @return {@code null} when something opened, otherwise what to tell the sender
     */
    public static @Nullable String open(TakashiDungeonsPlugin plugin, Player player,
                                        @Nullable String section) {
        EditorHub hub = new EditorHub(plugin, player);
        Menu target = switch (section == null ? "" : section.toLowerCase(Locale.ROOT)) {
            case "" -> hub;
            case "mobs", "mob" -> new MobListMenu(plugin, player, hub);
            case "loot" -> new LootHubMenu(plugin, player, hub);
            case "dungeon" -> new DungeonSettingsMenu(plugin, player, hub);
            default -> null;
        };
        if (target == null) {
            return "Unknown editor '" + section + "' - " + String.join(", ", SECTIONS) + ".";
        }
        target.open();
        return null;
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    protected void render() {
        button(11, Icons.icon(Material.ZOMBIE_HEAD, "<green><bold>Mobs",
                "<gray>Class, weight and stat ranges",
                "<gray>of every entry in <white>mobs.yml</white>.",
                "",
                "<yellow>Click to open"), click -> new MobListMenu(plugin, viewer, this).open());

        button(13, Icons.icon(Material.CHEST, "<gold><bold>Loot",
                "<gray>Items: class, weight, amount.",
                "<gray>Tables: draws and the rarity split,",
                "<gray>with live percentages per difficulty.",
                "",
                "<yellow>Click to open"), click -> new LootHubMenu(plugin, viewer, this).open());

        button(15, Icons.icon(Material.CLOCK, "<aqua><bold>Dungeon",
                "<gray>Duration, timeouts, gateway size",
                "<gray>and the default difficulty.",
                "",
                "<yellow>Click to open"), click -> new DungeonSettingsMenu(plugin, viewer, this).open());

        decor(22, Icons.icon(Material.BOOK, "<gray>How saving works",
                "<gray>Changes are a draft until you press <green>Save</green>.",
                "<gray>Only the values you changed are written;",
                "<gray>comments and layout in the file stay as they are.",
                "<gray>A save that would not load is refused.",
                "",
                "<dark_gray>Edited by hand meanwhile? The save is refused",
                "<dark_gray>for that field rather than overwriting it."));
        backButton(18);
        frameRow(0);
        frameRow(1);
        frameRow(2);
    }
}
