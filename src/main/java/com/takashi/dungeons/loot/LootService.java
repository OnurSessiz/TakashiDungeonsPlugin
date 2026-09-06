package com.takashi.dungeons.loot;

import com.takashi.dungeons.mob.Difficulty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Turns a {@link LootTable} into actual items.
 *
 * <h2>Two steps, and the split matters</h2>
 * <ol>
 *   <li><b>Which classes</b> — the table's rarity split, scaled once for the difficulty
 *       ({@link LootTable#weightsFor}), then one draw per roll.</li>
 *   <li><b>Which item</b> — a weighted draw inside that class ({@link LootRegistry#pick}).</li>
 * </ol>
 * Scaling once per roll of the table rather than once per draw is not micro-optimisation: it is
 * what makes every draw in one chest read against the same distribution.
 *
 * <h2>A draw that finds an empty pool produces nothing</h2>
 * It does not slide down to a class that does have items. That would be the silent substitution the
 * registry refuses, and it would do it at the worst possible moment — turning the 1% legendary roll
 * a player just earned into a loaf of bread. The count comes back in {@link Roll#empty()} so the
 * command layer can say it out loud, and the registry already warned at load.
 *
 * <h2>Reproducibility</h2>
 * Everything random arrives through the caller's {@link RandomGenerator}. Phase 4B derives one per
 * chest from the dungeon seed ({@code Seeds.derive}), so the same seed produces the same dungeon,
 * the same mobs <i>and</i> the same chests.
 */
public final class LootService {

    private final LootRegistry registry;

    /**
     * One draw: the catalogue entry it landed on and the stack that was built from it.
     *
     * <p>The entry travels with the stack rather than being looked back up from the material.
     * Two entries are allowed to share a material — a plain {@code DIAMOND_SWORD} and a named one
     * — so a reverse lookup would attribute one to the other, and it would do it silently in
     * exactly the report an operator uses to check the weighting.
     */
    public record Drawn(LootItem item, ItemStack stack) {
    }

    /**
     * The result of rolling a table once.
     *
     * @param draws what came out, in draw order
     * @param empty how many draws landed on a class with no items in it — not an error, but the
     *              number that explains a thin chest
     */
    public record Roll(List<Drawn> draws, int empty) {

        public Roll {
            draws = List.copyOf(draws);
        }

        /** Just the stacks — what a chest is filled with. */
        public List<ItemStack> items() {
            List<ItemStack> items = new ArrayList<>(draws.size());
            for (Drawn drawn : draws) {
                items.add(drawn.stack());
            }
            return items;
        }
    }

    public LootService(LootRegistry registry) {
        this.registry = registry;
    }

    public LootRegistry registry() {
        return registry;
    }

    /** Rolls a table at a difficulty. */
    public Roll roll(LootTable table, Difficulty difficulty, RandomGenerator random) {
        RarityWeights weights = table.weightsFor(registry.multiplier(difficulty));
        int count = table.rollCount(random);
        List<Drawn> draws = new ArrayList<>(count);
        int empty = 0;
        for (int i = 0; i < count; i++) {
            ItemClass itemClass = weights.pick(random);
            LootItem item = itemClass == null ? null : registry.pick(itemClass, random);
            if (item == null) {
                empty++;
                continue;
            }
            draws.add(new Drawn(item, build(item, random)));
        }
        return new Roll(draws, empty);
    }

    /**
     * Builds one item, rolling its stack size.
     *
     * <h2>Italics off</h2>
     * A component set as a display name renders italic in vanilla, which is why every server's
     * custom items look like they are being whispered. The decoration is turned off explicitly
     * unless the operator's own MiniMessage turns it back on — {@code decoration:italic} in their
     * string wins, because it is applied after.
     */
    public ItemStack build(LootItem item, RandomGenerator random) {
        ItemStack stack = new ItemStack(item.material(), item.amount().roll(random));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            // Some materials carry no meta at all. The stack is still the right item in the right
            // amount, which is the part that matters.
            return stack;
        }

        if (item.displayName() != null) {
            meta.displayName(plain(item.displayName()));
        }
        if (!item.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>(item.lore().size());
            for (String line : item.lore()) {
                lore.add(plain(line));
            }
            meta.lore(lore);
        }
        applyEnchantments(meta, item);
        if (item.unbreakable()) {
            meta.setUnbreakable(true);
        }
        if (item.customModelData() != null) {
            applyCustomModelData(meta, item.customModelData());
        }
        if (item.glow()) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.flags().forEach(meta::addItemFlags);

        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Writes the enchantments, through the storage meta when the item is a book.
     *
     * <p>An enchanted book keeps its enchantments in a separate list; {@code addEnchant} on one
     * enchants the <i>book itself</i>, which looks identical in the chest and does nothing at the
     * anvil. Getting this wrong is invisible until a player tries to use the reward.
     */
    private void applyEnchantments(ItemMeta meta, LootItem item) {
        if (item.enchantments().isEmpty()) {
            return;
        }
        for (Map.Entry<Enchantment, Integer> entry : item.enchantments().entrySet()) {
            if (meta instanceof EnchantmentStorageMeta storage) {
                storage.addStoredEnchant(entry.getKey(), entry.getValue(), true);
            } else {
                // true: levels above the vanilla maximum are allowed on purpose. A legendary that
                // is capped at Sharpness V is not a legendary.
                meta.addEnchant(entry.getKey(), entry.getValue(), true);
            }
        }
    }

    /**
     * Writes the resource-pack model id.
     *
     * <p>The plain integer form is deprecated in favour of 1.21.5's component, and used anyway: it
     * is what every pack author and every wiki page still writes, the server maps it onto the
     * component itself, and it is the only form that also works on the older releases phase 13 has
     * to test against. Reaching for the component here would mean an operator's {@code
     * custom-model-data: 3} silently stopped matching their pack.
     */
    @SuppressWarnings("deprecation")
    private void applyCustomModelData(ItemMeta meta, int value) {
        meta.setCustomModelData(value);
    }

    /** Deserialises operator MiniMessage with the vanilla name italics turned off first. */
    private Component plain(String miniMessage) {
        return Component.empty().decoration(TextDecoration.ITALIC, false)
                .append(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    /** Whether this material can hold the meta an entry asks for — used by the command layer. */
    public static boolean hasMeta(Material material) {
        return new ItemStack(material).getItemMeta() != null;
    }
}
