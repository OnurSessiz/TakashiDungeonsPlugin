import com.takashi.dungeons.generation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Phase 1C verification: weighted selection, collision testing, backing off, DEAD doors.
 *
 * Because the generation package is pure Java, no server is needed. The anchor and weight
 * values of the test rooms match what TestRoomFactory produces (verifiable against the
 * generated .yml files).
 */
public class GenProbe {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== FAZ 1C dogrulamasi (sunucusuz) ===\n");

        weightBelongsToTemplate();
        weightOrderingPreserved();
        poolFiltering();
        drawWithoutReplacement();
        chainGeneration();
        branchingExtinction();
        slotBoundsRespected();
        deadEndMarking();
        determinism();

        System.out.println("\n==============================================");
        System.out.println("GECEN: " + pass + "   KALAN: " + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 1C tests

    /**
     * The generation.md §5.4 decision: weight belongs to the TEMPLATE, not to the
     * (template x door) pair. A 4-door room must count its weight ONCE.
     */
    static void weightBelongsToTemplate() {
        section("Agirlik sablona ait mi -- 200k cekilis");
        List<RoomTemplate> pool = normalPool();
        long total = pool.stream().mapToLong(RoomTemplate::weight).sum();

        Random rnd = new Random(20260825L);
        Map<String, Integer> hits = new HashMap<>();
        int draws = 200_000;
        for (int i = 0; i < draws; i++) {
            RoomTemplate t = RoomLibrary.pickWeighted(pool, rnd);
            hits.merge(t.name(), 1, Integer::sum);
        }

        System.out.println("   oda              kapi  agirlik  beklenen  gozlenen  sapma");
        boolean allClose = true;
        for (RoomTemplate t : pool) {
            double expected = 100.0 * t.weight() / total;
            double observed = 100.0 * hits.getOrDefault(t.name(), 0) / draws;
            double drift = Math.abs(expected - observed);
            if (drift > 0.5) allClose = false;
            System.out.printf("   %-16s %2d   %4d     %%%5.2f    %%%5.2f    %.2f%n",
                    t.name(), t.doorCount(), t.weight(), expected, observed, drift);
        }
        check("gozlenen dagilim sablon agirligiyla ortusuyor (sapma < %0.5)", allClose, "");

        // There must be NO correlation with door count. test_cross has 4 doors but a lower
        // weight than test_corridor, so its observed frequency must come out lower too.
        double cross = 100.0 * hits.get("test_cross") / draws;
        double corridor = 100.0 * hits.get("test_corridor") / draws;
        check("test_cross (4 kapi, ag=100) < test_corridor (2 kapi, ag=150)",
                cross < corridor, String.format("cross %%%.2f  corridor %%%.2f", cross, corridor));
    }

    /**
     * The proof of the real argument: under pair ownership the ORDERING the mapper wrote
     * would invert. Here the two models are computed side by side.
     */
    static void weightOrderingPreserved() {
        section("Cifte ait olsaydi ne olurdu -- siralama karsilastirmasi");
        List<RoomTemplate> pool = normalPool();

        long templateTotal = pool.stream().mapToLong(RoomTemplate::weight).sum();
        long pairTotal = pool.stream().mapToLong(t -> (long) t.weight() * t.doorCount()).sum();

        System.out.println("   oda              kapi  agirlik   SABLONA(secilen)  CIFTE(reddedilen)");
        for (RoomTemplate t : pool) {
            double byTemplate = 100.0 * t.weight() / templateTotal;
            double byPair = 100.0 * t.weight() * t.doorCount() / pairTotal;
            System.out.printf("   %-16s %2d   %4d       %%%5.2f            %%%5.2f%n",
                    t.name(), t.doorCount(), t.weight(), byTemplate, byPair);
        }

        RoomTemplate cross = byName(pool, "test_cross");
        RoomTemplate corridor = byName(pool, "test_corridor");

        double crossT = 100.0 * cross.weight() / templateTotal;
        double corridorT = 100.0 * corridor.weight() / templateTotal;
        double crossP = 100.0 * cross.weight() * cross.doorCount() / pairTotal;
        double corridorP = 100.0 * corridor.weight() * corridor.doorCount() / pairTotal;

        check("haritaci koridora daha yuksek agirlik yazmis (150 > 100)",
                corridor.weight() > cross.weight(), "");
        check("SABLONA ait: koridor gercekten daha sik", corridorT > crossT,
                String.format("koridor %%%.2f > cross %%%.2f", corridorT, crossT));
        check("CIFTE ait olsaydi SIRALAMA TERSINE DONERDI (cross one gecerdi)",
                crossP > corridorP,
                String.format("cross %%%.2f > koridor %%%.2f", crossP, corridorP));
        check("dead-end cifte ait olsa yariya inerdi",
                (100.0 * 60 / pairTotal) < (100.0 * 60 / templateTotal) / 2.0 + 0.5, "");
    }

    static void poolFiltering() {
        section("Havuz filtresi: giris ve boss normal havuzda YOK");
        RoomLibrary lib = new RoomLibrary(allTemplates());

        check("normal havuzda giris yok",
                lib.normalPool().stream().noneMatch(t -> t.type() == RoomType.ENTRANCE), "");
        check("normal havuzda boss yok",
                lib.normalPool().stream().noneMatch(t -> t.type() == RoomType.BOSS), "");
        check("giris havuzu dolu", lib.entrances().size() == 1, "" + lib.entrances().size());
        check("boss havuzu dolu", lib.bosses().size() == 1, "" + lib.bosses().size());
        check("normal havuzda 6 oda var", lib.normalPool().size() == 6, "" + lib.normalPool().size());
        check("kutuphane kullanilabilir", lib.isUsable(), "");

        // a doorless room must not enter the pool
        List<RoomTemplate> withDoorless = new ArrayList<>(allTemplates());
        withDoorless.add(new RoomTemplate("sussuz_oda", RoomType.NORMAL, 500,
                List.of(), box(11, 5, 11)));
        RoomLibrary lib2 = new RoomLibrary(withDoorless);
        check("kapisiz oda havuza alinmiyor (bosuna deneme uretmesin)",
                lib2.normalPool().stream().noneMatch(t -> t.name().equals("sussuz_oda")), "");
    }

    static void drawWithoutReplacement() {
        section("Geri cekilme: cekilen sablon havuzdan DUSUYOR mu");
        List<RoomTemplate> pool = new ArrayList<>(normalPool());
        int start = pool.size();
        Random rnd = new Random(7L);
        List<String> drawn = new ArrayList<>();
        RoomTemplate t;
        while ((t = RoomLibrary.drawWeighted(pool, rnd)) != null) {
            drawn.add(t.name());
        }
        check("havuz tukendi", pool.isEmpty(), "kalan " + pool.size());
        check("her sablon TAM BIR KEZ cekildi", drawn.size() == start
                && drawn.stream().distinct().count() == start, drawn.toString());
        check("havuz bosalinca null donuyor (sonsuz dongu yok)",
                RoomLibrary.drawWeighted(pool, rnd) == null, "");
    }

    static void chainGeneration() {
        section("Naif doldurma: 500 seed, hicbirinde tutarsizlik olmamali");
        RoomLibrary lib = new RoomLibrary(allTemplates());
        Aabb slot = slotBox(512);
        Vec3i center = new Vec3i(256, 64, 256);

        int totalRooms = 0, badSeeds = 0;
        for (long seed = 1; seed <= 500; seed++) {
            DungeonLayout layout = naiveFill(lib, Seeds.from(seed), slot, center, 12);
            List<String> problems = layout.validate();
            if (!problems.isEmpty()) {
                badSeeds++;
                if (badSeeds == 1) {
                    System.out.println("   seed " + seed + " sorunlari: " + problems);
                }
            }
            totalRooms += layout.size();
        }
        check("500 seed'in hicbirinde tutarsizlik yok (cakisma/hizasizlik/kopuk graf)",
                badSeeds == 0, badSeeds + " seed sorunlu");
        check("odalar gercekten yerlestiriliyor", totalRooms >= 500 * 5,
                "toplam " + totalRooms + " oda");
    }

    /**
     * "Fill every open door" -- 1C's naive strategy.
     *
     * NO LONGER in the production code (1D's DungeonGenerator replaced it), but kept here as
     * a measurement: this is the baseline that shows WHY 1D's critical path design is
     * necessary.
     */
    static DungeonLayout naiveFill(RoomLibrary lib, java.util.random.RandomGenerator rnd, Aabb slot, Vec3i origin,
                                   int target) {
        DungeonLayout layout = new DungeonLayout(slot);
        List<RoomTemplate> entrancePool = lib.entrances().isEmpty()
                ? lib.normalPool() : lib.entrances();
        RoomTemplate entrance = RoomLibrary.pickWeighted(entrancePool, rnd);
        if (entrance == null) {
            return layout;
        }
        LayoutNode root = layout.addRoot(entrance, origin, Rotation.NONE);

        RoomPlacer placer = new RoomPlacer(rnd, 2.0);
        Deque<OpenDoor> queue = new ArrayDeque<>(root.openDoors());
        while (layout.size() < target && !queue.isEmpty()) {
            OpenDoor door = queue.poll();
            if (layout.node(door.nodeId()).doorState(door.doorIndex()) != DoorState.OPEN) {
                continue;
            }
            RoomPlacer.Attempt a = placer.fill(layout, door, lib.normalPool());
            if (a.success()) {
                queue.addAll(a.placed().openDoors());
            }
        }
        return layout;
    }

    /**
     * A MEASUREMENT, not a failure: 1C's "fill every open door" strategy does NOT guarantee
     * the target room count. The cause is not geometry but a branching process
     * (Galton-Watson) going extinct.
     *
     * This section is the proof of why 1D has to build the critical path FIRST
     * (generation.md §6.2). If these numbers change, 1D's assumption has changed too.
     */
    static void branchingExtinction() {
        section("OLCUM: hedefe ulasma orani ve tikanma MEKANIZMASI");
        List<RoomTemplate> pool = normalPool();
        long total = pool.stream().mapToLong(RoomTemplate::weight).sum();

        // Each placement consumes 1 door and adds (doors-1) -> a net (doors-2)
        double netDoors = 0;
        for (RoomTemplate t : pool) {
            netDoors += (double) t.weight() / total * (t.doorCount() - 2);
        }
        System.out.printf("   beklenen net kapi degisimi: %+.3f / oda%n", netDoors);
        check("net kapi degisimi pozitif (havuz teorik olarak buyuyebiliyor)",
                netDoors > 0, String.format("%+.3f", netDoors));

        RoomLibrary lib = new RoomLibrary(allTemplates());
        Aabb slot = slotBox(512);
        Vec3i center = new Vec3i(256, 64, 256);

        int runs = 2000, reached = 0, jammed = 0, jammedWithDead = 0, stoppedAtTwo = 0;
        for (long seed = 1; seed <= runs; seed++) {
            DungeonLayout l = naiveFill(lib, Seeds.from(seed), slot, center, 12);
            if (l.size() >= 12) {
                reached++;
            } else {
                jammed++;
                if (l.deadDoorCount() > 0) jammedWithDead++;
                if (l.size() <= 2) stoppedAtTwo++;
            }
        }
        System.out.printf("   hedefe ulasan: %d/%d (%%%.1f)%n", reached, runs, 100.0 * reached / runs);
        System.out.printf("   tikanan: %d -- bunlarin sadece %d tanesinde olu kapi var%n",
                jammed, jammedWithDead);
        System.out.printf("   2 odada duran: %d (%%%.1f) -- kok tek kapili + ilk oda cikmaz%n",
                stoppedAtTwo, 100.0 * stoppedAtTwo / runs);

        check("tikanmalarin cogu CAKISMA degil, frontier tukenmesi",
                jammedWithDead < jammed / 2,
                jammedWithDead + "/" + jammed + " tikanmada olu kapi var");

        double deadendShare = 100.0 * byName(pool, "test_deadend").weight() / total;
        check("2 odada durma orani, cikmaz odanin cekilis oranina esit (mekanizma dogrulandi)",
                Math.abs(100.0 * stoppedAtTwo / runs - deadendShare) < 1.5,
                String.format("durma %%%.1f  vs  cikmaz cekilisi %%%.1f",
                        100.0 * stoppedAtTwo / runs, deadendShare));

        // What happens when dead ends leave the pool -> this is exactly 1D's fix
        List<RoomTemplate> noTerminal = new ArrayList<>();
        for (RoomTemplate t : allTemplates()) {
            if (t.doorCount() != 1 || t.type() != RoomType.NORMAL) noTerminal.add(t);
        }
        RoomLibrary lib2 = new RoomLibrary(noTerminal);
        int reached2 = 0;
        for (long seed = 1; seed <= runs; seed++) {
            if (naiveFill(lib2, Seeds.from(seed), slot, center, 12).size() >= 12) {
                reached2++;
            }
        }
        System.out.printf("   tek kapili odalar havuzdan cikarilirsa: %d/%d (%%%.1f)%n",
                reached2, runs, 100.0 * reached2 / runs);
        check("tek kapili odalari elemek sorunu buyuk olcude cozuyor -> 1D'nin yolu",
                reached2 > reached + runs / 5,
                reached2 + " vs " + reached);
    }

    static void slotBoundsRespected() {
        section("Slot siniri: dar slot'ta hicbir oda disari tasmamali");
        RoomLibrary lib = new RoomLibrary(allTemplates());
        // A narrow 96-block slot: even the 33x33 boss room barely fits, so expect collisions.
        Aabb slot = slotBox(96);
        Vec3i center = new Vec3i(48, 64, 48);

        int escapes = 0, runs = 20;
        for (long seed = 1; seed <= runs; seed++) {
            DungeonLayout l = naiveFill(lib, Seeds.from(seed), slot, center, 20);
            for (LayoutNode n : l.nodes()) {
                Aabb b = n.bounds();
                if (b.minX() < slot.minX() || b.maxX() > slot.maxX()
                        || b.minZ() < slot.minZ() || b.maxZ() > slot.maxZ()) {
                    escapes++;
                }
            }
            if (!l.validate().isEmpty()) escapes++;
        }
        check("dar slot'ta 20 kosumda hicbir oda sinirdan tasmadi", escapes == 0,
                escapes + " tasma");
    }

    static void deadEndMarking() {
        section("OLU kapi: yer kalmayinca kapi olu isaretlenmeli, sessizce yutulmamali");
        RoomLibrary lib = new RoomLibrary(allTemplates());
        // A very narrow slot -> almost nothing fits after the entrance room.
        Aabb slot = slotBox(40);
        DungeonLayout l = naiveFill(lib, Seeds.from(3L), slot, new Vec3i(20, 64, 20), 10);

        int open = l.openDoorCount();
        int dead = l.deadDoorCount();
        System.out.println("   oda=" + l.size() + " olu=" + dead + " bos=" + open);
        check("hedefe ulasilamadi (dar slot bekleniyor)", l.size() < 10, "" + l.size());
        check("olu kapi isaretlendi", dead > 0, "olu=" + dead);
        check("yerlesim yine de tutarli", l.validate().isEmpty(), "" + l.validate());
    }

    static void determinism() {
        section("Tekrarlanabilirlik: ayni seed ayni dungeon");
        RoomLibrary lib = new RoomLibrary(allTemplates());
        Aabb slot = slotBox(512);
        Vec3i center = new Vec3i(256, 64, 256);

        String a = signature(naiveFill(lib, Seeds.from(42L), slot, center, 10));
        String b = signature(naiveFill(lib, Seeds.from(42L), slot, center, 10));
        String c = signature(naiveFill(lib, Seeds.from(43L), slot, center, 10));

        check("seed 42 iki kez ayni sonucu verdi", a.equals(b), "");
        check("seed 43 farkli sonuc verdi", !a.equals(c), "");
    }

    static String signature(DungeonLayout layout) {
        StringBuilder sb = new StringBuilder();
        for (LayoutNode n : layout.nodes()) {
            sb.append(n.template().name()).append('@')
              .append(n.room().origin()).append('/')
              .append(n.room().rotation().steps()).append(';');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- test rooms
    // Single source: Rooms.java. When the set changes, it changes in one place.

    static List<RoomTemplate> allTemplates() {
        return Rooms.all();
    }

    static List<RoomTemplate> normalPool() {
        return Rooms.normalPool();
    }

    static Aabb box(int sizeX, int height, int sizeZ) {
        return Rooms.box(sizeX, height, sizeZ);
    }

    static Aabb slotBox(int size) {
        return Rooms.slotBox(size);
    }

    static RoomTemplate byName(List<RoomTemplate> pool, String name) {
        return pool.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
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
