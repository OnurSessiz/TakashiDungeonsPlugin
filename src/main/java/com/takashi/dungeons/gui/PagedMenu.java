package com.takashi.dungeons.gui;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A six-row list: 45 entries a page, the bottom row for navigation.
 *
 * <p>Footer layout, fixed for every list so the hand learns it once: 45 back · 46–47 subclass
 * buttons · 48 previous · 49 page counter · 50 next · 51–53 subclass buttons.
 *
 * <p>The entry list is asked for again on every render. A list kept from the moment the menu
 * opened would go on showing an entry the operator has since switched off, from the very window
 * they switched it off in.
 */
public abstract class PagedMenu<T> extends Menu {

    public static final int PAGE_SIZE = 45;
    protected static final int SLOT_BACK = 45;
    protected static final int SLOT_PREVIOUS = 48;
    protected static final int SLOT_PAGE = 49;
    protected static final int SLOT_NEXT = 50;

    private int page;

    protected PagedMenu(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent,
                        Component title) {
        super(plugin, viewer, parent, 6, title);
    }

    /** What is listed, in order. Asked for on every render. */
    protected abstract List<T> entries();

    protected abstract ItemStack iconOf(T entry);

    protected abstract void onPick(T entry, Click click);

    /** Extra footer buttons (filters and the like) — slots 46, 47, 51, 52, 53 are free. */
    protected void renderFooter() {
    }

    @Override
    protected final void render() {
        List<T> entries = entries();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        // A filter can shrink the list under the current page; land on its last page, not an empty one.
        page = Math.min(page, pages - 1);

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < entries.size(); i++) {
            T entry = entries.get(from + i);
            button(i, iconOf(entry), click -> onPick(entry, click));
        }

        backButton(SLOT_BACK);
        if (page > 0) {
            button(SLOT_PREVIOUS, Icons.icon(Material.SPECTRAL_ARROW, "<yellow>Previous page"),
                    click -> {
                        page--;
                        refresh();
                    });
        }
        decor(SLOT_PAGE, Icons.icon(Material.PAPER, "<gray>Page <white>" + (page + 1) + "</white>/"
                + pages, "<dark_gray>" + entries.size() + " entries"));
        if (page < pages - 1) {
            button(SLOT_NEXT, Icons.icon(Material.SPECTRAL_ARROW, "<yellow>Next page"), click -> {
                page++;
                refresh();
            });
        }
        renderFooter();
        frameRow(5);
    }

    /** Back to the first page — after a filter change, so a new filter never opens on page 4. */
    protected final void firstPage() {
        page = 0;
    }
}
