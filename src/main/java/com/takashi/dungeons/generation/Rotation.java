package com.takashi.dungeons.generation;

/**
 * 90°'lik saat yönü rotasyon adımı — {@code generation.md} §3'teki {@code R ∈ {0,1,2,3}}.
 *
 * <p><b>Yön işareti ÖLÇÜLDÜ (2026-08-25), varsayım değil.</b> {@code SchematicService}'in
 * paste'te uyguladığı {@code AffineTransform().rotateY(-derece)} doğrudan çalıştırıldı:
 * <pre>
 *   rotateY(-90):  (x,y,z) -> (-z,y,x)     KUZEY -> DOĞU -> GÜNEY -> BATI
 * </pre>
 * Yani {@code R=1} saat yönü ve {@code d' = (d + R) mod 4} doğru. Dördü de
 * {@code generation.md} §3'teki nokta formülleriyle birebir eşleşti. Bu işaret yanlış
 * olsaydı üretilen bütün dungeon'ların kapıları tutmazdı ve hata sessiz olurdu — o yüzden
 * koda geçmeden önce ölçüldü.
 *
 * <p><b>Y ekseni korunuyor.</b> {@code rotateY} sadece X-Z düzleminde döndürür. Çok katlı
 * odanın (alttan giriş, üstten devam) ek kod istememesinin sebebi bu.
 */
public enum Rotation {

    NONE(0),
    CW_90(1),
    CW_180(2),
    CW_270(3);

    private static final Rotation[] VALUES = values();

    private final int steps;

    Rotation(int steps) {
        this.steps = steps;
    }

    /** Kaç adet 90°'lik saat yönü adımı (0-3). */
    public int steps() {
        return steps;
    }

    /** Saat yönü derece karşılığı — {@code SchematicService.paste} bu değeri bekler. */
    public int degrees() {
        return steps * 90;
    }

    public static Rotation ofSteps(int steps) {
        return VALUES[Math.floorMod(steps, 4)];
    }

    public static Rotation ofDegrees(int degrees) {
        if (Math.floorMod(degrees, 90) != 0) {
            throw new IllegalArgumentException("Rotation 90'ın katı olmalı: " + degrees);
        }
        return ofSteps(Math.floorMod(degrees, 360) / 90);
    }

    /** {@code d' = (d + R) mod 4}. */
    public Direction apply(Direction direction) {
        return Direction.byIndex(direction.index() + steps);
    }

    /**
     * Noktayı origin etrafında döndürür — {@code generation.md} §3.
     *
     * <pre>
     *   R=0 -> ( x, y,  z)      R=1 -> (-z, y,  x)
     *   R=2 -> (-x, y, -z)      R=3 -> ( z, y, -x)
     * </pre>
     */
    public Vec3i apply(Vec3i v) {
        return switch (this) {
            case NONE -> v;
            case CW_90 -> new Vec3i(-v.z(), v.y(), v.x());
            case CW_180 -> new Vec3i(-v.x(), v.y(), -v.z());
            case CW_270 -> new Vec3i(v.z(), v.y(), -v.x());
        };
    }

    /**
     * Çocuk odanın kapısını ebeveyn kapısına <b>sırt sırta</b> getiren rotasyon —
     * {@code generation.md} §5.2 adım 2: {@code R = (d_p + 2 - d_c) mod 4}.
     *
     * <p>Bu değer <b>aranmıyor, hesaplanıyor</b>. Çocuğun kapı duvarı, ebeveyn kapısının
     * dışa bakan yönünün karşıtına dönmüş olur — iki duvar yüz yüze bakar. Aday havuzunu
     * "şu yöne bakan kapısı olanlar" diye daraltmaya gerek kalmamasının sebebi bu:
     * her oda her bağlantıya aday, rotasyon farkı kapatıyor.
     *
     * @param parentOutward ebeveyn kapısının DÜNYA çerçevesinde dışa bakan yönü
     * @param childWall     çocuk kapısının odanın YEREL çerçevesindeki duvarı
     */
    public static Rotation align(Direction parentOutward, Direction childWall) {
        return ofSteps(parentOutward.index() + 2 - childWall.index());
    }
}
