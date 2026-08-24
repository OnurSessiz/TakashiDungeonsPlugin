# İŞLEYİŞ — Sistem Mimarisi Referansı

> Amaç: Yazdığımız her sistemin **nasıl çalıştığını** buraya kaydetmek.
> Böylece satırlarca kod okumadan yapıyı hatırlayabiliriz.
>
> Kural: Bir sistem yazıldıktan sonra buraya eklenir. Sistem değişirse burası
> da güncellenir — yoksa dosya yalan söylemeye başlar.

---

## Şablon (her yeni sistem için kopyala)

```
## [Sistem Adı]
**Dosya/Paket:** com.takashi.dungeons.xxx
**Ne işe yarar:** (1-2 cümle)
**Nasıl çalışır:** (akış, adım adım)
**Bağımlılıkları:** (hangi sisteme bağlı)
**Dikkat edilecek:** (tuzaklar, gelecekte kırılabilecek yerler)
```

---

## KARARLAŞTIRILMIŞ ALGORİTMALAR

### Loot — Weighted Random Selection

Her item class'ına bir **weight** verilir, toplam **1000** olacak şekilde:

| Class | Base Weight | Yüzde |
|---|---|---|
| Common | 600 | %60 |
| Uncommon | 250 | %25 |
| Rare | 100 | %10 |
| Ultra Rare | 40 | %4 |
| Legendary | 10 | %1 |

**Seçim:** 0-1000 arası random sayı üretilir, kümülatif aralığa düşürülür.
Yüzde yerine weight kullanma sebebi: yeni class eklemek/oran değiştirmek kolay.

**Zorluk multiplier'ı — KRİTİK NOKTA:**
Multiplier tüm class'lara uygulanırsa oranlar değişmez (normalize edilince aynı kalır).
Doğru yöntem: **multiplier sadece rare ve üstüne uygulanır**, artan miktar
common'dan düşülür. Toplam her zaman 1000'de kalır.

```
hard (x2.5):
  rare      100 → 250
  ultra      40 → 100
  legendary  10 →  25
  nadir toplam = 375
  uncommon = 250 (sabit)
  common = 1000 - 375 - 250 = 375
```

Sonuç: legendary şansı %1 → %2.5, common %60 → %37.5.

---

### Dungeon Generation — Room Graph

> **Tam algoritma ayrı dosyada: [`generation.md`](generation.md).**
> Koordinat konvansiyonları, rotasyon formülleri, yerleştirme adımları, metadata şeması ve
> açık sorular orada. Burada sadece özet duruyor.

Endüstri standardı **room-based procedural generation** (Diablo, Isaac, Gungeon mantığı).
Yerleştirme **socket/jigsaw tabanlı** — Mojang'ın köy/bastion/ancient city üretimiyle aynı yöntem.

**Akış:**
1. Boyut seçilir → hedef oda sayısı belirlenir (small 3-6 / medium 7-12 / large 13-20)
2. **Kritik path** üretilir: giriş odasından başlayıp hedef uzunluğa kadar zincir
3. Path'in **son node'u boss odası** olarak sabitlenir (random değil, atama)
4. Kalan oda kotası **yan dallar** olarak random walk ile eklenir
5. Her adımda oda, ebeveynin boş kapısına **kapı anchor'ları çakıştırılarak** takılır;
   gereken rotasyon aranmaz, `R = (d_p + 2 - d_c) mod 4` ile hesaplanır
6. Yerleşim serbest olduğu için her adımda **3B AABB çakışma testi** zorunlu;
   geçen aday yoksa kapı ÖLÜ işaretlenir ve tıpayla kapatılır
7. Çok kapılı odalar (2-3 çıkışlı) labirent hissi yaratır — dallanma noktaları
8. Graf tamamlandıktan **sonra** odalar void world'deki instance slot'una async paste edilir

**Rotation:** Aynı schematic 90/180/270 döndürülerek farklı kapı yönelimleri elde
edilir. Bu yüzden her oda için ayrı kapı varyantı schematic'i üretilmez.

**Dungeon içi hücre grid'i YOK.** Odalar sabit hücrelere oturmuyor, kapı noktalarından
kenetleniyor — bu sayede oda boyutları serbest. 512'lik **instance slot grid'i** ayrı bir
katman ve duruyor (bkz. "Dungeon Void World & Grid Slot").

**Referans projeler:** Mythic Dungeons, DungeonsXL (aynı mimariyi kullanıyorlar).

---

### Mob Provider Abstraction

Plugin **mob yaratmaz** — var olan mob'ları (vanilla / MythicMobs / diğer) registry'ye
tanıtır ve spawn eder.

```
MobProvider (interface)
├── VanillaMobProvider     → EntityType enum ile spawn (her zaman aktif, fallback)
├── MythicMobsProvider     → MythicMobs API ile spawn (softdepend)
└── [gelecek]              → EliteMobs vb.
```

