package com.takashi.dungeons.generation;

/**
 * Bir odanın kapı bağlantı noktası — {@code generation.md} §2.
 *
 * <p>Anchor, kapı açıklığının <b>taban-merkez bloğu</b>; odanın origin'ine göre yerel
 * koordinat olarak saklanır.
 *
 * <p><b>Yön saklanmıyor, türetiliyor.</b> {@link #wall} metadata'dan okunmuyor,
 * {@link Direction#ofAnchor} ile anchor vektöründen hesaplanıyor. Sebebi tek bir cümle:
 * "metadata kuzey diyor ama anchor doğu duvarında" tutarsızlığının doğabileceği bir yer
 * bırakmamak. Harita ekibi 40+ oda için elle metadata yazacak; yazılabilen her alan
 * yanlış yazılabilen bir alandır.
 *
 * @param index odanın kapı listesindeki sırası — {@code generation.md} §8'deki "adres".
 *              Doldurma sırası DEĞİL (ona geometri karar veriyor); hangi kapının bağlandığını
 *              ve hangisine tıpa basılacağını takip etmeye yarıyor.
 * @param local anchor'ın origin'e göre koordinatı
 * @param wall  anchor'dan türetilmiş duvar (= kapının dışa bakan yönü, oda döndürülmeden önce)
 */
public record DoorAnchor(int index, Vec3i local, Direction wall) {

    /** Anchor'dan duvarı türeterek kapıyı kurar. */
    public static DoorAnchor of(int index, Vec3i local, Aabb localBox) {
        return new DoorAnchor(index, local, Direction.ofAnchor(local, localBox));
    }

    @Override
    public String toString() {
        return "kapı#" + index + " " + local + " " + wall.turkish();
    }
}
