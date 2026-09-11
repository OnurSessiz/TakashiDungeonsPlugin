import com.takashi.dungeons.player.PlayerDataRepository;
import com.takashi.dungeons.player.PlayerStats;
import com.takashi.dungeons.storage.Migration;
import com.takashi.dungeons.storage.Schema;
import com.takashi.dungeons.storage.SchemaMigrator;
import com.takashi.dungeons.storage.SqlDialect;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-free verification of the phase 7 storage layer.
 *
 * Possible for the same reason the other probes are: the `storage` package and
 * `PlayerDataRepository` carry no Bukkit import at all. The SQL therefore runs here against a real
 * SQLite file in the temp folder, which is the only way the write path gets exercised without a
 * player logging in.
 *
 * What this can and cannot cover: the SQLite statements are EXECUTED, so a typo fails the probe.
 * The MySQL ones cannot be - there is no server - so they are PINNED as strings instead. That is
 * weaker and honest about it: it catches a change to the clause, not a MySQL server refusing it.
 */
public class StorageProbe {

    static int pass = 0, fail = 0;

    static final UUID ALICE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final UUID BOB = UUID.fromString("66666666-7777-8888-9999-000000000000");

    public static void main(String[] args) throws Exception {
        System.out.println("=== FAZ 7 depolama dogrulamasi (sunucusuz) ===\n");

        dialectVocabulary();
        upsertClauses();
        migrationShape();

        File file = File.createTempFile("takashi-probe-", ".db");
        // The driver has to create the file itself; an empty one left by createTempFile is fine
        // for SQLite, but deleting it proves the "first ever start" path.
        Files.delete(file.toPath());
        try (Connection connection = open(file)) {
            migrationRuns(connection);
            settingsRoundTrip(connection);
            tristateSetting(connection);
            statsAccumulate(connection);
            lookups(connection);
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(new File(file.getPath() + "-wal").toPath());
            Files.deleteIfExists(new File(file.getPath() + "-shm").toPath());
        }

        futureSchemaRefused();

        System.out.println("\n==============================================");
        System.out.println("GECEN: " + pass + "   KALAN: " + fail);
        if (fail > 0) System.exit(1);
    }

