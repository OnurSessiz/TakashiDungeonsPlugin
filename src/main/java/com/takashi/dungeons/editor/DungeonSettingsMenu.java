package com.takashi.dungeons.editor;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.generation.DungeonSize;
import com.takashi.dungeons.gui.Click;
import com.takashi.dungeons.gui.Icons;
import com.takashi.dungeons.gui.Menu;
import com.takashi.dungeons.mob.Difficulty;
import com.takashi.dungeons.mob.MobRegistry;
import com.takashi.dungeons.yaml.DataFiles;
import com.takashi.dungeons.yaml.YamlPatch;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
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
 * The settings that shape a run: how long it lasts, when an empty one closes, how long the party
 * gets after the boss, what size a gateway opens and at which difficulty.
 *
 * <p>Two files, two drafts: the timers and the gateway live in {@code config.yml}, the default
 * difficulty in {@code mobs.yml}. They are saved one after the other through the same
 * {@link Saver} path, config first, and a refusal on the first stops the second.
 *
 * <h2>Open dungeons keep their clock</h2>
 * A dungeon's duration is fixed when it opens ({@code isleyis.md} § FAZ 2B), so a save here
 * changes the next dungeon, not the one a party is standing in. The window says so rather than
 * letting an operator wonder why the boss bar did not move.
 */
final class DungeonSettingsMenu extends Menu {

    private static final String CONFIG = "config.yml";

    private static final List<String> CONFIG_FIELDS = List.of("instance.duration-seconds",
            "instance.empty-timeout-seconds", "instance.clear-grace-seconds", "portal.size");

    private Draft config;
    private Draft mobs;

    DungeonSettingsMenu(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent) {
        super(plugin, viewer, parent, 4, Component.text("Dungeon settings", NamedTextColor.DARK_GRAY));
        reread();
    }

    @Override
    public String permission() {
        return EditorHub.PERMISSION;
    }

    private void reread() {
        config = draftOf(CONFIG, CONFIG_FIELDS);
        mobs = draftOf(MobRegistry.FILE_NAME, List.of("default-difficulty"));
    }

    private Draft draftOf(String file, List<String> fields) {
        Map<String, Object> values = new HashMap<>();
        try {
            YamlPatch patch = DataFiles.read(plugin, file);
            for (String field : fields) {
                values.put(field, patch.get(field));
            }
        } catch (IOException | RuntimeException error) {
            viewer.sendMessage(Component.text(file + " could not be read: " + error.getMessage(),
                    NamedTextColor.RED));
        }
        return new Draft(List.of(), values);
    }

    @Override
    protected void render() {
        seconds(10, "instance.duration-seconds", Material.CLOCK, "Duration", 60, 60,
                "How long a dungeon stays up. When it runs out",
                "everyone is sent back and the dungeon is deleted.");
        seconds(11, "instance.empty-timeout-seconds", Material.COBWEB, "Empty timeout", 30, 0,
                "Seconds after the last player leaves before it",
                "closes. 0 = never; it runs its full duration.");
        seconds(12, "instance.clear-grace-seconds", Material.TOTEM_OF_UNDYING, "After the boss", 10, 0,
                "Time left once the boss dies - a CEILING, never",
                "a refill. 0 = the clock is not shortened.");

        DungeonSize size = DungeonSize.parse(String.valueOf(config.get("portal.size")));
        button(14, mark(config, Icons.icon(Material.AMETHYST_SHARD, "<yellow>Gateway size: <white>"
                        + (size == null ? config.get("portal.size") : size.key()),
                cycleLore(DungeonSize.values(), size, "What a right-click on a gateway opens.")),
                "portal.size"), click -> {
            DungeonSize[] sizes = DungeonSize.values();
            int index = size == null ? 0 : size.ordinal() + (click.right() ? -1 : 1);
            config.set("portal.size", sizes[Math.floorMod(index, sizes.length)].key());
            refresh();
        });

        Difficulty difficulty = Difficulty.parse(String.valueOf(mobs.get("default-difficulty")));
        button(15, mark(mobs, Icons.icon(Material.IRON_SWORD, "<yellow>Default difficulty: <white>"
                        + (difficulty == null ? mobs.get("default-difficulty") : difficulty.key()),
                cycleLore(Difficulty.values(), difficulty, "Used by every dungeon not told otherwise.",
                        "Written to mobs.yml, not config.yml.")), "default-difficulty"), click -> {
            Difficulty[] levels = Difficulty.values();
            int index = difficulty == null ? 0 : difficulty.ordinal() + (click.right() ? -1 : 1);
            mobs.set("default-difficulty", levels[Math.floorMod(index, levels.length)].key());
            refresh();
        });

        decor(16, Icons.icon(Material.BOOK, "<gray>Open dungeons are not changed",
                "<gray>A dungeon's clock is fixed when it opens:",
                "<gray>a save here shapes the NEXT one."));

        boolean dirty = config.dirty() || mobs.dirty();
        String summary = summary();
        button(27, Icons.icon(Material.ARROW, "<gray>Back", dirty
                ? List.of("<gold>Unsaved: <white>" + summary.replace("<", "\\<"),
                "<gray>Shift-click to leave without saving.") : List.of()), click -> {
            if (dirty && !click.shift()) {
                viewer.sendMessage(Component.text("Unsaved changes (" + summary + "). Save, or "
                        + "shift-click Back to leave without saving.", NamedTextColor.GOLD));
                return;
            }
            back();
        });
        button(29, Icons.icon(Material.COMPASS, "<aqua>Reload from disk",
                "<gray>Reads config.yml and mobs.yml again.",
                dirty ? "<gold>Shift-click: your unsaved changes are dropped." : ""), click -> {
            if (dirty && !click.shift()) {
                viewer.sendMessage(Component.text("You have unsaved changes - shift-click to drop "
                        + "them and reload.", NamedTextColor.GOLD));
                return;
            }
            reread();
            refresh();
        });
        if (dirty) {
            button(30, Icons.icon(Material.RED_DYE, "<red>Discard changes", "<gray>" + summary), click -> {
                config.discard();
                mobs.discard();
                refresh();
            });
            button(31, Icons.icon(Material.LIME_DYE, "<green><bold>Save", "<gray>" + summary,
                    "", "<gray>Writes only these values and reloads."), click -> save());
        } else {
            decor(31, Icons.icon(Material.GRAY_DYE, "<gray>Save", "<dark_gray>Nothing changed yet."));
        }
        for (int row = 0; row < 4; row++) {
            frameRow(row);
        }
    }

