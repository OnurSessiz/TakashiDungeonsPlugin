package com.takashi.dungeons.mob;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The mark a dungeon mob carries: which instance owns it, which run of the server spawned it, what
 * it was drawn from, and whether it is the boss.
 *
 * <h2>Why persistent data and not a set of UUIDs</h2>
 * A {@code Set<UUID>} on {@link com.takashi.dungeons.instance.DungeonInstance} would be simpler and
 * would answer every question <i>while the process lives</i>. The tag is written into the entity's
 * own NBT instead, for two things the in-memory set cannot do:
 *
 * <ul>
 *   <li><b>It survives what the plugin does not.</b> A crash leaves no teardown behind: the
 *       instances die with the process while the mobs stay in the region files. With
 *       {@code reset-on-start} off — which the config explicitly allows — those mobs wake up inside
 *       the next party's dungeon. A tagged mob can be recognised as debris and removed on sight;
 *       an untagged one is indistinguishable from a mob somebody built the world around.</li>
 *   <li><b>It travels with the entity.</b> Phase 8's event handlers and phase 4's loot table are
 *       handed an entity, not an instance. Asking the entity what it is costs one map lookup and
 *       needs no registry at all.</li>
 * </ul>
 *
 * <h2>The session field is not decoration</h2>
 * Instance ids are unique within one run and start again at 1 on the next
 * ({@code InstanceManager.nextId}). Without a per-run token, a leftover mob tagged
 * {@code instance=1} would be claimed by the <b>next</b> run's first dungeon — the exact bug the
 * tag exists to prevent, reintroduced by the tag itself. A random long written at enable makes
 * "mine" and "from a previous run" two different answers.
 *
 * <h2>Reading is defensive on purpose</h2>
 * Every getter tolerates a missing or wrong-typed value and answers "not a dungeon mob". These
 * containers are writable by any plugin on the server and survive across versions; a class cast
 * exception raised inside a death event would take an unrelated listener chain down with it.
 */
public final class DungeonMobTag {

    /** Answer for an entity that carries no instance id. */
    public static final int NO_INSTANCE = -1;

    private final NamespacedKey instanceKey;
    private final NamespacedKey sessionKey;
    private final NamespacedKey definitionKey;
    private final NamespacedKey bossKey;

    /** Identifies this run of the server. Regenerated on every enable, never persisted. */
    private final long session;

    public DungeonMobTag(Plugin plugin) {
        this.instanceKey = new NamespacedKey(plugin, "instance");
        this.sessionKey = new NamespacedKey(plugin, "session");
        this.definitionKey = new NamespacedKey(plugin, "mob");
        this.bossKey = new NamespacedKey(plugin, "boss");
        this.session = UUID.randomUUID().getMostSignificantBits();
    }

    public long session() {
        return session;
    }

    /**
     * Marks an entity as belonging to an instance.
     *
     * <p>The boss flag is written only when it is true. A key that is absent means the same thing
     * as a key holding zero, and not writing it keeps the ordinary mob's container to three
     * entries — there are hundreds of these per dungeon.
     */
    public void apply(Entity entity, int instanceId, MobDefinition definition, boolean boss) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(instanceKey, PersistentDataType.INTEGER, instanceId);
        pdc.set(sessionKey, PersistentDataType.LONG, session);
        pdc.set(definitionKey, PersistentDataType.STRING, definition.id());
        if (boss) {
            pdc.set(bossKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    /** Whether this entity was spawned by the dungeon system — in this run or an earlier one. */
    public boolean isDungeonMob(Entity entity) {
        return instanceId(entity) != NO_INSTANCE;
    }

    /** The instance that owns this entity, or {@link #NO_INSTANCE}. */
    public int instanceId(Entity entity) {
        Integer stored = read(entity, instanceKey, PersistentDataType.INTEGER);
        return stored == null ? NO_INSTANCE : stored;
    }

    /**
     * Whether the entity was spawned by <b>this</b> run of the server.
     *
     * <p>A tagged mob that answers {@code false} is debris from a previous run: its instance is
     * gone and can never come back, whatever id it claims.
     */
    public boolean isFromThisSession(Entity entity) {
        Long stored = read(entity, sessionKey, PersistentDataType.LONG);
        return stored != null && stored == session;
    }

    public boolean isBoss(Entity entity) {
        Byte stored = read(entity, bossKey, PersistentDataType.BYTE);
        return stored != null && stored != 0;
    }

    /** The {@code mobs.yml} id this entity was drawn from, or {@code null}. */
    public @Nullable String definitionId(Entity entity) {
        return read(entity, definitionKey, PersistentDataType.STRING);
    }

    /** Whether this entity belongs to that instance, in this run. */
    public boolean belongsTo(Entity entity, int instanceId) {
        return instanceId(entity) == instanceId && isFromThisSession(entity);
    }

    /**
     * One container read that cannot throw.
     *
     * <p>{@code has()} followed by {@code get()} is two lookups and still races nothing useful;
     * the try/catch is what actually covers the case this is defending against — a key written
     * with a different type by an older version of this plugin or by somebody else's.
     */
    private <T> @Nullable T read(Entity entity, NamespacedKey key,
                                 PersistentDataType<?, T> type) {
        try {
            return entity.getPersistentDataContainer().get(key, type);
        } catch (IllegalArgumentException | ClassCastException error) {
            return null;
        }
    }
}
