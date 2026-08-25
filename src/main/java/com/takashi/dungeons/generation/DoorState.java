package com.takashi.dungeons.generation;

/**
 * Yerleştirilmiş bir odanın kapısının çalışma zamanı durumu —
 * {@code generation.md} §8.
 *
 * <p>Dosyada tutulmaz, sadece bellekte. Şablon "bu odada 3 kapı var" der; hangisinin
 * dolduğu o odanın grafın neresine düştüğüne bağlı.
 */
public enum DoorState {

    /** Henüz denenmedi ya da denenecek — yan dal buradan büyüyebilir. */
    BOS,

    /** Başka bir odaya bağlandı, geçit açık. */
    BAGLI,

    /**
     * Denendi, hiçbir aday oturmadı (hepsi çakıştı ya da slot sınırını taştı).
     * Boşluğa açılıyor — {@code generation.md} §7 gereği tıpa basılacak.
     */
    OLU
}
