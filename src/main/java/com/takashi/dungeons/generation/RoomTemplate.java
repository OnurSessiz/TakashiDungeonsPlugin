package com.takashi.dungeons.generation;

import java.util.List;

/**
 * Bir oda şablonu: {@code .schem} dosyasının geometrisi + yanındaki {@code .yml}'nin metadata'sı.
 *
 * <p><b>Boyut metadata'da yazmıyor</b> — {@link #localBox} schematic'in kendisinden okunuyor.
 * Kapı yönü gibi bu da türetilen bir değer: haritacı odayı editörde büyütüp schematic'i
 * yeniden export ettiğinde {@code .yml}'yi güncellemeyi unutabilir, ve kutu yanlışsa çakışma
 * testi sessizce yanlış çalışır. Tek kaynak schematic.
 *
 * <p>Şablon <b>döndürülerek</b> kullanılır; kapı varyantı olarak çoğaltılmaz
 * ({@code generation.md} §7 — 40 oda × 5 şekil = 200 schematic maliyeti bu yüzden reddedildi).
 *
 * @param name     uzantısız dosya adı ({@code .schem} ve {@code .yml} bu adı paylaşır)
 * @param type     graf içindeki rol
 * @param weight   aday seçiminde ağırlıklı rastgele — loot weight mantığı (1C/1D kullanacak)
 * @param doors    kapı anchor'ları, {@code .yml}'deki sırayla; boş olabilir (çıkmaz süs odası)
 * @param localBox odanın origin'e göre sınır kutusu, schematic'ten okunmuş
 */
public record RoomTemplate(String name, RoomType type, int weight,
                           List<DoorAnchor> doors, Aabb localBox) {

    public RoomTemplate {
        doors = List.copyOf(doors);
    }

    public int doorCount() {
        return doors.size();
    }

    public DoorAnchor door(int index) {
        if (index < 0 || index >= doors.size()) {
            throw new IndexOutOfBoundsException(name + ": kapı#" + index + " yok (bu odada "
                    + doors.size() + " kapı var, geçerli aralık 0-" + (doors.size() - 1) + ")");
        }
        return doors.get(index);
    }

    /**
     * Bu şablonu, ebeveynin boş kapısına takar — {@code generation.md} §5.2 adım 2-4.
     *
     * <p>Çakışma testi (adım 5) burada <b>yapılmıyor</b>: bu metot saf geometri, "nereye
     * oturur" sorusunun cevabı. "Oturabilir mi" sorusu graf katmanının (1C) işi ve
     * yerleşmiş odaların listesini gerektiriyor. İkisini ayırmak yerleştirme matematiğini
     * dünyaya erişmeden test edilebilir tutuyor.
     *
     * @param doorIndex     bu şablonun hangi kapısından bağlanacağı
     * @param parentAnchor  ebeveyn kapısının DÜNYA koordinatı
     * @param parentOutward ebeveyn kapısının DÜNYA çerçevesinde dışa bakan yönü
     */
    public PlacedRoom attachTo(int doorIndex, Vec3i parentAnchor, Direction parentOutward) {
        DoorAnchor door = door(doorIndex);
        Rotation rotation = Rotation.align(parentOutward, door.wall());

        // Sırt sırta: çocuğun kapı anchor'ı ebeveyninkinden tam bir blok dışarıda dursun.
        // Duvarlar çakışsaydı ikinci paste birincinin duvarını ezerdi ve sonuç paste
        // SIRASINA bağlı olurdu — sıra bağımlılığı olan üretim hata ayıklanamaz.
        Vec3i childAnchorWorld = parentAnchor.plus(parentOutward.step());
        Vec3i origin = childAnchorWorld.minus(rotation.apply(door.local()));

        return PlacedRoom.of(this, rotation, origin);
    }

    /** Rotasyon uygulanmadan, sadece kutu boyutunu özetler — komut çıktısı için. */
    public String describeSize() {
        return localBox.sizeX() + "×" + localBox.sizeY() + "×" + localBox.sizeZ();
    }
}
