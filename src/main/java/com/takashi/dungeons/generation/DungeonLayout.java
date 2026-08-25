package com.takashi.dungeons.generation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Bir dungeon'ın yerleşimi: kabul edilmiş odalar, kapı bağlantıları ve çakışma testi.
 *
 * <p><b>Neden bu sınıf var:</b> Anchor tabanlı yerleşimde odalar sabit hücrelere oturmuyor,
 * dolayısıyla çakışma <b>matematiksel olarak mümkün</b> ({@code generation.md} §2). Hücre
 * grid'inde bu sınıfa gerek yoktu; serbest yerleşimin bedeli bu.
 *
 * <p><b>Kaba kuvvet yeterli.</b> En büyük dungeon 20 oda ({@code generation.md} §6.1), yeni
 * aday başına 20 kutu testi. Spatial hash / octree gereksiz karmaşıklık olurdu — 20 elemanlı
 * bir listede lineer tarama, hash'in kendi maliyetinden ucuz.
 *
 * <p>Sınıf <b>Bukkit'ten bağımsız</b>: slot sınırı {@link Aabb} olarak dışarıdan veriliyor.
 * Böylece bütün yerleşim mantığı sunucu açmadan test edilebiliyor.
 */
public final class DungeonLayout {

    private final Aabb bounds;
    private final List<LayoutNode> nodes = new ArrayList<>();

    /**
     * @param bounds instance slot'unun sınırı — hiçbir oda bunun dışına taşamaz.
     *               İki party'nin blokları kesişmesin diye ({@code isleyis.md}, grid slot).
     */
    public DungeonLayout(Aabb bounds) {
        this.bounds = bounds;
    }

    public Aabb bounds() {
        return bounds;
    }

