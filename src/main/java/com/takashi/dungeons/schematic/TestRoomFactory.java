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
import com.takashi.dungeons.generation.RoomType;
import com.takashi.dungeons.generation.Vec3i;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Kod içinde basit test odaları üretip {@code .schem} + {@code .yml} olarak diske yazar.
 *
 * <p><b>Neden var:</b> FAZ 1'in generation mantığını gerçek haritalar (FAZ 10) hazır olmadan
 * test edebilmek için. Üretilen odalar geçici placeholder — haritacı ekibi gerçek odaları
 * yazdığında aynı dosya adlarıyla değiştirilirler.
 *
 * <p><b>Metadata'yı da bu sınıf yazıyor.</b> Kapı anchor'ını hesaplayan tek yer burası
 * olduğu için {@code .yml}'yi elle yazmak gereksiz bir hata kaynağı olurdu: schematic
 * değişip metadata değişmezse kapılar tutmaz. İkisi tek fonksiyondan çıkıyor.
 *
 * <p><b>Origin merkezde:</b> Clipboard origin'i odanın yatay merkezine, taban seviyesine
 * ayarlanıyor. Böylece rotation odanın kendi ekseninde döner ve paste hedefi doğrudan
 * bir dünya noktası olur. Origin köşe olsaydı her rotation için ayrı ofset hesabı gerekirdi.
 *
 * <p><b>Çift sayı kenar artık serbest</b> ({@code generation.md} §9). Anchor tabanlı
 * yerleşimde odanın origin'e göre asimetrik olması sorun değil; {@code test_even} tam da
 * bunu doğrulamak için var.
 */
public final class TestRoomFactory {

    /** Odanın bir duvarı. */
    public enum Door {
        NORTH, EAST, SOUTH, WEST;

        /** Duvarın uzandığı eksen X mi (kuzey/güney duvarları) yoksa Z mi (doğu/batı). */
        boolean runsAlongX() {
            return this == NORTH || this == SOUTH;
        }
    }

    /**
     * Bir kapı: hangi duvarda ve duvarın ortasından kaç blok kaymış.
     *
     * <p>{@code offset} kuzey/güney duvarlarında +X, doğu/batı duvarlarında +Z yönünde.
     * Sıfırdan farklı ofsetler {@code generation.md} §6.4'ün "düz sırayı kıran" mekanizması —
     * odalar cetvelle çizilmiş gibi dizilmesin diye.
     */
    public record DoorSpec(Door wall, int offset) {
        public static DoorSpec of(Door wall) {
            return new DoorSpec(wall, 0);
        }
    }

    /** Kapı açıklığının genişliği (blok) — tek sayı ki bir merkez bloğu olsun. */
    private static final int DOOR_WIDTH = 3;
    /** Kapı açıklığının yüksekliği (blok), tabandan yukarı. */
    private static final int DOOR_HEIGHT = 3;
    /** Anchor'ın Y'si: açıklığın taban bloğu, oda tabanının bir üstü. */
    private static final int DOOR_BASE_Y = 1;

    private TestRoomFactory() {
    }

    /** Üretilmiş bir oda: clipboard + metadata'sı. Aynı yerden çıkarlar, ayrılamazlar. */
    public record BuiltRoom(Clipboard clipboard, RoomType type, int weight, List<Vec3i> doorAnchors) {
    }

    /**
     * İçi boş, duvarları kapalı, verilen kapıları açık bir oda üretir.
     *
     * @param sizeX  doğu-batı genişliği
     * @param sizeZ  kuzey-güney uzunluğu
     * @param height toplam yükseklik (taban + iç + tavan)
     * @param doors  açılacak kapılar
     */
    public static BuiltRoom buildRoom(int sizeX, int sizeZ, int height,
                                      RoomType type, int weight, List<DoorSpec> doors) {
        if (sizeX < 5 || sizeZ < 5 || height < 5) {
            throw new IllegalArgumentException(
                    "Oda en az 5x5x5 olmalı: " + sizeX + "x" + height + "x" + sizeZ);
        }

        CuboidRegion region = new CuboidRegion(
                BlockVector3.ZERO, BlockVector3.at(sizeX - 1, height - 1, sizeZ - 1));
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);

        // rotation ve paste'in referans noktası: yatay merkez, taban seviyesi.
        // Çift kenarda tam merkez blok yok; origin bir tarafa kayıyor ve oda origin'e göre
        // asimetrik oluyor. Anchor tabanlı yerleşim bunu tolere ediyor (generation.md §9).
        BlockVector3 origin = BlockVector3.at(sizeX / 2, 0, sizeZ / 2);
        clipboard.setOrigin(origin);

        BlockState wall = state(BlockTypes.STONE_BRICKS, "stone_bricks");
        BlockState floor = state(BlockTypes.POLISHED_ANDESITE, "polished_andesite");
        BlockState light = state(BlockTypes.GLOWSTONE, "glowstone");
        BlockState air = state(BlockTypes.AIR, "air");

