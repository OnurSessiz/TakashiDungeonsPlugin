package com.takashi.dungeons.loot;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.DungeonGenerator;
import com.takashi.dungeons.generation.LayoutNode;
import com.takashi.dungeons.generation.RoomType;
import com.takashi.dungeons.generation.Seeds;
import com.takashi.dungeons.generation.Vec3i;
import com.takashi.dungeons.instance.DungeonInstance;
import com.takashi.dungeons.mob.Difficulty;
import com.takashi.dungeons.mob.RoomSpawnFinder;
import com.takashi.dungeons.mob.WorldColumnProbe;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Puts loot in a freshly generated dungeon.
 *
 * <h2>Hybrid, and the mapper wins</h2>
 * A room whose schematic contains chests has <b>those</b> chests filled, all of them, wherever the
 * mapper put them. Only a room with none gets one placed. This is the same principle that keeps
 * spawn points out of the room {@code .yml} files, pointed the other way: the person who built the
 * room knows where a chest belongs in it, and a procedural search that overruled them would put
 * the treasure in the open next to the alcove they carved for it.
 *
 * <h2>What decides what</h2>
 * <ul>
 *   <li><b>Whether a room gets loot</b> — its type, through {@link ChestRules#tableFor}. The
 *       entrance ships with no table and is not touched at all.</li>
 *   <li><b>Which table</b> — the same lookup. The boss room draws {@code room_chest}, not
 *       {@code boss_chest}: a hidden chest in the lair is a bonus, and giving it the boss's own
 *       table would make the reward for killing the boss the second-best thing in the room.</li>
 *   <li><b>What comes out</b> — {@link LootService#roll}, at the instance's difficulty.</li>
 * </ul>
 *
 * <h2>The boss room is filled but never furnished</h2>
 * A chest a mapper drew into the boss room <i>is</i> stocked. Nothing is ever <i>placed</i> there:
 * the reward is the chest that appears where the boss dies (phase 4C), and a second chest standing
 * in the corner from the moment the door opens would announce the ending before the fight does.
 *
 * <h2>Reproducibility</h2>
 * Each room draws from {@link Seeds#derive(long, int, long)} on the {@link #CHEST_STREAM} stream,
 * so the same seed gives the same dungeon, the same mobs <i>and</i> the same chests — while
 * keeping loot's numbers independent of the mob populator's, which indexes rooms identically.
 */
public final class LootPopulator {

    /**
     * Which stream of the dungeon seed belongs to loot.
     *
     * <p>Any constant other than the mob populator's would do; this one is arbitrary and fixed.
     * Changing it changes every chest in every existing seed, so it is not a knob.
     */
    public static final long CHEST_STREAM = 0x10_07L;

    /**
     * What came of filling one dungeon.
     *
     * @param filled        chests that received at least one item
     * @param placed        chests the plugin created because the room had none of its own
     * @param fromSchematic chests that were already in the room
     * @param items         total stacks put into all of them
     * @param empty         draws that landed on a class with no items in it
     * @param roomsSkipped  rooms whose type has no table, or which had nowhere to put a chest
     * @param noSpace       rooms that needed a chest placed and had no standable floor for one
     */
    public record Report(int filled, int placed, int fromSchematic, int items, int empty,
                         int roomsSkipped, int noSpace) {

        public boolean isEmpty() {
            return filled == 0 && placed == 0;
        }
    }

    /**
     * One chest as the player meets it: one inventory, and every block that opens it.
     *
     * <p>The two are not the same list for a double chest, and keeping them apart is not
     * bookkeeping. It is filled <b>once</b>, through {@link #primary}, because a player opens it
     * once. It must be tagged on <b>every</b> block, because {@code ChestListener} is handed a
     * block and breaking either half drops the whole inventory — tagging only the half that
     * happened to be scanned first leaves the other half minable, and the protection then works
     * on exactly one side of a chest for no reason a player could ever guess.
     *
     * @param primary the block whose state owns the inventory
     * @param blocks  every block belonging to it, {@code primary} included
     */
    private record ChestGroup(Block primary, List<Block> blocks) {
    }

    private final TakashiDungeonsPlugin plugin;

    public LootPopulator(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Fills every eligible room. <b>Main thread only</b> — it reads and writes blocks.
     *
     * @param instance   the live dungeon
     * @param world      the dungeon world
     * @param difficulty which difficulty the tables are scaled for
     */
    public Report populate(DungeonInstance instance, World world, Difficulty difficulty) {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Chest placement must happen on the main thread.");
        }
        LootRegistry registry = plugin.getLootRegistry();
        LootService service = plugin.getLootService();
        DungeonChestTag tag = plugin.getDungeonChestTag();
        ChestRules rules = registry.chestRules();
        RoomSpawnFinder finder = new RoomSpawnFinder(new WorldColumnProbe(world));

        DungeonGenerator.Result result = instance.result();
        int filled = 0;
        int placed = 0;
        int fromSchematic = 0;
        int items = 0;
        int empty = 0;
        int skipped = 0;
        int noSpace = 0;

        for (LayoutNode node : result.layout().nodes()) {
            RoomType type = typeOf(node, result);
            String tableId = rules.tableFor(type);
            LootTable table = tableId == null ? null : registry.table(tableId);
            if (table == null) {
                // No table for this room type is the shipped answer for the entrance, and it means
                // "leave the room alone" rather than "put an empty chest in it". A table id that
                // names nothing is a different thing and the registry already said so at load.
                skipped++;
                continue;
            }

            Aabb box = node.bounds();
            // FAWE leaves the chunks it pasted into unloaded, and a block read in an unloaded chunk
            // answers for air. Without this the scan finds no chests anywhere and every room looks
            // like it needs one placed -- the same trap CLAUDE.md records for console block checks.
            loadChunks(world, box);

            RandomGenerator random = Seeds.derive(result.seed(), node.id(), CHEST_STREAM);
            List<ChestGroup> chests = findChests(world, box, rules);

            if (chests.isEmpty()) {
                if (!rules.mayPlaceIn(type)) {
                    skipped++;
                    continue;
                }
                Block block = placeChest(world, box, rules, finder, random);
                if (block == null) {
                    noSpace++;
                    continue;
                }
                chests = List.of(new ChestGroup(block, List.of(block)));
                placed++;
            } else {
                fromSchematic += chests.size();
            }

            for (ChestGroup chest : chests) {
                LootService.Roll roll = service.roll(table, difficulty, random);
                empty += roll.empty();
                int written = fill(chest.primary(), roll.items(), random);
                items += written;
                if (written > 0) {
                    filled++;
                }
                // Every block, not just the primary: see ChestGroup.
                for (Block block : chest.blocks()) {
                    tag.apply(block, instance.id(), table.id());
                }
            }
        }
        return new Report(filled, placed, fromSchematic, items, empty, skipped, noSpace);
    }

    /**
     * The room's role. The boss room is the one the graph named, not one whose template happens to
     * say {@code boss} — the same rule {@code MobPopulator} follows, and for the same reason: a
     * layout has exactly one ending and it is the one the critical path chose.
     */
    private RoomType typeOf(LayoutNode node, DungeonGenerator.Result result) {
        if (node.id() == result.bossNodeId()) {
            return RoomType.BOSS;
        }
        if (node.id() == 0) {
            return RoomType.ENTRANCE;
        }
        return node.template().type();
    }

    // ------------------------------------------------------------------ finding what is there

    /**
     * Every container the mapper put in the room, one entry per <b>inventory</b>.
     *
     * <h2>A double chest is one chest</h2>
     * Its two blocks share one inventory, so filling both halves would roll the table twice into
     * the same box. That gets the incentive backwards: two chests standing apart are two finds and
     * should be worth twice as much, while a double chest is a single find the player opens once.
     * The halves are grouped by the location of the inventory's left side, which is stable
     * whichever half is scanned first.
     */
    private List<ChestGroup> findChests(World world, Aabb box, ChestRules rules) {
        // Keyed by inventory identity rather than by block, so the second half of a double chest
        // joins the first entry instead of becoming one beside it.
        Map<String, List<Block>> byInventory = new LinkedHashMap<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!rules.isContainer(block.getType())) {
                        continue;
                    }
                    byInventory.computeIfAbsent(inventoryKey(block), key -> new ArrayList<>())
                            .add(block);
                }
            }
        }
        List<ChestGroup> groups = new ArrayList<>(byInventory.size());
        for (List<Block> blocks : byInventory.values()) {
            groups.add(new ChestGroup(blocks.get(0), blocks));
        }
        return groups;
    }

    /**
     * A key that is the same for both halves of a double chest and unique for everything else.
     *
     * <p>Falls back to the block's own coordinates whenever the state is not a chest or the holder
     * is not a double — a barrel, a single chest, or a block whose state could not be read.
     */
    private String inventoryKey(Block block) {
        BlockState state = block.getState(false);
        if (state instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest doubleChest
                    && doubleChest.getLeftSide() instanceof Chest left) {
                return left.getX() + ":" + left.getY() + ":" + left.getZ();
            }
        }
        return block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    // ------------------------------------------------------------------ placing one

    /**
     * Puts a chest on the floor of a room that has none.
     *
     * <p>Exactly one, and it comes from the same {@link RoomSpawnFinder} the mobs use: a place the
     * player can walk to, reached by flood fill from the middle of the room, so it is never sealed
     * behind a wall. The seed is skipped when there is more than one candidate — the centre-most
     * column is where a boss stands and where a party lands, and a chest in the exact middle of
     * every unfurnished room is a tell.
     *
     * @return the block the chest now occupies, or {@code null} when the room has no floor
     */
    private @Nullable Block placeChest(World world, Aabb box, ChestRules rules,
                                       RoomSpawnFinder finder, RandomGenerator random) {
        List<Vec3i> surface = finder.survey(box, random);
        if (surface.isEmpty()) {
            return null;
        }
        Vec3i floor = surface.get(surface.size() == 1 ? 0 : 1 + random.nextInt(surface.size() - 1));
        Block block = world.getBlockAt(floor.x(), floor.y() + 1, floor.z());
        // The survey guarantees headroom above the floor, so this block is clear. Written without
        // physics: a chest dropped in with physics on would pop off a slab or a stair the mapper
        // used as flooring, and the loot would land on the ground.
        block.setType(rules.placeMaterial(), false);
        return block;
    }

    // ------------------------------------------------------------------ filling one

    /**
     * Scatters the stacks through the container's free slots.
     *
     * <p>Scattered rather than packed from slot zero: contents that run 0, 1, 2, 3 read as
     * generated, and the same items in scattered slots read as left behind. Vanilla's loot tables
     * scatter for the same reason.
     *
     * <p>Anything already in the container is left where it is. A mapper who pre-loaded a chest
     * with a story item meant it to be there, and overwriting it would be the one kind of damage
     * this whole class is arranged to avoid.
     *
     * @return how many stacks actually went in
     */
    private int fill(Block block, List<ItemStack> items, RandomGenerator random) {
        // getState(false), not getState(): the no-argument form hands back a SNAPSHOT, and items
        // written into a snapshot's inventory are written into a copy that is then discarded. The
        // chest would come out empty with nothing anywhere reporting a failure.
        BlockState state = block.getState(false);
        if (!(state instanceof Container container)) {
            return 0;
        }
        Inventory inventory = container.getInventory();
        List<Integer> free = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                free.add(slot);
            }
        }
        int written = 0;
        for (ItemStack item : items) {
            if (free.isEmpty()) {
                // More loot than the chest can hold. Not an error worth failing on: the roll is
                // capped by the table's own 'rolls', so this only happens in a container somebody
                // has nearly filled by hand.
                break;
            }
            int index = random.nextInt(free.size());
            inventory.setItem(free.remove(index), item);
            written++;
        }
        return written;
    }

    /**
     * Loads every chunk the room's box touches.
     *
     * <p>Synchronous, for the reason {@code MobPopulator} gives: the dungeon world is
     * void-generated so a resident chunk is nearly free, and filling chests as chunks drifted in
     * would let a player walk past an empty chest that fills up behind them.
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
