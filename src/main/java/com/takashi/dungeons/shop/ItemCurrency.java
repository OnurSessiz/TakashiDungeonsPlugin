package com.takashi.dungeons.shop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Paying with an item — the currency that needs nothing installed.
 *
 * <h2>Why this is the default and not a stopgap</h2>
 * A fresh server has no economy plugin, and the out-of-box guarantee says the shop has to work
 * there. It is also the only currency that closes a loop the plugin already owns: {@code loot.yml}
 * ships {@code emerald_shard} and {@code gold_ingot_stash} in the uncommon pool, so what comes out
 * of a chest is what gets spent at the merchant. A coin nobody can earn would be a shop nobody can
 * use.
 *
 * <h2>Matched on material, deliberately</h2>
 * Any emerald pays, not one particular named emerald. Matching a custom item would make the
 * currency forgeable-looking (a player who renames an emerald has an obvious question) and would
 * make every existing stack in the world worthless. A real coin — one that is not an item at all —
 * arrives with phase 12 as its own {@link ShopCurrency}, which is what the interface is for.
 */
public final class ItemCurrency implements ShopCurrency {

    public static final String ID = "item";

    private final Material material;

    /** What the material is called in messages. Operator text, so MiniMessage is honoured. */
    private final Component label;

    public ItemCurrency(Material material, Component label) {
        this.material = material;
        this.label = label;
    }

    /**
     * Reads the {@code currency:} block.
     *
     * <p>Throws rather than falling back to a default material: a typo that silently made the shop
     * charge in dirt is the kind of mistake nobody traces back to this line.
     */
    public static ItemCurrency parse(@Nullable ConfigurationSection section, String where) {
        String raw = section == null ? null : section.getString("material");
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(where + ": the 'material' field is required - "
                    + "for example: EMERALD");
        }
        Material material = Material.matchMaterial(raw.trim());
        if (material == null || !material.isItem()) {
            throw new IllegalArgumentException(where + ": unknown currency material '" + raw
                    + "' - use a Minecraft item id, for example EMERALD");
        }
        String name = section.getString("name", "");
        Component label = name.isBlank()
                ? Component.text(material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '))
                : MiniMessage.miniMessage().deserialize(name);
        return new ItemCurrency(material, label);
    }

    public Material material() {
        return material;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return true;   // the whole point of this implementation
    }

    @Override
    public double balance(Player player) {
        int total = 0;
        // getStorageContents, not getContents: the latter includes armour and the off-hand, and a
        // player wearing an emerald-coloured helmet does not have more money.
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (matches(stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Takes the price out of the inventory.
     *
     * <p>Counted first, then removed. Removing as it goes and giving up halfway would leave the
     * player short of both the item and the goods — and the failure would happen at exactly the
     * moment they could least afford it.
     */
    @Override
    public boolean take(Player player, double amount) {
        int needed = (int) Math.ceil(amount);
        if (needed <= 0) {
            return true;
        }
        if (balance(player) < needed) {
            return false;
        }
        Inventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        int left = needed;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack stack = contents[i];
            if (!matches(stack)) {
                continue;
            }
            int taken = Math.min(left, stack.getAmount());
            left -= taken;
            if (stack.getAmount() == taken) {
                contents[i] = null;
            } else {
                stack.setAmount(stack.getAmount() - taken);
            }
        }
        inventory.setStorageContents(contents);
        return true;
    }

    /**
     * A plain stack of the material and nothing else.
     *
     * <p>An item with a custom name, lore or enchantment is refused: it is somebody's keepsake, or
     * a loot item that happens to be made of the currency material, and spending it by accident is
     * not recoverable.
     */
    private boolean matches(@Nullable ItemStack stack) {
        return stack != null && stack.getType() == material && !stack.hasItemMeta();
    }

    @Override
    public Component format(double amount) {
        return Component.text((int) Math.ceil(amount) + " ").append(label);
    }
}
