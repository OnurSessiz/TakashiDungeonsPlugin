package com.takashi.dungeons.generation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Dungeon grafını üretir — {@code generation.md} §6.
 *
 * <h2>Kritik path önce, boss sona ATANIR</h2>
 *
 * Odaları rastgele serpip "en uzaktakine boss koyalım" denirse boss'un girişten kaç oda
 * uzakta olduğu kontrol edilemez. Path'i önce kurmak <b>oynanış süresini garanti altına
 * alıyor</b>: rastgelelik çeşitlilik için, iskelet garanti için.
 *
 * <h2>Path havuzunda tek kapılı oda YOK</h2>
 *
 * 1C'de ölçüldü ({@code generation.md} §6.2'deki kutu): "her boş kapıyı doldur" stratejisi
 * 12 oda hedefinin sadece <b>%70'ini</b> tutturuyor ve tıkanmaların %86'sı çakışma değil,
 * kapı frontier'ının tükenmesi — bir dallanma süreci sönümlenmesi. Tek kapılı bir oda
 * çekildiğinde o dal anında ölüyor.
 *
 * <p>Path kurulurken tek kapılı şablonlar elenince aynı ölçüm <b>%97</b>'ye çıkıyor.
 * Yan dallarda serbestler — orada zaten sona ermeleri isteniyor.
 *
 * <h2>Tıkanırsa yeniden denenir</h2>
 *
 * Path hedef uzunluğa ulaşamazsa bütün deneme çöpe atılıp yeni bir tohumla baştan
 * başlanıyor. Kısa dungeon'ı sessizce kabul etmek marketplace tarafında kabul edilemez:
 * kullanıcı "medium" seçtiyse medium almalı. Denemeler tükenirse <b>en iyi</b> deneme
 * kullanılıyor ve sonuç uyarıyla raporlanıyor — sessizce küçük dungeon verilmiyor.
 */
public final class DungeonGenerator {

    private final RoomLibrary library;
    private final double turnBias;
    private final int maxAttempts;

