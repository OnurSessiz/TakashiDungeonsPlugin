package com.takashi.dungeons.command;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.schematic.SchematicService;
import com.takashi.dungeons.schematic.TestRoomFactory;
import com.takashi.dungeons.world.GridSlot;
import com.takashi.dungeons.world.GridSlotManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /tdungeons} — yönetim ve FAZ 1 doğrulama komutu.
 *
 * <p>Buradaki {@code gen}/{@code paste}/{@code free} alt komutları geliştirme amaçlı:
 * generation zincirini (slot ayır → schematic yükle → paste) elle tetikleyip doğrulamak
 * için var. Oyuncuya açık dungeon komutları (join/leave) FAZ 2'de gelecek.
 */
public final class DungeonsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS =
            List.of("version", "status", "world", "list", "gen", "paste", "slots", "free");

    private static final List<String> ROTATIONS = List.of("0", "90", "180", "270");

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
            case "status" -> status(sender);
            case "world" -> world(sender);
            case "list" -> list(sender);
            case "gen" -> generate(sender);
            case "paste" -> paste(sender, label, args);
            case "slots" -> slots(sender);
            case "free" -> free(sender, label, args);
            default -> sender.sendMessage(Component
                    .text("Kullanım: /" + label + " <" + String.join("|", SUB_COMMANDS) + ">",
                            NamedTextColor.RED));
        }
        return true;
    }

    private void status(CommandSender sender) {
        sender.sendMessage(Component
                .text("TakashiDungeons v" + plugin.getPluginMeta().getVersion(), NamedTextColor.GOLD));

        sender.sendMessage(Component.text("Entegrasyonlar:", NamedTextColor.GRAY));
        plugin.getIntegrations().forEach((name, present) -> sender.sendMessage(Component
                .text("  " + name + ": ", NamedTextColor.GRAY)
                .append(Component.text(present ? "bulundu" : "yok",
                        present ? NamedTextColor.GREEN : NamedTextColor.RED))));

        World world = plugin.getWorldManager() == null ? null : plugin.getWorldManager().getWorld();
        sender.sendMessage(Component.text("Dungeon dünyası: ", NamedTextColor.GRAY)
                .append(world == null
                        ? Component.text("yüklenmedi", NamedTextColor.RED)
                        : Component.text(world.getName() + " (" + world.getLoadedChunks().length
                                + " chunk yüklü)", NamedTextColor.GREEN)));

        GridSlotManager slots = plugin.getSlotManager();
        if (slots != null) {
            sender.sendMessage(Component.text("Slot: ", NamedTextColor.GRAY)
                    .append(Component.text(slots.allocatedCount() + " ayrılmış, kenar "
                            + slots.slotSize() + " blok", NamedTextColor.WHITE)));
        }

        SchematicService service = plugin.getSchematicService();
        sender.sendMessage(Component.text("Schematic: ", NamedTextColor.GRAY)
                .append(service == null
                        ? Component.text("devre dışı (WorldEdit/FAWE yok)", NamedTextColor.RED)
                        : Component.text(service.list().size() + " dosya, paste "
                                + (service.isAsyncPaste() ? "async" : "senkron"), NamedTextColor.GREEN)));
    }

    private void world(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        World world = requireWorld(sender);
        if (world == null) {
            return;
        }
        player.teleport(new Location(world, 0.5, 65, 0.5));
        sender.sendMessage(Component.text("Dungeon dünyasına ışınlandın.", NamedTextColor.GREEN));
    }

    private void list(CommandSender sender) {
        SchematicService service = requireSchematics(sender);
        if (service == null) {
            return;
        }
        List<String> names = service.list();
        if (names.isEmpty()) {
            sender.sendMessage(Component.text("Schematic yok. Klasör: "
                    + service.getDirectory().getPath() + "  (/tdungeons gen ile test odası üret)",
                    NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Schematic (" + names.size() + "):", NamedTextColor.GRAY));
        names.forEach(n -> sender.sendMessage(Component.text("  " + n, NamedTextColor.WHITE)));
    }

    private void generate(CommandSender sender) {
        SchematicService service = requireSchematics(sender);
        if (service == null) {
            return;
        }
        // Dosya yazma I/O — main thread'de yapılmaz
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int count = TestRoomFactory.writeStandardSet(service.getDirectory());
                service.invalidateCache();
                sender.sendMessage(Component.text(count + " test odası üretildi → "
                        + service.getDirectory().getPath(), NamedTextColor.GREEN));
            } catch (Exception e) {
                sender.sendMessage(Component.text("Üretim başarısız: " + e.getMessage(), NamedTextColor.RED));
                plugin.getLogger().warning("Test odası üretimi başarısız: " + e);
            }
        });
    }

    private void paste(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Kullanım: /" + label + " paste <schematic> [0|90|180|270]",
                    NamedTextColor.RED));
            return;
        }
        SchematicService service = requireSchematics(sender);
        World world = requireWorld(sender);
        if (service == null || world == null) {
            return;
        }

        int rotation = 0;
        if (args.length >= 3) {
            try {
                rotation = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Rotation sayı olmalı: " + args[2], NamedTextColor.RED));
                return;
            }
            if (Math.floorMod(rotation, 90) != 0) {
                sender.sendMessage(Component.text("Rotation 90'ın katı olmalı: " + rotation,
                        NamedTextColor.RED));
                return;
            }
        }

        String name = args[1];
        GridSlot slot = plugin.getSlotManager().allocate();
        int rot = rotation;

        sender.sendMessage(Component.text("Yükleniyor: " + name + " → " + slot, NamedTextColor.GRAY));

        service.load(name)
                .thenCompose((Clipboard clipboard) -> service.paste(clipboard, world,
                        slot.originX() + slot.size() / 2,
                        slot.originY(),
                        slot.originZ() + slot.size() / 2,
                        rot, false))
                .whenComplete((millis, error) -> {
                    if (error != null) {
                        // Paste patladıysa slot'u tutmanın anlamı yok
                        plugin.getSlotManager().release(slot.index());
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        sender.sendMessage(Component.text("Paste başarısız: " + cause.getMessage(),
                                NamedTextColor.RED));
                        plugin.getLogger().warning("Paste başarısız (" + name + "): " + cause);
                        return;
                    }
                    sender.sendMessage(Component.text("Paste tamam: " + name + " rot=" + rot
                            + " " + slot + " (" + millis + " ms)", NamedTextColor.GREEN));
                    teleportToSlot(sender, world, slot);
                });
    }

    /** Paste async bitmiş olabilir; teleport her zaman main thread'de yapılmalı. */
    private void teleportToSlot(CommandSender sender, World world, GridSlot slot) {
        if (!(sender instanceof Player player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Location target = slot.center(world).add(0, 1, 0);
            player.teleport(target);
            player.sendMessage(Component.text("Odanın merkezine ışınlandın.", NamedTextColor.GRAY));
        });
    }

    private void slots(CommandSender sender) {
        GridSlotManager manager = plugin.getSlotManager();
        if (manager.allocatedCount() == 0) {
            sender.sendMessage(Component.text("Ayrılmış slot yok.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Ayrılmış slot (" + manager.allocatedCount() + "):",
                NamedTextColor.GRAY));
        manager.allocated().forEach(s ->
                sender.sendMessage(Component.text("  " + s, NamedTextColor.WHITE)));
    }

    private void free(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Kullanım: /" + label + " free <index|all>", NamedTextColor.RED));
            return;
        }
        GridSlotManager manager = plugin.getSlotManager();
        if (args[1].equalsIgnoreCase("all")) {
            int count = manager.allocatedCount();
            manager.releaseAll();
            sender.sendMessage(Component.text(count + " slot serbest bırakıldı. "
                    + "(Bloklar silinmez — temizlik FAZ 2'de.)", NamedTextColor.GREEN));
            return;
        }
        try {
            int index = Integer.parseInt(args[1]);
            boolean released = manager.release(index);
            sender.sendMessage(released
                    ? Component.text("slot#" + index + " serbest bırakıldı. (Bloklar silinmez.)",
                            NamedTextColor.GREEN)
                    : Component.text("slot#" + index + " zaten ayrılmış değil.", NamedTextColor.YELLOW));
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Geçersiz index: " + args[1], NamedTextColor.RED));
        }
    }

    private @Nullable Player asPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Component.text("Bu komut oyuncu tarafından çalıştırılmalı.", NamedTextColor.RED));
        return null;
    }

    private @Nullable World requireWorld(CommandSender sender) {
        World world = plugin.getWorldManager() == null ? null : plugin.getWorldManager().getWorld();
        if (world == null) {
            sender.sendMessage(Component.text("Dungeon dünyası yüklü değil — konsol loglarına bak.",
                    NamedTextColor.RED));
        }
        return world;
    }

    private @Nullable SchematicService requireSchematics(CommandSender sender) {
        SchematicService service = plugin.getSchematicService();
        if (service == null) {
            sender.sendMessage(Component.text("Schematic servisi kapalı — WorldEdit ya da FAWE kurulu değil.",
                    NamedTextColor.RED));
        }
        return service;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUB_COMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && sub.equals("paste")) {
            SchematicService service = plugin.getSchematicService();
            if (service == null) {
                return List.of();
            }
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return service.list().stream()
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 3 && sub.equals("paste")) {
            return ROTATIONS.stream().filter(r -> r.startsWith(args[2])).toList();
        }
        if (args.length == 2 && sub.equals("free")) {
            List<String> options = new ArrayList<>();
            options.add("all");
            plugin.getSlotManager().allocated().forEach(s -> options.add(String.valueOf(s.index())));
            return options.stream().filter(o -> o.startsWith(args[1])).toList();
        }
        return List.of();
    }
}
