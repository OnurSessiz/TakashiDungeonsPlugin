package com.takashi.dungeons.shop;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.LayoutNode;
import com.takashi.dungeons.generation.Seeds;
import com.takashi.dungeons.generation.Vec3i;
import com.takashi.dungeons.instance.DungeonInstance;
import com.takashi.dungeons.mob.ColumnProbe;
import com.takashi.dungeons.mob.MobProvider;
import com.takashi.dungeons.mob.RoomSpawnFinder;
import com.takashi.dungeons.mob.WorldColumnProbe;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Puts a merchant in a dungeon's entrance room, and knows which entity is one.
 *
 * <h2>The entrance is the only room this can go in</h2>
 * It is also the only room that is otherwise empty: {@code MobPopulator} skips it deliberately (a
 * player is teleported into it and should not land in a fight) and {@code LootPopulator} gives it
 * no table at all. So the merchant competes with nothing, and nothing here has to teach the other
 * two populators about shops.
 *
 * <h2>Its own random stream</h2>
 * Placement draws on {@link #SHOP_STREAM}, separate from the mob and chest streams for the reason
 * phase 4B wrote down the hard way: two systems indexing rooms the same way and sharing a stream
 * are locked together for ever, and no test that exercises them separately would ever see it.
 *
 * <h2>Live merchants live in memory</h2>
 * The entity id → instance map is what answers a right-click. The PDC tag exists for what the map
 * cannot survive — see {@link ShopKeeperTag}.
 */
public final class ShopManager {

    /** Distinct from {@code CHEST_STREAM} and {@code REWARD_STREAM}. */
    public static final long SHOP_STREAM = 0x53_09L;

    private final TakashiDungeonsPlugin plugin;
    private final ShopKeeperTag tag;

    /** Merchant entity → the instance it belongs to. */
    private final Map<UUID, Integer> keepers = new HashMap<>();

    /** instance → player → entry id → how many they have bought. Dies with the instance. */
    private final Map<Integer, Map<UUID, Map<String, Integer>>> purchases = new HashMap<>();

    public ShopManager(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
        this.tag = new ShopKeeperTag(plugin);
    }

    public ShopKeeperTag tag() {
        return tag;
    }

    /** How many merchants are standing right now — for the admin listing. */
    public int liveCount() {
        return keepers.size();
    }

    // ------------------------------------------------------------------ placement

    /** What placement did, so the caller has one line to log. */
    public record Report(boolean placed, @Nullable String reason) {

        public static Report skipped(String reason) {
            return new Report(false, reason);
        }

        public static final Report PLACED = new Report(true, null);
    }

    /**
     * Places the merchant. <b>Main thread only</b> — it reads blocks and spawns an entity.
     *
     * <p>Never throws for a reason the operator could have caused: an unusable catalogue, a room
     * with no floor, a provider that refused. Each of those comes back as a reason, because a
     * dungeon without a merchant is a smaller dungeon and a dungeon that failed to generate is a
     * held slot and a player at a portal being told nothing.
     */
    public Report place(DungeonInstance instance, World world) {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("The merchant must be placed on the main thread.");
        }
        ShopRegistry shop = plugin.getShopRegistry();
        if (shop == null || !shop.isUsable()) {
            return Report.skipped("the shop is not usable");
        }
        KeeperSpec spec = shop.keeper();

        LayoutNode entrance = instance.result().layout().root();
        if (entrance == null) {
            return Report.skipped("the dungeon has no entrance room");
        }

        RandomGenerator random = Seeds.derive(instance.result().seed(), entrance.id(), SHOP_STREAM);
        if (random.nextDouble() >= spec.chance()) {
            return Report.skipped("chance");
        }

        Aabb box = entrance.bounds();
        // FAWE leaves the chunks it pasted into unloaded, and a block read in an unloaded chunk
        // answers for air — the survey would find no floor and every dungeon would come back
        // "nowhere to stand". The same trap CLAUDE.md records for console block checks.
        loadChunks(world, box);

        Location spot = findSpot(world, instance, box, spec, random);
        if (spot == null) {
            return Report.skipped("no standable spot far enough from where players land");
        }

        LivingEntity keeper = spawn(spec, spot);
        if (keeper == null) {
            return Report.skipped("the provider '" + spec.providerId() + "' refused to spawn "
                    + spec.mobKey());
        }
        dress(keeper, spec);
        tag.apply(keeper, instance.id());
        keepers.put(keeper.getUniqueId(), instance.id());
        return Report.PLACED;
    }

    /**
     * A standable column in the entrance room, far enough from the arrival point.
     *
     * <p>The distance check is the whole reason this is not one line. {@link RoomSpawnFinder}
     * starts from the room's centre, and {@code entranceSpawn} is derived from the same room — so
     * the first candidate is regularly the square a player is about to land on, and a merchant
     * standing in somebody's face on arrival reads as a bug.
     */
    private @Nullable Location findSpot(World world, DungeonInstance instance, Aabb box,
                                        KeeperSpec spec, RandomGenerator random) {
        ColumnProbe probe = new WorldColumnProbe(world);
        List<Vec3i> surface = new RoomSpawnFinder(probe).survey(box, random);
        if (surface.isEmpty()) {
            return null;
        }
        Location arrival = instance.entranceSpawn(world);
        double minimum = spec.minDistance() * (double) spec.minDistance();

        Vec3i fallback = null;
        for (Vec3i point : surface) {
            Location candidate = standOn(world, point);
            if (arrival == null || candidate.distanceSquared(arrival) >= minimum) {
                return candidate;
            }
            fallback = point;
        }
        // Every square is inside the exclusion radius: a very small entrance room. A merchant one
        // block from the arrival point is still better than no merchant, and the caller's log line
        // is not the place to explain room sizes.
        return fallback == null ? null : standOn(world, fallback);
    }

    private @Nullable LivingEntity spawn(KeeperSpec spec, Location location) {
        MobProvider provider = plugin.getMobRegistry().provider(spec.providerId());
        if (provider == null || !provider.isAvailable()) {
            return null;
        }
        return provider.spawn(spec.mobKey(), location);
    }

    /**
     * Makes the merchant a merchant rather than a mob that happens to be standing there.
     *
     * <p>AI off is doing more work than it looks: it is also what stops a villager wandering out of
     * the room, panicking, breeding, restocking trades and — the one that matters — being converted
     * by a zombie. Invulnerable covers the rest of that last case.
     */
    private void dress(LivingEntity keeper, KeeperSpec spec) {
        keeper.setInvulnerable(true);
        keeper.setPersistent(true);
        keeper.setRemoveWhenFarAway(false);
        keeper.setCollidable(false);
        keeper.setCanPickupItems(false);
        if (keeper instanceof Mob mob) {
            mob.setAware(false);
        }
        keeper.setAI(false);
        if (spec.displayName() != null) {
            keeper.customName(MiniMessage.miniMessage().deserialize(spec.displayName()));
            keeper.setCustomNameVisible(true);
        }
    }

    // ------------------------------------------------------------------ buying

    /** How a purchase attempt ended. The listener maps each to a message. */
    public enum Purchase {
        OK,
        /** The dungeon closed while the window was open. */
        GONE,
        /** This player has bought their allowance of this entry. */
        LIMIT,
        /** Not enough currency. */
        POOR,
        /** Nowhere to put it. */
        NO_ROOM
    }

    /**
     * Sells one entry to one player.
     *
     * <p><b>The order is the design.</b> Room is checked before the money is taken, because there
     * is no clean way back once it has been: an item currency has no "give it back" that cannot
     * itself fail on a full inventory, and a player who paid and received nothing has lost
     * something the plugin cannot return. So: is it gone, may they buy it, is there room, take,
     * give.
     */
    public Purchase buy(Player player, int instanceId, ShopEntry entry) {
        var instances = plugin.getInstanceManager();
        var instance = instances == null ? null : instances.get(instanceId);
        if (instance == null || !instance.isActive()) {
            return Purchase.GONE;
        }
        if (entry.hasLimit() && bought(instanceId, player.getUniqueId(), entry.id()) >= entry.limit()) {
            return Purchase.LIMIT;
        }

        ShopRegistry shop = plugin.getShopRegistry();
        ShopCurrency currency = shop.currency();
        if (currency == null || !currency.isAvailable()) {
            return Purchase.GONE;
        }
        ItemStack goods = plugin.getLootService().build(entry.item(), new Random());
        if (!hasRoomFor(player, goods)) {
            return Purchase.NO_ROOM;
        }
        if (currency.balance(player) < entry.price() || !currency.take(player, entry.price())) {
            return Purchase.POOR;
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(goods);
        if (!leftover.isEmpty()) {
            // Unreachable: the room check above just said it fits. If it ever happens the player
            // has already paid, so the goods go on the floor rather than nowhere — and the log
            // line is how we would find out that the check is wrong.
            plugin.getLogger().warning("Shop: " + player.getName() + " paid for " + entry.id()
                    + " but the inventory was full after the check - dropped at their feet.");
            leftover.values().forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
        }
        record(instanceId, player.getUniqueId(), entry.id());
        return Purchase.OK;
    }

    /**
     * Whether this stack fits without displacing anything.
     *
     * <p>An empty slot takes anything; a matching partial stack takes what it has room for. Asked
     * before the money moves, which is the only moment the answer is worth anything.
     */
    private boolean hasRoomFor(Player player, ItemStack stack) {
        int needed = stack.getAmount();
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                return true;
            }
            if (slot.isSimilar(stack)) {
                needed -= Math.max(0, slot.getMaxStackSize() - slot.getAmount());
                if (needed <= 0) {
                    return true;
                }
            }
        }
        return needed <= 0;
    }

    /**
     * How many of one entry this player has already bought in this dungeon.
     *
     * <p>In memory and scoped to the instance, like a party: it is a fact about right now, it dies
     * with the dungeon it belongs to, and nothing about it wants a database. A limit that survived
     * a restart would also be a limit nobody could explain — the dungeon it was counted in no
     * longer exists.
     */
    public int bought(int instanceId, UUID player, String entryId) {
        return purchases.getOrDefault(instanceId, Map.of())
                .getOrDefault(player, Map.of())
                .getOrDefault(entryId, 0);
    }

    private void record(int instanceId, UUID player, String entryId) {
        purchases.computeIfAbsent(instanceId, id -> new HashMap<>())
                .computeIfAbsent(player, id -> new HashMap<>())
                .merge(entryId, 1, Integer::sum);
    }

    // ------------------------------------------------------------------ lookup and teardown

    /** The instance whose merchant this entity is, or {@code null} if it is not one. */
    public @Nullable Integer instanceOf(Entity entity) {
        Integer live = keepers.get(entity.getUniqueId());
        if (live != null) {
            return live;
        }
        // Not in the map but tagged by THIS run: the map was rebuilt (a /reload) while the entity
        // stayed. Tagged by an older session is debris and deliberately not adopted.
        if (tag.isKeeper(entity) && tag.isFromThisSession(entity)) {
            return tag.instanceId(entity);
        }
        return null;
    }

    /**
     * The instance closed.
     *
     * <p>The entity itself is not removed here: the teardown's box sweep already deletes every
     * non-player entity in the room union, and the merchant is standing in it. Removing it twice
     * would only make the close report count it twice.
     */
    public void forget(int instanceId) {
        keepers.values().removeIf(id -> id == instanceId);
        // The limits go with it. They counted purchases inside a dungeon that no longer exists,
        // and keeping them would make the next run of the same numbered instance start used up.
        purchases.remove(instanceId);
    }

    /** Shuts every shop window that belongs to this instance. */
    public void closeMenus(int instanceId) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ShopMenu menu
                    && menu.instanceId() == instanceId) {
                player.closeInventory();
            }
        }
    }

    /** Drops every live merchant from the map — for disable and reload. */
    public void clear() {
        keepers.clear();
        purchases.clear();
    }

    // ------------------------------------------------------------------ helpers

    private static Location standOn(World world, Vec3i floor) {
        return new Location(world, floor.x() + 0.5, floor.y() + 1, floor.z() + 0.5);
    }

    private static void loadChunks(World world, Aabb box) {
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    world.getChunkAt(cx, cz);
                }
            }
        }
    }
}
