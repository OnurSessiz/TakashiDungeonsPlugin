import com.takashi.dungeons.loot.CountRange;
import com.takashi.dungeons.loot.ItemClass;
import com.takashi.dungeons.loot.LootTable;
import com.takashi.dungeons.loot.RarityWeights;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Server-free verification of the phase 4A loot draw.
 *
 * The rarity arithmetic is the one piece of this system that is easy to get wrong and impossible
 * to see on a server: a distribution that is eight percent off looks exactly like luck, and it
 * would take hundreds of chests and a spreadsheet to tell the difference. So it is pure Java and
 * it is checked here, against the worked example the project wrote down in isleyis.md before any
 * loot code existed.
 *
 * The trap being guarded against: multiplying EVERY class by the difficulty multiplier changes
 * nothing at all, because a draw normalises. The rule that works is "rare and above only, paid for
 * out of common" -- and that rule has an edge (common can run out) which is checked here too.
 */
public class LootProbe {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== FAZ 4A loot cekilisi dogrulamasi (sunucusuz) ===\n");

        baseWeights();
        hardWorkedExample();
        mediumMultiplier();
        neutralMultiplier();
        multiplierBelowOne();
        commonRunsOut();
        noCommonAtAll();
        empiricalDistribution();
        determinism();
        countRanges();
        tableRules();
        parsing();
        classBoundary();

