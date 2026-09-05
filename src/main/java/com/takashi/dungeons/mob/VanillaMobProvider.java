package com.takashi.dungeons.mob;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mobs from the vanilla entity registry. <b>Always available</b> — this is the fallback that makes
 * the out-of-box guarantee true ({@code anahedef.md} §4): with no mob plugin installed at all, a
 * dungeon is still populated.
 *
 * <h2>What is deliberately excluded</h2>
 * Only living, spawnable entity types are offered. {@code ARMOR_STAND} is technically a living
 * entity and {@code PLAYER} is technically an entity type; neither is a mob, and both would only
 * show up in tab-complete to be picked by mistake. {@code ENDER_DRAGON} is left in — an operator
 * who deliberately puts one in a boss room is not making a mistake, they are making a boss room.
 */
public final class VanillaMobProvider implements MobProvider {

    public static final String ID = "vanilla";

    /**
     * Types that pass the "living and spawnable" test but are not mobs.
     *
     * <p>The armour stand is the one that matters: it is the single most likely accidental pick
     * in an alphabetical tab-complete list, and a room full of armour stands looks like a bug in
     * the generator rather than a typo in {@code mobs.yml}.
     */
    private static final List<EntityType> NOT_A_MOB =
            List.of(EntityType.ARMOR_STAND, EntityType.PLAYER);

    /** Upper-case name → type. Built once; the registry does not change at runtime. */
    private final Map<String, EntityType> byName = new TreeMap<>();

    public VanillaMobProvider() {
        for (EntityType type : EntityType.values()) {
            if (isMob(type)) {
                byName.put(type.name(), type);
            }
        }
    }

    private static boolean isMob(EntityType type) {
        if (NOT_A_MOB.contains(type) || !type.isSpawnable()) {
            return false;
        }
        Class<? extends Entity> clazz = type.getEntityClass();
        return clazz != null && LivingEntity.class.isAssignableFrom(clazz);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Vanilla";
    }

    /** Vanilla mobs exist wherever the server does. */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean supports(String mobKey) {
        return byName.containsKey(normalize(mobKey));
    }

    @Override
    public @Nullable LivingEntity spawn(String mobKey, Location location) {
        EntityType type = byName.get(normalize(mobKey));
        World world = location.getWorld();
        if (type == null || world == null) {
            return null;
        }
        // spawnEntity can still hand back a non-living entity if the registry ever disagrees with
        // the class check above; a cast that throws inside a spawn loop would abort the rest of
        // the room's mobs, so it is tested rather than assumed.
        Entity entity = world.spawnEntity(location, type);
        if (entity instanceof LivingEntity living) {
            return living;
        }
        entity.remove();
        return null;
    }

    @Override
    public Collection<String> knownKeys() {
        return List.copyOf(byName.keySet());
    }

    /** Vanilla mobs carry no stat design of their own, so overriding is the sane default. */
    @Override
    public boolean defaultStatOverride() {
        return true;
    }

    /** Accepts {@code zombie}, {@code Zombie} and {@code minecraft:zombie} alike. */
    private static String normalize(String mobKey) {
        String value = mobKey.trim();
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
