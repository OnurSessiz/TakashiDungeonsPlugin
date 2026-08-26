package com.takashi.dungeons;

import com.takashi.dungeons.command.DungeonsCommand;
import com.takashi.dungeons.generation.RoomTemplateStore;
import com.takashi.dungeons.schematic.DoorPlugger;
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
 * Plugin giriş noktası.
 *
 * <p>Enable sırası bilinçli olarak şu şekilde: (1) config, (2) entegrasyon tespiti,
 * (3) dungeon dünyası, (4) schematic servisi, (5) komutlar. Her katman kendinden
 * öncekinin sonucuna bakıyor — schematic servisi WorldEdit yoksa hiç kurulmuyor,
 * plugin yine de enable oluyor (anahedef.md: hard depend YASAK).
 */
public final class TakashiDungeonsPlugin extends JavaPlugin {

    /** Softdepend olarak tanımlı, varlığı zorunlu OLMAYAN plugin'ler. */
    private static final List<String> SOFT_INTEGRATIONS =
            List.of("WorldEdit", "FastAsyncWorldEdit", "MythicMobs", "Vault");

    private final Map<String, Boolean> integrations = new LinkedHashMap<>();

    private DungeonWorldManager worldManager;
    private GridSlotManager slotManager;
    private SchematicService schematicService;
    private RoomTemplateStore templateStore;
    private DoorPlugger doorPlugger;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        detectIntegrations();

        getLogger().info("TakashiDungeons v" + getPluginMeta().getVersion() + " etkinleştirildi.");
        // İmza jar'la birlikte seyahat eder; README silinir, bu satır kalır.
        getLogger().info("  Onur Sessiz — github.com/OnurSessiz/TakashiDungeonsPlugin (GPLv3)");
        getLogger().info("Entegrasyonlar:");
        integrations.forEach((name, present) ->
                getLogger().info("  - " + name + ": " + (present ? "bulundu" : "yok")));

        setupWorld();
        setupSchematics();
        registerCommands();
    }

    @Override
    public void onDisable() {
        getLogger().info("TakashiDungeons devre dışı bırakıldı.");
    }

    /**
     * Dünya level.dat'tan yüklenirse Bukkit generator'ı buradan ister.
     * {@link DungeonWorldManager} zaten WorldCreator'a generator veriyor ama sunucu
     * yeniden başlarken dünyayı bizden önce yükleyebilir — o yolda da void kalmalı.
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

        slotManager = new GridSlotManager(slotSize, columns, baseY);
        worldManager = new DungeonWorldManager(this, worldName);
        if (!worldManager.load()) {
            getLogger().severe("Dungeon dünyası yüklenemedi — generation komutları çalışmayacak.");
        }
    }

    private void setupSchematics() {
        boolean worldEdit = hasIntegration("WorldEdit") || hasIntegration("FastAsyncWorldEdit");
        if (!worldEdit) {
            getLogger().warning("WorldEdit/FAWE bulunamadı — schematic paste devre dışı. "
                    + "Dungeon üretimi için WorldEdit ya da FastAsyncWorldEdit kurun.");
            return;
        }

        boolean forceSync = getConfig().getBoolean("schematics.force-sync-paste", false);
        boolean async = hasIntegration("FastAsyncWorldEdit") && !forceSync;

        schematicService = new SchematicService(this, new File(getDataFolder(), "schematics"), async);
        // Şablon deposu schematic servisinin üstünde duruyor: geometri ondan, metadata
        // yanındaki .yml'den geliyor. Servis kurulmadıysa depo da kurulmaz.
        templateStore = new RoomTemplateStore(this, schematicService);
        // Tıpa da blok yazıyor; paste ile aynı thread kuralına tabi (async sadece FAWE ile).
        doorPlugger = new DoorPlugger(this, async);
        getLogger().info("Schematic servisi hazır — paste modu: "
                + (async ? "async (FAWE)" : "senkron (main thread)"));
    }

    private void registerCommands() {
        PluginCommand command = getCommand("tdungeons");
        if (command == null) {
            // plugin.yml ile kod arasındaki uyumsuzluğu sessizce geçme
            getLogger().severe("'tdungeons' komutu plugin.yml'de tanımlı değil — komut kaydedilemedi.");
            return;
        }
        DungeonsCommand executor = new DungeonsCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void detectIntegrations() {
        integrations.clear();
        for (String name : SOFT_INTEGRATIONS) {
            integrations.put(name, getServer().getPluginManager().isPluginEnabled(name));
        }
    }

    /** Enable sırasında tespit edilen opsiyonel entegrasyonlar (plugin adı → kurulu mu). */
    public Map<String, Boolean> getIntegrations() {
        return Collections.unmodifiableMap(integrations);
    }

    /** Belirtilen opsiyonel entegrasyon enable sırasında bulunduysa {@code true}. */
    public boolean hasIntegration(String pluginName) {
        return integrations.getOrDefault(pluginName, false);
    }

    public DungeonWorldManager getWorldManager() {
        return worldManager;
    }

    public GridSlotManager getSlotManager() {
        return slotManager;
    }

    /** WorldEdit/FAWE yoksa {@code null} — çağıran kontrol etmek zorunda. */
    public @Nullable SchematicService getSchematicService() {
        return schematicService;
    }

    /** Oda şablonu deposu. Schematic servisi kurulmadıysa {@code null}. */
    public @Nullable RoomTemplateStore getTemplateStore() {
        return templateStore;
    }

    /** Boş kapıları kapatan tıpa servisi. Schematic servisi kurulmadıysa {@code null}. */
    public @Nullable DoorPlugger getDoorPlugger() {
        return doorPlugger;
    }
}
