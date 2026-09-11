package com.takashi.dungeons.player;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * When a player's saved state is read and written.
 *
 * <h2>Why the load hangs off pre-login</h2>
 * It is the only hook that runs <b>before</b> the player exists and is already off the main thread,
 * so the read costs the server nothing and is finished before the first sidebar is painted. Loading
 * on join would show the player the config default for a frame and then correct it — a flicker that
 * reads as a bug in the toggle they just used last session.
 *
 * <p>A failed or slow read never refuses the login. The reasoning is the one the whole plugin is
 * built on: nothing here is important enough to keep somebody off the server.
 */
public final class PlayerDataListener implements Listener {

    private final TakashiDungeonsPlugin plugin;

    public PlayerDataListener(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Reads the profile while the player is still connecting.
     *
     * <p>Only for a login that has already been allowed — reading rows for somebody who is about to
     * be told they are banned is work for nothing.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        PlayerDataService data = plugin.getPlayerData();
        if (data != null) {
            data.preload(event.getUniqueId(), event.getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PlayerDataService data = plugin.getPlayerData();
        if (data != null) {
            data.handleJoin(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerDataService data = plugin.getPlayerData();
        if (data != null) {
            data.handleQuit(event.getPlayer());
        }
    }

    /**
     * Deaths are counted only inside a dungeon.
     *
     * <p>A player drowning in the overworld is not this plugin's business, and a "deaths" number
     * that quietly included them would mean nothing next to "runs entered". Membership is asked of
     * the instance layer rather than derived from the world name: somebody standing in the dungeon
     * world who never entered an instance is not on a run.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        PlayerDataService data = plugin.getPlayerData();
        if (data == null || plugin.getInstanceManager() == null) {
            return;
        }
        if (plugin.getInstanceManager().instanceOf(event.getEntity()) != null) {
            data.onDeath(event.getEntity());
        }
    }
}
