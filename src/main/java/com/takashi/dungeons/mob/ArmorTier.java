package com.takashi.dungeons.mob;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The armour set handed to a mob at a given difficulty.
 *
 * <h2>Why armour is part of difficulty at all</h2>
 * A hard dungeon whose only difference is a bigger health number is a longer fight, not a harder
 * one — and the player cannot see it. Armour is the half of difficulty that is <b>visible before
 * the first hit</b>: a mob in diamond reads as dangerous across a room. That readability is the
 * point; the damage reduction is a side effect.
 *
 * <p>Armour is applied only when a definition has {@code statOverride: true}. A MythicMobs mob
 * arrives dressed by its own plugin, and dressing it again would overwrite exactly the thing its
 * author designed.
 */
public enum ArmorTier {

    NONE("none", null, null, null, null),
    LEATHER("leather", Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE,
            Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS),
    GOLDEN("golden", Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE,
            Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS),
    CHAINMAIL("chainmail", Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE,
            Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS),
    IRON("iron", Material.IRON_HELMET, Material.IRON_CHESTPLATE,
            Material.IRON_LEGGINGS, Material.IRON_BOOTS),
    DIAMOND("diamond", Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
            Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS),
    NETHERITE("netherite", Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
            Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS);

    private final String key;
    private final @Nullable Material helmet;
    private final @Nullable Material chestplate;
    private final @Nullable Material leggings;
    private final @Nullable Material boots;

    ArmorTier(String key, @Nullable Material helmet, @Nullable Material chestplate,
              @Nullable Material leggings, @Nullable Material boots) {
        this.key = key;
        this.helmet = helmet;
        this.chestplate = chestplate;
        this.leggings = leggings;
        this.boots = boots;
    }

    public String key() {
        return key;
    }

    public boolean isNone() {
        return this == NONE;
    }

    public @Nullable Material helmet() {
        return helmet;
    }

    public @Nullable Material chestplate() {
        return chestplate;
    }

    public @Nullable Material leggings() {
        return leggings;
    }

    public @Nullable Material boots() {
        return boots;
    }

    /** Parses a YAML value; {@code null} when there is no match. */
    public static @Nullable ArmorTier parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (ArmorTier tier : values()) {
            if (tier.key.equals(value)) {
                return tier;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return key;
    }
}
