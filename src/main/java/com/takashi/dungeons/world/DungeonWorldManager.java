package com.takashi.dungeons.world;

import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.List;

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

    /**
     * The subfolders holding generated terrain. {@code level.dat} is deliberately not in the
     * list: it carries the world's own settings and spawn point, which are worth keeping across
     * a reset.
     */
    private static final List<String> CHUNK_FOLDERS = List.of("region", "entities", "poi");

    private final Plugin plugin;
    private final String worldName;
    private final boolean resetOnStart;

    private World world;
    private int resetFiles;

    public DungeonWorldManager(Plugin plugin, String worldName, boolean resetOnStart) {
        this.plugin = plugin;
        this.worldName = worldName;
        this.resetOnStart = resetOnStart;
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

        if (resetOnStart) {
            reset();
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

    /**
     * Deletes the world's generated chunks before it is loaded.
     *
     * <p><b>Why this is safe, and why it is right:</b> the dungeon world holds nothing but
     * instances, and no instance survives a shutdown — they live in memory only. So every block
     * left on disk is, by definition, the debris of a dungeon that no longer exists. Without this
     * the slot counter restarts at 0 while the old blocks stay put, and the next dungeon is
     * pasted on top of the previous one: the trap already documented in {@code CLAUDE.md} for
     * manual testing, which would hit real servers exactly the same way.
     *
     * <p>It is nevertheless config-gated ({@code dungeon-world.reset-on-start}), because an
     * operator who decides to build something permanent in that world should be able to keep it —
     * having been told that instances then accumulate.
     *
     * <p>Runs <b>before</b> the world is created or loaded. Deleting region files under a loaded
     * world would corrupt what the server has in memory.
     */
    private void reset() {
        File folder = new File(plugin.getServer().getWorldContainer(), worldName);
        if (!folder.isDirectory()) {
            return;   // first start; nothing to clean
        }
        resetFiles = 0;
        for (String name : CHUNK_FOLDERS) {
            resetFiles += deleteRecursively(new File(folder, name));
        }
        if (resetFiles > 0) {
            plugin.getLogger().info("Dungeon dünyası sıfırlandı: " + resetFiles
                    + " chunk dosyası silindi (dungeon-world.reset-on-start).");
        }
    }

    /** @return how many files were removed */
    private int deleteRecursively(File file) {
        if (!file.exists()) {
            return 0;
        }
        int removed = 0;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    removed += deleteRecursively(child);
                }
            }
            file.delete();
            return removed;
        }
        if (file.delete()) {
            return 1;
        }
        plugin.getLogger().warning("Silinemedi: " + file.getPath());
        return 0;
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

    /** Whether the world's chunks are wiped at startup. */
    public boolean isResetOnStart() {
        return resetOnStart;
    }

    /** How many chunk files the last startup reset removed. */
    public int getResetFiles() {
        return resetFiles;
    }
}
