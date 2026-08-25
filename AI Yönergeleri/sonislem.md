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

## 2026-08-25 — FAZ 1B: Oda Metadata Modeli + Yerleştirme Geometrisi (TAMAMLANDI)

**Ne yapıldı:**
- **Önce ölçüm, sonra kod** — `generation.md` §12'nin dediği sırayla gidildi.
- İki açık soru kapandı: rotasyon işareti (§3) ve tek sayı kenar kuralı (§9).
- `com.takashi.dungeons.generation` paketi kuruldu — oda şablonu, kapı anchor'ı,
  rotasyon, kutu ve yerleştirme formülü.
- `TestRoomFactory` artık `.schem` **ve** `.yml` yazıyor; oda seti 5'ten 8'e çıktı.
- `/tdungeons rooms`, `room <ad>`, `connect <a> <b> [kapıA] [kapıB]` komutları eklendi.

**AÇIK SORU #1 KAPANDI — rotasyon işareti `+1`, saat yönü:**
`SchematicService`'in paste'te kullandığı `AffineTransform().rotateY(-derece)` doğrudan
JVM'de çalıştırıldı — sunucu gerekmedi, transform saf matematik.
`rotateY(-90)` → `(x,y,z) → (-z,y,x)`, yön olarak **K→D→G→B**.
R=0/1/2/3'ün dördü de `generation.md` §3'teki nokta formülleriyle birebir eşleşti.
`d' = (d - R) mod 4` hipotezi R=1 ve R=3'te elendi. `test_corner` (K+D) 90° → **D+G**.
→ **§3'teki bütün formüller doğru, hiçbiri ters çevrilmedi.**

**AÇIK SORU #2 KAPANDI — tek sayı kenar kuralı gerçekten kalktı:**
`test_even` (10 geniş × 16 uzun, üç kapısı da ofsetli) bunun için üretildi.
Origin `(5,0,8)` → kutu `-5..4` / `-8..7`, iki eksende de asimetrik.
12 kombinasyonun (3 kapı × 4 yön) hepsinde kapı tam yerine oturdu; sunucuda 90° dönmüş
hâlde bağlandığında geçit açık çıktı ve odanın **diğer iki kapısı da** hesaplanan yerde bulundu.
→ **Harita ekibine "kenar uzunluğu serbest" denebilir.**

**Kurulan yapı / değişen dosyalar:**
- `generation/Vec3i.java`, `Direction.java`, `Rotation.java`, `Aabb.java` (YENİ) — saf geometri
- `generation/DoorAnchor.java`, `RoomType.java`, `RoomTemplate.java`, `RoomMetadata.java` (YENİ)
- `generation/RoomTemplateStore.java` (YENİ) — `.schem` + `.yml` birleştirici, async
- `generation/PlacedRoom.java` (YENİ) — şablon + R + dünya origin'i + kutu
- `schematic/TestRoomFactory.java` — baştan yazıldı: ayrı `sizeX`/`sizeZ`, kapı ofseti,
  `.yml` üretimi, kapı taşma kontrolü
- `command/DungeonsCommand.java` — `rooms`, `room`, `connect` + tab-complete
- `TakashiDungeonsPlugin.java` — `RoomTemplateStore` bootstrap'i
- `plugin.yml` — usage satırı
- `AI Yönergeleri/generation.md` — §3, §4, §9, §10, §11, §12 güncellendi; §13 (1C planı) eklendi
- `AI Yönergeleri/isleyis.md` — yeni sistem kaydı + eskiyen "tek sayı kenar" maddesi düzeltildi
- `AI Yönergeleri/Roadmap.md` — metadata modeli ve rotation `[x]`
- `scripts/geo-probe/` (YENİ) — sunucusuz geometri testi + WorldEdit rotasyon ölçer,
  `run.ps1` ile koşuluyor

**Alınan kararlar:**
- **`generation` paketi saf Java** — ne Bukkit ne WorldEdit tipi (`RoomMetadata` ve
  `RoomTemplateStore` sınırda). İki kazanç: yerleştirme matematiği sunucu açmadan test
  edilebiliyor (53 kontrol saniyeler içinde koşuyor), ve FAZ 8'de dışarı açılacak API'ye
  üçüncü parti tip sızmıyor.
- **Kutu ve duvar yönü türetilen değerler.** Kutu schematic'ten, duvar anchor vektöründen.
  Metadata'da tutulsalardı schematic değişince eskiyip *sessizce* yanlış çalışırlardı.
  Harita ekibi 40+ oda yazacak: yazılabilen her alan yanlış yazılabilen bir alan.
- **Duvar hesabı yön başına normalize ediliyor**, tek bir "yarı genişlik" ile değil.
  §9 ile origin artık odanın tam ortasında olmak zorunda olmadığı için asimetrik odada
  tek değer yanlış sonuç verirdi.
