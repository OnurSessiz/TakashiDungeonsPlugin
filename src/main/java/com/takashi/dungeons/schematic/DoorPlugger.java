package com.takashi.dungeons.schematic;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.Direction;
import com.takashi.dungeons.generation.PlugTarget;
import com.takashi.dungeons.generation.Vec3i;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Boşluğa açılan kapıları kapatır — {@code generation.md} §7 (tıpa).
 *
 * <h2>Neden oda varyantı değil, tıpa</h2>
 * "Her odanın 1/2/3 kapılı versiyonu olsun" fikri elendi: kapı <b>sayısı</b> versiyonu
 * tanımlamıyor, kapı <b>kümesi</b> tanımlıyor ({K,G} ≠ {K,D}). Rotasyonla 5 şekle inse bile
 * 40 oda × 5 = 200 schematic — harita ekibi projenin en dar boğazı. Tıpa aynı görsel sonucun
 * neredeyse tamamını <b>0 dosyayla</b> veriyor.
 *
 * <h2>Açıklığın boyutu ölçülüyor, verilmiyor</h2>
 * Metadata'da "kapı 3×3" diye bir alan <b>yok</b>. Motor, anchor'dan başlayıp odanın duvar
 * düzleminde hava bloklarını tarayarak açıklığı kendisi buluyor. Sebebi §9'un ruhu:
 * yazılabilen her alan yanlış yazılabilen bir alandır. Haritacı 3×4 bir kapı çizerse tıpa
 * yine tutar; kemerli, basamaklı, asimetrik açıklıklar da çalışır.
 *
 * <h2>Malzeme de ölçülüyor</h2>
 * Tıpa bloğu config'den gelmiyor — açıklığın <b>kenarındaki duvar bloğu</b> örnekleniyor.
 * Böylece Nether odasında nether brick, End odasında end stone çıkıyor; biome başına ayrı
 * ayar gerekmiyor. {@code generation.md} §7'nin "biome tıpası" seçeneğinin faydasını
 * dosya maliyeti olmadan veriyor.
 *
 * <h2>Tarama neden odanın kutusuyla sınırlı</h2>
 * Duvar düzlemi (örn. {@code z = Z0}) matematiksel olarak sonsuz. Odanın dışında o düzlem
 * void — yani hava. Sınır konmasaydı tarama açıklıktan çıkıp boşluğa sızar ve dungeon'ın
 * yarısını taş duvarla doldururdu.
 */
public final class DoorPlugger {

    /**
     * Bir açıklıkta taranacak en fazla blok. 3×3 bir kapı 9 blok; 64 bolca pay bırakıyor.
     *
     * <p>Sınır asıl olarak <b>bozuk metadata'ya karşı</b>: anchor duvarın üstünde değil de
     * odanın içindeyse ({@code RoomTemplateStore} bunu uyarıyor ama patlatmıyor) tarama
     * duvar düzleminde değil boş bir iç düzlemde başlar ve odanın bütün kesitini doldururdu.
     * Sınır aşılırsa o kapı atlanıyor ve uyarı yazılıyor.
     */
    private static final int MAX_OPENING_BLOCKS = 64;

    private final Plugin plugin;
    private final boolean async;

    public DoorPlugger(Plugin plugin, boolean async) {
        this.plugin = plugin;
        this.async = async;
    }

    /** Tıpa işleminin sonucu. */
    public record Report(int plugged, int skipped, int blocks, List<String> warnings) {
    }

    /**
     * Verilen kapıların hepsini kapatır.
     *
     * <p>Tek bir {@link EditSession} kullanılıyor: her kapı için ayrı session açmak
     * FAWE'nin batch'lemesini bozar ve büyük dungeon'da gözle görülür yavaşlar.
     */
    public CompletableFuture<Report> plugAll(World world, List<PlugTarget> targets) {
        CompletableFuture<Report> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(plugBlocking(world, targets));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        if (async) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
        return future;
    }

