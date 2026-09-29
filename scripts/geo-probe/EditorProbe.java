import com.takashi.dungeons.editor.Draft;
import com.takashi.dungeons.editor.Num;
import com.takashi.dungeons.editor.RangeField;
import com.takashi.dungeons.yaml.YamlPatch;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-free verification of the phase 9 editor core: the draft, the range field, the numbers.
 *
 * The menus themselves need a player; the rules under them do not, and they are the part that is
 * easy to get subtly wrong - a range whose min passes its max, a stepper that writes
 * 0.24000000000000002, a save that quietly overwrites a hand edit.
 */
public class EditorProbe {

    static int pass = 0, fail = 0;
    static Path root;

    public static void main(String[] args) throws Exception {
        root = Path.of(args.length > 0 ? args[0] : ".");
        System.out.println("=== FAZ 9 editor cekirdegi (sunucusuz) ===\n");

        numbers();
        draftBasics();
        ranges();
        rangeSpelling();
        conflicts();
        endToEnd();

        System.out.println("\n==============================================");
        System.out.println("GECEN: " + pass + "   KALAN: " + fail);
        if (fail > 0) System.exit(1);
    }

    static void numbers() {
        section("Sayilar");
        BigDecimal speed = Num.of(0.23);
        for (int i = 0; i < 7; i++) speed = speed.add(new BigDecimal("0.01"));
        check("0.23 + 7 x 0.01 = 0.3 tam (kayan nokta kalintisi yok)", Num.clean(speed).equals(0.3),
                Num.clean(speed));
        check("tam sayi Long olarak yaziliyor", Num.clean(new BigDecimal("20.000")).equals(20L));
        check("Integer 20 == Double 20.0 (kanonik)", Num.canonical(20).equals(Num.canonical(20.0)));
        check("[16, 22] == [16.0, 22.0] (kanonik)",
                Num.canonical(List.of(16, 22)).equals(Num.canonical(List.of(16.0, 22.0))));
        check("virgullu girdi kabul (0,25)", Num.parse("0,25").compareTo(new BigDecimal("0.25")) == 0);
        check("sayi olmayan girdi reddediliyor", throwsAny(() -> Num.parse("abc")));
        check("describe: aralik", Num.describe(List.of(16, 22)).equals("[16, 22]"));
    }

    static void draftBasics() {
        section("Taslak");
        Draft draft = draft(Map.of("weight", 150));
        check("baslangicta temiz", !draft.dirty());
        draft.set("weight", 175);
        check("degisince kirli", draft.dirty() && draft.isChanged("weight"));
        draft.set("weight", 150);
        check("eski degere donunce yine temiz", !draft.dirty());
        draft.set("weight", 150.0);
        check("150.0 da 150 sayiliyor (degisiklik degil)", !draft.dirty());
        draft.set("speed", 0.25);
        check("olmayan alani eklemek degisiklik", draft.isChanged("speed"));
        draft.set("speed", null);
        check("...ve geri silmek temiz", !draft.dirty());
        draft.set("weight", 175);
        check("ozet 'weight 150 → 175'", draft.summary().equals("weight 150 → 175"), draft.summary());
        draft.commit();
        check("commit sonrasi temiz ve yeni baslangic 175",
                !draft.dirty() && Num.canonical(draft.original("weight")).equals(Num.canonical(175)));
        draft.set("weight", 200);
        draft.discard();
        check("discard geri aliyor", !draft.dirty() && Num.canonical(draft.get("weight")).equals(Num.canonical(175)));
    }

    static final RangeField HEALTH = RangeField.decimal("health", "1", "1", "20");
    static final RangeField SPEED = RangeField.decimal("speed", "0.01", "0", "0.23");
    static final RangeField AMOUNT = RangeField.whole("amount", 1, 1, 1);

    static void ranges() {
        section("Aralik: min max'i gecemez");
        Draft draft = draft(Map.of("health", List.of(16, 22)));
        HEALTH.adjustMin(draft, new BigDecimal("10"));
        check("min 16+10=26 -> max da 26'ya suruklendi", range(draft, HEALTH).equals("26 - 26")
                || range(draft, HEALTH).equals("26"), range(draft, HEALTH));
        HEALTH.adjustMax(draft, new BigDecimal("-10"));
        check("max 26-10=16 -> min de 16'ya indi", range(draft, HEALTH).startsWith("16"), range(draft, HEALTH));
        HEALTH.setMin(draft, new BigDecimal("-5"));
        check("taban: health 1'in altina inmiyor", HEALTH.read(draft)[0].compareTo(BigDecimal.ONE) == 0,
                range(draft, HEALTH));
        check("elle yazilan alti-taban reddediliyor", throwsAny(() -> HEALTH.check(new BigDecimal("0"))));

        Draft speed = draft(new HashMap<>());
        check("yok olan stat 'present' degil", !SPEED.present(speed));
        SPEED.create(speed);
        check("create -> 0.23'ten basliyor", range(speed, SPEED).equals("0.23"), range(speed, SPEED));
        SPEED.adjustMax(speed, new BigDecimal("0.02"));
        check("hiz 0.23-0.25 (tam ondalik)", range(speed, SPEED).equals("0.23 - 0.25"), range(speed, SPEED));
        check("yazilan deger [0.23, 0.25]", speed.get("speed").equals(List.of(0.23, 0.25)), speed.get("speed"));
        SPEED.clear(speed);
        check("clear -> alan silinecek (dogal deger)", speed.get("speed") == null && !speed.dirty());

        Draft amount = draft(Map.of("amount", List.of(1, 3)));
        AMOUNT.adjustMax(amount, new BigDecimal("1"));
        check("adet tam sayi: [1, 4]", amount.get("amount").equals(List.of(1L, 4L)), amount.get("amount"));
        check("adette ondalik reddediliyor", throwsAny(() -> AMOUNT.check(new BigDecimal("1.5"))));
    }