- **`attachTo` çakışma testi yapmıyor.** "Nereye oturur" (geometri, 1B) ile "oturabilir mi"
  (graf, 1C) ayrıldı; ayrım geometriyi dünyaya erişmeden test edilebilir tutuyor.
- **`Direction` enum sırası rotasyon matematiğinin parçası** — K,D,G,B = 0,1,2,3.
  Sıra değişirse `(d + R) mod 4` sessizce bozulur; sınıf yorumunda yazılı.

**Doğrulama:**
- **53/53 saf geometri kontrolü** (sunucusuz): rotasyon tur kapanışı, 16 `align`
  kombinasyonu, kare + dikdörtgen + asimetrik odada duvar türetme, çakışma kuralları,
  5 oda × tüm kapılar × tüm yönler = 48 yerleştirme.
- **23/23 blok kontrolü** (sunucuda, `execute if block`): iki bağlantı senaryosunda geçidin
  açık olduğu, duvarların sırt sırta durduğu, kapı dışının dolu kaldığı, rotasyonun odanın
  **diğer** kapılarını da doğru yere taşıdığı.
- Plugin'in raporladığı R / origin / kutu değerleri, spec formüllerinden **elle** hesaplanan
  değerlerle birebir eşleşti: rot=270 origin `(273,64,256)`; rot=90 origin `(771,64,243)`,
  kutu 10×8×16 → 16×8×10.

**Yol boyunca yakalanan hatalar:**
- `test_long`'un doğu kapısı ofset **+11** ile duvarın köşe bloğuna taşıyordu (25 uzunlukta
  3 bloklu açıklık için son geçerli merkez 22). `carveDoor`'a konan sınır kontrolü bunu
  `/tdungeons gen` anında patlattı; ofset +10'a çekildi. Kontrol olmasaydı anchor duvarın
  dışına düşer, hata paste sonrası gözle aranırdı.
- `RoomTemplateStore.load` ilk hâlinde dar bir açık vardı: `computeIfAbsent` içinde
  zincirlenince, executor task'ı reddederse (plugin disable olurken) temizlik ekleme
  tamamlanmadan çalışıyor ve **başarısız future cache'te kalıcı** oluyordu. Future önce
  cache'e konup zincir sonra kurulacak şekilde düzeltildi (`SchematicService`'in kullandığı
  iki argümanlı `remove` kalıbı).

**Test harness'ında öğrenilenler (sunucuyu konsoldan sürerken):**
- **`run/logs/latest.log` önce silinmeli.** Eski log'da "Done (" duruyor; script sunucuyu
  hazır sanıp komutları *açılış sırasında* gönderiyor. İlk koşumda tam bu oldu.
- **PowerShell 5.1'in stdin writer'ı ilk yazımda UTF-8 BOM basıyor** ve
  `ProcessStartInfo.StandardInputEncoding` .NET Framework'te yok. BOM ilk komutu bozuyor
  (`?tdungeons gen<--[HERE]`). Çözüm: bir kurban satırı gönderip yutmak.
- **`gen` bitmeden sonraki komut gönderilmemeli** — yarım yazılmış `.schem` okununca
  `EOFException: Unexpected end of ZLIB input stream`. Log'da tamamlanma mesajı beklenmeli.
- **Test dünyası silinmeli.** Slot index'i her açılışta sıfırdan başlıyor, aynı slot'a
  yeniden paste ediliyor ve eski yapı altta kalıyor (`release` blok temizlemiyor — FAZ 2).
  Silinmezse önceki koşumdan kalan bloklar *yanlış "geçti"* üretir.
- Dungeon dünyasının dimension key'i **`minecraft:takashi_dungeons`** —
  `execute in minecraft:takashi_dungeons run forceload add ...` böyle çalışıyor.

**Kaldığımız yer / sıradaki adım:**
→ **FAZ 1C.** Plan `generation.md` **§13**'te maddelenmiş. Özet: 1B "bu oda buraya şu açıyla
  oturur"u verdi; 1C'nin sorusu **"oturabilir mi, ve hangi oda seçilmeli?"**
  Sırasıyla: aday havuzu (`şablon × kapı`) → çakışma testi + slot sınırı (`DungeonLayout`) →
  ağırlıklı seçim → dönüş yanlılığı → ÖLÜ kapı işaretleme.

**Çözülmemiş sorun / not:**
- **Ağırlık nasıl uygulanacak, karar verilmedi** (`generation.md` §11 madde 6). `agirlik`
  metadata'da var ve okunuyor ama kimse kullanmıyor. 4 kapılı oda 4 aday üretiyor; ağırlık
  şablon başına verilirse çok kapılı odalar kendiliğinden 4 kat şanslı oluyor. Muhtemel
  cevap: ağırlık şablona ait, çift seçilirken kapı eşit dağılsın — 1C'de kapanacak.
