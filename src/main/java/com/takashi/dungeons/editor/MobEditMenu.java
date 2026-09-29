package com.takashi.dungeons.editor;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.gui.Click;
import com.takashi.dungeons.gui.Icons;
import com.takashi.dungeons.gui.Menu;
import com.takashi.dungeons.mob.Difficulty;
import com.takashi.dungeons.mob.DifficultyScaling;
import com.takashi.dungeons.mob.MobClass;
import com.takashi.dungeons.mob.MobDefinition;
import com.takashi.dungeons.mob.MobProvider;
import com.takashi.dungeons.mob.MobRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One {@code mobs.yml} entry.
 *
 * <p>Layout: row 0 the mob itself · row 1 class, weight and the flags · rows 2–4 health, damage and
 * speed, each as label + min + max · the book on the right shows what the numbers become in game at
 * each difficulty — the operator's real question is never "what did I type" but "how hard does it hit
 * on hard".
 */
final class MobEditMenu extends DraftMenu {

    private static final List<String> FIELDS = List.of("mob", "class", "weight", "enabled", "baby",
            "statOverride", "dungeonDrops", "name", "health", "damage", "speed");

    /** Steps and floors. Health cannot reach 0 — a mob with no health is a bug, not a setting. */
    private static final RangeField HEALTH = RangeField.decimal("health", "1", "1", "20");
    private static final RangeField DAMAGE = RangeField.decimal("damage", "0.5", "0", "3");
    private static final RangeField SPEED = RangeField.decimal("speed", "0.01", "0", "0.23");

    private final String id;

    MobEditMenu(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent, String id,
                Map<String, Object> fields) {
        super(plugin, viewer, parent, Component.text("Mob - " + id, NamedTextColor.DARK_GRAY),
                MobRegistry.FILE_NAME, draftOf(id, fields));
        this.id = id;
    }

    private static Draft draftOf(String id, Map<String, Object> fields) {
        Map<String, Object> values = new java.util.HashMap<>();
        for (String field : FIELDS) {
            values.put(field, fields.get(field));
        }
        return new Draft(List.of("mobs", id), values);
    }

    // ------------------------------------------------------------------ body

    @Override
    protected void renderBody() {
        String address = String.valueOf(draft.get("mob"));
        MobProvider provider = providerOf(address);

        button(4, mark(Icons.icon(iconFor(address), nameLine(), List.of(
                Icons.line("<gray>Id <white>" + id),
                Icons.line("<gray>Mob <white>" + escape(address)),
                Icons.line(MobListMenu.status(plugin, id, Map.of("enabled",
                        draft.original("enabled") == null ? true : draft.original("enabled")))),
                Component.empty(),
                Icons.line("<gray>Q: change the mob (<white>provider:key</white>)"))), "mob"),
                click -> {
                    if (click.drop()) {
                        askText(this::setAddress,
                                "<yellow>Type the mob as <white>provider:key</white>.",
                                "<gray>For example <white>vanilla:ZOMBIE</white> or "
                                        + "<white>mythicmobs:SkeletalKnight</white>.");
                    }
                });

        renderClass(10);
        renderWeight(11);
        renderEnabled(12);
        renderBaby(13);
        renderTristate(14, "statOverride", Material.ANVIL, provider == null ? null
                : provider.defaultStatOverride(), "true: the ranges below and the difficulty",
                "multiplier are applied. false: the mob keeps", "its own plugin's stats (anahedef §4).");
        renderTristate(15, "dungeonDrops", Material.BUNDLE, provider == null ? null
                : provider.defaultDungeonDrops(), "true: killing it may roll loot.yml -> drops.",
                "false: it drops only what it drops anyway.", "");
        renderName(16);

        renderRange(19, HEALTH, Material.RED_DYE, "Health", "max health (vanilla zombie: 20)");
        renderRange(28, DAMAGE, Material.IRON_SWORD, "Damage", "attack damage (vanilla zombie: 3)");
        renderRange(37, SPEED, Material.SUGAR, "Speed", "movement speed (vanilla zombie: 0.23)");

        decor(24, preview(provider));
        frameRow(0);
        frameRow(1);
        frameRow(2);
        frameRow(3);
        frameRow(4);
    }

    private Component nameLine() {
        Object name = draft.get("name");
        return name == null ? Icons.line("<white><bold>" + id) : Icons.safeLine(String.valueOf(name));
    }

    private void renderClass(int slot) {
        MobClass current = MobClass.parse(String.valueOf(draft.get("class") == null
                ? "normal" : draft.get("class")));
        List<String> lore = new ArrayList<>();
        for (MobClass mobClass : MobClass.values()) {
            lore.add((mobClass == current ? "<green>▶ " : "<dark_gray>  ") + mobClass.key());
        }
        lore.add("");
        lore.add("<gray>A pool tag, not a multiplier: it decides");
        lore.add("<gray>where the mob may turn up. boss = boss room only.");
        lore.add("<gray>Left: next  ·  Right: previous");
        button(slot, mark(Icons.icon(Material.NAME_TAG, "<yellow>Class: <white>"
                + (current == null ? draft.get("class") : current.key()), lore), "class"), click -> {
            MobClass[] classes = MobClass.values();
            int index = current == null ? 0 : current.ordinal() + (click.right() ? -1 : 1);
            MobClass next = classes[Math.floorMod(index, classes.length)];
            draft.set("class", next.key());
            refresh();
        });
    }