    static Connection open(File file) throws SQLException {
        // Exactly what Database does, and for the same reason: DriverManager refuses drivers the
        // calling class loader cannot see.
        return new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.getAbsolutePath(),
                new java.util.Properties());
    }

    // ---------------------------------------------------------------- the dialect

    static void dialectVocabulary() {
        section("Dialect: tip sozlugu");
        String ddl = "uuid ${uuid}, name ${name}, hud ${bool}, n ${long}, l ${label}"
                + ")${table-options}";
        String sqlite = SqlDialect.SQLITE.resolve(ddl);
        String mysql = SqlDialect.MYSQL.resolve(ddl);

        check("SQLite: uuid TEXT", sqlite.contains("uuid TEXT"), sqlite);
        check("SQLite: bool INTEGER", sqlite.contains("hud INTEGER"), sqlite);
        check("SQLite: tablo secenegi yok", sqlite.endsWith(")"), sqlite);
        check("MySQL: uuid CHAR(36)", mysql.contains("uuid CHAR(36)"), mysql);
        check("MySQL: name VARCHAR(16)", mysql.contains("name VARCHAR(16)"), mysql);
        check("MySQL: bool TINYINT(1)", mysql.contains("hud TINYINT(1)"), mysql);
        check("MySQL: InnoDB + utf8mb4", mysql.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"), mysql);
        check("Iki dialect de BIGINT", sqlite.contains("n BIGINT") && mysql.contains("n BIGINT"), null);
        check("Cozulmemis yer tutucu kalmadi",
                !sqlite.contains("${") && !mysql.contains("${"), sqlite + " | " + mysql);
    }

    static void upsertClauses() {
        section("Dialect: upsert ve increment cumleleri (MySQL calistirilamiyor, PINLENIYOR)");
        check("SQLite upsert",
                SqlDialect.SQLITE.upsert("uuid", "name", "hud")
                        .equals(" ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, hud = excluded.hud"),
                SqlDialect.SQLITE.upsert("uuid", "name", "hud"));
        check("MySQL upsert",
                SqlDialect.MYSQL.upsert("uuid", "name", "hud")
                        .equals(" ON DUPLICATE KEY UPDATE name = VALUES(name), hud = VALUES(hud)"),
                SqlDialect.MYSQL.upsert("uuid", "name", "hud"));
        check("SQLite increment toplayarak yaziyor",
                SqlDialect.SQLITE.increment("uuid", "deaths")
                        .equals(" ON CONFLICT(uuid) DO UPDATE SET deaths = deaths + excluded.deaths"),
                SqlDialect.SQLITE.increment("uuid", "deaths"));
        check("MySQL increment toplayarak yaziyor",
                SqlDialect.MYSQL.increment("uuid", "deaths")
                        .equals(" ON DUPLICATE KEY UPDATE deaths = deaths + VALUES(deaths)"),
                SqlDialect.MYSQL.increment("uuid", "deaths"));
        // MariaDB desteklemedigi icin satir takma adi (INSERT ... AS new) BILEREK kullanilmiyor.
        check("MySQL tarafi satir takma adi kullanmiyor (MariaDB destegi)",
                !SqlDialect.MYSQL.upsert("uuid", "name").contains(" AS "),
                SqlDialect.MYSQL.upsert("uuid", "name"));

        check("sqlite ve mysql disindaki ad reddediliyor", SqlDialect.from("potato") == null);
        check("bosluk ve buyuk harf tolere ediliyor", SqlDialect.from(" MySQL ") == SqlDialect.MYSQL);
    }

    static void migrationShape() {
        section("Sema: migration listesi");
        check("En az bir migration var", !Schema.MIGRATIONS.isEmpty());
        check("Son surum = latestVersion()",
                Schema.latestVersion()
                        == Schema.MIGRATIONS.get(Schema.MIGRATIONS.size() - 1).version(),
                Schema.latestVersion());

        int previous = 0;
        boolean ordered = true;
        boolean reRunnable = true;
        for (Migration migration : Schema.MIGRATIONS) {
            if (migration.version() <= previous) {
                ordered = false;
            }
            previous = migration.version();
            for (String sql : migration.statements()) {
                // MySQL DDL'i islem icine alinamiyor: yarim kalan migration'in uzerinden
                // yurunebilmesi icin her cumlenin tekrar calistirilabilir olmasi SART.
                if (sql.startsWith("CREATE TABLE") && !sql.contains("IF NOT EXISTS")) {
                    reRunnable = false;
                }
            }
        }
        check("Surumler artan sirada", ordered);
        check("Her CREATE TABLE tekrar calistirilabilir", reRunnable);
    }

    // ---------------------------------------------------------------- the real database

    static void migrationRuns(Connection connection) throws SQLException {
        section("Migration: bos veritabani -> v" + Schema.latestVersion());
        SchemaMigrator migrator = new SchemaMigrator(quietLogger());

        int first = migrator.migrate(connection, SqlDialect.SQLITE);
        check("Ilk kosum en son surume cikiyor", first == Schema.latestVersion(), first);
        check("td_players olustu", tableExists(connection, Schema.PLAYERS));
        check("td_player_stats olustu", tableExists(connection, Schema.STATS));
        check("td_schema_version olustu", tableExists(connection, Schema.VERSIONS));

        int second = migrator.migrate(connection, SqlDialect.SQLITE);
        check("Ikinci kosum ayni surumu veriyor", second == first, second);
        check("Surum tablosunda migration basina TEK satir",
                countRows(connection, Schema.VERSIONS) == Schema.MIGRATIONS.size(),
                countRows(connection, Schema.VERSIONS));
    }

    static void settingsRoundTrip(Connection connection) throws SQLException {
        section("Ayarlar: yaz, oku, uzerine yaz");
        PlayerDataRepository repository = new PlayerDataRepository(SqlDialect.SQLITE);

        PlayerDataRepository.Loaded missing = repository.load(connection, ALICE);
        check("Bilinmeyen oyuncu: exists=false", !missing.exists());
        check("Bilinmeyen oyuncu: istatistik sifir", missing.stats().equals(PlayerStats.ZERO));
        check("Bilinmeyen oyuncu: ayarlar null (secim yapilmamis)",
                missing.hud() == null && missing.partyHud() == null);

        long born = 1_700_000_000_000L;
        repository.saveSettings(connection, ALICE, "Alice", true, false, born);
        PlayerDataRepository.Loaded stored = repository.load(connection, ALICE);
        check("Kayit sonrasi exists=true", stored.exists());
        check("hud=true okundu", Boolean.TRUE.equals(stored.hud()), stored.hud());
        check("party_hud=false okundu", Boolean.FALSE.equals(stored.partyHud()), stored.partyHud());
        check("first_seen yazildi", stored.firstSeen() == born, stored.firstSeen());

        // Ikinci yazim: ad ve ayar degisiyor, first_seen DEGISMIYOR - upsert'in butun meselesi bu.
        repository.saveSettings(connection, ALICE, "AliceRenamed", false, true, 999L);
        PlayerDataRepository.Loaded updated = repository.load(connection, ALICE);
        check("Uzerine yazim hud'u cevirdi", Boolean.FALSE.equals(updated.hud()), updated.hud());
        check("Uzerine yazim party_hud'u cevirdi", Boolean.TRUE.equals(updated.partyHud()));
        check("first_seen DEGISMEDI (upsert onu guncellemiyor)",
                updated.firstSeen() == born, updated.firstSeen());
        check("Tek satir kaldi, ikinci satir acilmadi",
                countRows(connection, Schema.PLAYERS) == 1, countRows(connection, Schema.PLAYERS));
        check("Ad guncellendi", "AliceRenamed".equals(nameOf(connection, ALICE)),
                nameOf(connection, ALICE));
    }

    static void tristateSetting(Connection connection) throws SQLException {
        section("Ayar UC durumlu: null = 'secim yapilmadi'");
        PlayerDataRepository repository = new PlayerDataRepository(SqlDialect.SQLITE);
        repository.saveSettings(connection, BOB, "Bob", null, null, 5L);
        PlayerDataRepository.Loaded loaded = repository.load(connection, BOB);
        check("Satir var ama ayar yok", loaded.exists() && loaded.hud() == null, loaded.hud());
        check("SQL NULL yazildi, 0 degil", isSqlNull(connection, BOB, "hud"));

        repository.saveSettings(connection, BOB, "Bob", false, null, 5L);
        check("false yazildi ve null'dan ayirt edilebiliyor",
                Boolean.FALSE.equals(repository.load(connection, BOB).hud()));
        check("Diger alan hala null", repository.load(connection, BOB).partyHud() == null);
    }

    static void statsAccumulate(Connection connection) throws SQLException {
        section("Istatistik: oku-degistir-yaz DEGIL, uzerine ekleme");
        PlayerDataRepository repository = new PlayerDataRepository(SqlDialect.SQLITE);

        repository.addStats(connection, ALICE, PlayerStats.entry());
        check("Ilk delta satiri aciyor",
                repository.load(connection, ALICE).stats().runsEntered() == 1);

        repository.addStats(connection, ALICE, PlayerStats.mobKill());
        repository.addStats(connection, ALICE, PlayerStats.mobKill());
        repository.addStats(connection, ALICE, PlayerStats.bossKill());
        repository.addStats(connection, ALICE, PlayerStats.clear());
        repository.addStats(connection, ALICE, PlayerStats.death());
        repository.addStats(connection, ALICE, PlayerStats.seconds(90));
        repository.addStats(connection, ALICE, PlayerStats.seconds(30));

        PlayerStats totals = repository.load(connection, ALICE).stats();
        check("mob 2", totals.mobKills() == 2, totals.mobKills());
        check("boss 1", totals.bossKills() == 1, totals.bossKills());
        check("temizleme 1", totals.runsCleared() == 1, totals.runsCleared());
        check("olum 1", totals.deaths() == 1, totals.deaths());
        check("sure 120 saniye", totals.secondsInside() == 120, totals.secondsInside());
        check("giris hala 1 (uzerine yazilmadi)", totals.runsEntered() == 1, totals.runsEntered());

        // Bir delta bir satirda birden fazla alan tasiyabilir.
        repository.addStats(connection, ALICE, new PlayerStats(1, 1, 0, 5, 0, 60));
        PlayerStats after = repository.load(connection, ALICE).stats();
        check("Cok alanli delta hepsini topladi",
                after.runsEntered() == 2 && after.runsCleared() == 2
                        && after.mobKills() == 7 && after.secondsInside() == 180, after);
        check("Istatistik tablosunda oyuncu basina tek satir",
                countRows(connection, Schema.STATS) == 1, countRows(connection, Schema.STATS));

        // PlayerStats.plus ile veritabani toplamasi ayni sonucu vermeli; HUD'un gosterdigi
        // "henuz yazilmamis toplam" bu esitlige dayaniyor.
        PlayerStats local = PlayerStats.ZERO
                .plus(PlayerStats.entry()).plus(PlayerStats.mobKill()).plus(PlayerStats.mobKill())
                .plus(PlayerStats.bossKill()).plus(PlayerStats.clear()).plus(PlayerStats.death())
                .plus(PlayerStats.seconds(120)).plus(new PlayerStats(1, 1, 0, 5, 0, 60));
        check("Bellekteki toplam = veritabanindaki toplam", local.equals(after), local);
        check("ZERO gercekten bos", PlayerStats.ZERO.isZero());
        check("Tek sayac bile bos degil", !PlayerStats.death().isZero());
    }

    static void lookups(Connection connection) throws SQLException {
        section("Arama: isimden uuid, ve sayim");
        PlayerDataRepository repository = new PlayerDataRepository(SqlDialect.SQLITE);
        check("Son gorulen ad bulunuyor",
                ALICE.equals(repository.findByName(connection, "AliceRenamed")));
        check("Eski ad artik bulunmuyor", repository.findByName(connection, "Alice") == null);
        check("Olmayan ad null donuyor", repository.findByName(connection, "Nobody") == null);
        check("Oyuncu sayimi", repository.countPlayers(connection) == 2,
                repository.countPlayers(connection));
    }

    static void futureSchemaRefused() throws Exception {
        section("Gelecekten gelen veritabani REDDEDILIYOR (geri alinmis plugin)");
        File file = File.createTempFile("takashi-probe-future-", ".db");
        Files.delete(file.toPath());
        try (Connection connection = open(file)) {
            SchemaMigrator migrator = new SchemaMigrator(quietLogger());
            migrator.migrate(connection, SqlDialect.SQLITE);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + Schema.VERSIONS + " (version, name, applied_at) VALUES (?, ?, ?)")) {
                insert.setInt(1, Schema.latestVersion() + 50);
                insert.setString(2, "from the future");
                insert.setLong(3, System.currentTimeMillis());
                insert.executeUpdate();
            }
            boolean refused = false;
            String message = "";
            try {
                migrator.migrate(connection, SqlDialect.SQLITE);
            } catch (SQLException expected) {
                refused = true;
                message = expected.getMessage();
            }
            check("Daha yeni sema ile calismayi reddediyor", refused);
            check("Sebep surum numarasini soyluyor", message.contains("schema version"), message);
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(new File(file.getPath() + "-wal").toPath());
            Files.deleteIfExists(new File(file.getPath() + "-shm").toPath());
        }
    }

    // ---------------------------------------------------------------- helpers

    static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            select.setString(1, table);
            try (ResultSet row = select.executeQuery()) {
                return row.next() && row.getInt(1) == 1;
            }
        }
    }

    static long countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return row.next() ? row.getLong(1) : -1;
        }
    }

    static String nameOf(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT name FROM " + Schema.PLAYERS + " WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getString("name") : null;
            }
        }
    }

    static boolean isSqlNull(Connection connection, UUID uuid, String column) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT " + column + " FROM " + Schema.PLAYERS + " WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return false;
                }
                row.getInt(column);
                return row.wasNull();
            }
        }
    }

    /** The migrator logs one line per applied migration; the probe prints its own. */
    static Logger quietLogger() {
        Logger logger = Logger.getLogger("StorageProbe");
        logger.setLevel(Level.OFF);
        return logger;
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
