package com.takashi.dungeons.shop;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The merchant itself: which mob it is, what it is called, and where it may stand.
 *
 * <h2>Addressed like a mob, but NOT an entry in mobs.yml</h2>
 * The {@code <provider>:<key>} form is the one {@code mobs.yml} already teaches, so a MythicMobs
 * NPC is a merchant by writing {@code mythicmobs:MyTrader} and nothing else. What the merchant must
 * not be is an <i>entry</i> in that file: every entry there belongs to a {@code MobClass} pool and
 * {@code MobPopulator} draws from those pools by class — a merchant in one would spawn in the
 * rooms, as an enemy, with rolled stats.
 *
 * <p>No stat ranges here either, for the same reason a merchant is spawned invulnerable: health on
 * something that cannot be hurt is a number nobody ever reads.
 *
 * @param providerId  the part before the colon, lower case
 * @param mobKey      the part after the colon, handed to the provider verbatim
 * @param displayName MiniMessage name floating over it, or {@code null} to leave the mob's own
 * @param chance      probability that a generated dungeon gets a merchant, 0.0-1.0
 * @param minDistance how far from the entrance spawn it has to stand, in blocks
 */
public record KeeperSpec(String providerId, String mobKey, @Nullable String displayName,
                         double chance, int minDistance) {

    public String address() {
        return providerId + ":" + mobKey;
    }

    public static KeeperSpec parse(@Nullable ConfigurationSection section) {
        String where = "keeper";
        String address = section == null ? null : section.getString("mob");
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException(where + ": the 'mob' field is required - for "
                    + "example: vanilla:VILLAGER");
        }
        int colon = address.indexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            throw new IllegalArgumentException(where + ": '" + address + "' is not a "
                    + "<provider>:<mob> address - for example: vanilla:VILLAGER");
        }

        double chance = section.getDouble("chance", 1.0);
        if (chance < 0.0 || chance > 1.0) {
            throw new IllegalArgumentException(where + ": 'chance' must be between 0.0 and 1.0 "
                    + "(found: " + chance + ")");
        }
        // Zero would mean the merchant never spawns, which is what 'enabled: false' says out loud.
        // Allowed anyway: an operator turning it off for one theme's testing should not have to
        // learn a second switch.
        int minDistance = Math.max(0, section.getInt("min-distance", 3));

        String name = section.getString("name");
        return new KeeperSpec(address.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                address.substring(colon + 1).trim(),
                name == null || name.isBlank() ? null : name,
                chance, minDistance);
    }

    @Override
    public String toString() {
        return address() + " (chance " + chance + ", min-distance " + minDistance + ")";
    }
}
