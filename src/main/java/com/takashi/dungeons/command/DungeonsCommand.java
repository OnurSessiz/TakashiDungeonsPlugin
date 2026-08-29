package com.takashi.dungeons.command;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.DoorAnchor;
import com.takashi.dungeons.generation.DungeonGenerator;
import com.takashi.dungeons.generation.DungeonSize;
import com.takashi.dungeons.generation.LayoutNode;
import com.takashi.dungeons.generation.PlacedRoom;
import com.takashi.dungeons.generation.RoomLibrary;
import com.takashi.dungeons.hud.HudService;
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
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                    "gen", "paste", "connect", "dungeon", "slots", "free", "reload", "hud");

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
            case "slots" -> slots(sender);
            case "free" -> free(sender, label, args);
            case "hud" -> hud(sender, label, args);
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
            player.teleport(target);
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
                player.teleport(new Location(world, target.x() + 0.5, target.y(), target.z() + 0.5)));
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
     * <p>The phase 1D milestone — {@code generation.md} §6 and §7.
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

        double turnBias = plugin.getConfig().getDouble("generation.turn-bias", 2.0);
        int maxAttempts = plugin.getConfig().getInt("generation.max-attempts", 8);
        boolean doPlug = plugin.getConfig().getBoolean("generation.plug-open-doors", true);

        GridSlot slot = plugin.getSlotManager().allocate();
        Aabb slotBounds = slotBounds(slot, world);
        Vec3i center = new Vec3i(slot.originX() + slot.size() / 2, slot.originY(),
                slot.originZ() + slot.size() / 2);

        sender.sendMessage(Component.text("Üretiliyor: tema=" + theme + ", " + size.key()
                + ", seed=" + seed, NamedTextColor.GRAY));

        store.loadAll(store.list(theme))
                .thenApply(templates -> {
                    RoomLibrary library = new RoomLibrary(templates);
                    if (!library.isUsable()) {
                        throw new IllegalStateException(library.describeProblem());
                    }
                    return new DungeonGenerator(library, turnBias, maxAttempts)
                            .generate(slotBounds, center, size, seed);
                })
                .thenCompose(result -> pasteDungeon(service, world, result)
                        .thenApply(ignored -> result))
                .thenCompose(result -> plugDoors(world, result, doPlug)
                        .thenApply(report -> Map.entry(result, report)))
                .whenComplete((pair, error) -> {
                    if (error != null) {
                        plugin.getSlotManager().release(slot.index());
                        sendFailure(sender, "Üretim başarısız", error);
                        return;
                    }
                    reportDungeon(sender, world, slot, theme, pair.getKey(), pair.getValue());
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

    /** Pastes the rooms one after another — chained so the order stays deterministic. */
    private CompletableFuture<Long> pasteDungeon(SchematicService service, World world,
                                                 DungeonGenerator.Result result) {
        CompletableFuture<Long> chain = CompletableFuture.completedFuture(0L);
        for (LayoutNode node : result.layout().nodes()) {
            PlacedRoom room = node.room();
            chain = chain.thenCompose(ignored -> service.load(room.template().name())
                    .thenCompose(clip -> pasteAt(service, world, clip, room)));
        }
        return chain;
    }

    /**
     * Plugging — only AFTER the pastes are done. Done earlier, the next room's paste would
     * overwrite the plug; and whether a door is left open is only known once the graph is
     * complete.
     */
    private CompletableFuture<DoorPlugger.Report> plugDoors(World world,
                                                            DungeonGenerator.Result result,
                                                            boolean enabled) {
        DoorPlugger plugger = plugin.getDoorPlugger();
        if (!enabled || plugger == null || result.layout().isEmpty()) {
            return CompletableFuture.completedFuture(new DoorPlugger.Report(0, 0, 0, List.of()));
        }
        return plugger.plugAll(world, result.plugTargets());
    }

    private void reportDungeon(CommandSender sender, World world, GridSlot slot, String theme,
                               DungeonGenerator.Result result, DoorPlugger.Report plug) {
        sender.sendMessage(Component.text("Dungeon üretildi — " + slot, NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  tema: " + theme, NamedTextColor.GRAY));
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

        LayoutNode root = result.layout().root();
        if (root != null) {
            teleportTo(sender, world, root.room().origin().plus(new Vec3i(0, 1, 0)));
        }
    }

    /**
     * The slot's 3D bounds. X/Z come from the slot, Y from the world's height limits.
     *
     * <p>The X/Z bound is a hard requirement: a room that overflows reaches into a neighbouring
     * instance's blocks. There is no slot concept on Y, so the world limits suffice.
     */
    private static Aabb slotBounds(GridSlot slot, World world) {
        return new Aabb(
                slot.originX(), world.getMinHeight(), slot.originZ(),
                slot.originX() + slot.size() - 1, world.getMaxHeight() - 1,
                slot.originZ() + slot.size() - 1);
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
        if (args.length == 2 && sub.equals("hud")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return HUD_SETTINGS.stream().filter(o -> o.startsWith(prefix)).toList();
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
