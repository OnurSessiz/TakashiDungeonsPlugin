package com.takashi.dungeons;

import com.takashi.dungeons.api.TakashiDungeonsAPI;
import com.takashi.dungeons.command.DungeonsCommand;
import com.takashi.dungeons.command.HudCommand;
import com.takashi.dungeons.command.PartyCommand;
import com.takashi.dungeons.hud.HudService;
import com.takashi.dungeons.generation.RoomTemplateStore;
import com.takashi.dungeons.instance.InstanceListener;
import com.takashi.dungeons.instance.InstanceManager;
import com.takashi.dungeons.loot.ChestListener;
import com.takashi.dungeons.loot.DungeonChestTag;
import com.takashi.dungeons.loot.LootPopulator;
import com.takashi.dungeons.loot.LootRegistry;
import com.takashi.dungeons.loot.LootService;
import com.takashi.dungeons.loot.MobDropService;
import com.takashi.dungeons.mob.DungeonMobListener;
import com.takashi.dungeons.mob.MobPopulator;
import com.takashi.dungeons.mob.MobRegistry;
import com.takashi.dungeons.mob.MobService;
import com.takashi.dungeons.mob.MythicMobsProvider;
import com.takashi.dungeons.mob.VanillaMobProvider;
import com.takashi.dungeons.party.PartyListener;
import com.takashi.dungeons.party.PartyManager;
import com.takashi.dungeons.player.PlayerDataListener;
import com.takashi.dungeons.player.PlayerDataService;
import com.takashi.dungeons.portal.PortalListener;
import com.takashi.dungeons.portal.PortalManager;
import com.takashi.dungeons.schematic.BundledRooms;
import com.takashi.dungeons.shop.ShopListener;
import com.takashi.dungeons.shop.ShopManager;
import com.takashi.dungeons.shop.ShopRegistry;
import com.takashi.dungeons.schematic.DoorPlugger;
import com.takashi.dungeons.schematic.RegionCleaner;
import com.takashi.dungeons.schematic.SchematicService;
import com.takashi.dungeons.storage.StorageService;
import com.takashi.dungeons.text.Messages;
import com.takashi.dungeons.world.DungeonWorldManager;
import com.takashi.dungeons.world.GridSlotManager;
import com.takashi.dungeons.world.VoidChunkGenerator;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plugin entry point.
 *
 * <p>The enable order is deliberate: (1) config, (2) integration detection, (3) dungeon world,
 * (4) schematic service, (5) commands. Each layer looks at the result of the one before it —
 * the schematic service is never built when WorldEdit is absent, and the plugin still enables.
 * A hard dependency on any of these is forbidden.
 */
public final class TakashiDungeonsPlugin extends JavaPlugin {

    /** Plugins declared as softdepend — none of them is required to be present. */
    private static final List<String> SOFT_INTEGRATIONS =
            List.of("WorldEdit", "FastAsyncWorldEdit", "MythicMobs", "Vault");

    private final Map<String, Boolean> integrations = new LinkedHashMap<>();

    private DungeonWorldManager worldManager;
    private GridSlotManager slotManager;
    private SchematicService schematicService;
    private RoomTemplateStore templateStore;
    private DoorPlugger doorPlugger;
    private RegionCleaner regionCleaner;
    private BundledRooms bundledRooms;
    private HudService hudService;
    private InstanceManager instanceManager;
    private PartyManager partyManager;
    private PortalManager portalManager;
    private MobRegistry mobRegistry;
    private MobService mobService;
    private MobPopulator mobPopulator;
    private LootRegistry lootRegistry;
    private LootService lootService;
    private LootPopulator lootPopulator;
    private DungeonChestTag dungeonChestTag;
    private ShopRegistry shopRegistry;
    private ShopManager shopManager;
    private MobDropService mobDropService;
    private StorageService storage;
    private PlayerDataService playerData;
    private ApiService apiService;
    private ApiEvents apiEvents;
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Words first: every layer below this line has something to say to a player, and a
        // message asked for before the language is loaded would answer with its own key.
        messages = new Messages(this);
        messages.load();
        detectIntegrations();

        getLogger().info("TakashiDungeons v" + getPluginMeta().getVersion() + " enabled.");
        // The signature travels with the jar; a README can be deleted, this line stays.
        getLogger().info("  Onur Sessiz — github.com/OnurSessiz/TakashiDungeonsPlugin (GPLv3)");
        getLogger().info("Integrations:");
        integrations.forEach((name, present) ->
                getLogger().info("  - " + name + ": " + (present ? "found" : "absent")));

