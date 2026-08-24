package com.takashi.dungeons.world;

import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.plugin.Plugin;

/**
 * Dungeon instance'larının yaşadığı void dünyanın kurulumu ve erişimi.
 *
 * <p>Nasıl çalışır:
 * <ol>
 *   <li>{@code onEnable} → {@link #load()} çağrılır</li>
 *   <li>Dünya yoksa {@link VoidChunkGenerator} ile oluşturulur, varsa aynı generator'la yüklenir</li>
 *   <li>Gamerule'lar dungeon'a uygun sabitlenir (aşağıdaki nedenlerle)</li>
 * </ol>
 *
 * <p><b>Neden bu gamerule'lar:</b> Dungeon'daki her mob/eşya bizim sistemimizden gelir.
 * Doğal spawn, hava, gündüz-gece, ateş yayılımı ve random tick açık kalırsa hem içerik
 * kontrolümüz bozulur hem de N tane instance açıkken boşuna TPS harcanır.
 * {@code SPAWN_CHUNK_RADIUS = 0}: spawn çevresini bellekte tutmanın anlamı yok, dungeon
 * chunk'ları zaten oyuncu girince yükleniyor.
 */
public final class DungeonWorldManager {

    private final Plugin plugin;
    private final String worldName;

    private World world;

    public DungeonWorldManager(Plugin plugin, String worldName) {
        this.plugin = plugin;
        this.worldName = worldName;
    }

    /**
     * Dungeon dünyasını oluşturur/yükler.
     *
     * @return dünya hazırsa {@code true}
     */
    public boolean load() {
        World existing = plugin.getServer().getWorld(worldName);
        if (existing != null) {
            this.world = existing;
            applyRules(existing);
            return true;
        }

        WorldCreator creator = new WorldCreator(worldName)
                .generator(new VoidChunkGenerator())
                .type(WorldType.FLAT)
                .environment(World.Environment.NORMAL)
                .generateStructures(false);

        World created = creator.createWorld();
        if (created == null) {
            plugin.getLogger().severe("Dungeon dünyası '" + worldName + "' oluşturulamadı.");
            return false;
        }

        this.world = created;
        applyRules(created);
        plugin.getLogger().info("Dungeon dünyası hazır: " + worldName);
        return true;
    }

    private void applyRules(World w) {
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.DO_FIRE_TICK, false);
        w.setGameRule(GameRule.MOB_GRIEFING, false);
        w.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        w.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        w.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        w.setGameRule(GameRule.SPAWN_CHUNK_RADIUS, 0);
        w.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);

        w.setTime(6000L);       // sabit öğle: oda aydınlatması schematic'in ışık kaynaklarına kalsın
        w.setStorm(false);
        w.setThundering(false);
        w.setDifficulty(org.bukkit.Difficulty.NORMAL);
    }

    /** Dungeon dünyası — {@link #load()} başarılı olmadıysa {@code null}. */
    public World getWorld() {
        return world;
    }

    public String getWorldName() {
        return worldName;
    }

    public boolean isReady() {
        return world != null;
    }
}
