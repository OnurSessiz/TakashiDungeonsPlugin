# SON İŞLEM — Oturum Günlüğü

> Amaç: Her oturumda ne yaptığımızı, nerede kaldığımızı kaydetmek.
> **En yeni kayıt en üstte.** Eski kayıtlar silinmez, aşağı iner.
>
> Kural: Oturum bitmeden buraya yazılır. Yazılmazsa bir sonraki oturumda
> "nerede kalmıştık" diye kod okumak zorunda kalırız.

---

## Şablon (kopyala, en üste ekle)

```
## [TARİH] — [Kısa Başlık]

**Ne yapıldı:**
-

**Kurulan yapı / değişen dosyalar:**
-

**Neden böyle yapıldı:**
-

**Kaldığımız yer / sıradaki adım:**
-

**Çözülmemiş sorun / not:**
-
```

---

## 2026-08-23 — FAZ 0: Maven İskeleti (TAMAMLANDI)

**Ne yapıldı:**
- Toolchain kuruldu: **Temurin JDK 21** (winget) + **Maven 3.9.16** (winget'te Apache.Maven paketi
  yok, Apache'den indirildi, SHA512 doğrulandı → `%USERPROFILE%/tools/apache-maven-3.9.16`)
- Maven wrapper repoya eklendi (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` → 3.9.16'ya sabit)
- `pom.xml`, `plugin.yml`, main class ve `/tdungeons` komutu yazıldı
- Paper 1.21.8 (build 60) test sunucusu `run/` altına kuruldu, SHA256 doğrulandı, EULA kabul edildi
- Sunucuda **doğrulandı**: plugin enable oluyor, `plugins` listesinde görünüyor,
  `/tdungeons status` çalışıyor, disable temiz, log'da hata/stacktrace yok
- Roadmap FAZ 0'ın 6 maddesi de `[x]`

**Kurulan yapı / değişen dosyalar:**
- `pom.xml` — com.takashi:TakashiDungeons:0.1.0-SNAPSHOT, release 21, shade + relocation
- `src/main/resources/plugin.yml` — softdepend: WorldEdit / FastAsyncWorldEdit / MythicMobs / Vault
- `src/main/java/com/takashi/dungeons/TakashiDungeonsPlugin.java`
- `src/main/java/com/takashi/dungeons/command/DungeonsCommand.java`
- `scripts/build.ps1` (build → run/plugins), `scripts/server.ps1` (JDK 21 ile Paper başlat)
- `AI Yönergeleri/isleyis.md` — 2 sistem kaydı eklendi

**Alınan kararlar (önceki oturumdan açık kalanlar kapandı):**
- **Hedef sürüm: MC 1.21.8** — `paper-api 1.21.8-R0.1-SNAPSHOT`, `api-version: '1.21'`, Java 21.
  Sebep: 1.21 hattı marketplace'te en yaygın kurulu taban; Paper artık 26.x şemasına geçmiş
  ama o sürümlerin kurulu sunucu tabanı dar.
- **Paket adı: `com.takashi.dungeons`** (groupId `com.takashi`, artifactId `TakashiDungeons`)
- SQLite driver `com.takashi.dungeons.libs.sqlite` altına **relocate** edildi (başka plugin'in
  shade ettiği driver ile çakışmasın diye — marketplace ürününde en sık gelen destek talebi)

**Neden böyle yapıldı:**
- Sistemde sadece JDK 26 vardı; Paper 1.21.8 Java 26'yı kabul etmiyor → JDK 21 kuruldu ve
  scriptlerde java yolu **sabitlendi**, PATH'e güvenilmiyor
- Maven wrapper: harita ekibindeki 2-3 kişi Maven kurmadan build alabilsin diye
- SQLite bağımlılığı FAZ 7'de kullanılacak olsa da şimdi eklendi: shade+relocate altyapısının
  gerçekten çalıştığı boş plugin aşamasında doğrulandı (jar içinde `org/sqlite` kalıntısı yok)

**Kaldığımız yer / sıradaki adım:**
→ **FAZ 1 — Çekirdek Generation.** İlk madde: FAWE/WorldEdit dependency'sini pom'a ekleyip
  async paste'i çalıştırmak. `enginehub` repository tanımı pom'da hazır bekliyor.

**Çözülmemiş sorun / not:**
- Paper 1.21.8 plugin'i **remap** ediyor (`PluginRemapper`), ilk yüklemede ~1.5 sn ek süre — normal
- Party sistemi detayı (oyuncu dungeon'dayken çıkarsa ne olacak?) hâlâ açık, FAZ 5'e bırakıldı

---

## 2026-08-23 — Proje Kurulumu ve Mimari Kararlar

**Ne yapıldı:**
- Proje fikri baştan sona konuşuldu, mimari netleştirildi
- 4 takip dosyası oluşturuldu: `anahedef.md`, `Roadmap.md`, `isleyis.md`, `sonislem.md`
- Henüz kod yazılmadı, Faz 0 başlamadı

**Alınan kararlar:**
- Build tool: **Maven** (Spigot ekosisteminin varsayılanı, tutorial/örnek bolluğu)
- Addon'lar **ayrı jar + ayrı repo**, core'un public API'si üzerinden bağlanacak
- Dungeon yerleşimi: **instanced void world** (dünyaya kalıcı yazma YOK)
- Oda çeşitliliği **rotation** ile — kapı varyantı ayrı schematic olarak üretilmeyecek
- `statOverride: true/false` flag'i kullanılacak
- Player data **SQL**'de (SQLite default, MySQL opsiyon)
- Supply mob → villager trade değil, event cancel + custom GUI

**Neden böyle yapıldı:**
- Maven: Paper/Spigot dokümantasyonu ve topluluk çözümleri ezici çoğunlukla Maven
- Ayrı jar: core ucuz/ücretsiz + addon'lar ek gelir → upsell modeli kurulabilir
- Instanced: iki party aynı anda girerse çakışma olmasın; griefing/rollback derdi yok
- Rotation: 120 schematic → ~40'a düşüyor, inşa yükü 3 kat azalıyor
- SQL: YAML'de player data, oyuncu sayısı artınca disk I/O'yu çökertir

**Kaldığımız yer / sıradaki adım:**
→ **FAZ 0**: GitHub repo (Java .gitignore) + Maven projesi kurulumu

**Çözülmemiş sorun / not:**
- Hedef Minecraft versiyonu henüz belirlenmedi (1.20.x / 1.21.x?)
- Paket adı (`com.takashi.dungeons` mı, başka mı?) netleşmedi
- Party sistemi detayı (oyuncu dungeon'dayken çıkarsa ne olacak?) sonraya bırakıldı
- Zaman beklentisi: 1 hafta gerçekçi değil, **2-4 ay** faz faz ilerleme planlandı
