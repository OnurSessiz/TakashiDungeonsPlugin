package com.takashi.dungeons.mob;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.instance.DungeonInstance;
import com.takashi.dungeons.instance.InstanceManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * The two things that happen to a dungeon mob because of its {@link DungeonMobTag}: it dies and
 * somebody has to hear about it, or it turns out to be debris and has to go.
 *
 * <h2>The kill signal</h2>
 * Read at {@code MONITOR}: by then every other plugin has had its say about the death and the drop
 * list is final, which is what phase 4 will want to see. Nothing here cancels or modifies the
 * event — the signal is an observation, and a listener that also rewrote drops would put this
 * plugin in a fight with every loot plugin on the server.
 *
 * <p><b>Vanilla drops are deliberately left alone.</b> Rotten flesh from a dungeon zombie is the
 * mob's own drop, not a reward this plugin invented; deleting it is a loot decision and loot is
 * phase 4's system. The one thing already taken away is armour, and only because the plugin put it
 * there in the first place ({@code MobService.wear}).
 *
 * <h2>The orphan sweep</h2>
 * A crash leaves no teardown behind: instances live in memory, the mobs live in region files. With
 * {@code dungeon-world.reset-on-start} off — the config allows exactly that — those mobs come back
 * when their chunk is next loaded, inside whatever dungeon now stands in that slot. So every chunk
 * load in the dungeon world is checked, and a tagged mob whose owner cannot possibly exist is
 * removed.
 *
 * <p>Checked as entities load rather than swept at enable, because at enable nothing is loaded
 * yet: a scan then would find nothing and the debris would arrive later anyway. It is also the
 * cheapest possible place — the entities are in hand, and a void world's chunks are almost all
 * empty. {@code EntitiesLoadEvent} rather than {@code ChunkLoadEvent}: since 1.17 entities live in
 * their own storage and load separately, and reading a chunk's entity list from a chunk-load
 * handler forces that load early for every chunk, whether or not it holds anything of ours.
 *
 * <p><b>Only a tagged mob is ever removed.</b> Anything else in the dungeon world belongs to
 * whoever put it there, and an operator building something in that world must not have it deleted
 * by a plugin doing tidying it was not asked to do.
 */
public final class DungeonMobListener implements Listener {

    private final TakashiDungeonsPlugin plugin;

    public DungeonMobListener(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        MobService service = plugin.getMobService();
        InstanceManager instances = plugin.getInstanceManager();
        if (service == null || instances == null) {
            return;
        }
        LivingEntity entity = event.getEntity();
        DungeonMobTag tag = service.tag();
        if (!tag.isDungeonMob(entity) || !tag.isFromThisSession(entity)) {
            return;
        }
        DungeonInstance instance = instances.get(tag.instanceId(entity));
        if (instance == null) {
            // Its dungeon closed while it was mid-air, or the mob outlived a teardown that missed
            // it. There is nothing to credit the kill to; the sweep will take the corpse's kin.
            return;
        }
        String definitionId = tag.definitionId(entity);
        MobDefinition definition = definitionId == null
                ? null : service.registry().definition(definitionId);
        service.fireKill(new MobKill(instance, definition, entity, tag.isBoss(entity),
                entity.getKiller()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!event.getWorld().getName().equals(plugin.getWorldManager().getWorldName())) {
            return;
        }
        MobService service = plugin.getMobService();
        InstanceManager instances = plugin.getInstanceManager();
        if (service == null || instances == null) {
            return;
        }
        DungeonMobTag tag = service.tag();
        int removed = 0;
        for (Entity entity : event.getEntities()) {
            if (!tag.isDungeonMob(entity) || !isOrphan(tag, instances, entity)) {
                continue;
            }
            entity.remove();
            removed++;
        }
        if (removed > 0) {
            plugin.getLogger().info("Sahipsiz dungeon mob'u temizlendi: " + removed + " adet @ "
                    + event.getChunk().getX() + "," + event.getChunk().getZ());
        }
    }

    /**
     * A tagged mob with no owner: either it comes from an earlier run of the server — its instance
     * can never come back, whatever id it claims — or its instance has already been closed.
     */
    private boolean isOrphan(DungeonMobTag tag, InstanceManager instances, Entity entity) {
        return !tag.isFromThisSession(entity) || instances.get(tag.instanceId(entity)) == null;
    }
}
