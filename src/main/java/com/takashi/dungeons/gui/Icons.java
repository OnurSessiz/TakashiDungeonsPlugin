package com.takashi.dungeons.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Building the stacks a menu is made of.
 *
 * <p>Everything goes through MiniMessage, and italics are switched off: Minecraft italicises custom
 * names and lore by default, and a whole window of slanted text reads as a mistake.
 */
public final class Icons {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Icons() {
    }

    /** A MiniMessage line, italics off, white unless the markup says otherwise. */
    public static Component line(String mini) {
        return MINI.deserialize(mini).colorIfAbsent(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** A MiniMessage line, or the raw text if it does not parse — operator input goes through here. */
    public static Component safeLine(String mini) {
        try {
            return line(mini);
        } catch (RuntimeException broken) {
            return Component.text(mini, NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        }
    }

    public static ItemStack icon(Material material, String name, String... lore) {
        return icon(material, name, List.of(lore));
    }

    public static ItemStack icon(Material material, String name, List<String> lore) {
        List<Component> lines = new ArrayList<>(lore.size());
        for (String text : lore) {
            lines.add(line(text));
        }
        return icon(material, line(name), lines);
    }

    public static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material.isItem() ? material : Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            meta.lore(lore.stream().map(component -> component
                    .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
            // Attribute lines ("+7 Attack Damage") under a sword used as a button are noise.
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** A pane with no name — the frame between buttons. */
    public static ItemStack filler() {
        return icon(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
    }

    /** The shared "how to adjust a number" lore lines. */
    public static List<String> stepperHelp(String small, String large) {
        return List.of(
                "",
                "<gray>Left <white>+" + small + "</white>  ·  Right <white>-" + small + "</white>",
                "<gray>Shift <white>±" + large + "</white>  ·  Q <white>type a value</white>");
    }
}
