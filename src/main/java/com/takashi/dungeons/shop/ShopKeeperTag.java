package com.takashi.dungeons.shop;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The mark a merchant carries — instance and session, exactly like
 * {@link com.takashi.dungeons.mob.DungeonMobTag} and
 * {@link com.takashi.dungeons.loot.DungeonChestTag}.
 *
 * <h2>Why not just reuse the mob tag</h2>
 * That tag carries two things a merchant is not: it feeds the kill signal (a dead merchant is not a
 * kill anybody wants a phase 4 drop or a phase 8 event for) and it names a {@code mobs.yml} entry
 * (a merchant has none, on purpose). Sharing it would mean adding "unless it is a merchant"
 * conditions to the mob layer, which is how a clean seam turns into a special case.
 *
 * <h2>Why a tag at all, when a map would do</h2>
 * {@link ShopManager} keeps the live merchants in memory and that is what answers a right-click.
 * The tag is for the case the map cannot survive: a crash, or {@code reset-on-start: false} leaving
 * a merchant asleep in a region file. Recognising one then is the difference between removing it
 * and a villager standing forever in a dungeon that no longer exists.
 */
public final class ShopKeeperTag {

    public static final int NO_INSTANCE = -1;

    private final NamespacedKey instanceKey;
    private final NamespacedKey sessionKey;

    /** Identifies this run of the server. Regenerated on every enable, never persisted. */
    private final long session;

    public ShopKeeperTag(Plugin plugin) {
        this.instanceKey = new NamespacedKey(plugin, "shop_instance");
        this.sessionKey = new NamespacedKey(plugin, "shop_session");
        this.session = UUID.randomUUID().getMostSignificantBits();
    }

    public long session() {
        return session;
    }

    public void apply(Entity entity, int instanceId) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(instanceKey, PersistentDataType.INTEGER, instanceId);
        pdc.set(sessionKey, PersistentDataType.LONG, session);
    }

    public boolean isKeeper(Entity entity) {
        return instanceId(entity) != NO_INSTANCE;
    }

    public int instanceId(Entity entity) {
        Integer stored = read(entity, instanceKey, PersistentDataType.INTEGER);
        return stored == null ? NO_INSTANCE : stored;
    }

    /** A merchant that answers {@code false} is debris from a run that has already ended. */
    public boolean isFromThisSession(Entity entity) {
        Long stored = read(entity, sessionKey, PersistentDataType.LONG);
        return stored != null && stored == session;
    }

    private <T, Z> @Nullable Z read(Entity entity, NamespacedKey key, PersistentDataType<T, Z> type) {
        return entity.getPersistentDataContainer().get(key, type);
    }
}
