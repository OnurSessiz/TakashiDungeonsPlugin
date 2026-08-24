package com.takashi.dungeons.schematic;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Kod icinde basit test odaları üretip {@code .schem} olarak diske yazar.
 *
 * <p><b>Neden var:</b> FAZ 1'in generation mantığını gerçek haritalar (FAZ 10) hazır olmadan
 * test edebilmek için. Üretilen odalar geçici placeholder - haritacı ekibi gerçek odaları
 * yazdığında aynı dosya adlarıyla değiştirilirler.
 *
 * <p><b>Neden tek sayı boyut:</b> Oda kenarları 17 gibi TEK sayı. Çift sayıda kenar
 * kullanılırsa odanın gerçek merkez bloğu olmaz; rotation merkezi yarım blok kayar ve
 * 90 derece döndürülen oda grid'de 1 blok ofsetle oturur. Tek sayı bunu tamamen ortadan
 * kaldırır - bu kural gerçek odalar için de geçerli olacak.
 *
 * <p><b>Origin merkezde:</b> Clipboard origin'i odanın yatay merkezine, taban seviyesine
 * ayarlanıyor. Böylece rotation odanın kendi ekseninde döner ve paste hedefi doğrudan
 * "slot merkezi" olur. Origin köşe olsaydı her rotation için ayrı ofset hesabı gerekirdi.
 */
public final class TestRoomFactory {

    /** Odanın açık kapı yönleri. */
    public enum Door {
        NORTH, EAST, SOUTH, WEST
    }

    /** Kapı açıklığının genişliği (blok) - tek sayı ki duvarın tam ortasına otursun. */
    private static final int DOOR_WIDTH = 3;
    /** Kapı açıklığının yüksekliği (blok), tabandan yukarı. */
    private static final int DOOR_HEIGHT = 3;

    private TestRoomFactory() {
    }

    /**
     * İçi boş, duvarları kapalı, verilen yönlerde kapısı olan bir oda üretir.
     *
     * @param size  kenar uzunluğu (TEK sayı olmalı)
     * @param height toplam yükseklik (taban + iç + tavan)
     * @param doors açık bırakılacak yönler
     */
    public static Clipboard buildRoom(int size, int height, Set<Door> doors) {
        if (size % 2 == 0) {
            throw new IllegalArgumentException("Oda kenarı tek sayı olmalı (rotation merkezi için): " + size);
        }
        if (size < 5 || height < 5) {
            throw new IllegalArgumentException("Oda en az 5x5x5 olmalı: " + size + "x" + height);
        }

        BlockVector3 min = BlockVector3.ZERO;
        BlockVector3 max = BlockVector3.at(size - 1, height - 1, size - 1);
        CuboidRegion region = new CuboidRegion(min, max);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        // rotation ve paste'in referans noktası: yatay merkez, taban seviyesi
        clipboard.setOrigin(BlockVector3.at(size / 2, 0, size / 2));

        BlockState wall = state(BlockTypes.STONE_BRICKS, "stone_bricks");
        BlockState floor = state(BlockTypes.POLISHED_ANDESITE, "polished_andesite");
        BlockState light = state(BlockTypes.GLOWSTONE, "glowstone");
        BlockState air = state(BlockTypes.AIR, "air");

        try {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    for (int y = 0; y < height; y++) {
                        boolean shell = y == 0 || y == height - 1
                                || x == 0 || x == size - 1
                                || z == 0 || z == size - 1;
                        if (!shell) {
                            continue; // iç hacim boş kalır
                        }
                        BlockState block = (y == 0) ? floor : wall;
                        clipboard.setBlock(BlockVector3.at(x, y, z), block);
                    }
                }
            }

            // tavan ortasına ışık: oda kendi kendini aydınlatsın (dungeon dünyasında güneş yok)
            clipboard.setBlock(BlockVector3.at(size / 2, height - 1, size / 2), light);

