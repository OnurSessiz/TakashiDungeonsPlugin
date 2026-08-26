package com.takashi.dungeons.world;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;

/**
 * Divides the dungeon world into fixed-size squares and reserves one square per instance.
 *
 * <p>Why a grid: in instanced dungeons two parties' blocks must never intersect. A
 * deterministic grid beats random placement on two counts — (1) overlap becomes
 * mathematically impossible, and (2) since the position is computed from the slot number,
 * storing only the index in the database is enough (phase 7).
 *
 * <p>Slots are laid out in rows: {@code x = (index % columns) * size},
 * {@code z = (index / columns) * size}. Released indices are pooled and reused smallest
 * first, so the world does not grow forever.
 *
 * <p><b>Caution:</b> {@code release} only returns the index; it does NOT clear the blocks.
 * Block cleanup belongs to phase 2 (instance lifecycle); until then, a second paste into the
 * same slot leaves the old structure underneath.
 */
public final class GridSlotManager {

    private final int slotSize;
    private final int columns;
    private final int baseY;

    /** Released, reusable indices (smallest first). */
    private final TreeSet<Integer> freed = new TreeSet<>();
    /** Currently allocated slots — index → slot. */
    private final Map<Integer, GridSlot> allocated = new LinkedHashMap<>();

    private int nextIndex = 0;

    public GridSlotManager(int slotSize, int columns, int baseY) {
        if (slotSize <= 0) {
            throw new IllegalArgumentException("slotSize must be > 0: " + slotSize);
        }
        if (columns <= 0) {
            throw new IllegalArgumentException("columns must be > 0: " + columns);
        }
        this.slotSize = slotSize;
        this.columns = columns;
        this.baseY = baseY;
    }

    /** Allocates a new slot: the released pool first, otherwise the next fresh index. */
    public synchronized GridSlot allocate() {
        int index = freed.isEmpty() ? nextIndex++ : freed.pollFirst();
        GridSlot slot = slotAt(index);
        allocated.put(index, slot);
        return slot;
    }

    /** Releases the slot (does not clear its blocks — see the class note). */
    public synchronized boolean release(int index) {
        if (allocated.remove(index) == null) {
            return false;
        }
        freed.add(index);
        return true;
    }

    /** Cancels every allocation; the index counter is reset. */
    public synchronized void releaseAll() {
        allocated.clear();
        freed.clear();
        nextIndex = 0;
    }

    public synchronized GridSlot get(int index) {
        GridSlot slot = allocated.get(index);
        if (slot == null) {
            throw new NoSuchElementException("No such allocated slot: " + index);
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

    /** The geometry an index maps to — it does not have to be allocated. */
    public GridSlot slotAt(int index) {
        int col = index % columns;
        int row = index / columns;
        return new GridSlot(index, col * slotSize, baseY, row * slotSize, slotSize);
    }
}
