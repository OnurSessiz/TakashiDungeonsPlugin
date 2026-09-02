package com.takashi.dungeons.portal;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.generation.DungeonSize;
import com.takashi.dungeons.generation.RoomTemplateStore;
import com.takashi.dungeons.instance.DungeonInstance;
import com.takashi.dungeons.instance.InstanceManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/**
 * The dungeon entrances standing in the world, and everything that puts them there or takes them
 * away.
 *
 * <h2>What a portal is for</h2>
 * Up to phase 2B the only way into a dungeon was an admin command. That is not a game: the player
 * has to <b>find</b> a way in. The portal is that way in, and it is also what makes the roadmap's
 * "in the wild it disappears, in the lobby it resets" concrete — see {@link PortalKind}.
 *
 * <h2>Nothing here survives a restart, and that is on purpose</h2>
 * Portals live in memory, exactly like the instances they open. They cannot outlive them: a
 * portal pointing at a dungeon that no longer exists is a trap for the first player who clicks
 * it. Persistence arrives with the database in phase 7, and it will persist the <b>lobby</b>
 * portals — a wild one is meant to be transient anyway.
 *
 * <p>What that costs is the possibility of debris: a crash leaves the block and its display
 * entities behind with nothing tracking them. Both are cleaned up — the entities carry a tag and
 * are swept on enable and on chunk load, and the block is restored on a clean shutdown.
 */
public final class PortalManager {

    /** Marks our display entities so orphans can be recognised after a crash. */
    private static final String TAG_KEY = "portal";

    /** How far the floating item turns each second. A full turn every eight seconds. */
    private static final float SPIN_DEGREES = 45f;

    private final TakashiDungeonsPlugin plugin;
    private final Random random = new Random();

    private final Map<Integer, DungeonPortal> portals = new LinkedHashMap<>();
    private int nextId = 1;

    /** Portals whose dungeon is being generated — a second click must not start a second one. */
    private final Set<Integer> generating = new HashSet<>();

    private BukkitTask clock;
    private long nextWildAttempt;

    public PortalManager(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lifecycle

    public void enable() {
        sweepOrphans();
        plugin.getInstanceManager().onClosed(this::onInstanceClosed);
        clock = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        spawnConfiguredLobbyPortals();
    }

    public void disable() {
        if (clock != null) {
            clock.cancel();
            clock = null;
        }
        // Remove rather than leave standing: a portal that outlives the plugin is a block a
        // player can click with nothing behind it.
        removeAll();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (DungeonPortal portal : all()) {
            spin(portal);
            refreshIfDue(portal, now);
        }
        if (now >= nextWildAttempt) {
            long interval = plugin.getConfig().getLong("portal.wild.interval-minutes", 10) * 60_000L;
            nextWildAttempt = now + Math.max(60_000L, interval);
            trySpawnWild();
        }
    }

    // ------------------------------------------------------------------ building one

    /**
     * Places a portal at a block position.
     *
     * @return the portal, or {@code null} if one already stands there
     */
    public @Nullable DungeonPortal create(Location where, PortalKind kind, @Nullable String theme,
                                          DungeonSize size) {
        Location blockLoc = where.getBlock().getLocation();
        if (at(blockLoc) != null) {
            return null;
        }
        Block block = blockLoc.getBlock();
        DungeonPortal portal = new DungeonPortal(nextId++, blockLoc, kind, theme, size,
                block.getBlockData().clone());

        block.setType(blockMaterial());
        spawnVisuals(portal);
        portals.put(portal.id(), portal);
        return portal;
    }

    private void spawnVisuals(DungeonPortal portal) {
        World world = portal.block().getWorld();
        Location centre = portal.center();

        ItemDisplay item = world.spawn(centre.clone().add(0, 1.25, 0), ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(itemMaterial()));
            // FIXED, not billboarded: the item has to hold still in the world so its own spin
            // reads as motion. A billboarded item would face every player and never appear to turn.
            e.setBillboard(Display.Billboard.FIXED);
            e.setTransformation(rotation(0f));
            // Interpolation is what turns one update a second into a smooth spin: the client
            // eases between transformations instead of snapping.
            e.setInterpolationDuration(20);
            e.setInterpolationDelay(0);
            e.setViewRange(0.6f);
            tag(e, portal.id());
        });

