package com.takashi.dungeons.generation;

/**
 * Eksene hizalı 3B kutu; her iki uç da <b>dahil</b> (blok koordinatı, aralık değil).
 *
 * <p>İki yerde kullanılıyor:
 * <ul>
 *   <li><b>Yerel kutu</b> — odanın origin'ine göre sınırları ({@link RoomTemplate})</li>
 *   <li><b>Dünya kutusu</b> — yerleştirilmiş odanın kapladığı hacim ({@link PlacedRoom})</li>
 * </ul>
 *
 * <p><b>Neden 3B, neden 2B ayak izi değil:</b> {@code generation.md} §5.2 adım 5. Çok katlı
 * dungeon'da iki odanın ayak izi çakışıp hacimleri çakışmayabilir — üst kattaki oda alttakinin
 * üstünden geçer. Ayak izine bakan bir test bunu yanlışlıkla reddeder, yani mimarinin
 * desteklemek için tasarlandığı şeyi engeller.
 */
public record Aabb(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Aabb {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Geçersiz kutu: min > max");
        }
    }

    public static Aabb of(Vec3i a, Vec3i b) {
        return new Aabb(
                Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    public Vec3i min() {
        return new Vec3i(minX, minY, minZ);
    }

    public Vec3i max() {
        return new Vec3i(maxX, maxY, maxZ);
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public boolean contains(Vec3i p) {
        return p.x() >= minX && p.x() <= maxX
                && p.y() >= minY && p.y() <= maxY
                && p.z() >= minZ && p.z() <= maxZ;
    }

    /**
     * İki kutu ortak blok paylaşıyor mu.
     *
     * <p>Uçlar dahil olduğu için karşılaştırma {@code <=}: {@code maxX=10} ile
     * {@code minX=10} kutuları x=10 sütununu <b>paylaşır</b>, kesişir. Sırt sırta
     * bağlanan odalar bu yüzden çakışmaz — aralarında tam bir blokluk fark var
     * ({@code generation.md} §5.2 adım 3).
     */
    public boolean intersects(Aabb other) {
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    /** Kutuyu origin etrafında döndürür. Köşeler dönüp yeniden min/max alınıyor. */
    public Aabb rotate(Rotation rotation) {
        return Aabb.of(rotation.apply(min()), rotation.apply(max()));
    }

    public Aabb translate(Vec3i offset) {
        return new Aabb(
                minX + offset.x(), minY + offset.y(), minZ + offset.z(),
                maxX + offset.x(), maxY + offset.y(), maxZ + offset.z());
    }

    /** Kutuyu her yönde {@code amount} blok küçültür — sınır testinde pay bırakmak için. */
    public Aabb shrink(int amount) {
        return new Aabb(minX + amount, minY + amount, minZ + amount,
                maxX - amount, maxY - amount, maxZ - amount);
    }

    @Override
    public String toString() {
        return "[" + minX + "," + minY + "," + minZ + " → " + maxX + "," + maxY + "," + maxZ
                + "] " + sizeX() + "×" + sizeY() + "×" + sizeZ();
    }
}
