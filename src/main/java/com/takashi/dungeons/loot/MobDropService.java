package com.takashi.dungeons.loot;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.Seeds;
import com.takashi.dungeons.instance.DungeonInstance;
import com.takashi.dungeons.mob.Difficulty;
import com.takashi.dungeons.mob.DungeonMobTag;
import com.takashi.dungeons.mob.MobClass;
import com.takashi.dungeons.mob.MobDefinition;
import com.takashi.dungeons.mob.MobKill;
import com.takashi.dungeons.mob.MobProvider;
import com.takashi.dungeons.mob.MobService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * What a dungeon mob leaves behind.
 *
 * <h2>Two halves, deliberately in two places</h2>
 * <ul>
 *   <li><b>Ordinary mobs</b> are handled in {@link #onDeath}, a listener on the death event,
 *       because both jobs need the event itself: clearing the vanilla drops and adding the
 *       dungeon's own to the same list. Items added here behave like drops, because they are
 *       drops — every plugin downstream sees them.</li>
 *   <li><b>The boss</b> is handled in {@link #onKill}, on {@code MobService}'s kill signal, the
 *       seam phase 3C built for exactly this. Its reward is not a drop at all: it is a chest that
 *       appears where it died.</li>
 * </ul>
 *
 * <h2>Priority, and why it is not MONITOR</h2>
 * {@link #onDeath} runs at {@code HIGH} because it <b>modifies</b> the event, and MONITOR is a
 * promise not to. {@code DungeonMobListener} keeps its MONITOR handler for the kill signal, so the
 * order is: drops rewritten here, then observed there as final. That is also the order the comment
 * in that class already promised phase 4 would keep.
 *
 * <h2>Why the boss gets a chest and a zombie gets floor drops</h2>
 * Not size. A boss's reward is worth a fight the whole party just spent minutes on, and on the
 * floor it goes to whoever swung last, can fall into lava or off the edge, and despawns on a timer
 * nobody is watching. A chest waits, and everybody can open it. A zombie's three iron nuggets have
 * none of those problems and a chest per zombie would turn a dungeon into a warehouse.
 */
public final class MobDropService implements Listener {

    /**
     * Which stream of the dungeon seed belongs to the boss reward.
     *
     * <p>Separate from {@link LootPopulator#CHEST_STREAM} for the reason that constant documents:
     * both index by node id, and sharing a stream would tie the boss's hoard to whatever the room
     * chests in that same room drew.
     */
    public static final long REWARD_STREAM = 0x4C_05L;

    /** How far up and down of the death spot a floor is looked for, in blocks. */
    private static final int REWARD_SEARCH = 4;

    private final TakashiDungeonsPlugin plugin;

    public MobDropService(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ ordinary mobs

    /**
     * Rewrites the drop list of a dungeon mob.
     *
     * <p>Reads the tag rather than a registry: the entity is in hand and the tag travels with it,
     * which is the whole argument {@link DungeonMobTag} makes. A mob from an earlier run of the
     * server is left completely alone — its instance can never come back, so there is no
     * difficulty to scale by and no party to reward.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        MobService mobs = plugin.getMobService();
        LootRegistry registry = plugin.getLootRegistry();
        if (mobs == null || registry == null) {
            return;
        }
        LivingEntity entity = event.getEntity();
        DungeonMobTag tag = mobs.tag();
        if (!tag.isDungeonMob(entity) || !tag.isFromThisSession(entity)) {
            return;
        }
        DungeonInstance instance = plugin.getInstanceManager() == null
                ? null : plugin.getInstanceManager().get(tag.instanceId(entity));
        if (instance == null) {
            return;
        }
        DropRules rules = registry.dropRules();

        if (!rules.vanillaDrops()) {
            // The mob's own drops, not a reward anyone designed. Cleared before anything of ours
            // is added, so the list that comes out holds exactly what this plugin decided.
            event.getDrops().clear();
        }
        if (tag.isBoss(entity)) {
            // The boss's reward is a chest, placed on the kill signal. Its vanilla drops are still
            // cleared above -- a boss shedding rotten flesh next to its hoard reads as a bug.
            return;
        }

        String definitionId = tag.definitionId(entity);
        MobDefinition definition = definitionId == null
                ? null : mobs.registry().definition(definitionId);
        if (definition == null || !allowsDungeonDrops(definition)) {
            return;
        }
        String tableId = rules.tableFor(definition.mobClass());
        LootTable table = tableId == null ? null : registry.table(tableId);
        if (table == null) {
            return;
        }
        // Not seeded from the dungeon: which mobs a party kills, and in what order, is not
        // reproducible in the first place, so pretending the drops are would only make the seed
        // look like it promises something it cannot.
        RandomGenerator random = java.util.concurrent.ThreadLocalRandom.current();
        if (random.nextDouble() >= rules.chanceFor(definition.mobClass())) {
            return;
        }
        LootService.Roll roll = plugin.getLootService().roll(table, difficultyOf(), random);
        event.getDrops().addAll(roll.items());
    }

    /**
     * Whether this definition may receive the dungeon's table, resolved against its provider.
     *
     * <p>An unknown provider answers no: the definition names a source that is not registered, and
     * inventing a default for it would be guessing on behalf of a plugin that is not there.
     */
    private boolean allowsDungeonDrops(MobDefinition definition) {
        MobProvider provider = plugin.getMobRegistry().provider(definition.providerId());
        return provider != null && definition.resolveDungeonDrops(provider);
    }

    // ------------------------------------------------------------------ the boss

    /**
     * Puts the boss's reward where it fell.
     *
     * <p>Registered on {@code MobService.onKill}. The instance is still open at this point and
     * {@code clear-grace-seconds} is what keeps it open long enough to walk over and take it —
     * that setting was written in phase 3C for this chest specifically.
     */
    public void onKill(MobKill kill) {
        if (!kill.boss()) {
            return;
        }
        LootRegistry registry = plugin.getLootRegistry();
        DropRules rules = registry.dropRules();
        LootTable table = rules.bossTable().isBlank() ? null : registry.table(rules.bossTable());
        if (table == null) {
            plugin.getLogger().warning("Boss reward skipped: drops.boss.table names '"
                    + rules.bossTable() + "', which is not a table in loot.yml.");
            return;
        }
        DungeonInstance instance = kill.instance();
        Block block = rewardSpot(kill.entity().getLocation(), instance);
        if (block == null) {
            plugin.getLogger().warning("Boss reward skipped for instance#" + instance.id()
                    + ": no floor was found near where the boss died.");
            return;
        }

        // Seeded, unlike an ordinary mob's drops: where the boss dies is not reproducible, but
        // WHAT is in its hoard should be, so that a seed still describes the dungeon it names.
        RandomGenerator random = Seeds.derive(instance.result().seed(),
                instance.result().bossNodeId(), REWARD_STREAM);
        block.setType(rules.bossMaterial(), false);
        LootService.Roll roll = plugin.getLootService().roll(table, difficultyOf(), random);
        int written = fill(block, roll.items());
        plugin.getDungeonChestTag().apply(block, instance.id(), table.id());

        plugin.getInstanceManager().broadcast(instance,
                plugin.getMessages().get("loot.boss-reward",
                        Placeholder.unparsed("boss", bossName(kill))));
        plugin.getLogger().info("Boss reward placed: instance#" + instance.id() + " - " + written
                + " items at " + block.getX() + "," + block.getY() + "," + block.getZ());
    }

    /**
     * A block the reward chest can stand in, as close to the death spot as possible.
     *
     * <p>Walks down from where the boss died looking for the first solid block with space above
     * it. Down rather than out: a boss dies standing on the floor of its own room almost always,
     * and the exceptions — killed mid-knockback, killed on a step — are all a block or two above
     * the floor rather than beside it.
     *
     * @return the block to turn into a chest, or {@code null} when nothing suitable is inside the
     *         instance's bounds. Outside the bounds is not a candidate at all: the teardown wipes
     *         the union of the rooms, and a chest outside it would outlive the dungeon
     */
    private @Nullable Block rewardSpot(Location death, DungeonInstance instance) {
        World world = death.getWorld();
        if (world == null) {
            return null;
        }
        Aabb bounds = instance.bounds();
        int x = death.getBlockX();
        int z = death.getBlockZ();
        if (x < bounds.minX() || x > bounds.maxX() || z < bounds.minZ() || z > bounds.maxZ()) {
            return null;
        }
        for (int y = death.getBlockY() + 1; y >= death.getBlockY() - REWARD_SEARCH; y--) {
            if (y - 1 < bounds.minY() || y > bounds.maxY()) {
                continue;
            }
            Block candidate = world.getBlockAt(x, y, z);
            Block below = world.getBlockAt(x, y - 1, z);
            if (candidate.getType().isAir() && below.getType().isSolid()) {
                return candidate;
            }
        }
        return null;
    }

    /** Writes the reward into the chest. Straight down the slots — a hoard may read as packed. */
    private int fill(Block block, List<ItemStack> items) {
        if (!(block.getState(false) instanceof org.bukkit.block.Container container)) {
            return 0;
        }
        int written = 0;
        for (ItemStack item : items) {
            if (container.getInventory().firstEmpty() == -1) {
                break;
            }
            container.getInventory().addItem(item);
            written++;
        }
        return written;
    }

    /**
     * The name to announce.
     *
     * <p>The display name when the operator gave one, otherwise the {@code mobs.yml} id. Never a
     * hardcoded word: an operator who named their boss expects to read that name, and a mob whose
     * entry was edited away between the spawn and the kill still has an id worth printing.
     */
    private String bossName(MobKill kill) {
        LivingEntity entity = kill.entity();
        if (entity.customName() != null) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(entity.customName());
        }
        MobDefinition definition = kill.definition();
        return definition == null ? MobClass.BOSS.key() : definition.id();
    }

    /** Dungeons run at the configured difficulty; per-instance difficulty arrives with phase 9. */
    private Difficulty difficultyOf() {
        return plugin.getMobRegistry().defaultDifficulty();
    }
}
