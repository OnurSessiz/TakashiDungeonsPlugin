package com.takashi.dungeons.instance;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * The safety net around the dungeon world: nobody wakes up inside a dungeon that no longer
 * exists.
 *
 * <h2>Why this is needed at all</h2>
 * A player who logs out inside an instance has that position written to their player data. The
 * instance does not survive the restart — the world is reset on start and instances live in
 * memory — so on their next join they load into a void world standing on nothing, and fall
 * forever. The same happens, without a restart, to anyone offline while their dungeon expires.
 *
 * <h2>Why only on join</h2>
 * Entering the dungeon world is a legitimate thing to do: {@code /tdungeons world} puts an
 * operator there on purpose, and phase 2C's entry object will put players there. A rescue that
 * fired on every world change would drag them straight back out. Logging in, by contrast, is
 * never a deliberate entry — the position is a leftover.
 */
public final class InstanceListener implements Listener {

    private final TakashiDungeonsPlugin plugin;

    public InstanceListener(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Location loc = event.getPlayer().getLocation();
        if (loc.getWorld() == null
                || !loc.getWorld().getName().equals(plugin.getWorldManager().getWorldName())) {
            return;
        }
        InstanceManager instances = plugin.getInstanceManager();
        if (instances == null || instances.instanceAt(loc) != null) {
            return;   // the dungeon they logged out in is still standing
        }
        event.getPlayer().teleport(instances.fallbackExit());
        event.getPlayer().sendMessage(Component.text(
                "Bulunduğun dungeon artık yok — dışarı çıkarıldın.", NamedTextColor.YELLOW));
    }
}