    static void rangeSpelling() {
        section("Aralik: dosyanin yazimi korunuyor");
        Draft scalar = draft(Map.of("health", 20));
        HEALTH.adjustMin(scalar, BigDecimal.ONE);
        check("tek sayi olarak yazilmis alan, min=max iken tek sayi kaliyor",
                scalar.get("health").equals(21L), scalar.get("health"));
        HEALTH.adjustMax(scalar, BigDecimal.ONE);
        check("min != max olunca liste", scalar.get("health").equals(List.of(21L, 22L)), scalar.get("health"));
        HEALTH.adjustMin(scalar, BigDecimal.ONE);
        check("tekrar esitlenince tek sayiya donuyor", scalar.get("health").equals(22L), scalar.get("health"));

        Draft list = draft(Map.of("health", List.of(20, 24)));
        HEALTH.setMax(list, new BigDecimal("20"));
        check("liste olarak yazilmis alan, min=max iken de liste ([20, 20])",
                list.get("health").equals(List.of(20L, 20L)), list.get("health"));
    }

    static void conflicts() {
        section("Iyimser kilit");
        String disk = "mobs:\n  z:\n    weight: 150\n    health: [16, 22]\n";
        Draft draft = new Draft(List.of("mobs", "z"), Map.of("weight", 150, "health", List.of(16, 22)));
        draft.set("weight", 175);
        check("disk degismediyse cakisma yok", draft.conflicts(YamlPatch.of(disk)).isEmpty());
        String handEditedOther = disk.replace("[16, 22]", "[30, 40]");
        check("DOKUNULMAYAN alan elle degistiyse cakisma YOK",
                draft.conflicts(YamlPatch.of(handEditedOther)).isEmpty());
        String handEditedSame = disk.replace("weight: 150", "weight: 160");
        check("dokunulan alan elle degistiyse cakisma: [weight]",
                draft.conflicts(YamlPatch.of(handEditedSame)).equals(List.of("weight")),
                draft.conflicts(YamlPatch.of(handEditedSame)));
        YamlPatch patch = YamlPatch.of(handEditedOther);
        draft.applyTo(patch);
        check("kayit elle yapilan [30, 40]'i koruyor, sadece weight'i yaziyor",
                patch.text().equals("mobs:\n  z:\n    weight: 175\n    health: [30, 40]\n"), patch.text());
        String reformatted = disk.replace("weight: 150", "weight: 150.0");
        check("150 -> 150.0 bicim degisikligi cakisma sayilmiyor",
                draft.conflicts(YamlPatch.of(reformatted)).isEmpty());
    }

    /** What the mob editor does to the shipped mobs.yml, without the menu around it. */
    static void endToEnd() throws Exception {
        section("Uctan uca: mobs.yml uzerinde editor kaydi");
        String mobs = Files.readString(root.resolve("src/main/resources/mobs.yml"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        YamlPatch disk = YamlPatch.of(mobs);
        Map<String, Object> fields = new HashMap<>();
        for (String field : List.of("weight", "health", "damage", "speed", "statOverride", "enabled")) {
            fields.put(field, disk.get("mobs.crypt_zombie." + field));
        }
        Draft draft = new Draft(List.of("mobs", "crypt_zombie"), fields);
        draft.set("weight", 175);
        HEALTH.adjustMax(draft, new BigDecimal("2"));
        SPEED.create(draft);
        draft.set("enabled", Boolean.FALSE);
        draft.applyTo(disk);
        String out = disk.text();
        int added = out.split("\n", -1).length - mobs.split("\n", -1).length;
        check("iki alan eklendi (speed + enabled), baska satir eklenmedi", added == 2, added);
        check("weight 175", out.contains("    weight: 175\n"));
        check("health [16, 24] akis biciminde", out.contains("    health: [16, 24]\n"));
        check("speed ve enabled girdinin sonuna", out.contains("    damage: [2, 3]\n    speed: 0.23\n    enabled: false\n"),
                out.substring(out.indexOf("  crypt_zombie:"), out.indexOf("  crypt_skeleton:")));
        check("komsu girdi ve basliklar yerinde", out.contains("  crypt_skeleton:\n    mob: vanilla:SKELETON")
                && out.contains("# ----------------------------------------------------------------------------- weak"));
    }

    // ---------------------------------------------------------------- helpers

    static Draft draft(Map<String, ?> values) {
        return new Draft(List.of("mobs", "x"), values);
    }

    static String range(Draft draft, RangeField field) {
        return field.describe(draft);
    }

    static boolean throwsAny(Runnable action) {
        try {
            action.run();
            return false;
        } catch (RuntimeException expected) {
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
