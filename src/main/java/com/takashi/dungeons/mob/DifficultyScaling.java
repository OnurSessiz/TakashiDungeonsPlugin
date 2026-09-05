package com.takashi.dungeons.mob;

import org.bukkit.configuration.ConfigurationSection;

/**
 * What one difficulty level does to a mob's rolled stats.
 *
 * <h2>Why three multipliers and not one</h2>
 * Scaling speed the way health is scaled turns a hard dungeon into a track meet: at 2.2× movement
 * speed nothing can be kited, fled from, or fought with a bow, and the fight stops being a fight.
 * Health tolerates a large multiplier, damage a moderate one, speed almost none — so they are
 * three separate numbers and the defaults reflect that.
 *
 * <p>Applied <b>after</b> the range roll and only when the definition says {@code statOverride:
 * true}. On a definition that keeps its own plugin's stats, difficulty changes nothing — which is
 * the correct answer, not an omission: overriding stats there is exactly what the flag forbids.
 *
 * @param health      multiplier on rolled max health
 * @param damage      multiplier on rolled attack damage
 * @param speed       multiplier on rolled movement speed
 * @param armor       the armour set handed out at this difficulty
 * @param armorChance probability, 0..1, that a given mob gets that set — at 1.0 every skeleton in
 *                    the dungeon is dressed identically, which reads as a uniform rather than as
 *                    danger
 */
public record DifficultyScaling(double health, double damage, double speed,
                                ArmorTier armor, double armorChance) {

    /** Used when {@code mobs.yml} has no entry for a difficulty — never harder than written. */
    public static final DifficultyScaling NEUTRAL =
            new DifficultyScaling(1.0, 1.0, 1.0, ArmorTier.NONE, 0.0);

    public DifficultyScaling {
        health = requirePositive(health, "health");
        damage = requirePositive(damage, "damage");
        speed = requirePositive(speed, "speed");
        armorChance = Math.clamp(armorChance, 0.0, 1.0);
    }

    private static double requirePositive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("difficulty." + field
                    + " pozitif bir sayı olmalı — bulunan: " + value);
        }
        return value;
    }

    /**
     * Reads one {@code difficulty.<level>} block.
     *
     * <p>Missing keys fall back to {@link #NEUTRAL}'s values rather than throwing: an operator who
     * only wants to raise health on hard should be able to write that one line.
     */
    public static DifficultyScaling parse(ConfigurationSection section, String where) {
        if (section == null) {
            return NEUTRAL;
        }
        ArmorTier tier = ArmorTier.parse(section.getString("armor", ArmorTier.NONE.key()));
        if (tier == null) {
            throw new IllegalArgumentException(where + ".armor: geçersiz değer '"
                    + section.getString("armor") + "' — geçerli: none, leather, golden, chainmail, "
                    + "iron, diamond, netherite");
        }
        return new DifficultyScaling(
                section.getDouble("health", NEUTRAL.health()),
                section.getDouble("damage", NEUTRAL.damage()),
                section.getDouble("speed", NEUTRAL.speed()),
                tier,
                section.getDouble("armor-chance", tier.isNone() ? 0.0 : 1.0));
    }

    @Override
    public String toString() {
        return "hp×" + health + " dmg×" + damage + " spd×" + speed
                + (armor.isNone() ? "" : " armor=" + armor + "@" + Math.round(armorChance * 100) + "%");
    }
}
