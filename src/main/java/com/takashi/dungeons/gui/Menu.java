package com.takashi.dungeons.gui;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A chest window made of buttons.
 *
 * <h2>The holder is the identity</h2>
 * The same rule as the shop ({@code ShopMenu}): a click belongs to a menu because the inventory's
 * holder is one, never because the title reads a certain way. {@link MenuListener} is the only
 * listener, and it cancels every click and drag on a menu before a single button runs.
 *
 * <h2>Redraw, do not patch</h2>
 * {@link #refresh()} clears the window and runs {@link #render()} again. A button that changed a
 * value never updates "its" slot by hand: two buttons that show the same value (a min and the
 * preview next to it) would drift apart the first time one of them forgot.
 */
public abstract class Menu implements InventoryHolder {

    protected final TakashiDungeonsPlugin plugin;
    protected final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Consumer<Click>> actions = new HashMap<>();

    /** Where "back" goes. {@code null} for a top-level menu, whose back button closes it. */
    private final @Nullable Menu parent;

    protected Menu(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent, int rows,
                   Component title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.parent = parent;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public Player viewer() {
        return viewer;
    }

    /**
     * Draws the menu and shows it.
     *
     * <p>Deferred to the next tick: this is usually called from inside a click on another menu, and
     * opening an inventory while the server is still processing a click on the previous one is the
     * documented way to get a ghost item stuck on the cursor.
     */
    public void open() {
        refresh();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                viewer.openInventory(inventory);
            }
        });
    }

    /** Clears and redraws in place. */
    public final void refresh() {
        inventory.clear();
        actions.clear();
        render();
    }

    /** Puts every button in place. Called on open and after every change. */
    protected abstract void render();

    /**
     * The permission every click is checked against, or {@code null} for none. Checked on each
     * click, not only on open: an operator who is de-opped with the window up must not keep writing
     * files through it.
     */
    public @Nullable String permission() {
        return null;
    }

    protected final void button(int slot, ItemStack icon, Consumer<Click> action) {
        inventory.setItem(slot, icon);
        actions.put(slot, action);
    }

    protected final void decor(int slot, ItemStack icon) {
        inventory.setItem(slot, icon);
    }

    /** Fills every empty slot of one row with the frame pane. */
    protected final void frameRow(int row) {
        ItemStack filler = Icons.filler();
        for (int slot = row * 9; slot < row * 9 + 9; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    /** The back arrow — to the parent menu, or closing the window at the top. */
    protected final void backButton(int slot) {
        button(slot, Icons.icon(Material.ARROW, parent == null ? "<gray>Close" : "<gray>Back"),
                click -> back());
    }

    protected void back() {
        if (parent != null) {
            parent.open();
        } else {
            viewer.closeInventory();
        }
    }

    protected final @Nullable Menu parent() {
        return parent;
    }

    /** Called by {@link MenuListener}; the event is already cancelled by then. */
    final void click(int slot, Click click) {
        Consumer<Click> action = actions.get(slot);
        if (action != null) {
            action.accept(click);
        }
    }
}
