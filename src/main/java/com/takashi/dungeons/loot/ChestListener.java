package com.takashi.dungeons.loot;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.List;

/**
 * Keeps a dungeon chest where it was put.
 *
 * <h2>Why a chest needs protecting at all</h2>
 * The dungeon world is wiped when the instance closes, so nothing here is about permanence. It is
 * about the run: a chest broken before the party reaches it drops its contents on the floor, where
 * they can be lost down a hole, burned, or quietly taken by whoever swung first. Loot the plugin
 * decided to give a party should be handed over by opening the chest, not by racing to mine it.
 *
 * <p>The block is also the plugin's, not the world's — the same argument
 * {@link com.takashi.dungeons.portal.PortalListener} makes about portals, and the handlers below
 * are deliberately the same shape so that one reads as a copy of the other rather than as a
 * different policy.
 *
 * <h2>Opening is untouched</h2>
 * There is no interact handler here. A chest that cannot be opened is not a chest, and every
 * protection below is about the block surviving, not about who may look inside. Who may take what
 * is a party question and belongs to phase 5.
 */
public final class ChestListener implements Listener {

    private final TakashiDungeonsPlugin plugin;

    public ChestListener(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Whether this block is a chest the dungeon system owns.
     *
     * <p>Chests from an <b>earlier</b> run are deliberately included. Their instance is gone, so
     * nothing will ever clean them up on a server running with {@code reset-on-start} off; leaving
     * them breakable would at least let a player take the contents, which is worse than leaving
     * them sealed until the region is wiped.
     */
    private boolean isDungeonChest(Block block) {
        DungeonChestTag tag = plugin.getDungeonChestTag();
        return tag != null && tag.isDungeonChest(block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isDungeonChest(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.getMessages().get("loot.chest-unbreakable"));
    }

    /** An explosion must not scatter a chest's contents across the room. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isDungeonChest);
    }

    /**
     * Nor may a piston move it.
     *
     * <p>A piston cannot actually push a chest in vanilla, but it can push the block a chest is
     * standing against, and a mapper is free to build a room with redstone in it. Refusing the
     * whole move is cheaper than working out afterwards which chest ended up somewhere else.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (touchesChest(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (touchesChest(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    /** Nor may water or lava wash it away. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (isDungeonChest(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * Nor may an entity take it.
     *
     * <p>The one that actually happens: an enderman in a dungeon room. It cannot pick up a chest,
     * but a mapper is free to make the floor under one out of something it can, and the general
     * form costs nothing.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isDungeonChest(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    private boolean touchesChest(List<Block> blocks) {
        for (Block block : blocks) {
            if (isDungeonChest(block)) {
                return true;
            }
        }
        return false;
    }
}
