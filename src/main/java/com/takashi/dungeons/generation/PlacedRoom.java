package com.takashi.dungeons.generation;

/**
 * Dünyaya oturtulmuş bir oda: şablon + rotasyon + origin'in dünya koordinatı.
 *
 * <p>Değişmez ve sadece <b>geometri</b> taşıyor. Kapıların bağlı/boş/ölü durumu burada
 * DEĞİL — o graf katmanının (1C/1D) tuttuğu çalışma zamanı durumu
 * ({@code generation.md} §8). Ayrımın sebebi: geometri hesabı yerleştirmeyi kabul etmeden
 * önce yapılıyor (çakışma testi için kutu lazım), graf durumu ise kabul edildikten sonra
 * doğuyor. İkisini tek nesnede birleştirmek "yarı kurulmuş oda" hâli üretirdi.
 *
 * @param template odanın şablonu
 * @param rotation uygulanan saat yönü rotasyon
 * @param origin   şablonun origin'inin oturduğu DÜNYA koordinatı — paste hedefi budur
 * @param bounds   döndürülmüş ve dünyaya taşınmış sınır kutusu
 */
public record PlacedRoom(RoomTemplate template, Rotation rotation, Vec3i origin, Aabb bounds) {

    public static PlacedRoom of(RoomTemplate template, Rotation rotation, Vec3i origin) {
        Aabb bounds = template.localBox().rotate(rotation).translate(origin);
        return new PlacedRoom(template, rotation, origin, bounds);
    }

    /** Kapı anchor'ının DÜNYA koordinatı. */
    public Vec3i doorAnchor(int index) {
        return origin.plus(rotation.apply(template.door(index).local()));
    }

    /** Kapının DÜNYA çerçevesinde dışa bakan yönü — {@code d' = (d + R) mod 4}. */
    public Direction doorOutward(int index) {
        return rotation.apply(template.door(index).wall());
    }

    /**
     * Bu kapıya bağlanacak çocuğun kapı anchor'ının oturacağı nokta.
     * Sırt sırta konvansiyonu gereği bir blok dışarıda ({@code generation.md} §5.2).
     */
    public Vec3i doorMate(int index) {
        return doorAnchor(index).plus(doorOutward(index).step());
    }

    public int doorCount() {
        return template.doorCount();
    }

    @Override
    public String toString() {
        return template.name() + " rot=" + rotation.degrees() + " origin=" + origin;
    }
}
