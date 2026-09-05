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
 * <h2>Two rooms are skipped</h2>
 * The <b>entrance</b>, because a player is teleported into it and should not arrive mid-fight, and
 * because a party gathers there. The <b>boss room</b>, because it is phase 3C's job and a boss
 * drawn by the ordinary room spawner would be a boss standing among four zombies.
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
     */
    public record Report(int roomsPopulated, int roomsSkipped, int spawned, int refused,
                         int roomsShortOfSpace) {
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
            throw new IllegalStateException("Mob yerleştirme main thread'de yapılmalı.");
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

        for (LayoutNode node : result.layout().nodes()) {
            if (isSkipped(node, result, rules)) {
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
                Location where = new Location(world, point.x() + 0.5, point.y() + 1,
                        point.z() + 0.5);
                LivingEntity entity = service.spawn(definition, where, difficulty, random);
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
        return new Report(populated, skipped, spawned, refused, shortOfSpace);
    }

    /**
     * The entrance and the boss room are left alone; so is a room whose type says entrance even
     * when the graph did not make it the root, because a mapper marked it as a place players
     * arrive.
     */
    private boolean isSkipped(LayoutNode node, DungeonGenerator.Result result, SpawnRules rules) {
        if (node.id() == result.bossNodeId()) {
            return true;
        }
        boolean entrance = node.id() == 0 || node.template().type() == RoomType.ENTRANCE;
        return entrance && !rules.entranceMobs();
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
