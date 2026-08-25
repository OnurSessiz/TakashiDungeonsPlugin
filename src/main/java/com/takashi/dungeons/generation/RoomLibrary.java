package com.takashi.dungeons.generation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Yüklenmiş oda şablonlarının havuzu ve <b>ağırlıklı seçim</b> —
 * {@code generation.md} §5.4.
 *
 * <h2>Ağırlık ŞABLONA ait, {@code (şablon × kapı)} çiftine değil</h2>
 *
 * Karar 2026-08-25'te verildi. Aday bir çift olduğu için 4 kapılı bir oda 4 aday üretiyor;
 * ağırlık çifte ait olsaydı o oda ağırlığını <b>4 kez</b> sayardı. Sonuç sadece dağılımın
 * kayması değil, haritacının yazdığı <b>sıralamanın tersine dönmesi</b>:
 *
 * <pre>
 *   test_corridor  2 kapı  ağırlık 150  →  çifte ait olsa %21.4 | şablona ait %25.4
 *   test_cross     4 kapı  ağırlık 100  →  çifte ait olsa %28.6 | şablona ait %16.9
 * </pre>
 *
 * Haritacı koridora daha yüksek ağırlık vermiş ama çifte ait olsaydı cross onu geçerdi.
 * Config'in söylediğinin tersi olurdu — ve sapma birikirdi: çok kapılı oda koydukça daha
 * çok boş kapı açılıyor, her boş kapı yine çok kapılı odayı kayırıyor.
 *
 * <p>Marketplace tarafı da bunu gerektiriyor: {@code agirlik}'i sunucu sahibi YAML'den
 * düzenleyecek ve "200 yazarsam 100'ün iki katı sıklıkta gelir" beklentisi tutulmak zorunda.
 * Kapı sayısının bunu sessizce ezmesi, sebebi hiçbir yerde yazmadığı için teşhis edilemeyen
 * bir hata olurdu.
 *
 * <p><b>Dallanmayı teşvik etmek istersek</b> ayrı ve kapatılabilir bir config düğmesi
 * eklenir — ağırlığın içine gömülmez. §6.4'ün dönüş yanlılığı da aynı kalıpta.
 *
 * <h2>Havuz filtresi</h2>
 * {@link RoomType#GIRIS} ve {@link RoomType#BOSS} normal havuzda <b>yok</b>. İkisi de
 * {@code generation.md} §6.2'de atanıyor; havuzda kalsalardı boss odası dungeon'ın
 * ortasında belirebilirdi.
 */
public final class RoomLibrary {

    private final List<RoomTemplate> all;
    private final List<RoomTemplate> normal;
    private final List<RoomTemplate> branching;
    private final List<RoomTemplate> entrances;
    private final List<RoomTemplate> bosses;

    public RoomLibrary(List<RoomTemplate> templates) {
        this.all = List.copyOf(templates);

        List<RoomTemplate> normalPool = new ArrayList<>();
        List<RoomTemplate> entrancePool = new ArrayList<>();
        List<RoomTemplate> bossPool = new ArrayList<>();
        for (RoomTemplate t : templates) {
            // Kapısız oda grafa bağlanamaz — havuzda durması sonsuz döngü değil ama
            // boşuna deneme üretir. Baştan eleniyor.
            if (t.doorCount() == 0) {
                continue;
            }
            switch (t.type()) {
                case GIRIS -> entrancePool.add(t);
                case BOSS -> bossPool.add(t);
                case NORMAL -> normalPool.add(t);
            }
        }
        this.normal = List.copyOf(normalPool);
        this.entrances = List.copyOf(entrancePool);
        this.bosses = List.copyOf(bossPool);
        this.branching = normalPool.stream().filter(t -> t.doorCount() > 1).toList();
    }

    public List<RoomTemplate> all() {
        return all;
    }

    /** Yan dal ve ara odaların çekildiği havuz — giriş ve boss burada YOK. */
    public List<RoomTemplate> normalPool() {
        return normal;
    }

    /**
     * <b>Kritik path</b> havuzu: {@link #normalPool()}'un tek kapılı odalar çıkarılmış hâli.
     *
     * <p>Tek kapılı bir oda path'e girdiğinde o dal anında ölüyor — bağlandığı kapı dışında
     * devam edecek kapısı yok. 1C'de ölçüldü ({@code generation.md} §6.2): "her boş kapıyı
     * doldur" stratejisi 12 oda hedefinin sadece <b>%70'ini</b> tutturuyor, ve tıkanmaların
     * %86'sı çakışma değil <b>kapı frontier'ının tükenmesi</b>. Tek kapılılar path havuzundan
     * elenince aynı ölçüm <b>%97</b>'ye çıkıyor.
     *
     * <p>Yan dallarda ({@link #normalPool()}) serbestler: orada sona ermeleri zaten istenen
     * şey. Aynı oda bir havuzda sorun, diğerinde özellik.
     */
    public List<RoomTemplate> branchingPool() {
        return branching;
    }

    public List<RoomTemplate> entrances() {
        return entrances;
    }

    public List<RoomTemplate> bosses() {
        return bosses;
    }

    /** Kapısı olan hiç normal oda yoksa üretim yapılamaz. */
    public boolean isUsable() {
        return !normal.isEmpty();
    }

    /** Havuzun neden kullanılamadığını anlatır — komut çıktısında gösterilir. */
    public String describeProblem() {
        if (isUsable()) {
            return null;
        }
        if (all.isEmpty()) {
            return "hiç oda şablonu yüklenmedi (/tdungeons gen ile test odası üret)";
        }
        long doorless = all.stream().filter(t -> t.doorCount() == 0).count();
        return "kapısı olan 'normal' tipte oda yok — " + all.size() + " şablon var, "
                + doorless + " tanesi kapısız, geri kalanı giris/boss";
    }

    /**
     * Havuzdan ağırlıkla bir şablon çeker ve <b>havuzdan çıkarır</b> (yerine koymadan).
     *
     * <p>Yerine koymamak {@code generation.md} §5.3'ün geri çekilmesi için şart: bir şablonun
     * bütün kapıları çakışırsa o şablon elenmiş olmalı, yoksa aynı adayı tekrar tekrar
     * çekip sonsuza kadar denerdik.
     *
     * @param pool   üzerinde çalışılan <b>değiştirilebilir</b> havuz kopyası
     * @param random rastgelelik kaynağı
     * @return çekilen şablon, havuz boşsa {@code null}
     */
    public static RoomTemplate drawWeighted(List<RoomTemplate> pool, RandomGenerator random) {
        if (pool.isEmpty()) {
            return null;
        }
        long total = 0;
        for (RoomTemplate t : pool) {
            total += t.weight();
        }
        // RoomMetadata ağırlığın pozitif olduğunu garanti ediyor; yine de savunma.
        if (total <= 0) {
            return pool.remove(random.nextInt(pool.size()));
        }
        long roll = random.nextLong(total);
        long cumulative = 0;
        for (int i = 0; i < pool.size(); i++) {
            cumulative += pool.get(i).weight();
            if (roll < cumulative) {
                return pool.remove(i);
            }
        }
        return pool.remove(pool.size() - 1);   // kayan nokta değil, buraya düşülmemeli
    }

    /** Ağırlıklı tek seferlik seçim — havuzu değiştirmez (giriş / boss odası için). */
    public static RoomTemplate pickWeighted(List<RoomTemplate> pool, RandomGenerator random) {
        if (pool.isEmpty()) {
            return null;
        }
        List<RoomTemplate> copy = new ArrayList<>(pool);
        return drawWeighted(copy, random);
    }

    /** Havuzun ağırlık dağılımını yüzde olarak döker — karar doğrulaması ve komut çıktısı. */
    public static List<String> describeDistribution(List<RoomTemplate> pool) {
        long total = pool.stream().mapToLong(RoomTemplate::weight).sum();
        if (total <= 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (RoomTemplate t : pool) {
            double percent = 100.0 * t.weight() / total;
            lines.add(String.format("%-16s kapı=%d  ağırlık=%-4d  %%%.1f",
                    t.name(), t.doorCount(), t.weight(), percent));
        }
        return Collections.unmodifiableList(lines);
    }
}
