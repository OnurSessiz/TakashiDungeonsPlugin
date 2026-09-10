import com.takashi.dungeons.generation.Seeds;
import com.takashi.dungeons.loot.CountRange;
import com.takashi.dungeons.loot.ItemClass;
import com.takashi.dungeons.loot.LootTable;
import com.takashi.dungeons.loot.RarityWeights;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

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
        mythicClass();
        chestStream();

        System.out.println("\n==============================================");
        System.out.println("GECEN: " + pass + "   KALAN: " + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 1. taban agirliklar

    static void baseWeights() {
        section("Taban agirliklar: 1000 tabanli, 62.5/26.5/8.5/2/0.5");
        RarityWeights base = RarityWeights.DEFAULT;
        check("toplam 1000", base.total() == 1000, base.total());
        check("common %62.5", pct(base, ItemClass.COMMON, 62.5));
        check("uncommon %26.5", pct(base, ItemClass.UNCOMMON, 26.5));
        check("rare %8.5", pct(base, ItemClass.RARE, 8.5));
        check("ultra_rare %2", pct(base, ItemClass.ULTRA_RARE, 2.0));
        check("legendary %0.5", pct(base, ItemClass.LEGENDARY, 0.5));
        check("mythic SIFIR (taban bolumde yok)", base.weight(ItemClass.MYTHIC) == 0,
                base.weight(ItemClass.MYTHIC));
        check("rare ve ustu toplami 110", base.rareTotal() == 110, base.rareTotal());

        // The reason the top end is this thin, expressed as the number an operator actually feels.
        // A medium dungeon is ~12 chests x ~3 draws; at the 4% ultra rare that shipped first this
        // came out at 2.5 enchanted diamond swords PER RUN, which is equipment, not a find.
        section("Kosum basina ust uc (medium, ~36 cekilis)");
        RarityWeights medium = base.scaled(1.75);
        checkNear("ultra_rare ~1.3 adet", 36 * medium.share(ItemClass.ULTRA_RARE), 1.26);
        checkNear("legendary ~0.3 adet", 36 * medium.share(ItemClass.LEGENDARY), 0.32);
    }

    // ---------------------------------------------------------------- 2. isleyis.md ornegi

    /**
     * The exact table from isleyis.md section Loot. If this fails, either the code or the document
     * is lying, and finding out which is the first thing to do.
     */
    static void hardWorkedExample() {
        section("hard (2.5x) -- isleyis.md'deki ornegin BIREBIR ayni cikmasi");
        RarityWeights hard = RarityWeights.DEFAULT.scaled(2.5);
        check("rare 85 -> 213 (212.5 yukari)", hard.weight(ItemClass.RARE) == 213,
                hard.weight(ItemClass.RARE));
        check("ultra_rare 20 -> 50", hard.weight(ItemClass.ULTRA_RARE) == 50,
                hard.weight(ItemClass.ULTRA_RARE));
        check("legendary 5 -> 13 (12.5 yukari)", hard.weight(ItemClass.LEGENDARY) == 13,
                hard.weight(ItemClass.LEGENDARY));
        check("uncommon DOKUNULMUYOR (265)", hard.weight(ItemClass.UNCOMMON) == 265,
                hard.weight(ItemClass.UNCOMMON));
        check("common 625 -> 459", hard.weight(ItemClass.COMMON) == 459,
                hard.weight(ItemClass.COMMON));
        check("toplam hala 1000", hard.total() == 1000, hard.total());
        check("legendary %0.5 -> %1.3", pct(hard, ItemClass.LEGENDARY, 1.3));
        check("common %62.5 -> %45.9", pct(hard, ItemClass.COMMON, 45.9));
    }

    static void mediumMultiplier() {
        section("medium (1.75x)");
        RarityWeights medium = RarityWeights.DEFAULT.scaled(1.75);
        check("rare 149 (148.75 yukari)", medium.weight(ItemClass.RARE) == 149,
                medium.weight(ItemClass.RARE));
        check("ultra_rare 35", medium.weight(ItemClass.ULTRA_RARE) == 35,
                medium.weight(ItemClass.ULTRA_RARE));
        check("legendary 9 (8.75 yukari yuvarlanir)", medium.weight(ItemClass.LEGENDARY) == 9,
                medium.weight(ItemClass.LEGENDARY));
        check("common 625 - 83 = 542", medium.weight(ItemClass.COMMON) == 542,
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
        check("rare 43 (42.5 yukari)", half.weight(ItemClass.RARE) == 43,
                half.weight(ItemClass.RARE));
        check("ultra_rare 10", half.weight(ItemClass.ULTRA_RARE) == 10,
                half.weight(ItemClass.ULTRA_RARE));
        check("legendary 3 (2.5 yukari)", half.weight(ItemClass.LEGENDARY) == 3,
                half.weight(ItemClass.LEGENDARY));
        check("common 625 -> 679", half.weight(ItemClass.COMMON) == 679,
                half.weight(ItemClass.COMMON));
        check("toplam hala 1000", half.total() == 1000, half.total());
    }

    // ---------------------------------------------------------------- 3. common tukenirse

    /**
     * Common is the only source the multiplier may spend. When the increase is larger than common
     * has, the increments are scaled back proportionally and common lands EXACTLY at zero -- it
     * never goes negative, and the ratios the operator wrote between the rare classes survive.
     */
    static void commonRunsOut() {
        section("common yetmiyorsa: sifira iniyor, negatife DUSMUYOR, oranlar korunuyor");
        RarityWeights thin = RarityWeights.of(50, 250, 100, 40, 10, 0);
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
        RarityWeights flat = RarityWeights.of(0, 300, 400, 200, 100, 0);
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
                new LootTable("x", CountRange.fixed(1), RarityWeights.of(0, 0, 0, 0, 0, 0))));
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
        check("mythic", ItemClass.parse("mythic") == ItemClass.MYTHIC);
        // This check used to spell its unknown class "mythic" -- and mythic then became real.
        // "epic" is a name from other games' vocabularies that this plugin deliberately does NOT
        // have, which is what makes it a safe stand-in for a typo.
        check("bilinmeyen -> null", ItemClass.parse("epic") == null);

        section("ItemClass.keyList(): hata mesajlari enum'dan turetiliyor");
        check("mythic listede", ItemClass.keyList().contains("mythic"), ItemClass.keyList());
        check("her sinif listede", ItemClass.keyList().split(", ").length
                == ItemClass.values().length, ItemClass.keyList());
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
        check("mythic rare", ItemClass.MYTHIC.isRare());
        check("mythic EN NADIR (sonuncu)",
                ItemClass.values()[ItemClass.values().length - 1] == ItemClass.MYTHIC);
    }

    // ---------------------------------------------------------------- 9. mythic

    /**
     * The shipped {@code boss_chest} block, written out here so the file and the code cannot drift
     * apart without one of them being caught -- the same thing this probe already does for the
     * worked example in {@code isleyis.md}.
     *
     * <p>Every expectation is the INTEGER the arithmetic actually lands on, never the ideal value.
     * At weights this small the two differ and the difference is the point: mythic 3 at medium
     * wants 5.25 and gets 5, an effective 1.67x rather than the nominal 1.75x. Asserting 5.25
     * rounded "to 1.75x" would be asserting a number the code never produces.
     */
    static void mythicClass() {
        section("mythic: sadece boss_chest'te, taban bolumde YOK");
        RarityWeights room = RarityWeights.DEFAULT;
        check("room_chest mythic uretemez", room.weight(ItemClass.MYTHIC) == 0);
        for (double multiplier : new double[] {1.0, 1.75, 2.5}) {
            check(multiplier + "x sonrasi da uretemez (sifirin carpani sifir)",
                    room.scaled(multiplier).weight(ItemClass.MYTHIC) == 0);
        }

        section("boss_chest: loot.yml'deki agirliklarin BIREBIR ayni cikmasi");
        RarityWeights boss = RarityWeights.of(617, 150, 160, 55, 15, 3);
        check("toplam 1000", boss.total() == 1000, boss.total());
        check("mythic 3", boss.weight(ItemClass.MYTHIC) == 3, boss.weight(ItemClass.MYTHIC));
        check("mythic legendary'nin 1/5'i", boss.weight(ItemClass.LEGENDARY) == 15
                && boss.weight(ItemClass.MYTHIC) == 3);
        check("rare ve ustu toplami 233 (mythic dahil)", boss.rareTotal() == 233, boss.rareTotal());
        // The ceiling the shipped defaults were once written below. common must be able to pay for
        // the whole increase at the HIGHEST multiplier, or medium and hard produce the same table.
        check("common tavani rahat: 617 >= 1.5 x 233 = 350",
                boss.weight(ItemClass.COMMON) >= Math.ceil(1.5 * boss.rareTotal()),
                boss.weight(ItemClass.COMMON));

        section("boss_chest medium (1.75x) -- yuvarlamanin GERCEK sonucu");
        RarityWeights bossMedium = boss.scaled(1.75);
        check("rare 280", bossMedium.weight(ItemClass.RARE) == 280,
                bossMedium.weight(ItemClass.RARE));
        check("ultra_rare 96 (96.25 asagi)", bossMedium.weight(ItemClass.ULTRA_RARE) == 96,
                bossMedium.weight(ItemClass.ULTRA_RARE));
        check("legendary 26 (26.25 asagi)", bossMedium.weight(ItemClass.LEGENDARY) == 26,
                bossMedium.weight(ItemClass.LEGENDARY));
        check("mythic 5 (5.25 asagi -- etkin carpan 1.67x, ilan edilen 1.75x degil)",
                bossMedium.weight(ItemClass.MYTHIC) == 5, bossMedium.weight(ItemClass.MYTHIC));
        check("common 443", bossMedium.weight(ItemClass.COMMON) == 443,
                bossMedium.weight(ItemClass.COMMON));
        check("toplam hala 1000", bossMedium.total() == 1000, bossMedium.total());

        section("boss_chest hard (2.5x)");
        RarityWeights bossHard = boss.scaled(2.5);
        check("rare 400", bossHard.weight(ItemClass.RARE) == 400, bossHard.weight(ItemClass.RARE));
        check("ultra_rare 138 (137.5 yukari)", bossHard.weight(ItemClass.ULTRA_RARE) == 138,
                bossHard.weight(ItemClass.ULTRA_RARE));
        check("legendary 38 (37.5 yukari)", bossHard.weight(ItemClass.LEGENDARY) == 38,
                bossHard.weight(ItemClass.LEGENDARY));
        check("mythic 8 (7.5 yukari -- etkin carpan 2.67x)",
                bossHard.weight(ItemClass.MYTHIC) == 8, bossHard.weight(ItemClass.MYTHIC));
        check("common 266", bossHard.weight(ItemClass.COMMON) == 266,
                bossHard.weight(ItemClass.COMMON));
        check("toplam hala 1000", bossHard.total() == 1000, bossHard.total());
        check("common tavana CARPMIYOR", bossHard.weight(ItemClass.COMMON) > 0);

        section("mythic zorlukla artiyor ve legendary'yi HIC gecmiyor");
        check("3 -> 5 -> 8", boss.weight(ItemClass.MYTHIC) < bossMedium.weight(ItemClass.MYTHIC)
                && bossMedium.weight(ItemClass.MYTHIC) < bossHard.weight(ItemClass.MYTHIC));
        for (RarityWeights weights : new RarityWeights[] {boss, bossMedium, bossHard}) {
            check("mythic < legendary < ultra_rare",
                    weights.weight(ItemClass.MYTHIC) < weights.weight(ItemClass.LEGENDARY)
                            && weights.weight(ItemClass.LEGENDARY)
                                    < weights.weight(ItemClass.ULTRA_RARE), weights);
        }

        section("sandik basina mythic ihtimali (4-6 cekilis)");
        checkNear("easy  ~%1.5", chestChance(boss, ItemClass.MYTHIC), 1.49);
        checkNear("medium ~%2.5", chestChance(bossMedium, ItemClass.MYTHIC), 2.47);
        checkNear("hard  ~%3.9", chestChance(bossHard, ItemClass.MYTHIC), 3.95);
    }

    // ---------------------------------------------------------------- 10. loot akisi (4B)

    /**
     * Loot and mobs both index rooms by node id. Had loot used the two-argument
     * {@code Seeds.derive} it would have been handed the IDENTICAL sequence the mob populator
     * already consumed for that room -- nothing would look broken, and the two systems would be
     * locked together forever: the room that drew high for its mob class would draw high for its
     * loot rarity, in every dungeon.
     *
     * <p>This is why {@code LootPopulator.CHEST_STREAM} exists, and it is checked here because it
     * is the sort of fault no test of either system on its own would ever see.
     */
    static void chestStream() {
        section("Loot ve mob AYRI akis kullaniyor (ayni tohum, ayni oda)");
        long seed = 987654321L;
        int node = 7;
        List<Integer> mobs = draws(Seeds.derive(seed, node), 50);
        List<Integer> loot = draws(Seeds.derive(seed, node, 0x10_07L), 50);
        check("iki dizi ayni DEGIL", !mobs.equals(loot));
        check("iki argumanli derive akis 0 ile ayni",
                draws(Seeds.derive(seed, node), 50).equals(draws(Seeds.derive(seed, node, 0), 50)));

        section("Loot akisi hala tekrarlanabilir");
        check("ayni tohum+oda -> ayni dizi", draws(Seeds.derive(seed, node, 0x10_07L), 50)
                .equals(draws(Seeds.derive(seed, node, 0x10_07L), 50)));
        check("farkli oda -> farkli dizi", !draws(Seeds.derive(seed, node, 0x10_07L), 50)
                .equals(draws(Seeds.derive(seed, node + 1, 0x10_07L), 50)));
        check("farkli tohum -> farkli dizi", !draws(Seeds.derive(seed, node, 0x10_07L), 50)
                .equals(draws(Seeds.derive(seed + 1, node, 0x10_07L), 50)));

        // The failure this guards against is a stream constant chosen so that (index, stream) can
        // collide with (index', stream') -- two rooms sharing a chest layout across systems.
        section("Komsu oda/akis kombinasyonlari carpismiyor");
        Set<List<Integer>> seen = new java.util.HashSet<>();
        boolean unique = true;
        for (int id = 0; id < 40; id++) {
            unique &= seen.add(draws(Seeds.derive(seed, id), 8));
            unique &= seen.add(draws(Seeds.derive(seed, id, 0x10_07L), 8));
        }
        check("80 akisin hepsi farkli", unique);

        // 4C adds a third consumer indexing by node id: the boss reward chest, which uses the boss
        // room's node. Sharing CHEST_STREAM would tie the boss's hoard to whatever the room chests
        // in that same room drew -- the same fault, one phase later.
        section("Boss odulu de AYRI akis (4C)");
        List<Integer> chest = draws(Seeds.derive(seed, node, 0x10_07L), 50);
        List<Integer> reward = draws(Seeds.derive(seed, node, 0x4C_05L), 50);
        check("odul != oda sandigi", !reward.equals(chest));
        check("odul != mob", !reward.equals(mobs));
        check("odul tekrarlanabilir",
                reward.equals(draws(Seeds.derive(seed, node, 0x4C_05L), 50)));
    }

    static List<Integer> draws(RandomGenerator random, int count) {
        List<Integer> values = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(random.nextInt(1000));
        }
        return values;
    }

    /**
     * The chance that a chest of 4-6 draws contains at least one item of this class, as a
     * percentage. This is the number a player experiences; the per-draw weight is not.
     */
    static double chestChance(RarityWeights weights, ItemClass itemClass) {
        double miss = 1.0 - weights.share(itemClass);
        double total = 0;
        for (int draws = 4; draws <= 6; draws++) {
            total += 1.0 - Math.pow(miss, draws);
        }
        return 100.0 * total / 3.0;
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

    /** For a computed probability: 0.05 points of slack, so a rounding change shows up. */
    static void checkNear(String what, double actual, double expected) {
        check(what + " (" + String.format("%.2f", actual) + ")",
                Math.abs(actual - expected) < 0.05, actual);
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
