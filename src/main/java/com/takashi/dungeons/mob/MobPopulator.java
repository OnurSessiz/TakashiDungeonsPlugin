package com.takashi.dungeons.mob;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.DungeonGenerator;
import com.takashi.dungeons.generation.LayoutNode;
import com.takashi.dungeons.generation.RoomType;
import com.takashi.dungeons.generation.Seeds;
import com.takashi.dungeons.generation.Vec3i;
import com.takashi.dungeons.instance.DungeonInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Fills a freshly generated dungeon with mobs.
 *
 * <h2>What decides what</h2>
 * <ul>
 *   <li><b>How many</b> — the room's walkable floor divided by {@code spawn.density}. Room size is
 *       what the player sees; an empty great hall and a packed corridor both read as bugs.</li>
 *   <li><b>Which class</b> — the room's <i>relative</i> depth, {@code depth / maxDepth}, through
 *       {@link SpawnRules#bandFor}. Relative, so a four-room small dungeon still has a curve.</li>
 *   <li><b>Which mob</b> — a weighted draw inside that class ({@link MobRegistry#pick}).</li>
 *   <li><b>How hard</b> — the instance's difficulty, applied by {@link MobService}.</li>
 * </ul>
 *
 * <h2>The entrance is skipped; the boss room is filled separately</h2>
 * The <b>entrance</b> gets nothing: a player is teleported into it and should not arrive
 * mid-fight, and a party gathers there. The <b>boss room</b> is not skipped but goes down a
 * different path ({@link #populateBoss}) — the rules above give the wrong answer for it in both
 * halves, and a boss drawn by the ordinary room spawner would be a boss standing among four
 * zombies.
 *
 * <h2>Reproducibility</h2>
 * Each room draws from {@code Seeds.derive(dungeonSeed, nodeId)}, so the same seed produces the
 * same dungeon <i>and</i> the same mobs ({@code generation.md} §13) — and one room's contents do
 * not shift because another room happened to be populated first.
 */
public final class MobPopulator {

    /**
     * Result of populating one dungeon — for the command output and the log line.
     *
     * @param roomsShortOfSpace rooms that had fewer standing places than the density asked for;
     *                          not an error, but the number that explains a thin dungeon
     * @param bossMob           the id of the boss that was placed, or {@code null} if none was
     * @param guards            how many companions stand with it
     * @param bossProblem       why there is no boss, in a sentence — {@code null} when there is
     *                          one, or when the layout has no boss room to fill
     */
    public record Report(int roomsPopulated, int roomsSkipped, int spawned, int refused,
                         int roomsShortOfSpace, @Nullable String bossMob, int guards,
                         @Nullable String bossProblem) {

        public boolean hasBoss() {
            return bossMob != null;
        }
    }

    private final TakashiDungeonsPlugin plugin;

    public MobPopulator(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Populates every eligible room. <b>Main thread only</b> — it reads blocks and spawns entities.
     *
     * @param instance   the live dungeon
     * @param world      the dungeon world
     * @param difficulty which difficulty scaling to apply
     */
    public Report populate(DungeonInstance instance, World world, Difficulty difficulty) {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Mob placement must happen on the main thread.");
        }
        MobRegistry registry = plugin.getMobRegistry();
        MobService service = plugin.getMobService();
        SpawnRules rules = registry.spawnRules();
        RoomSpawnFinder finder = new RoomSpawnFinder(new WorldColumnProbe(world));

        DungeonGenerator.Result result = instance.result();
        int maxDepth = maxDepth(result);
        int populated = 0;
        int skipped = 0;
        int spawned = 0;
        int refused = 0;
        int shortOfSpace = 0;

        BossOutcome boss = BossOutcome.NONE;

        for (LayoutNode node : result.layout().nodes()) {
            if (node.id() == result.bossNodeId()) {
                boss = populateBoss(instance, world, difficulty, node, result, rules, finder);
                spawned += boss.spawned();
                if (boss.spawned() > 0) {
                    populated++;
                } else {
                    skipped++;
                }
                continue;
            }
            if (isSkipped(node, rules)) {
                skipped++;
                continue;
            }
            Aabb box = node.bounds();
            // FAWE leaves the chunks it pasted into unloaded, and a block read in an unloaded
            // chunk answers for air. Without this the survey finds no floor and every room comes
            // back empty -- the same trap CLAUDE.md records for console block checks.
            loadChunks(world, box);

            RandomGenerator random = Seeds.derive(result.seed(), node.id());
            List<Vec3i> surface = finder.survey(box, random);
            int wanted = rules.countFor(surface.size());
            List<Vec3i> points = finder.spread(surface, wanted);
            if (points.size() < wanted) {
                shortOfSpace++;
            }

            double ratio = maxDepth == 0 ? 1.0 : (double) node.depth() / maxDepth;
            SpawnRules.Band band = rules.bandFor(ratio);
            if (band == null) {
                skipped++;
                continue;
            }
            int placed = 0;
            for (Vec3i point : points) {
                MobClass mobClass = band.pick(random);
                MobDefinition definition = mobClass == null ? null : registry.pick(mobClass, random);
                if (definition == null) {
                    // An empty class pool is a configuration gap, not an error: the operator may
                    // simply have no super_strong mobs yet. The room ends up lighter, and the
                    // report says so.
                    refused++;
                    continue;
                }
                LivingEntity entity = service.spawn(definition, standOn(world, point), difficulty,
                        random, instance.id(), false);
                if (entity == null) {
                    refused++;
                } else {
                    placed++;
                }
            }
            spawned += placed;
            if (placed > 0) {
                populated++;
            } else {
                skipped++;
            }
        }
        return new Report(populated, skipped, spawned, refused, shortOfSpace,
                boss.mobId(), boss.guards(), boss.problem());
    }

    /**
     * The entrance is left alone; so is a room whose type says entrance even when the graph did
     * not make it the root, because a mapper marked it as a place players arrive.
     */
    private boolean isSkipped(LayoutNode node, SpawnRules rules) {
        boolean entrance = node.id() == 0 || node.template().type() == RoomType.ENTRANCE;
        return entrance && !rules.entranceMobs();
    }

    // ------------------------------------------------------------------ the boss room

    /**
     * What came of the boss room.
     *
     * @param mobId   the boss's registry id, or {@code null} if none was placed
     * @param guards  companions actually spawned
     * @param problem the sentence explaining an absent boss, or {@code null}
     */
    private record BossOutcome(@Nullable String mobId, int guards, @Nullable String problem) {

        /** No boss room in the layout — not a problem, just nothing to do. */
        static final BossOutcome NONE = new BossOutcome(null, 0, null);

        static BossOutcome failed(String problem) {
            return new BossOutcome(null, 0, problem);
        }

        int spawned() {
            return (mobId == null ? 0 : 1) + guards;
        }
    }

    /**
     * Fills the boss room: one boss on the room's centre-most standing place, and a small, fixed
     * retinue around it.
     *
     * <h2>The boss stands on the seed, not on a random point</h2>
     * {@link RoomSpawnFinder#survey} returns the walkable surface in breadth-first order from a
     * seed found by walking rings outward from the centre of the box — so its <b>first element is
     * the standable column nearest the middle of the room</b>, already computed. That is where a
     * boss belongs: the player comes through a door and the room's occupant is in front of them,
     * not behind a pillar in a corner. Nothing extra is measured to get it.
     *
     * <p>The guards follow from the same list through {@link RoomSpawnFinder#spread}, which is why
     * they end up ringing the boss at the minimum spacing rather than clustered on one side.
     *
     * <h2>No fallback when the boss pool is empty</h2>
     * The room is left empty and the reason is reported. Drawing a {@code super_strong} instead
     * would be the silent redirection {@code MobRegistry} refuses on principle: an operator who
     * emptied the boss pool would find out weeks later, through a complaint that the last room
     * feels like the one before it.
     */
    private BossOutcome populateBoss(DungeonInstance instance, World world, Difficulty difficulty,
                                     LayoutNode node, DungeonGenerator.Result result,
                                     SpawnRules rules, RoomSpawnFinder finder) {
        SpawnRules.Boss bossRules = rules.boss();
        if (!bossRules.enabled()) {
            return BossOutcome.failed("boss placement is switched off (spawn.boss.enabled)");
        }
        MobRegistry registry = plugin.getMobRegistry();
        MobService service = plugin.getMobService();

        loadChunks(world, node.bounds());
        RandomGenerator random = Seeds.derive(result.seed(), node.id());
        List<Vec3i> surface = finder.survey(node.bounds(), random);
        if (surface.isEmpty()) {
            return BossOutcome.failed("no standable place was found in the boss room");
        }

        MobDefinition definition = registry.pick(MobClass.BOSS, random);
        if (definition == null) {
            return BossOutcome.failed("the boss pool is empty - no mob in mobs.yml has 'class: boss'");
        }
        // One point for the boss plus one per guard, taken from the same spread so the minimum
        // spacing holds between the boss and its retinue as well as among the guards.
        List<Vec3i> points = finder.spread(surface, 1 + bossRules.guards());
        LivingEntity boss = service.spawn(definition, standOn(world, points.get(0)), difficulty,
                random, instance.id(), true);
        if (boss == null) {
            return BossOutcome.failed("the boss could not be spawned: " + definition.address());
        }

        int guards = 0;
        for (int i = 1; i < points.size(); i++) {
            MobDefinition guard = registry.pick(bossRules.guardClass(), random);
            if (guard == null) {
                // The guard class has no mobs in it. The boss is standing and the dungeon is
                // finishable; that is the part that matters, so this is not a failure.
                break;
            }
            if (service.spawn(guard, standOn(world, points.get(i)), difficulty, random,
                    instance.id(), false) != null) {
                guards++;
            }
        }
        return new BossOutcome(definition.id(), guards, null);
    }

    /** The mob stands on top of the floor block, in the middle of it. */
    private Location standOn(World world, Vec3i floor) {
        return new Location(world, floor.x() + 0.5, floor.y() + 1, floor.z() + 0.5);
    }

    /** The deepest room in the layout; 0 when there is only an entrance. */
    private int maxDepth(DungeonGenerator.Result result) {
        int max = 0;
        for (LayoutNode node : result.layout().nodes()) {
            max = Math.max(max, node.depth());
        }
        return max;
    }

    /**
     * Loads every chunk the room's box touches.
     *
     * <p>Synchronous on purpose. The alternative — populating asynchronously as chunks arrive —
     * would spawn mobs into a dungeon a player may already be walking through, and a room that
     * fills up behind you is worse than a two-tick pause before the door opens. The dungeon world
     * is void-generated, so a chunk that is not already resident costs almost nothing to make.
     */
    private void loadChunks(World world, Aabb box) {
        int minChunkX = box.minX() >> 4;
        int maxChunkX = box.maxX() >> 4;
        int minChunkZ = box.minZ() >> 4;
        int maxChunkZ = box.maxZ() >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    world.getChunkAt(cx, cz);
                }
            }
        }
    }
}
