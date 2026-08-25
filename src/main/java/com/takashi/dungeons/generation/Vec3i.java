package com.takashi.dungeons.generation;

/**
 * Tam sayı 3B vektör — kapı anchor'ları, oda origin'leri ve kutu köşeleri için.
 *
 * <p><b>Neden WorldEdit'in {@code BlockVector3}'ü değil:</b> generation paketindeki
 * geometri saf fonksiyon olarak kalsın diye. Bu sayede rotasyon ve duvar hesabı
 * WorldEdit classpath'te olmadan da test edilebiliyor, ve FAZ 8'de dışarı açılacak
 * API'ye üçüncü parti bir kütüphanenin tipi sızmıyor (breaking change riski).
 * Dönüşüm sadece paste sınırında, tek satırda yapılıyor.
 */
public record Vec3i(int x, int y, int z) {

    public static final Vec3i ZERO = new Vec3i(0, 0, 0);

    public Vec3i plus(Vec3i other) {
        return new Vec3i(x + other.x, y + other.y, z + other.z);
    }

    public Vec3i minus(Vec3i other) {
        return new Vec3i(x - other.x, y - other.y, z - other.z);
    }

    public Vec3i times(int scalar) {
        return new Vec3i(x * scalar, y * scalar, z * scalar);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + ")";
    }
}
