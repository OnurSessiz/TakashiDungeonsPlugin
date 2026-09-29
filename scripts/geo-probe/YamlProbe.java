import com.takashi.dungeons.yaml.YamlPatch;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Server-free verification of phase 9A's YAML writer.
 *
 * The promise being tested is narrow and strict: an edit changes the value it was asked to change
 * and NOTHING else in the file. So most checks here are line counts against the real shipped files
 * (mobs.yml, loot.yml, config.yml) - "one line differs" is the thing an operator's diff shows - and
 * a re-parse, so that "one line differs" also means "the right line, and it still loads".
 *
 * Needs SnakeYAML on the classpath (Paper bundles it; run.ps1 takes the copy in .m2).
 */
public class YamlProbe {

    static int pass = 0, fail = 0;
    static Path root;

    public static void main(String[] args) throws Exception {
        root = Path.of(args.length > 0 ? args[0] : ".");
        System.out.println("=== FAZ 9A YAML yazici dogrulamasi (sunucusuz) ===\n");

        rendering();
        noOpIsIdentity();
        replaceInRealFiles();
        insertAndRemove();
        typeChanges();
        sections();
        quoteStyle();
        inlineComments();
        lineEndingsAndUnicode();
        refusals();
        writeRoundTrip();

        System.out.println("\n==============================================");
        System.out.println("GECEN: " + pass + "   KALAN: " + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- rendering

    static void rendering() {
        section("Yazim: deger dosyada nasil gorunuyor");
        check("int -> 175", YamlPatch.render(175).equals("175"), YamlPatch.render(175));
        check("long -> 1800", YamlPatch.render(1800L).equals("1800"));
        check("double 0.23 -> 0.23", YamlPatch.render(0.23).equals("0.23"), YamlPatch.render(0.23));
        check("double 0.1+0.2 -> 0.3 (kayan nokta kalintisi yok)",
                YamlPatch.render(0.1 + 0.2).equals("0.3"), YamlPatch.render(0.1 + 0.2));
        check("double 20.0 -> 20.0 (double oldugu belli)", YamlPatch.render(20.0).equals("20.0"),
                YamlPatch.render(20.0));
        check("bool -> false", YamlPatch.render(false).equals("false"));
        check("liste -> [18, 24]", YamlPatch.render(List.of(18, 24)).equals("[18, 24]"));
        check("karisik liste -> [0.26, 0.3]", YamlPatch.render(List.of(0.26, 0.3)).equals("[0.26, 0.3]"));
        check("vanilla:ZOMBIE tirnaksiz", YamlPatch.render("vanilla:ZOMBIE").equals("vanilla:ZOMBIE"));
        check("strong tirnaksiz", YamlPatch.render("strong").equals("strong"));
        for (String risky : List.of("true", "no", "yes", "off", "null", "~", "1.5", "12", "",
                " bosluk", "a: b", "a #b", "- x", "[x]", "#yorum", "a,b")) {
            String out = YamlPatch.render(risky);
            Object back = new Yaml(new LoaderOptions()).load("v: " + out + "\n") instanceof Map<?, ?> m
                    ? m.get("v") : null;
            check("'" + risky + "' tirnakli yaziliyor ve string olarak geri okunuyor",
                    out.startsWith("\"") && risky.equals(back), out + " -> " + back);
        }
        String mini = YamlPatch.render("<gray>Ash Warden</gray>");
        check("MiniMessage geri ayni string", "<gray>Ash Warden</gray>".equals(load("v: " + mini).get("v")),
                mini);
        String escaped = YamlPatch.render("a \"b\" \\ c");
        check("tirnak ve ters bolu kacisi", "a \"b\" \\ c".equals(load("v: " + escaped).get("v")),
                escaped);
        check("NaN reddediliyor", throwsAny(() -> YamlPatch.render(Double.NaN)));
        check("null reddediliyor (remove kullanilmali)", throwsAny(() -> YamlPatch.render(null)));
        check("Map reddediliyor (bolum deger olarak yazilmaz)", throwsAny(() -> YamlPatch.render(Map.of())));
    }

    // ---------------------------------------------------------------- identity

    static void noOpIsIdentity() throws Exception {
        section("Dokunulmayan dosya byte byte ayni");
        for (String name : List.of("mobs.yml", "loot.yml", "config.yml", "shop.yml", "lang/en.yml")) {
            String original = resource(name);
            check(name + ": okuma + text() = orijinal", YamlPatch.of(original).text().equals(original));
        }
    }

    // ---------------------------------------------------------------- the real files

    static void replaceInRealFiles() throws Exception {
        section("Gercek dosyalarda tek deger degistirme -> tek satir");

        String mobs = resource("mobs.yml");
        String out = YamlPatch.of(mobs).set("mobs.crypt_zombie.weight", 175).text();
        check("mobs.yml weight: tek satir farkli", changedLines(mobs, out) == 1, changedLines(mobs, out));
        check("mobs.yml weight: satir '    weight: 175'", line(out, "weight: 175").equals("    weight: 175"));
        check("mobs.yml weight: yuklenen deger 175", intAt(out, "mobs", "crypt_zombie", "weight") == 175);
        check("mobs.yml weight: kardes girdi dokunulmadi",
                intAt(out, "mobs", "crypt_skeleton", "weight") == 120);
        check("mobs.yml weight: yorum sayisi ayni", comments(mobs) == comments(out));

        out = YamlPatch.of(mobs).set("mobs.crypt_zombie.health", List.of(18, 24)).text();
        check("mobs.yml health aralik: tek satir", changedLines(mobs, out) == 1);
        check("mobs.yml health: akis bicimi korundu",
                out.contains("    health: [18, 24]\n    damage: [2, 3]"));

        String loot = resource("loot.yml");
        out = YamlPatch.of(loot).set("rarity.common", 600).text();
        check("loot.yml rarity.common: tek satir", changedLines(loot, out) == 1);
        check("loot.yml: satir ici yorum korundu", out.contains("  common: 600        # 62.5%"),
                line(out, "common: 600"));

        String config = resource("config.yml");
        out = YamlPatch.of(config).set("instance.duration-seconds", 1800).text();
        check("config.yml sure: tek satir", changedLines(config, out) == 1);
        check("config.yml: akis listesi warn-seconds dokunulmadi",
                out.contains("warn-seconds: [120, 60, 10]"));
        out = YamlPatch.of(config).set("instance.warn-seconds", List.of(300, 60, 10)).text();
        check("config.yml warn-seconds: tek satir, akis bicimi",
                changedLines(config, out) == 1 && out.contains("  warn-seconds: [300, 60, 10]"));
        out = YamlPatch.of(config).set("portal.size", "large").text();
        check("config.yml portal.size: tek satir", changedLines(config, out) == 1
                && out.contains("  size: large\n"));
    }

    static void insertAndRemove() throws Exception {
        section("Anahtar ekleme / silme");
        String mobs = resource("mobs.yml");

        String out = YamlPatch.of(mobs).set("mobs.crypt_zombie.speed", 0.25).text();
        check("yeni anahtar: tam bir satir eklendi", lineCount(out) == lineCount(mobs) + 1);
        check("yeni anahtar: bolumun sonuna, ayni girintide",
                out.contains("    damage: [2, 3]\n    speed: 0.25\n"));
        check("yeni anahtar: yuklenen deger 0.25",
                ((Number) at(out, "mobs", "crypt_zombie", "speed")).doubleValue() == 0.25);

        out = YamlPatch.of(mobs).set("mobs.cave_spider_scout.speed", 0.3).text();
        check("yorumla biten girdiye ekleme yukleniyor",
                ((Number) at(out, "mobs", "cave_spider_scout", "speed")).doubleValue() == 0.3);

        YamlPatch patch = YamlPatch.of(mobs);
        check("silme: true donuyor", patch.remove("mobs.crypt_zombie.damage"));
        out = patch.text();
        check("silme: tam bir satir gitti", lineCount(out) == lineCount(mobs) - 1);
        check("silme: anahtar yok", at(out, "mobs", "crypt_zombie", "damage") == null);
        check("silme: kardes alan duruyor", at(out, "mobs", "crypt_zombie", "health") != null);
        check("olmayan anahtari silmek false", !YamlPatch.of(mobs).remove("mobs.crypt_zombie.nope"));

        patch = YamlPatch.of(mobs);
        patch.remove("mobs.crypt_zombie");
        out = patch.text();
        check("bolumun ilk girdisini silmek: '---- weak' basligi kaldi",
                out.contains("# ----------------------------------------------------------------------------- weak"));
        check("bolumun ilk girdisini silmek: girdi yok, komsu var",
                at(out, "mobs", "crypt_zombie") == null && at(out, "mobs", "crypt_skeleton") != null);
        check("silinen girdi 6 satirdi (anahtar + 5 alan)", lineCount(mobs) - lineCount(out) == 6,
                lineCount(mobs) - lineCount(out));
    }

    static void typeChanges() {
        section("Tip degisimi");
        String src = "a:\n  speed: 0.28\n  health: [16, 22]\n  list:\n    - 1\n    - 2\n  after: 9\n";
        String out = YamlPatch.of(src).set("a.speed", List.of(0.26, 0.3)).text();
        check("skaler -> aralik", out.contains("  speed: [0.26, 0.3]\n"), out);
        out = YamlPatch.of(src).set("a.health", 20).text();
        check("aralik -> skaler", out.contains("  health: 20\n"), out);
        out = YamlPatch.of(src).set("a.list", List.of(5, 6)).text();
        check("blok liste -> akis listesi, sonraki anahtar yerinde",
                out.equals("a:\n  speed: 0.28\n  health: [16, 22]\n  list: [5, 6]\n  after: 9\n"), out);
        YamlPatch patch = YamlPatch.of(src);
        patch.remove("a.list");
        check("blok listeyi silmek tum satirlarini goturuyor",
                patch.text().equals("a:\n  speed: 0.28\n  health: [16, 22]\n  after: 9\n"), patch.text());
    }

    static void sections() {
        section("Eksik bolumler");
        String out = YamlPatch.of("a: 1\n").set("x.y.z", 3).text();
        check("kokte yeni ic ice yol", out.equals("a: 1\nx:\n  y:\n    z: 3\n"), out);
        out = YamlPatch.of("a:\n  b: 1\nc: 2\n").set("a.n.m", 4).text();
        check("var olan bolume ic ice ekleme", out.equals("a:\n  b: 1\n  n:\n    m: 4\nc: 2\n"), out);
        out = YamlPatch.of("a:\nb: 1\n").set("a.c", 5).text();
        check("bos 'a:' bolumunun altina", out.equals("a:\n  c: 5\nb: 1\n"), out);
        out = YamlPatch.of("a:\nb: 1\n").set("a", 7).text();
        check("bos 'a:' degerine yazmak", out.equals("a: 7\nb: 1\n"), out);
        out = YamlPatch.of("").set("k", "v").text();
        check("bos dosya", out.equals("k: v\n"), out);
        out = YamlPatch.of("# sadece yorum\n").set("k", 1).text();
        check("sadece yorumlu dosya", out.equals("# sadece yorum\nk: 1\n"), out);
        // No trailing newline stays no trailing newline: the writer does not add one the file did
        // not have, or the last line would show up as changed in every diff.
        out = YamlPatch.of("a: 1").set("b", 2).text();
        check("sonunda satir sonu olmayan dosya (eklemiyor)", out.equals("a: 1\nb: 2"),
                out.replace("\n", "\\n"));
        out = YamlPatch.of("a: 1").set("a", 2).text();
        check("sonunda satir sonu olmayan dosyada degistirme", out.equals("a: 2"), out);
        out = YamlPatch.of("a:\n  b: 1   # yorum\nc: 2\n").set("a.d", 3).text();
        check("satir ici yorumlu son girdinin ardina", out.equals("a:\n  b: 1   # yorum\n  d: 3\nc: 2\n"), out);
    }

    static void quoteStyle() {
        section("Tirnak bicimi korunuyor");
        String out = YamlPatch.of("name: \"<gray>Ash</gray>\"\n").set("name", "<red>X</red>").text();
        check("cift tirnak cift tirnak kaliyor", out.equals("name: \"<red>X</red>\"\n"), out);
        out = YamlPatch.of("name: 'abc'\n").set("name", "it's").text();
        check("tek tirnak tek tirnak kaliyor (kacisli)", out.equals("name: 'it''s'\n"), out);
        check("tek tirnak geri okunuyor", "it's".equals(load(out).get("name")));
        out = YamlPatch.of("name: plain\n").set("name", "other").text();
        check("tirnaksiz tirnaksiz kaliyor", out.equals("name: other\n"), out);
    }

    static void inlineComments() throws Exception {
        section("Satir ici yorum (yuzde yorumu yalan soylemesin)");
        String loot = resource("loot.yml");
        YamlPatch patch = YamlPatch.of(loot);
        check("rarity.common yorumu okunuyor", "62.5%".equals(patch.comment("rarity.common")),
                patch.comment("rarity.common"));
        check("mythic yorumu okunuyor",
                String.valueOf(patch.comment("rarity.mythic")).startsWith("boss_chest only"));
        patch.set("rarity.common", 600);
        check("deger degisince yorum yerinde", "62.5%".equals(patch.comment("rarity.common")));
        check("yorum yeniden yaziliyor", patch.comment("rarity.common", "60%"));
        String out = patch.text();
        check("hizalama korundu, tek satir", out.contains("  common: 600        # 60%\n")
                && changedLines(loot, out) == 1, line(out, "common: 600"));
        check("yorumsuz degere yorum EKLENMIYOR",
                !YamlPatch.of("a: 1\n").comment("a", "x"));
        check("tirnak icindeki # yorum degil", YamlPatch.of("a: 'x # y'\n").comment("a") == null);
        check("bosluksuz # yorum degil (degerin parcasi)", YamlPatch.of("a: x#y\n").comment("a") == null);
        check("olmayan anahtar -> null", YamlPatch.of("a: 1\n").comment("b") == null);
        String flow = YamlPatch.of("r: [2, 4]  # draws\n").set("r", List.of(3, 5)).text();
        check("akis listesinden sonraki yorum korunuyor", flow.equals("r: [3, 5]  # draws\n"), flow);
    }

    static void lineEndingsAndUnicode() {
        section("Satir sonu ve Unicode");
        String crlf = "a:\r\n  b: 1\r\nc: 2\r\n";
        String out = YamlPatch.of(crlf).set("a.d", 3).set("c", 4).text();
        check("CRLF dosyada eklenen satir da CRLF", out.equals("a:\r\n  b: 1\r\n  d: 3\r\nc: 4\r\n"),
                out.replace("\r", "\\r").replace("\n", "\\n"));
        YamlPatch patch = YamlPatch.of(crlf);
        patch.remove("a.b");
        check("CRLF dosyada silme satiri tam goturuyor", patch.text().equals("a:\r\nc: 2\r\n"),
                patch.text().replace("\r", "\\r").replace("\n", "\\n"));
        // An emoji is two UTF-16 units and one code point: an off-by-one here would cut a value in half.
        String emoji = "a: \"\uD83D\uDE00 \u2014 x\"\nb: 1\n";
        out = YamlPatch.of(emoji).set("b", 2).text();
        check("emoji'den sonraki deger dogru yerde", out.equals("a: \"\uD83D\uDE00 \u2014 x\"\nb: 2\n"), out);
    }

    static void refusals() {
        section("Reddedilenler - ve reddedince dosya degismiyor");
        YamlPatch flow = YamlPatch.of("a: {x: 1}\n");
        check("akis map'ine ekleme reddediliyor", throwsAny(() -> flow.set("a.y", 2)));
        check("...ve metin ayni", flow.text().equals("a: {x: 1}\n"));
        YamlPatch scalar = YamlPatch.of("a: 1\n");
        check("skalerin icine yol reddediliyor", throwsAny(() -> scalar.set("a.b", 2)));
        check("...ve metin ayni", scalar.text().equals("a: 1\n"));
        YamlPatch listed = YamlPatch.of("l:\n  - k: 1\n    j: 2\n");
        check("liste icinden yol yurunmuyor (bolum degil)", throwsAny(() -> listed.set("l.k", 3)));
        check("bozuk YAML ile baslanmiyor", throwsAny(() -> YamlPatch.of("a: [1, 2\n")));
        check("bozuk YAML ile baslanmiyor (girinti)", throwsAny(() -> YamlPatch.of("a: 1\n b: 2\n")));
    }

    static void writeRoundTrip() throws Exception {
        section("Diske yazma");
        Path dir = Files.createTempDirectory("takashi-yaml-");
        Path file = dir.resolve("t.yml");
        try {
            Files.writeString(file, "\uFEFFa: 1\n", StandardCharsets.UTF_8);
            YamlPatch patch = YamlPatch.read(file).set("a", 2);
            check("BOM'lu dosya okunuyor, text() BOM'suz", patch.text().equals("a: 2\n"));
            patch.write(file);
            String back = Files.readString(file, StandardCharsets.UTF_8);
            check("yazilan dosya BOM'u koruyor", back.equals("\uFEFFa: 2\n"), back);
            check("gecici dosya kalmadi", !Files.exists(dir.resolve("t.yml.tmp")));
            String mobs = resource("mobs.yml");
            Files.writeString(file, mobs, StandardCharsets.UTF_8);
            YamlPatch.read(file).write(file);
            check("mobs.yml diske yazip okuyunca byte byte ayni",
                    Files.readString(file, StandardCharsets.UTF_8).equals(mobs));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir.resolve("t.yml.tmp"));
            Files.deleteIfExists(dir);
        }
    }

    // ---------------------------------------------------------------- helpers

    static String resource(String name) throws Exception {
        // Normalised to LF: a clone with autocrlf has CRLF files, and CRLF has its own section.
        return Files.readString(root.resolve("src/main/resources/" + name), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> load(String text) {
        Object loaded = new Yaml(new LoaderOptions()).load(text);
        return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    static Object at(String text, String... path) {
        Object current = load(text);
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(key);
        }
        return current;
    }

    static int intAt(String text, String... path) {
        Object value = at(text, path);
        return value instanceof Number n ? n.intValue() : Integer.MIN_VALUE;
    }

    static List<String> lines(String text) {
        return new ArrayList<>(List.of(text.split("\n", -1)));
    }

    static int lineCount(String text) {
        return lines(text).size();
    }

    /** Lines that differ position by position; only meaningful when the counts match. */
    static int changedLines(String a, String b) {
        List<String> x = lines(a), y = lines(b);
        if (x.size() != y.size()) return -Math.abs(x.size() - y.size()) - 1000;
        int changed = 0;
        for (int i = 0; i < x.size(); i++) {
            if (!x.get(i).equals(y.get(i))) changed++;
        }
        return changed;
    }

    static String line(String text, String containing) {
        for (String line : lines(text)) {
            if (line.contains(containing)) return line;
        }
        return "<yok>";
    }

    static long comments(String text) {
        return lines(text).stream().filter(l -> l.strip().startsWith("#")).count();
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
