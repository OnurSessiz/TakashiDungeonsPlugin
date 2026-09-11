package com.takashi.dungeons.command;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.takashi.dungeons.ApiEvents;
import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.api.TakashiDungeonsAPI;
import com.takashi.dungeons.api.event.DungeonCloseEvent;
import com.takashi.dungeons.api.event.DungeonCompleteEvent;
import com.takashi.dungeons.api.event.DungeonCreateEvent;
import com.takashi.dungeons.api.event.DungeonEnterEvent;
import com.takashi.dungeons.api.event.DungeonLeaveEvent;
import com.takashi.dungeons.api.event.DungeonMobKillEvent;
import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.DoorAnchor;
import com.takashi.dungeons.generation.DungeonGenerator;
import com.takashi.dungeons.generation.DungeonSize;
import com.takashi.dungeons.generation.PlacedRoom;
import com.takashi.dungeons.generation.RoomLibrary;
import com.takashi.dungeons.hud.HudService;
import com.takashi.dungeons.instance.DungeonInstance;
import com.takashi.dungeons.instance.InstanceManager;
import com.takashi.dungeons.loot.ItemClass;
import com.takashi.dungeons.loot.LootItem;
import com.takashi.dungeons.loot.LootRegistry;
import com.takashi.dungeons.loot.LootService;
import com.takashi.dungeons.loot.LootTable;
import com.takashi.dungeons.loot.RarityWeights;
import com.takashi.dungeons.mob.Difficulty;
import com.takashi.dungeons.mob.MobClass;
import com.takashi.dungeons.mob.MobDefinition;
import com.takashi.dungeons.mob.MobProvider;
import com.takashi.dungeons.mob.MobRegistry;
import com.takashi.dungeons.mob.MobService;
import com.takashi.dungeons.party.Party;
import com.takashi.dungeons.party.PartyManager;
import com.takashi.dungeons.player.PlayerDataService;
import com.takashi.dungeons.portal.DungeonPortal;
import com.takashi.dungeons.portal.PortalKind;
import com.takashi.dungeons.portal.PortalManager;
import com.takashi.dungeons.portal.PortalState;
import com.takashi.dungeons.schematic.BundledRooms;
import com.takashi.dungeons.storage.StorageService;
import com.takashi.dungeons.shop.KeeperSpec;
import com.takashi.dungeons.shop.ShopCurrency;
import com.takashi.dungeons.shop.ShopEntry;
import com.takashi.dungeons.shop.ShopRegistry;
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
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /tdungeons} — the administration and phase 1 verification command.
 *
 * <p>The {@code gen}/{@code paste}/{@code free} subcommands here are for development: they
 * exist to trigger the generation chain (allocate a slot → load a schematic → paste) by hand
 * and check it. Player-facing dungeon commands (join/leave) arrive in phase 2.
 *
 * <p>Everything this class prints is English and lives in the source rather than in
 * {@code lang/}. That is deliberate ({@code isleyis.md} § Dil Katmanı): what it says is diagnostics
 * — room lists, weight tables, closing reports — a player cannot run these commands, and a line
 * quoted in a bug report has to be greppable in the source.
 */
