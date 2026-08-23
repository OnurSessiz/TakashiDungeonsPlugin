package com.takashi.dungeons.command;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * {@code /tdungeons} — FAZ 0 doğrulama komutu.
 *
 * <p>Sadece iki alt komut: {@code version} ve {@code status}. Dungeon komutları
 * (create, join, reload…) ilgili fazlarda buraya eklenecek.
 */
public final class DungeonsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of("version", "status");

    private final TakashiDungeonsPlugin plugin;

    public DungeonsCommand(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "version" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "version" -> sender.sendMessage(Component
                    .text("TakashiDungeons v" + plugin.getPluginMeta().getVersion(), NamedTextColor.GOLD));

            case "status" -> {
                sender.sendMessage(Component
                        .text("TakashiDungeons v" + plugin.getPluginMeta().getVersion(), NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Entegrasyonlar:", NamedTextColor.GRAY));
                plugin.getIntegrations().forEach((name, present) -> sender.sendMessage(Component
                        .text("  " + name + ": ", NamedTextColor.GRAY)
                        .append(Component.text(present ? "bulundu" : "yok",
                                present ? NamedTextColor.GREEN : NamedTextColor.RED))));
            }

            default -> sender.sendMessage(Component
                    .text("Kullanım: /" + label + " <version|status>", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUB_COMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
