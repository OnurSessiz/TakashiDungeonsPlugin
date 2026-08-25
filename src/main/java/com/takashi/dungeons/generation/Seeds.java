package com.takashi.dungeons.generation;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Üretim tohumlarını rastgelelik kaynağına çevirir.
 *
 * <h2>Neden ayrı bir sınıf — ölçülmüş bir tuzak var</h2>
 *
 * {@code new java.util.Random(seed)} <b>ardışık tohumlarda korelasyonlu</b>. Java'nın
 * {@code Random}'ı bir LCG ve ilk çıktısı tohumun üst bitlerinin doğrudan bir fonksiyonu;
 * bitişik tohumlar bitişik iç durum üretiyor. Ölçüldü (2026-08-25):
 *
 * <pre>
 *   new Random(seed).nextInt(4), seed = 1..40:
 *     2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2
 *
 *   4000 ardışık tohumda dağılım: [0, 0, 1857, 2143]   ← 0 ve 1 HİÇ çıkmıyor
 * </pre>
 *
 * <p><b>Bunun somut bedeli:</b> {@code small} dungeon'ın oda sayısı 3-6 aralığından
 * çekiliyor. Ardışık tohumlarla ilk çekiliş hep aynı gelseydi bütün small dungeon'lar
 * 5-6 oda olurdu, 3-4 hiç görülmezdi. "Aralık" sözü tutulmazdı.
 *
 * <p><b>Neden gerçekten olurdu:</b> FAZ 7'de instance'lar DB'ye yazılacak ve tohum olarak
 * artan bir id ya da {@code System.currentTimeMillis()} kullanmak en doğal seçim — ikisi de
 * ardışık. Hata da sessiz olurdu: dungeon'lar üretilir, sadece hep aynı boyda çıkardı.
 *
 * <h2>Çözüm</h2>
 * Tohum önce <b>splitmix64</b> ile karıştırılıyor, sonra {@link SplittableRandom}'a
 * veriliyor. splitmix64 bit karıştırıcısı 1 bitlik tohum farkını bütün çıktı bitlerine
 * yayıyor; {@code SplittableRandom} da {@code Random}'dan çok daha iyi bir üreteç.
 * Aynı ölçüm karıştırma sonrası: {@code [977, 1010, 973, 1040]} — düzgün.
 *
 * <p>Tekrarlanabilirlik korunuyor: karıştırma <b>deterministik</b>, aynı tohum aynı
 * dungeon'ı vermeye devam ediyor.
 */
public final class Seeds {

    private Seeds() {
    }

    /**
     * splitmix64 son karıştırma adımı — 1 bitlik girdi farkını çıktının yarısına yayar.
     *
     * <p>Sabitler referans splitmix64 uygulamasından; değiştirilmemeli, karıştırma
     * kalitesi bu çarpanlara bağlı.
     */
    public static long mix(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Tohumdan rastgelelik kaynağı üretir — ardışık tohumlar bağımsız akışlar verir. */
    public static RandomGenerator from(long seed) {
        return new SplittableRandom(mix(seed));
    }

    /**
     * Aynı ana tohumun {@code n}. türevi — yeniden denemeler için.
     *
     * <p>Türev de deterministik: bütün üretim tek bir tohumdan tekrar edilebilir kalıyor.
     * Yeniden denemede rastgele yeni tohum çekilseydi "şu bozuk dungeon"u geri getirmek
     * imkânsız olurdu.
     */
    public static RandomGenerator derive(long seed, int index) {
        return new SplittableRandom(mix(seed + index * 0x9E3779B97F4A7C15L));
    }
}
