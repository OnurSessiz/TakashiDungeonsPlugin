package com.takashi.dungeons.portal;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Arrays;
import java.util.List;

/**
 * Everything that happens to a portal because a player, or the world, touched it.
 *
 * <h2>Two ways in, on purpose</h2>
 * Right-clicking the block works, and so does right-clicking the {@code Interaction} hitbox that
 * covers the floating shard and the label. Only supporting the block would be technically
 * sufficient and practically wrong: the eye goes to the glowing thing in the air, and a click
 * that lands on nothing reads as a broken portal rather than a mis-aimed click.
 *
 * <h2>The portal is protected, not just placed</h2>
 * The block is the plugin's, not the world's. Mining it, pushing it with a piston, blowing it up
 * or washing it away would leave the manager tracking a portal that is no longer there — the
 * displays would keep floating over a hole. Cheaper to refuse all of it than to detect it after
 * the fact.
 */
public final class PortalListener implements Listener {

    private final TakashiDungeonsPlugin plugin;

    public PortalListener(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    private PortalManager portals() {
        return plugin.getPortalManager();
    }

    /** Right-click on the portal block. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;   // the off-hand fires a second event for the same click
        }
        DungeonPortal portal = portals().at(event.getClickedBlock().getLocation());
        if (portal == null) {
            return;
        }
        // Cancelled so the click cannot also place whatever is in the player's hand against the
        // portal — the block would be buried under it.
        event.setCancelled(true);
        portals().use(event.getPlayer(), portal);
    }

    /** Right-click on the hitbox standing in for the floating pieces. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        DungeonPortal portal = portals().claimedBy(event.getRightClicked().getUniqueId());
        if (portal == null) {
            return;
        }
        event.setCancelled(true);
        portals().use(event.getPlayer(), portal);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (portals().at(event.getBlock().getLocation()) == null) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("Bu geçit kırılamaz.", NamedTextColor.RED));
    }

    /** Explosions must not take the block out from under a live portal. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> portals().at(block.getLocation()) != null);
    }

    /** Nor may a piston move it: the manager's record is keyed on the block position. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (touchesPortal(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (touchesPortal(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    /** Nor may water or lava wash it away. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (portals().at(event.getToBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * The display entities take no damage.
     *
     * <p>{@code Interaction} entities in particular are attackable, and a left-click would
     * otherwise be able to delete the hitbox and leave a portal that can only be opened by
     * clicking the block precisely.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (portals().claimedBy(event.getEntity().getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Sweeps portal debris as chunks come in.
     *
     * <p>Portals do not survive a restart, so after one every tagged entity in the world belongs
     * to a portal that no longer exists. The enable-time sweep only reaches loaded chunks; this
     * catches the rest as the world opens up, instead of leaving shards spinning over nothing in
     * a corner of the map nobody has visited yet.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        PortalManager manager = portals();
        if (manager == null) {
            return;
        }
        manager.sweepOrphans(Arrays.asList(event.getChunk().getEntities()));
    }

    private boolean touchesPortal(List<Block> blocks) {
        for (Block block : blocks) {
            if (portals().at(block.getLocation()) != null) {
                return true;
            }
        }
        return false;
    }
}
