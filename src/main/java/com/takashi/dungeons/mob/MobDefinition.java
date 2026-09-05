package com.takashi.dungeons.mob;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * One entry from {@code mobs.yml}: which mob, from which provider, in which pool, with what stats.
 *
 * <h2>Addressing is explicit — {@code provider:key}</h2>
 * {@code vanilla:ZOMBIE}, {@code mythicmobs:SkeletalKnight}. Two sources can name the same mob,
 * and guessing which one was meant is the kind of decision that is wrong silently. When the named
 * provider is missing the definition is <b>disabled with a reason</b>, never quietly re-pointed at
 * vanilla: a boss that turns into an ordinary zombie because MythicMobs failed to load looks like
 * a balance bug, not a missing plugin.
 *
 * <p>The out-of-box guarantee is met a different way — the shipped {@code mobs.yml} is entirely
 * vanilla, so a server with no mob plugin at all has a complete, working set.
 *
 * @param id          registry key, unique within {@code mobs.yml}
 * @param providerId  the part before the colon, lower case
 * @param mobKey      the part after the colon, passed to the provider verbatim
 * @param mobClass    which pool the spawner may draw this from
 * @param weight      share in the weighted draw inside that pool — loot-weight semantics, the
 *                    same convention as room templates ({@code generation.md} §5.4)
 * @param statOverride {@code null} means "use the provider's default"; see
 *                    {@link MobProvider#defaultStatOverride()}
 * @param health      max health range, or {@code null} to leave the entity's natural value
 * @param damage      attack damage range, or {@code null}
 * @param speed       movement speed range, or {@code null}
 * @param displayName MiniMessage name shown above the mob, or {@code null} for none
 * @param baby        spawn as a baby where the entity supports it
 */
public record MobDefinition(String id, String providerId, String mobKey, MobClass mobClass,
                            int weight, @Nullable Boolean statOverride,
                            @Nullable StatRange health, @Nullable StatRange damage,
                            @Nullable StatRange speed, @Nullable String displayName,
                            boolean baby) {

    /** {@code provider:key}, as written in the file. */
    public String address() {
        return providerId + ":" + mobKey;
    }

    /** Resolves the flag against the provider that owns this definition. */
    public boolean resolveStatOverride(MobProvider provider) {
        return statOverride != null ? statOverride : provider.defaultStatOverride();
    }

    /** Whether any attribute would actually be written, assuming the override is on. */
    public boolean hasStats() {
        return health != null || damage != null || speed != null;
    }

    /**
     * Reads one {@code mobs.<id>} block.
     *
     * <p>Strict, like room metadata: a mistyped class or a missing {@code mob} address throws with
     * the id in the message. The alternative — defaulting quietly — produces a mob set that looks
     * loaded and behaves wrong, and the operator has no line to look at.
     */
    public static MobDefinition parse(ConfigurationSection section, String id) {
        String where = "mobs." + id;

        String address = section.getString("mob");
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException(where + ": 'mob' alanı zorunlu — "
                    + "biçim: <provider>:<key>, örnek: vanilla:ZOMBIE");
        }
        int colon = address.indexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            throw new IllegalArgumentException(where + ": 'mob' değeri <provider>:<key> biçiminde "
                    + "olmalı (bulunan: '" + address + "'). Örnek: vanilla:ZOMBIE");
        }
        String providerId = address.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String mobKey = address.substring(colon + 1).trim();

        MobClass mobClass = MobClass.parse(section.getString("class", MobClass.NORMAL.key()));
        if (mobClass == null) {
            throw new IllegalArgumentException(where + ": geçersiz class '"
                    + section.getString("class") + "' — geçerli: weak, normal, strong, "
                    + "super_strong, boss");
        }

        int weight = section.getInt("weight", 100);
        if (weight <= 0) {
            throw new IllegalArgumentException(where + ": weight pozitif olmalı (bulunan: " + weight
                    + "). Bir mob'u kapatmak için 'enabled: false' yaz ya da girdiyi sil.");
        }

        // Read as an Object rather than getBoolean: the difference between "false" and "not
        // written" is the whole point of the flag, and getBoolean flattens the two.
        Boolean statOverride = section.isSet("statOverride")
                ? section.getBoolean("statOverride") : null;

        return new MobDefinition(id, providerId, mobKey, mobClass, weight, statOverride,
                StatRange.parse(section.get("health"), where + " -> health"),
                StatRange.parse(section.get("damage"), where + " -> damage"),
                StatRange.parse(section.get("speed"), where + " -> speed"),
                section.getString("name"),
                section.getBoolean("baby", false));
    }

    @Override
    public String toString() {
        return id + " (" + address() + ", " + mobClass + ", w=" + weight + ")";
    }
}