        System.out.println("\n==============================================");
        System.out.println("GECEN: " + pass + "   KALAN: " + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 1. taban agirliklar

    static void baseWeights() {
        section("Taban agirliklar: 1000 tabanli, 60/25/10/4/1");
        RarityWeights base = RarityWeights.DEFAULT;
        check("toplam 1000", base.total() == 1000, base.total());
        check("common %60", pct(base, ItemClass.COMMON, 60.0));
        check("uncommon %25", pct(base, ItemClass.UNCOMMON, 25.0));
        check("rare %10", pct(base, ItemClass.RARE, 10.0));
        check("ultra_rare %4", pct(base, ItemClass.ULTRA_RARE, 4.0));
        check("legendary %1", pct(base, ItemClass.LEGENDARY, 1.0));
        check("rare ve ustu toplami 150", base.rareTotal() == 150, base.rareTotal());
    }

    // ---------------------------------------------------------------- 2. isleyis.md ornegi

    /**
     * The exact table from isleyis.md section Loot. If this fails, either the code or the document
     * is lying, and finding out which is the first thing to do.
     */
    static void hardWorkedExample() {
        section("hard (2.5x) -- isleyis.md'deki ornegin BIREBIR ayni cikmasi");
        RarityWeights hard = RarityWeights.DEFAULT.scaled(2.5);
        check("rare 100 -> 250", hard.weight(ItemClass.RARE) == 250, hard.weight(ItemClass.RARE));
        check("ultra_rare 40 -> 100", hard.weight(ItemClass.ULTRA_RARE) == 100,
                hard.weight(ItemClass.ULTRA_RARE));
        check("legendary 10 -> 25", hard.weight(ItemClass.LEGENDARY) == 25,
                hard.weight(ItemClass.LEGENDARY));
        check("uncommon DOKUNULMUYOR (250)", hard.weight(ItemClass.UNCOMMON) == 250,
                hard.weight(ItemClass.UNCOMMON));
        check("common 600 -> 375", hard.weight(ItemClass.COMMON) == 375,
                hard.weight(ItemClass.COMMON));
        check("toplam hala 1000", hard.total() == 1000, hard.total());
        check("legendary %1 -> %2.5", pct(hard, ItemClass.LEGENDARY, 2.5));
        check("common %60 -> %37.5", pct(hard, ItemClass.COMMON, 37.5));
    }

    static void mediumMultiplier() {
        section("medium (1.75x)");
        RarityWeights medium = RarityWeights.DEFAULT.scaled(1.75);
        check("rare 175", medium.weight(ItemClass.RARE) == 175, medium.weight(ItemClass.RARE));
        check("ultra_rare 70", medium.weight(ItemClass.ULTRA_RARE) == 70,
                medium.weight(ItemClass.ULTRA_RARE));
        check("legendary 18 (17.5 yukari yuvarlanir)", medium.weight(ItemClass.LEGENDARY) == 18,
                medium.weight(ItemClass.LEGENDARY));
        check("common 1000 - 263 - 250 = 487", medium.weight(ItemClass.COMMON) == 487,
                medium.weight(ItemClass.COMMON));
        check("toplam hala 1000", medium.total() == 1000, medium.total());
    }

    static void neutralMultiplier() {
        section("1.0x hicbir seyi degistirmiyor");
        RarityWeights same = RarityWeights.DEFAULT.scaled(1.0);
        check("agirliklar ayni", same.equals(RarityWeights.DEFAULT), same);
    }

    /**
     * Below 1.0 the weight moves the other way -- into common, not out of it. Nobody has asked for
     * this, but a multiplier field accepts any number and the arithmetic must not fall apart on a
     * value the operator is allowed to write.
     */
    static void multiplierBelowOne() {
        section("0.5x ters yone calisiyor (agirlik common'a DONUYOR)");
        RarityWeights half = RarityWeights.DEFAULT.scaled(0.5);
        check("rare 50", half.weight(ItemClass.RARE) == 50, half.weight(ItemClass.RARE));
        check("ultra_rare 20", half.weight(ItemClass.ULTRA_RARE) == 20,
                half.weight(ItemClass.ULTRA_RARE));
        check("legendary 5", half.weight(ItemClass.LEGENDARY) == 5,
                half.weight(ItemClass.LEGENDARY));
        check("common 600 -> 675", half.weight(ItemClass.COMMON) == 675,
                half.weight(ItemClass.COMMON));
        check("toplam hala 450+... = 1000", half.total() == 1000, half.total());
    }

    // ---------------------------------------------------------------- 3. common tukenirse

    /**
     * Common is the only source the multiplier may spend. When the increase is larger than common
     * has, the increments are scaled back proportionally and common lands EXACTLY at zero -- it
     * never goes negative, and the ratios the operator wrote between the rare classes survive.
     */
    static void commonRunsOut() {
        section("common yetmiyorsa: sifira iniyor, negatife DUSMUYOR, oranlar korunuyor");
        RarityWeights thin = RarityWeights.of(50, 250, 100, 40, 10);
        int before = thin.total();
        RarityWeights scaled = thin.scaled(5.0);
        check("common tam sifir", scaled.weight(ItemClass.COMMON) == 0,
                scaled.weight(ItemClass.COMMON));
        check("toplam korunuyor (" + before + ")", scaled.total() == before, scaled.total());
        check("uncommon hala 250", scaled.weight(ItemClass.UNCOMMON) == 250,
                scaled.weight(ItemClass.UNCOMMON));
        check("rare buyudu (100 -> " + scaled.weight(ItemClass.RARE) + ")",
                scaled.weight(ItemClass.RARE) > 100, scaled.weight(ItemClass.RARE));
        // The 100/40/10 ordering must survive the trim: a budget shortfall may make the increase
        // smaller, never reorder what the operator wrote.
        check("rare > ultra_rare > legendary sirasi korunuyor",
                scaled.weight(ItemClass.RARE) > scaled.weight(ItemClass.ULTRA_RARE)
                        && scaled.weight(ItemClass.ULTRA_RARE) > scaled.weight(ItemClass.LEGENDARY),
                scaled);
        check("hicbir agirlik negatif degil", noNegatives(scaled), scaled);
    }

    static void noCommonAtAll() {
        section("common HIC yoksa: zorluk carpani hicbir sey yapamiyor (belgelenmis davranis)");
        RarityWeights flat = RarityWeights.of(0, 300, 400, 200, 100);
        RarityWeights scaled = flat.scaled(2.5);
        check("dagilim aynen kaliyor", scaled.equals(flat), scaled);
        check("toplam korunuyor", scaled.total() == flat.total(), scaled.total());
    }

    // ---------------------------------------------------------------- 4. ampirik dagilim

    /**
     * 200,000 draws against the declared weights, the same evidence GenProbe produces for template
     * selection. Tolerance is 0.5 percentage points: wide enough that a fair sample never trips it,
     * narrow enough to catch an off-by-one in the cumulative walk.
     */
    static void empiricalDistribution() {
        section("200.000 cekilis: gozlenen dagilim ilan edilen agirliklarla ortusuyor");
        int draws = 200_000;
        for (double multiplier : new double[] {1.0, 1.75, 2.5}) {
            RarityWeights weights = RarityWeights.DEFAULT.scaled(multiplier);
            Map<ItemClass, Integer> seen = new EnumMap<>(ItemClass.class);
            Random random = new Random(20260906L);
            for (int i = 0; i < draws; i++) {
                seen.merge(weights.pick(random), 1, Integer::sum);
            }
            double worst = 0;
            ItemClass worstClass = ItemClass.COMMON;
            for (ItemClass itemClass : ItemClass.values()) {
                double observed = 100.0 * seen.getOrDefault(itemClass, 0) / draws;
                double expected = 100.0 * weights.share(itemClass);
                if (Math.abs(observed - expected) > worst) {
                    worst = Math.abs(observed - expected);
                    worstClass = itemClass;
                }
            }
            check(multiplier + "x -> en buyuk sapma " + String.format("%.2f", worst)
                    + " puan (" + worstClass.key() + ")", worst < 0.5, worst);
        }
        section("hicbir cekilis kaybolmuyor (toplam = cekilis sayisi)");
        Map<ItemClass, Integer> seen = new EnumMap<>(ItemClass.class);
        Random random = new Random(7L);
        for (int i = 0; i < 10_000; i++) {
            seen.merge(RarityWeights.DEFAULT.pick(random), 1, Integer::sum);
        }
        int total = seen.values().stream().mapToInt(Integer::intValue).sum();
        check("10.000 cekilisin hepsi bir sinifa dustu", total == 10_000, total);
    }

    static void determinism() {
        section("Ayni tohum ayni diziyi veriyor (uretimin tekrarlanabilirligi buna dayaniyor)");
        List<ItemClass> first = sequence(4242L, 500);
        List<ItemClass> second = sequence(4242L, 500);
        List<ItemClass> other = sequence(4243L, 500);
        check("ayni tohum -> ayni dizi", first.equals(second));
        check("farkli tohum -> farkli dizi", !first.equals(other));
    }

    static List<ItemClass> sequence(long seed, int count) {
        Random random = new Random(seed);
        List<ItemClass> drawn = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            drawn.add(RarityWeights.DEFAULT.pick(random));
        }
        return drawn;
    }

