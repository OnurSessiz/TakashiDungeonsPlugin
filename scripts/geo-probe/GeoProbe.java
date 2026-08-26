import com.takashi.dungeons.generation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-free verification of the phase 1B geometry.
 *
 * The generation package is deliberately pure Java: no Bukkit, no WorldEdit. That is why all
 * the placement maths can be exercised without starting a server. The anchor values
 * TestRoomFactory produces are kept here as HAND-computed constants; that it really produces
 * them is verified on the server with /tdungeons room.
 */
public class GeoProbe {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== FAZ 1B geometri dogrulamasi (sunucusuz) ===\n");

        rotationRoundTrip();
        alignFormula();
        wallDerivationSquare();
        wallDerivationRectangle();
        evenSidedRoom();
        aabbRules();
        attachAllCombinations();
        chainDrift();

        System.out.println("\n==============================================");
        System.out.println("GECEN: " + pass + "   KALAN: " + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- test rooms
    // TestRoomFactory formulu: origin = (sizeX/2, 0, sizeZ/2)
    //                          anchor = wallBlock - origin

    static RoomTemplate cross() {   // 17x17, 4 kapi, ofsetsiz
        return template("test_cross", box(17, 9, 17),
                new Vec3i(0, 1, -8), new Vec3i(8, 1, 0), new Vec3i(0, 1, 8), new Vec3i(-8, 1, 0));
    }

    static RoomTemplate corner() {  // 17x17, K+D
        return template("test_corner", box(17, 9, 17), new Vec3i(0, 1, -8), new Vec3i(8, 1, 0));
    }

    static RoomTemplate boss() {    // 33x33, tek kapi
        return template("test_boss", box(33, 15, 33), new Vec3i(0, 1, -16));
    }

    static RoomTemplate longRoom() { // 9 genis x 25 uzun, dogu kapisi ofset +10
        return template("test_long", box(9, 7, 25), new Vec3i(0, 1, -12), new Vec3i(4, 1, 10));
    }

    static RoomTemplate even() {    // 10 x 16 CIFT kenar -> origin (5,0,8), kutu -5..4 / -8..7
        return template("test_even", new Aabb(-5, 0, -8, 4, 7, 7),
                new Vec3i(1, 1, -8), new Vec3i(-2, 1, 7), new Vec3i(4, 1, 3));
    }

    /** For an odd-sided room the origin is centred: box -n..n. */
    static Aabb box(int sizeX, int height, int sizeZ) {
        return new Aabb(-(sizeX / 2), 0, -(sizeZ / 2),
                sizeX - 1 - sizeX / 2, height - 1, sizeZ - 1 - sizeZ / 2);
    }

    static RoomTemplate template(String name, Aabb box, Vec3i... anchors) {
        List<DoorAnchor> doors = new ArrayList<>();
        for (int i = 0; i < anchors.length; i++) {
            doors.add(DoorAnchor.of(i, anchors[i], box));
        }
        return new RoomTemplate(name, RoomType.NORMAL, 100, doors, box);
    }

    // ---------------------------------------------------------------- tests

    static void rotationRoundTrip() {
        section("Rotasyon: 4 adim = birim, ve olculen isaret");
        Vec3i v = new Vec3i(3, 7, 5);
        Vec3i r = v;
        for (int i = 0; i < 4; i++) r = Rotation.CW_90.apply(r);
        check("nokta 4x90 sonrasi kendine doner", v.equals(r), v + " -> " + r);

        for (Direction d : Direction.values()) {
            Direction x = d;
            for (int i = 0; i < 4; i++) x = Rotation.CW_90.apply(x);
            check(d + " 4x90 sonrasi kendine doner", d == x, d + " -> " + x);
        }
        check("KUZEY + R=1 = DOGU (olculen saat yonu)",
                Rotation.CW_90.apply(Direction.NORTH) == Direction.EAST,
                "" + Rotation.CW_90.apply(Direction.NORTH));
        check("R=1 nokta formulu (x,y,z)->(-z,y,x)",
                Rotation.CW_90.apply(new Vec3i(3, 7, 5)).equals(new Vec3i(-5, 7, 3)),
                "" + Rotation.CW_90.apply(new Vec3i(3, 7, 5)));
        check("Y rotasyondan etkilenmiyor (cok katli oda destegi)",
                Rotation.CW_90.apply(new Vec3i(3, 7, 5)).y() == 7
                        && Rotation.CW_270.apply(new Vec3i(3, 7, 5)).y() == 7, "");
        check("ofDegrees(270) == CW_270", Rotation.ofDegrees(270) == Rotation.CW_270, "");
        check("ofDegrees(-90) == CW_270 (negatif normalize)",
                Rotation.ofDegrees(-90) == Rotation.CW_270, "" + Rotation.ofDegrees(-90));
    }

    static void alignFormula() {
        section("align(): cocugun kapisi ebeveyninkine YUZ YUZE donuyor mu (16 kombinasyon)");
        for (Direction parentOut : Direction.values()) {
            for (Direction childWall : Direction.values()) {
                Rotation r = Rotation.align(parentOut, childWall);
                check(parentOut + " kapisina " + childWall + " duvarli kapi -> R=" + r.steps(),
                        r.apply(childWall) == parentOut.opposite(),
                        "cocuk kapisi " + r.apply(childWall) + ", beklenen " + parentOut.opposite());
            }
        }
    }

    static void wallDerivationSquare() {
        section("Duvar turetme: kare oda 17x17");
        Aabb b = box(17, 9, 17);
        check("(0,1,-8) kuzey", Direction.ofAnchor(new Vec3i(0, 1, -8), b) == Direction.NORTH, "");
        check("(8,1,0) dogu", Direction.ofAnchor(new Vec3i(8, 1, 0), b) == Direction.EAST, "");
        check("(0,1,8) guney", Direction.ofAnchor(new Vec3i(0, 1, 8), b) == Direction.SOUTH, "");
        check("(-8,1,0) bati", Direction.ofAnchor(new Vec3i(-8, 1, 0), b) == Direction.WEST, "");
        check("(5,1,-8) ofsetli kapi hala kuzey",
                Direction.ofAnchor(new Vec3i(5, 1, -8), b) == Direction.NORTH, "");
        try {
            Direction.ofAnchor(new Vec3i(0, 1, 0), b);
            check("origin ustundeki anchor reddedilir", false, "istisna atilmadi");
        } catch (IllegalArgumentException e) {
            check("origin ustundeki anchor reddedilir", true, "");
        }
    }

    static void wallDerivationRectangle() {
        section("Duvar turetme: DIKDORTGEN oda -- naif kuralin kirildigi yer (generation.md 4)");
        Aabb b = box(9, 7, 25);          // -4..4 (X) / -12..12 (Z)
        Vec3i v = new Vec3i(4, 1, 10);   // dogu duvari, guney ucuna yakin
        check("naif |dz|>|dx| kurali bu ornekte GUNEY der (yanlis)",
                Math.abs(v.z()) > Math.abs(v.x()), "|dx|=4 |dz|=10");
        check("normalize edilmis kural DOGU diyor  (nx=4/4=1.00 > nz=10/12=0.83)",
                Direction.ofAnchor(v, b) == Direction.EAST, "bulunan: " + Direction.ofAnchor(v, b));
        check("(0,1,-12) kuzey", Direction.ofAnchor(new Vec3i(0, 1, -12), b) == Direction.NORTH, "");
        check("(-4,1,-11) bati", Direction.ofAnchor(new Vec3i(-4, 1, -11), b) == Direction.WEST, "");
        check("(3,1,12) guney (X ucta degil, Z ucta)",
                Direction.ofAnchor(new Vec3i(3, 1, 12), b) == Direction.SOUTH, "");
    }

    static void evenSidedRoom() {
        section("ACIK SORU #2: cift kenarli, origin'e gore ASIMETRIK oda (10x16)");
        RoomTemplate t = even();
        System.out.println("   kutu: " + t.localBox());
        check("kutu X'te asimetrik (-5..4)",
                t.localBox().minX() == -5 && t.localBox().maxX() == 4, "" + t.localBox());
        check("kutu Z'de asimetrik (-8..7)",
                t.localBox().minZ() == -8 && t.localBox().maxZ() == 7, "" + t.localBox());

        Direction[] beklenen = {Direction.NORTH, Direction.SOUTH, Direction.EAST};
        for (DoorAnchor d : t.doors()) {
            System.out.println("   " + d);
            check("kapi#" + d.index() + " duvari " + beklenen[d.index()],
                    d.wall() == beklenen[d.index()], "bulunan " + d.wall());
        }
        check("asimetrik kutu R=1 ile donunce boyut takas eder",
                t.localBox().rotate(Rotation.CW_90).sizeX() == 16
                        && t.localBox().rotate(Rotation.CW_90).sizeZ() == 10,
                "" + t.localBox().rotate(Rotation.CW_90));
    }

    static void aabbRules() {
        section("Sirt sirta / cakisma kurallari");
        Aabb a = new Aabb(0, 0, 0, 10, 5, 10);
        check("bitisik kutular (maxX=10 | minX=11) KESISMEZ",
                !a.intersects(new Aabb(11, 0, 0, 20, 5, 10)), "");
        check("bir blok paylasan kutular (maxX=10 | minX=10) KESISIR",
                a.intersects(new Aabb(10, 0, 0, 20, 5, 10)), "");
        check("Y'de ayrik kutular kesismez -- cok katli dungeon destegi",
                !a.intersects(new Aabb(0, 6, 0, 10, 12, 10)), "");
        check("Y'de temas eden kutular kesisir",
                a.intersects(new Aabb(0, 5, 0, 10, 12, 10)), "");
        check("kesisim simetrik",
                a.intersects(new Aabb(5, 2, 5, 15, 8, 15))
                        == new Aabb(5, 2, 5, 15, 8, 15).intersects(a), "");
        Aabb rot = box(9, 7, 25).rotate(Rotation.CW_90);
        check("9x25 kutu R=1 ile donunce 25x9 olur", rot.sizeX() == 25 && rot.sizeZ() == 9, "" + rot);
        check("rotasyon hacmi korur",
                rot.sizeX() * rot.sizeY() * rot.sizeZ() == 9 * 7 * 25, "" + rot);
    }

    static void attachAllCombinations() {
        section("Yerlestirme: tum odalar x tum kapilar x tum yonler");
        List<RoomTemplate> all = List.of(cross(), corner(), boss(), longRoom(), even());
        Vec3i parentAnchor = new Vec3i(37, 64, -91);   // keyfi, hizalanmamis nokta
        int total = 0, ok = 0;

        for (RoomTemplate t : all) {
            for (int di = 0; di < t.doorCount(); di++) {
                for (Direction out : Direction.values()) {
                    total++;
                    PlacedRoom p = t.attachTo(di, parentAnchor, out);
                    Vec3i want = parentAnchor.plus(out.step());

                    boolean mated = p.doorAnchor(di).equals(want);
                    boolean facing = p.doorOutward(di) == out.opposite();
                    boolean inBox = p.bounds().contains(p.doorAnchor(di));
                    boolean sameY = p.doorAnchor(di).y() == parentAnchor.y();
                    boolean volume = p.bounds().sizeY() == t.localBox().sizeY();

                    if (mated && facing && inBox && sameY && volume) ok++;
                    else System.out.println("   HATA " + t.name() + " kapi#" + di + " -> " + out
                            + "  anchor=" + p.doorAnchor(di) + " beklenen=" + want
                            + " disaBakan=" + p.doorOutward(di) + " kutuIcinde=" + inBox);
                }
            }
        }
        check("hepsi tam oturuyor: anchor + yon + kutu + Y hizasi", ok == total, ok + "/" + total);
        System.out.println("   " + total + " yerlestirme sinandi");
    }

    static void chainDrift() {
        section("Zincir: ofsetli kapilar duz sirayi kiriyor mu (generation.md 6.4)");
        // test_long: north door centred, east door near the south end (offset +10).
        // Chaining from the north door and always continuing through the east door, the
        // layout should meander on its own.
        RoomTemplate t = longRoom();
        Vec3i anchor = new Vec3i(0, 64, 0);
        Direction out = Direction.NORTH;
        List<Aabb> placed = new ArrayList<>();
        boolean anyOverlap = false;
        StringBuilder path = new StringBuilder();

        for (int i = 0; i < 6; i++) {
            PlacedRoom p = t.attachTo(0, anchor, out);          // kuzey kapisindan bagla
            for (Aabb b : placed) if (b.intersects(p.bounds())) anyOverlap = true;
            placed.add(p.bounds());
            path.append(p.origin()).append(" ");
            anchor = p.doorAnchor(1);                            // dogu kapisindan devam
            out = p.doorOutward(1);
        }
        System.out.println("   origin izi: " + path.toString().trim());
        check("6 odalik zincir kuruldu", placed.size() == 6, "");
        check("zincir tek eksende kalmadi (kivriliyor)",
                placed.stream().map(Aabb::minX).distinct().count() > 1
                        && placed.stream().map(Aabb::minZ).distinct().count() > 1, "");
        // NOTE: a collision may well be EXPECTED -- this chain deliberately tangles itself.
        // What matters is that the collision is DETECTABLE; rejecting it is 1C's job.
        System.out.println("   cakisma tespit edildi mi: " + (anyOverlap ? "EVET" : "hayir")
                + "  (1C'de bu adaylari reddedecek)");
    }

    // ---------------------------------------------------------------- helpers

    static void section(String title) {
        System.out.println("--- " + title + " ---");
    }

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("   [OK]   " + what); }
        else { fail++; System.out.println("   [FAIL] " + what + "   " + detail); }
    }
}
