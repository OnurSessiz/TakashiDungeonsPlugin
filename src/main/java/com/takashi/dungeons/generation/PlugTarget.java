package com.takashi.dungeons.generation;

/**
 * Kapatılacak bir kapı açıklığı — {@code generation.md} §7 (tıpa).
 *
 * <p>Graf bittiğinde bazı kapılar boşluğa açılıyor olur: ya yan dal kotası dolduğu için
 * hiç denenmedi (BOŞ), ya denendi ve bütün adaylar çakıştı (ÖLÜ). İkisi de kapatılmalı —
 * yoksa oyuncu odadan void'e düşer.
 *
 * <p><b>Açıklığın boyutu burada yazmıyor</b> ve bilerek yazmıyor. Motor açıklığı, odanın
 * duvar düzleminde anchor'dan başlayarak hava bloklarını tarayarak kendisi buluyor
 * (bkz. {@code schematic/DoorPlugger}). Sebebi §9'un ruhu: metadata'ya yazılabilen her alan
 * yanlış yazılabilen bir alan. Haritacı 3×3 yerine 3×4 bir kapı çizerse tıpa yine tutar.
 *
 * @param anchor      kapı anchor'ının DÜNYA koordinatı
 * @param outward     kapının dışa bakan yönü — hangi duvar düzleminde olduğunu belirler
 * @param roomBounds  odanın dünya kutusu — taramanın sınırı; olmasaydı tarama duvar
 *                    düzleminde odanın dışına, void'e sızardı
 * @param dead        {@code true} ise ÖLÜ (denendi, oturmadı), {@code false} ise BOŞ
 */
public record PlugTarget(Vec3i anchor, Direction outward, Aabb roomBounds, boolean dead) {

    @Override
    public String toString() {
        return anchor + " " + outward.turkish() + (dead ? " (ölü)" : " (boş)");
    }
}
