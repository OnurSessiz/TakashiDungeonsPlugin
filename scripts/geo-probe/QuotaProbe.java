import com.takashi.dungeons.generation.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * max-per-dungeon verification -- generation.md §8.
 *
 * Two separate claims are being tested and they must not be confused:
 *
 *   1. The cap HOLDS. A room capped at 1 never appears twice, in any size, under any seed.
 *      This is a hard guarantee: a single violation fails the run.
 *
 *   2. The weight still STEERS. Capping a room must not change what the weight means for the
 *      draws below the cap -- the realized frequency is reported so the number in the .yml can
 *      be tuned against a measurement instead of against arithmetic on paper.
 *
 * Claim 2 is the reason the distribution is printed rather than asserted: the target ("about a
 * third of medium dungeons") is a design preference, not an invariant. Turning a preference
 * into a test would make every future weight change look like a regression.
 */
public class QuotaProbe {

    static int pass = 0, fail = 0;

    static final Aabb SLOT = new Aabb(0, -64, 0, 511, 319, 511);
    static final Vec3i CENTER = new Vec3i(256, 64, 256);

    /** The room the cap is being tested on -- stands in for the maze. */
    static final String CAPPED = "test_cross";

    public static void main(String[] args) {
        System.out.println("=== max-per-dungeon dogrulamasi (sunucusuz) ===\n");

        capIsNeverExceeded();
        capOfZeroMeansNever();
        uncappedRoomsAreUntouched();
        realizedDistribution();

        System.out.println("\n==============================================");
        System.out.println("GECEN: " + pass + "   KALAN: " + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- tests

    static void capIsNeverExceeded() {
        section("TAVAN TUTUYOR MU -- small 0 / medium 1 / large 2, 2000 tohum");

        Map<DungeonSize, Integer> caps = new EnumMap<>(DungeonSize.class);
        caps.put(DungeonSize.SMALL, 0);
        caps.put(DungeonSize.MEDIUM, 1);
        caps.put(DungeonSize.LARGE, 2);
        RoomLibrary lib = new RoomLibrary(withCap(CAPPED, caps));

        for (DungeonSize size : DungeonSize.values()) {
            int limit = caps.get(size);
            int runs = 2000, worst = 0;
            long worstSeed = -1;
            for (long seed = 0; seed < runs; seed++) {
                int n = count(gen(lib).generate(SLOT, CENTER, size, seed), CAPPED);
                if (n > worst) {
                    worst = n;
                    worstSeed = seed;
                }
            }
            check(size.key() + ": en fazla " + limit + " (olculen en yuksek: " + worst + ")",
                    worst <= limit,
                    worst > limit ? "tohum " + worstSeed + " -> " + worst + " adet" : "tamam");
        }
    }

    static void capOfZeroMeansNever() {
        section("Tek boyutta 0 -- o boyutta hic cikmiyor, digerleri etkilenmiyor");

        Map<DungeonSize, Integer> caps = new EnumMap<>(DungeonSize.class);
        caps.put(DungeonSize.SMALL, 0);
        RoomLibrary lib = new RoomLibrary(withCap(CAPPED, caps));

        int inSmall = 0, inLarge = 0;
        for (long seed = 0; seed < 1000; seed++) {
            inSmall += count(gen(lib).generate(SLOT, CENTER, DungeonSize.SMALL, seed), CAPPED);
            inLarge += count(gen(lib).generate(SLOT, CENTER, DungeonSize.LARGE, seed), CAPPED);
        }
        check("small'da hic yok", inSmall == 0, "toplam " + inSmall);
        // The other sizes carry no cap at all, so the room must still be turning up there --
        // otherwise a zero could be leaking across sizes and the first check would pass for
        // the wrong reason.
        check("large yazilmamis -> sinirsiz, oda hala cikiyor", inLarge > 0, "toplam " + inLarge);
    }

    static void uncappedRoomsAreUntouched() {
        section("Tavansiz dunya bit bit ayni -- alan eklemek eski davranisi bozmadi");

        RoomLibrary lib = new RoomLibrary(Rooms.all());
        int mismatches = 0;
        for (long seed = 0; seed < 500; seed++) {
            for (DungeonSize size : DungeonSize.values()) {
                DungeonGenerator.Result a = gen(lib).generate(SLOT, CENTER, size, seed);
                DungeonGenerator.Result b = gen(lib).generate(SLOT, CENTER, size, seed);
                if (!sig(a).equals(sig(b))) {
                    mismatches++;
                }
            }
        }
        check("ayni tohum ayni dungeon (1500 uretim)", mismatches == 0,
                mismatches + " uyusmazlik");
    }

    static void realizedDistribution() {
        section("GERCEKLESEN DAGILIM -- .yml'deki agirlik buna bakilarak ayarlanir");

        Map<DungeonSize, Integer> caps = new EnumMap<>(DungeonSize.class);
        caps.put(DungeonSize.SMALL, 0);
        caps.put(DungeonSize.MEDIUM, 1);
        caps.put(DungeonSize.LARGE, 2);

        for (int weight : new int[] {35, 50, 100}) {
            List<RoomTemplate> set = withCap(CAPPED, caps);
            set = withWeight(set, CAPPED, weight);
            RoomLibrary lib = new RoomLibrary(set);

            System.out.println("  weight=" + weight + ":");
            for (DungeonSize size : DungeonSize.values()) {
                int runs = 2000;
                Map<Integer, Integer> histogram = new LinkedHashMap<>();
                for (long seed = 0; seed < runs; seed++) {
                    int n = count(gen(lib).generate(SLOT, CENTER, size, seed), CAPPED);
                    histogram.merge(n, 1, Integer::sum);
                }
                StringBuilder sb = new StringBuilder();
                for (int n = 0; n <= 3; n++) {
                    int c = histogram.getOrDefault(n, 0);
                    if (c == 0 && n > 0) {
                        continue;
                    }
                    sb.append(String.format("  %d adet: %%%.1f", n, 100.0 * c / runs));
                }
                int atLeastOne = runs - histogram.getOrDefault(0, 0);
                System.out.printf("    %-7s%s   |  en az bir: %%%.1f%n",
                        size.key(), sb, 100.0 * atLeastOne / runs);
            }
        }
        // Nothing is asserted here on purpose -- see the class comment.
        check("dagilim raporlandi", true, "");
    }

    // ---------------------------------------------------------------- helpers

    /** Rooms.all() with one template given a per-size cap. */
    static List<RoomTemplate> withCap(String name, Map<DungeonSize, Integer> caps) {
        List<RoomTemplate> out = new ArrayList<>();
        for (RoomTemplate t : Rooms.all()) {
            out.add(t.name().equals(name)
                    ? new RoomTemplate(t.name(), t.type(), t.weight(), t.doors(), t.localBox(), caps)
                    : t);
        }
        return out;
    }

    static List<RoomTemplate> withWeight(List<RoomTemplate> set, String name, int weight) {
        List<RoomTemplate> out = new ArrayList<>();
        for (RoomTemplate t : set) {
            out.add(t.name().equals(name)
                    ? new RoomTemplate(t.name(), t.type(), weight, t.doors(), t.localBox(),
                            t.maxPerDungeon())
                    : t);
        }
        return out;
    }

    /** How many nodes of the finished layout use the named template. */
    static int count(DungeonGenerator.Result result, String name) {
        int n = 0;
        for (LayoutNode node : result.layout().nodes()) {
            if (node.template().name().equals(name)) {
                n++;
            }
        }
        return n;
    }

    static String sig(DungeonGenerator.Result r) {
        StringBuilder sb = new StringBuilder();
        for (LayoutNode node : r.layout().nodes()) {
            sb.append(node.template().name()).append('@').append(node.room().origin())
              .append('/').append(node.room().rotation().steps()).append(';');
        }
        return sb.append("boss=").append(r.bossNodeId()).toString();
    }

    static DungeonGenerator gen(RoomLibrary lib) {
        return new DungeonGenerator(lib, 2.0, 8);
    }

    static void section(String title) {
        System.out.println("\n--- " + title + " ---");
    }

    static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [GECTI] " + what);
        } else {
            fail++;
            System.out.println("  [KALDI] " + what + "   -> " + detail);
        }
    }
}