Runtime'da `Bukkit.getPluginManager().isPluginEnabled("X")` ile hangi provider'ın
aktif olacağı belirlenir. Hiçbiri yoksa vanilla fallback devrede kalır.

**statOverride flag'i:** Mob başına config'de bulunur.
- `true` → plugin mob'un health/damage/speed attribute'unu ezer
- `false` → mob'un kendi plugin'inin statları korunur

Sebep: MythicMobs gibi plugin'lerin kendi stat sistemi var, ezmek çakışma yaratır.

---

### Supply Mob — Villager Trade Sorunu

**Neden vanilla trade kullanılamaz:** Villager trade sistemi item-takas-item mantığında
çalışır ve emerald'a bağlıdır. Custom currency kabul etmez.

**Çözüm akışı:**
1. `PlayerInteractEntityEvent` yakalanır
2. Entity supply mob mu diye kontrol edilir
3. Event **cancel** edilir (vanilla trade GUI açılmaz)
4. Kendi custom GUI shop'umuz açılır
5. Satın alma → coin SQL'den düşülür, item verilir

Yani "köylü takas özelleştirmesi" değil, **köylü kılığında GUI shop**.

---

## YAZILAN SİSTEMLER

## Dungeon Void World & Grid Slot
**Dosya/Paket:** `com.takashi.dungeons.world` — `VoidChunkGenerator`, `DungeonWorldManager`,
`GridSlotManager`, `GridSlot`
**Ne işe yarar:** Instance'ların yaşadığı boş dünyayı kurar ve her instance'a çakışmayan bir
kare alan (slot) ayırır.
**Nasıl çalışır:**
1. `onEnable` → `DungeonWorldManager.load()` → dünya yoksa `VoidChunkGenerator` ile oluşturulur
2. `VoidChunkGenerator` hiçbir aşamayı üretmez (noise/surface/cave/decoration/structure/mob = false),
   biome `THE_VOID` — doğal mob spawn tablosu boş
3. Dünyaya dungeon gamerule'ları uygulanır: `doMobSpawning`, `doDaylightCycle`, `doWeatherCycle`,
   `doFireTick`, `mobGriefing`, patrol/trader spawn kapalı; `randomTickSpeed=0`, `spawnChunkRadius=0`
4. `GridSlotManager` satır düzeninde slot verir: `x=(i%columns)*slotSize`, `z=(i/columns)*slotSize`
5. Serbest bırakılan index'ler `TreeSet` havuzuna girer, en küçüğünden yeniden kullanılır
**Bağımlılıkları:** Sadece Bukkit API. WorldEdit gerekmez.
**Dikkat edilecek:**
- `release()` blokları **temizlemez**, sadece index'i geri verir. Aynı slot'a ikinci paste
  yapılırsa eski yapı altta kalır — blok temizliği FAZ 2'nin işi.
- `slot-size` (default 512) üretilebilecek en büyük dungeon'dan büyük olmalı, yoksa iki
  instance birbirine taşar.
- Slot index'i deterministik (index → konum hesaplanabilir), bu yüzden FAZ 7'de DB'ye sadece
  index yazmak yeterli.
- Config değişince slot geometrisi kayar; dünyada eski yapılar varken `slot-size` değiştirilmemeli.

