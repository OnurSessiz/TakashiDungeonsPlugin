package com.takashi.dungeons.generation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Boş bir kapıya oda takar: aday seçer, çakışmayı test eder, geçen ilkini yerleştirir —
 * {@code generation.md} §5.2 ve §5.3.
 *
 * <p><b>1B ile ayrım.</b> {@link RoomTemplate#attachTo} saf geometri: "bu oda buraya şu
 * açıyla oturur." Bu sınıfın sorusu farklı: <b>"oturabilir mi, ve hangi oda seçilmeli?"</b>
 * Ayrım, geometriyi dünyaya ve rastgeleliğe erişmeden test edilebilir tutuyor.
 *
 * <p><b>İki aşamalı seçim</b> ({@code generation.md} §5.4 — karar verildi):
 * <ol>
 *   <li>Şablon <b>ağırlıkla</b> çekilir, havuzdan çıkarılır (yerine konmadan)</li>
 *   <li>O şablonun kapıları sıralanır ve sırayla denenir</li>
 *   <li>Hiçbiri oturmazsa şablon elenmiş olur, 1'e dönülür</li>
 *   <li>Havuz boşalırsa kapı <b>ÖLÜ</b> işaretlenir → §7 tıpa</li>
 * </ol>
 */
public final class RoomPlacer {

    /**
     * Dönüş yanlılığının gücü — {@code generation.md} §6.4.
     *
     * <p>Kapı seçimi aşamasında uygulanıyor, <b>şablon seçimi aşamasında değil</b>.
     * Sebebi §5.4: şablon aşamasına dokunmak {@code agirlik}'in anlamını bozar, ve
     * ağırlığın kapı sayısından bağımsız kalması için verilen kararın altını oyar.
     *
     * <p>1.0 = ceza yok. Yüksek değer düz devamı daha çok geriye iter.
     */
    private final double turnBias;

    private final RandomGenerator random;
    /**
     * {@link Collections#shuffle} eski {@link java.util.Random} istiyor. Tek bir örnek
     * tutuluyor: her çağrıda yenisini üretmek aynı tohumdan farklı sonuç verir ve
     * üretimi tekrar edilemez hâle getirirdi — hata ayıklamayı imkânsızlaştırırdı.
     */
    private final java.util.Random shuffleRandom;

    public RoomPlacer(RandomGenerator random, double turnBias) {
        this.random = random;
        this.turnBias = Math.max(1.0, turnBias);
        this.shuffleRandom = (random instanceof java.util.Random legacy)
                ? legacy
                : new java.util.Random(random.nextLong());
    }

    /** Bir yerleştirme denemesinin sonucu. */
    public record Attempt(LayoutNode placed, int childDoor, int candidatesTried, String failReason) {

        public boolean success() {
            return placed != null;
        }
    }

    /**
     * Verilen boş kapıya bir oda takmaya çalışır.
     *
     * <p>Başarılıysa oda yerleşime eklenir ve iki kapı karşılıklı bağlanır. Başarısızsa
     * kapı ÖLÜ işaretlenir — bir daha denenmez, tıpa 1D'de basılacak.
     *
     * @param layout yerleşim (değiştirilir)
     * @param door   doldurulacak boş kapı
     * @param pool   aday şablonlar — <b>bu metot listeyi değiştirmez</b>, kopyasıyla çalışır
     */
    public Attempt fill(DungeonLayout layout, OpenDoor door, List<RoomTemplate> pool) {
        List<RoomTemplate> remaining = new ArrayList<>(pool);
        LayoutNode parent = layout.node(door.nodeId());
        int tried = 0;
        String lastReason = "aday havuzu boş";

        RoomTemplate template;
        while ((template = RoomLibrary.drawWeighted(remaining, random)) != null) {
            for (int childDoor : orderDoors(template, door.outward())) {
                tried++;
                PlacedRoom candidate = template.attachTo(childDoor, door.anchor(), door.outward());
                String reason = layout.rejectReason(candidate.bounds());
                if (reason != null) {
                    lastReason = template.name() + " kapı#" + childDoor + ": " + reason;
                    continue;
                }
                LayoutNode child = layout.add(candidate, parent.depth() + 1);
                layout.link(parent.id(), door.doorIndex(), child.id(), childDoor);
                return new Attempt(child, childDoor, tried, null);
            }
        }

        layout.markDead(door.nodeId(), door.doorIndex());
        return new Attempt(null, -1, tried, lastReason);
    }

    /**
     * Şablonun kapılarını deneme sırasına dizer.
     *
     * <p>Karıştırma çeşitlilik için; dönüş yanlılığı ise "düz devam eden" seçenekleri geriye
     * iter. Bir kapı seçeneği <b>düz</b> sayılıyor: o kapıdan bağlanınca odanın <i>başka</i>
     * bir kapısı ebeveynin dışa bakan yönünü gösteriyorsa — yani zincir aynı istikamette
     * devam edecekse.
     *
     * <p>Karşılıklı çift kapılı odada (koridor) iki seçenek de düz olduğu için ceza etkisiz
     * kalır. Bu dürüst bir sonuç: o oda ne yapılırsa yapılsın düz devam ediyor. §6.4'ün asıl
     * motoru zaten kapı ofsetleri.
     */
    private List<Integer> orderDoors(RoomTemplate template, Direction parentOutward) {
        int count = template.doorCount();
        List<Integer> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            order.add(i);
        }
        Collections.shuffle(order, shuffleRandom);

        if (turnBias <= 1.0 || count < 2) {
            return order;
        }
        // Kararlı bölme: düz olmayanlar önde, düz olanlar arkada; her grup içinde
        // karıştırılmış sıra korunuyor.
        List<Integer> turning = new ArrayList<>(count);
        List<Integer> straight = new ArrayList<>(count);
        for (int index : order) {
            (continuesStraight(template, index, parentOutward) ? straight : turning).add(index);
        }
        // Ceza mutlak değil: turnBias büyüdükçe düz seçeneğin öne geçme şansı azalıyor.
        if (!turning.isEmpty() && !straight.isEmpty() && random.nextDouble() < 1.0 / turnBias) {
            turning.addAll(0, straight);
            return turning;
        }
        turning.addAll(straight);
        return turning;
    }

    /**
     * {@code childDoor}'dan bağlanınca odanın başka bir kapısı ebeveynin yönünü mü gösteriyor.
     */
    private static boolean continuesStraight(RoomTemplate template, int childDoor,
                                             Direction parentOutward) {
        Rotation rotation = Rotation.align(parentOutward, template.door(childDoor).wall());
        for (DoorAnchor other : template.doors()) {
            if (other.index() == childDoor) {
                continue;
            }
            if (rotation.apply(other.wall()) == parentOutward) {
                return true;
            }
        }
        return false;
    }
}
