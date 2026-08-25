package com.takashi.dungeons.generation;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.takashi.dungeons.schematic.SchematicService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Oda şablonlarını yükler: {@code .schem} (geometri) + yanındaki {@code .yml} (metadata).
 *
 * <p><b>İki kaynak, tek nesne.</b> Kutu schematic'ten, kapı anchor'ları {@code .yml}'den
 * geliyor; duvar yönü ikisinin birleşiminden türetiliyor ({@code generation.md} §4).
 * Metadata dosyası yoksa oda kapısız NORMAL sayılır — yüklenir ama grafa bağlanamaz.
 * Sessizce atlanmıyor, {@code /tdungeons rooms} listesinde "kapısız" olarak görünüyor.
 *
 * <p><b>Her şey async.</b> Hem NBT parse hem YAML okuma disk I/O; main thread'de yapılırsa
 * dungeon üretimi sırasında sunucu donar.
 */
public final class RoomTemplateStore {

    private final Plugin plugin;
    private final SchematicService schematics;

    /** Ad (küçük harf) → yüklenmiş şablon. */
    private final Map<String, CompletableFuture<RoomTemplate>> cache = new ConcurrentHashMap<>();

    /**
     * Metadata okumayı main thread'e düşürmemek için açık executor.
     *
     * <p>Gerekli, çünkü {@code SchematicService.load} cache'ten dönerse future <b>zaten
     * tamamlanmış</b> gelir; düz {@code thenApply} o durumda çağıranın thread'inde
     * (yani main thread'de) çalışır ve YAML dosyasını orada okurdu. İkinci yüklemede
     * ortaya çıkan, ilkinde görünmeyen türden bir tuzak.
     */
    private final Executor async;

    public RoomTemplateStore(Plugin plugin, SchematicService schematics) {
        this.plugin = plugin;
        this.schematics = schematics;
        this.async = task -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    /** Schematic klasöründeki bütün oda adları (metadata'sı olsun olmasın). */
    public List<String> list() {
        return schematics.list();
    }

    public void invalidateCache() {
        cache.clear();
    }

    /**
     * Şablonu yükler; aynı ad için ikinci çağrı cache'ten döner.
     *
     * @param name uzantısız dosya adı
     */
    public CompletableFuture<RoomTemplate> load(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        CompletableFuture<RoomTemplate> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        // Future ÖNCE cache'e konuyor, zincir SONRA kuruluyor. Ters sırada
        // (computeIfAbsent içinde zincirleyerek) bir açık var: executor task'ı
        // reddederse — plugin disable olurken olur — zincir senkron patlıyor ve
        // temizlik computeIfAbsent henüz eklemeden çalışıyor; başarısız future
        // sonra cache'e girip orada kalıyor. Bu sırada o pencere yok.
        CompletableFuture<RoomTemplate> created = new CompletableFuture<>();
        CompletableFuture<RoomTemplate> raced = cache.putIfAbsent(key, created);
        if (raced != null) {
            return raced;
        }

        schematics.load(key)
                .thenApplyAsync(clipboard -> build(key, clipboard), async)
                .whenComplete((template, error) -> {
                    if (error != null) {
                        // Başarısız yükleme cache'te kalmasın: dosya düzeltilince yeniden
                        // denensin. İki argümanlı remove — bu arada invalidateCache() olup
                        // başkası yeni bir future koyduysa onu silme.
                        cache.remove(key, created);
                        created.completeExceptionally(error);
                    } else {
                        created.complete(template);
                    }
                });
        return created;
    }

    /** Verilen adların hepsini paralel yükler; biri patlarsa sonuç da patlar. */
    public CompletableFuture<List<RoomTemplate>> loadAll(List<String> names) {
        List<CompletableFuture<RoomTemplate>> futures = names.stream().map(this::load).toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    /** Şablonun metadata dosyası. */
    public File metadataFile(String name) {
        return new File(schematics.getDirectory(), name + ".yml");
    }

    private RoomTemplate build(String name, Clipboard clipboard) {
        Aabb localBox = localBoxOf(clipboard);
        RoomMetadata metadata = readMetadata(name);

        List<DoorAnchor> doors = new ArrayList<>(metadata.doorLocal().size());
        for (int i = 0; i < metadata.doorLocal().size(); i++) {
            Vec3i local = metadata.doorLocal().get(i);
            if (!localBox.contains(local)) {
                throw new IllegalArgumentException(name + ": kapilar[" + i + "] " + local
                        + " odanın dışında. Oda kutusu (origin'e göre): " + localBox
                        + ". Anchor origin'e GÖRE yazılır, schematic'in köşesine göre değil.");
            }
            DoorAnchor door = DoorAnchor.of(i, local, localBox);
            warnIfNotOnWall(name, door, localBox);
            doors.add(door);
        }

        return new RoomTemplate(name, metadata.type(), metadata.weight(), doors, localBox);
    }

    /**
     * Anchor türetilen duvarın yüzeyinde değilse uyarır.
     *
     * <p>Patlatmıyor: içeri çekilmiş (girintili) bir kapı bilinçli bir tasarım tercihi
     * olabilir. Ama {@code generation.md} §9'un dediği gibi "bu sistemlerin kırıldığı tek
     * yer burası" — bir blok kayan anchor duvarları iç içe geçirir. Uyarı, hatayı paste
     * sonrası gözle aramak yerine yükleme anında yakalatıyor.
     */
    private void warnIfNotOnWall(String name, DoorAnchor door, Aabb box) {
        Vec3i local = door.local();
        int actual = switch (door.wall()) {
            case NORTH, SOUTH -> local.z();
            case EAST, WEST -> local.x();
        };
        int expected = switch (door.wall()) {
            case NORTH -> box.minZ();
            case SOUTH -> box.maxZ();
            case WEST -> box.minX();
            case EAST -> box.maxX();
        };
        if (actual != expected) {
            plugin.getLogger().warning(name + ": " + door + " duvarın yüzeyinde değil — "
                    + door.wall().turkish() + " duvarı " + expected + ", anchor " + actual
                    + " (" + Math.abs(actual - expected) + " blok içeride). Bilerek yapılmadıysa "
                    + "bağlanan odalar arasında boşluk kalır.");
        }
    }

    /** Metadata dosyası yoksa {@link RoomMetadata#DEFAULT}; varsa çözülür, bozuksa patlar. */
    private RoomMetadata readMetadata(String name) {
        File file = metadataFile(name);
        if (!file.isFile()) {
            return RoomMetadata.DEFAULT;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return RoomMetadata.parse(yaml, name);
    }

    /**
     * Clipboard'ın sınırlarını <b>origin'e göre</b> kutuya çevirir.
     *
     * <p>Origin'e göre olması şart: rotasyon origin etrafında dönüyor, paste hedefi de
     * origin'in gideceği nokta. Kutuyu clipboard'ın kendi koordinatlarında tutsaydık her
     * hesapta ayrıca origin çıkarmamız gerekirdi — ve bir yerde unutulurdu.
     */
    private static Aabb localBoxOf(Clipboard clipboard) {
        BlockVector3 origin = clipboard.getOrigin();
        BlockVector3 min = clipboard.getMinimumPoint().subtract(origin);
        BlockVector3 max = clipboard.getMaximumPoint().subtract(origin);
        return new Aabb(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }
}