        List<Vec3i> anchors = new ArrayList<>(doors.size());
        try {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    for (int y = 0; y < height; y++) {
                        boolean shell = y == 0 || y == height - 1
                                || x == 0 || x == sizeX - 1
                                || z == 0 || z == sizeZ - 1;
                        if (!shell) {
                            continue; // iç hacim boş kalır
                        }
                        clipboard.setBlock(BlockVector3.at(x, y, z), y == 0 ? floor : wall);
                    }
                }
            }

            // tavan ortasına ışık: oda kendi kendini aydınlatsın (dungeon dünyasında güneş yok)
            clipboard.setBlock(BlockVector3.at(sizeX / 2, height - 1, sizeZ / 2), light);

            for (DoorSpec door : doors) {
                anchors.add(carveDoor(clipboard, sizeX, sizeZ, origin, door, air));
            }
        } catch (WorldEditException e) {
            throw new IllegalStateException("Test odası oluşturulamadı: " + e.getMessage(), e);
        }

        return new BuiltRoom(clipboard, type, weight, anchors);
    }

    /**
     * Duvarda {@value #DOOR_WIDTH}x{@value #DOOR_HEIGHT} bir açıklık açar.
     *
     * @return açıklığın taban-merkez bloğunun <b>origin'e göre</b> koordinatı — kapı anchor'ı
     */
    private static Vec3i carveDoor(BlockArrayClipboard clipboard, int sizeX, int sizeZ,
                                   BlockVector3 origin, DoorSpec spec, BlockState air)
            throws WorldEditException {
        Door wall = spec.wall();
        int along = wall.runsAlongX() ? sizeX : sizeZ;
        int center = along / 2 + spec.offset();
        int half = DOOR_WIDTH / 2;

        // Açıklık köşeye taşarsa duvar yapısal olarak bozulur ve anchor duvarın dışına düşer.
        if (center - half < 1 || center + half > along - 2) {
            throw new IllegalArgumentException(wall + " kapısı ofset " + spec.offset()
                    + " ile duvarın dışına taşıyor (duvar uzunluğu " + along + ")");
        }

        for (int d = -half; d <= half; d++) {
            for (int y = DOOR_BASE_Y; y < DOOR_BASE_Y + DOOR_HEIGHT; y++) {
                clipboard.setBlock(doorBlock(wall, sizeX, sizeZ, center + d, y), air);
            }
        }

        BlockVector3 anchor = doorBlock(wall, sizeX, sizeZ, center, DOOR_BASE_Y);
        BlockVector3 local = anchor.subtract(origin);
        return new Vec3i(local.x(), local.y(), local.z());
    }

    /** Duvar üzerindeki bir noktanın clipboard koordinatı. */
    private static BlockVector3 doorBlock(Door wall, int sizeX, int sizeZ, int along, int y) {
        return switch (wall) {
            case NORTH -> BlockVector3.at(along, y, 0);
            case SOUTH -> BlockVector3.at(along, y, sizeZ - 1);
            case WEST -> BlockVector3.at(0, y, along);
            case EAST -> BlockVector3.at(sizeX - 1, y, along);
        };
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
     * Odanın metadata dosyasını yazar — {@code generation.md} §8'deki şema.
     *
     * <p>Yorum satırları bilerek dolu: bu dosyalar harita ekibinin kendi odaları için
     * kopyalayacağı örnek. Anchor'ın neye göre yazıldığı burada anlatılmazsa başka
     * hiçbir yerde okunmayacak.
     */
    public static File writeMetadata(BuiltRoom room, File directory, String name) throws IOException {
        File file = new File(directory, name + ".yml");
        try (Writer w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            w.write("# " + name + " — TakashiDungeons oda metadata'sı\n");
            w.write("# TestRoomFactory tarafından üretildi; elle düzenlenirse /tdungeons gen ezer.\n");
            w.write("#\n");
            w.write("# tip:     giris | normal | boss\n");
            w.write("# agirlik: aday seçiminde ağırlıklı rastgele payı (yüksek = daha sık)\n");
            w.write("# kapilar: [x, y, z] — kapı açıklığının TABAN-MERKEZ bloğu,\n");
            w.write("#          odanın ORIGIN'ine göre (schematic köşesine göre DEĞİL).\n");
            w.write("#          Yön burada yazmaz; anchor vektöründen hesaplanır.\n");
            w.write("#          Listedeki sıra kapının adresi — hangi kapı bağlandı, hangisine\n");
            w.write("#          tıpa basılacak bu indeksle takip ediliyor.\n");
            w.write("\n");
            w.write("tip: " + room.type().yamlValue() + "\n");
            w.write("agirlik: " + room.weight() + "\n");
            w.write("\n");
            if (room.doorAnchors().isEmpty()) {
                w.write("kapilar: []\n");
            } else {
                w.write("kapilar:\n");
                for (Vec3i a : room.doorAnchors()) {
                    w.write("  - [" + a.x() + ", " + a.y() + ", " + a.z() + "]\n");
                }
            }
        }
        return file;
    }

    /**
     * FAZ 1 testleri için standart oda setini üretir ve diske yazar ({@code .schem} + {@code .yml}).
     *
     * <p>Set bilerek çeşitli — her oda bir şeyi sınıyor:
     * <ul>
     *   <li>{@code test_cross} 4 kapı — dallanma noktası (§6.3'ün labirent hissi)</li>
     *   <li>{@code test_corridor} karşılıklı çift — zincir parçası</li>
     *   <li>{@code test_corner} komşu çift — <b>rotasyon işaretini ölçen oda</b> (§11-1);
     *       K-G simetrik olmadığı için saat yönü / ters yön ayırt edilebiliyor</li>
     *   <li>{@code test_deadend} tek kapı — yan dalın ucu</li>
     *   <li>{@code test_giris} tek kapı, tip {@code giris} — kritik path'in başlangıcı</li>
     *   <li>{@code test_boss} tek kapı, 33x33 — farklı boyutun aynı dungeon'da yaşadığını gösterir</li>
     *   <li>{@code test_long} 9x25 dikdörtgen, doğu kapısı güney ucunda — <b>§4'ün naif
     *       mutlak-değer kuralını kıran oda.</b> Naif kural "güney duvarı" der; doğrusu doğu.</li>
     *   <li>{@code test_even} 10x16 <b>çift kenarlı</b>, ofsetli kapılar — §9'daki
     *       "tek sayı kenar kuralı kalktı" iddiasını sınar (açık soru #2)</li>
     * </ul>
     *
     * @return yazılan oda sayısı
     */
    public static int writeStandardSet(File directory) throws IOException {
        record Spec(String name, int sizeX, int sizeZ, int height,
                    RoomType type, int weight, List<DoorSpec> doors) {
        }

        List<Spec> specs = List.of(
                new Spec("test_cross", 17, 17, 9, RoomType.NORMAL, 100, List.of(
                        DoorSpec.of(Door.NORTH), DoorSpec.of(Door.EAST),
                        DoorSpec.of(Door.SOUTH), DoorSpec.of(Door.WEST))),
                new Spec("test_corridor", 17, 17, 9, RoomType.NORMAL, 150, List.of(
                        DoorSpec.of(Door.NORTH), DoorSpec.of(Door.SOUTH))),
                new Spec("test_corner", 17, 17, 9, RoomType.NORMAL, 120, List.of(
                        DoorSpec.of(Door.NORTH), DoorSpec.of(Door.EAST))),
                new Spec("test_deadend", 17, 17, 9, RoomType.NORMAL, 60, List.of(
                        DoorSpec.of(Door.NORTH))),
                new Spec("test_giris", 17, 17, 9, RoomType.GIRIS, 100, List.of(
                        DoorSpec.of(Door.NORTH))),
                new Spec("test_boss", 33, 33, 15, RoomType.BOSS, 100, List.of(
                        DoorSpec.of(Door.NORTH))),
                // doğu duvarı, güney ucuna yakın: v = (+4, +10). |dz| > |dx| olduğu için naif
                // kural GÜNEY der — yanlış. Normalize edilince nx=4/4=1.0 > nz=10/12=0.833 -> DOĞU.
                // Ofset +10 tavan: +11'de 3 bloklu açıklık köşe bloğuna taşıyor (25 uzunlukta
                // son geçerli merkez 22). Sınır kontrolü carveDoor'da.
                new Spec("test_long", 9, 25, 7, RoomType.NORMAL, 80, List.of(
                        DoorSpec.of(Door.NORTH), new DoorSpec(Door.EAST, 10))),
                // çift kenar (10, 16) -> origin (5,0,8), kutu -5..4 / -8..7: origin'e göre asimetrik
                new Spec("test_even", 10, 16, 8, RoomType.NORMAL, 80, List.of(
                        new DoorSpec(Door.NORTH, 1), new DoorSpec(Door.SOUTH, -2),
                        new DoorSpec(Door.EAST, 3))));

        for (Spec spec : specs) {
            BuiltRoom room = buildRoom(spec.sizeX(), spec.sizeZ(), spec.height(),
                    spec.type(), spec.weight(), spec.doors());
            write(room.clipboard(), directory, spec.name());
            writeMetadata(room, directory, spec.name());
        }
        return specs.size();
    }

    /** {@link BlockTypes} alanları nullable; eksikse sessizce hava koymak yerine patlat. */
    private static BlockState state(BlockType type, String name) {
        if (type == null) {
            throw new IllegalStateException("Blok tipi bu surumde yok: " + name);
        }
        return type.getDefaultState();
    }
}
