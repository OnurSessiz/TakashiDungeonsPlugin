package com.takashi.dungeons.loot;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One entry from {@code loot.yml}: what the item is, which pool it may be drawn from, and how it
 * looks when it lands in a chest.
 *
 * <h2>Everything is declared, nothing is guessed</h2>
 * An unknown material or a misspelled enchantment <b>disables the entry with a reason</b> rather
 * than substituting something plausible — the same rule {@link com.takashi.dungeons.mob.MobRegistry}
 * follows. A legendary that quietly became a stone sword is a balance complaint three weeks later,
 * and nobody traces that back to a typo.
 *
 * <h2>Names and lore are MiniMessage, and they belong here, not in lang/</h2>
 * The language layer ({@code isleyis.md} § Dil Katmanı) owns every word the plugin itself says.
 * An item's name is not the plugin speaking: it is content the operator authored, in the same file
 * as its weight and its material, and phase 9's GUI will edit the two together. The shipped file is
 * written in English for the same reason the default language is.
 *
 * @param id            registry key, unique within {@code loot.yml}
 * @param itemClass     which pool a draw may take this from
 * @param weight        share in the weighted draw inside that pool — the same 1000-based convention
 *                      as room templates and {@code mobs.yml}
 * @param material      the item; must be a real, obtainable item type
 * @param amount        stack size range, rolled per draw
 * @param displayName   MiniMessage name, or {@code null} to leave the vanilla name
 * @param lore          MiniMessage lore lines; empty for none
 * @param enchantments  enchantment → level. Levels above the vanilla maximum are allowed: this is a
 *                      dungeon reward, and refusing them would rule out the one thing that makes a
 *                      legendary feel legendary
 * @param unbreakable   whether the item ignores durability
 * @param customModelData resource-pack model id, or {@code null} for none
 * @param glow          force the enchantment glint on an item that has no enchantment
 * @param flags         item flags to hide (attribute lines, the enchantment list, and so on)
 */
