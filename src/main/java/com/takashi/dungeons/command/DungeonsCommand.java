package com.takashi.dungeons.command;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.DoorAnchor;
import com.takashi.dungeons.generation.DungeonGenerator;
import com.takashi.dungeons.generation.DungeonSize;
import com.takashi.dungeons.generation.PlacedRoom;
import com.takashi.dungeons.generation.RoomLibrary;
import com.takashi.dungeons.hud.HudService;
import com.takashi.dungeons.instance.DungeonInstance;
import com.takashi.dungeons.instance.InstanceManager;
import com.takashi.dungeons.mob.Difficulty;
import com.takashi.dungeons.mob.MobClass;
import com.takashi.dungeons.mob.MobDefinition;
import com.takashi.dungeons.mob.MobProvider;
import com.takashi.dungeons.mob.MobRegistry;
import com.takashi.dungeons.mob.MobService;
import com.takashi.dungeons.portal.DungeonPortal;
import com.takashi.dungeons.portal.PortalKind;
import com.takashi.dungeons.portal.PortalManager;
import com.takashi.dungeons.portal.PortalState;
import com.takashi.dungeons.schematic.BundledRooms;
import com.takashi.dungeons.schematic.DoorPlugger;
import com.takashi.dungeons.generation.RoomTemplate;
import com.takashi.dungeons.generation.RoomTemplateStore;
import com.takashi.dungeons.generation.Rotation;
import com.takashi.dungeons.generation.Vec3i;
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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /tdungeons} — the administration and phase 1 verification command.
 *
 * <p>The {@code gen}/{@code paste}/{@code free} subcommands here are for development: they
 * exist to trigger the generation chain (allocate a slot → load a schematic → paste) by hand
 * and check it. Player-facing dungeon commands (join/leave) arrive in phase 2.
 *
 * <p>Note: the messages this class sends are still Turkish. They are the operator-facing
 * message set and will be translated as a whole, together with the {@code messages.yml}
 * extraction, rather than piecemeal.
 */
