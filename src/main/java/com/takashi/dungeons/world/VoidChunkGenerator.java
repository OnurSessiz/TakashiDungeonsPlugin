package com.takashi.dungeons.world;

import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Random;

/**
 * A completely empty (void) chunk generator — the substrate of the dungeon world.
 *
 * <p>No stage is generated: no stone, no caves, no structures, no decoration, no bedrock. The
 * reason is that a dungeon's only content is the schematics pasted into it. Leaving vanilla
 * generation on would burn CPU and accumulate unrelated blocks and mobs outside the rooms.
 *
 * <p>{@code THE_VOID} was chosen as the biome: its natural mob spawn table is empty, so no mob
 * leaks in outside the rooms, and there are no visual artefacts such as grass or water tint.
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    /** A provider that returns a single biome for the entire world. */
    private static final class VoidBiomeProvider extends BiomeProvider {
        @Override
        public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
            return Biome.THE_VOID;
        }

        @Override
        public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
            return List.of(Biome.THE_VOID);
        }
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return new VoidBiomeProvider();
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    /**
     * A height map is meaningless in a void world; returning the minimum keeps Bukkit's
     * "highest block" queries from climbing to the world ceiling.
     */
    @Override
    public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random,
                             int x, int z, @NotNull HeightMap heightMap) {
        return worldInfo.getMinHeight();
    }

    /**
     * Players should never land here directly — entries happen by teleport — but Bukkit still
     * insists on a spawn point, so we hand it a fixed one outside the grid.
     */
    @Override
    public org.bukkit.Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new org.bukkit.Location(world, 0.5, 64, 0.5);
    }
}