    // ---------------------------------------------------------------- 5. sayi araliklari

    static void countRanges() {
        section("CountRange: iki uc de dahil, tek sayi sifir genislikli aralik");
        CountRange fixed = CountRange.fixed(3);
        Random random = new Random(1L);
        boolean alwaysThree = true;
        for (int i = 0; i < 200; i++) {
            alwaysThree &= fixed.roll(random) == 3;
        }
        check("sabit aralik hep ayni sayi", alwaysThree);

        CountRange range = new CountRange(2, 4);
        boolean sawMin = false, sawMax = false, inBounds = true;
        for (int i = 0; i < 2000; i++) {
            int rolled = range.roll(random);
            sawMin |= rolled == 2;
            sawMax |= rolled == 4;
            inBounds &= rolled >= 2 && rolled <= 4;
        }
        check("hep sinirlar icinde", inBounds);
        check("alt sinir cikiyor", sawMin);
        check("ust sinir da cikiyor (dahil)", sawMax);

        check("[2, 4] okunuyor", CountRange.parse(List.of(2, 4), "t", null).equals(range));
        check("tek sayi okunuyor", CountRange.parse(5, "t", null).equals(CountRange.fixed(5)));
        check("yoksa fallback donuyor", CountRange.parse(null, "t", fixed) == fixed);
        check("ondalik reddediliyor", throwsOn(() -> CountRange.parse(2.5, "t", null)));
        check("max < min reddediliyor", throwsOn(() -> CountRange.parse(List.of(9, 2), "t", null)));
        check("negatif reddediliyor", throwsOn(() -> CountRange.parse(-1, "t", null)));
        check("uc elemanli liste reddediliyor",
                throwsOn(() -> CountRange.parse(List.of(1, 2, 3), "t", null)));
    }

    // ---------------------------------------------------------------- 6. tablo