            for (Door door : doors) {
                carveDoor(clipboard, size, door, air);
            }
        } catch (WorldEditException e) {
            throw new IllegalStateException("Test odası oluşturulamadı: " + e.getMessage(), e);
        }

        return clipboard;
    }

    /** Duvarın tam ortasında {@value #DOOR_WIDTH}x{@value #DOOR_HEIGHT} bir açıklık açar. */
    private static void carveDoor(BlockArrayClipboard clipboard, int size, Door door, BlockState air)
            throws WorldEditException {
        int center = size / 2;
        int half = DOOR_WIDTH / 2;

        for (int offset = -half; offset <= half; offset++) {
            for (int y = 1; y <= DOOR_HEIGHT; y++) {
                BlockVector3 pos = switch (door) {
                    case NORTH -> BlockVector3.at(center + offset, y, 0);
                    case SOUTH -> BlockVector3.at(center + offset, y, size - 1);
                    case WEST -> BlockVector3.at(0, y, center + offset);
                    case EAST -> BlockVector3.at(size - 1, y, center + offset);
                };
                clipboard.setBlock(pos, air);
            }
        }
    }

    /**
     * Sponge {@code .schem} formatının alias'ları — sürümden sürüme değişiyor, sırayla denenir.
     *
     * <p>{@code findByFile} kullanılmıyor: o metot formatı tespit için dosyayı AÇAR, henüz
     * var olmayan bir dosyada {@code NoSuchFileException} atar. Yazma yolunda alias şart.
     */
    private static final List<String> SCHEM_ALIASES = List.of("sponge.3", "schem", "sponge", "sponge.2");

    /** Clipboard'ı {@code .schem} (Sponge) olarak diske yazar; aynı adlı dosyanın üzerine yazar. */
    public static File write(Clipboard clipboard, File directory, String name) throws IOException {
        File file = new File(directory, name + ".schem");
        ClipboardFormat format = null;
        for (String alias : SCHEM_ALIASES) {
            format = ClipboardFormats.findByAlias(alias);
            if (format != null) {
                break;
            }
        }
        if (format == null) {
            throw new IOException("'.schem' formatı bu WorldEdit sürümünde bulunamadı");
        }
        try (OutputStream out = new FileOutputStream(file);
             ClipboardWriter writer = format.getWriter(out)) {
            writer.write(clipboard);
        }
        return file;
    }

    /**
     * FAZ 1 testleri için 5 standart oda üretir ve diske yazar.
     *
     * <p>Kapı kombinasyonları bilerek farklı: kesişme (4), koridor (2 karşılıklı),
     * köşe (2 komşu), çıkmaz (1) ve boss (1). FAZ 1C'deki kapı eşleştirme mantığının
     * çalıştığını göstermek için bu çeşitlilik yeterli.
     *
     * @return yazılan dosya sayısı
     */
    public static int writeStandardSet(File directory) throws IOException {
        record Spec(String name, int size, int height, Set<Door> doors) {
        }

        Spec[] specs = {
                new Spec("test_cross", 17, 9, EnumSet.allOf(Door.class)),
                new Spec("test_corridor", 17, 9, EnumSet.of(Door.NORTH, Door.SOUTH)),
                new Spec("test_corner", 17, 9, EnumSet.of(Door.NORTH, Door.EAST)),
                new Spec("test_deadend", 17, 9, EnumSet.of(Door.NORTH)),
                new Spec("test_boss", 33, 15, EnumSet.of(Door.NORTH)),
        };

        for (Spec spec : specs) {
            write(buildRoom(spec.size(), spec.height(), spec.doors()), directory, spec.name());
        }
        return specs.length;
    }

    /** {@link BlockTypes} alanları nullable; eksikse sessizce hava koymak yerine patlat. */
    private static BlockState state(BlockType type, String name) {
        if (type == null) {
            throw new IllegalStateException("Blok tipi bu surumde yok: " + name);
        }
        return type.getDefaultState();
    }
}