        setupStorage();
        setupWorld();
        setupSchematics();
        setupMobs();
        setupLoot();
        setupInstances();
        setupPlayerData();
        setupShop();
        setupParty();
        setupPortals();
        setupHud();
        setupApi();
        registerCommands();
    }

    @Override
    public void onDisable() {
        // The sidebar is a client-side scoreboard: if it is not taken down here it survives a
        // /reload and sticks to the player with a plugin that no longer exists behind it.
        if (hudService != null) {
            hudService.disable();
        }
        // Portals first: they take their blocks and display entities back out of the world. Left
        // standing they would be a block a player can click with nothing behind it.
        if (portalManager != null) {
            portalManager.disable();
        }
        // Parties are session state and the session is ending — this cancels the invite sweeper
        // and drops the maps, so a /reload cannot leave a party pointing at players who are about
        // to be handed a fresh PartyManager.
        if (partyManager != null) {
            partyManager.disable();
        }
        if (instanceManager != null) {
            instanceManager.stopClock();
            // The boss bar is client-side, exactly like the sidebar: left up, it survives a
            // /reload and counts down against a plugin that is no longer there.
            instanceManager.hideAllBars();
        }
        // Blocks are NOT wiped here. A shutdown is not a teardown: the world is reset on the next
        // start, so wiping now would only make the server take longer to stop. What does matter is
        // that nobody's logout position ends up inside an instance — that position outlives the
        // instance and would drop them into the void on their next join.
        evacuateDungeonWorld();
        // Player data LAST, and in this order: the service settles everyone's time inside and
        // writes what the session accumulated, and only then is the connection allowed to close.
        // Reversed, the final flush would be submitted to a storage layer that had already shut
        // down — which is the one moment where losing a write cannot be retried.
        if (playerData != null) {
            playerData.disable();
        }
        if (storage != null) {
            storage.disable();
        }
        getLogger().info("TakashiDungeons disabled.");
    }

    /** Everyone standing in the dungeon world goes back out before the plugin stops. */
    private void evacuateDungeonWorld() {
        if (worldManager == null || worldManager.getWorld() == null || instanceManager == null) {
            return;
        }
        var exit = instanceManager.fallbackExit();
        worldManager.getWorld().getPlayers().forEach(player -> player.teleport(exit));
    }

    /**
     * When the world is loaded from level.dat, Bukkit asks for the generator here.
     * {@link DungeonWorldManager} already hands one to its WorldCreator, but on restart the
     * server may load the world before we do — it has to stay void on that path too.
     */
    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        return new VoidChunkGenerator();
    }

    private void setupWorld() {
        FileConfiguration config = getConfig();
        String worldName = config.getString("dungeon-world.name", "takashi_dungeons");
        int slotSize = config.getInt("dungeon-world.slot-size", 512);
        int columns = config.getInt("dungeon-world.columns", 32);
        int baseY = config.getInt("dungeon-world.base-y", 64);

        boolean resetOnStart = config.getBoolean("dungeon-world.reset-on-start", true);

        slotManager = new GridSlotManager(slotSize, columns, baseY);
        worldManager = new DungeonWorldManager(this, worldName, resetOnStart);
        if (!worldManager.load()) {
            getLogger().severe("The dungeon world could not be loaded - generation commands will not work.");
        }
    }

    private void setupSchematics() {
        File schematicDir = new File(getDataFolder(), "schematics");
        extractBundledRooms(schematicDir);

        boolean worldEdit = hasIntegration("WorldEdit") || hasIntegration("FastAsyncWorldEdit");
        if (!worldEdit) {
            getLogger().warning("WorldEdit/FAWE not found - schematic pasting is disabled. "
                    + "Install WorldEdit or FastAsyncWorldEdit to generate dungeons.");
            return;
        }

        boolean forceSync = getConfig().getBoolean("schematics.force-sync-paste", false);
        boolean async = hasIntegration("FastAsyncWorldEdit") && !forceSync;

        schematicService = new SchematicService(this, schematicDir, async);
        // The template store sits on top of the schematic service: geometry comes from it,
        // metadata from the .yml beside each file. No service, no store.
        templateStore = new RoomTemplateStore(this, schematicService);
        // Plugging writes blocks too, so it obeys the same threading rule as paste (async
        // only with FAWE).
        doorPlugger = new DoorPlugger(this, async);
        // Same rule again for the cleaner: a sync paste racing an async wipe of the same slot is
        // exactly the corruption the threading decision exists to prevent.
        regionCleaner = new RegionCleaner(this, async);
        getLogger().info("Schematic service ready - paste mode: "
                + (async ? "async (FAWE)" : "synchronous (main thread)"));
    }

    /**
     * The mob catalogue and the service that spawns from it.
     *
     * <p>Built before the instance layer and unconditionally: {@link VanillaMobProvider} has no
     * external dependency, so a server with no mob plugin at all still gets a full set. The
     * MythicMobs provider is registered even when MythicMobs is absent — it reports its own
     * absence, which is what lets {@code mobs.yml} say "MythicMobs is not installed" instead of
     * "unknown provider".
     */
    private void setupMobs() {
        mobRegistry = new MobRegistry(this);
        mobRegistry.register(new VanillaMobProvider());
        mobRegistry.register(new MythicMobsProvider(this));
        mobRegistry.load();
        mobService = new MobService(this, mobRegistry);
        mobPopulator = new MobPopulator(this);
        getServer().getPluginManager().registerEvents(new DungeonMobListener(this), this);
    }

    /**
     * The loot catalogue and the service that builds items from it.
     *
     * <p>Built after the mob layer because loot reads the mob system's {@link
     * com.takashi.dungeons.mob.Difficulty} scale, and before the instance layer because phase 4B
     * fills chests in the same pass that populates a new dungeon's rooms. No external dependency:
     * an entirely vanilla {@code loot.yml} ships in the jar.
     */
    private void setupLoot() {
        lootRegistry = new LootRegistry(this);
        lootRegistry.load();
        lootService = new LootService(lootRegistry);
        // The tag is built once and holds this run's session token, so it must outlive any single
        // reload of the catalogue -- a chest filled before a /tdungeons reload is still this run's.
        dungeonChestTag = new DungeonChestTag(this);
        lootPopulator = new LootPopulator(this);
        getServer().getPluginManager().registerEvents(new ChestListener(this), this);
        // The death-event half registers here; the boss half subscribes to the kill signal, which
        // only exists once the mob layer is up -- setupMobs() has already run.
        mobDropService = new MobDropService(this);
        getServer().getPluginManager().registerEvents(mobDropService, this);
        mobService.onKill(mobDropService::onKill);
    }

    /**
     * The database.
     *
     * <p>Built first of all the systems and on purpose: it is the one layer with nothing above it
     * to wait for, and everything that might want to read a player's row has to find it already
     * open. A failure here is <b>not</b> fatal — {@link StorageService#enable()} says why in the
     * log and the plugin runs exactly as it did before phase 7, with settings and counters living
     * only as long as the session.
     */
    private void setupStorage() {
        storage = new StorageService(this);
        storage.enable();
    }

    /**
     * Player profiles, settings and counters.
     *
     * <p>Built <b>unconditionally</b>, whether or not the database opened. That is what lets the
     * HUD read a setting without asking whether persistence exists: with storage off the profile is
     * a plain in-memory object and the session behaves as it always did. A service that only
     * existed when SQL worked would put a second, untested code path in every caller.
     *
     * <p>Placed after the instance and mob layers because it subscribes to both of their signals —
     * entering, leaving, clearing and killing are the facts it counts.
     */
    private void setupPlayerData() {
        playerData = new PlayerDataService(this, storage);
        playerData.enable();
        getServer().getPluginManager().registerEvents(new PlayerDataListener(this), this);
        // Wired here rather than inside either class, exactly like the kill signal and the close
        // signal already are: the instance layer publishes facts and does not know who counts them.
        instanceManager.onEnter(playerData::onEnter);
        instanceManager.onExit(playerData::onExit);
        instanceManager.onCleared(playerData::onCleared);
        instanceManager.onClosed(instance -> playerData.onInstanceClosed(instance.id()));
        mobService.onKill(playerData::onKill);
    }

    /**
     * The instance layer. Always built, even without WorldEdit: {@code create} then refuses with
     * a clear reason, and the join safety net — which has no WorldEdit dependency — keeps working
     * on a server that lost FAWE between restarts.
     */
    private void setupInstances() {
        instanceManager = new InstanceManager(this);
        getServer().getPluginManager().registerEvents(new InstanceListener(this), this);
        // The mob layer publishes kills; the instance layer decides that a dead boss means a
        // cleared dungeon. Wired here rather than inside either one, so neither has to know the
        // other exists — phase 4's loot and phase 8's events subscribe to the same signal.
        mobService.onKill(instanceManager::onMobKilled);
        instanceManager.startClock();
    }

    /**
     * The supply merchant's catalogue.
     *
     * <p>Built after the loot layer because a stock entry may reference a {@code loot.yml} item by
     * id, and that reference is resolved at load — an unresolvable one has to be reported as a
     * broken entry rather than silently sold as something else.
     */
    private void setupShop() {
        shopRegistry = new ShopRegistry(this);
        shopRegistry.load();
        shopManager = new ShopManager(this);
        getServer().getPluginManager().registerEvents(new ShopListener(this), this);
        // The merchant belongs to its instance and dies with it. Wired here, like every other
        // cross-layer signal in this plugin, so neither class has to know the other exists.
        instanceManager.onClosed(instance -> {
            shopManager.forget(instance.id());
            // A window left open over a dungeon that is gone would keep offering goods. The click
            // handler refuses them anyway — that is the guarantee — but a window that answers
            // every click with "this dungeon is gone" is worse than one that simply closes.
            shopManager.closeMenus(instance.id());
        });
    }

    /**
     * The party layer. No external dependency and no dependency on WorldEdit either: a party is
     * people, and people can be grouped on a server that cannot generate a single room.
     *
     * <p>Built after the instance layer and before the portals, because phase 5B's shared entry
     * runs from a gateway click into {@code InstanceManager.enter} — the two ends have to exist
     * before the thing that joins them.
     */
    private void setupParty() {
        partyManager = new PartyManager(this);
        partyManager.enable();
        getServer().getPluginManager().registerEvents(new PartyListener(this), this);
        // A dungeon that has closed must stop being "the party's dungeon" — otherwise /party join
        // points at a number that no longer resolves and the party is told to come to nowhere.
        // Wired here rather than inside either class, the way the kill signal already is.
        instanceManager.onClosed(instance -> partyManager.unbindInstance(instance.id()));
    }

    /**
     * The entrance objects. Built after the instance layer, because a portal's whole job is to
     * open an instance and it registers a close listener on it.
     */
    private void setupPortals() {
        portalManager = new PortalManager(this);
        getServer().getPluginManager().registerEvents(new PortalListener(this), this);
        portalManager.enable();
    }

    /**
     * Unpacks the rooms shipped in the jar. Deliberately runs BEFORE the WorldEdit check: the
     * rooms have to be on disk even on a server that installs FAWE later, otherwise the first
     * generation attempt after installing it would still find an empty folder.
     */
    private void extractBundledRooms(File schematicDir) {
        bundledRooms = new BundledRooms(this, getFile());
        if (!getConfig().getBoolean("schematics.extract-bundled", true)) {
            getLogger().info("Extracting the bundled rooms is switched off in the config "
                    + "(schematics.extract-bundled).");
            return;
        }
        BundledRooms.Result result = bundledRooms.extract(schematicDir, false);
        if (result.written() > 0) {
            getLogger().info("Bundled room files extracted: " + result.written()
                    + " (already present: " + result.skipped() + ")");
        }
        if (result.failed() > 0) {
            getLogger().warning(result.failed() + " bundled room files could not be extracted.");
        }
    }

    private void setupHud() {
        hudService = new HudService(this);
        hudService.enable();
    }

    /**
     * The public API — registered <b>last</b>, and that is the whole of its design.
     *
     * <p>Every internal listener has already subscribed by this point, so an addon's listener runs
     * against a world the plugin has finished reacting to: the statistics are counted, the merchant
     * is gone, the slot is released. An API that fired first would be showing addons a half-finished
     * plugin and inviting them to depend on the order the halves arrive in.
     *
     * <p>Published through Bukkit's {@code ServicesManager} rather than as a static field on this
     * class. An addon that casts {@code getPlugin("TakashiDungeons")} needs our class to be loadable
     * while ITS class is verified, which quietly turns a softdepend into a hard one on a server
     * where we are absent. A service lookup has no such edge.
     */
    private void setupApi() {
        apiService = new ApiService(this);
        getServer().getServicesManager().register(TakashiDungeonsAPI.class, apiService, this,
                ServicePriority.Normal);
        apiEvents = new ApiEvents(this);
        apiEvents.register();
        getLogger().info("API " + TakashiDungeonsAPI.API_VERSION + " registered - addons can use "
                + "TakashiDungeons.api(); see docs/api.md");
    }

    private void registerCommands() {
        PluginCommand command = getCommand("tdungeons");
        if (command == null) {
            // Never pass over a mismatch between plugin.yml and the code in silence
            getLogger().severe("The 'tdungeons' command is not declared in plugin.yml - it was not registered.");
            return;
        }
        DungeonsCommand executor = new DungeonsCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        PluginCommand hudCommand = getCommand("hud");
        if (hudCommand == null) {
            getLogger().severe("The 'hud' command is not declared in plugin.yml - it was not registered.");
            return;
        }
        HudCommand hudExecutor = new HudCommand(this);
        hudCommand.setExecutor(hudExecutor);
        hudCommand.setTabCompleter(hudExecutor);

        PluginCommand partyCommand = getCommand("party");
        if (partyCommand == null) {
            getLogger().severe("The 'party' command is not declared in plugin.yml - it was not registered.");
            return;
        }
        PartyCommand partyExecutor = new PartyCommand(this);
        partyCommand.setExecutor(partyExecutor);
        partyCommand.setTabCompleter(partyExecutor);
    }

    private void detectIntegrations() {
        integrations.clear();
        for (String name : SOFT_INTEGRATIONS) {
            integrations.put(name, getServer().getPluginManager().isPluginEnabled(name));
        }
    }

    /** Optional integrations detected during enable (plugin name → present). */
    public Map<String, Boolean> getIntegrations() {
        return Collections.unmodifiableMap(integrations);
    }

    /** {@code true} if the named optional integration was present during enable. */
    public boolean hasIntegration(String pluginName) {
        return integrations.getOrDefault(pluginName, false);
    }

    public DungeonWorldManager getWorldManager() {
        return worldManager;
    }

    public GridSlotManager getSlotManager() {
        return slotManager;
    }

    /** {@code null} without WorldEdit/FAWE — callers must check. */
    public @Nullable SchematicService getSchematicService() {
        return schematicService;
    }

    /** The room template store. {@code null} when the schematic service was not built. */
    public @Nullable RoomTemplateStore getTemplateStore() {
        return templateStore;
    }

    /** The rooms shipped inside the jar. Always built — it has no external dependency. */
    public BundledRooms getBundledRooms() {
        return bundledRooms;
    }

    /** The sidebar HUD service. Always built — it has no external dependency. */
    public HudService getHudService() {
        return hudService;
    }

    /** The service that plugs open doors. {@code null} when the schematic service was not built. */
    public @Nullable DoorPlugger getDoorPlugger() {
        return doorPlugger;
    }

    /** Clears a region back to air. {@code null} without WorldEdit/FAWE — callers must check. */
    public @Nullable RegionCleaner getRegionCleaner() {
        return regionCleaner;
    }

    /** The instance registry. Always built — it reports its own missing dependencies. */
    public InstanceManager getInstanceManager() {
        return instanceManager;
    }

    /** The supply merchant's catalogue. Always built — it reports its own problems. */
    public ShopRegistry getShopRegistry() {
        return shopRegistry;
    }

    /** Places merchants and knows which entity is one. Always built. */
    public ShopManager getShopManager() {
        return shopManager;
    }

    /** The party registry. Always built — grouping players needs nothing installed. */
    public PartyManager getPartyManager() {
        return partyManager;
    }

    /** The entrance objects standing in the world. Always built. */
    public PortalManager getPortalManager() {
        return portalManager;
    }

    /** The mob catalogue. Always built — the vanilla provider has no external dependency. */
    public MobRegistry getMobRegistry() {
        return mobRegistry;
    }

    /** Spawns mobs from the catalogue. Always built. */
    public MobService getMobService() {
        return mobService;
    }

    /** Fills a generated dungeon's rooms with mobs. Always built. */
    public MobPopulator getMobPopulator() {
        return mobPopulator;
    }

    /** The loot catalogue. Always built — the shipped {@code loot.yml} is entirely vanilla. */
    public LootRegistry getLootRegistry() {
        return lootRegistry;
    }

    /** Rolls tables and builds the items they name. Always built. */
    public LootService getLootService() {
        return lootService;
    }

    /** Places and fills a generated dungeon's chests. Always built. */
    public LootPopulator getLootPopulator() {
        return lootPopulator;
    }

    /** The mark a dungeon chest carries. Holds this run's session token. */
    public DungeonChestTag getDungeonChestTag() {
        return dungeonChestTag;
    }

    /** Mob drops and the boss's reward chest. Always built. */
    public MobDropService getMobDropService() {
        return mobDropService;
    }

    /**
     * The API bridge — for {@code /tdungeons api} only.
     *
     * <p>Addons do not come through here: they ask the {@code ServicesManager} for
     * {@link TakashiDungeonsAPI}, which is the object with the promise attached.
     */
    public ApiEvents getApiEvents() {
        return apiEvents;
    }

    /** The database. Always built; ask {@link StorageService#isReady()} before relying on it. */
    public StorageService getStorage() {
        return storage;
    }

    /**
     * Player profiles and counters. Always built — with the database off it keeps them in memory
     * for the session, which is what every caller is written against.
     */
    public PlayerDataService getPlayerData() {
        return playerData;
    }

    /** Every word a player reads. Built first, before anything can need one. */
    public Messages getMessages() {
        return messages;
    }
}
