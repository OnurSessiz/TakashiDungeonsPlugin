package com.takashi.dungeons.instance;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;

/**
 * Everything that has to happen to a player because of where they are.
 *
 * <h2>The join safety net</h2>
 * A player who logs out inside an instance has that position written to their player data. The
 * instance does not survive the restart — the world is reset on start and instances live in
 * memory — so on their next join they load into a void world standing on nothing, and fall
 * forever. The same happens, without a restart, to anyone offline while their dungeon expires.
 *
 * <p>It fires on <b>join only</b>. Entering the dungeon world is a legitimate thing to do:
 * {@code /tdungeons world} puts an operator there on purpose, and phase 2C's entry object will
 * put players there. A rescue that fired on every world change would drag them straight back out.
 * Logging in, by contrast, is never a deliberate entry — the position is a leftover.
 *
 * <h2>The teleport block</h2>
 * {@code /tp} and {@code /tpa} must not work in a dungeon — admins excepted ({@code anahedef.md}
 * §5). Without the rule the whole design leaks: a player teleports a friend past the boss, or
 * teleports out with the loot and back in on a fresh timer, and the instance stops being an
 * instance.
 */
public final class InstanceListener implements Listener {

    /**
     * The teleport causes that count as "being teleported" rather than playing.
     *
     * <p>Ender pearls and chorus fruit are deliberately absent: they are items with a cost, used
     * inside the room the player is standing in, and blocking them would be a nerf to gameplay
     * rather than a closing of the exploit. {@code COMMAND} is vanilla {@code /tp};
     * {@code PLUGIN} covers every {@code /tpa}, {@code /home} and {@code /warp} plugin there is.
     */
    private static final List<PlayerTeleportEvent.TeleportCause> BLOCKED_CAUSES = List.of(
            PlayerTeleportEvent.TeleportCause.COMMAND,
            PlayerTeleportEvent.TeleportCause.PLUGIN,
            PlayerTeleportEvent.TeleportCause.SPECTATE);

    /** Holders of this may teleport in and out freely — the admin exception. */
    public static final String BYPASS_PERMISSION = "takashidungeons.teleport.bypass";

    private final TakashiDungeonsPlugin plugin;

    public InstanceListener(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Location loc = event.getPlayer().getLocation();
        if (!isDungeonWorld(loc)) {
            return;
        }
        InstanceManager instances = plugin.getInstanceManager();
        if (instances == null) {
            return;
        }
        DungeonInstance standing = instances.instanceAt(loc);
        if (standing != null && standing.contains(event.getPlayer().getUniqueId())) {
            // Their dungeon is still up and they are still a member: put them back on the bar
            // and leave them where they were. Logging out is not leaving.
            standing.showBar(event.getPlayer());
            return;
        }
        instances.teleportInternal(event.getPlayer(), instances.fallbackExit());
        event.getPlayer().sendMessage(Component.text(
                "Bulunduğun dungeon artık yok — dışarı çıkarıldın.", NamedTextColor.YELLOW));
    }

    /**
     * A player who disconnects is deregistered but <b>not</b> teleported.
     *
     * <p>Teleporting during a quit is a race against the position being written to disk, and the
     * join safety net covers the same case afterwards without racing anything.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        InstanceManager instances = plugin.getInstanceManager();
        if (instances != null) {
            instances.forget(event.getPlayer());
        }
    }

    /**
     * Leaving the dungeon world by any means ends membership.
     *
     * <p>Deregistering rather than teleporting: they are already out, and dragging them "home"
     * from wherever they legitimately went would be the plugin taking over their movement.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        InstanceManager instances = plugin.getInstanceManager();
        if (instances == null || isDungeonWorld(event.getPlayer().getLocation())) {
            return;
        }
        instances.forget(event.getPlayer());
    }

    /**
     * Blocks teleports into, out of, and within the dungeon world.
     *
     * <p>Both ends are tested. Blocking only the destination would still let a player teleport
     * out of a dungeon with the loot; blocking only the origin would let a friend drop in past
     * the boss. Teleports between two slots are blocked by the same test, which is right — that
     * is one party walking into another's instance.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!BLOCKED_CAUSES.contains(event.getCause())) {
            return;
        }
        if (!isDungeonWorld(event.getFrom()) && !isDungeonWorld(event.getTo())) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return;
        }
        InstanceManager instances = plugin.getInstanceManager();
        // Our own moves have cause PLUGIN too. Rather than let the rule guess whose teleport it
        // is looking at, the manager announces its own for the length of the call.
        if (instances != null && instances.isInternalTeleport(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(Component.text("Dungeon içinde ışınlanma kapalı.", NamedTextColor.RED));
    }

    private boolean isDungeonWorld(Location location) {
        return location != null && location.getWorld() != null
                && location.getWorld().getName().equals(plugin.getWorldManager().getWorldName());
    }
}