## Schematic Servisi (async load + paste)
**Dosya/Paket:** `com.takashi.dungeons.schematic.SchematicService`, `TestRoomFactory`
**Ne işe yarar:** `.schem` dosyalarını okuyup dungeon dünyasına, istenen açıyla paste eder.
**Nasıl çalışır:**
1. Dosya `plugins/TakashiDungeons/schematics/` altından okunur — **her zaman async**
   (disk I/O + NBT parse main thread'i bloklar)
2. Elde edilen `Clipboard` bellekte cache'lenir; aynı oda yüzlerce kez paste edileceği için
   dosya bir kez okunur (`invalidateCache()` reload'da çağrılır)
3. Paste `ClipboardHolder` + `AffineTransform().rotateY(-derece)` ile yapılır
4. Paste thread'i: **FAWE varsa async**, yoksa main thread
5. `TestRoomFactory` FAZ 10 haritaları gelene kadar kod içinde placeholder oda üretir
   (`/tdungeons gen` → 5 dosya)
**Bağımlılıkları:** WorldEdit **veya** FAWE. İkisi de yoksa servis hiç kurulmaz, plugin yine
enable olur ve `/tdungeons status` "devre dışı" der.
**Dikkat edilecek:**
- **Async paste sadece FAWE ile güvenli.** Düz WorldEdit'in EditSession'ı thread-safe değildir;
  async yazmak dünya bozar. `config.yml → schematics.force-sync-paste` teşhis için var.
- `rotateY` açısı **negatif** veriliyor — WorldEdit'in kendi `//rotate` komutuyla aynı yön çıksın diye.
  İşaret değişirse haritacının editörde gördüğü yön ile üretilen yön ters düşer.
- Yazma yolunda `ClipboardFormats.findByFile` **kullanılamaz**: format tespiti için dosyayı açar,
  henüz var olmayan dosyada `NoSuchFileException` atar. Alias ile çözülüyor (`sponge.3`/`schem`/…).
- `copyEntities(false)`: schematic'e gömülü entity'ler her paste'te çoğalır ve FAZ 3'ün stat
  sisteminin dışında kalırdı.
- Odalar **tek sayı kenarlı** (17, 33): çift kenarda gerçek merkez blok olmaz, 90° döndürülen oda
  grid'de 1 blok kayar. Bu kural gerçek haritalar için de geçerli.
- Clipboard origin'i odanın **yatay merkezi**, taban seviyesi. Paste hedefi doğrudan slot merkezi
  olabiliyor; origin köşede olsaydı her açı için ayrı ofset hesabı gerekirdi.
- FAWE paste'ten sonra chunk'ları yüklü bırakmaz. `execute if block` gibi kontroller için
  önce `forceload` gerekir — test yazarken tuzak.

## Plugin Bootstrap & Entegrasyon Tespiti
**Dosya/Paket:** `com.takashi.dungeons.TakashiDungeonsPlugin`, `com.takashi.dungeons.command.DungeonsCommand`
**Ne işe yarar:** Plugin'in enable/disable yaşam döngüsü + opsiyonel (softdepend) plugin'lerin
sunucuda kurulu olup olmadığının tespiti.
**Nasıl çalışır:**
1. `onEnable` → `SOFT_INTEGRATIONS` listesi (`WorldEdit`, `FastAsyncWorldEdit`, `MythicMobs`, `Vault`)
   üzerinde `PluginManager.isPluginEnabled(...)` çağrılır
2. Sonuç `LinkedHashMap<String, Boolean>` içinde saklanır (sıra korunur), konsola yazılır
3. `/tdungeons status` bu tabloyu oyuncuya/konsola basar; `hasIntegration("MythicMobs")` ile
   diğer sistemler sorgular
4. `onDisable` → sadece log
**Bağımlılıkları:** Yok. Hiçbir softdepend kurulu olmasa da enable olur (out-of-box garantisi).
**Dikkat edilecek:**
- Tespit **enable anında bir kez** yapılır. Bir plugin sonradan (PlugMan vb.) yüklenirse tablo
  eskir — reload komutu geldiğinde `detectIntegrations()` yeniden çağrılmalı.
- `getCommand("tdungeons")` null dönerse severe log basılır: plugin.yml ile kod arasındaki
  uyumsuzluk sessizce yutulmaz.
- FAZ 3'teki `MobProvider` seçimi bu tabloyu kullanacak — provider mantığı buraya değil,
  kendi paketine yazılacak.

## Build → Deploy Akışı
**Dosya/Paket:** `pom.xml`, `scripts/build.ps1`, `scripts/server.ps1`, `run/`
**Ne işe yarar:** Kaynak koddan sunucuda çalışan jar'a tek komutla gitmek.
**Nasıl çalışır:**
1. `scripts/build.ps1` → JAVA_HOME'u Temurin **JDK 21**'e sabitler, `mvnw.cmd clean package` çalıştırır
2. `maven-shade-plugin` SQLite driver'ı jar'a gömer ve `org.sqlite` → `com.takashi.dungeons.libs.sqlite`
   olarak **relocate** eder
3. Resource filtering `plugin.yml` içindeki `${project.version}`'ı pom'daki sürümle doldurur
4. Üretilen jar `run/plugins/` içine kopyalanır (eski jar silinir — iki sürüm birden durursa sunucu ikisini de yükler)
5. `scripts/server.ps1` → Paper 1.21.8'i **JDK 21 java.exe'si ile açıkça** başlatır
**Bağımlılıkları:** Maven wrapper (`mvnw`), Temurin JDK 21, `run/paper.jar`.
**Dikkat edilecek:**
- Sistem PATH'inde JDK 26 var; Paper 1.21.8 Java 26'yı **kabul etmez**. Bu yüzden java yolu
  scriptlerde sabit — `mvn`/`java` PATH'ten çağrılırsa build veya sunucu kırılır.
- `run/` .gitignore'da; paper.jar ve dünya dosyaları repoya girmez.
- SQLite relocation yapıldığı için FAZ 7'de driver **`com.takashi.dungeons.libs.sqlite.JDBC`**
  adıyla yüklenecek, `org.sqlite.JDBC` ile değil.
