package com.takashi.dungeons.generation;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code .yml} dosyasından okunan ham metadata — schematic'ten bağımsız kısım.
 *
 * <p>Kapılar burada {@link Vec3i} olarak duruyor, {@link DoorAnchor} olarak DEĞİL: duvarın
 * hangisi olduğunu hesaplayabilmek için odanın kutusu lazım, o da schematic'ten geliyor.
 * Birleştirme {@link RoomTemplateStore}'da yapılıyor.
 *
 * <p><b>Neden merkezi tek dosya değil, oda başına ayrı {@code .yml}:</b> harita ekibi
 * 2-3 kişi paralel çalışacak. Tek dosyada herkes aynı satırlara dokunur, her export merge
 * conflict üretir ({@code generation.md} §8).
 *
 * @param type      graf rolü
 * @param weight    ağırlıklı rastgele seçimde payı
 * @param doorLocal kapı anchor'ları, dosyadaki sırayla
 */
public record RoomMetadata(RoomType type, int weight, List<Vec3i> doorLocal) {

    /** Metadata dosyası yoksa varsayılan: normal oda, standart ağırlık, kapısız. */
    public static final RoomMetadata DEFAULT = new RoomMetadata(RoomType.NORMAL, 100, List.of());

    public RoomMetadata {
        doorLocal = List.copyOf(doorLocal);
    }

    /**
     * YAML'i çözer. Hatalarda net mesajla patlar — sessiz varsayılana düşmez.
     *
     * <p>Kasten katı: yanlış yazılmış bir anchor odayı bir blok kaydırır, duvarlar iç içe
     * geçer ve hata paste'ten sonra, gözle bakınca fark edilir. Yükleme anında yüksek sesle
     * patlamak harita ekibine dosyanın adını ve satırın hangisi olduğunu söylüyor.
     *
     * @param section dosyanın kök bölümü
     * @param name    hata mesajlarında görünecek şablon adı
     */
    public static RoomMetadata parse(ConfigurationSection section, String name) {
        RoomType type = RoomType.parse(section.getString("tip", "normal"), name);

        int weight = section.getInt("agirlik", 100);
        if (weight <= 0) {
            throw new IllegalArgumentException(name + ": agirlik pozitif olmalı (bulunan: "
                    + weight + "). Odayı devre dışı bırakmak için dosyayı taşıyın.");
        }

        List<Vec3i> doors = new ArrayList<>();
        List<?> raw = section.getList("kapilar");
        if (raw != null) {
            for (int i = 0; i < raw.size(); i++) {
                doors.add(parseAnchor(raw.get(i), name, i));
            }
        }

        return new RoomMetadata(type, weight, doors);
    }

    /** Bir {@code [x, y, z]} girdisini çözer. */
    private static Vec3i parseAnchor(Object entry, String name, int index) {
        String where = name + ": kapilar[" + index + "]";
        if (!(entry instanceof List<?> list)) {
            throw new IllegalArgumentException(where + " bir liste olmalı — beklenen biçim: [x, y, z]");
        }
        if (list.size() != 3) {
            throw new IllegalArgumentException(where + " tam 3 sayı içermeli [x, y, z] "
                    + "(bulunan: " + list.size() + " öğe)");
        }
        return new Vec3i(
                intAt(list.get(0), where, "x"),
                intAt(list.get(1), where, "y"),
                intAt(list.get(2), where, "z"));
    }

    private static int intAt(Object value, String where, String axis) {
        if (value instanceof Number number) {
            // Ondalık yazılmış bir koordinat (8.5) sessizce yuvarlanırsa oda yarım blok
            // kayar; blok koordinatında ondalığın anlamı yok, reddediliyor.
            if (number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException(where + " " + axis
                        + " tam sayı olmalı (bulunan: " + number + ")");
            }
            return number.intValue();
        }
        throw new IllegalArgumentException(where + " " + axis + " sayı olmalı (bulunan: " + value + ")");
    }
}
