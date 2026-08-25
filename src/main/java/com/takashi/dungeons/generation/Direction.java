package com.takashi.dungeons.generation;

/**
 * Yatay yönler — {@code generation.md} §3'teki index düzeninde: K=0, D=1, G=2, B=3.
 *
 * <p>Sıra <b>saat yönü</b>. Bu, {@link Rotation}'ın {@code (d + R) mod 4} formülünü
 * doğrudan {@code ordinal()} aritmetiğine indirgiyor — enum sırası değiştirilirse
 * rotasyon matematiği sessizce bozulur.
 *
 * <p>Minecraft ekseni: {@code +X = Doğu}, {@code +Z = Güney}, dolayısıyla Kuzey {@code -Z}.
 */
public enum Direction {

    NORTH(0, 0, -1, "kuzey"),
    EAST(1, 0, 0, "doğu"),
    SOUTH(0, 0, 1, "güney"),
    WEST(-1, 0, 0, "batı");

    private static final Direction[] VALUES = values();

    private final Vec3i step;
    private final String turkish;

    Direction(int dx, int dy, int dz, String turkish) {
        this.step = new Vec3i(dx, dy, dz);
        this.turkish = turkish;
    }

    /** Bu yönde bir blokluk yer değiştirme. */
    public Vec3i step() {
        return step;
    }

    /** Yön index'i (K=0, D=1, G=2, B=3). */
    public int index() {
        return ordinal();
    }

    /** {@code karşıt(d) = (d + 2) mod 4}. */
    public Direction opposite() {
        return VALUES[(ordinal() + 2) & 3];
    }

    /** Mesajlarda kullanılacak Türkçe ad. */
    public String turkish() {
        return turkish;
    }

    public static Direction byIndex(int index) {
        return VALUES[Math.floorMod(index, 4)];
    }

    /**
     * Kapı anchor'ının hangi duvarda olduğunu türetir — {@code generation.md} §4.
     *
     * <p><b>Neden ham {@code |dx| > |dz|} değil:</b> o kural sadece kare odada doğru.
     * 9 geniş × 25 uzun bir koridorda, doğu duvarının güney ucuna yakın bir kapı
     * {@code (dx=+4, dz=+11)} verir; {@code |dz| > |dx|} olduğu için naif kural "güney
     * duvarı" der — yanlış. Doğrusu her bileşeni <b>kendi yönündeki yarı boyuta</b>
     * normalize edip hangisinin ±1'e ulaştığına bakmak: nokta o duvara değiyordur.
     *
     * <p>Normalizasyon yön başına ayrı yapılıyor (doğu ve batı uzanımları ayrı okunuyor),
     * çünkü {@code generation.md} §9 ile tek-sayı-kenar kuralı kalktı: origin artık odanın
     * tam ortasında olmak zorunda değil, oda origin'e göre asimetrik olabilir.
     *
     * @param local    anchor'ın origin'e göre yerel koordinatı
     * @param localBox odanın origin'e göre sınır kutusu
     * @throws IllegalArgumentException anchor tam origin'in üstündeyse (duvar türetilemez)
     */
    public static Direction ofAnchor(Vec3i local, Aabb localBox) {
        double nx = ratio(local.x(), localBox.maxX(), -localBox.minX());
        double nz = ratio(local.z(), localBox.maxZ(), -localBox.minZ());

        if (nx == 0.0 && nz == 0.0) {
            throw new IllegalArgumentException(
                    "Kapı anchor'ı odanın origin'iyle aynı noktada — duvar türetilemez: " + local);
        }
        // Eşitlikte X kazanır (generation.md §4: |nx| >= |nz|). Köşe anchor'ında
        // seçimin belirsiz kalmaması için kural sabit tutuluyor.
        if (nx >= nz) {
            return local.x() > 0 ? EAST : WEST;
        }
        return local.z() > 0 ? SOUTH : NORTH;
    }

    /**
     * Bileşenin, işaret ettiği yöndeki uzanıma oranı.
     *
     * <p>Uzanım 0 ise (origin o kenarın tam üstünde) oran sonsuz sayılır: nokta zaten
     * o duvarda demektir. Sıfıra bölmeyi bu yüzden ayrı ele alıyoruz.
     */
    private static double ratio(int delta, int positiveExtent, int negativeExtent) {
        if (delta == 0) {
            return 0.0;
        }
        int extent = delta > 0 ? positiveExtent : negativeExtent;
        if (extent <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.abs(delta) / (double) extent;
    }
}
