import com.takashi.dungeons.generation.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1D verification: critical path guarantee, boss assignment, side branches, plug targets.
 *
 * 1C's naive "fill every open door" strategy only hit the target room count 70% of the time.
 * 1D's path-first approach has to fix that -- this file is the proof.
 */
public class DungeonProbe {

    static int pass = 0, fail = 0;

    static final Aabb SLOT = new Aabb(0, -64, 0, 511, 319, 511);
    static final Vec3i CENTER = new Vec3i(256, 64, 256);

    public static void main(String[] args) {
        System.out.println("=== FAZ 1D dogrulamasi (sunucusuz) ===\n");

        pathLengthFormula();
        criticalPathGuarantee();
        bossIsAtPathEnd();
        bossNeverMidDungeon();
        sizeRanges();
        plugCoverage();
        determinism();
        sequentialSeedIndependence();
        outOfBoxFallbacks();

        System.out.println("\n==============================================");
        System.out.println("GECEN: " + pass + "   KALAN: " + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- tests

    static void pathLengthFormula() {
        section("Path uzunlugu formulu: round(hedef x 0.65), min 2");
        check("10 oda -> 7", DungeonSize.criticalPathLength(10) == 7,
                "" + DungeonSize.criticalPathLength(10));
        check("20 oda -> 13", DungeonSize.criticalPathLength(20) == 13,
                "" + DungeonSize.criticalPathLength(20));
        check("3 oda -> 2", DungeonSize.criticalPathLength(3) == 2,
                "" + DungeonSize.criticalPathLength(3));
        check("1 oda -> 2 (alt sinir: giris ve boss ayri oda olsun)",
                DungeonSize.criticalPathLength(1) == 2, "" + DungeonSize.criticalPathLength(1));
    }

    static void criticalPathGuarantee() {
        section("KRITIK PATH GARANTISI -- 1C'nin %70'i neye cikti");
        RoomLibrary lib = new RoomLibrary(Rooms.all());

        for (DungeonSize size : DungeonSize.values()) {
            int runs = 1000, full = 0, pathOk = 0, warned = 0;
            int totalAttempts = 0;
            for (long seed = 1; seed <= runs; seed++) {
                DungeonGenerator.Result r = gen(lib).generate(SLOT, CENTER, size, seed);
                if (r.rooms() >= r.targetRooms()) full++;
                if (r.pathLength() >= r.targetPathLength()) pathOk++;
                if (r.warning() != null) warned++;
                totalAttempts += r.attemptsUsed();
            }
            System.out.printf("   %-7s  path tam: %%%5.1f   oda tam: %%%5.1f   uyarili: %%%4.1f   ort. deneme: %.2f%n",
                    size.key(), 100.0 * pathOk / runs, 100.0 * full / runs,
                    100.0 * warned / runs, (double) totalAttempts / runs);
            check(size.key() + ": kritik path %99+ oraninda hedefe ulasiyor",
                    pathOk >= runs * 99 / 100, pathOk + "/" + runs);
        }
    }

    static void bossIsAtPathEnd() {
        section("Boss path'in SON dugumu mu (random degil, ATAMA)");
        RoomLibrary lib = new RoomLibrary(Rooms.all());
        int runs = 500, withBoss = 0, deepest = 0, correctType = 0;

        for (long seed = 1; seed <= runs; seed++) {
            DungeonGenerator.Result r = gen(lib).generate(SLOT, CENTER, DungeonSize.MEDIUM, seed);
            if (r.bossNodeId() < 0) continue;
            withBoss++;

            LayoutNode boss = r.layout().node(r.bossNodeId());
            if (boss.template().type() == RoomType.BOSS) correctType++;

            // In terms of distance from the entrance, the boss must sit at the end of the
            // critical path. Side branches may go deeper (the quota goes there), but the
            // boss's depth must be AT LEAST the target path length.
            if (boss.depth() >= r.targetPathLength() - 1) deepest++;
        }
        check("her uretimde boss odasi var", withBoss == runs, withBoss + "/" + runs);
        check("boss dugumu gercekten 'boss' tipinde", correctType == withBoss,
                correctType + "/" + withBoss);
        check("boss'un derinligi hedef path uzunlugunu tutuyor", deepest == withBoss,
                deepest + "/" + withBoss);
    }

    static void bossNeverMidDungeon() {
        section("Boss ve giris NORMAL havuzda belirmemeli");
        RoomLibrary lib = new RoomLibrary(Rooms.all());
        int runs = 500, violations = 0;

        for (long seed = 1; seed <= runs; seed++) {
            DungeonGenerator.Result r = gen(lib).generate(SLOT, CENTER, DungeonSize.LARGE, seed);
            for (LayoutNode n : r.layout().nodes()) {
                RoomType t = n.template().type();
                if (t == RoomType.BOSS && n.id() != r.bossNodeId()) violations++;
                if (t == RoomType.ENTRANCE && n.id() != 0) violations++;
            }
        }
        check("500 large uretimde tek bir yanlis yerlesim yok", violations == 0,
                violations + " ihlal");
    }

    static void sizeRanges() {
        section("Boyut araliklari (generation.md 6.1)");
        RoomLibrary lib = new RoomLibrary(Rooms.all());
        Map<DungeonSize, int[]> seen = new HashMap<>();

        for (DungeonSize size : DungeonSize.values()) {
            int min = Integer.MAX_VALUE, max = 0;
            for (long seed = 1; seed <= 400; seed++) {
                DungeonGenerator.Result r = gen(lib).generate(SLOT, CENTER, size, seed);
                min = Math.min(min, r.targetRooms());
                max = Math.max(max, r.targetRooms());
            }
            seen.put(size, new int[]{min, max});
            System.out.printf("   %-7s hedef araligi gorulen: %d-%d  (tanim: %d-%d)%n",
                    size.key(), min, max, size.minRooms(), size.maxRooms());
            check(size.key() + " hedefi tanimli aralikta",
                    min >= size.minRooms() && max <= size.maxRooms(), min + "-" + max);
        }
        check("small ve large ortusmuyor",
                seen.get(DungeonSize.SMALL)[1] < seen.get(DungeonSize.LARGE)[0], "");
    }

    static void plugCoverage() {
        section("Tipa hedefleri: BAGLI olmayan HER kapi listede olmali");
        RoomLibrary lib = new RoomLibrary(Rooms.all());
        int runs = 300, mismatch = 0, connectedInList = 0, totalTargets = 0;

        for (long seed = 1; seed <= runs; seed++) {
            DungeonGenerator.Result r = gen(lib).generate(SLOT, CENTER, DungeonSize.MEDIUM, seed);
            List<PlugTarget> targets = r.plugTargets();
            totalTargets += targets.size();

            int expected = r.layout().openDoorCount() + r.layout().deadDoorCount();
            if (targets.size() != expected) mismatch++;

            // A connected door's anchor must NEVER appear in the plug list -- if it did, the
            // engine would wall up an open passage and the dungeon would become unwalkable.
            List<Vec3i> targetAnchors = new ArrayList<>();
            for (PlugTarget t : targets) targetAnchors.add(t.anchor());
            for (LayoutNode n : r.layout().nodes()) {
                for (int i = 0; i < n.doorCount(); i++) {
                    if (n.doorState(i) == DoorState.CONNECTED
                            && targetAnchors.contains(n.room().doorAnchor(i))) {
                        connectedInList++;
                    }
                }
            }
        }
        check("tipa hedefi sayisi = bos + olu kapi sayisi", mismatch == 0,
                mismatch + " kosumda uyusmuyor");
        check("BAGLI kapi tipa listesine ASLA girmiyor", connectedInList == 0,
                connectedInList + " ihlal");
        System.out.println("   ortalama " + (totalTargets / runs) + " tipa / dungeon");
    }

    static void determinism() {
        section("Tekrarlanabilirlik: ayni seed ayni dungeon");
        RoomLibrary lib = new RoomLibrary(Rooms.all());
        String a = sig(gen(lib).generate(SLOT, CENTER, DungeonSize.MEDIUM, 42L));
        String b = sig(gen(lib).generate(SLOT, CENTER, DungeonSize.MEDIUM, 42L));
        String c = sig(gen(lib).generate(SLOT, CENTER, DungeonSize.MEDIUM, 43L));
        check("seed 42 iki kez ayni sonucu verdi", a.equals(b), "");
        check("seed 43 farkli sonuc verdi", !a.equals(c), "");

        // Retries are derived from the seed too -> reproducible even in a narrow slot
        Aabb tight = new Aabb(0, -64, 0, 120, 319, 120);
        String d1 = sig(gen(lib).generate(tight, new Vec3i(60, 64, 60), DungeonSize.LARGE, 9L));
        String d2 = sig(gen(lib).generate(tight, new Vec3i(60, 64, 60), DungeonSize.LARGE, 9L));
        check("yeniden deneme yolunda da tekrarlanabilir", d1.equals(d2), "");
    }

    /**
     * CONSECUTIVE SEED INDEPENDENCE -- the reason the Seeds class exists.
     *
     * new Random(seed).nextInt(4) returns the same value across consecutive seeds
     * (measured: over 4000 seeds, 0 and 1 never appear). Left unfixed, a small dungeon's
     * room count would come from a single value rather than the 3-6 range. In phase 7 the
     * most natural seed is an incrementing instance id -- and the failure would be silent:
     * dungeons still generate, they just always come out the same size.
     */
    static void sequentialSeedIndependence() {
        section("Ardisik tohumlar bagimsiz mi (Seeds karistirmasi)");

        // First, show that the trap is real
        java.util.Set<Integer> naive = new java.util.HashSet<>();
        for (long s = 1; s <= 200; s++) naive.add(new java.util.Random(s).nextInt(4));
        check("java.util.Random ardisik tohumlarda GERCEKTEN korelasyonlu",
                naive.size() < 4, "gorulen deger sayisi: " + naive.size());

        java.util.Set<Integer> mixed = new java.util.HashSet<>();
        for (long s = 1; s <= 200; s++) mixed.add(Seeds.from(s).nextInt(4));
        check("Seeds.from ile dort degerin hepsi cikiyor", mixed.size() == 4,
                "gorulen: " + mixed.size());

        // The actual test: does a small dungeon's room count use the WHOLE range
        RoomLibrary lib = new RoomLibrary(Rooms.all());
        java.util.Set<Integer> targets = new java.util.HashSet<>();
        for (long seed = 1; seed <= 400; seed++) {
            targets.add(gen(lib).generate(SLOT, CENTER, DungeonSize.SMALL, seed).targetRooms());
        }
        System.out.println("   small hedef degerleri (ardisik tohum 1-400): " + new java.util.TreeSet<>(targets));
        check("small araliginin DORT degeri de gorulüyor (3,4,5,6)",
                targets.contains(3) && targets.contains(4)
                        && targets.contains(5) && targets.contains(6), "" + targets);
    }

    static void outOfBoxFallbacks() {
        section("Out-of-box: eksik oda tipleriyle uretim DURMAMALI");

        // no boss room drawn
        List<RoomTemplate> noBoss = new ArrayList<>();
        for (RoomTemplate t : Rooms.all()) if (t.type() != RoomType.BOSS) noBoss.add(t);
        DungeonGenerator.Result r1 = gen(new RoomLibrary(noBoss))
                .generate(SLOT, CENTER, DungeonSize.MEDIUM, 5L);
        check("boss yokken yine de dungeon uretiliyor", r1.rooms() >= 3, "" + r1.rooms());
        check("boss yoklugu uyari olarak raporlaniyor",
                r1.warning() != null && r1.warning().contains("boss"), "" + r1.warning());
        check("boss yokken bossNodeId = -1", r1.bossNodeId() == -1, "" + r1.bossNodeId());

        // no entrance room drawn
        List<RoomTemplate> noEntrance = new ArrayList<>();
        for (RoomTemplate t : Rooms.all()) if (t.type() != RoomType.ENTRANCE) noEntrance.add(t);
        DungeonGenerator.Result r2 = gen(new RoomLibrary(noEntrance))
                .generate(SLOT, CENTER, DungeonSize.MEDIUM, 5L);
        check("giris odasi yokken normal havuzdan secilip uretiliyor",
                r2.rooms() >= 3, "" + r2.rooms());
        check("giris yokken yerlesim yine tutarli", r2.layout().validate().isEmpty(),
                "" + r2.layout().validate());

        // no rooms at all
        DungeonGenerator.Result r3 = gen(new RoomLibrary(List.of()))
                .generate(SLOT, CENTER, DungeonSize.MEDIUM, 5L);
        check("hic oda yokken patlamiyor, aciklama donuyor",
                r3.rooms() == 0 && r3.warning() != null, "" + r3.warning());

        // only single-door normal rooms -> branchingPool is empty, the fallback must kick in
        List<RoomTemplate> onlySingles = new ArrayList<>();
        onlySingles.add(Rooms.byName("test_deadend"));
        onlySingles.add(Rooms.byName("test_entrance"));
        DungeonGenerator.Result r4 = gen(new RoomLibrary(onlySingles))
                .generate(SLOT, CENTER, DungeonSize.SMALL, 5L);
        check("cok kapili oda yokken de uretim tamamlaniyor (fallback)",
                r4.rooms() >= 2, "" + r4.rooms());
        check("fallback yolunda yerlesim tutarli", r4.layout().validate().isEmpty(),
                "" + r4.layout().validate());
    }

    // ---------------------------------------------------------------- helpers

    static DungeonGenerator gen(RoomLibrary lib) {
        return new DungeonGenerator(lib, 2.0, 8);
    }

    static String sig(DungeonGenerator.Result r) {
        StringBuilder sb = new StringBuilder();
        for (LayoutNode n : r.layout().nodes()) {
            sb.append(n.template().name()).append('@').append(n.room().origin())
              .append('/').append(n.room().rotation().steps()).append(';');
        }
        return sb.append("boss=").append(r.bossNodeId()).toString();
    }

    static void section(String title) {
        System.out.println("--- " + title + " ---");
    }

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("   [OK]   " + what); }
        else { fail++; System.out.println("   [FAIL] " + what + "   " + detail); }
    }
}
