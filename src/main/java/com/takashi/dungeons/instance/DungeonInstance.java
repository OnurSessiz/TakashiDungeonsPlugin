package com.takashi.dungeons.instance;

import com.takashi.dungeons.api.Dungeon;
import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.DungeonGenerator;
import com.takashi.dungeons.generation.LayoutNode;
import com.takashi.dungeons.generation.Vec3i;
import com.takashi.dungeons.schematic.DoorPlugger;
import com.takashi.dungeons.world.GridSlot;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One live dungeon: the slot it occupies, what was generated into it, and how far through its
 * life it is.
 *
 * <h2>What is stored, and what is deliberately not</h2>
 * The identity of a dungeon is the quadruple <b>slot + theme + size + seed</b> — hand those four
 * back to {@link DungeonGenerator} and the same rooms come out in the same places
 * ({@code generation.md} §13). Phase 7 wrote none of them: an instance does not survive a restart,
 * so a stored one would point at a slot whose blocks have been wiped. The quadruple is still what
 * would be stored if that ever changes.
 *
 * <p>The generated {@link DungeonGenerator.Result} is kept in memory anyway, for two reasons that
 * only apply while the instance is alive: {@link #bounds()} is the exact volume to wipe on close,
 * and phase 3 needs the room graph to know where to spawn what. Neither survives a restart, and
 * neither needs to.
 *
 * <h2>This class is the API's {@link Dungeon}, and only that much of it</h2>
 * Implementing the interface rather than handing addons a wrapper keeps one object in play, so
 * what a listener reads during an event is the live truth and not a snapshot taken a tick ago. The
 * interface is narrow on purpose: the slot, the room graph and the plug report are the shape of
 * today's generator, and promising them would promise the generator.
 */
public final class DungeonInstance implements Dungeon {

    /**
     * How far the wiped box reaches past the rooms themselves.
     *
     * <p>Doors are plugged inside their own room's box, so in principle the union of the rooms is
     * exactly what was written. One block of slack costs nothing on a volume this size and covers
     * the off-by-one class of mistake — a wipe that misses is far more expensive than one that
     * clears a shell of air.
     */
    private static final int CLEANUP_MARGIN = 1;

    private final int id;
    private final GridSlot slot;
    private final String theme;
    private final DungeonGenerator.Result result;
    private final DoorPlugger.Report plugReport;
    private final Aabb bounds;
    private final long createdAt;

    /**
     * When the dungeon dies. <b>Movable in one direction only:</b> killing the boss shortens it to
     * a grace period, and nothing ever lengthens it. A timer a player can extend is not a timer.
     */
    private long expiresAt;

    /**
     * What the countdown bar measures itself against — {@link #createdAt}, until the boss dies and
     * it becomes the moment of the kill.
     *
     * <p>Without it the bar would jump from "eighteen minutes left" to a sliver: the same number
     * of pixels has to mean the whole grace period, or the player reads the clear as a punishment.
     */
    private long countdownFrom;

    /**
     * Who is inside, in the order they entered.
     *
     * <p>Membership is <b>declared</b>, not derived from position: a player who logged out inside
     * is still a member, and one who fell through a hole into the void below the rooms has not
     * left. Position answers a different question — {@code instanceAt} — and the two are used for
     * different things: this set decides who sees the boss bar and who gets teleported home,
     * position decides who gets swept out of a slot about to be wiped.
     */
    private final Set<UUID> players = new LinkedHashSet<>();

    /**
     * Where each player came from.
     *
     * <p>Kept per player, not per instance: a party can gather from anywhere, and sending
     * everybody to whoever entered first came from would be a teleport exploit rather than a
     * courtesy. In phase 2C this is the entry object's location.
     */
    private final Map<UUID, Location> returnLocations = new LinkedHashMap<>();

    private volatile InstanceState state;
    private BossBar bossBar;

    /**
     * When the instance last became empty, or {@code -1}.
     *
     * <p>Only armed once somebody has actually been inside. A dungeon an operator generated and
     * never entered has been empty since birth, and killing it on that basis would delete rooms
     * out from under whoever is inspecting them.
     */
    private long emptySince = -1;
    private boolean everOccupied;

    /**
     * When the boss died, or {@code -1}.
     *
     * <p>Deliberately <b>not</b> an {@link InstanceState}. That enum is a one-way ladder whose
     * transitions are checked by ordinal ({@code advanceTo}), and slotting a CLEARED rung between
     * ACTIVE and CLOSING would mean an uncleared dungeon could never be closed. Being cleared is
     * not a stage of the teardown — it is something true about a dungeon that is still fully
     * alive.
     */
    private long clearedAt = -1;

    /** Warning thresholds already announced, so each one fires once. */
    private final Set<Integer> warningsSent = new LinkedHashSet<>();

    DungeonInstance(int id, GridSlot slot, String theme, DungeonGenerator.Result result,
                    DoorPlugger.Report plugReport, Aabb bounds, long durationMillis) {
        this.id = id;
        this.slot = slot;
        this.theme = theme;
        this.result = result;
        this.plugReport = plugReport;
        this.bounds = bounds;
        this.createdAt = System.currentTimeMillis();
        this.expiresAt = createdAt + durationMillis;
        this.countdownFrom = createdAt;
        this.state = InstanceState.BUILDING;
    }

    /**
     * The volume the generator actually wrote to, clamped to the slot.
     *
     * <p>The clamp is not cosmetic: without it a room that overran its slot would have its
     * cleanup overrun too, and the wipe would eat the neighbouring instance's blocks. Generation
     * already refuses to place such a room, so this is the second lock on the same door.
     */
    static Aabb boundsOf(DungeonGenerator.Result result, GridSlot slot, World world) {
        Aabb union = null;
        for (LayoutNode node : result.layout().nodes()) {
            union = union == null ? node.bounds() : union.union(node.bounds());
        }
        Aabb slotBounds = slot.bounds(world);
        if (union == null) {
            // No rooms at all — nothing was written, so nothing needs wiping. An empty box at
            // the slot corner keeps every caller free of null checks.
            return new Aabb(slot.originX(), slot.originY(), slot.originZ(),
                    slot.originX(), slot.originY(), slot.originZ());
        }
        Aabb clamped = union.grow(CLEANUP_MARGIN).clampTo(slotBounds);
        return clamped == null ? union : clamped;
    }

    public int id() {
        return id;
    }

    public GridSlot slot() {
        return slot;
    }

    public String theme() {
        return theme;
    }

    public DungeonGenerator.Result result() {
        return result;
    }

    // The three below exist so that the API can answer "which dungeon is this" without handing out
    // DungeonGenerator.Result — the object whose shape IS the generation algorithm.

    /** {@code "small"} / {@code "medium"} / {@code "large"} — the API's {@code sizeKey}. */
    @Override
    public String sizeKey() {
        return result.size().key();
    }

    @Override
    public long seed() {
        return result.seed();
    }

    @Override
    public int roomCount() {
        return result.rooms();
    }

    public DoorPlugger.Report plugReport() {
        return plugReport;
    }

    /** The box to wipe on close — the union of the placed rooms, plus a block of slack. */
    public Aabb bounds() {
        return bounds;
    }

    public long createdAt() {
        return createdAt;
    }

    /** Milliseconds since the dungeon was generated. */
    public long ageMillis() {
        return System.currentTimeMillis() - createdAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    /** Milliseconds left before the dungeon expires; never negative. */
    public long remainingMillis() {
        return Math.max(0, expiresAt - System.currentTimeMillis());
    }

    /** The span the countdown bar draws — the full duration, or the grace period once cleared. */
    public long totalMillis() {
        return Math.max(1, expiresAt - countdownFrom);
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }

    // ------------------------------------------------------------------ clearing

    /** Whether the boss has been killed. */
    public boolean isCleared() {
        return clearedAt >= 0;
    }

    /** When the boss died, or {@code -1}. */
    public long clearedAt() {
        return clearedAt;
    }

    /**
     * Records the boss's death and, if a grace period is configured, cuts the remaining time down
     * to it.
     *
     * <h2>Why the timer moves at all</h2>
     * Left at its full length, the boss is a thing that happens on the way to the same expiry —
     * killing it changes nothing, so it is not an ending. Closed on the spot, the party is thrown
     * out of the room the instant they win, before they can pick up what the fight dropped (phase
     * 4 puts loot in that room). The grace period is the only answer that makes the kill both an
     * ending and a reward.
     *
     * <p>The window is a <b>ceiling, not a replacement</b>: a dungeon with forty seconds left does
     * not gain a minute by having its boss killed. {@code min} is what keeps this from becoming an
     * extension mechanic.
     *
     * @param graceMillis how long the party may stay after the kill; {@code 0} or less leaves the
     *                    remaining time untouched and only marks the dungeon cleared
     * @return {@code false} if it was already cleared — the second boss death, or a double-fired
     *         event, must not restart the window
     */
    synchronized boolean markCleared(long graceMillis) {
        if (clearedAt >= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        clearedAt = now;
        if (graceMillis > 0) {
            expiresAt = Math.min(expiresAt, now + graceMillis);
            countdownFrom = now;
        }
        return true;
    }

    // ------------------------------------------------------------------ occupancy

    /** Everyone registered as inside, in entry order. */
    public List<UUID> players() {
        return new ArrayList<>(players);
    }

    public int playerCount() {
        return players.size();
    }

    public boolean contains(UUID uuid) {
        return players.contains(uuid);
    }

    /**
     * Registers a player as inside and remembers where they came from.
     *
     * @return {@code false} if they were already a member
     */
    boolean addPlayer(UUID uuid, Location returnLocation) {
        if (!players.add(uuid)) {
            return false;
        }
        // Never overwrite an existing return location. A player who re-enters after a reconnect
        // must still be sent to the place they originally came from, not to the dungeon door
        // they happened to be standing at the second time.
        returnLocations.putIfAbsent(uuid, returnLocation);
        everOccupied = true;
        emptySince = -1;
        return true;
    }

    /** Deregisters a player; the return location is kept until the instance dies. */
    boolean removePlayer(UUID uuid) {
        if (!players.remove(uuid)) {
            return false;
        }
        if (players.isEmpty() && everOccupied) {
            emptySince = System.currentTimeMillis();
        }
        return true;
    }

    /** Where this player should be sent when they leave or the dungeon expires. */
    public @Nullable Location returnLocation(UUID uuid) {
        return returnLocations.get(uuid);
    }

    /**
     * How long the instance has stood empty, or {@code -1} while somebody is inside or nobody
     * ever was.
     */
    public long emptyMillis() {
        return emptySince < 0 ? -1 : System.currentTimeMillis() - emptySince;
    }

    /** Whether a player has ever been inside. */
    public boolean everOccupied() {
        return everOccupied;
    }

    /** @return {@code true} the first time this threshold is reached, {@code false} after */
    boolean markWarned(int seconds) {
        return warningsSent.add(seconds);
    }

    // ------------------------------------------------------------------ boss bar

    /** The countdown bar shown to everyone inside; {@code null} until one is attached. */
    public @Nullable BossBar bossBar() {
        return bossBar;
    }

    void bossBar(BossBar bar) {
        this.bossBar = bar;
    }

    /** Shows the bar to a player, if this instance has one. */
    void showBar(Player player) {
        if (bossBar != null) {
            player.showBossBar(bossBar);
        }
    }

    /** Hides the bar from a player, if this instance has one. */
    void hideBar(Player player) {
        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }
    }

    public InstanceState state() {
        return state;
    }

    public boolean isActive() {
        return state == InstanceState.ACTIVE;
    }

    /**
     * Moves the instance forward one state.
     *
     * <p>Refuses to go backwards or skip: the return value is what tells a second
     * {@code close()} that teardown is already running, instead of letting it release the slot a
     * second time.
     */
    synchronized boolean advanceTo(InstanceState next) {
        if (next.ordinal() != state.ordinal() + 1) {
            return false;
        }
        state = next;
        return true;
    }

    /** Where a player entering this dungeon lands: on the entrance room's floor. */
    public @Nullable Location entranceSpawn(World world) {
        LayoutNode root = result.layout().root();
        if (root == null) {
            return slot.center(world).add(0, 1, 0);
        }
        Vec3i o = root.room().origin();
        return new Location(world, o.x() + 0.5, o.y() + 1, o.z() + 0.5);
    }

    @Override
    public String toString() {
        return "instance#" + id + " (" + theme + "/" + result.size().key()
                + ", " + result.rooms() + " rooms, " + slot + ")";
    }
}