public final class DungeonsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS =
            List.of("version", "status", "world", "list", "themes", "rooms", "room", "weights",
                    "gen", "paste", "connect", "dungeon", "instances", "enter", "leave", "close",
                    "portal", "mob", "loot", "shop", "parties", "db", "stats", "api", "slots",
                    "free", "reload", "hud", "extract");

    private static final List<String> PORTAL_ACTIONS = List.of("create", "list", "remove", "tp");

    private static final List<String> MOB_ACTIONS =
            List.of("list", "info", "spawn", "providers", "reload");

    private static final List<String> LOOT_ACTIONS =
            List.of("list", "info", "tables", "roll", "give", "reload");

    private static final List<String> SHOP_ACTIONS = List.of("list", "info", "reload");

    private static final List<String> DB_ACTIONS = List.of("status", "flush");

    private static final List<String> API_ACTIONS = List.of("status", "debug");

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
            case "loot" -> loot(sender, label, args);
            case "shop" -> shop(sender, label, args);
            case "parties" -> parties(sender);
            case "db" -> db(sender, label, args);
            case "stats" -> stats(sender, label, args);
            case "api" -> api(sender, label, args);
            case "slots" -> slots(sender);
            case "free" -> free(sender, label, args);
            case "hud" -> hud(sender, label, args);
            case "extract" -> extract(sender, args);
            default -> sender.sendMessage(Component
                    .text("Usage: /" + label + " <" + String.join("|", SUB_COMMANDS) + ">",
                            NamedTextColor.RED));
        }
        return true;
    }

    private void status(CommandSender sender) {
        sender.sendMessage(Component
                .text("TakashiDungeons v" + plugin.getPluginMeta().getVersion(), NamedTextColor.GOLD));

        sender.sendMessage(Component.text("Integrations:", NamedTextColor.GRAY));
        plugin.getIntegrations().forEach((name, present) -> sender.sendMessage(Component
                .text("  " + name + ": ", NamedTextColor.GRAY)
                .append(Component.text(present ? "found" : "absent",
                        present ? NamedTextColor.GREEN : NamedTextColor.RED))));

        World world = plugin.getWorldManager() == null ? null : plugin.getWorldManager().getWorld();
        sender.sendMessage(Component.text("Dungeon world: ", NamedTextColor.GRAY)
                .append(world == null
                        ? Component.text("not loaded", NamedTextColor.RED)
                        : Component.text(world.getName() + " (" + world.getLoadedChunks().length
                                + " chunks loaded)", NamedTextColor.GREEN)));

        if (plugin.getWorldManager() != null) {
            sender.sendMessage(Component.text("  reset on start: ", NamedTextColor.GRAY)
                    .append(plugin.getWorldManager().isResetOnStart()
                            ? Component.text("on (" + plugin.getWorldManager().getResetFiles()
                                    + " files deleted)", NamedTextColor.GREEN)
                            : Component.text("off - dead dungeons pile up on disk",
                                    NamedTextColor.YELLOW)));
        }

        GridSlotManager slots = plugin.getSlotManager();
        if (slots != null) {
            sender.sendMessage(Component.text("Slot: ", NamedTextColor.GRAY)
                    .append(Component.text(slots.allocatedCount() + " allocated, edge "
                            + slots.slotSize() + " blocks", NamedTextColor.WHITE)));
        }

        InstanceManager instances = plugin.getInstanceManager();
        if (instances != null) {
            sender.sendMessage(Component.text("Instance: ", NamedTextColor.GRAY)
                    .append(Component.text(instances.count() + " open", NamedTextColor.WHITE)));
        }

        SchematicService service = plugin.getSchematicService();
        sender.sendMessage(Component.text("Schematic: ", NamedTextColor.GRAY)
                .append(service == null
                        ? Component.text("disabled (no WorldEdit/FAWE)", NamedTextColor.RED)
                        : Component.text(service.list().size() + " files, paste mode "
                                + (service.isAsyncPaste() ? "async" : "sync"), NamedTextColor.GREEN)));

        StorageService storage = plugin.getStorage();
        sender.sendMessage(Component.text("Storage: ", NamedTextColor.GRAY)
                .append(storage == null || !storage.isReady()
                        ? Component.text("disabled - nothing is saved (/tdungeons db)",
                                NamedTextColor.RED)
                        : Component.text(storage.settings().describe() + ", schema v"
                                + storage.schemaVersion(), NamedTextColor.GREEN)));
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
        sender.sendMessage(Component.text("Teleported to the dungeon world.", NamedTextColor.GREEN));
    }

    private void list(CommandSender sender) {
        SchematicService service = requireSchematics(sender);
        if (service == null) {
            return;
        }
        List<String> names = service.list();
        if (names.isEmpty()) {
            sender.sendMessage(Component.text("No schematics. Folder: "
                    + service.getDirectory().getPath() + "  (create test rooms with /tdungeons gen)",
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
                sender.sendMessage(Component.text(count + " test rooms (.schem + .yml) created -> "
                        + service.getDirectory().getPath(), NamedTextColor.GREEN));
            } catch (Exception e) {
                sender.sendMessage(Component.text("Creation failed: " + e.getMessage(), NamedTextColor.RED));
                plugin.getLogger().warning("Test room creation failed: " + e);
            }
        });
    }

    private void paste(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /" + label + " paste <schematic> [0|90|180|270]",
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
                sender.sendMessage(Component.text("Rotation must be a number: " + args[2], NamedTextColor.RED));
                return;
            }
            if (Math.floorMod(rotation, 90) != 0) {
                sender.sendMessage(Component.text("Rotation must be a multiple of 90: " + rotation,
                        NamedTextColor.RED));
                return;
            }
        }

        String name = args[1];
        GridSlot slot = plugin.getSlotManager().allocate();
        int rot = rotation;

        sender.sendMessage(Component.text("Loading: " + name + " → " + slot, NamedTextColor.GRAY));

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
                        sender.sendMessage(Component.text("Paste failed: " + cause.getMessage(),
                                NamedTextColor.RED));
                        plugin.getLogger().warning("Paste failed (" + name + "): " + cause);
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
            player.sendMessage(Component.text("Teleported to the centre of the room.", NamedTextColor.GRAY));
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
            sender.sendMessage(Component.text("No rooms - create test rooms with /tdungeons gen.",
                    NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Room templates (" + names.size() + "):", NamedTextColor.GRAY));
        store.loadAll(names).whenComplete((templates, error) -> {
            if (error != null) {
                sendFailure(sender, "Template could not be loaded", error);
                return;
            }
            for (RoomTemplate t : templates) {
                String walls = t.doors().isEmpty()
                        ? "doorless"
                        : t.doors().stream().map(d -> d.wall().displayName())
                                .reduce((a, b) -> a + "+" + b).orElse("");
                sender.sendMessage(Component.text("  " + t.name(), NamedTextColor.WHITE)
                        .append(Component.text("  " + t.type().yamlValue()
                                + "  weight=" + t.weight()
                                + "  " + t.describeSize()
                                + "  doors=" + t.doorCount() + " (" + walls + ")", NamedTextColor.GRAY)));
            }
        });
    }

    /** Dumps one template in resolved form — for verifying its metadata. */
    private void room(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /" + label + " room <room>", NamedTextColor.RED));
            return;
        }
        RoomTemplateStore store = requireTemplates(sender);
        if (store == null) {
            return;
        }
        store.load(args[1]).whenComplete((t, error) -> {
            if (error != null) {
                sendFailure(sender, "Template could not be loaded", error);
                return;
            }
            sender.sendMessage(Component.text("Room: " + t.name(), NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  type: " + t.type().yamlValue()
                    + "   weight: " + t.weight(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  size: " + t.describeSize()
                    + "   box (relative to origin): " + t.localBox(), NamedTextColor.GRAY));
            if (t.doors().isEmpty()) {
                sender.sendMessage(Component.text("  no doors - this room cannot join the graph.",
                        NamedTextColor.YELLOW));
                return;
            }
            sender.sendMessage(Component.text("  doors:", NamedTextColor.GRAY));
            for (DoorAnchor d : t.doors()) {
                sender.sendMessage(Component.text("    #" + d.index() + " " + d.local()
                        + " -> " + d.wall().displayName() + " wall", NamedTextColor.WHITE));
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
                    "Usage: /" + label + " connect <parent> <child> [parentDoor] [childDoor]",
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
            sender.sendMessage(Component.text("The door index must be a number.", NamedTextColor.RED));
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
                        sendFailure(sender, "Connection failed", error);
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

        sender.sendMessage(Component.text("Connected - " + slot, NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  ebeveyn: " + parent
                + "  door#" + parentDoor + " " + parentAnchor
                + " " + parent.doorOutward(parentDoor).displayName(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  cocuk:   " + child
                + "  door#" + childDoor + " " + childAnchor
                + " " + child.doorOutward(childDoor).displayName(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  rotation computed: R=" + child.rotation().steps()
                + " (" + child.rotation().degrees() + " degrees)", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  boxes: " + parent.bounds()
                + "  |  " + child.bounds(), NamedTextColor.DARK_GRAY));

        sender.sendMessage(mated
                ? Component.text("  [OK] the doors are back to back", NamedTextColor.GREEN)
                : Component.text("  [ERROR] the doors are misaligned - expected "
                        + parent.doorMate(parentDoor), NamedTextColor.RED));
        sender.sendMessage(overlap
                ? Component.text("  [ERROR] the boxes OVERLAP", NamedTextColor.RED)
                : Component.text("  [OK] the boxes do not overlap", NamedTextColor.GREEN));

        // The two points where the passage gets verified by block test — from the console,
        // forceload followed by execute if block.
        sender.sendMessage(Component.text("  doorway blocks: " + parentAnchor + " ve " + childAnchor,
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
            sender.sendMessage(Component.text("The room store is off - WorldEdit or FAWE is not installed.",
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
                sendFailure(sender, "Templates could not be loaded", error);
                return;
            }
            RoomLibrary library = new RoomLibrary(templates);
            sender.sendMessage(Component.text(
                    "Candidate pool - theme " + theme + " (entrance/boss excluded: they are assigned, not drawn):",
                    NamedTextColor.GOLD));
            if (!library.isUsable()) {
                sender.sendMessage(Component.text("  " + library.describeProblem(),
                        NamedTextColor.RED));
                return;
            }
            RoomLibrary.describeDistribution(library.normalPool()).forEach(line ->
                    sender.sendMessage(Component.text("  " + line, NamedTextColor.WHITE)));
            sender.sendMessage(Component.text(
                    "  the weight belongs to the TEMPLATE, not to the (template x door) pair - generation.md 5.4",
                    NamedTextColor.DARK_GRAY));

            if (!library.entrances().isEmpty()) {
                sender.sendMessage(Component.text("  entrance rooms: " + library.entrances().size()
                        + "   boss rooms: " + library.bosses().size(), NamedTextColor.GRAY));
            } else {
                sender.sendMessage(Component.text(
                        "  no entrance room - one is drawn from the normal pool (fallback)",
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
            sender.sendMessage(Component.text("Usage: /" + label
                    + " dungeon <theme> [small|medium|large] [seed]", NamedTextColor.RED));
            return;
        }
        long seed;
        try {
            seed = args.length > next + 1
                    ? Long.parseLong(args[next + 1])
                    : new Random().nextLong();
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Seed must be a number: " + args[next + 1],
                    NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("Generating: theme=" + theme + ", " + size.key()
                + ", seed=" + seed, NamedTextColor.GRAY));

        plugin.getInstanceManager().create(theme, size, seed)
                .whenComplete((instance, error) -> {
                    if (error != null) {
                        sendFailure(sender, "Generation failed", error);
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
            sender.sendMessage(Component.text("No rooms at all - create test rooms with /tdungeons gen, "
                    + "or make a theme folder under schematics/.", NamedTextColor.YELLOW));
            return null;
        }
        if (requested != null) {
            for (String theme : themes) {
                if (theme.equalsIgnoreCase(requested)) {
                    return theme;
                }
            }
            sender.sendMessage(Component.text("No such theme: " + requested + "   mevcut: "
                    + String.join(", ", themes), NamedTextColor.RED));
            return null;
        }
        if (themes.size() == 1) {
            return themes.get(0);
        }
        sender.sendMessage(Component.text("Name a theme - available: " + String.join(", ", themes),
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
            sender.sendMessage(Component.text("No themes - schematics/ is empty.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Themes (" + themes.size() + "):", NamedTextColor.GOLD));
        for (String theme : themes) {
            store.loadAll(store.list(theme)).whenComplete((templates, error) -> {
                if (error != null) {
                    sendFailure(sender, "  " + theme + " could not be loaded", error);
                    return;
                }
                RoomLibrary library = new RoomLibrary(templates);
                sender.sendMessage(Component.text("  " + theme, NamedTextColor.WHITE)
                        .append(Component.text("  " + templates.size() + " rooms"
                                + "  entrance=" + library.entrances().size()
                                + "  boss=" + library.bosses().size()
                                + "  normal=" + library.normalPool().size()
                                + " (multi-door=" + library.branchingPool().size() + ")",
                                NamedTextColor.GRAY)));
                if (!library.isUsable()) {
                    sender.sendMessage(Component.text("    [HATA] " + library.describeProblem(),
                            NamedTextColor.RED));
                    return;
                }
                if (library.entrances().isEmpty()) {
                    sender.sendMessage(Component.text(
                            "    [WARNING] no entrance room - one is drawn from the normal pool",
                            NamedTextColor.YELLOW));
                }
                if (library.bosses().isEmpty()) {
                    sender.sendMessage(Component.text(
                            "    [WARNING] no boss room - the dungeon is generated without one",
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
        // Messages FIRST, and before the HUD: the sidebar's layout is read out of the language
        // file, so reloading the HUD against the old one would rebuild it from the language the
        // operator has just changed away from.
        if (plugin.getMessages() != null) {
            plugin.getMessages().load();
            sender.sendMessage(Component.text("Language reloaded: "
                    + plugin.getMessages().language(), NamedTextColor.GREEN));
        }
        // The HUD is reloaded before the early return below: it has no WorldEdit dependency,
        // so a server without WorldEdit must still be able to reload its sidebar.
        if (plugin.getHudService() != null) {
            plugin.getHudService().reload();
            sender.sendMessage(Component.text("HUD reloaded.", NamedTextColor.GREEN));
        }
        // Mobs reload here too, and for the same reason: mobs.yml has no WorldEdit dependency, so
        // a server without it must still be able to fix a typo in its mob set without a restart.
        if (plugin.getMobRegistry() != null) {
            mobReload(sender);
        }
        // Loot too, and for the third time the same reason: loot.yml has no WorldEdit dependency.
        if (plugin.getLootRegistry() != null) {
            lootReload(sender);
        }
        // The shop too, and for the same reason again: shop.yml has no WorldEdit dependency. It
        // reloads AFTER loot, because a stock entry may reference a loot.yml item by id and the
        // reference is resolved at load — against the freshly read catalogue, not the old one.
        if (plugin.getShopRegistry() != null) {
            shopReload(sender);
        }
        // Parties re-read their limits. Existing parties are LEFT ALONE, including any that are
        // now over a lowered max-size: a reload is a settings change, and breaking up a group
        // mid-dungeon to satisfy a number the operator has just typed is not one.
        if (plugin.getPartyManager() != null) {
            plugin.getPartyManager().reload();
            sender.sendMessage(Component.text("Party settings reloaded - max size "
                    + plugin.getPartyManager().maxSize()
                    + (plugin.getPartyManager().isEnabled() ? "" : " (parties are off)"),
                    NamedTextColor.GREEN));
        }

        // The storage layer is deliberately NOT reloaded. Repointing a live connection at another
        // host mid-session means deciding what happens to the writes already queued against the old
        // one, and there is no answer to that which is not a lie about where the data went. Said
        // out loud so an operator who edited the block does not assume it took effect.
        if (plugin.getStorage() != null) {
            sender.sendMessage(Component.text("Storage: "
                    + (plugin.getStorage().isReady()
                            ? plugin.getStorage().settings().describe() + " (unchanged - "
                                    + "connection settings are read at startup)"
                            : "disabled - " + plugin.getStorage().problem()),
                    plugin.getStorage().isReady() ? NamedTextColor.GRAY : NamedTextColor.YELLOW));
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
        sender.sendMessage(Component.text("Reloaded - " + service.list().size() + " rooms, "
                + themes.size() + " themes (" + String.join(", ", themes) + ")",
                NamedTextColor.GREEN));
    }

    private void reportDungeon(CommandSender sender, World world, DungeonInstance instance) {
        DungeonGenerator.Result result = instance.result();
        DoorPlugger.Report plug = instance.plugReport();

        sender.sendMessage(Component.text("Dungeon generated - instance#" + instance.id()
                + " @ " + instance.slot(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  theme: " + instance.theme(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  size: " + result.size().key()
                + "   rooms: " + result.rooms() + "/" + result.targetRooms()
                + "   critical path: " + result.pathLength() + "/" + result.targetPathLength(),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  attempts: " + result.attemptsUsed()
                + "   plugs: " + plug.plugged() + " doors / " + plug.blocks() + " blocks"
                + (plug.skipped() > 0 ? "   atlanan: " + plug.skipped() : ""),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  seed: " + result.seed()
                + "  (same theme + size + seed, same dungeon)", NamedTextColor.DARK_GRAY));

        if (result.warning() != null) {
            sender.sendMessage(Component.text("  warning: " + result.warning(),
                    NamedTextColor.YELLOW));
        }
        plug.warnings().forEach(w -> sender.sendMessage(
                Component.text("  plug warning: " + w, NamedTextColor.YELLOW)));

        DungeonGenerator.describe(result.layout(), result.bossNodeId()).forEach(line ->
                sender.sendMessage(Component.text("  " + line, NamedTextColor.WHITE)));

        List<String> problems = result.layout().validate();
        if (problems.isEmpty()) {
            sender.sendMessage(Component.text(
                    "  [OK] layout consistent: no overlaps, doorways aligned, graph connected",
                    NamedTextColor.GREEN));
        } else {
            problems.forEach(pr -> sender.sendMessage(
                    Component.text("  [HATA] " + pr, NamedTextColor.RED)));
        }

        Aabb box = instance.bounds();
        sender.sendMessage(Component.text("  volume to clear: " + box
                + "  (" + box.volume() + " blocks)", NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text("  time left: "
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
            sender.sendMessage(Component.text("No open instances.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Open instances (" + live.size() + "):",
                NamedTextColor.GOLD));
        for (DungeonInstance instance : live) {
            sender.sendMessage(Component.text("  #" + instance.id(), NamedTextColor.WHITE)
                    .append(Component.text("  " + instance.theme()
                            + "/" + instance.result().size().key()
                            + "  " + instance.result().rooms() + " rooms"
                            + "  " + instance.slot()
                            + "  " + instance.state(), NamedTextColor.GRAY))
                    .append(Component.text("  left "
                            + InstanceManager.formatDuration(instance.remainingMillis()),
                            NamedTextColor.YELLOW))
                    .append(Component.text("  players " + instance.playerCount(),
                            NamedTextColor.GRAY))
                    .append(instance.isCleared()
                            ? Component.text("  cleared", NamedTextColor.GREEN)
                            : Component.empty()));
        }
        sender.sendMessage(Component.text("  /tdungeons enter <id> | leave | close <id|all>",
                NamedTextColor.DARK_GRAY));
    }

    // ------------------------------------------------------------------ shop

    private void shop(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /" + label + " shop <"
                    + String.join("|", SHOP_ACTIONS) + ">", NamedTextColor.RED));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> shopList(sender);
            case "info" -> shopInfo(sender, label, args);
            case "reload" -> shopReload(sender);
            default -> sender.sendMessage(Component.text("Usage: /" + label + " shop <"
                    + String.join("|", SHOP_ACTIONS) + ">", NamedTextColor.RED));
        }
    }

    /**
     * The whole shop in one screen.
     *
     * <p>An operator cannot walk up to a merchant from the console, so this listing is the only way
     * to see what the file actually produced — including the entries that did not survive it.
     */
    private void shopList(CommandSender sender) {
        ShopRegistry shop = plugin.getShopRegistry();
        if (shop == null) {
            sender.sendMessage(Component.text("The shop layer is not available.", NamedTextColor.RED));
            return;
        }
        if (shop.loadError() != null) {
            sender.sendMessage(Component.text(shop.loadError(), NamedTextColor.RED));
        }
        if (!shop.isEnabled()) {
            sender.sendMessage(Component.text("The shop is switched off (shop.yml -> enabled: false).",
                    NamedTextColor.YELLOW));
        }

        KeeperSpec keeper = shop.keeper();
        ShopCurrency currency = shop.currency();
        sender.sendMessage(Component.text("Merchant: ", NamedTextColor.GOLD)
                .append(Component.text(keeper == null ? "not defined" : keeper.toString(),
                        keeper == null ? NamedTextColor.RED : NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Currency: ", NamedTextColor.GOLD)
                .append(currency == null
                        ? Component.text("not defined", NamedTextColor.RED)
                        : currency.format(1).color(NamedTextColor.WHITE)));

        Collection<ShopEntry> stock = shop.stock();
        sender.sendMessage(Component.text("Stock (" + stock.size() + "):", NamedTextColor.GOLD));
        for (ShopEntry entry : stock) {
            sender.sendMessage(Component.text("  " + entry.id(), NamedTextColor.WHITE)
                    .append(Component.text("  " + entry.amount() + "x " + entry.item().material(),
                            NamedTextColor.GRAY))
                    .append(Component.text("  " + entry.price(), NamedTextColor.YELLOW))
                    .append(entry.hasLimit()
                            ? Component.text("  limit " + entry.limit(), NamedTextColor.DARK_GRAY)
                            : Component.empty()));
        }

        for (ShopRegistry.Disabled bad : shop.disabled()) {
            sender.sendMessage(Component.text("  disabled: " + bad.id() + " - " + bad.reason(),
                    NamedTextColor.RED));
        }
        // Said out loud, because "usable" is the difference between a merchant standing in the
        // entrance and no merchant at all, and every reason for it is somewhere else on screen.
        sender.sendMessage(Component.text("  -> " + (shop.isUsable()
                ? "usable: a merchant will be placed"
                : "NOT usable: no merchant will be placed"),
                shop.isUsable() ? NamedTextColor.GREEN : NamedTextColor.RED));
        // The only way to see a merchant from a console: it is standing in a world nobody is in.
        sender.sendMessage(Component.text("  standing right now: "
                + plugin.getShopManager().liveCount(), NamedTextColor.GRAY));
    }

    private void shopInfo(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /" + label + " shop info <id>",
                    NamedTextColor.RED));
            return;
        }
        ShopEntry entry = plugin.getShopRegistry().entry(args[2]);
        if (entry == null) {
            sender.sendMessage(Component.text("No stock entry called '" + args[2] + "'.",
                    NamedTextColor.RED));
            return;
        }
        LootItem item = entry.item();
        sender.sendMessage(Component.text(entry.id(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  item: " + entry.amount() + "x " + item.material(),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  price: " + entry.price(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  limit: "
                + (entry.hasLimit() ? entry.limit() + " per player per dungeon" : "none"),
                NamedTextColor.GRAY));
        if (item.displayName() != null) {
            sender.sendMessage(Component.text("  name: ", NamedTextColor.GRAY)
                    .append(MiniMessage.miniMessage().deserialize(item.displayName())));
        }
        for (String line : item.lore()) {
            sender.sendMessage(Component.text("  lore: ", NamedTextColor.DARK_GRAY)
                    .append(MiniMessage.miniMessage().deserialize(line)));
        }
        item.enchantments().forEach((enchantment, level) -> sender.sendMessage(
                Component.text("  enchant: " + enchantment.getKey().getKey() + " " + level,
                        NamedTextColor.GRAY)));
    }

    private void shopReload(CommandSender sender) {
        ShopRegistry shop = plugin.getShopRegistry();
        if (shop == null) {
            sender.sendMessage(Component.text("The shop layer is not available.", NamedTextColor.RED));
            return;
        }
        shop.load();
        sender.sendMessage(Component.text("shop.yml reloaded - " + shop.stock().size()
                + " for sale" + (shop.isUsable() ? "" : " (NOT usable - see /tdungeons shop list)"),
                shop.isUsable() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    }

    /**
     * The live parties, for an operator.
     *
     * <p>Read-only on purpose: an admin who breaks up somebody's party from the console is a
     * moderation tool, and this is a window. It exists because a party is invisible from outside —
     * the console cannot run {@code /party}, and a two-account test needs a third pair of eyes.
     */
    private void parties(CommandSender sender) {
        PartyManager manager = plugin.getPartyManager();
        if (manager == null) {
            sender.sendMessage(Component.text("The party layer is not available.", NamedTextColor.RED));
            return;
        }
        if (!manager.isEnabled()) {
            sender.sendMessage(Component.text("Parties are switched off (config.yml -> party.enabled).",
                    NamedTextColor.YELLOW));
            return;
        }
        List<Party> live = manager.all();
        if (live.isEmpty()) {
            sender.sendMessage(Component.text("No parties.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Parties (" + live.size() + ", max size "
                + manager.maxSize() + "):", NamedTextColor.GOLD));
        for (Party party : live) {
            StringBuilder names = new StringBuilder();
            for (UUID member : party.ordered()) {
                Player online = plugin.getServer().getPlayer(member);
                String name = online != null ? online.getName() : member.toString();
                names.append(names.isEmpty() ? "" : ", ")
                        .append(party.isLeader(member) ? "*" + name : name);
            }
            sender.sendMessage(Component.text("  #" + party.id(), NamedTextColor.WHITE)
                    .append(Component.text("  " + party.size() + "/" + manager.maxSize()
                            + "  " + names, NamedTextColor.GRAY)));
        }
        sender.sendMessage(Component.text("  * = leader", NamedTextColor.DARK_GRAY));
    }

    // ------------------------------------------------------------------ phase 7: the database

    /**
     * Answers from whichever thread the caller happens to be on, always on the main one.
     *
     * <p>Every phase 7 read comes back on the storage thread. Sending straight from there works
     * today — an Adventure component is immutable and the console does not care — but command
     * output is a main-thread surface, and a reply that happens to be safe is not the same as one
     * that is. One scheduler hop is the whole cost.
     */
    private void reply(CommandSender sender, Component message) {
        if (plugin.getServer().isPrimaryThread()) {
            sender.sendMessage(message);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(message));
    }

    private void db(CommandSender sender, String label, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> dbStatus(sender);
            case "flush" -> dbFlush(sender);
            default -> sender.sendMessage(Component.text("Usage: /" + label + " db <"
                    + String.join("|", DB_ACTIONS) + ">", NamedTextColor.RED));
        }
    }

    /**
     * What the storage layer is doing.
     *
     * <p>The first question an operator asks after installing this is "is it actually saving
     * anything", and before phase 7 there was nothing in the plugin that could answer it. The
     * backend, the schema version and the queue depth are that answer; the reason line is the one
     * that matters when it is not.
     */
    private void dbStatus(CommandSender sender) {
        StorageService storage = plugin.getStorage();
        PlayerDataService data = plugin.getPlayerData();
        if (storage == null) {
            sender.sendMessage(Component.text("The storage layer was not built.", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("Storage", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  backend: ", NamedTextColor.GRAY)
                .append(Component.text(storage.settings().describe(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  state: ", NamedTextColor.GRAY)
                .append(storage.isReady()
                        ? Component.text("ready (schema v" + storage.schemaVersion() + ")",
                                NamedTextColor.GREEN)
                        : Component.text("disabled", NamedTextColor.RED)));
        if (!storage.isReady()) {
            sender.sendMessage(Component.text("  reason: " + storage.problem(), NamedTextColor.YELLOW));
            sender.sendMessage(Component.text(
                    "  settings and counters last until logout; nothing else is affected.",
                    NamedTextColor.DARK_GRAY));
            return;
        }
        sender.sendMessage(Component.text("  queue: ", NamedTextColor.GRAY)
                .append(Component.text(storage.queued() + " waiting, flushing every "
                        + storage.settings().flushSeconds() + "s", NamedTextColor.WHITE)));
        if (data != null) {
            sender.sendMessage(Component.text("  cached profiles: ", NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(data.cachedCount()), NamedTextColor.WHITE)));
            // Asked asynchronously, like every other read: a COUNT(*) on a big MySQL table is not
            // something to make the main thread wait for just to print a line. The answer comes
            // back on the storage thread and is handed to the main thread before it is printed —
            // command output is a main-thread surface and staying on it costs one scheduler hop.
            data.countPlayers()
                    .thenAccept(count -> reply(sender, Component.text("  players on record: ",
                                    NamedTextColor.GRAY)
                            .append(Component.text(String.valueOf(count), NamedTextColor.WHITE))))
                    .exceptionally(error -> {
                        reply(sender, Component.text("  players on record: unreadable ("
                                + error.getMessage() + ")", NamedTextColor.YELLOW));
                        return null;
                    });
        }
    }

    /**
     * Writes everything pending right now.
     *
     * <p>For the one case the interval cannot cover: an operator about to stop or restart the
     * server by a means that does not run {@code onDisable} — a container kill, a host's power
     * button. Also the honest way to check that writing works at all without waiting a minute.
     */
    private void dbFlush(CommandSender sender) {
        PlayerDataService data = plugin.getPlayerData();
        if (data == null || !data.isPersistent()) {
            sender.sendMessage(Component.text("Persistence is off - there is nothing to flush.",
                    NamedTextColor.YELLOW));
            return;
        }
        int pending = data.flushAll();
        sender.sendMessage(Component.text(pending == 0
                ? "Nothing was pending."
                : pending + " profile(s) queued for writing.", NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ phase 8: the API

    private void api(CommandSender sender, String label, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> apiStatus(sender);
            case "debug" -> apiDebug(sender, label, args);
            default -> sender.sendMessage(Component.text("Usage: /" + label + " api <"
                    + String.join("|", API_ACTIONS) + ">", NamedTextColor.RED));
        }
    }

    /**
     * What the API is offering and who is listening.
     *
     * <p>The listener counts are the part worth having. An addon developer whose handler is not
     * running has two candidate explanations — the event is not firing, or their listener is not
     * registered — and nothing on a vanilla server can tell them apart. A count of zero against an
     * event name answers it in one line.
     */
    private void apiStatus(CommandSender sender) {
        var registration = plugin.getServer().getServicesManager()
                .getRegistration(TakashiDungeonsAPI.class);

        sender.sendMessage(Component.text("API", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  version: ", NamedTextColor.GRAY)
                .append(Component.text(TakashiDungeonsAPI.API_VERSION, NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  service: ", NamedTextColor.GRAY)
                .append(registration == null
                        ? Component.text("NOT registered - addons cannot reach it",
                                NamedTextColor.RED)
                        : Component.text("registered by "
                                + registration.getPlugin().getName(), NamedTextColor.GREEN)));

        sender.sendMessage(Component.text("  listeners:", NamedTextColor.GRAY));
        reportListeners(sender, "DungeonCreateEvent", DungeonCreateEvent.getHandlerList());
        reportListeners(sender, "DungeonEnterEvent", DungeonEnterEvent.getHandlerList());
        reportListeners(sender, "DungeonLeaveEvent", DungeonLeaveEvent.getHandlerList());
        reportListeners(sender, "DungeonCompleteEvent", DungeonCompleteEvent.getHandlerList());
        reportListeners(sender, "DungeonCloseEvent", DungeonCloseEvent.getHandlerList());
        reportListeners(sender, "DungeonMobKillEvent", DungeonMobKillEvent.getHandlerList());

        ApiEvents events = plugin.getApiEvents();
        if (events != null && events.isDebugging()) {
            sender.sendMessage(Component.text("  debug logging is ON (/tdungeons api debug off)",
                    NamedTextColor.YELLOW));
        }
    }

    private void reportListeners(CommandSender sender, String event, HandlerList handlers) {
        // The debug listener is one of these when it is on. Said rather than subtracted: a count
        // that quietly excludes something is a count somebody will one day mistrust.
        int count = handlers.getRegisteredListeners().length;
        sender.sendMessage(Component.text("    " + event + ": ", NamedTextColor.DARK_GRAY)
                .append(Component.text(count == 0 ? "none" : String.valueOf(count),
                        count == 0 ? NamedTextColor.DARK_GRAY : NamedTextColor.WHITE)));
    }

    /** Logs every API event to the console — the addon developer's mirror. */
    private void apiDebug(CommandSender sender, String label, String[] args) {
        ApiEvents events = plugin.getApiEvents();
        if (events == null) {
            sender.sendMessage(Component.text("The API layer was not built.", NamedTextColor.RED));
            return;
        }
        boolean on;
        if (args.length < 3) {
            on = !events.isDebugging();
        } else if (args[2].equalsIgnoreCase("on")) {
            on = true;
        } else if (args[2].equalsIgnoreCase("off")) {
            on = false;
        } else {
            sender.sendMessage(Component.text("Usage: /" + label + " api debug [on|off]",
                    NamedTextColor.RED));
            return;
        }
        events.debug(on);
        sender.sendMessage(Component.text("API event logging " + (on ? "on" : "off")
                + (on ? " - every event is written to the console, including one line per mob kill."
                        : "."),
                on ? NamedTextColor.GREEN : NamedTextColor.GRAY));
    }

    /**
     * {@code /tdungeons stats [player]} — what the counters say.
     *
     * <p>Admin-side and English in the source, like everything else this command prints. A
     * player-facing version is not missing by accident: what a player wants to see is a rank and a
     * balance, and those belong to the addons (phases 11 and 12), which read these numbers through
     * the phase 8 API rather than duplicating them.
     */
    private void stats(CommandSender sender, String label, String[] args) {
        PlayerDataService data = plugin.getPlayerData();
        if (data == null) {
            sender.sendMessage(Component.text("The player data layer was not built.", NamedTextColor.RED));
            return;
        }
        String name;
        if (args.length >= 2) {
            name = args[1];
        } else if (sender instanceof Player self) {
            name = self.getName();
        } else {
            sender.sendMessage(Component.text("Usage: /" + label + " stats <player>",
                    NamedTextColor.RED));
            return;
        }

        if (!data.isPersistent() && plugin.getServer().getPlayerExact(name) == null) {
            // Without a database there is nothing to look an offline player up in. Said plainly,
            // because "no stats" would read as "this player has never played".
            sender.sendMessage(Component.text("Persistence is off - only players who are online "
                    + "right now have counters.", NamedTextColor.YELLOW));
            return;
        }

        data.lookup(name).thenAccept(stats -> {
            if (stats == null) {
                reply(sender, Component.text("No record for '" + name
                        + "'. Names are matched against the last one seen on this server.",
                        NamedTextColor.YELLOW));
                return;
            }
            reply(sender, Component.text("Stats - " + name, NamedTextColor.GOLD));
            reply(sender, Component.text("  dungeons entered: ", NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(stats.runsEntered()), NamedTextColor.WHITE))
                    .append(Component.text("   cleared: ", NamedTextColor.GRAY))
                    .append(Component.text(String.valueOf(stats.runsCleared()), NamedTextColor.WHITE)));
            reply(sender, Component.text("  kills: ", NamedTextColor.GRAY)
                    .append(Component.text(stats.mobKills() + " mobs, " + stats.bossKills()
                            + " bosses", NamedTextColor.WHITE)));
            reply(sender, Component.text("  deaths inside: ", NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(stats.deaths()), NamedTextColor.WHITE))
                    .append(Component.text("   time inside: ", NamedTextColor.GRAY))
                    .append(Component.text(InstanceManager.formatDuration(
                            stats.secondsInside() * 1000L), NamedTextColor.WHITE)));
        }).exceptionally(error -> {
            reply(sender, Component.text("The lookup failed: " + error.getMessage(),
                    NamedTextColor.RED));
            return null;
        });
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
            sender.sendMessage(Component.text("Usage: /" + label + " enter <id>",
                    NamedTextColor.RED));
            return;
        }
        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid id: " + args[1], NamedTextColor.RED));
            return;
        }
        InstanceManager manager = plugin.getInstanceManager();
        DungeonInstance instance = manager.get(id);
        if (instance == null) {
            sender.sendMessage(Component.text("instance#" + id + " does not exist.", NamedTextColor.YELLOW));
            return;
        }
        if (!manager.enter(player, instance)) {
            sender.sendMessage(Component.text("instance#" + id + " cannot be entered: "
                    + instance.state(), NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("instance#" + id + " - you are inside, time left "
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
            sender.sendMessage(Component.text("You are not inside a dungeon.", NamedTextColor.YELLOW));
            return;
        }
        manager.leave(player, instance);
        sender.sendMessage(Component.text("instance#" + instance.id() + " left.",
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
            sender.sendMessage(Component.text("Usage: /" + label + " close <id|all>",
                    NamedTextColor.RED));
            return;
        }
        InstanceManager manager = plugin.getInstanceManager();

        if (args[1].equalsIgnoreCase("all")) {
            int count = manager.count();
            if (count == 0) {
                sender.sendMessage(Component.text("No open instances.", NamedTextColor.YELLOW));
                return;
            }
            sender.sendMessage(Component.text(count + " instances closing...", NamedTextColor.GRAY));
            manager.closeAll().whenComplete((ignored, error) -> sender.sendMessage(error == null
                    ? Component.text(count + " instances closed.", NamedTextColor.GREEN)
                    : Component.text("Something failed while closing - check the console.", NamedTextColor.RED)));
            return;
        }

        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid id: " + args[1], NamedTextColor.RED));
            return;
        }
        DungeonInstance instance = manager.get(id);
        if (instance == null) {
            sender.sendMessage(Component.text("instance#" + id + " does not exist.", NamedTextColor.YELLOW));
            return;
        }
        manager.close(instance).whenComplete((report, error) -> {
            if (error != null) {
                sendFailure(sender, "Could not be closed", error);
                return;
            }
            sender.sendMessage(Component.text("instance#" + report.id() + " closed - "
                    + report.blocksCleared() + " blocks cleared, "
                    + report.chunksUnloaded() + " chunks unloaded, "
                    + report.entitiesRemoved() + " entities removed, "
                    + report.playersEvicted() + " players evicted  ("
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
            sender.sendMessage(Component.text("The gateway service was not built.", NamedTextColor.RED));
            return;
        }
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);

        switch (action) {
            case "list" -> portalList(sender, manager);
            case "create" -> portalCreate(sender, manager, args);
            case "remove" -> portalRemove(sender, manager, label, args);
            case "tp" -> portalTeleport(sender, manager, label, args);
            default -> sender.sendMessage(Component.text("Usage: /" + label + " portal <"
                    + String.join("|", PORTAL_ACTIONS) + ">", NamedTextColor.RED));
        }
    }

    private void portalList(CommandSender sender, PortalManager manager) {
        List<DungeonPortal> all = manager.all();
        if (all.isEmpty()) {
            sender.sendMessage(Component.text("No gateways. Place a lobby gateway with /tdungeons portal "
                    + "create.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Gateways (" + all.size() + "):", NamedTextColor.GOLD));
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
            sender.sendMessage(Component.text("Size must be small|medium|large: " + args[2],
                    NamedTextColor.RED));
            return;
        }
        String theme = args.length >= 4 ? args[3] : null;

        DungeonPortal portal = manager.create(player.getLocation(), PortalKind.LOBBY, theme, size);
        if (portal == null) {
            sender.sendMessage(Component.text("There is already a gateway here.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Gateway placed: " + portal, NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  To make it permanent add it under config.yml -> portal.lobby.points; "
                + "gateways do not survive a restart (persistence is phase 7).",
                NamedTextColor.DARK_GRAY));
    }

    private void portalRemove(CommandSender sender, PortalManager manager, String label,
                              String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /" + label + " portal remove <id|all>",
                    NamedTextColor.RED));
            return;
        }
        if (args[2].equalsIgnoreCase("all")) {
            int count = manager.removeAll();
            sender.sendMessage(Component.text(count + " gateways removed.", NamedTextColor.GREEN));
            return;
        }
        DungeonPortal portal = findPortal(sender, manager, args[2]);
        if (portal == null) {
            return;
        }
        manager.remove(portal);
        sender.sendMessage(Component.text("portal#" + portal.id() + " removed.",
                NamedTextColor.GREEN));
    }

    private void portalTeleport(CommandSender sender, PortalManager manager, String label,
                                String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /" + label + " portal tp <id>",
                    NamedTextColor.RED));
            return;
        }
        DungeonPortal portal = findPortal(sender, manager, args[2]);
        if (portal == null) {
            return;
        }
        plugin.getInstanceManager().teleportInternal(player, portal.center().add(0, 1, 0));
        sender.sendMessage(Component.text("portal#" + portal.id() + " - teleported to it.",
                NamedTextColor.GREEN));
    }

    private @Nullable DungeonPortal findPortal(CommandSender sender, PortalManager manager,
                                               String raw) {
        try {
            DungeonPortal portal = manager.get(Integer.parseInt(raw));
            if (portal == null) {
                sender.sendMessage(Component.text("portal#" + raw + " does not exist.", NamedTextColor.YELLOW));
            }
            return portal;
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid id: " + raw, NamedTextColor.RED));
            return null;
        }
    }

    private void slots(CommandSender sender) {
        GridSlotManager manager = plugin.getSlotManager();
        if (manager.allocatedCount() == 0) {
            sender.sendMessage(Component.text("No allocated slots.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Allocated slots (" + manager.allocatedCount() + "):",
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
            sender.sendMessage(Component.text("Usage: /" + label + " free <index|all>", NamedTextColor.RED));
            return;
        }
        GridSlotManager manager = plugin.getSlotManager();
        if (args[1].equalsIgnoreCase("all")) {
            int count = manager.allocatedCount();
            manager.releaseAll();
            sender.sendMessage(Component.text(count + " slots released. "
                    + "(Blocks are not deleted - use /tdungeons close for an instance.)",
                    NamedTextColor.GREEN));
            return;
        }
        try {
            int index = Integer.parseInt(args[1]);
            boolean released = manager.release(index);
            sender.sendMessage(released
                    ? Component.text("slot#" + index + " released. (Blocks are not deleted - "
                            + "use /tdungeons close for an instance.)", NamedTextColor.GREEN)
                    : Component.text("slot#" + index + " was not allocated.", NamedTextColor.YELLOW));
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid index: " + args[1], NamedTextColor.RED));
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
            sender.sendMessage(Component.text("The HUD service was not built.", NamedTextColor.RED));
            return;
        }

        if (args.length == 1) {
            sender.sendMessage(Component.text("HUD: ", NamedTextColor.GRAY)
                    .append(hud.isEnabled()
                            ? Component.text("on", NamedTextColor.GREEN)
                            : Component.text("off (config: hud.enabled)", NamedTextColor.RED)));
            sender.sendMessage(Component.text("  Server name: ", NamedTextColor.GRAY)
                    .append(Component.text(hud.getServerName(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  Server IP: ", NamedTextColor.GRAY)
                    .append(Component.text(hud.getServerIp(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  Lines: ", NamedTextColor.GRAY)
                    .append(Component.text(hud.lineCount() + " (lang: hud.lines)",
                            NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Usage: /" + label + " hud <name|ip> <text>",
                    NamedTextColor.GRAY));
            return;
        }

        String setting = args[1].toLowerCase(Locale.ROOT);
        if (!HUD_SETTINGS.contains(setting)) {
            sender.sendMessage(Component.text("Usage: /" + label + " hud <name|ip> <text>",
                    NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Write a value: /" + label + " hud " + setting
                    + " <text>", NamedTextColor.RED));
            return;
        }

        // Everything after the setting name is the value — a server name has spaces in it.
        String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (setting.equals("name")) {
            hud.setServerName(value);
            sender.sendMessage(Component.text("HUD server name: ", NamedTextColor.GREEN)
                    .append(Component.text(value, NamedTextColor.WHITE)));
        } else {
            hud.setServerIp(value);
            sender.sendMessage(Component.text("HUD server IP: ", NamedTextColor.GREEN)
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
            sender.sendMessage(Component.text("The bundled-room service was not built.", NamedTextColor.RED));
            return;
        }
        boolean force = args.length > 1 && args[1].equalsIgnoreCase("force");
        BundledRooms.Result result = bundled.extract(
                new File(plugin.getDataFolder(), "schematics"), force);

        if (result.total() == 0) {
            sender.sendMessage(Component.text("The jar holds no bundled rooms.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Bundled rooms: ", NamedTextColor.GRAY)
                .append(Component.text(result.written() + " written", NamedTextColor.GREEN))
                .append(Component.text(", " + result.skipped()
                        + (force ? " skipped" : " already present"), NamedTextColor.GRAY))
                .append(result.failed() == 0
                        ? Component.empty()
                        : Component.text(", " + result.failed() + " failed", NamedTextColor.RED)));

        // New files on disk mean the caches are stale — the same reason /tdungeons reload exists.
        SchematicService service = plugin.getSchematicService();
        RoomTemplateStore store = plugin.getTemplateStore();
        if (result.written() > 0 && service != null && store != null) {
            service.invalidateCache();
            store.invalidateCache();
            sender.sendMessage(Component.text("Cache cleared - " + service.list().size()
                    + " rooms visible.", NamedTextColor.GRAY));
        }
    }

    private @Nullable Player asPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Component.text("This command must be run by a player.", NamedTextColor.RED));
        return null;
    }

    private @Nullable World requireWorld(CommandSender sender) {
        World world = plugin.getWorldManager() == null ? null : plugin.getWorldManager().getWorld();
        if (world == null) {
            sender.sendMessage(Component.text("The dungeon world is not loaded - check the console log.",
                    NamedTextColor.RED));
        }
        return world;
    }

    private @Nullable SchematicService requireSchematics(CommandSender sender) {
        SchematicService service = plugin.getSchematicService();
        if (service == null) {
            sender.sendMessage(Component.text("The schematic service is off - WorldEdit or FAWE is not installed.",
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
            default -> sender.sendMessage(Component.text("Usage: /" + label + " mob <"
                    + String.join("|", MOB_ACTIONS) + ">", NamedTextColor.RED));
        }
    }

    /**
     * The catalogue, grouped by class, with the disabled entries and their reasons underneath.
     *
     * <p>The disabled block is not an afterthought — it is the whole diagnostic. An operator whose
     * MythicMobs boss never turns up needs one place that says why, and a count without reasons
     * ("3 disabled") tells them something is wrong without telling them what.
     */
    private void mobList(CommandSender sender, String[] args) {
        MobRegistry registry = plugin.getMobRegistry();
        MobClass filter = args.length >= 3 ? MobClass.parse(args[2]) : null;
        if (args.length >= 3 && filter == null) {
            sender.sendMessage(Component.text("Unknown class: " + args[2] + " - valid: "
                    + "weak, normal, strong, super_strong, boss", NamedTextColor.RED));
            return;
        }

        if (registry.loadError() != null) {
            sender.sendMessage(Component.text(registry.loadError(), NamedTextColor.RED));
        }
        sender.sendMessage(Component.text("Mob registry - " + registry.definitions().size()
                + " usable, default difficulty: " + registry.defaultDifficulty(),
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
            sender.sendMessage(Component.text("  disabled (" + disabled.size() + "):",
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
            sender.sendMessage(Component.text("Usage: /" + label + " mob info <id>",
                    NamedTextColor.RED));
            return;
        }
        MobRegistry registry = plugin.getMobRegistry();
        MobDefinition definition = registry.definition(args[2]);
        if (definition == null) {
            sender.sendMessage(Component.text("No such mob: " + args[2]
                    + " — /" + label + " mob list", NamedTextColor.RED));
            return;
        }
        MobProvider provider = registry.provider(definition.providerId());
        boolean override = provider != null && definition.resolveStatOverride(provider);

        sender.sendMessage(Component.text(definition.id(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  address: " + definition.address(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  class: " + definition.mobClass()
                + "   weight: " + definition.weight(), NamedTextColor.GRAY));
        // The resolved value AND where it came from: the difference between "you wrote false" and
        // "MythicMobs definitions default to false" is what an operator is actually asking.
        sender.sendMessage(Component.text("  statOverride: " + override
                + (definition.statOverride() == null
                        ? " (provider default)" : " (written in the file)"), NamedTextColor.GRAY));
        boolean drops = provider != null && definition.resolveDungeonDrops(provider);
        sender.sendMessage(Component.text("  dungeonDrops: " + drops
                + (definition.dungeonDrops() == null
                        ? " (provider default)" : " (written in the file)"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  stats:" + (definition.hasStats()
                ? statSummary(definition) : " none - the mob's own values"), NamedTextColor.GRAY));
        if (definition.hasStats() && !override) {
            sender.sendMessage(Component.text("  ! statOverride is off - the stats above are "
                    + "NOT APPLIED.", NamedTextColor.YELLOW));
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
            sender.sendMessage(Component.text("Usage: /" + label
                    + " mob spawn <id> [easy|medium|hard]", NamedTextColor.RED));
            return;
        }
        MobService service = plugin.getMobService();
        MobRegistry registry = plugin.getMobRegistry();
        MobDefinition definition = registry.definition(args[2]);
        if (definition == null) {
            sender.sendMessage(Component.text("No such mob: " + args[2]
                    + " — /" + label + " mob list", NamedTextColor.RED));
            return;
        }
        Difficulty difficulty = registry.defaultDifficulty();
        if (args.length >= 4) {
            difficulty = Difficulty.parse(args[3]);
            if (difficulty == null) {
                sender.sendMessage(Component.text("Unknown difficulty: " + args[3]
                        + " - valid: easy, medium, hard", NamedTextColor.RED));
                return;
            }
        }

        var targetBlock = player.getTargetBlockExact(8);
        Location where = targetBlock == null
                ? player.getLocation() : targetBlock.getLocation().add(0.5, 1, 0.5);

        LivingEntity entity = service.spawn(definition, where, difficulty, new Random());
        if (entity == null) {
            sender.sendMessage(Component.text("Could not spawn - the provider refused. Check the console.",
                    NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(definition.id() + " spawned (" + difficulty.key()
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
        sender.sendMessage(Component.text("Mob providers:", NamedTextColor.GOLD));
        for (MobProvider provider : plugin.getMobRegistry().providers()) {
            boolean up = provider.isAvailable();
            sender.sendMessage(Component.text("  " + provider.id() + " — " + provider.displayName()
                    + ": " + (up ? provider.knownKeys().size() + " mobs" : "absent")
                    + "   defaults: statOverride=" + provider.defaultStatOverride()
                    + " dungeonDrops=" + provider.defaultDungeonDrops(),
                    up ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY));
        }
    }

    private void mobReload(CommandSender sender) {
        MobRegistry registry = plugin.getMobRegistry();
        registry.load();
        sender.sendMessage(Component.text("mobs.yml reloaded - "
                + registry.definitions().size() + " mobs"
                + (registry.disabled().isEmpty() ? "" : ", " + registry.disabled().size()
                        + " disabled"), NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ loot

    private void loot(CommandSender sender, String label, String[] args) {
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> lootList(sender, args);
            case "info" -> lootInfo(sender, label, args);
            case "tables" -> lootTables(sender);
            case "roll" -> lootRoll(sender, label, args);
            case "give" -> lootGive(sender, label, args);
            case "reload" -> lootReload(sender);
            default -> sender.sendMessage(Component.text("Usage: /" + label + " loot <"
                    + String.join("|", LOOT_ACTIONS) + ">", NamedTextColor.RED));
        }
    }

    /** The catalogue by class, with the disabled entries and their reasons underneath. */
    private void lootList(CommandSender sender, String[] args) {
        LootRegistry registry = plugin.getLootRegistry();
        ItemClass filter = args.length >= 3 ? ItemClass.parse(args[2]) : null;
        if (args.length >= 3 && filter == null) {
            sender.sendMessage(Component.text("Unknown class: " + args[2] + " - valid: "
                    + ItemClass.keyList(), NamedTextColor.RED));
            return;
        }
        if (registry.loadError() != null) {
            sender.sendMessage(Component.text(registry.loadError(), NamedTextColor.RED));
        }
        sender.sendMessage(Component.text("Loot registry - " + registry.items().size()
                + " usable items, " + registry.tables().size() + " tables", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  base rarity: " + registry.baseWeights(),
                NamedTextColor.DARK_GRAY));

        for (ItemClass itemClass : ItemClass.values()) {
            if (filter != null && filter != itemClass) {
                continue;
            }
            List<LootItem> pool = registry.pool(itemClass);
            sender.sendMessage(Component.text("  " + itemClass.key() + " (" + pool.size() + ")",
                    NamedTextColor.AQUA));
            for (LootItem item : pool) {
                sender.sendMessage(Component.text("    " + item.id() + " — " + item.material()
                        + " ×" + item.amount() + "  w=" + item.weight()
                        + (item.isCustomised() ? "  [custom]" : ""), NamedTextColor.GRAY));
            }
        }

        List<LootRegistry.Disabled> disabled = registry.disabled();
        if (!disabled.isEmpty()) {
            sender.sendMessage(Component.text("  disabled (" + disabled.size() + "):",
                    NamedTextColor.RED));
            for (LootRegistry.Disabled entry : disabled) {
                sender.sendMessage(Component.text("    " + entry.id() + " (" + entry.what() + ") — "
                        + entry.reason(), NamedTextColor.DARK_RED));
            }
        }
    }

    private void lootInfo(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /" + label + " loot info <id>",
                    NamedTextColor.RED));
            return;
        }
        LootRegistry registry = plugin.getLootRegistry();
        LootItem item = registry.item(args[2]);
        if (item == null) {
            sender.sendMessage(Component.text("No such item: " + args[2] + " — /" + label
                    + " loot list", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(item.id(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  material: " + item.material() + " ×" + item.amount(),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  class: " + item.itemClass() + "   weight: "
                + item.weight() + poolShare(registry, item), NamedTextColor.GRAY));
        if (item.displayName() != null) {
            sender.sendMessage(Component.text("  name: ", NamedTextColor.GRAY)
                    .append(MiniMessage.miniMessage().deserialize(item.displayName())));
        }
        for (String line : item.lore()) {
            sender.sendMessage(Component.text("  lore: ", NamedTextColor.DARK_GRAY)
                    .append(MiniMessage.miniMessage().deserialize(line)));
        }
        for (Map.Entry<Enchantment, Integer> entry : item.enchantments().entrySet()) {
            sender.sendMessage(Component.text("  enchant: " + entry.getKey().getKey().getKey()
                    + " " + entry.getValue(), NamedTextColor.DARK_GRAY));
        }
        if (item.unbreakable() || item.glow() || item.customModelData() != null
                || !item.flags().isEmpty()) {
            sender.sendMessage(Component.text("  flags:"
                    + (item.unbreakable() ? " unbreakable" : "")
                    + (item.glow() ? " glow" : "")
                    + (item.customModelData() == null ? "" : " cmd=" + item.customModelData())
                    + (item.flags().isEmpty() ? "" : " hide=" + item.flags()),
                    NamedTextColor.DARK_GRAY));
        }
    }

    /** How often this item comes up once its class has been drawn. */
    private String poolShare(LootRegistry registry, LootItem item) {
        int total = 0;
        for (LootItem other : registry.pool(item.itemClass())) {
            total += other.weight();
        }
        return total == 0 ? ""
                : "  (" + Math.round(100.0 * item.weight() / total) + "% of its class)";
    }

    /**
     * The tables, and what difficulty actually does to each one.
     *
     * <p>The per-difficulty rows are the point. "The multiplier is applied to rare and above and
     * paid for out of common" is a sentence; three rows of numbers with the shares next to them is
     * something an operator can check against what they meant.
     */
    private void lootTables(CommandSender sender) {
        LootRegistry registry = plugin.getLootRegistry();
        if (registry.tables().isEmpty()) {
            sender.sendMessage(Component.text("No loot tables are defined - loot.yml has no "
                    + "'tables:' block.", NamedTextColor.RED));
            return;
        }
        for (LootTable table : registry.tables()) {
            sender.sendMessage(Component.text(table.id() + "  rolls=" + table.rolls(),
                    NamedTextColor.GOLD));
            for (Difficulty difficulty : Difficulty.values()) {
                double multiplier = registry.multiplier(difficulty);
                RarityWeights weights = table.weightsFor(multiplier);
                StringBuilder shares = new StringBuilder();
                for (ItemClass itemClass : ItemClass.values()) {
                    if (weights.weight(itemClass) == 0) {
                        continue;
                    }
                    shares.append(shares.isEmpty() ? "" : "  ").append(itemClass.key()).append(' ')
                            .append(weights.weight(itemClass)).append(" (")
                            .append(percent(weights.share(itemClass))).append(')');
                }
                sender.sendMessage(Component.text("  " + difficulty.key() + " ×" + multiplier
                        + ": " + shares, NamedTextColor.GRAY));
            }
        }
    }

    /**
     * Rolls a table, once to see the items or many times to see the distribution.
     *
     * <p>The many-times form is the acceptance test for the weighting: a distribution that is eight
     * percent off looks exactly like luck in play, and this is the only place it becomes a number
     * an operator can compare against the table above.
     */
    private void lootRoll(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /" + label
                    + " loot roll <table> [easy|medium|hard] [count]", NamedTextColor.RED));
            return;
        }
        LootRegistry registry = plugin.getLootRegistry();
        LootTable table = registry.table(args[2]);
        if (table == null) {
            sender.sendMessage(Component.text("No such table: " + args[2] + " — /" + label
                    + " loot tables", NamedTextColor.RED));
            return;
        }
        Difficulty difficulty = args.length >= 4 ? Difficulty.parse(args[3]) : null;
        if (args.length >= 4 && difficulty == null) {
            sender.sendMessage(Component.text("Unknown difficulty: " + args[3]
                    + " - valid: easy, medium, hard", NamedTextColor.RED));
            return;
        }
        if (difficulty == null) {
            difficulty = plugin.getMobRegistry().defaultDifficulty();
        }
        int count = 1;
        if (args.length >= 5) {
            try {
                count = Math.clamp(Integer.parseInt(args[4]), 1, 100_000);
            } catch (NumberFormatException error) {
                sender.sendMessage(Component.text("The count must be a number: " + args[4],
                        NamedTextColor.RED));
                return;
            }
        }

        LootService service = plugin.getLootService();
        Random random = new Random();
        if (count == 1) {
            LootService.Roll roll = service.roll(table, difficulty, random);
            sender.sendMessage(Component.text(table.id() + " @ " + difficulty.key() + " — "
                    + roll.items().size() + " items"
                    + (roll.empty() == 0 ? "" : ", " + roll.empty() + " empty draws"),
                    NamedTextColor.GOLD));
            for (LootService.Drawn drawn : roll.draws()) {
                ItemStack stack = drawn.stack();
                Component name = stack.getItemMeta() != null
                        && stack.getItemMeta().hasDisplayName()
                        ? stack.getItemMeta().displayName()
                        : Component.text(stack.getType().toString());
                sender.sendMessage(Component.text("  ×" + stack.getAmount() + " ", NamedTextColor.GRAY)
                        .append(name)
                        .append(Component.text("  " + drawn.item().id(), NamedTextColor.DARK_GRAY)));
            }
            return;
        }

        Map<ItemClass, Integer> byClass = new EnumMap<>(ItemClass.class);
        Map<String, Integer> byItem = new LinkedHashMap<>();
        int draws = 0;
        int empty = 0;
        for (int i = 0; i < count; i++) {
            LootService.Roll roll = service.roll(table, difficulty, random);
            empty += roll.empty();
            draws += roll.draws().size() + roll.empty();
            // Counted from what actually landed in the chest rather than from the class draw: an
            // empty pool is exactly the difference between the two, and it is the thing worth
            // seeing.
            for (LootService.Drawn drawn : roll.draws()) {
                byClass.merge(drawn.item().itemClass(), 1, Integer::sum);
                byItem.merge(drawn.item().id(), 1, Integer::sum);
            }
        }
        RarityWeights expected = table.weightsFor(registry.multiplier(difficulty));
        sender.sendMessage(Component.text(table.id() + " @ " + difficulty.key() + " — " + count
                + " rolls, " + draws + " draws"
                + (empty == 0 ? "" : ", " + empty + " empty (a class with no items)"),
                NamedTextColor.GOLD));
        int landed = draws - empty;
        for (ItemClass itemClass : ItemClass.values()) {
            int seen = byClass.getOrDefault(itemClass, 0);
            if (seen == 0 && expected.weight(itemClass) == 0) {
                continue;
            }
            sender.sendMessage(Component.text("  " + itemClass.key() + ": "
                    + percent(landed == 0 ? 0 : (double) seen / landed) + " seen, "
                    + percent(expected.share(itemClass)) + " expected  (" + seen + ")",
                    NamedTextColor.GRAY));
            reportItemShare(sender, registry, itemClass, byItem, seen);
        }
    }

    /**
     * The share each item took <b>inside its own class</b>, observed against its weight.
     *
     * <p>This is the second of the two weights loot is built on, and until now it was the only
     * distribution in the system nothing measured: {@code LootProbe} checks the class split over
     * 200,000 draws, {@code tables} prints the class split, and the block above reports the class
     * split — while {@code LootRegistry.pick}, which decides <i>which item</i>, had no number
     * anywhere. A player who keeps finding the same sword had no way to be answered except by
     * argument, which is exactly the situation this project treats as a bug in the tooling.
     *
     * <p>Percentages are within the class, not of the whole roll, because that is the number the
     * item's {@code weight} actually sets.
     */
    private void reportItemShare(CommandSender sender, LootRegistry registry, ItemClass itemClass,
                                 Map<String, Integer> byItem, int classTotal) {
        List<LootItem> pool = registry.pool(itemClass);
        if (pool.size() < 2 || classTotal == 0) {
            // One item takes 100% of its class by definition, and an empty class has nothing to
            // report. Printing either would be a line that can never say anything.
            return;
        }
        int weightTotal = 0;
        for (LootItem item : pool) {
            weightTotal += item.weight();
        }
        for (LootItem item : pool) {
            int seen = byItem.getOrDefault(item.id(), 0);
            sender.sendMessage(Component.text("      " + item.id() + ": "
                    + percent((double) seen / classTotal) + " seen, "
                    + percent(weightTotal == 0 ? 0 : (double) item.weight() / weightTotal)
                    + " expected  (" + seen + ")", NamedTextColor.DARK_GRAY));
        }
    }

    private String percent(double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100);
    }

    /**
     * Builds one catalogue entry for real and hands it over — the proof that what the file
     * describes and what a chest would hold are the same item.
     */
    private void lootGive(CommandSender sender, String label, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /" + label + " loot give <id>",
                    NamedTextColor.RED));
            return;
        }
        LootItem item = plugin.getLootRegistry().item(args[2]);
        if (item == null) {
            sender.sendMessage(Component.text("No such item: " + args[2] + " — /" + label
                    + " loot list", NamedTextColor.RED));
            return;
        }
        ItemStack stack = plugin.getLootService().build(item, new Random());
        // Anything that does not fit goes on the floor rather than being deleted: this command
        // exists to look at an item, and silently eating it would be a strange way to fail.
        player.getInventory().addItem(stack).values()
                .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        sender.sendMessage(Component.text("Gave " + item.id() + " ×" + stack.getAmount() + " ("
                + item.itemClass() + ")", NamedTextColor.GREEN));
    }

    private void lootReload(CommandSender sender) {
        LootRegistry registry = plugin.getLootRegistry();
        registry.load();
        sender.sendMessage(Component.text("loot.yml reloaded - " + registry.items().size()
                + " items, " + registry.tables().size() + " tables"
                + (registry.disabled().isEmpty() ? "" : ", " + registry.disabled().size()
                        + " disabled"), NamedTextColor.GREEN));
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
        if (sub.equals("loot")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (args.length == 2) {
                return LOOT_ACTIONS.stream().filter(o -> o.startsWith(prefix)).toList();
            }
            String action = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && (action.equals("info") || action.equals("give"))) {
                return plugin.getLootRegistry().items().stream().map(LootItem::id)
                        .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
            }
            if (args.length == 3 && action.equals("list")) {
                return Arrays.stream(ItemClass.values()).map(ItemClass::key)
                        .filter(k -> k.startsWith(prefix)).toList();
            }
            if (args.length == 3 && action.equals("roll")) {
                return plugin.getLootRegistry().tables().stream().map(LootTable::id)
                        .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
            }
            if (args.length == 4 && action.equals("roll")) {
                return DIFFICULTIES.stream().filter(d -> d.startsWith(prefix)).toList();
            }
            return List.of();
        }
        if (sub.equals("shop")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (args.length == 2) {
                return SHOP_ACTIONS.stream().filter(o -> o.startsWith(prefix)).toList();
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("info")) {
                return plugin.getShopRegistry().stock().stream().map(ShopEntry::id)
                        .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
            }
            return List.of();
        }
        if (args.length == 2 && sub.equals("db")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return DB_ACTIONS.stream().filter(o -> o.startsWith(prefix)).toList();
        }
        if (sub.equals("api")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (args.length == 2) {
                return API_ACTIONS.stream().filter(o -> o.startsWith(prefix)).toList();
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("debug")) {
                return List.of("on", "off").stream().filter(o -> o.startsWith(prefix)).toList();
            }
            return List.of();
        }
        if (args.length == 2 && sub.equals("stats")) {
            // Online players only. The database knows every name it has ever seen, and reading
            // them all for a tab-complete would be a query per keystroke.
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
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
