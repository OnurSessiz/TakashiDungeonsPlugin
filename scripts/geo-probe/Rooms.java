import com.takashi.dungeons.generation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Probe'larin ortak test oda seti.
 *
 * Degerler TestRoomFactory'nin urettikleriyle AYNI -- uretilen .yml dosyalarindan
 * dogrulanabilir. Tek kaynak olmasinin sebebi: oda seti degisince iki ayri probe'u
 * elle guncellemek unutulur ve testler sessizce gercekle alakasiz hale gelir.
 */
public final class Rooms {

    private Rooms() {
    }

    /**
     * Giris + boss dahil butun set (8 oda), <b>ALFABETIK</b> sirada.
     *
     * Sira onemli ve tesadufi degil: sunucuda sablonlar
     * {@code SchematicService.list()}'ten geliyor ve o metot dosya adlarini
     * SIRALIYOR. RoomLibrary.drawWeighted kumulatif agirlik taramasi yaptigi icin
     * ayni tohum + farkli SIRA = farkli dungeon uretiyor.
     *
     * Bu sirayi sunucuyla ayni tutmak sayesinde probe'lar sunucunun ne uretecegini
     * ONCEDEN hesaplayabiliyor -- blok testlerinin beklenen koordinatlari boyle
     * cikariliyor.
     */
    public static List<RoomTemplate> all() {
        return List.of(
                t("test_boss", RoomType.BOSS, 100, box(33, 15, 33),
                        new Vec3i(0, 1, -16)),
                t("test_corner", RoomType.NORMAL, 120, box(17, 9, 17),
                        new Vec3i(0, 1, -8), new Vec3i(8, 1, 0)),
                t("test_corridor", RoomType.NORMAL, 150, box(17, 9, 17),
                        new Vec3i(0, 1, -8), new Vec3i(0, 1, 8)),
                t("test_cross", RoomType.NORMAL, 100, box(17, 9, 17),
                        new Vec3i(0, 1, -8), new Vec3i(8, 1, 0),
                        new Vec3i(0, 1, 8), new Vec3i(-8, 1, 0)),
                t("test_deadend", RoomType.NORMAL, 60, box(17, 9, 17),
                        new Vec3i(0, 1, -8)),
                t("test_even", RoomType.NORMAL, 80, new Aabb(-5, 0, -8, 4, 7, 7),
                        new Vec3i(1, 1, -8), new Vec3i(-2, 1, 7), new Vec3i(4, 1, 3)),
                t("test_giris", RoomType.GIRIS, 100, box(17, 9, 17),
                        new Vec3i(0, 1, -8)),
                t("test_long", RoomType.NORMAL, 80, box(9, 7, 25),
                        new Vec3i(0, 1, -12), new Vec3i(4, 1, 10)));
    }

    /** Sadece 'normal' tipli odalar (6 oda), all() ile ayni sirada. */
    public static List<RoomTemplate> normalPool() {
        List<RoomTemplate> out = new ArrayList<>();
        for (RoomTemplate t : all()) {
            if (t.type() == RoomType.NORMAL) {
                out.add(t);
            }
        }
        return out;
    }

    public static RoomTemplate byName(String name) {
        for (RoomTemplate t : all()) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Test odasi yok: " + name);
    }

    public static RoomTemplate t(String name, RoomType type, int weight, Aabb box,
                                 Vec3i... anchors) {
        List<DoorAnchor> doors = new ArrayList<>();
        for (int i = 0; i < anchors.length; i++) {
            doors.add(DoorAnchor.of(i, anchors[i], box));
        }
        return new RoomTemplate(name, type, weight, doors, box);
    }

    /** Tek sayi kenarli oda icin origin merkezde: kutu -n..n. */
    public static Aabb box(int sizeX, int height, int sizeZ) {
        return new Aabb(-(sizeX / 2), 0, -(sizeZ / 2),
                sizeX - 1 - sizeX / 2, height - 1, sizeZ - 1 - sizeZ / 2);
    }

    /** Slot kutusu: X/Z slot'tan, Y dunya yuksekligi gibi genis. */
    public static Aabb slotBox(int size) {
        return new Aabb(0, -64, 0, size - 1, 319, size - 1);
    }
}