public record LootItem(String id, ItemClass itemClass, int weight, Material material,
                       CountRange amount, @Nullable String displayName, List<String> lore,
                       Map<Enchantment, Integer> enchantments, boolean unbreakable,
                       @Nullable Integer customModelData, boolean glow, Set<ItemFlag> flags) {

    public LootItem {
        lore = List.copyOf(lore);
        // Insertion order, not Map.copyOf: that returns an unspecified iteration order, and
        // /tdungeons loot info would list the enchantments in an order the operator did not write
        // and cannot match against the file.
        enchantments = Collections.unmodifiableMap(new LinkedHashMap<>(enchantments));
        flags = flags.isEmpty() ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(flags));
    }

    /** Whether this entry does anything beyond "a stack of the vanilla item". */
    public boolean isCustomised() {
        return displayName != null || !lore.isEmpty() || !enchantments.isEmpty()
                || unbreakable || customModelData != null || glow || !flags.isEmpty();
    }

    /**
     * Reads one {@code items.<id>} block.
     *
     * <p>Strict, like {@code MobDefinition.parse}: the message names the file, the entry and the
     * field, so the operator has one line to look at instead of a set that loaded and behaves wrong.
     */
    public static LootItem parse(ConfigurationSection section, String id) {
        String where = "items." + id;

        String materialName = section.getString("material");
        if (materialName == null || materialName.isBlank()) {
            throw new IllegalArgumentException(where + ": the 'material' field is required - "
                    + "for example: DIAMOND_SWORD");
        }
        Material material = Material.matchMaterial(materialName.trim());
        if (material == null) {
            throw new IllegalArgumentException(where + ": unknown material '" + materialName
                    + "' - use a Minecraft item id, for example DIAMOND_SWORD");
        }
        if (!material.isItem()) {
            throw new IllegalArgumentException(where + ": '" + materialName + "' is a block state, "
                    + "not something that can sit in a chest");
        }

        ItemClass itemClass = ItemClass.parse(section.getString("class", ItemClass.COMMON.key()));
        if (itemClass == null) {
            throw new IllegalArgumentException(where + ": invalid class '"
                    + section.getString("class") + "' - valid: common, uncommon, rare, ultra_rare, "
                    + "legendary");
        }

        int weight = section.getInt("weight", 100);
        if (weight <= 0) {
            throw new IllegalArgumentException(where + ": weight must be positive (found: " + weight
                    + "). To switch an item off write 'enabled: false' or delete the entry.");
        }

        CountRange amount = CountRange.parse(section.get("amount"), where + " -> amount",
                CountRange.fixed(1));
        if (amount.max() < 1) {
            throw new IllegalArgumentException(where + ": amount must be at least 1 - an item that "
                    + "can roll a stack of zero is a draw that quietly produces nothing");
        }
        if (amount.max() > material.getMaxStackSize()) {
            throw new IllegalArgumentException(where + ": amount " + amount + " is larger than "
                    + material + " stacks (max " + material.getMaxStackSize() + ")");
        }

        return new LootItem(id, itemClass, weight, material, amount,
                section.getString("name"),
                section.getStringList("lore"),
                parseEnchantments(section.getConfigurationSection("enchantments"), where),
                section.getBoolean("unbreakable", false),
                section.isSet("custom-model-data") ? section.getInt("custom-model-data") : null,
                section.getBoolean("glow", false),
                parseFlags(section.getStringList("hide"), where));
    }

    private static Map<Enchantment, Integer> parseEnchantments(@Nullable ConfigurationSection section,
                                                               String where) {
        if (section == null) {
            return Map.of();
        }
        Map<Enchantment, Integer> enchantments = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Enchantment enchantment = matchEnchantment(key);
            if (enchantment == null) {
                throw new IllegalArgumentException(where + " -> enchantments: unknown enchantment '"
                        + key + "' - use a Minecraft enchantment id, for example sharpness");
            }
            int level = section.getInt(key);
            if (level < 1) {
                throw new IllegalArgumentException(where + " -> enchantments." + key
                        + ": the level must be at least 1 - found: " + level);
            }
            enchantments.put(enchantment, level);
        }
        return enchantments;
    }

    /**
     * Looks an enchantment up by its Minecraft id.
     *
     * <p>Accepts {@code sharpness}, {@code SHARPNESS} and {@code minecraft:sharpness}. The
     * uppercase form is what operators type out of habit from the old Bukkit constant names, and
     * refusing it would be a papercut with no upside.
     *
     * <p>Read through {@link RegistryAccess} rather than the {@code Registry.ENCHANTMENT} constant,
     * which 1.21 deprecated: enchantments are data-driven now, so a datapack's enchantment is a
     * legitimate thing for an operator to put in a loot table and the old constant cannot see one.
     */
    private static @Nullable Enchantment matchEnchantment(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = value.indexOf(':') >= 0
                ? NamespacedKey.fromString(value) : NamespacedKey.minecraft(value);
        return key == null ? null
                : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
    }

    private static Set<ItemFlag> parseFlags(List<String> raw, String where) {
        if (raw.isEmpty()) {
            return Set.of();
        }
        Set<ItemFlag> flags = EnumSet.noneOf(ItemFlag.class);
        List<String> known = new ArrayList<>();
        for (ItemFlag flag : ItemFlag.values()) {
            known.add(flag.name());
        }
        for (String name : raw) {
            ItemFlag flag = null;
            for (ItemFlag candidate : ItemFlag.values()) {
                if (candidate.name().equalsIgnoreCase(name.trim())) {
                    flag = candidate;
                    break;
                }
            }
            if (flag == null) {
                throw new IllegalArgumentException(where + " -> hide: unknown flag '" + name
                        + "' - valid: " + String.join(", ", known));
            }
            flags.add(flag);
        }
        return flags;
    }

    @Override
    public String toString() {
        return id + " (" + material + ", " + itemClass + ", w=" + weight + ")";
    }
}
