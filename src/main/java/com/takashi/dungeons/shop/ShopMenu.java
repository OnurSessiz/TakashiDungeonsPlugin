package com.takashi.dungeons.shop;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The merchant's window.
 *
 * <h2>The holder IS the identity</h2>
 * A click is recognised as a shop click because the inventory's holder is one of these — never
 * because the title matches a string. The title comes out of {@code lang/<code>.yml} and is meant
 * to be translated; a check that reads it would break the shop for everybody who translates it,
 * and would break it silently.
 *
 * <h2>What is shown is not what is sold</h2>
 * The stack in the window carries extra lore — the price, the limit, "click to buy". The stack the
 * player receives is built separately from the same {@link com.takashi.dungeons.loot.LootItem}. If
 * the display stack were handed over, every purchase would arrive with a price tag stapled to it.
 */
public final class ShopMenu implements InventoryHolder {

    /** Six rows. The last one is the footer, so 45 slots are for goods. */
    private static final int SIZE = 54;
    private static final int GOODS = ShopRegistry.MAX_SHOWN;
    private static final int BALANCE_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    private final TakashiDungeonsPlugin plugin;
    private final int instanceId;
    private final Player viewer;

    /** Slot → what is for sale there. Absent means the slot is furniture. */
    private final Map<Integer, ShopEntry> slots = new HashMap<>();

    private final Inventory inventory;

    public ShopMenu(TakashiDungeonsPlugin plugin, Player viewer, int instanceId) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.instanceId = instanceId;
        this.inventory = Bukkit.createInventory(this, SIZE,
                plugin.getMessages().get("shop.menu-title"));
        render();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public int instanceId() {
        return instanceId;
    }

    /** What is being sold in this slot, or {@code null} if the slot is not for sale. */
    public @Nullable ShopEntry entryAt(int slot) {
        return slots.get(slot);
    }

    public boolean isCloseButton(int slot) {
        return slot == CLOSE_SLOT;
    }

    /**
     * Draws the whole window.
     *
     * <p>Called again after every purchase: the balance changed, and so did the "x left" on the
     * entry that was just bought. A window that goes stale after a purchase is one a player has to
     * close and reopen to believe.
     */
    public void render() {
        inventory.clear();
        slots.clear();

        ShopRegistry shop = plugin.getShopRegistry();
        ShopCurrency currency = shop.currency();
        Random random = new Random();

        int slot = 0;
        for (ShopEntry entry : shop.stock()) {
            if (slot >= GOODS) {
                // Reported once at load rather than here, and truncated rather than paginated:
                // a supply merchant with 46 lines of stock is a different feature.
                break;
            }
            slots.put(slot, entry);
            inventory.setItem(slot, display(entry, currency, random));
            slot++;
        }

        ItemStack balance = new ItemStack(currency instanceof ItemCurrency item
                ? item.material() : Material.PAPER);
        applyName(balance, plugin.getMessages().get("shop.menu-balance",
                Placeholder.component("amount",
                        currency == null ? Component.empty() : currency.format(currency.balance(viewer)))));
        inventory.setItem(BALANCE_SLOT, balance);

        ItemStack close = new ItemStack(Material.BARRIER);
        applyName(close, plugin.getMessages().get("shop.menu-close"));
        inventory.setItem(CLOSE_SLOT, close);
    }

    /** The goods as they appear on the shelf: the real item plus a price tag. */
    private ItemStack display(ShopEntry entry, @Nullable ShopCurrency currency, Random random) {
        ItemStack stack = plugin.getLootService().build(entry.item(), random);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        List<Component> lore = new ArrayList<>();
        if (meta.hasLore() && meta.lore() != null) {
            lore.addAll(meta.lore());
        }
        lore.add(Component.empty());
        lore.add(plugin.getMessages().get("shop.menu-price", Placeholder.component("amount",
                currency == null ? Component.empty() : currency.format(entry.price()))));

        int left = remaining(entry);
        if (entry.hasLimit()) {
            lore.add(plugin.getMessages().get(left > 0 ? "shop.menu-left" : "shop.menu-sold-out",
                    Placeholder.unparsed("left", String.valueOf(left)),
                    Placeholder.unparsed("limit", String.valueOf(entry.limit()))));
        }
        if (left > 0) {
            lore.add(plugin.getMessages().get("shop.menu-click"));
        }
        // Italics off: Minecraft italicises custom lore by default and a whole shelf of slanted
        // price tags reads as a mistake.
        meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC,
                TextDecoration.State.FALSE)).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    /** How many of this entry the viewer may still buy. {@link Integer#MAX_VALUE} for no limit. */
    public int remaining(ShopEntry entry) {
        if (!entry.hasLimit()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, entry.limit()
                - plugin.getShopManager().bought(instanceId, viewer.getUniqueId(), entry.id()));
    }

    private void applyName(ItemStack stack, Component name) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name.colorIfAbsent(NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            stack.setItemMeta(meta);
        }
    }
}
