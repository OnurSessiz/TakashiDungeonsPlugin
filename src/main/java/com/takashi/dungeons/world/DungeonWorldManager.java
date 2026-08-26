package com.takashi.dungeons.world;

import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.plugin.Plugin;

/**
 * Creation of, and access to, the void world the dungeon instances live in.
 *
 * <p>How it works:
 * <ol>
 *   <li>{@code onEnable} calls {@link #load()}</li>
 *   <li>If the world does not exist it is created with {@link VoidChunkGenerator}; if it does,
 *       it is loaded with the same generator</li>
 *   <li>Gamerules are pinned to values suited to a dungeon, for the reasons below</li>
 * </ol>
 *
 * <p><b>Why these gamerules:</b> every mob and item in a dungeon comes from our own systems.
 * Leaving natural spawning, weather, the day cycle, fire spread and random ticking enabled
 * would both undermine that control and burn TPS for nothing while N instances are open.
 * {@code SPAWN_CHUNK_RADIUS = 0}: there is no point keeping the area around spawn in memory,
 * since dungeon chunks load when a player enters anyway.
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
     * Creates or loads the dungeon world.
     *
     * @return {@code true} once the world is ready
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

        w.setTime(6000L);       // fixed noon: room lighting is left to the schematic's own light sources
        w.setStorm(false);
        w.setThundering(false);
        w.setDifficulty(org.bukkit.Difficulty.NORMAL);
    }

    /** The dungeon world — {@code null} unless {@link #load()} succeeded. */
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