    public List<LayoutNode> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public int size() {
        return nodes.size();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public LayoutNode node(int id) {
        return nodes.get(id);
    }

    /** Grafın kökü — giriş odası. Boşsa {@code null}. */
    public LayoutNode root() {
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    /**
     * Aday oda buraya sığar mı — {@code generation.md} §5.2 adım 5.
     *
     * <p>İki ayrı ret sebebi var ve ikisi de gerekli: slot sınırını taşmak (komşu
     * instance'a girer) ve yerleşmiş bir odayla kesişmek (kendi içinde bozulur).
     *
     * @return sığıyorsa {@code null}, sığmıyorsa sebebi anlatan kısa metin
     */
    public String rejectReason(Aabb candidate) {
        if (!contains(bounds, candidate)) {
            return "slot sınırını taşıyor";
        }
        for (LayoutNode node : nodes) {
            if (node.bounds().intersects(candidate)) {
                return "oda#" + node.id() + " (" + node.template().name() + ") ile çakışıyor";
            }
        }
        return null;
    }

    public boolean fits(Aabb candidate) {
        return rejectReason(candidate) == null;
    }

    /** Odayı yerleşime kabul eder. Çakışma testi ÇAĞIRANIN sorumluluğu. */
    public LayoutNode add(PlacedRoom room, int depth) {
        LayoutNode node = new LayoutNode(nodes.size(), room, depth);
        nodes.add(node);
        return node;
    }

    /**
     * İlk odayı yerleştirir — giriş odası, verilen origin'e rotasyonsuz oturur.
     *
     * @throws IllegalStateException oda slot'a sığmıyorsa (giriş odası için sessiz
     *                               başarısızlık kabul edilemez: dungeon hiç kurulamaz)
     */
    public LayoutNode addRoot(RoomTemplate template, Vec3i origin, Rotation rotation) {
        if (!nodes.isEmpty()) {
            throw new IllegalStateException("Kök oda zaten var: " + root());
        }
        PlacedRoom room = PlacedRoom.of(template, rotation, origin);
        String reason = rejectReason(room.bounds());
        if (reason != null) {
            throw new IllegalStateException("Giriş odası '" + template.name()
                    + "' slot'a sığmıyor: " + reason + ". Oda " + template.describeSize()
                    + ", slot " + bounds.sizeX() + "×" + bounds.sizeZ() + ".");
        }
        return add(room, 0);
    }

    /** İki kapıyı karşılıklı bağlar — bağlantı her zaman çift yönlü kaydedilir. */
    public void link(int nodeA, int doorA, int nodeB, int doorB) {
        nodes.get(nodeA).link(doorA, nodeB, doorB);
        nodes.get(nodeB).link(doorB, nodeA, doorA);
    }

    public void markDead(int nodeId, int doorIndex) {
        nodes.get(nodeId).markDead(doorIndex);
    }

    /** Bütün odaların doldurulmayı bekleyen kapıları, oda sırasına göre. */
    public List<OpenDoor> openDoors() {
        List<OpenDoor> open = new ArrayList<>();
        for (LayoutNode node : nodes) {
            open.addAll(node.openDoors());
        }
        return open;
    }

    public int openDoorCount() {
        int count = 0;
        for (LayoutNode node : nodes) {
            count += node.openDoors().size();
        }
        return count;
    }

    public int deadDoorCount() {
        int count = 0;
        for (LayoutNode node : nodes) {
            count += node.deadDoors().size();
        }
        return count;
    }

    /**
     * Yerleşimin kendi içinde tutarlı olduğunu doğrular — test ve hata ayıklama için.
     *
     * <p>Üretim yolunda çağrılmıyor; bir hata varsa <i>hangi</i> değişmezin bozulduğunu
     * söylemesi için var. Prosedürel üretimde en pahalı hata, bozuk çıktının sessizce
     * kabul edilmesi.
     *
     * @return bulunan sorunlar; liste boşsa yerleşim tutarlı
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();

        for (int i = 0; i < nodes.size(); i++) {
            LayoutNode a = nodes.get(i);

            if (!contains(bounds, a.bounds())) {
                problems.add("oda#" + a.id() + " slot sınırını taşıyor: " + a.bounds());
            }
            for (int j = i + 1; j < nodes.size(); j++) {
                LayoutNode b = nodes.get(j);
                if (a.bounds().intersects(b.bounds())) {
                    problems.add("oda#" + a.id() + " ile oda#" + b.id() + " çakışıyor");
                }
            }

            for (int d = 0; d < a.doorCount(); d++) {
                if (a.doorState(d) != DoorState.BAGLI) {
                    continue;
                }
                int otherId = a.linkedNode(d);
                if (otherId < 0 || otherId >= nodes.size()) {
                    problems.add("oda#" + a.id() + " kapı#" + d + " geçersiz node'a bağlı: " + otherId);
                    continue;
                }
                // Sırt sırta: iki anchor tam bir blok arayla ve BİRBİRİNE bakıyor olmalı.
                LayoutNode other = nodes.get(otherId);
                Vec3i mine = a.room().doorAnchor(d);
                Vec3i expected = mine.plus(a.room().doorOutward(d).step());
                boolean matched = false;
                for (int e = 0; e < other.doorCount(); e++) {
                    if (other.linkedNode(e) == a.id() && other.room().doorAnchor(e).equals(expected)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    problems.add("oda#" + a.id() + " kapı#" + d + " ile oda#" + otherId
                            + " arasındaki geçit hizasız (beklenen " + expected + ")");
                }
            }
        }

        if (!nodes.isEmpty()) {
            int reached = reachableCount();
            if (reached != nodes.size()) {
                problems.add("graf kopuk: " + nodes.size() + " odadan " + reached
                        + " tanesine girişten ulaşılabiliyor");
            }
        }
        return problems;
    }

    /** Girişten kapı bağlantılarını izleyerek ulaşılabilen oda sayısı. */
    private int reachableCount() {
        boolean[] seen = new boolean[nodes.size()];
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        seen[0] = true;
        int count = 0;
        while (!queue.isEmpty()) {
            LayoutNode node = nodes.get(queue.poll());
            count++;
            for (int d = 0; d < node.doorCount(); d++) {
                int next = node.linkedNode(d);
                if (next >= 0 && !seen[next]) {
                    seen[next] = true;
                    queue.add(next);
                }
            }
        }
        return count;
    }

    /** {@code outer} kutusu {@code inner}'ı tamamen içeriyor mu. */
    private static boolean contains(Aabb outer, Aabb inner) {
        return inner.minX() >= outer.minX() && inner.maxX() <= outer.maxX()
                && inner.minY() >= outer.minY() && inner.maxY() <= outer.maxY()
                && inner.minZ() >= outer.minZ() && inner.maxZ() <= outer.maxZ();
    }
}
