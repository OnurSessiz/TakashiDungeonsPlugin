package com.takashi.dungeons.generation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * FAZ 1C doğrulaması: boş kapıları sırayla doldurup hedef oda sayısına ulaşan basit üretici.
 *
 * <p><b>Bu daha graf üretimi DEĞİL.</b> {@code generation.md} §6'nın kritik path'i, boss
 * ataması, yan dal kotası ve tıpası burada yok — onlar 1D. Buradaki tek iş, 1C'nin
 * parçalarının (ağırlıklı seçim + çakışma testi + geri çekilme + ÖLÜ işaretleme)
 * gerçekten birlikte çalıştığını gösterebilmek.
 *
 * <p>Kapılar <b>genişlik öncelikli</b> tüketiliyor. Derinlik öncelikli olsaydı tek bir uzun
 * kuyruk çıkar, yerleşim çabuk kendine dolanır ve çakışma testi erken tükenirdi. Genişlik
 * önceliği odaları merkezden dışarı doğru yayıyor — 1D'nin yan dal mantığına da yakın.
 */
public final class ChainGenerator {

    private final RoomLibrary library;
    private final RoomPlacer placer;
    private final RandomGenerator random;

    public ChainGenerator(RoomLibrary library, RandomGenerator random, double turnBias) {
        this.library = library;
        this.random = random;
        this.placer = new RoomPlacer(random, turnBias);
    }

    /**
     * Üretim raporu — komut çıktısı ve testler için.
     *
     * @param layout        kurulan yerleşim
     * @param requested     istenen oda sayısı
     * @param deadEnds      hiçbir adayın oturmadığı kapı sayısı
     * @param attempts      denenen toplam aday sayısı
     * @param stoppedReason hedefe ulaşılamadıysa sebebi, ulaşıldıysa {@code null}
     */
    public record Result(DungeonLayout layout, int requested, int deadEnds,
                         int attempts, String stoppedReason) {

        public int placed() {
            return layout.size();
        }

        public boolean reachedTarget() {
            return layout.size() >= requested;
        }
    }

    /**
     * Girişten başlayıp hedef oda sayısına kadar büyütür.
     *
     * @param bounds     instance slot'unun sınırı
     * @param origin     giriş odasının origin'i (genelde slot merkezi)
     * @param targetRooms hedef oda sayısı, giriş dahil
     */
    public Result generate(Aabb bounds, Vec3i origin, int targetRooms) {
        DungeonLayout layout = new DungeonLayout(bounds);

        // Giriş odası: 'giris' tipi varsa o, yoksa normal havuzdan biri. Fallback şart —
        // haritacı henüz giriş odası çizmediyse üretim durmamalı (out-of-box garantisi).
        List<RoomTemplate> entrancePool = library.entrances().isEmpty()
                ? library.normalPool()
                : library.entrances();
        RoomTemplate entrance = RoomLibrary.pickWeighted(entrancePool, random);
        if (entrance == null) {
            return new Result(layout, targetRooms, 0, 0, library.describeProblem());
        }

        LayoutNode root = layout.addRoot(entrance, origin, Rotation.NONE);

        Deque<OpenDoor> queue = new ArrayDeque<>(root.openDoors());
        int deadEnds = 0;
        int attempts = 0;
        String stopped = null;

        while (layout.size() < targetRooms) {
            if (queue.isEmpty()) {
                stopped = "boş kapı kalmadı — yerleşim tıkandı ("
                        + deadEnds + " kapı ölü işaretlendi)";
                break;
            }
            OpenDoor door = queue.poll();
            // Kapı bu arada başka bir yoldan dolmuş olabilir mi: hayır, her kapı kuyruğa
            // bir kez giriyor. Yine de durumu kontrol etmek ucuz ve ileride yan dal
            // mantığı geldiğinde koruma sağlıyor.
            if (layout.node(door.nodeId()).doorState(door.doorIndex()) != DoorState.BOS) {
                continue;
            }

            RoomPlacer.Attempt attempt = placer.fill(layout, door, library.normalPool());
            attempts += attempt.candidatesTried();

            if (attempt.success()) {
                queue.addAll(attempt.placed().openDoors());
            } else {
                deadEnds++;
            }
        }

        // Hedefe ulaşıldıktan sonra kalan kapılar BOŞ kalıyor — 1D onları yan dal için
        // kullanacak ya da tıpalayacak. Burada zorla ölü işaretlemek bilgi kaybı olurdu.
        return new Result(layout, targetRooms, deadEnds, attempts, stopped);
    }

    /** Yerleşimi satır satır özetler — komut çıktısı. */
    public static List<String> describe(DungeonLayout layout) {
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
            lines.add("#" + node.id() + " " + node.template().name()
                    + " rot=" + node.room().rotation().degrees()
                    + " d=" + node.depth()
                    + " @" + node.room().origin()
                    + "  [" + doors + "]");
        }
        return lines;
    }
}
