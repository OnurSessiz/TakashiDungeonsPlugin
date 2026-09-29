package com.takashi.dungeons.editor;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.gui.Click;
import com.takashi.dungeons.gui.Icons;
import com.takashi.dungeons.gui.Menu;
import com.takashi.dungeons.gui.PagedMenu;
import com.takashi.dungeons.mob.MobClass;
import com.takashi.dungeons.mob.MobRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Every entry in {@code mobs.yml}, filterable by class. */
final class MobListMenu extends PagedMenu<MobListMenu.Row> {

    record Row(String id, Map<String, Object> fields) {
    }

    /** {@code null} = every class. */
    private @Nullable MobClass filter;
    private @Nullable String problem;

    MobListMenu(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent) {
        super(plugin, viewer, parent, Component.text("Mobs - mobs.yml", NamedTextColor.DARK_GRAY));
    }

    @Override
    public String permission() {
        return EditorHub.PERMISSION;
    }

    @Override
    protected List<Row> entries() {
        Entries.Read read = Entries.read(plugin, MobRegistry.FILE_NAME, "mobs");
        problem = read.problem();
        List<Row> rows = new ArrayList<>();
        read.entries().forEach((id, fields) -> {
            MobClass mobClass = MobClass.parse(String.valueOf(fields.getOrDefault("class", "normal")));
            if (filter == null || filter == mobClass) {
                rows.add(new Row(id, fields));
            }
        });
        return rows;
    }

    @Override
    protected ItemStack iconOf(Row row) {
        Map<String, Object> f = row.fields();
        String address = String.valueOf(f.getOrDefault("mob", "?"));
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + address);
        lore.add("<gray>Class <white>" + f.getOrDefault("class", "normal") + "</white>  ·  weight <white>"
                + Num.describe(f.getOrDefault("weight", 100)));
        lore.add("<gray>Health <white>" + stat(f.get("health")) + "</white>  ·  damage <white>"
                + stat(f.get("damage")) + "</white>  ·  speed <white>" + stat(f.get("speed")));
        lore.add(status(plugin, row.id(), f));
        lore.add("");
        lore.add("<yellow>Click to edit");
        Object name = f.get("name");
        return Icons.icon(MobEditMenu.iconFor(address), name == null
                ? Icons.line("<white>" + row.id()) : Icons.safeLine(String.valueOf(name)),
                lore.stream().map(Icons::line).toList());
    }

    @Override
    protected void onPick(Row row, Click click) {
        new MobEditMenu(plugin, viewer, this, row.id(), row.fields()).open();
    }

    @Override
    protected void renderFooter() {
        MobClass[] classes = MobClass.values();
        String shown = filter == null ? "all" : filter.key();
        button(46, Icons.icon(Material.HOPPER, "<yellow>Class: <white>" + shown,
                "<gray>Left: next  ·  Right: previous"), click -> {
            int index = filter == null ? -1 : filter.ordinal();
            index += click.right() ? -1 : 1;
            // -1 is "all", so the cycle is all → weak → … → boss → all.
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
            decor(52, Icons.icon(Material.BARRIER, "<red>mobs.yml could not be read", "<gray>" + problem));
        }
    }

    private static String stat(@Nullable Object raw) {
        return raw == null ? "natural" : Num.describe(raw);
    }

    /** One coloured line: what the registry made of this entry. */
    static String status(TakashiDungeonsPlugin plugin, String id, Map<String, Object> fields) {
        MobRegistry registry = plugin.getMobRegistry();
        if (Boolean.FALSE.equals(fields.get("enabled"))) {
            return "<dark_gray>Switched off (enabled: false)";
        }
        if (registry.definition(id) != null) {
            return "<green>Loaded";
        }
        for (MobRegistry.Disabled disabled : registry.disabled()) {
            if (disabled.id().equals(id)) {
                return "<red>Disabled: " + disabled.reason().replace("<", "\\<");
            }
        }
        return "<gold>Not loaded yet - it was added by hand; /tdungeons reload";
    }
}
