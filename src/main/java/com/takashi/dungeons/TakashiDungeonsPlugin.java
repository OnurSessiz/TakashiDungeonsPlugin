package com.takashi.dungeons;

import com.takashi.dungeons.command.DungeonsCommand;
import com.takashi.dungeons.command.HudCommand;
import com.takashi.dungeons.hud.HudService;
import com.takashi.dungeons.generation.RoomTemplateStore;
import com.takashi.dungeons.instance.InstanceListener;
import com.takashi.dungeons.instance.InstanceManager;
import com.takashi.dungeons.mob.MobPopulator;
import com.takashi.dungeons.mob.MobRegistry;
import com.takashi.dungeons.mob.MobService;
import com.takashi.dungeons.mob.MythicMobsProvider;
import com.takashi.dungeons.mob.VanillaMobProvider;
import com.takashi.dungeons.portal.PortalListener;
import com.takashi.dungeons.portal.PortalManager;
import com.takashi.dungeons.schematic.BundledRooms;
import com.takashi.dungeons.schematic.DoorPlugger;
import com.takashi.dungeons.schematic.RegionCleaner;
import com.takashi.dungeons.schematic.SchematicService;
import com.takashi.dungeons.world.DungeonWorldManager;
import com.takashi.dungeons.world.GridSlotManager;
import com.takashi.dungeons.world.VoidChunkGenerator;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.ChunkGenerator;
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
    private PortalManager portalManager;
    private MobRegistry mobRegistry;
    private MobService mobService;
    private MobPopulator mobPopulator;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        detectIntegrations();

        getLogger().info("TakashiDungeons v" + getPluginMeta().getVersion() + " etkinleştirildi.");
        // The signature travels with the jar; a README can be deleted, this line stays.
        getLogger().info("  Onur Sessiz — github.com/OnurSessiz/TakashiDungeonsPlugin (GPLv3)");
        getLogger().info("Entegrasyonlar:");
        integrations.forEach((name, present) ->
                getLogger().info("  - " + name + ": " + (present ? "bulundu" : "yok")));

        setupWorld();
        setupSchematics();
        setupMobs();
        setupInstances();
        setupPortals();
        setupHud();
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
        getLogger().info("TakashiDungeons devre dışı bırakıldı.");
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
            getLogger().severe("Dungeon dünyası yüklenemedi — generation komutları çalışmayacak.");
        }
    }

    private void setupSchematics() {
        File schematicDir = new File(getDataFolder(), "schematics");
        extractBundledRooms(schematicDir);

        boolean worldEdit = hasIntegration("WorldEdit") || hasIntegration("FastAsyncWorldEdit");
        if (!worldEdit) {
            getLogger().warning("WorldEdit/FAWE bulunamadı — schematic paste devre dışı. "
                    + "Dungeon üretimi için WorldEdit ya da FastAsyncWorldEdit kurun.");
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
        getLogger().info("Schematic servisi hazır — paste modu: "
                + (async ? "async (FAWE)" : "senkron (main thread)"));
    }

    /**
     * The mob catalogue and the service that spawns from it.
     *
     * <p>Built before the instance layer and unconditionally: {@link VanillaMobProvider} has no
     * external dependency, so a server with no mob plugin at all still gets a full set. The
     * MythicMobs provider is registered even when MythicMobs is absent — it reports its own
     * absence, which is what lets {@code mobs.yml} say "MythicMobs kurulu değil" instead of
     * "bilinmeyen sağlayıcı".
     */
    private void setupMobs() {
        mobRegistry = new MobRegistry(this);
        mobRegistry.register(new VanillaMobProvider());
        mobRegistry.register(new MythicMobsProvider(this));
        mobRegistry.load();
        mobService = new MobService(this, mobRegistry);
        mobPopulator = new MobPopulator(this);
    }

    /**
     * The instance layer. Always built, even without WorldEdit: {@code create} then refuses with
     * a clear reason, and the join safety net — which has no WorldEdit dependency — keeps working
     * on a server that lost FAWE between restarts.
     */
    private void setupInstances() {
        instanceManager = new InstanceManager(this);
        getServer().getPluginManager().registerEvents(new InstanceListener(this), this);
        instanceManager.startClock();
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
            getLogger().info("Gömülü odaların çıkarılması config'de kapalı "
                    + "(schematics.extract-bundled).");
            return;
        }
        BundledRooms.Result result = bundledRooms.extract(schematicDir, false);
        if (result.written() > 0) {
            getLogger().info("Gömülü oda dosyası çıkarıldı: " + result.written()
                    + " (zaten mevcut: " + result.skipped() + ")");
        }
        if (result.failed() > 0) {
            getLogger().warning(result.failed() + " gömülü oda dosyası çıkarılamadı.");
        }
    }

    private void setupHud() {
        hudService = new HudService(this);
        hudService.enable();
    }

    private void registerCommands() {
        PluginCommand command = getCommand("tdungeons");
        if (command == null) {
            // Never pass over a mismatch between plugin.yml and the code in silence
            getLogger().severe("'tdungeons' komutu plugin.yml'de tanımlı değil — komut kaydedilemedi.");
            return;
        }
        DungeonsCommand executor = new DungeonsCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        PluginCommand hudCommand = getCommand("hud");
        if (hudCommand == null) {
            getLogger().severe("'hud' komutu plugin.yml'de tanımlı değil — komut kaydedilemedi.");
            return;
        }
        HudCommand hudExecutor = new HudCommand(this);
        hudCommand.setExecutor(hudExecutor);
        hudCommand.setTabCompleter(hudExecutor);
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
}