    public DungeonGenerator(RoomLibrary library, double turnBias, int maxAttempts) {
        this.library = library;
        this.turnBias = turnBias;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /**
     * Üretim sonucu.
     *
     * @param layout           kurulan yerleşim
     * @param size             istenen boyut
     * @param targetRooms      hedef oda sayısı
     * @param targetPathLength hedef kritik path uzunluğu (giriş + boss dahil)
     * @param pathLength       ulaşılan path uzunluğu
     * @param bossNodeId       boss odasının node id'si, yoksa -1
     * @param attemptsUsed     kaç deneme harcandı
     * @param seed             üretimi tekrarlamak için gereken tohum
     * @param warning          bir şey tam istendiği gibi olmadıysa açıklaması, yoksa {@code null}
     */
    public record Result(DungeonLayout layout, DungeonSize size, int targetRooms,
                         int targetPathLength, int pathLength, int bossNodeId,
                         int attemptsUsed, long seed, String warning) {

        public int rooms() {
            return layout.size();
        }

        public boolean perfect() {
            return warning == null;
        }

        /** Tıpa basılacak kapılar — BOŞ ve ÖLÜ olanların hepsi ({@code generation.md} §7). */
        public List<PlugTarget> plugTargets() {
            List<PlugTarget> targets = new ArrayList<>();
            for (LayoutNode node : layout.nodes()) {
                for (int i = 0; i < node.doorCount(); i++) {
                    DoorState state = node.doorState(i);
                    if (state == DoorState.BAGLI) {
                        continue;
                    }
                    targets.add(new PlugTarget(node.room().doorAnchor(i),
                            node.room().doorOutward(i), node.bounds(),
                            state == DoorState.OLU));
                }
            }
            return targets;
        }
    }

    /**
     * Dungeon üretir. Başarısız denemeler atılır, en iyisi döner.
     *
     * @param bounds instance slot'unun sınırı
     * @param origin giriş odasının origin'i (genelde slot merkezi)
     * @param size   istenen boyut
     * @param seed   ana tohum — aynı tohum aynı dungeon'ı verir
     */
    public Result generate(Aabb bounds, Vec3i origin, DungeonSize size, long seed) {
        if (!library.isUsable()) {
            return new Result(new DungeonLayout(bounds), size, 0, 0, 0, -1, 0, seed,
                    library.describeProblem());
        }

        Result best = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            // Deneme tohumu ana tohumdan TÜRETİLİYOR: bütün üretim tek bir seed'den
            // tekrar edilebilir kalsın diye. Rastgele yeni tohum çekilseydi "şu bozuk
            // dungeon"u geri getirmek imkânsız olurdu.
            Result candidate = attemptOnce(bounds, origin, size, seed, attempt);
            if (candidate.perfect()) {
                return candidate;
            }
            if (best == null || isBetter(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    /** Daha uzun path daha değerli; eşitse daha çok oda. */
    private static boolean isBetter(Result a, Result b) {
        if (a.pathLength() != b.pathLength()) {
            return a.pathLength() > b.pathLength();
        }
        return a.rooms() > b.rooms();
    }

    private Result attemptOnce(Aabb bounds, Vec3i origin, DungeonSize size,
                               long seed, int attempt) {
        // Seeds.derive: ardisik tohumlarda korelasyonu kiriyor. new Random(seed) burada
        // KULLANILAMAZ -- ilk nextInt(kucuk) cagrisi ardisik tohumlarda hep ayni degeri
        // verir ve small dungeon'larin oda sayisi aralik yerine tek bir degere sabitlenir.
        // Olcum ve sayilar Seeds sinifinin yorumunda.
        RandomGenerator random = Seeds.derive(seed, attempt);
        RoomPlacer placer = new RoomPlacer(random, turnBias);

        int targetRooms = size.pickRoomCount(random);
        int targetPath = DungeonSize.criticalPathLength(targetRooms);
        DungeonLayout layout = new DungeonLayout(bounds);

        // ---- 1) Giriş odası
        RoomTemplate entrance = pickEntrance(random);
        if (entrance == null) {
            return new Result(layout, size, targetRooms, targetPath, 0, -1,
                    attempt + 1, seed, library.describeProblem());
        }
        try {
            layout.addRoot(entrance, origin, Rotation.NONE);
        } catch (IllegalStateException e) {
            return new Result(layout, size, targetRooms, targetPath, 0, -1,
                    attempt + 1, seed, e.getMessage());
        }

        // ---- 2) Kritik path — giriş + (targetPath-2) normal oda + boss
        // Tek kapılı şablonlar bu havuzda YOK: çekilirlerse dal anında ölür (§6.2 ölçümü).
        List<RoomTemplate> pathPool = library.branchingPool();
        if (pathPool.isEmpty()) {
            // Out-of-box: haritacı henüz çok kapılı oda çizmediyse üretim durmasın,
            // tam havuza düş. Path garantisi zayıflar ama dungeon yine çıkar.
            pathPool = library.normalPool();
        }

        // Path'e giriş dahil targetPath-1 oda konuyor; sonuncusunu boss tamamlayacak.
        int pathRooms = 1;                  // giriş odası path'in ilk düğümü
        LayoutNode tip = layout.root();
        String warning = null;

        while (pathRooms < targetPath - 1) {
            OpenDoor door = pickOpenDoor(tip, random);
            if (door == null) {
                warning = "kritik path " + pathRooms + "/" + targetPath
                        + " odada tıkandı: " + tip.template().name() + " odasında boş kapı kalmadı";
                break;
            }
            RoomPlacer.Attempt placed = placer.fill(layout, door, pathPool);
            if (!placed.success()) {
                warning = "kritik path " + pathRooms + "/" + targetPath
                        + " odada tıkandı: " + placed.failReason();
                break;
            }
            tip = placed.placed();
            pathRooms++;
        }

        // ---- 3) Yan dallar — kalan kota, ama BOSS İÇİN BİR ODA AYRILIYOR (§6.3)
        //
        // Yan dallar boss'tan ÖNCE büyüyor. Sıra bilerek böyle ve iki şeyi birden çözüyor:
        //
        // (a) targetPath == 2 olduğunda (small, hedef 3 oda) path sadece girişten ibaret.
        //     Boss önce bağlansaydı tek kapılı girişin kapısı boss'a giderdi, boss da
        //     terminal olduğu için yan dala BOŞ kapı kalmazdı — 3 odalık dungeon
        //     üretilemezdi. Ölçüldü: small hedefi 3 hiç görünmüyordu, hep 4-6 çıkıyordu.
        //
        // (b) Boss'a çok daha fazla aday kapı kalıyor. Önce bağlandığında tek bir kapı
        //     deneniyordu ve 33×33 boss odası çakışırsa dungeon BOSS'SUZ kalıyordu
        //     (2000 medium üretimde 4 kez). Boss'suz dungeon oyuncuya hedef vermiyor.
        growBranches(layout, placer, random, targetRooms - 1, -1);

        // ---- 4) Boss — random değil, ATAMA: girişten EN UZAK boş kapıya.
        //
        // "Path'in son düğümü" kuralının pratikteki karşılığı bu. En derin kapıyı seçmek
        // boss'un girişe olan mesafesini en büyük yapıyor; §6.2'nin garanti etmek istediği
        // şey de tam olarak o mesafe. Tek bir kapıya bağlı kalmak yerine derinlik sırasıyla
        // hepsi deneniyor — çakışma yüzünden boss'un düşmesi böylece ortadan kalkıyor.
        int bossNodeId = -1;
        if (library.bosses().isEmpty()) {
            // Out-of-box garantisi: boss odası çizilmemişse üretim yine tamamlanır.
            warning = "boss odası şablonu yok — dungeon boss'suz üretildi";
        } else {
            bossNodeId = attachBoss(layout, placer, random);
            if (bossNodeId < 0) {
                warning = "boss odası hiçbir boş kapıya sığmadı ("
                        + layout.openDoorCount() + " kapı denendi)";
            }
        }

        // Kritik path uzunluğu = girişten boss'a giden rotadaki oda sayısı.
        // Boss yoksa en derin odanın rotası.
        int pathLength = bossNodeId >= 0
                ? layout.node(bossNodeId).depth() + 1
                : deepestDepth(layout) + 1;

        if (warning == null && pathLength < targetPath) {
            warning = "kritik path kısa kaldı: " + pathLength + "/" + targetPath;
        }
        if (warning == null && layout.size() < targetRooms) {
            warning = "oda kotası dolmadı: " + layout.size() + "/" + targetRooms
                    + " (yer kalmadı)";
        }
        return new Result(layout, size, targetRooms, targetPath, pathLength,
                bossNodeId, attempt + 1, seed, warning);
    }

    /**
     * Boss odasını girişten en uzak boş kapıya bağlar.
     *
     * <p>Kapılar <b>derinliğe göre azalan</b> sırayla deneniyor: ilk oturan kazanıyor.
     * Böylece boss mümkün olan en uzak noktaya gidiyor ve tek bir kapının çakışması
     * yüzünden boss'suz dungeon üretilmiyor.
     *
     * @return boss node id'si, hiçbir kapıya sığmadıysa -1
     */
    private int attachBoss(DungeonLayout layout, RoomPlacer placer, RandomGenerator random) {
        List<OpenDoor> doors = new ArrayList<>(layout.openDoors());
        doors.sort((a, b) -> Integer.compare(
                layout.node(b.nodeId()).depth(), layout.node(a.nodeId()).depth()));

        for (OpenDoor door : doors) {
            if (layout.node(door.nodeId()).doorState(door.doorIndex()) != DoorState.BOS) {
                continue;
            }
            RoomPlacer.Attempt placed = placer.fill(layout, door, library.bosses());
            if (placed.success()) {
                return placed.placed().id();
            }
            // fill() başarısızsa kapıyı ÖLÜ işaretledi — zaten bir daha denenmeyecek.
        }
        return -1;
    }

    /** Yerleşimdeki en büyük derinlik. */
    private static int deepestDepth(DungeonLayout layout) {
        int max = 0;
        for (LayoutNode node : layout.nodes()) {
            max = Math.max(max, node.depth());
        }
        return max;
    }

    /**
     * Kalan kotayı yan dallarla doldurur — {@code generation.md} §6.3.
     *
     * <p>Kritik path bir odaya girdiğinde odanın diğer kapıları kullanılmamış kalıyor;
     * dallanma buralardan başlıyor. Oyuncu odaya girip üç çıkış görüyor: biri boss'a,
     * ikisi ganimete. Hangisinin hangisi olduğunu bilmemesi labirent hissini veren şey.
     *
     * <p>Burada <b>tam havuz</b> kullanılıyor — çıkmaz odalar dahil. Yan dalın sona ermesi
     * istenen bir şey; path'te sorun olan tam da burada özellik.
     *
     * <p>Genişlik öncelikli: derinlik öncelikli olsaydı tek uzun kuyruk çıkar ve yerleşim
     * çabuk kendine dolanırdı.
     */
    private void growBranches(DungeonLayout layout, RoomPlacer placer,
                              RandomGenerator random, int targetRooms, int bossNodeId) {
        Deque<OpenDoor> queue = new ArrayDeque<>();
        for (LayoutNode node : layout.nodes()) {
            if (node.id() != bossNodeId) {
                queue.addAll(node.openDoors());
            }
        }

        while (layout.size() < targetRooms && !queue.isEmpty()) {
            OpenDoor door = queue.poll();
            if (layout.node(door.nodeId()).doorState(door.doorIndex()) != DoorState.BOS) {
                continue;
            }
            RoomPlacer.Attempt placed = placer.fill(layout, door, library.normalPool());
            if (placed.success()) {
                queue.addAll(placed.placed().openDoors());
            }
        }
    }

    private RoomTemplate pickEntrance(RandomGenerator random) {
        // Giriş odası çizilmemişse normal havuzdan biri kullanılır — out-of-box garantisi.
        List<RoomTemplate> pool = library.entrances().isEmpty()
                ? library.normalPool()
                : library.entrances();
        return RoomLibrary.pickWeighted(pool, random);
    }

    /** Odanın boş kapılarından rastgele biri; hiç yoksa {@code null}. */
    private static OpenDoor pickOpenDoor(LayoutNode node, RandomGenerator random) {
        List<OpenDoor> open = new ArrayList<>(node.openDoors());
        if (open.isEmpty()) {
            return null;
        }
        return open.get(random.nextInt(open.size()));
    }

    /** Yerleşimi satır satır özetler — komut çıktısı. */
    public static List<String> describe(DungeonLayout layout, int bossNodeId) {
        List<String> lines = new ArrayList<>();
        for (LayoutNode node : layout.nodes()) {
            StringBuilder doors = new StringBuilder();
            for (int i = 0; i < node.doorCount(); i++) {
                doors.append(switch (node.doorState(i)) {
                    case BAGLI -> "→#" + node.linkedNode(i);
                    case OLU -> "ölü";
                    case BOS -> "boş";
                }).append(i == node.doorCount() - 1 ? "" : " ");
            }
            String tag = node.id() == 0 ? " [GİRİŞ]" : (node.id() == bossNodeId ? " [BOSS]" : "");
            lines.add("#" + node.id() + " " + node.template().name()
                    + " rot=" + node.room().rotation().degrees()
                    + " d=" + node.depth()
                    + " @" + node.room().origin()
                    + "  [" + doors + "]" + tag);
        }
        return Collections.unmodifiableList(lines);
    }
}
