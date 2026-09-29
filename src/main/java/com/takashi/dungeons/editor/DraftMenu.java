package com.takashi.dungeons.editor;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.gui.Icons;
import com.takashi.dungeons.gui.Menu;
import com.takashi.dungeons.yaml.YamlPatch;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * An editor over one {@link Draft}: the body is the subclass's, the footer is shared.
 *
 * <p>Footer: 45 back · 47 reload from disk · 48 discard · 49 save. Changed fields glint and say what
 * they were — an operator three clicks into a draft should not have to remember which buttons they
 * touched.
 *
 * <p>Leaving with unsaved changes takes a shift-click. A plain click on Back says what would be
 * lost instead of losing it.
 */
abstract class DraftMenu extends Menu {

    protected static final int SLOT_BACK = 45;
    protected static final int SLOT_RELOAD = 47;
    protected static final int SLOT_DISCARD = 48;
    protected static final int SLOT_SAVE = 49;

    protected final String file;
    protected Draft draft;

    DraftMenu(TakashiDungeonsPlugin plugin, Player viewer, @Nullable Menu parent, Component title,
              String file, Draft draft) {
        super(plugin, viewer, parent, 6, title);
        this.file = file;
        this.draft = draft;
    }

    @Override
    public String permission() {
        return EditorHub.PERMISSION;
    }

    /** The editor's own buttons, rows 0–4. */
    protected abstract void renderBody();

    /** Given the patched file, why it would not load — or {@code null}. */
    protected abstract @Nullable String validate(YamlConfiguration patched);

    /** Makes a successful save live. */
    protected abstract void reloadRegistry();

    /** A fresh draft from the file on disk, or {@code null} when the entry is gone. */
    protected abstract @Nullable Draft reread();

    /** Said after a save — what the registry made of the result. */
    protected @Nullable String afterSave() {
        return null;
    }

    @Override
    protected final void render() {
        renderBody();

        button(SLOT_BACK, Icons.icon(Material.ARROW, "<gray>Back", draft.dirty()
                ? List.of("<gold>Unsaved changes: <white>" + escape(draft.summary()),
                "<gray>Shift-click to leave without saving.")
                : List.of()), click -> {
            if (draft.dirty() && !click.shift()) {
                viewer.sendMessage(Component.text("Unsaved changes (" + draft.summary()
                        + "). Save, or shift-click Back to leave without saving.",
                        NamedTextColor.GOLD));
                return;
            }
            back();
        });

        button(SLOT_RELOAD, Icons.icon(Material.COMPASS, "<aqua>Reload from disk",
                "<gray>Reads " + file + " again - use it after",
                "<gray>editing the file by hand.",
                draft.dirty() ? "<gold>Shift-click: your unsaved changes are dropped." : ""), click -> {
            if (draft.dirty() && !click.shift()) {
                viewer.sendMessage(Component.text("You have unsaved changes - shift-click to drop "
                        + "them and reload.", NamedTextColor.GOLD));
                return;
            }
            Draft fresh = reread();
            if (fresh == null) {
                viewer.sendMessage(Component.text("The entry is no longer in " + file + ".",
                        NamedTextColor.RED));
                back();
                return;
            }
            draft = fresh;
            refresh();
        });

        if (draft.dirty()) {
            button(SLOT_DISCARD, Icons.icon(Material.RED_DYE, "<red>Discard changes",
                    "<gray>" + escape(draft.summary())), click -> {
                draft.discard();
                refresh();
            });
            button(SLOT_SAVE, Icons.icon(Material.LIME_DYE, "<green><bold>Save",
                    "<gray>" + escape(draft.summary()),
                    "",
                    "<gray>Writes only these values into <white>" + file + "</white>",
                    "<gray>and reloads it."), click -> save());
        } else {
            decor(SLOT_SAVE, Icons.icon(Material.GRAY_DYE, "<gray>Save",
                    "<dark_gray>Nothing changed yet."));
        }
        frameRow(5);
    }

    /** Runs on the patched text before it is validated and written. */
    protected void touchUp(YamlPatch patch) {
    }

    private void save() {
        boolean saved = Saver.save(plugin, viewer, file, draft, this::touchUp, this::validate,
                this::reloadRegistry);
        if (saved) {
            String after = afterSave();
            if (after != null) {
                viewer.sendMessage(Icons.line(after));
            }
        }
        refresh();
    }

    // ------------------------------------------------------------------ helpers for the body

    /** Glint and a "was" line on a button whose field is changed. */
    protected ItemStack mark(ItemStack stack, String... fields) {
        List<String> changed = new ArrayList<>();
        for (String field : fields) {
            if (draft.isChanged(field)) {
                changed.add(field + " was " + Num.describe(draft.original(field)));
            }
        }
        if (changed.isEmpty()) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            for (String line : changed) {
                lore.add(Icons.line("<gold>● changed - " + escape(line)));
            }
            meta.lore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Asks for a number in chat; {@code apply} returns the reason to refuse, or {@code null}. */
    protected void askNumber(String what, Function<BigDecimal, @Nullable String> apply) {
        plugin.getChatPrompt().ask(viewer, this, text -> apply.apply(Num.parse(text)),
                "<yellow>Type the new value for <white>" + what + "</white>.");
    }

    protected void askText(Function<String, @Nullable String> apply, String... question) {
        plugin.getChatPrompt().ask(viewer, this, apply, question);
    }

    /** Operator text shown inside MiniMessage lore. */
    protected static String escape(String text) {
        return text.replace("<", "\\<");
    }

    /** A three-state flag: absent (provider decides) → true → false → absent. */
    protected static @Nullable Boolean cycle(@Nullable Object current) {
        if (current == null) {
            return Boolean.TRUE;
        }
        return Boolean.TRUE.equals(current) ? Boolean.FALSE : null;
    }
}