- Tıpa yöntemi, koridor parçası ve giriş odası tekilliği hâlâ açık (§11 madde 3-5).
- Geometri testi repoda: `scripts/geo-probe/` (`run.ps1`, README, `GeoProbe` + `RotProbe`).
  **Derlemeye girmiyor** — `src/` altında değil, build'i bozma riski yok. 1C'de
  `src/test/java` altına JUnit olarak taşınması değerlendirilmeli; `surefire` pom'da zaten
  yapılandırılmış, test kaynağı olmadığı için atlıyor. Tek eksik bir dependency satırı.

---

## 2026-08-24 — Generation Tasarımı: Hücre Grid'i → Anchor Yerleşimi (KOD YOK)

**Ne yapıldı:**
- Üretim algoritması baştan konuşuldu, **`generation.md`** dosyası açıldı — 1B/1C/1D'nin
  tam spec'i orada. Yeni oturum o dosyayı okuyarak devam edebilir.
- `isleyis.md`'deki "Dungeon Generation — Room Graph" bölümü **eskimişti**, güncellendi ve
  `generation.md`'ye yönlendirildi.

**Değişen mimari karar:**
- **Dungeon içi hücre grid'i terk edildi.** Yerine **anchor (socket/jigsaw) tabanlı serbest
  yerleşim** geldi — Mojang'ın köy/bastion üretimiyle aynı yöntem.
