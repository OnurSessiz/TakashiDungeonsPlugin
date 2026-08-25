package com.takashi.dungeons.generation;

import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Dungeon boyutu ve karşılık geldiği oda sayısı aralığı — {@code generation.md} §6.1.
 *
 * <p>Aralık sabit değil, <b>rastgele</b>: aynı boyutu seçen iki oyuncu aynı uzunlukta
 * dungeon görmesin diye. Ama aralık dar tutuluyor ki "medium" sözü anlamını korusun.
 */
public enum DungeonSize {

    SMALL("small", 3, 6),
    MEDIUM("medium", 7, 12),
    LARGE("large", 13, 20);

    private final String key;
    private final int minRooms;
    private final int maxRooms;

    DungeonSize(String key, int minRooms, int maxRooms) {
        this.key = key;
        this.minRooms = minRooms;
        this.maxRooms = maxRooms;
    }

    public String key() {
        return key;
    }

    public int minRooms() {
        return minRooms;
    }

    public int maxRooms() {
        return maxRooms;
    }

    /** Bu boyut için hedef oda sayısı çeker. */
    public int pickRoomCount(RandomGenerator random) {
        return minRooms + random.nextInt(maxRooms - minRooms + 1);
    }

    /**
     * Kritik path'in hedef uzunluğu — {@code generation.md} §6.2: {@code round(hedef × 0.65)},
     * en az 2.
     *
     * <p>Uzunluğa <b>giriş ve boss dahil</b>. En az 2 olması giriş + boss'un her zaman
     * ayrı oda olmasını garanti ediyor; 1 olsaydı boss girişin kendisi olurdu.
     *
     * <p>%65 oranı: odaların üçte ikisi zorunlu yolda, üçte biri yan dallarda. Yan dalların
     * payı çok büyürse dungeon "geniş ama sığ" hissediyor, çok küçülürse tek koridor oluyor.
     */
    public static int criticalPathLength(int targetRooms) {
        return Math.max(2, Math.round(targetRooms * 0.65f));
    }

    /** YAML/komut değerini çözer; bulunamazsa {@code null}. */
    public static DungeonSize parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (DungeonSize size : values()) {
            if (size.key.equals(value)) {
                return size;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return key + " (" + minRooms + "-" + maxRooms + " oda)";
    }
}
