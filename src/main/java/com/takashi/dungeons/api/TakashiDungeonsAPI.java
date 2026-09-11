package com.takashi.dungeons.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Everything an addon is allowed to ask this plugin.
 *
 * <h2>The promise</h2>
 * <b>Nothing in {@code com.takashi.dungeons.api} will ever change in a way that breaks code
 * compiled against it</b> ({@code anahedef.md} §5). Concretely:
 * <ul>
 *   <li>A method is never removed, renamed, or given a different parameter list or return type.</li>
 *   <li>An enum constant is never removed or renamed.</li>
 *   <li>An event is never removed, and the meaning of one never changes. New ones are added.</li>
 *   <li>Methods <b>are</b> added to this interface over time. That is safe because this interface
 *       is <b>ours to implement and yours to call</b> — see the note below.</li>
 * </ul>
 *
 * <h2>Do not implement these interfaces</h2>
 * {@link Dungeon}, {@link DungeonStats}, {@link DungeonParty} and this one are views onto the
 * plugin's own objects. Read them; do not implement them. An addon that implements one is a class
 * that stops compiling the day a method is added, and that breakage is the addon's own doing rather
 * than a broken promise. The events are the other direction — those you listen to.
 *
 * <h2>Everything outside this package is internal</h2>
 * {@code com.takashi.dungeons.instance}, {@code .mob}, {@code .loot}, {@code .party},
 * {@code .storage} and the rest carry <b>no promise at all</b> and change whenever there is a
 * reason. If something you need is not reachable from here, ask for it to be added rather than
 * reaching around — a reach-around is a dependency neither side knows exists until it breaks.
 *
 * <h2>How to get hold of this</h2>
 * <pre>{@code
 * // plugin.yml: softdepend: [TakashiDungeons]
 * TakashiDungeonsAPI api = TakashiDungeons.api();          // throws if absent
 * TakashiDungeons.optional().ifPresent(api -> ...);        // for a soft integration
 * }</pre>
 * It is also registered with Bukkit's {@code ServicesManager}, so
 * {@code getServer().getServicesManager().load(TakashiDungeonsAPI.class)} works just as well.
 */
public interface TakashiDungeonsAPI {

    /**
     * The API's own version, independent of the plugin's.
     *
     * <p>Two numbers: the first changes only if the promise above is ever broken (it is not meant
     * to), the second every time something is added. An addon that needs a method added in 1.3
     * can refuse to enable when it sees 1.2 — which is why this is a string to print and compare,
     * not a number to do arithmetic on.
     */
    String API_VERSION = "1.0";

    /** The version this server's plugin implements — see {@link #API_VERSION}. */
    String apiVersion();

    // ------------------------------------------------------------------ dungeons

    /** Every dungeon standing right now, oldest first. Never {@code null}; often empty. */
    Collection<Dungeon> dungeons();

    /** The dungeon with this id, if it is still standing. Ids are never reused. */
    Optional<Dungeon> dungeon(int id);

    /**
     * The dungeon this player is registered in.
     *
     * <p>Registered, not standing in: a player who logged out inside one is still a member, and
     * one who fell into the void below the rooms has not left. That is the same answer the boss
     * bar and the expiry teleport use.
     */
    Optional<Dungeon> dungeonOf(Player player);

    /** Shorthand for {@code dungeonOf(player).isPresent()}. */
    boolean isInDungeon(Player player);

    // ------------------------------------------------------------------ players

    /**
     * What this player has done in dungeons, counting what has not been written to the database
     * yet.
     *
     * <p>Always answers. With persistence switched off — or for a player whose row could not be
     * read — the numbers are this session's only, which is the honest answer rather than a
     * failure. Use {@link #isPersistent()} if the difference matters to you.
     */
    DungeonStats stats(Player player);

    /**
     * The same, for a player who may be offline.
     *
     * <p>Asynchronous because it may have to go to the database. The future completes with
     * {@code null} when there is no record — and completes exceptionally only if the read itself
     * failed. Never block the main thread on it.
     */
    CompletableFuture<@Nullable DungeonStats> stats(UUID player);

    /**
     * Whether anything written today will still be here tomorrow.
     *
     * <p>{@code false} when the operator has no working database. The plugin runs either way; this
     * is here so an addon can decide whether to say something about it rather than discovering it
     * from a counter that resets.
     */
    boolean isPersistent();

    // ------------------------------------------------------------------ parties

    /** The party this player belongs to, if any. A party of one is a real party. */
    Optional<DungeonParty> partyOf(Player player);

    /** Every party that exists right now. Empty when parties are switched off. */
    Collection<DungeonParty> parties();

    /** Whether the operator has parties switched on at all ({@code config.yml} → {@code party}). */
    boolean isPartyEnabled();
}