public final class DungeonsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS =
            List.of("version", "status", "world", "list", "themes", "rooms", "room", "weights",
                    "gen", "paste", "connect", "dungeon", "instances", "enter", "leave", "close",
                    "portal", "mob", "slots", "free", "reload", "hud", "extract");

    private static final List<String> PORTAL_ACTIONS = List.of("create", "list", "remove", "tp");

    private static final List<String> MOB_ACTIONS =
            List.of("list", "info", "spawn", "providers", "reload");

    private static final List<String> DIFFICULTIES = List.of("easy", "medium", "hard");

    private static final List<String> SIZES = List.of("small", "medium", "large");

    private static final List<String> ROTATIONS = List.of("0", "90", "180", "270");

    private static final List<String> HUD_SETTINGS = List.of("name", "ip");

    /** Door index suggestions for tab-complete; the test room with the most doors has 4. */
    private static final List<String> DOOR_INDICES = List.of("0", "1", "2", "3");

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
            case "themes" -> themes(sender);
            case "rooms" -> rooms(sender);
            case "room" -> room(sender, label, args);
            case "gen" -> generate(sender);
            case "paste" -> paste(sender, label, args);
            case "connect" -> connect(sender, label, args);
            case "reload" -> reload(sender);
            case "weights" -> weights(sender, args);
            case "dungeon" -> dungeon(sender, label, args);
            case "instances" -> instances(sender);
            case "enter" -> enter(sender, label, args);
            case "leave" -> leave(sender);
            case "close" -> close(sender, label, args);
            case "portal" -> portal(sender, label, args);
            case "mob" -> mob(sender, label, args);
            case "slots" -> slots(sender);
            case "free" -> free(sender, label, args);
            case "hud" -> hud(sender, label, args);
            case "extract" -> extract(sender, args);
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

        if (plugin.getWorldManager() != null) {
            sender.sendMessage(Component.text("  açılışta sıfırlama: ", NamedTextColor.GRAY)
                    .append(plugin.getWorldManager().isResetOnStart()
                            ? Component.text("açık (" + plugin.getWorldManager().getResetFiles()
                                    + " dosya silindi)", NamedTextColor.GREEN)
                            : Component.text("kapalı — eski dungeon'lar diskte birikir",
                                    NamedTextColor.YELLOW)));
        }

        GridSlotManager slots = plugin.getSlotManager();
        if (slots != null) {
            sender.sendMessage(Component.text("Slot: ", NamedTextColor.GRAY)
                    .append(Component.text(slots.allocatedCount() + " ayrılmış, kenar "
                            + slots.slotSize() + " blok", NamedTextColor.WHITE)));
        }

        InstanceManager instances = plugin.getInstanceManager();
        if (instances != null) {
            sender.sendMessage(Component.text("Instance: ", NamedTextColor.GRAY)
                    .append(Component.text(instances.count() + " açık", NamedTextColor.WHITE)));
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
        // Through the manager, so the plugin's own move is not stopped by its own teleport block.
        plugin.getInstanceManager().teleportInternal(player, new Location(world, 0.5, 65, 0.5));
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
        // Writing files is I/O — never on the main thread
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int count = TestRoomFactory.writeStandardSet(service.getDirectory());
                service.invalidateCache();
                // The template cache sits ON TOP of the clipboard cache; clearing only the
                // lower one would leave stale door metadata in memory.
                RoomTemplateStore store = plugin.getTemplateStore();
                if (store != null) {
                    store.invalidateCache();
                }
                sender.sendMessage(Component.text(count + " test odası (.schem + .yml) üretildi → "
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
                        // No point holding the slot if the paste blew up
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

    /** The paste may have finished async; a teleport must always run on the main thread. */
    private void teleportToSlot(CommandSender sender, World world, GridSlot slot) {
        if (!(sender instanceof Player player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Location target = slot.center(world).add(0, 1, 0);
            plugin.getInstanceManager().teleportInternal(player, target);
            player.sendMessage(Component.text("Odanın merkezine ışınlandın.", NamedTextColor.GRAY));
        });
    }

    // ---------------------------------------------------------------- Phase 1B: room model

    /** Lists the templates in the folder together with their metadata. */
    private void rooms(CommandSender sender) {
        RoomTemplateStore store = requireTemplates(sender);
        if (store == null) {
            return;
        }
        List<String> names = store.list();
        if (names.isEmpty()) {
            sender.sendMessage(Component.text("Oda yok \u2014 /tdungeons gen ile test odasi uret.",
                    NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Oda sablonlari (" + names.size() + "):", NamedTextColor.GRAY));
        store.loadAll(names).whenComplete((templates, error) -> {
            if (error != null) {
                sendFailure(sender, "Sablon yuklenemedi", error);
                return;
            }
            for (RoomTemplate t : templates) {
                String walls = t.doors().isEmpty()
                        ? "kapisiz"
                        : t.doors().stream().map(d -> d.wall().displayName())
                                .reduce((a, b) -> a + "+" + b).orElse("");
                sender.sendMessage(Component.text("  " + t.name(), NamedTextColor.WHITE)
                        .append(Component.text("  " + t.type().yamlValue()
                                + "  agirlik=" + t.weight()
                                + "  " + t.describeSize()
                                + "  kapi=" + t.doorCount() + " (" + walls + ")", NamedTextColor.GRAY)));
            }
        });
    }

    /** Dumps one template in resolved form — for verifying its metadata. */
    private void room(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Kullanim: /" + label + " room <oda>", NamedTextColor.RED));
            return;
        }
        RoomTemplateStore store = requireTemplates(sender);
        if (store == null) {
            return;
        }
        store.load(args[1]).whenComplete((t, error) -> {
            if (error != null) {
                sendFailure(sender, "Sablon yuklenemedi", error);
                return;
            }
            sender.sendMessage(Component.text("Oda: " + t.name(), NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  type: " + t.type().yamlValue()
                    + "   weight: " + t.weight(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  boyut: " + t.describeSize()
                    + "   kutu (origin'e gore): " + t.localBox(), NamedTextColor.GRAY));
            if (t.doors().isEmpty()) {
                sender.sendMessage(Component.text("  kapi yok \u2014 bu oda grafa baglanamaz.",
                        NamedTextColor.YELLOW));
                return;
            }
            sender.sendMessage(Component.text("  doors:", NamedTextColor.GRAY));
            for (DoorAnchor d : t.doors()) {
                sender.sendMessage(Component.text("    #" + d.index() + " " + d.local()
                        + " -> " + d.wall().displayName() + " duvari", NamedTextColor.WHITE));
            }
        });
    }

    /**
     * Attaches two rooms to each other through their doors.
     *
     * <p>The parent is placed at the slot centre with rot=0; the child's rotation and position
     * are <b>computed</b> by {@link RoomTemplate#attachTo}, not searched for. The output also
     * reports that the boxes do not intersect: under the back-to-back convention the two rooms
     * must share no block at all ({@code generation.md} §5.2).
     */
    private void connect(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text(
                    "Kullanim: /" + label + " connect <ebeveyn> <cocuk> [parentDoor] [childDoor]",
                    NamedTextColor.RED));
            return;
        }
        RoomTemplateStore store = requireTemplates(sender);
        SchematicService service = requireSchematics(sender);
        World world = requireWorld(sender);
        if (store == null || service == null || world == null) {
            return;
        }

        int parentDoor;
        int childDoor;
        try {
            parentDoor = args.length >= 4 ? Integer.parseInt(args[3]) : 0;
            childDoor = args.length >= 5 ? Integer.parseInt(args[4]) : 0;
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Kapi indeksi sayi olmali.", NamedTextColor.RED));
            return;
        }

        GridSlot slot = plugin.getSlotManager().allocate();
        Vec3i slotCenter = new Vec3i(
                slot.originX() + slot.size() / 2, slot.originY(), slot.originZ() + slot.size() / 2);

        store.load(args[1])
                .thenCombine(store.load(args[2]), (parentTemplate, childTemplate) -> {
                    PlacedRoom parent = PlacedRoom.of(parentTemplate, Rotation.NONE, slotCenter);
                    PlacedRoom child = childTemplate.attachTo(
                            childDoor, parent.doorAnchor(parentDoor), parent.doorOutward(parentDoor));
                    return new PlacedRoom[]{parent, child};
                })
                .thenCompose(pair -> pasteBoth(service, world, pair).thenApply(ignored -> pair))
                .whenComplete((pair, error) -> {
                    if (error != null) {
                        plugin.getSlotManager().release(slot.index());
                        sendFailure(sender, "Baglanti basarisiz", error);
                        return;
                    }
                    reportConnection(sender, world, slot, pair[0], pair[1], parentDoor, childDoor);
                });
    }

    /** Parent first, then child — chained so the order stays deterministic. */
    private CompletableFuture<Long> pasteBoth(SchematicService service, World world, PlacedRoom[] pair) {
        return service.load(pair[0].template().name())
                .thenCompose(clip -> pasteAt(service, world, clip, pair[0]))
                .thenCompose(ignored -> service.load(pair[1].template().name()))
                .thenCompose(clip -> pasteAt(service, world, clip, pair[1]));
    }

    private CompletableFuture<Long> pasteAt(SchematicService service, World world,
                                            Clipboard clipboard, PlacedRoom room) {
        Vec3i o = room.origin();
        return service.paste(clipboard, world, o.x(), o.y(), o.z(), room.rotation().degrees(), false);
    }

    private void reportConnection(CommandSender sender, World world, GridSlot slot,
                                  PlacedRoom parent, PlacedRoom child, int parentDoor, int childDoor) {
        Vec3i parentAnchor = parent.doorAnchor(parentDoor);
        Vec3i childAnchor = child.doorAnchor(childDoor);
        boolean mated = childAnchor.equals(parent.doorMate(parentDoor));
        boolean overlap = parent.bounds().intersects(child.bounds());

        sender.sendMessage(Component.text("Baglandi \u2014 " + slot, NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  ebeveyn: " + parent
                + "  kapi#" + parentDoor + " " + parentAnchor
                + " " + parent.doorOutward(parentDoor).displayName(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  cocuk:   " + child
                + "  kapi#" + childDoor + " " + childAnchor
                + " " + child.doorOutward(childDoor).displayName(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  rotasyon hesaplandi: R=" + child.rotation().steps()
                + " (" + child.rotation().degrees() + " derece)", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  kutular: " + parent.bounds()
                + "  |  " + child.bounds(), NamedTextColor.DARK_GRAY));

        sender.sendMessage(mated
                ? Component.text("  [OK] kapilar sirt sirta", NamedTextColor.GREEN)
                : Component.text("  [HATA] kapilar hizasiz \u2014 beklenen "
                        + parent.doorMate(parentDoor), NamedTextColor.RED));
        sender.sendMessage(overlap
                ? Component.text("  [HATA] kutular CAKISIYOR", NamedTextColor.RED)
                : Component.text("  [OK] kutular cakismiyor", NamedTextColor.GREEN));

        // The two points where the passage gets verified by block test — from the console,
        // forceload followed by execute if block.
        sender.sendMessage(Component.text("  gecit bloklari: " + parentAnchor + " ve " + childAnchor,
                NamedTextColor.DARK_GRAY));

        teleportTo(sender, world, parentAnchor);
    }

    private void teleportTo(CommandSender sender, World world, Vec3i target) {
        if (!(sender instanceof Player player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.getInstanceManager().teleportInternal(player,
                        new Location(world, target.x() + 0.5, target.y(), target.z() + 0.5)));
    }

    private @Nullable RoomTemplateStore requireTemplates(CommandSender sender) {
        RoomTemplateStore store = plugin.getTemplateStore();
        if (store == null) {
            sender.sendMessage(Component.text("Oda deposu kapali \u2014 WorldEdit ya da FAWE kurulu degil.",
                    NamedTextColor.RED));
        }
        return store;
    }

    /** The future chain wraps errors in {@code CompletionException}; show the real cause. */
    private void sendFailure(CommandSender sender, String prefix, Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        sender.sendMessage(Component.text(prefix + ": " + cause.getMessage(), NamedTextColor.RED));
        plugin.getLogger().warning(prefix + ": " + cause);
    }

    // ------------------------------------------------- Phase 1C: selection + collision

    /**
     * Shows the candidate pool's weight distribution — a by-eye check of the decision in
     * {@code generation.md} §5.4.
     *
     * <p>The percentages must be independent of door count: a 4-door room counts its weight
     * once. This command demonstrates that what the config says and what the engine does are
     * the same thing.
     */
    private void weights(CommandSender sender, String[] args) {
        RoomTemplateStore store = requireTemplates(sender);
        if (store == null) {
            return;
        }
        String theme = resolveTheme(sender, store, args.length >= 2 ? args[1] : null);
        if (theme == null) {
            return;
        }
        store.loadAll(store.list(theme)).whenComplete((templates, error) -> {
            if (error != null) {
                sendFailure(sender, "Şablonlar yüklenemedi", error);
                return;
            }
            RoomLibrary library = new RoomLibrary(templates);
            sender.sendMessage(Component.text(
                    "Aday havuzu — tema " + theme + " (giris/boss hariç: onlar atanıyor, seçilmiyor):",
                    NamedTextColor.GOLD));
            if (!library.isUsable()) {
                sender.sendMessage(Component.text("  " + library.describeProblem(),
                        NamedTextColor.RED));
                return;
            }
            RoomLibrary.describeDistribution(library.normalPool()).forEach(line ->
                    sender.sendMessage(Component.text("  " + line, NamedTextColor.WHITE)));
            sender.sendMessage(Component.text(
                    "  ağırlık ŞABLONA ait, (şablon x kapı) çiftine değil — generation.md 5.4",
                    NamedTextColor.DARK_GRAY));

            if (!library.entrances().isEmpty()) {
                sender.sendMessage(Component.text("  giriş odaları: " + library.entrances().size()
                        + "   boss odaları: " + library.bosses().size(), NamedTextColor.GRAY));
            } else {
                sender.sendMessage(Component.text(
                        "  giriş odası yok — normal havuzdan seçilecek (fallback)",
                        NamedTextColor.YELLOW));
            }
        });
    }

    /**
     * Generates a full dungeon: critical path → boss → side branches → paste → plugs.
     *
     * <p>The phase 1D milestone — {@code generation.md} §6 and §7. Since phase 2A the result is
     * a registered {@link DungeonInstance} rather than loose blocks: it has an id, it can be
     * listed, and closing it takes its blocks with it.
     */
    private void dungeon(CommandSender sender, String label, String[] args) {
        RoomTemplateStore store = requireTemplates(sender);
        SchematicService service = requireSchematics(sender);
        World world = requireWorld(sender);
        if (store == null || service == null || world == null) {
            return;
        }

        // A theme name is whatever occupies arg 1 and is not a size. That makes the pre-theme
        // form `dungeon medium 1337` keep working on a single-theme install; the cost is that a
        // theme literally called "small" is unreachable, which is not worth guarding against.
        String themeArg = null;
        int next = 1;
        if (args.length >= 2 && DungeonSize.parse(args[1]) == null) {
            themeArg = args[1];
            next = 2;
        }
        String theme = resolveTheme(sender, store, themeArg);
        if (theme == null) {
            return;
        }

        DungeonSize size = args.length > next ? DungeonSize.parse(args[next]) : DungeonSize.MEDIUM;
        if (size == null) {
            sender.sendMessage(Component.text("Kullanım: /" + label
                    + " dungeon <tema> [small|medium|large] [seed]", NamedTextColor.RED));
            return;
        }
        long seed;
        try {
            seed = args.length > next + 1
                    ? Long.parseLong(args[next + 1])
                    : new Random().nextLong();
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Seed sayı olmalı: " + args[next + 1],
                    NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("Üretiliyor: tema=" + theme + ", " + size.key()
                + ", seed=" + seed, NamedTextColor.GRAY));

        plugin.getInstanceManager().create(theme, size, seed)
                .whenComplete((instance, error) -> {
                    if (error != null) {
                        sendFailure(sender, "Üretim başarısız", error);
                        return;
                    }
                    reportDungeon(sender, world, instance);
                });
    }

    /**
     * Resolves the theme to generate from.
     *
     * <p>With exactly one theme on disk that theme is implied, so a single-theme install never
     * has to type it. With several, the theme is <b>required</b>: silently picking one produces
     * the "why did I get crypt rooms" class of bug, which costs an hour to trace and looks like
     * a generation fault rather than a wrong pool.
     *
     * @return the theme, or {@code null} after the reason has been reported to the sender
     */
    private @Nullable String resolveTheme(CommandSender sender, RoomTemplateStore store,
                                          @Nullable String requested) {
        List<String> themes = store.themes();
        if (themes.isEmpty()) {
            sender.sendMessage(Component.text("Hiç oda yok — /tdungeons gen ile test odası üret, "
                    + "ya da schematics/ altına bir tema klasörü aç.", NamedTextColor.YELLOW));
            return null;
        }
        if (requested != null) {
            for (String theme : themes) {
                if (theme.equalsIgnoreCase(requested)) {
                    return theme;
                }
            }
            sender.sendMessage(Component.text("Tema yok: " + requested + "   mevcut: "
                    + String.join(", ", themes), NamedTextColor.RED));
            return null;
        }
        if (themes.size() == 1) {
            return themes.get(0);
        }
        sender.sendMessage(Component.text("Tema belirt — mevcut: " + String.join(", ", themes),
                NamedTextColor.RED));
        return null;
    }

    /** Lists the themes with their pool composition, and whether each can actually generate. */
    private void themes(CommandSender sender) {
        RoomTemplateStore store = requireTemplates(sender);
        if (store == null) {
            return;
        }
        List<String> themes = store.themes();
        if (themes.isEmpty()) {
            sender.sendMessage(Component.text("Tema yok — schematics/ boş.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Temalar (" + themes.size() + "):", NamedTextColor.GOLD));
        for (String theme : themes) {
            store.loadAll(store.list(theme)).whenComplete((templates, error) -> {
                if (error != null) {
                    sendFailure(sender, "  " + theme + " yüklenemedi", error);
                    return;
                }
                RoomLibrary library = new RoomLibrary(templates);
                sender.sendMessage(Component.text("  " + theme, NamedTextColor.WHITE)
                        .append(Component.text("  " + templates.size() + " oda"
                                + "  giriş=" + library.entrances().size()
                                + "  boss=" + library.bosses().size()
                                + "  normal=" + library.normalPool().size()
                                + " (çok kapılı=" + library.branchingPool().size() + ")",
                                NamedTextColor.GRAY)));
                if (!library.isUsable()) {
                    sender.sendMessage(Component.text("    [HATA] " + library.describeProblem(),
                            NamedTextColor.RED));
                    return;
                }
                if (library.entrances().isEmpty()) {
                    sender.sendMessage(Component.text(
                            "    [UYARI] giriş odası yok — normal havuzdan seçilecek",
                            NamedTextColor.YELLOW));
                }
                if (library.bosses().isEmpty()) {
                    sender.sendMessage(Component.text(
                            "    [UYARI] boss odası yok — dungeon boss'suz üretilir",
                            NamedTextColor.YELLOW));
                }
            });
        }
    }

    /**
     * Re-reads schematics and metadata from disk.
     *
     * <p>The room-building loop is: export a schematic, write its {@code .yml}, check it. Without
     * this the check needs a server restart — and worse, the clipboard cache would keep serving
     * the previous version of a room that was just re-exported, so the check would silently
     * pass on stale geometry.
     */
    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        // The HUD is reloaded before the early return below: it has no WorldEdit dependency,
        // so a server without WorldEdit must still be able to reload its sidebar.
        if (plugin.getHudService() != null) {
            plugin.getHudService().reload();
            sender.sendMessage(Component.text("HUD yeniden yüklendi.", NamedTextColor.GREEN));
        }
        // Mobs reload here too, and for the same reason: mobs.yml has no WorldEdit dependency, so
        // a server without it must still be able to fix a typo in its mob set without a restart.
        if (plugin.getMobRegistry() != null) {
            mobReload(sender);
        }

        SchematicService service = requireSchematics(sender);
        RoomTemplateStore store = plugin.getTemplateStore();
        if (service == null || store == null) {
            return;
        }
        // The template cache sits ON TOP of the clipboard cache; clearing only the lower one
        // would leave stale door metadata in memory.
        service.invalidateCache();
        store.invalidateCache();

        List<String> themes = service.themes();
        sender.sendMessage(Component.text("Yeniden yüklendi — " + service.list().size() + " oda, "
                + themes.size() + " tema (" + String.join(", ", themes) + ")",
                NamedTextColor.GREEN));
    }

    private void reportDungeon(CommandSender sender, World world, DungeonInstance instance) {
        DungeonGenerator.Result result = instance.result();
        DoorPlugger.Report plug = instance.plugReport();

        sender.sendMessage(Component.text("Dungeon üretildi — instance#" + instance.id()
                + " @ " + instance.slot(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  tema: " + instance.theme(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  boyut: " + result.size().key()
                + "   oda: " + result.rooms() + "/" + result.targetRooms()
                + "   kritik path: " + result.pathLength() + "/" + result.targetPathLength(),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  deneme: " + result.attemptsUsed()
                + "   tıpa: " + plug.plugged() + " kapı / " + plug.blocks() + " blok"
                + (plug.skipped() > 0 ? "   atlanan: " + plug.skipped() : ""),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  seed: " + result.seed()
                + "  (tema + boyut + seed aynıysa dungeon da aynı)", NamedTextColor.DARK_GRAY));

        if (result.warning() != null) {
            sender.sendMessage(Component.text("  uyarı: " + result.warning(),
                    NamedTextColor.YELLOW));
        }
        plug.warnings().forEach(w -> sender.sendMessage(
                Component.text("  tıpa uyarısı: " + w, NamedTextColor.YELLOW)));

        DungeonGenerator.describe(result.layout(), result.bossNodeId()).forEach(line ->
                sender.sendMessage(Component.text("  " + line, NamedTextColor.WHITE)));

        List<String> problems = result.layout().validate();
        if (problems.isEmpty()) {
            sender.sendMessage(Component.text(
                    "  [OK] yerleşim tutarlı: çakışma yok, geçitler hizalı, graf bağlı",
                    NamedTextColor.GREEN));
        } else {
            problems.forEach(pr -> sender.sendMessage(
                    Component.text("  [HATA] " + pr, NamedTextColor.RED)));
        }

        Aabb box = instance.bounds();
        sender.sendMessage(Component.text("  temizlenecek hacim: " + box
                + "  (" + box.volume() + " blok)", NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text("  süre: "
                + InstanceManager.formatDuration(instance.remainingMillis()),
                NamedTextColor.DARK_GRAY));

        // The generator goes IN, not merely to the coordinates. Being teleported to a slot makes
        // you a bystander standing in one; entering makes you a member — which is what the
        // countdown, the boss bar and the expiry teleport all run on.
        if (sender instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> plugin.getInstanceManager().enter(player, instance));
        }
    }

    // ------------------------------------------------------- Phase 2A: instance lifecycle

    /** Lists the live instances: what stands, where, and for how long. */
    private void instances(CommandSender sender) {
        InstanceManager manager = plugin.getInstanceManager();
        List<DungeonInstance> live = manager.all();
        if (live.isEmpty()) {
            sender.sendMessage(Component.text("Açık instance yok.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Açık instance (" + live.size() + "):",
                NamedTextColor.GOLD));
        for (DungeonInstance instance : live) {
            sender.sendMessage(Component.text("  #" + instance.id(), NamedTextColor.WHITE)
                    .append(Component.text("  " + instance.theme()
                            + "/" + instance.result().size().key()
                            + "  " + instance.result().rooms() + " oda"
                            + "  " + instance.slot()
                            + "  " + instance.state(), NamedTextColor.GRAY))
                    .append(Component.text("  kalan "
                            + InstanceManager.formatDuration(instance.remainingMillis()),
                            NamedTextColor.YELLOW))
                    .append(Component.text("  oyuncu " + instance.playerCount(),
                            NamedTextColor.GRAY)));
        }
        sender.sendMessage(Component.text("  /tdungeons enter <id> | leave | close <id|all>",
                NamedTextColor.DARK_GRAY));
    }

    /**
     * {@code /tdungeons enter <id>} — the way in until the entry object exists (phase 2C).
     *
     * <p>Entering is what makes a player a <b>member</b>, and membership is what the countdown,
     * the expiry teleport and the boss bar all run on. Walking into a slot by teleport does not
     * do that — which is the point: the object will be the only door.
     */
    private void enter(CommandSender sender, String label, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Kullanım: /" + label + " enter <id>",
                    NamedTextColor.RED));
            return;
        }
        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Geçersiz id: " + args[1], NamedTextColor.RED));
            return;
        }
        InstanceManager manager = plugin.getInstanceManager();
        DungeonInstance instance = manager.get(id);
        if (instance == null) {
            sender.sendMessage(Component.text("instance#" + id + " yok.", NamedTextColor.YELLOW));
            return;
        }
        if (!manager.enter(player, instance)) {
            sender.sendMessage(Component.text("instance#" + id + " girilebilir durumda değil: "
                    + instance.state(), NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("instance#" + id + " içindesin — kalan "
                + InstanceManager.formatDuration(instance.remainingMillis()), NamedTextColor.GREEN));
    }

    /** {@code /tdungeons leave} — out, and back to where you came in from. */
    private void leave(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        InstanceManager manager = plugin.getInstanceManager();
        DungeonInstance instance = manager.instanceOf(player);
        if (instance == null) {
            sender.sendMessage(Component.text("Bir dungeon içinde değilsin.", NamedTextColor.YELLOW));
            return;
        }
        manager.leave(player, instance);
        sender.sendMessage(Component.text("instance#" + instance.id() + " terk edildi.",
                NamedTextColor.GREEN));
    }

    /**
     * {@code /tdungeons close <id|all>} — the teardown that phase 1 lacked.
     *
     * <p>This is what {@code free} was never able to do: {@code free} hands the slot index back
     * while the blocks stay, so the next dungeon is pasted on top of the previous one. Closing
     * evicts, wipes, unloads and only then releases.
     */
    private void close(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Kullanım: /" + label + " close <id|all>",
                    NamedTextColor.RED));
            return;
        }
        InstanceManager manager = plugin.getInstanceManager();

        if (args[1].equalsIgnoreCase("all")) {
            int count = manager.count();
            if (count == 0) {
                sender.sendMessage(Component.text("Açık instance yok.", NamedTextColor.YELLOW));
                return;
            }
            sender.sendMessage(Component.text(count + " instance kapatılıyor…", NamedTextColor.GRAY));
            manager.closeAll().whenComplete((ignored, error) -> sender.sendMessage(error == null
                    ? Component.text(count + " instance kapatıldı.", NamedTextColor.GREEN)
                    : Component.text("Kapatma sırasında hata — konsola bak.", NamedTextColor.RED)));
            return;
        }

        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Geçersiz id: " + args[1], NamedTextColor.RED));
            return;
        }
        DungeonInstance instance = manager.get(id);
        if (instance == null) {
            sender.sendMessage(Component.text("instance#" + id + " yok.", NamedTextColor.YELLOW));
            return;
        }
        manager.close(instance).whenComplete((report, error) -> {
            if (error != null) {
                sendFailure(sender, "Kapatılamadı", error);
                return;
            }
            sender.sendMessage(Component.text("instance#" + report.id() + " kapatıldı — "
                    + report.blocksCleared() + " blok silindi, "
                    + report.chunksUnloaded() + " chunk boşaltıldı, "
                    + report.entitiesRemoved() + " entity kaldırıldı, "
                    + report.playersEvicted() + " oyuncu çıkarıldı  ("
                    + report.millis() + " ms)", NamedTextColor.GREEN));
        });
    }

    // ---------------------------------------------------------------- Phase 2C: entry object

    /**
     * {@code /tdungeons portal create|list|remove|tp} — the operator side of the entrance objects.
     *
     * <p>Only lobby portals are placed by hand; wild ones are the plugin's own doing
     * ({@code portal.wild}). Placing a wild one on request would be placing a "random discovery"
     * on purpose, which is a contradiction — and the {@code create} form is how you build a lobby.
     */
    private void portal(CommandSender sender, String label, String[] args) {
        PortalManager manager = plugin.getPortalManager();
        if (manager == null) {
            sender.sendMessage(Component.text("Geçit servisi kurulmadı.", NamedTextColor.RED));
            return;
        }
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);

        switch (action) {
            case "list" -> portalList(sender, manager);
            case "create" -> portalCreate(sender, manager, args);
            case "remove" -> portalRemove(sender, manager, label, args);
            case "tp" -> portalTeleport(sender, manager, label, args);
            default -> sender.sendMessage(Component.text("Kullanım: /" + label + " portal <"
                    + String.join("|", PORTAL_ACTIONS) + ">", NamedTextColor.RED));
        }
    }

    private void portalList(CommandSender sender, PortalManager manager) {
        List<DungeonPortal> all = manager.all();
        if (all.isEmpty()) {
            sender.sendMessage(Component.text("Geçit yok. /tdungeons portal create ile lobby "
                    + "geçidi koy.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Geçitler (" + all.size() + "):", NamedTextColor.GOLD));
        for (DungeonPortal portal : all) {
            Component line = Component.text("  #" + portal.id(), NamedTextColor.WHITE)
                    .append(Component.text("  " + portal.kind().displayName()
                            + "  " + portal.size().key()
                            + "  " + portal.block().getWorld().getName() + " "
                            + portal.block().getBlockX() + "," + portal.block().getBlockY() + ","
                            + portal.block().getBlockZ(), NamedTextColor.GRAY))
                    .append(Component.text("  " + portal.state().displayName(),
                            portal.state() == PortalState.READY
                                    ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            if (portal.boundInstanceId() != null) {
                line = line.append(Component.text("  → instance#" + portal.boundInstanceId(),
                        NamedTextColor.DARK_GRAY));
            }
            if (portal.state() == PortalState.COOLDOWN) {
                long remaining = Math.max(0, portal.readyAt() - System.currentTimeMillis());
                line = line.append(Component.text("  (" + InstanceManager.formatDuration(remaining)
                        + ")", NamedTextColor.DARK_GRAY));
            }
            sender.sendMessage(line);
        }
    }

    private void portalCreate(CommandSender sender, PortalManager manager, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        // Everything after "create" is optional: [size] [theme].
        DungeonSize size = args.length >= 3 ? DungeonSize.parse(args[2]) : manager.defaultSize();
        if (size == null) {
            sender.sendMessage(Component.text("Boyut small|medium|large olmalı: " + args[2],
                    NamedTextColor.RED));
            return;
        }
        String theme = args.length >= 4 ? args[3] : null;

        DungeonPortal portal = manager.create(player.getLocation(), PortalKind.LOBBY, theme, size);
        if (portal == null) {
            sender.sendMessage(Component.text("Burada zaten bir geçit var.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Geçit kuruldu: " + portal, NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  Kalıcı olması için config.yml → portal.lobby.points "
                + "altına ekle; geçitler restart'ı atlatmıyor (kalıcılık FAZ 7).",
                NamedTextColor.DARK_GRAY));
    }

    private void portalRemove(CommandSender sender, PortalManager manager, String label,
                              String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Kullanım: /" + label + " portal remove <id|all>",
                    NamedTextColor.RED));
            return;
        }
        if (args[2].equalsIgnoreCase("all")) {
            int count = manager.removeAll();
            sender.sendMessage(Component.text(count + " geçit kaldırıldı.", NamedTextColor.GREEN));
            return;
        }
        DungeonPortal portal = findPortal(sender, manager, args[2]);
        if (portal == null) {
            return;
        }
        manager.remove(portal);
        sender.sendMessage(Component.text("portal#" + portal.id() + " kaldırıldı.",
                NamedTextColor.GREEN));
    }

    private void portalTeleport(CommandSender sender, PortalManager manager, String label,
                                String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Kullanım: /" + label + " portal tp <id>",
                    NamedTextColor.RED));
            return;
        }
        DungeonPortal portal = findPortal(sender, manager, args[2]);
        if (portal == null) {
            return;
        }
        plugin.getInstanceManager().teleportInternal(player, portal.center().add(0, 1, 0));
        sender.sendMessage(Component.text("portal#" + portal.id() + " konumuna ışınlandın.",
                NamedTextColor.GREEN));
    }

    private @Nullable DungeonPortal findPortal(CommandSender sender, PortalManager manager,
                                               String raw) {
        try {
            DungeonPortal portal = manager.get(Integer.parseInt(raw));
            if (portal == null) {
                sender.sendMessage(Component.text("portal#" + raw + " yok.", NamedTextColor.YELLOW));
            }
            return portal;
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Geçersiz id: " + raw, NamedTextColor.RED));
            return null;
        }
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

    /**
     * {@code /tdungeons free <index|all>} — returns a raw slot index without touching its blocks.
     *
     * <p>Kept for the slots {@code paste} and {@code connect} take by hand, which have no
     * instance behind them. For anything generated by {@code dungeon}, {@code close} is the right
     * command: {@code free} here would leave the dungeon standing while the slot is handed out
     * again, and the next paste would land on top of it.
     */
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
                    + "(Bloklar silinmez — instance için /tdungeons close kullan.)",
                    NamedTextColor.GREEN));
            return;
        }
        try {
            int index = Integer.parseInt(args[1]);
            boolean released = manager.release(index);
            sender.sendMessage(released
                    ? Component.text("slot#" + index + " serbest bırakıldı. (Bloklar silinmez — "
                            + "instance için /tdungeons close kullan.)", NamedTextColor.GREEN)
                    : Component.text("slot#" + index + " zaten ayrılmış değil.", NamedTextColor.YELLOW));
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Geçersiz index: " + args[1], NamedTextColor.RED));
        }
    }

    /**
     * {@code /tdungeons hud [name|ip] <metin>} — the operator side of the sidebar. The name
     * and the IP are written back into config.yml, because they are server settings that have
     * to survive a restart, and every open sidebar is repainted right away.
     */
    private void hud(CommandSender sender, String label, String[] args) {
        HudService hud = plugin.getHudService();
        if (hud == null) {
            sender.sendMessage(Component.text("HUD servisi kurulmadı.", NamedTextColor.RED));
            return;
        }

        if (args.length == 1) {
            sender.sendMessage(Component.text("HUD: ", NamedTextColor.GRAY)
                    .append(hud.isEnabled()
                            ? Component.text("açık", NamedTextColor.GREEN)
                            : Component.text("kapalı (config: hud.enabled)", NamedTextColor.RED)));
            sender.sendMessage(Component.text("  Sunucu adı: ", NamedTextColor.GRAY)
                    .append(Component.text(hud.getServerName(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  Sunucu IP: ", NamedTextColor.GRAY)
                    .append(Component.text(hud.getServerIp(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  Satır: ", NamedTextColor.GRAY)
                    .append(Component.text(hud.lineCount() + " (config: hud.lines)",
                            NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Kullanım: /" + label + " hud <name|ip> <metin>",
                    NamedTextColor.GRAY));
            return;
        }

        String setting = args[1].toLowerCase(Locale.ROOT);
        if (!HUD_SETTINGS.contains(setting)) {
            sender.sendMessage(Component.text("Kullanım: /" + label + " hud <name|ip> <metin>",
                    NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Bir değer yaz: /" + label + " hud " + setting
                    + " <metin>", NamedTextColor.RED));
            return;
        }

        // Everything after the setting name is the value — a server name has spaces in it.
        String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (setting.equals("name")) {
            hud.setServerName(value);
            sender.sendMessage(Component.text("HUD sunucu adı: ", NamedTextColor.GREEN)
                    .append(Component.text(value, NamedTextColor.WHITE)));
        } else {
            hud.setServerIp(value);
            sender.sendMessage(Component.text("HUD sunucu IP: ", NamedTextColor.GREEN)
                    .append(Component.text(value, NamedTextColor.WHITE)));
        }
    }

    /**
     * {@code /tdungeons extract [force]} — unpacks the rooms bundled in the jar again.
     *
     * <p>Enable already does this for what is missing; the command is for the room-building
     * loop, where a rebuilt jar carries a new room and the test server should get it without a
     * restart. {@code force} overwrites what is on disk, and that can eat a fresher local
     * export — so it is never the default and it says what it did.
     */
    private void extract(CommandSender sender, String[] args) {
        BundledRooms bundled = plugin.getBundledRooms();
        if (bundled == null) {
            sender.sendMessage(Component.text("Gömülü oda servisi kurulmadı.", NamedTextColor.RED));
            return;
        }
        boolean force = args.length > 1 && args[1].equalsIgnoreCase("force");
        BundledRooms.Result result = bundled.extract(
                new File(plugin.getDataFolder(), "schematics"), force);

        if (result.total() == 0) {
            sender.sendMessage(Component.text("Jar'da gömülü oda yok.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Gömülü odalar: ", NamedTextColor.GRAY)
                .append(Component.text(result.written() + " yazıldı", NamedTextColor.GREEN))
                .append(Component.text(", " + result.skipped()
                        + (force ? " atlandı" : " zaten vardı"), NamedTextColor.GRAY))
                .append(result.failed() == 0
                        ? Component.empty()
                        : Component.text(", " + result.failed() + " başarısız", NamedTextColor.RED)));

        // New files on disk mean the caches are stale — the same reason /tdungeons reload exists.
        SchematicService service = plugin.getSchematicService();
        RoomTemplateStore store = plugin.getTemplateStore();
        if (result.written() > 0 && service != null && store != null) {
            service.invalidateCache();
            store.invalidateCache();
            sender.sendMessage(Component.text("Cache boşaltıldı — " + service.list().size()
                    + " oda görünüyor.", NamedTextColor.GRAY));
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

    // ------------------------------------------------------------------ mob

    private void mob(CommandSender sender, String label, String[] args) {
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> mobList(sender, args);
            case "info" -> mobInfo(sender, label, args);
            case "spawn" -> mobSpawn(sender, label, args);
            case "providers" -> mobProviders(sender);
            case "reload" -> mobReload(sender);
            default -> sender.sendMessage(Component.text("Kullanım: /" + label + " mob <"
                    + String.join("|", MOB_ACTIONS) + ">", NamedTextColor.RED));
        }
    }

    /**
     * The catalogue, grouped by class, with the disabled entries and their reasons underneath.
     *
     * <p>The disabled block is not an afterthought — it is the whole diagnostic. An operator whose
     * MythicMobs boss never turns up needs one place that says why, and a count without reasons
     * ("3 devre dışı") tells them something is wrong without telling them what.
     */
    private void mobList(CommandSender sender, String[] args) {
        MobRegistry registry = plugin.getMobRegistry();
        MobClass filter = args.length >= 3 ? MobClass.parse(args[2]) : null;
        if (args.length >= 3 && filter == null) {
            sender.sendMessage(Component.text("Bilinmeyen sınıf: " + args[2] + " — geçerli: "
                    + "weak, normal, strong, super_strong, boss", NamedTextColor.RED));
            return;
        }

        if (registry.loadError() != null) {
            sender.sendMessage(Component.text(registry.loadError(), NamedTextColor.RED));
        }
        sender.sendMessage(Component.text("Mob kaydı — " + registry.definitions().size()
                + " kullanılabilir, varsayılan zorluk: " + registry.defaultDifficulty(),
                NamedTextColor.GOLD));

        for (MobClass mobClass : MobClass.values()) {
            if (filter != null && filter != mobClass) {
                continue;
            }
            List<MobDefinition> pool = registry.pool(mobClass);
            sender.sendMessage(Component.text("  " + mobClass.key() + " (" + pool.size() + ")",
                    NamedTextColor.AQUA));
            for (MobDefinition definition : pool) {
                sender.sendMessage(Component.text("    " + definition.id() + " — "
                        + definition.address() + "  w=" + definition.weight()
                        + statSummary(definition), NamedTextColor.GRAY));
            }
        }

        List<MobRegistry.Disabled> disabled = registry.disabled();
        if (!disabled.isEmpty()) {
            sender.sendMessage(Component.text("  devre dışı (" + disabled.size() + "):",
                    NamedTextColor.RED));
            for (MobRegistry.Disabled entry : disabled) {
                sender.sendMessage(Component.text("    " + entry.id() + " (" + entry.address()
                        + ") — " + entry.reason(), NamedTextColor.DARK_RED));
            }
        }
    }

    private String statSummary(MobDefinition definition) {
        StringBuilder text = new StringBuilder();
        if (definition.health() != null) {
            text.append("  hp=").append(definition.health());
        }
        if (definition.damage() != null) {
            text.append("  dmg=").append(definition.damage());
        }
        if (definition.speed() != null) {
            text.append("  spd=").append(definition.speed());
        }
        return text.toString();
    }

    private void mobInfo(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Kullanım: /" + label + " mob info <id>",
                    NamedTextColor.RED));
            return;
        }
        MobRegistry registry = plugin.getMobRegistry();
        MobDefinition definition = registry.definition(args[2]);
        if (definition == null) {
            sender.sendMessage(Component.text("Böyle bir mob yok: " + args[2]
                    + " — /" + label + " mob list", NamedTextColor.RED));
            return;
        }
        MobProvider provider = registry.provider(definition.providerId());
        boolean override = provider != null && definition.resolveStatOverride(provider);

        sender.sendMessage(Component.text(definition.id(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  adres: " + definition.address(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  sınıf: " + definition.mobClass()
                + "   ağırlık: " + definition.weight(), NamedTextColor.GRAY));
        // The resolved value AND where it came from: the difference between "you wrote false" and
        // "MythicMobs definitions default to false" is what an operator is actually asking.
        sender.sendMessage(Component.text("  statOverride: " + override
                + (definition.statOverride() == null
                        ? " (sağlayıcı varsayılanı)" : " (dosyada yazılı)"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  statlar:" + (definition.hasStats()
                ? statSummary(definition) : " yok — mob'un doğal değerleri"), NamedTextColor.GRAY));
        if (definition.hasStats() && !override) {
            sender.sendMessage(Component.text("  ! statOverride kapalı — yukarıdaki statlar "
                    + "UYGULANMAYACAK.", NamedTextColor.YELLOW));
        }
        for (Difficulty difficulty : Difficulty.values()) {
            sender.sendMessage(Component.text("  " + difficulty.key() + ": "
                    + registry.scaling(difficulty), NamedTextColor.DARK_GRAY));
        }
    }

    /**
     * Spawns one mob where the player is looking — the 3A acceptance test.
     *
     * <p>Aimed at a block rather than dropped at the player's feet: a boss that materialises
     * inside you is hard to look at, and the point of this command is to look at it.
     */
    private void mobSpawn(CommandSender sender, String label, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Kullanım: /" + label
                    + " mob spawn <id> [easy|medium|hard]", NamedTextColor.RED));
            return;
        }
        MobService service = plugin.getMobService();
        MobRegistry registry = plugin.getMobRegistry();
        MobDefinition definition = registry.definition(args[2]);
        if (definition == null) {
            sender.sendMessage(Component.text("Böyle bir mob yok: " + args[2]
                    + " — /" + label + " mob list", NamedTextColor.RED));
            return;
        }
        Difficulty difficulty = registry.defaultDifficulty();
        if (args.length >= 4) {
            difficulty = Difficulty.parse(args[3]);
            if (difficulty == null) {
                sender.sendMessage(Component.text("Bilinmeyen zorluk: " + args[3]
                        + " — geçerli: easy, medium, hard", NamedTextColor.RED));
                return;
            }
        }

        var targetBlock = player.getTargetBlockExact(8);
        Location where = targetBlock == null
                ? player.getLocation() : targetBlock.getLocation().add(0.5, 1, 0.5);

        LivingEntity entity = service.spawn(definition, where, difficulty, new Random());
        if (entity == null) {
            sender.sendMessage(Component.text("Doğurulamadı — sağlayıcı reddetti. Konsola bak.",
                    NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(definition.id() + " doğdu (" + difficulty.key()
                + ") — can " + round(entity.getHealth()) + "/"
                + round(entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                        ? entity.getHealth()
                        : entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()),
                NamedTextColor.GREEN));
    }

    private String round(double value) {
        return String.valueOf(Math.round(value * 10) / 10.0);
    }

    private void mobProviders(CommandSender sender) {
        sender.sendMessage(Component.text("Mob sağlayıcıları:", NamedTextColor.GOLD));
        for (MobProvider provider : plugin.getMobRegistry().providers()) {
            boolean up = provider.isAvailable();
            sender.sendMessage(Component.text("  " + provider.id() + " — " + provider.displayName()
                    + ": " + (up ? provider.knownKeys().size() + " mob" : "yok")
                    + "   statOverride varsayılanı: " + provider.defaultStatOverride(),
                    up ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY));
        }
    }

    private void mobReload(CommandSender sender) {
        MobRegistry registry = plugin.getMobRegistry();
        registry.load();
        sender.sendMessage(Component.text("mobs.yml yeniden yüklendi — "
                + registry.definitions().size() + " mob"
                + (registry.disabled().isEmpty() ? "" : ", " + registry.disabled().size()
                        + " devre dışı"), NamedTextColor.GREEN));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUB_COMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        // Positions that expect a schematic name: paste/room at arg 2, connect at args 2 and 3.
        boolean wantsRoomName = (args.length == 2 && (sub.equals("paste") || sub.equals("room")
                || sub.equals("connect")))
                || (args.length == 3 && sub.equals("connect"));
        if (wantsRoomName) {
            SchematicService service = plugin.getSchematicService();
            if (service == null) {
                return List.of();
            }
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return service.list().stream()
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 3 && sub.equals("paste")) {
            return ROTATIONS.stream().filter(r -> r.startsWith(args[2])).toList();
        }
        if (sub.equals("connect") && (args.length == 4 || args.length == 5)) {
            return DOOR_INDICES.stream().filter(d -> d.startsWith(args[args.length - 1])).toList();
        }
        // dungeon: arg 2 is the theme (sizes still offered, for the single-theme shorthand),
        // arg 3 is the size once a theme has been typed. weights takes a theme at arg 2.
        if (args.length == 2 && (sub.equals("dungeon") || sub.equals("weights"))) {
            SchematicService service = plugin.getSchematicService();
            List<String> options = new ArrayList<>(
                    service == null ? List.of() : service.themes());
            if (sub.equals("dungeon")) {
                options.addAll(SIZES);
            }
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 3 && sub.equals("dungeon")) {
            return SIZES.stream().filter(n -> n.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && sub.equals("portal")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return PORTAL_ACTIONS.stream().filter(o -> o.startsWith(prefix)).toList();
        }
        if (args.length == 3 && sub.equals("portal")) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if (action.equals("create")) {
                return SIZES.stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            List<String> options = new ArrayList<>();
            if (action.equals("remove")) {
                options.add("all");
            }
            plugin.getPortalManager().all().forEach(p -> options.add(String.valueOf(p.id())));
            return options.stream().filter(o -> o.startsWith(args[2])).toList();
        }
        if (args.length == 4 && sub.equals("portal")
                && args[1].equalsIgnoreCase("create")) {
            SchematicService service = plugin.getSchematicService();
            String prefix = args[3].toLowerCase(Locale.ROOT);
            return (service == null ? List.<String>of() : service.themes()).stream()
                    .filter(t -> t.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (sub.equals("mob")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (args.length == 2) {
                return MOB_ACTIONS.stream().filter(o -> o.startsWith(prefix)).toList();
            }
            String action = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && (action.equals("info") || action.equals("spawn"))) {
                return plugin.getMobRegistry().definitions().stream().map(MobDefinition::id)
                        .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
            }
            if (args.length == 3 && action.equals("list")) {
                return Arrays.stream(MobClass.values()).map(MobClass::key)
                        .filter(k -> k.startsWith(prefix)).toList();
            }
            if (args.length == 4 && action.equals("spawn")) {
                return DIFFICULTIES.stream().filter(d -> d.startsWith(prefix)).toList();
            }
            return List.of();
        }
        if (args.length == 2 && sub.equals("extract")) {
            return List.of("force").stream()
                    .filter(o -> o.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && sub.equals("hud")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return HUD_SETTINGS.stream().filter(o -> o.startsWith(prefix)).toList();
        }
        if (args.length == 2 && (sub.equals("close") || sub.equals("enter"))) {
            List<String> options = new ArrayList<>();
            if (sub.equals("close")) {
                options.add("all");
            }
            plugin.getInstanceManager().all().forEach(i -> options.add(String.valueOf(i.id())));
            return options.stream().filter(o -> o.startsWith(args[1])).toList();
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