        TextDisplay text = world.spawn(centre.clone().add(0, 1.9, 0), TextDisplay.class, e -> {
            e.text(label());
            // CENTER billboard is what makes it read like a player's nametag — always facing you.
            e.setBillboard(Display.Billboard.CENTER);
            e.setSeeThrough(false);
            e.setDefaultBackground(false);
            e.setBackgroundColor(org.bukkit.Color.fromARGB(0));
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setViewRange(0.6f);
            tag(e, portal.id());
        });

        // The reason this exists: Display entities have no hitbox, so a player aiming at the
        // floating shard would click straight through it into the air behind. This gives the
        // visual the hitbox the eye assumes it has.
        Interaction hitbox = world.spawn(centre.clone().add(0, 0.0, 0), Interaction.class, e -> {
            e.setInteractionWidth(1.2f);
            e.setInteractionHeight(2.4f);
            e.setResponsive(true);
            tag(e, portal.id());
        });

        portal.entities(item.getUniqueId(), text.getUniqueId(), hitbox.getUniqueId());
    }

    private void spin(DungeonPortal portal) {
        if (portal.itemDisplay() == null) {
            return;
        }
        Entity entity = plugin.getServer().getEntity(portal.itemDisplay());
        if (!(entity instanceof ItemDisplay display)) {
            return;   // chunk unloaded, or the entity is gone; respawned by the sweep if needed
        }
        display.setInterpolationDelay(0);
        display.setTransformation(rotation(portal.nextSpin(SPIN_DEGREES)));
    }

    private static Transformation rotation(float degrees) {
        return new Transformation(
                new Vector3f(0f, 0f, 0f),
                new AxisAngle4f((float) Math.toRadians(degrees), 0f, 1f, 0f),
                new Vector3f(0.55f, 0.55f, 0.55f),
                new AxisAngle4f(0f, 0f, 1f, 0f));
    }

    /** Takes a portal down and puts back whatever block was there. */
    public boolean remove(DungeonPortal portal) {
        if (portals.remove(portal.id()) == null) {
            return false;
        }
        despawn(portal.itemDisplay());
        despawn(portal.textDisplay());
        despawn(portal.interaction());
        Block block = portal.block().getBlock();
        if (block.getType() == blockMaterial()) {
            // Only if it is still ours. An operator who replaced it in the meantime meant to.
            block.setBlockData(portal.previousBlock());
        }
        return true;
    }

    public int removeAll() {
        int count = 0;
        for (DungeonPortal portal : all()) {
            if (remove(portal)) {
                count++;
            }
        }
        return count;
    }

    private void despawn(@Nullable UUID id) {
        if (id == null) {
            return;
        }
        Entity entity = plugin.getServer().getEntity(id);
        if (entity != null) {
            entity.remove();
        }
    }

    // ------------------------------------------------------------------ using one

    /**
     * A player right-clicked the portal.
     *
     * <p>A bound, still-standing dungeon is <b>joined</b> rather than replaced: that is what lets
     * a second player follow their party in through the same door, and it is the seam phase 5's
     * party system will use.
     */
    public void use(Player player, DungeonPortal portal) {
        InstanceManager instances = plugin.getInstanceManager();

        if (portal.state() == PortalState.COOLDOWN) {
            long remaining = Math.max(0, portal.readyAt() - System.currentTimeMillis());
            player.sendMessage(Component.text("Bu geçit yenileniyor — "
                    + InstanceManager.formatDuration(remaining) + " sonra açılacak.",
                    NamedTextColor.YELLOW));
            return;
        }
        if (portal.boundInstanceId() != null) {
            DungeonInstance bound = instances.get(portal.boundInstanceId());
            if (bound != null && bound.isActive()) {
                instances.enter(player, bound);
                return;
            }
            // The dungeon died without the close hook reaching us (a manual close during a lag
            // spike, say). Fall through and open a fresh one rather than refuse.
            portal.bind(null);
        }
        if (!generating.add(portal.id())) {
            player.sendMessage(Component.text("Geçit hazırlanıyor, bir saniye…",
                    NamedTextColor.GRAY));
            return;
        }

        String theme = resolveTheme(portal);
        if (theme == null) {
            generating.remove(portal.id());
            player.sendMessage(Component.text(
                    "Geçit açılamıyor: kullanılabilir bir oda teması yok.", NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text("Dungeon hazırlanıyor…", NamedTextColor.GRAY));
        instances.create(theme, portal.size(), random.nextLong())
                .whenComplete((instance, error) -> plugin.getServer().getScheduler().runTask(plugin,
                        () -> finishUse(player, portal, instance, error)));
    }

    private void finishUse(Player player, DungeonPortal portal, @Nullable DungeonInstance instance,
                           @Nullable Throwable error) {
        generating.remove(portal.id());
        if (error != null) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            player.sendMessage(Component.text("Dungeon üretilemedi: " + cause.getMessage(),
                    NamedTextColor.RED));
            plugin.getLogger().warning("Geçitten üretim başarısız (" + portal + "): " + cause);
            return;
        }
        // The portal may have been removed by an operator while the dungeon was generating.
        if (!portals.containsKey(portal.id())) {
            return;
        }
        portal.bind(instance.id());
        portal.state(PortalState.OCCUPIED);
        plugin.getInstanceManager().enter(player, instance);
    }

    /**
     * The theme this portal generates from.
     *
     * <p>Falls through portal → config → "the only theme there is". The last step is what keeps a
     * default install working with no configuration at all; with several themes and nothing
     * chosen it returns {@code null} rather than picking one, for the reason in
     * {@code DungeonsCommand.resolveTheme}: a silently wrong pool looks like a generation fault.
     */
    private @Nullable String resolveTheme(DungeonPortal portal) {
        RoomTemplateStore store = plugin.getTemplateStore();
        if (store == null) {
            return null;
        }
        List<String> themes = store.themes();
        if (themes.isEmpty()) {
            return null;
        }
        for (String candidate : new String[]{portal.theme(),
                plugin.getConfig().getString("portal.theme", "")}) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            for (String theme : themes) {
                if (theme.equalsIgnoreCase(candidate)) {
                    return theme;
                }
            }
        }
        return themes.size() == 1 ? themes.get(0) : null;
    }

    // ------------------------------------------------------------------ the two kinds

    /**
     * The rule the roadmap calls "in the wild it disappears, in the lobby it resets".
     *
     * <p>A wild portal is a find; one that respawned on the spot would stop being one. A lobby
     * portal is furniture; one that vanished after a single use would leave a hole in the lobby.
     */
    private void onInstanceClosed(DungeonInstance instance) {
        for (DungeonPortal portal : all()) {
            if (portal.boundInstanceId() == null || portal.boundInstanceId() != instance.id()) {
                continue;
            }
            portal.bind(null);
            if (portal.kind() == PortalKind.WILD) {
                remove(portal);
                continue;
            }
            portal.state(PortalState.COOLDOWN);
            portal.readyAt(nextRefreshTime());
        }
    }

    /**
     * When a used lobby portal opens again — the next configured hour of the day.
     *
     * <p>An hour of the day rather than a duration: a lobby is a shared place, and "the dungeons
     * refresh at noon and midnight" is something a server can announce and players can plan
     * around. A rolling timer would have every portal come back at a different, invisible moment.
     * With the list empty there is no waiting at all.
     */
    private long nextRefreshTime() {
        List<Integer> hours = plugin.getConfig().getIntegerList("portal.lobby.refresh-hours");
        if (hours.isEmpty()) {
            return System.currentTimeMillis();
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime best = null;
        for (int hour : hours) {
            if (hour < 0 || hour > 23) {
                continue;
            }
            LocalDateTime candidate = now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
            if (!candidate.isAfter(now)) {
                candidate = candidate.plusDays(1);
            }
            if (best == null || candidate.isBefore(best)) {
                best = candidate;
            }
        }
        return best == null
                ? System.currentTimeMillis()
                : best.atZone(zone).toInstant().toEpochMilli();
    }

    private void refreshIfDue(DungeonPortal portal, long now) {
        if (portal.state() == PortalState.COOLDOWN && now >= portal.readyAt()) {
            portal.state(PortalState.READY);
        }
    }

    /** Lobby portals listed in the config, placed once at enable. */
    private void spawnConfiguredLobbyPortals() {
        List<Map<?, ?>> entries = plugin.getConfig().getMapList("portal.lobby.points");
        for (Map<?, ?> entry : entries) {
            World world = plugin.getServer().getWorld(String.valueOf(entry.get("world")));
            if (world == null) {
                plugin.getLogger().warning("Lobby geçidi atlandı — dünya yok: " + entry.get("world"));
                continue;
            }
            Location loc = new Location(world,
                    toInt(entry.get("x")), toInt(entry.get("y")), toInt(entry.get("z")));
            DungeonSize size = DungeonSize.parse(String.valueOf(entry.get("size")));
            String theme = entry.get("theme") == null ? null : String.valueOf(entry.get("theme"));
            create(loc, PortalKind.LOBBY, theme, size == null ? defaultSize() : size);
        }
        if (!entries.isEmpty()) {
            plugin.getLogger().info("Lobby geçidi kuruldu: " + entries.size());
        }
    }

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * Tries to put one portal out in the world.
     *
     * <p>Placed around a random online player, never around spawn: a dungeon that appears where
     * nobody is standing is a dungeon nobody finds. With nobody online nothing is spawned at all,
     * which is the same reasoning stated the other way round.
     */
    private void trySpawnWild() {
        if (!plugin.getConfig().getBoolean("portal.wild.enabled", true)) {
            return;
        }
        int max = plugin.getConfig().getInt("portal.wild.max", 3);
        if (countWild() >= max) {
            return;
        }
        List<String> worlds = plugin.getConfig().getStringList("portal.wild.worlds");
        List<Player> candidates = plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> worlds.isEmpty() || worlds.contains(p.getWorld().getName()))
                .map(p -> (Player) p)
                .toList();
        if (candidates.isEmpty()) {
            return;
        }

        Player anchor = candidates.get(random.nextInt(candidates.size()));
        int minDistance = plugin.getConfig().getInt("portal.wild.min-distance", 64);
        int maxDistance = plugin.getConfig().getInt("portal.wild.max-distance", 256);
        int spacing = plugin.getConfig().getInt("portal.wild.spacing", 128);

        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = minDistance + random.nextDouble() * Math.max(1, maxDistance - minDistance);
            int x = anchor.getLocation().getBlockX() + (int) (Math.cos(angle) * distance);
            int z = anchor.getLocation().getBlockZ() + (int) (Math.sin(angle) * distance);
            Location spot = surfaceAt(anchor.getWorld(), x, z);
            if (spot == null || tooClose(spot, spacing)) {
                continue;
            }
            DungeonPortal portal = create(spot, PortalKind.WILD, null, defaultSize());
            if (portal != null) {
                plugin.getLogger().info("Doğada geçit doğdu: " + portal);
                return;
            }
        }
    }

    /**
     * A standing spot on the surface, or {@code null}.
     *
     * <p>The chunk is <b>not</b> loaded to find out. Forcing a chunk load per attempt would turn
     * a background spawn roll into world generation on the main thread; an unloaded candidate is
     * simply passed over, and the next roll picks somewhere else.
     */
    private @Nullable Location surfaceAt(World world, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        Block ground = world.getHighestBlockAt(x, z);
        if (!ground.getType().isSolid() || ground.isLiquid()) {
            return null;
        }
        Block target = ground.getRelative(0, 1, 0);
        if (!target.getType().isAir() || !target.getRelative(0, 1, 0).getType().isAir()) {
            return null;
        }
        return target.getLocation();
    }

    private boolean tooClose(Location spot, int spacing) {
        for (DungeonPortal portal : all()) {
            Location other = portal.block();
            if (other.getWorld().equals(spot.getWorld())
                    && other.distanceSquared(spot) < (double) spacing * spacing) {
                return true;
            }
        }
        return false;
    }

    private int countWild() {
        return (int) portals.values().stream().filter(p -> p.kind() == PortalKind.WILD).count();
    }

    // ------------------------------------------------------------------ orphans

    private void tag(Entity entity, int portalId) {
        entity.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, TAG_KEY), PersistentDataType.INTEGER, portalId);
        entity.setPersistent(true);
    }

    /** Whether this entity carries our tag — true for a live portal's pieces and for debris. */
    public boolean isTagged(Entity entity) {
        return entity.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey(plugin, TAG_KEY), PersistentDataType.INTEGER);
    }

    /**
     * Removes tagged entities that no live portal claims.
     *
     * <p>Called at enable and on every chunk load. Portals do not survive a restart, so after one
     * every tagged entity in the world is debris by definition; the chunk-load hook is what
     * reaches the ones that were not loaded at the time.
     */
    public int sweepOrphans(Collection<Entity> entities) {
        int removed = 0;
        for (Entity entity : entities) {
            if (!isTagged(entity) || claimedBy(entity.getUniqueId()) != null) {
                continue;
            }
            entity.remove();
            removed++;
        }
        return removed;
    }

    private void sweepOrphans() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            removed += sweepOrphans(world.getEntities());
        }
        if (removed > 0) {
            plugin.getLogger().info("Sahipsiz geçit parçası temizlendi: " + removed);
        }
    }

    // ------------------------------------------------------------------ lookup

    public List<DungeonPortal> all() {
        return new ArrayList<>(portals.values());
    }

    public int count() {
        return portals.size();
    }

    public @Nullable DungeonPortal get(int id) {
        return portals.get(id);
    }

    /** The portal whose block is at this position. */
    public @Nullable DungeonPortal at(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        for (DungeonPortal portal : portals.values()) {
            Location block = portal.block();
            if (block.getWorld().equals(location.getWorld())
                    && block.getBlockX() == location.getBlockX()
                    && block.getBlockY() == location.getBlockY()
                    && block.getBlockZ() == location.getBlockZ()) {
                return portal;
            }
        }
        return null;
    }

    /** The portal that owns this entity. */
    public @Nullable DungeonPortal claimedBy(UUID entity) {
        for (DungeonPortal portal : portals.values()) {
            if (portal.owns(entity)) {
                return portal;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ config

    public Material blockMaterial() {
        Material material = Material.matchMaterial(
                plugin.getConfig().getString("portal.block", "AMETHYST_BLOCK"));
        return material == null || !material.isBlock() ? Material.AMETHYST_BLOCK : material;
    }

    public Material itemMaterial() {
        Material material = Material.matchMaterial(
                plugin.getConfig().getString("portal.item", "AMETHYST_SHARD"));
        return material == null ? Material.AMETHYST_SHARD : material;
    }

    private Component label() {
        String raw = plugin.getConfig().getString("portal.label",
                "<light_purple><bold>Dungeon</bold></light_purple>");
        try {
            return MiniMessage.miniMessage().deserialize(raw);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("portal.label okunamadı, düz metne düşüldü: " + e.getMessage());
            return Component.text("Dungeon", NamedTextColor.LIGHT_PURPLE);
        }
    }

    public DungeonSize defaultSize() {
        DungeonSize size = DungeonSize.parse(plugin.getConfig().getString("portal.size", "medium"));
        return size == null ? DungeonSize.MEDIUM : size;
    }
}
