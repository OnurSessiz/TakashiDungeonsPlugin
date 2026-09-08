package com.takashi.dungeons.loot;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The mark a dungeon chest carries: which instance owns it and which run of the server filled it.
 *
 * <h2>The same argument DungeonMobTag makes, for the same reasons</h2>
 * A {@code Set<Location>} on the instance would answer every question while the process lives. The
 * tag goes into the block's own tile-entity NBT instead because it survives what the plugin does
 * not: a crash leaves the chests sitting in the region files with no instance left to remember
 * them, and with {@code dungeon-world.reset-on-start} off — which the config explicitly allows —
 * they are still there when the next party arrives. A tagged chest can be recognised; an untagged
 * one is indistinguishable from a chest somebody built the world around.
 *
 * <p>It also travels with the block, which is what the protection listener needs: a break event
 * hands over a {@link Block}, not an instance, and asking the block what it is costs one lookup
 * and no registry at all.
 *
 * <h2>The session field is not decoration</h2>
 * Instance ids restart at 1 on the next run. Without a per-run token a leftover chest tagged
 * {@code instance=1} would be claimed by the next run's first dungeon — the exact confusion the
 * tag exists to prevent, reintroduced by the tag itself.
 *
 * <h2>Reading is defensive</h2>
 * Every getter tolerates a missing or wrong-typed value and answers "not a dungeon chest". These
 * containers are writable by any plugin and survive across versions, and a class cast exception
 * raised inside a break event would take an unrelated listener chain down with it.
 */
public final class DungeonChestTag {

    /** Answer for a block that carries no instance id. */
    public static final int NO_INSTANCE = -1;

    private final NamespacedKey instanceKey;
    private final NamespacedKey sessionKey;
    private final NamespacedKey tableKey;

    /** Identifies this run of the server. Regenerated on every enable, never persisted. */
    private final long session;

    public DungeonChestTag(Plugin plugin) {
        this.instanceKey = new NamespacedKey(plugin, "chest_instance");
        this.sessionKey = new NamespacedKey(plugin, "chest_session");
        this.tableKey = new NamespacedKey(plugin, "chest_table");
        this.session = UUID.randomUUID().getMostSignificantBits();
    }

    public long session() {
        return session;
    }

    /**
     * Marks a block as a dungeon chest.
     *
     * <p>Writes and commits the tile state. The commit is the part that is easy to leave out and
     * silent when you do: the container handed back by {@code getState()} is a snapshot, and
     * without {@code update()} the tag is set on a copy that is then thrown away.
     *
     * @return whether the block could carry a tag at all
     */
    public boolean apply(Block block, int instanceId, String tableId) {
        // getState(false) so the container being written is the live tile entity's, not a snapshot
        // that would also roll back the items just placed in it when it was committed.
        BlockState state = block.getState(false);
        if (!(state instanceof TileState tile)) {
            return false;
        }
        PersistentDataContainer pdc = tile.getPersistentDataContainer();
        pdc.set(instanceKey, PersistentDataType.INTEGER, instanceId);
        pdc.set(sessionKey, PersistentDataType.LONG, session);
        pdc.set(tableKey, PersistentDataType.STRING, tableId);
        return tile.update(true, false);
    }

    /** Whether this block was placed or filled by the dungeon system, in this run or an earlier. */
    public boolean isDungeonChest(Block block) {
        return instanceId(block) != NO_INSTANCE;
    }

    /** The instance that owns this chest, or {@link #NO_INSTANCE}. */
    public int instanceId(Block block) {
        Integer stored = read(block, instanceKey, PersistentDataType.INTEGER);
        return stored == null ? NO_INSTANCE : stored;
    }

    /**
     * Whether the chest was filled by <b>this</b> run of the server.
     *
     * <p>A tagged chest that answers {@code false} is debris from a previous run: its instance is
     * gone and can never come back, whatever id it claims.
     */
    public boolean isFromThisSession(Block block) {
        Long stored = read(block, sessionKey, PersistentDataType.LONG);
        return stored != null && stored == session;
    }

    /** The {@code loot.yml} table this chest was filled from, or {@code null}. */
    public @Nullable String tableId(Block block) {
        return read(block, tableKey, PersistentDataType.STRING);
    }

    /**
     * One container read that cannot throw.
     *
     * <p>{@code getState()} on a block is not free, but a break event fires once per block broken;
     * the alternative — a location set consulted on every event — is the design this class exists
     * instead of.
     */
    private <T> @Nullable T read(Block block, NamespacedKey key, PersistentDataType<?, T> type) {
        try {
            BlockState state = block.getState(false);
            if (!(state instanceof TileState tile)) {
                return null;
            }
            return tile.getPersistentDataContainer().get(key, type);
        } catch (IllegalArgumentException | ClassCastException error) {
            return null;
        }
    }
}
