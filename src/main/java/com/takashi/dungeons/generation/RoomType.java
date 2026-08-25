package com.takashi.dungeons.generation;

import java.util.Locale;

/**
 * Odanın graf içindeki rolü — {@code generation.md} §8'deki {@code tip} alanı.
 *
 * <p>{@link #GIRIS} ve {@link #BOSS} graf üretiminde <b>atanır</b>, rastgele seçilmez:
 * kritik path giriş odasından başlar, son düğümü boss odasıdır ({@code generation.md} §6.2).
 * Bu, oynanış süresini garanti altına alan şey — odalar rastgele serpilip "en uzaktakine
 * boss koyalım" denseydi bazı dungeon'lar 2 odada biterdi.
 */
public enum RoomType {

    GIRIS,
    NORMAL,
    BOSS;

    /** YAML'deki {@code tip} değerini çözer; büyük/küçük harf ve boşluk toleranslı. */
    public static RoomType parse(String raw, String templateName) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        for (RoomType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException(templateName + ": geçersiz tip '" + raw
                + "' — geçerli değerler: giris, normal, boss");
    }

    public String yamlValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