    private Report plugBlocking(World world, List<PlugTarget> targets) {
        int plugged = 0;
        int skipped = 0;
        int blocks = 0;
        List<String> warnings = new ArrayList<>();

        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {

            for (PlugTarget target : targets) {
                Opening opening = scan(session, target);
                if (opening == null) {
                    skipped++;
                    warnings.add(target.anchor() + " " + target.outward().turkish()
                            + ": açıklık " + MAX_OPENING_BLOCKS
                            + " bloktan büyük, atlandı (anchor duvarın üstünde mi?)");
                    continue;
                }
                if (opening.cells.isEmpty()) {
                    // Zaten kapalı: aynı dungeon iki kez tıpalanmışsa ya da haritacı
                    // kapıyı kapalı çizmişse olur. Hata değil.
                    continue;
                }
                if (opening.material == null) {
                    skipped++;
                    warnings.add(target.anchor() + " " + target.outward().turkish()
                            + ": açıklığın kenarında duvar bloğu bulunamadı, malzeme örneklenemedi");
                    continue;
                }
                for (BlockVector3 cell : opening.cells) {
                    session.setBlock(cell, opening.material);
                }
                plugged++;
                blocks += opening.cells.size();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Tıpa başarısız: " + e.getMessage(), e);
        }
        return new Report(plugged, skipped, blocks, warnings);
    }

    /** Taranmış açıklık: doldurulacak hücreler + kenarından örneklenmiş duvar malzemesi. */
    private record Opening(List<BlockVector3> cells, BlockState material) {
    }

    /**
     * Açıklığı duvar düzleminde tarar ve kenarından malzeme örnekler.
     *
     * <p>Düzlem, {@code outward} yönünün ekseni sabit tutularak elde ediliyor: kuzey/güney
     * kapısında Z sabit (X-Y düzlemi), doğu/batı kapısında X sabit (Z-Y düzlemi).
     *
     * @return açıklık, ya da sınır aşıldıysa {@code null}
     */
    private static Opening scan(EditSession session, PlugTarget target) {
        Vec3i anchor = target.anchor();
        Aabb bounds = target.roomBounds();
        boolean alongX = target.outward() == Direction.NORTH || target.outward() == Direction.SOUTH;

        List<BlockVector3> cells = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<BlockVector3> queue = new ArrayDeque<>();
        // Kenardan örneklenen malzemeler — en sık görüleni kullanılıyor. Tek bir komşuya
        // bakmak yanıltıcı olabilir: kapının hemen yanında meşale, pencere ya da farklı
        // bir süs bloğu olabilir.
        Map<BlockState, Integer> materials = new HashMap<>();

        BlockVector3 start = BlockVector3.at(anchor.x(), anchor.y(), anchor.z());
        queue.add(start);
        seen.add(key(start));

        while (!queue.isEmpty()) {
            BlockVector3 pos = queue.poll();

            if (!contains(bounds, pos)) {
                continue;   // odanın dışına sızma
            }
            BlockState state = session.getBlock(pos);
            if (!state.getBlockType().getMaterial().isAir()) {
                materials.merge(state, 1, Integer::sum);   // açıklığın kenarı = duvar
                continue;
            }

            cells.add(pos);
            if (cells.size() > MAX_OPENING_BLOCKS) {
                return null;
            }
            for (BlockVector3 next : neighbours(pos, alongX)) {
                if (seen.add(key(next))) {
                    queue.add(next);
                }
            }
        }

        BlockState material = materials.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        return new Opening(cells, material);
    }

    /** Duvar düzlemi içindeki 4 komşu: yukarı/aşağı + duvar boyunca iki yan. */
    private static BlockVector3[] neighbours(BlockVector3 p, boolean alongX) {
        if (alongX) {
            return new BlockVector3[]{
                    p.add(1, 0, 0), p.add(-1, 0, 0), p.add(0, 1, 0), p.add(0, -1, 0)};
        }
        return new BlockVector3[]{
                p.add(0, 0, 1), p.add(0, 0, -1), p.add(0, 1, 0), p.add(0, -1, 0)};
    }

    private static boolean contains(Aabb box, BlockVector3 p) {
        return p.x() >= box.minX() && p.x() <= box.maxX()
                && p.y() >= box.minY() && p.y() <= box.maxY()
                && p.z() >= box.minZ() && p.z() <= box.maxZ();
    }

    /** Koordinatı tek bir long'a paketler — ziyaret kümesi için. */
    private static long key(BlockVector3 p) {
        return ((long) (p.x() & 0x3FFFFF) << 42)
                | ((long) (p.y() & 0xFFFFF) << 22)
                | (p.z() & 0x3FFFFF);
    }
}