    private void renderWeight(int slot) {
        int weight = weight();
        List<String> lore = new ArrayList<>(List.of(
                "<gray>Share of a draw inside the same class:",
                "<gray>200 turns up twice as often as 100."));
        lore.addAll(Icons.stepperHelp("10", "100"));
        button(slot, mark(Icons.icon(Material.GOLD_NUGGET, "<yellow>Weight: <white>" + weight, lore),
                "weight"), click -> {
            if (click.drop()) {
                askNumber("weight", value -> {
                    if (value.stripTrailingZeros().scale() > 0 || value.signum() <= 0) {
                        return "Weight is a whole number above 0. To switch a mob off use Enabled.";
                    }
                    draft.set("weight", value.intValueExact());
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

    private int weight() {
        BigDecimal value = Num.of(draft.get("weight"));
        return value == null ? 100 : value.intValue();
    }

    private void renderEnabled(int slot) {
        boolean enabled = !Boolean.FALSE.equals(draft.get("enabled"));
        button(slot, mark(Icons.icon(enabled ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                enabled ? "<green>Enabled" : "<gray>Switched off",
                "<gray>Off keeps the entry in the file but out",
                "<gray>of every pool - the way to retire a mob",
                "<gray>without deleting what you wrote.",
                "", "<gray>Click to toggle"), "enabled"), click -> {
            // Written as enabled: false, or removed again when switched back on: "true" is the
            // default and an entry that never said it should not start saying it.
            draft.set("enabled", enabled ? Boolean.FALSE : null);
            refresh();
        });
    }

    private void renderBaby(int slot) {
        boolean baby = Boolean.TRUE.equals(draft.get("baby"));
        button(slot, mark(Icons.icon(Material.EGG, "<yellow>Baby: <white>" + (baby ? "yes" : "no"),
                "<gray>Where the entity supports it.", "", "<gray>Click to toggle"), "baby"), click -> {
            draft.set("baby", baby ? null : Boolean.TRUE);
            refresh();
        });
    }

    /**
     * The three states are the point: absent means "the provider decides", and that is NOT the same
     * as writing the provider's current answer down — FAZ 3A's rule, and the reason this is not a
     * checkbox.
     */
    private void renderTristate(int slot, String field, Material material,
                                @Nullable Boolean providerDefault, String... help) {
        Object value = draft.get(field);
        String shown = value == null
                ? "provider decides" + (providerDefault == null ? "" : " (" + providerDefault + ")")
                : String.valueOf(value);
        List<String> lore = new ArrayList<>();
        for (String line : help) {
            if (!line.isEmpty()) {
                lore.add("<gray>" + line);
            }
        }
        lore.add("");
        lore.add("<gray>Click: provider decides → true → false");
        button(slot, mark(Icons.icon(material, "<yellow>" + field + ": <white>" + shown, lore), field),
                click -> {
                    draft.set(field, cycle(value));
                    refresh();
                });
    }

    private void renderName(int slot) {
        Object name = draft.get("name");
        List<Component> lore = new ArrayList<>();
        lore.add(Icons.line("<gray>Shown above the mob. MiniMessage."));
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
                    }, "<yellow>Type the name shown above the mob.",
                            "<gray>MiniMessage works: <white>\\<red>\\<bold>Bone Lord</white>");
                });
    }

    private void renderRange(int slot, RangeField range, Material material, String label, String what) {
        boolean present = range.present(draft);
        String step = Num.show(range.step());
        String bigStep = Num.show(range.step().multiply(BigDecimal.TEN));
        button(slot, mark(Icons.icon(material, "<yellow>" + label + ": <white>"
                        + (present ? range.describe(draft) : "natural"),
                "<gray>" + what,
                "<gray>A range is rolled again for every spawn.",
                "",
                present ? "<gray>Right-click: back to the natural value"
                        : "<gray>Click: set a range (starts at " + Num.show(range.fallback()) + ")"),
                range.field()), click -> {
            if (present && click.right()) {
                range.clear(draft);
            } else if (!present && click.left()) {
                range.create(draft);
            }
            refresh();
        });
        if (!present) {
            return;
        }
        BigDecimal[] values = range.read(draft);
        List<String> minLore = new ArrayList<>(List.of("<gray>Raising it past max drags max along."));
        minLore.addAll(Icons.stepperHelp(step, bigStep));
        button(slot + 1, Icons.icon(Material.PAPER, "<white>min " + Num.show(values[0]), minLore),
                click -> adjust(click, range, true, label));
        List<String> maxLore = new ArrayList<>(List.of("<gray>Lowering it past min drags min along."));
        maxLore.addAll(Icons.stepperHelp(step, bigStep));
        button(slot + 2, Icons.icon(Material.PAPER, "<white>max " + Num.show(values[1]), maxLore),
                click -> adjust(click, range, false, label));
    }

    private void adjust(Click click, RangeField range, boolean min, String label) {
        if (click.drop()) {
            askNumber(label.toLowerCase(Locale.ROOT) + (min ? " min" : " max"), value -> {
                range.check(value);
                if (min) {
                    range.setMin(draft, value);
                } else {
                    range.setMax(draft, value);
                }
                return null;
            });
            return;
        }
        BigDecimal step = click.step(range.step());
        if (step.signum() == 0) {
            return;
        }
        if (min) {
            range.adjustMin(draft, step);
        } else {
            range.adjustMax(draft, step);
        }
        refresh();
    }

    /** What the numbers become in game, per difficulty. */
    private ItemStack preview(@Nullable MobProvider provider) {
        List<String> lore = new ArrayList<>();
        Object override = draft.get("statOverride");
        boolean applies = override != null ? Boolean.TRUE.equals(override)
                : provider == null || provider.defaultStatOverride();
        if (!applies) {
            lore.add("<gray>statOverride is <white>false</white>: none of these");
            lore.add("<gray>numbers are applied - the mob keeps its own");
            lore.add("<gray>stats at every difficulty.");
        } else {
            MobRegistry registry = plugin.getMobRegistry();
            for (Difficulty difficulty : Difficulty.values()) {
                DifficultyScaling scaling = registry.scaling(difficulty);
                lore.add("<white>" + difficulty.key() + "<dark_gray> (hp×" + scaling.health() + " dmg×"
                        + scaling.damage() + " spd×" + scaling.speed() + ")");
                lore.add("<gray>  health " + scaled(HEALTH, scaling.health())
                        + "  ·  damage " + scaled(DAMAGE, scaling.damage())
                        + "  ·  speed " + scaled(SPEED, scaling.speed()));
            }
            lore.add("");
            lore.add("<dark_gray>Multipliers: mobs.yml -> difficulty");
        }
        if (provider == null) {
            lore.add("");
            lore.add("<red>Unknown provider in the mob address.");
        } else if (!provider.isAvailable()) {
            lore.add("");
            lore.add("<red>" + provider.displayName() + " is not installed - this entry is disabled.");
        }
        return Icons.icon(Material.WRITTEN_BOOK, "<aqua>In game", lore);
    }

    private String scaled(RangeField range, double multiplier) {
        BigDecimal[] values = range.read(draft);
        if (values == null) {
            return "natural";
        }
        int scale = range == SPEED ? 3 : 1;
        BigDecimal factor = BigDecimal.valueOf(multiplier);
        BigDecimal min = values[0].multiply(factor).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal max = values[1].multiply(factor).setScale(scale, RoundingMode.HALF_UP);
        return min.compareTo(max) == 0 ? Num.show(min) : Num.show(min) + "-" + Num.show(max);
    }

    private @Nullable String setAddress(String text) {
        int colon = text.indexOf(':');
        if (colon <= 0 || colon == text.length() - 1 || text.contains(" ")) {
            return "Write it as provider:key, for example vanilla:ZOMBIE.";
        }
        String providerId = text.substring(0, colon).toLowerCase(Locale.ROOT);
        String key = text.substring(colon + 1);
        // Vanilla entity names are upper case in the shipped file; a lower-case "zombie" works too,
        // but the file should read the same way throughout.
        draft.set("mob", providerId + ":" + (providerId.equals("vanilla") ? key.toUpperCase(Locale.ROOT) : key));
        return null;
    }

    private @Nullable MobProvider providerOf(String address) {
        int colon = address.indexOf(':');
        return colon <= 0 ? null : plugin.getMobRegistry().provider(address.substring(0, colon));
    }

    /** A spawn egg for a vanilla mob, something neutral otherwise. */
    static Material iconFor(String address) {
        if (address.toLowerCase(Locale.ROOT).startsWith("vanilla:")) {
            Material egg = Material.matchMaterial(address.substring(8).toUpperCase(Locale.ROOT)
                    + "_SPAWN_EGG");
            if (egg != null) {
                return egg;
            }
        }
        return address.toLowerCase(Locale.ROOT).startsWith("mythicmobs:")
                ? Material.WITHER_SKELETON_SKULL : Material.ZOMBIE_HEAD;
    }

    // ------------------------------------------------------------------ saving

    @Override
    protected @Nullable String validate(YamlConfiguration patched) {
        ConfigurationSection section = patched.getConfigurationSection("mobs." + id);
        if (section == null) {
            return "mobs." + id + " is no longer a section";
        }
        try {
            MobDefinition.parse(section, id);
            return null;
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
    }

    @Override
    protected void reloadRegistry() {
        plugin.getMobRegistry().load();
    }

    @Override
    protected @Nullable String afterSave() {
        Object enabled = draft.get("enabled");
        return "<gray>mobs.yml reloaded - " + id + ": " + MobListMenu.status(plugin, id,
                Map.of("enabled", enabled == null ? true : enabled));
    }

    @Override
    protected @Nullable Draft reread() {
        Map<String, Object> fields = Entries.read(plugin, MobRegistry.FILE_NAME, "mobs").entries().get(id);
        return fields == null ? null : draftOf(id, fields);
    }
}
