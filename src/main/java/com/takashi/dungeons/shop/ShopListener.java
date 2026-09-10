package com.takashi.dungeons.shop;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;

/**
 * Everything a player can do to the merchant.
 *
 * <h2>The vanilla trade window never opens — {@code anahedef.md} §5</h2>
 * Trying to wire a custom currency into villager trades does not work: a vanilla trade is a barter
 * of item stacks and nothing else, so the interact event is cancelled and our own window is opened
 * in its place. Cancelling is also what stops a villager restocking, a lead being attached, or a
 * name tag being used on it.
 *
 * <h2>Every inventory click is cancelled — all of them</h2>
 * Not only clicks on the goods: shift-clicks from the player's own inventory, number-key swaps,
 * double-click gathering and drags. A shop window is a display, and the single most common way a
 * plugin shop becomes an item duplicator is one interaction path that was not thought of. So the
 * rule here is the blunt one: if the top inventory is a {@link ShopMenu}, nothing moves.
 */
public final class ShopListener implements Listener {

    private final TakashiDungeonsPlugin plugin;

    public ShopListener(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ opening

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;   // the off-hand fires a second event for the same click
        }
        // PlayerInteractAtEntityEvent is a SUBCLASS of this one and shares its handler list, so
        // without this the same click can arrive twice. Handled on the plain event only, which is
        // the one a villager fires.
        if (event instanceof PlayerInteractAtEntityEvent) {
            return;
        }
        Integer instanceId = plugin.getShopManager().instanceOf(event.getRightClicked());
        if (instanceId == null) {
            return;
        }
        event.setCancelled(true);
        open(event.getPlayer(), instanceId);
    }

    private void open(Player player, int instanceId) {
        ShopRegistry shop = plugin.getShopRegistry();
        if (!shop.isUsable()) {
            // The merchant is standing there because the catalogue WAS usable when the dungeon
            // opened; a /tdungeons reload since then may have broken it. Saying so beats a window
            // with nothing in it.
            player.sendMessage(plugin.getMessages().get("shop.unavailable"));
            return;
        }
        player.openInventory(new ShopMenu(plugin, player, instanceId).getInventory());
    }

    // ------------------------------------------------------------------ clicking

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        ShopMenu menu = menuOf(event.getView().getTopInventory().getHolder());
        if (menu == null) {
            return;
        }
        // Cancelled first and unconditionally, before a single branch below can decide otherwise.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // A click in the player's own inventory is now a no-op, which is what we want: the goods
        // are up there and nothing should travel in either direction by hand.
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (menu.isCloseButton(event.getSlot())) {
            player.closeInventory();
            return;
        }
        ShopEntry entry = menu.entryAt(event.getSlot());
        if (entry == null) {
            return;
        }
        buy(player, menu, entry);
    }

    /** Drags can cross both inventories at once, so they are refused wholesale. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory().getHolder()) != null) {
            event.setCancelled(true);
        }
    }

    private void buy(Player player, ShopMenu menu, ShopEntry entry) {
        ShopManager.Purchase result =
                plugin.getShopManager().buy(player, menu.instanceId(), entry);
        var messages = plugin.getMessages();
        switch (result) {
            case OK -> {
                player.sendMessage(messages.get("shop.bought",
                        Placeholder.unparsed("amount", String.valueOf(entry.amount())),
                        Placeholder.component("item", itemName(entry)),
                        Placeholder.component("price", price(entry))));
                // Redrawn rather than left alone: the balance moved and so did the "x left".
                menu.render();
            }
            case POOR -> player.sendMessage(messages.get("shop.too-poor",
                    Placeholder.component("price", price(entry))));
            case LIMIT -> {
                player.sendMessage(messages.get("shop.limit-reached"));
                menu.render();
            }
            case NO_ROOM -> player.sendMessage(messages.get("shop.no-room"));
            case GONE -> {
                player.sendMessage(messages.get("shop.gone"));
                player.closeInventory();
            }
        }
    }

    private net.kyori.adventure.text.Component itemName(ShopEntry entry) {
        String name = entry.item().displayName();
        return name == null
                ? net.kyori.adventure.text.Component.translatable(entry.item().material())
                : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(name);
    }

    private net.kyori.adventure.text.Component price(ShopEntry entry) {
        ShopCurrency currency = plugin.getShopRegistry().currency();
        return currency == null
                ? net.kyori.adventure.text.Component.text(entry.price())
                : currency.format(entry.price());
    }

    private ShopMenu menuOf(InventoryHolder holder) {
        return holder instanceof ShopMenu menu ? menu : null;
    }
}