    static void tableRules() {
        section("LootTable");
        LootTable table = new LootTable("room_chest", new CountRange(2, 4), RarityWeights.DEFAULT);
        Random random = new Random(9L);
        boolean inBounds = true;
        for (int i = 0; i < 500; i++) {
            int rolled = table.rollCount(random);
            inBounds &= rolled >= 2 && rolled <= 4;
        }
        check("cekilis sayisi sinirlar icinde", inBounds);
        check("weightsFor = rarity.scaled", table.weightsFor(2.5)
                .equals(RarityWeights.DEFAULT.scaled(2.5)));
        check("bos rarity reddediliyor", throwsOn(() ->
                new LootTable("x", CountRange.fixed(1), RarityWeights.of(0, 0, 0, 0, 0))));
        check("rolls'suz tablo reddediliyor",
                throwsOn(() -> new LootTable("x", null, RarityWeights.DEFAULT)));
        check("idsiz tablo reddediliyor",
                throwsOn(() -> new LootTable(" ", CountRange.fixed(1), RarityWeights.DEFAULT)));
    }

    // ---------------------------------------------------------------- 7. ayristirma

    static void parsing() {
        section("rarity blogu: yazim hatasi SESSIZCE atlanmiyor");
        Map<String, Object> good = new LinkedHashMap<>();
        good.put("common", 500);
        good.put("legendary", 500);
        RarityWeights parsed = RarityWeights.parse(good, "t", null);
        check("okunan agirliklar dogru", parsed.weight(ItemClass.COMMON) == 500
                && parsed.weight(ItemClass.LEGENDARY) == 500, parsed);

        Map<String, Object> typo = Map.of("comon", 500);
        check("bilinmeyen sinif adi reddediliyor",
                throwsOn(() -> RarityWeights.parse(typo, "t", null)));
        Map<String, Object> negative = Map.of("common", -5);
        check("negatif agirlik reddediliyor",
                throwsOn(() -> RarityWeights.parse(negative, "t", null)));
        check("bos blok reddediliyor", throwsOn(() -> RarityWeights.parse(Map.of(), "t", null)));
        check("blok yoksa fallback donuyor",
                RarityWeights.parse(null, "t", RarityWeights.DEFAULT) == RarityWeights.DEFAULT);
        check("negatif carpan reddediliyor",
                throwsOn(() -> RarityWeights.DEFAULT.scaled(-1.0)));
        section("ItemClass.parse ayirici toleransi");
        check("ultra_rare", ItemClass.parse("ultra_rare") == ItemClass.ULTRA_RARE);
        check("ultra-rare", ItemClass.parse("ultra-rare") == ItemClass.ULTRA_RARE);
        check("ULTRA RARE", ItemClass.parse("ULTRA RARE") == ItemClass.ULTRA_RARE);
        check("bilinmeyen -> null", ItemClass.parse("mythic") == null);
    }

    /**
     * The class order is load-bearing: isRare() draws the difficulty rule's line at RARE, so
     * reordering the enum would silently rewrite which classes difficulty touches.
     */
    static void classBoundary() {
        section("isRare() sinirinin yeri (zorluk kuralinin tamami bu satirda)");
        check("common rare degil", !ItemClass.COMMON.isRare());
        check("uncommon rare degil", !ItemClass.UNCOMMON.isRare());
        check("rare rare", ItemClass.RARE.isRare());
        check("ultra_rare rare", ItemClass.ULTRA_RARE.isRare());
        check("legendary rare", ItemClass.LEGENDARY.isRare());
    }

    // ---------------------------------------------------------------- yardimcilar

    static boolean pct(RarityWeights weights, ItemClass itemClass, double expected) {
        return Math.abs(weights.share(itemClass) * 100 - expected) < 0.001;
    }

    static boolean noNegatives(RarityWeights weights) {
        for (ItemClass itemClass : ItemClass.values()) {
            if (weights.weight(itemClass) < 0) {
                return false;
            }
        }
        return true;
    }

    static boolean throwsOn(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    static void section(String title) {
        System.out.println("\n-- " + title);
    }

    static void check(String what, boolean ok) {
        check(what, ok, null);
    }

    static void check(String what, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.println("   [OK] " + what);
        } else {
            fail++;
            System.out.println("   [KALDI] " + what + (actual == null ? "" : "  -> " + actual));
        }
    }
}
