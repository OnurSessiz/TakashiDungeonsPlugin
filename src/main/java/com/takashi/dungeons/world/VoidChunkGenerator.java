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
 * Tamamen boş (void) chunk üreteci — dungeon dünyasının zemini.
 *
 * <p>Hiçbir aşamayı üretmiyoruz: taş yok, mağara yok, yapı yok, dekorasyon yok, bedrock yok.
 * Sebep: dungeon'ın tek içeriği paste edilen schematic'ler. Vanilla generation açık kalırsa
 * hem CPU harcanır hem de odaların dışında ilgisiz blok/mob birikir.
 *
 * <p>Biome {@code THE_VOID} seçildi: doğal mob spawn tablosu boş olduğu için oda dışına
 * mob sızmaz, ayrıca çim/su rengi gibi görsel artıklar oluşmaz.
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    /** Bütün dünyada tek biome döndüren sağlayıcı. */
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
     * Void dünyada yükseklik haritası anlamsız; 0 döndürüyoruz ki Bukkit'in
     * "en üst blok" sorguları dünya tavanına tırmanmasın.
     */
    @Override
    public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random,
                             int x, int z, @NotNull HeightMap heightMap) {
        return worldInfo.getMinHeight();
    }

    /**
     * Oyuncular buraya asla doğrudan düşmemeli (girişler ışınlanarak yapılır) ama
     * Bukkit yine de bir spawn noktası ister — grid'in dışında, sabit bir nokta veriyoruz.
     */
    @Override
    public org.bukkit.Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new org.bukkit.Location(world, 0.5, 64, 0.5);
    }
}
