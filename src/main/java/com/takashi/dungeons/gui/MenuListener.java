package com.takashi.dungeons.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * The single listener behind every {@link Menu}.
 *
 * <h2>Every click and every drag is cancelled — all of them</h2>
 * Not only clicks on buttons: shift-clicks from the player's own inventory, number-key swaps,
 * double-click gathering, drags across both halves. It is the shop's rule ({@code ShopListener})
 * and for the same reason: a window made of item stacks becomes an item duplicator through the one
 * interaction path nobody thought of. Here the "items" are an editor's buttons, and a button pulled
 * out onto the cursor is a creative-mode player walking off with a renamed spawn egg.
 */
public final class MenuListener implements Listener {

    /**
     * The only click types a button ever sees. The rest are cancelled and dropped: a fast double
     * click arrives as LEFT, LEFT, DOUBLE_CLICK, and a toggle that also answered the third would
     * flip three times; a number key or the off-hand swap pressed over a button is not a request.
     */
    private static final Set<ClickType> DISPATCHED = EnumSet.of(ClickType.LEFT, ClickType.RIGHT,
            ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.DROP, ClickType.CONTROL_DROP);

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        // First and unconditionally, before any branch below can decide otherwise.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;   // a click down in the player's own inventory does nothing
        }
        if (!DISPATCHED.contains(event.getClick())) {
            return;
        }
        String permission = menu.permission();
        if (permission != null && !player.hasPermission(permission)) {
            player.closeInventory();
            player.sendMessage(Component.text("You no longer have permission to use this editor ("
                    + permission + ").", NamedTextColor.RED));
            return;
        }
        menu.click(event.getSlot(), new Click(player, event.getClick()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    /**
     * Closes every open menu — on disable. A window whose holder class belongs to an unloaded
     * plugin keeps responding to nothing, and after {@code /reload} the new listener would not
     * recognise it as a menu at all: its items would be free to take.
     */
    public static void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                player.closeInventory();
            }
        }
    }
}
