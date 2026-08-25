package com.takashi.dungeons.command;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.ChainGenerator;
import com.takashi.dungeons.generation.DoorAnchor;
import com.takashi.dungeons.generation.LayoutNode;
import com.takashi.dungeons.generation.PlacedRoom;
import com.takashi.dungeons.generation.RoomLibrary;
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
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /tdungeons} — yönetim ve FAZ 1 doğrulama komutu.
 *
 * <p>Buradaki {@code gen}/{@code paste}/{@code free} alt komutları geliştirme amaçlı:
 * generation zincirini (slot ayır → schematic yükle → paste) elle tetikleyip doğrulamak
 * için var. Oyuncuya açık dungeon komutları (join/leave) FAZ 2'de gelecek.
 */
public final class DungeonsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS =
            List.of("version", "status", "world", "list", "rooms", "room", "weights", "gen",
                    "paste", "connect", "build", "slots", "free");

    private static final List<String> ROTATIONS = List.of("0", "90", "180", "270");

    /** Tab-complete icin kapi indeksi onerileri; en cok kapili test odasi 4 kapili. */
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
            case "rooms" -> rooms(sender);
            case "room" -> room(sender, label, args);
            case "gen" -> generate(sender);
            case "paste" -> paste(sender, label, args);
            case "connect" -> connect(sender, label, args);
            case "weights" -> weights(sender);
            case "build" -> build(sender, label, args);
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
                // Şablon cache'i clipboard cache'inin ÜSTÜNDE duruyor; sadece alttakini
                // temizlemek eski kapı metadata'sını bellekte bırakırdı.
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

    // ---------------------------------------------------------------- FAZ 1B: oda modeli

    /** Klasordeki sablonlari metadata ile birlikte listeler. */
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
                        : t.doors().stream().map(d -> d.wall().turkish())
                                .reduce((a, b) -> a + "+" + b).orElse("");
                sender.sendMessage(Component.text("  " + t.name(), NamedTextColor.WHITE)
                        .append(Component.text("  " + t.type().yamlValue()
                                + "  agirlik=" + t.weight()
                                + "  " + t.describeSize()
                                + "  kapi=" + t.doorCount() + " (" + walls + ")", NamedTextColor.GRAY)));
            }
        });
    }

    /** Tek bir sablonun cozumlenmis halini doker \u2014 metadata dogrulamak icin. */
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
            sender.sendMessage(Component.text("  tip: " + t.type().yamlValue()
                    + "   agirlik: " + t.weight(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  boyut: " + t.describeSize()
                    + "   kutu (origin'e gore): " + t.localBox(), NamedTextColor.GRAY));
            if (t.doors().isEmpty()) {
                sender.sendMessage(Component.text("  kapi yok \u2014 bu oda grafa baglanamaz.",
                        NamedTextColor.YELLOW));
                return;
            }
            sender.sendMessage(Component.text("  kapilar:", NamedTextColor.GRAY));
            for (DoorAnchor d : t.doors()) {
                sender.sendMessage(Component.text("    #" + d.index() + " " + d.local()
                        + " -> " + d.wall().turkish() + " duvari", NamedTextColor.WHITE));
            }
        });
    }

    /**
     * Iki odayi kapilarindan birbirine takar \u2014 {@code generation.md} 12. bolum, adim 6.
     *
     * <p>Ebeveyn slot merkezine rot=0 ile konuyor; cocugun rotasyonu ve konumu
     * {@link RoomTemplate#attachTo} ile <b>hesaplaniyor</b> (aranmiyor). Ciktida kutularin
     * kesismedigi de raporlaniyor: sirt sirta konvansiyonu geregi iki oda hicbir blogu
     * paylasmamali ({@code generation.md} 5.2).
     */
    private void connect(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text(
                    "Kullanim: /" + label + " connect <ebeveyn> <cocuk> [ebeveynKapi] [cocukKapi]",
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

    /** Once ebeveyn, sonra cocuk \u2014 sira deterministik olsun diye zincirleniyor. */
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
                + " " + parent.doorOutward(parentDoor).turkish(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  cocuk:   " + child
                + "  kapi#" + childDoor + " " + childAnchor
                + " " + child.doorOutward(childDoor).turkish(), NamedTextColor.GRAY));
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

        // Gecidin blok testiyle dogrulanacagi iki nokta \u2014 konsoldan forceload + execute if block.
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

    /** Future zinciri {@code CompletionException} ile sariyor; kullaniciya asil sebep gosterilir. */
    private void sendFailure(CommandSender sender, String prefix, Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        sender.sendMessage(Component.text(prefix + ": " + cause.getMessage(), NamedTextColor.RED));
        plugin.getLogger().warning(prefix + ": " + cause);
    }

    // ------------------------------------------------------- FAZ 1C: seçim + çakışma

    /**
     * Aday havuzunun ağırlık dağılımını gösterir — {@code generation.md} §5.4 kararının
     * gözle doğrulanması.
     *
     * <p>Yüzdeler kapı sayısından bağımsız olmalı: 4 kapılı bir oda ağırlığını bir kez
     * sayar. Bu komut, config'in söylediğiyle motorun yaptığının aynı olduğunu gösteriyor.
     */
    private void weights(CommandSender sender) {
        RoomTemplateStore store = requireTemplates(sender);
        if (store == null) {
            return;
        }
        store.loadAll(store.list()).whenComplete((templates, error) -> {
            if (error != null) {
                sendFailure(sender, "Şablonlar yüklenemedi", error);
                return;
            }
            RoomLibrary library = new RoomLibrary(templates);
            sender.sendMessage(Component.text(
                    "Aday havuzu (giris/boss hariç — onlar atanıyor, seçilmiyor):",
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
     * Zincir üretir ve slot'a paste eder — FAZ 1C doğrulaması.
     *
     * <p>Kritik path / boss ataması / tıpa burada YOK, onlar 1D. Buradaki iş: ağırlıklı
     * seçim + çakışma testi + geri çekilme + ÖLÜ işaretlemenin birlikte çalıştığını
     * göstermek.
     */
    private void build(CommandSender sender, String label, String[] args) {
        RoomTemplateStore store = requireTemplates(sender);
        SchematicService service = requireSchematics(sender);
        World world = requireWorld(sender);
        if (store == null || service == null || world == null) {
            return;
        }

        int target;
        long seed;
        try {
            target = args.length >= 2 ? Integer.parseInt(args[1]) : 8;
            seed = args.length >= 3 ? Long.parseLong(args[2]) : new Random().nextLong();
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Kullanım: /" + label + " build [odaSayısı] [seed]",
                    NamedTextColor.RED));
            return;
        }
        if (target < 1 || target > 64) {
            sender.sendMessage(Component.text("Oda sayısı 1-64 arasında olmalı: " + target,
                    NamedTextColor.RED));
            return;
        }

        double turnBias = plugin.getConfig().getDouble("generation.turn-bias", 2.0);
        GridSlot slot = plugin.getSlotManager().allocate();
        Aabb slotBounds = slotBounds(slot, world);
        Vec3i center = new Vec3i(slot.originX() + slot.size() / 2, slot.originY(),
                slot.originZ() + slot.size() / 2);

        sender.sendMessage(Component.text("Üretiliyor: " + target + " oda, seed=" + seed
                + ", dönüş yanlılığı=" + turnBias, NamedTextColor.GRAY));

        store.loadAll(store.list())
                .thenApply(templates -> {
                    RoomLibrary library = new RoomLibrary(templates);
                    if (!library.isUsable()) {
                        throw new IllegalStateException(library.describeProblem());
                    }
                    return new ChainGenerator(library, new Random(seed), turnBias)
                            .generate(slotBounds, center, target);
                })
                .thenCompose(result -> pasteLayout(service, world, result)
                        .thenApply(millis -> result))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        plugin.getSlotManager().release(slot.index());
                        sendFailure(sender, "Üretim başarısız", error);
                        return;
                    }
                    reportBuild(sender, world, slot, result, seed);
                });
    }

    /**
     * Slot'un 3B sınırı. X/Z slot'tan, Y dünyanın yükseklik sınırlarından geliyor.
     *
     * <p>X/Z sınırı sert bir gereklilik: taşan bir oda komşu instance'ın bloklarına girer.
     * Y'de slot kavramı yok, dünya sınırı yeterli.
     */
    private static Aabb slotBounds(GridSlot slot, World world) {
        return new Aabb(
                slot.originX(), world.getMinHeight(), slot.originZ(),
                slot.originX() + slot.size() - 1, world.getMaxHeight() - 1,
                slot.originZ() + slot.size() - 1);
    }

    /** Odaları sırayla paste eder — sıra deterministik olsun diye zincirleniyor. */
    private CompletableFuture<Long> pasteLayout(SchematicService service, World world,
                                                ChainGenerator.Result result) {
        CompletableFuture<Long> chain = CompletableFuture.completedFuture(0L);
        for (LayoutNode node : result.layout().nodes()) {
            PlacedRoom room = node.room();
            chain = chain.thenCompose(ignored -> service.load(room.template().name())
                    .thenCompose(clip -> pasteAt(service, world, clip, room)));
        }
        return chain;
    }

    private void reportBuild(CommandSender sender, World world, GridSlot slot,
                             ChainGenerator.Result result, long seed) {
        sender.sendMessage(Component.text("Üretildi — " + slot, NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  oda: " + result.placed() + "/" + result.requested()
                + "   ölü kapı: " + result.deadEnds()
                + "   boş kapı: " + result.layout().openDoorCount()
                + "   denenen aday: " + result.attempts(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  seed: " + seed
                + "  (aynı seed aynı dungeon'ı üretir)", NamedTextColor.DARK_GRAY));

        if (result.stoppedReason() != null) {
            sender.sendMessage(Component.text("  erken durdu: " + result.stoppedReason(),
                    NamedTextColor.YELLOW));
        }

        ChainGenerator.describe(result.layout()).forEach(line ->
                sender.sendMessage(Component.text("  " + line, NamedTextColor.WHITE)));

        // Kendi kendini denetleme: çakışma, slot taşması, hizasız geçit, kopuk graf.
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
        // schematic adi bekleyen konumlar: paste/room 2. argumanda, connect 2. ve 3.
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
        if (args.length == 2 && sub.equals("build")) {
            return List.of("4", "8", "12", "20").stream()
                    .filter(n -> n.startsWith(args[1])).toList();
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
