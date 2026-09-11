package com.takashi.dungeons;

import com.takashi.dungeons.api.Dungeon;
import com.takashi.dungeons.api.DungeonParty;
import com.takashi.dungeons.api.DungeonStats;
import com.takashi.dungeons.api.TakashiDungeonsAPI;
import com.takashi.dungeons.party.PartyManager;
import com.takashi.dungeons.player.PlayerDataService;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The plugin's answer to {@link TakashiDungeonsAPI}.
 *
 * <h2>Why this lives in the root package rather than in {@code api}</h2>
 * Everything under {@code com.takashi.dungeons.api} is frozen by promise. An implementation is the
 * opposite of frozen — it changes whenever the internals it reads do — so keeping it out of that
 * package is what makes "everything in there is API" a statement you can check by looking at the
 * folder rather than by remembering which classes were special.
 *
 * <h2>It holds no state</h2>
 * Every method reads the live managers through the plugin. An addon may therefore keep the object
 * for the life of the server, which is exactly what {@code TakashiDungeons.api()} invites it to do,
 * without ever holding a stale view.
 */
final class ApiService implements TakashiDungeonsAPI {

    private final TakashiDungeonsPlugin plugin;

    ApiService(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String apiVersion() {
        return API_VERSION;
    }

    // ------------------------------------------------------------------ dungeons

    @Override
    public Collection<Dungeon> dungeons() {
        // Copied and widened in one pass. The internal list is a fresh copy already, but handing
        // out List<DungeonInstance> as Collection<Dungeon> would let a caller cast an element back
        // to the implementation and reach the whole internal surface.
        return List.copyOf(plugin.getInstanceManager().all());
    }

    @Override
    public Optional<Dungeon> dungeon(int id) {
        return Optional.ofNullable(plugin.getInstanceManager().get(id));
    }

    @Override
    public Optional<Dungeon> dungeonOf(Player player) {
        return Optional.ofNullable(plugin.getInstanceManager().instanceOf(player));
    }

    @Override
    public boolean isInDungeon(Player player) {
        return plugin.getInstanceManager().instanceOf(player) != null;
    }

    // ------------------------------------------------------------------ players

    @Override
    public DungeonStats stats(Player player) {
        PlayerDataService data = plugin.getPlayerData();
        // The profile is always handed out, with or without a database - so is this.
        return data.profile(player).stats();
    }

    @Override
    public CompletableFuture<@Nullable DungeonStats> stats(UUID player) {
        return plugin.getPlayerData().lookup(player).thenApply(stats -> stats);
    }

    @Override
    public boolean isPersistent() {
        return plugin.getPlayerData().isPersistent();
    }

    // ------------------------------------------------------------------ parties

    @Override
    public Optional<DungeonParty> partyOf(Player player) {
        PartyManager parties = plugin.getPartyManager();
        return parties == null ? Optional.empty() : Optional.ofNullable(parties.partyOf(player));
    }

    @Override
    public Collection<DungeonParty> parties() {
        PartyManager parties = plugin.getPartyManager();
        return parties == null ? List.of() : List.copyOf(parties.all());
    }

    @Override
    public boolean isPartyEnabled() {
        PartyManager parties = plugin.getPartyManager();
        return parties != null && parties.isEnabled();
    }
}
