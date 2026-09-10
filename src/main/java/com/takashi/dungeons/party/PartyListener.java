package com.takashi.dungeons.party;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeps parties in step with who is actually online.
 *
 * <p>One event, deliberately. A party is state about right now, so the only thing that has to be
 * watched is somebody ceasing to be here — everything else a player does to a party they do
 * through {@code /party}.
 */
public final class PartyListener implements Listener {

    private final TakashiDungeonsPlugin plugin;

    public PartyListener(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * MONITOR, because this changes nothing about the quit itself — it reacts to a departure that
     * has already been decided. The name is still readable here; after the event it is not.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PartyManager parties = plugin.getPartyManager();
        if (parties != null) {
            parties.handleQuit(event.getPlayer());
        }
    }
}
