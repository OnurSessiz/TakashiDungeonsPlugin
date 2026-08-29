package com.takashi.dungeons.command;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.hud.HudService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * {@code /hud} — the player-facing switch. Every player may run it; the admin side of the
 * HUD (server name, IP) lives under {@code /tdungeons hud} instead, behind the admin
 * permission.
 */
public final class HudCommand implements CommandExecutor, TabCompleter {

    private static final List<String> STATES = List.of("on", "off");

    private final TakashiDungeonsPlugin plugin;

    public HudCommand(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Bu komut oyuncu tarafından çalıştırılmalı.",
                    NamedTextColor.RED));
            return true;
        }

        HudService hud = plugin.getHudService();
        if (hud == null || !hud.isEnabled()) {
            player.sendMessage(Component.text("HUD sunucu genelinde kapalı.", NamedTextColor.RED));
            return true;
        }

        boolean visible;
        if (args.length == 0) {
            visible = hud.toggle(player);
        } else {
            String state = args[0].toLowerCase(Locale.ROOT);
            if (!STATES.contains(state)) {
                player.sendMessage(Component.text("Kullanım: /" + label + " [on|off]",
                        NamedTextColor.RED));
                return true;
            }
            visible = state.equals("on");
            hud.setVisible(player, visible);
        }

        player.sendMessage(Component.text("HUD ", NamedTextColor.GRAY)
                .append(visible
                        ? Component.text("açık", NamedTextColor.GREEN)
                        : Component.text("kapalı", NamedTextColor.RED)));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return STATES.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