    private void seconds(int slot, String field, Material material, String label, int step, int floor,
                         String... help) {
        BigDecimal value = Num.of(config.get(field));
        long current = value == null ? 0 : value.longValue();
        List<String> lore = new ArrayList<>();
        for (String line : help) {
            lore.add("<gray>" + line);
        }
        lore.addAll(Icons.stepperHelp(step + "s", (step * 10) + "s"));
        button(slot, mark(config, Icons.icon(material, "<yellow>" + label + ": <white>" + clock(current),
                lore), field), click -> adjust(click, field, label, step, floor, current));
    }

    private void adjust(Click click, String field, String label, int step, int floor, long current) {
        if (click.drop()) {
            plugin.getChatPrompt().ask(viewer, this, text -> {
                BigDecimal typed = Num.parse(text);
                if (typed.stripTrailingZeros().scale() > 0 || typed.longValue() < floor) {
                    return label + " is a whole number of seconds, at least " + floor + ".";
                }
                config.set(field, typed.longValue());
                return null;
            }, "<yellow>Type <white>" + label.toLowerCase(java.util.Locale.ROOT) + "</white> in seconds.");
            return;
        }
        int delta = click.step(step);
        if (delta != 0) {
            config.set(field, Math.max(floor, current + delta));
            refresh();
        }
    }

    private void save() {
        if (config.dirty() && !Saver.save(plugin, viewer, CONFIG, config, this::validateConfig,
                plugin::reloadConfig)) {
            refresh();
            return;
        }
        if (mobs.dirty()) {
            Saver.save(plugin, viewer, MobRegistry.FILE_NAME, mobs, patched ->
                    Difficulty.parse(patched.getString("default-difficulty")) == null
                            ? "default-difficulty must be easy, medium or hard" : null,
                    () -> plugin.getMobRegistry().load());
        }
        refresh();
    }

    private @Nullable String validateConfig(YamlConfiguration patched) {
        for (String field : List.of("instance.duration-seconds", "instance.empty-timeout-seconds",
                "instance.clear-grace-seconds")) {
            if (!patched.isInt(field) && !patched.isLong(field)) {
                return field + " must be a whole number";
            }
            if (patched.getLong(field) < 0) {
                return field + " cannot be negative";
            }
        }
        if (patched.getLong("instance.duration-seconds") < 60) {
            return "instance.duration-seconds must be at least 60";
        }
        if (DungeonSize.parse(patched.getString("portal.size")) == null) {
            return "portal.size must be small, medium or large";
        }
        return null;
    }

    private String summary() {
        List<String> parts = new ArrayList<>();
        if (config.dirty()) {
            parts.add(config.summary());
        }
        if (mobs.dirty()) {
            parts.add(mobs.summary());
        }
        return String.join(", ", parts);
    }

    private static <E extends Enum<E>> List<String> cycleLore(E[] values, @Nullable E current, String... help) {
        List<String> lore = new ArrayList<>();
        for (E value : values) {
            lore.add((value == current ? "<green>▶ " : "<dark_gray>  ") + value);
        }
        lore.add("");
        for (String line : help) {
            lore.add("<gray>" + line);
        }
        lore.add("<gray>Left: next  ·  Right: previous");
        return lore;
    }

    /** 1200 → {@code 20:00}; 0 → {@code 0 (off)}. */
    private static String clock(long seconds) {
        if (seconds <= 0) {
            return "0 (off)";
        }
        return seconds / 60 + ":" + String.format("%02d", seconds % 60) + " <gray>(" + seconds + "s)";
    }

    /** The glint-and-"was" treatment of {@link DraftMenu#mark}, for either draft. */
    private static org.bukkit.inventory.ItemStack mark(Draft draft, org.bukkit.inventory.ItemStack stack,
                                                       String field) {
        if (!draft.isChanged(field)) {
            return stack;
        }
        var meta = stack.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(Icons.line("<gold>● changed - was " + Num.describe(draft.original(field))));
            meta.lore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
