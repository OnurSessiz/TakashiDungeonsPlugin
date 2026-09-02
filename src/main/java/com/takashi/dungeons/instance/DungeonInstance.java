package com.takashi.dungeons.instance;

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
 * ({@code generation.md} §13). So phase 7 will write four columns, not a layout dump.
 *
 * <p>The generated {@link DungeonGenerator.Result} is kept in memory anyway, for two reasons that
 * only apply while the instance is alive: {@link #bounds()} is the exact volume to wipe on close,
 * and phase 3 needs the room graph to know where to spawn what. Neither survives a restart, and
 * neither needs to.
 */
public final class DungeonInstance {

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
    private final long expiresAt;

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

    public long totalMillis() {
        return Math.max(1, expiresAt - createdAt);
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
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
                + ", " + result.rooms() + " oda, " + slot + ")";
    }
}
