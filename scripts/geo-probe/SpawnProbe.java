import com.takashi.dungeons.generation.Aabb;
import com.takashi.dungeons.generation.Vec3i;
import com.takashi.dungeons.mob.ColumnProbe;
import com.takashi.dungeons.mob.RoomSpawnFinder;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Server-free verification of the phase 3B spawn search.
 *
 * RoomSpawnFinder reads the world only through ColumnProbe, so the whole search can be run over
 * hand-drawn rooms. That is the point: a flood fill is either right on an L-shaped hall or
 * silently wrong on one, and "silently wrong" is invisible on a server -- every room still gets
 * its mobs, they are just in places nobody can reach.
 *
 * Rooms are drawn as ASCII layers. '#' is solid, '.' is air. Every room is given a solid floor
 * layer at the bottom and open air above, so the drawing describes the WALLS.
 */
public class SpawnProbe {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== FAZ 3B spawn aramasi dogrulamasi (sunucusuz) ===\n");

        openRoom();
        blockedCentre();
        sealedAlcove();
        lShapedHall();
        corridor();
        twoStorey();
        spacingHonoured();
        reproducibility();
        noFloorAtAll();

        System.out.println("\n==============================================");
        System.out.println("GECEN: " + pass + "   KALAN: " + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 1. duz oda

    static void section(String title) {
        System.out.println("\n-- " + title);
    }

    static void openRoom() {
        section("Bos kare oda: tohum tam merkez, N nokta bulunuyor");
        Room room = Room.of(
                "#########",
                "#.......#",
                "#.......#",
                "#.......#",
                "#.......#",
                "#.......#",
                "#.......#",
                "#.......#",
                "#########");
        List<Vec3i> found = room.find(4);
        check("4 nokta istendi, 4 bulundu", found.size() == 4, "bulunan: " + found.size());
        // Merkez (4,4) bos -> halka yaricapi 0'da bulunmali.
        Vec3i seed = found.get(0);
        check("ilk nokta odanin merkezi", seed.x() == 4 && seed.z() == 4,
                "ilk nokta: " + seed);
        check("hepsi oda icinde", room.allInside(found), "disari tasan nokta var");
        check("hepsi zemin ustunde", room.allStandable(found), "havada duran nokta var");
    }

    // ---------------------------------------------------------------- 2. merkez kapali

    static void blockedCentre() {
        section("Merkezde sutun: halka aramasi disari cikiyor");
        Room room = Room.of(
                "#########",
                "#.......#",
                "#.......#",
                "#.......#",
                "#...#...#",
                "#.......#",
                "#.......#",
                "#.......#",
                "#########");
        List<Vec3i> found = room.find(3);
        check("nokta bulundu", found.size() == 3, "bulunan: " + found.size());
        check("hicbiri sutunun icinde degil",
                found.stream().noneMatch(p -> p.x() == 4 && p.z() == 4), "sutuna kondu");
        Vec3i seed = found.get(0);
        int chebyshev = Math.max(Math.abs(seed.x() - 4), Math.abs(seed.z() - 4));
        check("tohum merkezin 1 halka disinda", chebyshev == 1, "chebyshev: " + chebyshev);
    }

    // ---------------------------------------------------------------- 3. muhurlu nis

    static void sealedAlcove() {
        section("Duvar ardindaki muhurlu nis: flood fill oraya ULASMAMALI");
        // Sagdaki 2 genisligindeki oda tamamen duvarla ayrilmis -- oyuncu giremez.
        Room room = Room.of(
                "############",
                "#........#.#",
                "#........#.#",
                "#........#.#",
                "#........#.#",
                "#........#.#",
                "#........#.#",
                "############");
        List<Vec3i> found = room.find(40);
        boolean leaked = found.stream().anyMatch(p -> p.x() >= 10);
        check("nise mob konmadi", !leaked, "nise sizan nokta var");
        check("ana bolumde nokta bulundu", !found.isEmpty(), "hic nokta yok");

        // survey() yogunluk hesabinin girdisi: nis sayilirsa oda oldugundan buyuk gorunur ve
        // 6 mob'luk odaya 8 mob konur. Ana bolum 8x6 = 48 sutun; nis 1x6 = 6 sutun.
        List<Vec3i> surface = room.survey();
        check("survey sadece ulasilabilir zemini sayiyor (48)", surface.size() == 48,
                "sayilan: " + surface.size());
        System.out.println("        (bulunan nokta: " + found.size() + ", hepsi ana bolumde)");
    }

    // ---------------------------------------------------------------- 4. L oda

    static void lShapedHall() {
        section("L seklinde salon: kose donuluyor, duvarin icinden gecilmiyor");
        Room room = Room.of(
                "###########",
                "#.....#####",
                "#.....#####",
                "#.....#####",
                "#.........#",
                "#.........#",
                "#.........#",
                "###########");
        List<Vec3i> found = room.find(30);
        check("hepsi yurunebilir zeminde", room.allStandable(found), "duvara kondu");
        boolean reachesArm = found.stream().anyMatch(p -> p.x() >= 7);
        check("L'nin uzak kolu da dolduruldu", reachesArm, "sadece bir kol dolduruldu");
        check("duvar bolgesine kimse konmadi",
                found.stream().noneMatch(p -> p.z() <= 3 && p.x() >= 6), "duvara sizdi");
    }

    // ---------------------------------------------------------------- 5. koridor

    static void corridor() {
        section("Dar uzun koridor (kare degil): halka degil zemin takip ediliyor");
        Room room = Room.of(
                "###################",
                "#.................#",
                "#.................#",
                "###################");
        List<Vec3i> found = room.find(5);
        check("5 nokta bulundu", found.size() == 5, "bulunan: " + found.size());
        check("hepsi koridorun icinde", room.allStandable(found), "duvara kondu");
        int minX = found.stream().mapToInt(Vec3i::x).min().orElse(0);
        int maxX = found.stream().mapToInt(Vec3i::x).max().orElse(0);
        check("koridor boyunca yayildi (>= 8 blok)", maxX - minX >= 8,
                "yayilim: " + (maxX - minX));
    }

    // ---------------------------------------------------------------- 6. iki kat

    static void twoStorey() {
        section("Iki katli oda: ust kat ayri, fill oraya cikmiyor");
        // Alt kat y=0 zemin, y=4'te ikinci bir tavan/zemin katmani.
        Room room = Room.of(
                "#######",
                "#.....#",
                "#.....#",
                "#.....#",
                "#######");
        room.addSolidLayer(4);   // ust katin zemini
        room.height = 9;
        List<Vec3i> found = room.find(20);
        boolean upstairs = found.stream().anyMatch(p -> p.y() >= 4);
        check("ust kata cikilmadi", !upstairs, "ust katta nokta var");
        check("hepsi alt katta", found.stream().allMatch(p -> p.y() == 0),
                "farkli y'de nokta var");
    }

    // ---------------------------------------------------------------- 7. aralik

    static void spacingHonoured() {
        section("Minimum aralik: noktalar birbirinin dibine yapismiyor");
        Room room = Room.of(
                "###############",
                "#.............#",
                "#.............#",
                "#.............#",
                "#.............#",
                "#.............#",
                "#.............#",
                "###############");
        List<Vec3i> found = room.find(6);
        int worst = Integer.MAX_VALUE;
        for (int i = 0; i < found.size(); i++) {
            for (int j = i + 1; j < found.size(); j++) {
                int dx = found.get(i).x() - found.get(j).x();
                int dz = found.get(i).z() - found.get(j).z();
                worst = Math.min(worst, dx * dx + dz * dz);
            }
        }
        check("her cift en az 3 blok uzakta", worst >= 9, "en yakin cift karesi: " + worst);
    }

    // ---------------------------------------------------------------- 8. tekrarlanabilirlik

    static void reproducibility() {
        section("Ayni seed ayni sonuc (generation.md 13 vaadi)");
        Room a = Room.of(
                "##########",
                "#........#",
                "#..##....#",
                "#........#",
                "#....##..#",
                "#........#",
                "##########");
        List<Vec3i> first = a.findSeeded(6, 12345L);
        List<Vec3i> second = a.findSeeded(6, 12345L);
        check("ayni seed -> ayni noktalar", first.equals(second),
                first + " != " + second);

        // Tek bir "farkli seed farkli sonuc" iddiasi kirilgan olurdu: iki seed tesadufen ayni
        // sonucu verebilir. 16 seed'in HEPSI ayni cikarsa yanlilik gercektir.
        Set<String> distinct = new HashSet<>();
        for (long seed = 1; seed <= 16; seed++) {
            distinct.add(a.findSeeded(6, seed).toString());
        }
        check("16 seed en az 2 farkli yerlesim uretti (yon yanliligi yok)", distinct.size() >= 2,
                "hepsi ayni cikti: " + distinct);
    }

    // ---------------------------------------------------------------- 9. zemin yok

    static void noFloorAtAll() {
        section("Hic zemin yok: bos liste, exception degil");
        Room room = Room.of(
                "#####",
                "#####",
                "#####");
        // Tamamen dolu -> ustunde bosluk olan zemin yok.
        List<Vec3i> found = room.find(4);
        check("bos liste donuyor", found.isEmpty(), "bulunan: " + found.size());
    }

    // ================================================================ yardimcilar

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("   [OK]   " + what); }
        else { fail++; System.out.println("   [FAIL] " + what + "   " + detail); }
    }

    /**
     * ASCII katmanlarindan kurulan oda. Satir = z, sutun = x.
     * y=0 daima zemin (cizimdeki '#' disinda), y=1..height-1 cizime gore.
     */
    static final class Room implements ColumnProbe {

        final String[] plan;
        final int sizeX, sizeZ;
        int height = 5;
        final Set<Integer> solidLayers = new HashSet<>();

        private Room(String[] plan) {
            this.plan = plan;
            this.sizeZ = plan.length;
            this.sizeX = plan[0].length();
        }

        static Room of(String... plan) {
            return new Room(plan);
        }

        /** y seviyesine tam dolu bir katman ekler (ust katin zemini). */
        void addSolidLayer(int y) {
            solidLayers.add(y);
        }

        Aabb box() {
            return new Aabb(0, 0, 0, sizeX - 1, height - 1, sizeZ - 1);
        }

        boolean isWall(int x, int z) {
            if (x < 0 || x >= sizeX || z < 0 || z >= sizeZ) return true;
            return plan[z].charAt(x) == '#';
        }

        @Override
        public boolean isFloor(int x, int y, int z) {
            if (x < 0 || x >= sizeX || z < 0 || z >= sizeZ) return false;
            if (solidLayers.contains(y)) return true;
            // y = -1 diye bir sey yok: zemin y=0'da ve duvar sutunlari bastan asagi dolu.
            if (y == 0) return true;
            return isWall(x, z) && y < height;
        }

        @Override
        public boolean isClear(int x, int y, int z) {
            if (x < 0 || x >= sizeX || z < 0 || z >= sizeZ) return false;
            if (y <= 0) return false;
            if (solidLayers.contains(y)) return false;
            return !isWall(x, z);
        }

        List<Vec3i> find(int count) {
            return findSeeded(count, 42L);
        }

        List<Vec3i> findSeeded(int count, long seed) {
            return new RoomSpawnFinder(this).find(box(), count, new Random(seed));
        }

        List<Vec3i> survey() {
            return new RoomSpawnFinder(this).survey(box(), new Random(42L));
        }

        boolean allInside(List<Vec3i> points) {
            Aabb box = box();
            for (Vec3i p : points) if (!box.contains(p)) return false;
            return true;
        }

        /** Nokta gercekten uzerinde durulabilir bir zemin mi. */
        boolean allStandable(List<Vec3i> points) {
            for (Vec3i p : points) {
                if (!isFloor(p.x(), p.y(), p.z())) return false;
                if (!isClear(p.x(), p.y() + 1, p.z())) return false;
                if (!isClear(p.x(), p.y() + 2, p.z())) return false;
            }
            return true;
        }
    }
}