- Kazanımlar: oda boyutları serbest (9×25 koridor + 33×33 boss aynı dungeon'da), rotasyon
  aranan değil **hesaplanan** değer (`R = (d_p + 2 - d_c) mod 4`), aday filtresi ölüyor
  (her oda her bağlantıya aday), kapı ofsetleri düz sırayı kendiliğinden kırıyor.
- Bedeli: **3B AABB çakışma testi zorunlu** hâle geldi (hücre grid'inde çakışma imkânsızdı).
  20 odalık dungeon'da kaba kuvvet yeterli, endişe yok.
- **512'lik instance slot grid'i DURUYOR** — o ayrı katman, 1A'da yazılan hiçbir şey çöp değil.

**Onur'un katkıları (tasarımı bunlar şekillendirdi):**
- Kapının hangi duvarda olduğu **"kapı konumu − oda merkezi"** ile türetilsin → metadata'da
  yön saklanmıyor, tutarsızlık doğamıyor. Benim önerdiğim `yon: KUZEY` alanından daha iyi.
- Y ekseninin hizalanması (çift katlı oda: alttan giriş, üstten devam) → anchor'a Y konuldu,
  rotasyon Y'yi korduğu için bedavaya geldi.
- `door1/door2/door3` fikri → doldurma sırası olarak değil, **adres** olarak kaldı.

**Elenen fikir ve sebebi:**
- "Her odanın 1/2/3 kapılı versiyonu olsun, boş kapı çıkınca bir eksiğini kullan" — kapı
  **sayısı** versiyonu tanımlamıyor, kapı **kümesi** tanımlıyor ({K,G} ≠ {K,D}). 15 kombinasyon,
  rotasyonla 5 şekle iniyor ama 40 oda × 5 = **200 schematic**. Harita ekibi kaldıramaz.
- Yerine: **tıpa** — boş kapıyı motor kapatıyor. Prosedürel (0 dosya) ya da biome başına
  schematic (4 dosya). Varyant desteği metadata'da opsiyonel kaçış kapısı olarak duruyor.

**Kaldığımız yer / sıradaki adım:**
→ **FAZ 1B.** `generation.md` §12'deki sırayla. İlk iş kod değil **ölçüm**: `test_corner`
  90° döndürülüp rotasyon işaretinin saat yönü mü olduğu kanıtlanacak.

**Çözülmemiş sorun / not:**
- Rotasyon işareti (`+1` mi `-1` mi) kanıtlanmadı — 1A testindeki `test_corridor` K-G
  simetrik olduğu için ayırt edemedi. Yanlışsa bütün kapılar tutmaz, sessiz hata.
- "Tek sayı kenar" kuralının anchor yerleşiminde gerektiği düşünülmüyor ama bu **akıl
  yürütme**, test değil. Doğrulanana kadar harita ekibine "serbest" denmeyecek.
- Koridor parçası, giriş odası tekilliği, tıpa yönteminin sırası → `generation.md` §11.

---

## 2026-08-24 — FAZ 1A: Void World + Grid Slot + Async Paste (TAMAMLANDI)

**Ne yapıldı:**
- FAZ 1 dört adıma bölündü: **1A** altyapı (bu oturum) → 1B metadata → 1C kapı eşleştirme →
  1D graph üretimi (gezilebilir dungeon milestone'u)
- `worldedit-bukkit 7.3.16` pom'a **provided** olarak eklendi
- Void dünya (`takashi_dungeons`) + grid slot yönetimi yazıldı
- Schematic servisi yazıldı: async dosya okuma + cache + rotation'lı paste
- `TestRoomFactory` ile kod üretimli 5 placeholder oda (`/tdungeons gen`)
- Test sunucusuna **FAWE 2.15.4** kuruldu (Modrinth, SHA512 doğrulandı)
- Sunucuda **doğrulandı** (13/13 kontrol, `execute if block` ile):
  zemin / tavan ışığı / 4 kapı açıklığı / köşe duvarı / iç hacim boş;
  rot=0 koridorda kapılar K-G, **rot=90'da D-B** ve kuzey duvarı dolu
- Paste süreleri: 17³ oda ~250-300 ms, ilk paste 602 ms (FAWE ısınması), async thread'de

**Kurulan yapı / değişen dosyalar:**
- `pom.xml` — `worldedit.version=7.3.16`, worldedit-bukkit provided
- `src/main/resources/config.yml` (YENİ) — dünya adı, slot-size 512, columns 32, base-y 64,
  `schematics.force-sync-paste`
- `com/takashi/dungeons/world/VoidChunkGenerator.java` (YENİ)
- `com/takashi/dungeons/world/DungeonWorldManager.java` (YENİ)
- `com/takashi/dungeons/world/GridSlot.java`, `GridSlotManager.java` (YENİ)
- `com/takashi/dungeons/schematic/SchematicService.java` (YENİ)
- `com/takashi/dungeons/schematic/TestRoomFactory.java` (YENİ)
- `TakashiDungeonsPlugin.java` — config + world + schematic servisi bootstrap'i
- `DungeonsCommand.java` — `world`, `list`, `gen`, `paste`, `slots`, `free` alt komutları
- `plugin.yml` — usage satırı
- `AI Yönergeleri/isleyis.md` — 2 sistem kaydı eklendi

**Alınan kararlar:**
- **Derleme tabanı WorldEdit 7.3.16, çalışma zamanı FAWE/WorldEdit fark etmez.** Eskiye karşı
  derleyip yenide çalıştırmak güvenli; tersi `NoSuchMethodError` üretir. FAWE de aynı
  `com.sk89q.worldedit.*` paketlerini sağladığı için **tek kod yolu** ikisiyle de çalışıyor.
- **Async paste sadece FAWE varsa.** Düz WorldEdit'in EditSession'ı thread-safe değil.
  Dosya okuma her iki durumda da async — asıl pahalı kısım o.
- **Odalar tek sayı kenarlı** (17, 33). Çift kenarda gerçek merkez blok yok, 90° dönen oda
  1 blok kayıyor. Bu kural FAZ 10'daki gerçek odalar için de geçerli — haritacı ekibine söylenecek.
- **Clipboard origin'i odanın yatay merkezinde**, taban seviyesinde. Rotation kendi ekseninde
  dönüyor, paste hedefi doğrudan slot merkezi olabiliyor.
- **`rotateY(-derece)`** — WorldEdit'in `//rotate` komutuyla aynı yön çıksın diye.

**Neden böyle yapıldı:**
- Grid slot deterministik: index'ten konum hesaplanabildiği için FAZ 7'de DB'ye sadece index yazılacak
- Void world'de tüm doğal üretim ve gamerule'lar kapatıldı: N instance açıkken boşuna TPS harcanmasın,
  içerik kontrolü tamamen bizde kalsın
- Test odaları kod üretimli: gerçek haritalar (FAZ 10) beklenmeden generation mantığı test edilebilsin

**Kaldığımız yer / sıradaki adım:**
→ **FAZ 1B — Schematic metadata modeli.** Her oda için: oda tipi (normal/boss/giriş), kapı yönleri
  (K/D/G/B), boyut, ağırlık. YAML'de mi schematic'in yanında `.yml` olarak mı tutulacağına
  karar verilecek. Sonrasında 1C'de kapı yönlerinin rotation ile birlikte döndürülmesi.

**Çözülmemiş sorun / not:**
- `release()` slot index'ini geri veriyor ama **blokları silmiyor** — temizlik FAZ 2'de.
  Şu an aynı slot'a ikinci paste eski yapının üstüne yazar.
- FAWE paste sonrası chunk'ları yüklü bırakmıyor; `execute if block` ile test ederken önce
  `forceload` gerekiyor (test scriptinde bu yüzden var).
- `run/plugins/FastAsyncWorldEdit-Paper-2.15.4.jar` eklendi ama `run/` .gitignore'da —
  başka bir makinede test edecek kişi FAWE'yi kendi indirmeli.

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
