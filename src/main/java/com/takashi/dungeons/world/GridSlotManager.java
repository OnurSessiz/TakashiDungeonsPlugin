package com.takashi.dungeons.world;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;

/**
 * Dungeon dünyasını sabit boyutlu karelere böler ve instance başına bir kare ayırır.
 *
 * <p>Neden grid: instanced dungeon'da iki party'nin blokları asla kesişmemeli. Rastgele
 * konum yerine deterministik grid kullanmanın iki faydası var — (1) çakışma matematiksel
 * olarak imkânsız, (2) slot numarasından konum hesaplanabildiği için DB'de sadece index
 * saklamak yeterli (FAZ 7).
 *
 * <p>Slot'lar satır düzeninde yerleşir: {@code x = (index % columns) * size},
 * {@code z = (index / columns) * size}. Serbest bırakılan index'ler havuzda tutulur ve
 * en küçüğünden yeniden kullanılır — böylece dünya sonsuza kadar büyümez.
 *
 * <p><b>Dikkat:</b> {@code release} sadece index'i geri verir, blokları TEMİZLEMEZ.
 * Blok temizliği FAZ 2'nin (instance yaşam döngüsü) işi; o gelene kadar aynı slot'a
 * ikinci paste yapılırsa eski yapı altta kalır.
 */
public final class GridSlotManager {

    private final int slotSize;
    private final int columns;
    private final int baseY;

    /** Serbest kalmış, yeniden kullanılabilir index'ler (küçükten büyüğe). */
    private final TreeSet<Integer> freed = new TreeSet<>();
    /** Şu an ayrılmış slot'lar — index → slot. */
    private final Map<Integer, GridSlot> allocated = new LinkedHashMap<>();

    private int nextIndex = 0;

    public GridSlotManager(int slotSize, int columns, int baseY) {
        if (slotSize <= 0) {
            throw new IllegalArgumentException("slotSize > 0 olmalı: " + slotSize);
        }
        if (columns <= 0) {
            throw new IllegalArgumentException("columns > 0 olmalı: " + columns);
        }
        this.slotSize = slotSize;
        this.columns = columns;
        this.baseY = baseY;
    }

    /** Yeni bir slot ayırır. Önce serbest havuzuna, yoksa sıradaki yeni index'e bakar. */
    public synchronized GridSlot allocate() {
        int index = freed.isEmpty() ? nextIndex++ : freed.pollFirst();
        GridSlot slot = slotAt(index);
        allocated.put(index, slot);
        return slot;
    }

    /** Slot'u serbest bırakır (blokları temizlemez — bkz. sınıf notu). */
    public synchronized boolean release(int index) {
        if (allocated.remove(index) == null) {
            return false;
        }
        freed.add(index);
        return true;
    }

    /** Tüm ayırmaları iptal eder; index sayacı sıfırlanır. */
    public synchronized void releaseAll() {
        allocated.clear();
        freed.clear();
        nextIndex = 0;
    }

    public synchronized GridSlot get(int index) {
        GridSlot slot = allocated.get(index);
        if (slot == null) {
            throw new NoSuchElementException("Ayrılmış slot yok: " + index);
        }
        return slot;
    }

    public synchronized Collection<GridSlot> allocated() {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(allocated).values());
    }

    public synchronized int allocatedCount() {
        return allocated.size();
    }

    public int slotSize() {
        return slotSize;
    }

    /** Index'in geometrik karşılığı — ayrılmış olması gerekmez. */
    public GridSlot slotAt(int index) {
        int col = index % columns;
        int row = index / columns;
        return new GridSlot(index, col * slotSize, baseY, row * slotSize, slotSize);
    }
}
