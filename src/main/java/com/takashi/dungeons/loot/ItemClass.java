package com.takashi.dungeons.loot;

import java.util.Locale;

/**
 * How rare an item is — the pool a loot draw picks from.
 *
 * <h2>A pool tag, exactly like MobClass</h2>
 * A class scales nothing. It answers "which pool may this item be drawn from"; what the item
 * actually is comes from its own entry in {@code loot.yml}. The operator learns the meaning of
 * {@code class} and {@code weight} once and it holds for rooms, mobs and loot alike.
 *
 * <h2>The order is load-bearing</h2>
 * The constants are declared from most common to rarest and {@link #isRare()} draws its line at
 * {@link #RARE}. That line is the whole difficulty rule ({@code isleyis.md} § Loot): the difficulty
 * multiplier is applied to rare and above <b>only</b>, and the weight it adds is taken back out of
 * {@link #COMMON}. Reordering these constants silently rewrites that rule, so anything that depends
 * on the order says so out loud rather than assuming it.
 */
public enum ItemClass {

    COMMON("common"),
    UNCOMMON("uncommon"),
    RARE("rare"),
    ULTRA_RARE("ultra_rare"),
    LEGENDARY("legendary");

    private final String key;

    ItemClass(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /**
     * Whether the difficulty multiplier touches this class.
     *
     * <p>Rare and above. Applying it to every class would change nothing at all — a multiplier on
     * every weight normalises back to the same distribution — which is the mistake this project
     * wrote down before writing any code ({@code isleyis.md} § Loot).
     */
    public boolean isRare() {
        return ordinal() >= RARE.ordinal();
    }

    /**
     * Parses a YAML/command value. Tolerates {@code ultra rare} and {@code ultra-rare} beside
     * {@code ultra_rare}: the separator is the one thing nobody remembers.
     *
     * @return {@code null} when there is no match
     */
    public static ItemClass parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (ItemClass itemClass : values()) {
            if (itemClass.key.equals(value)) {
                return itemClass;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return key;
    }
}
