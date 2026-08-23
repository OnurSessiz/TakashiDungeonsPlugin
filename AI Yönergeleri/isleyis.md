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

Endüstri standardı **room-based procedural generation** (Diablo, Isaac, Gungeon mantığı).

**Akış:**
1. Boyut seçilir → hedef oda sayısı belirlenir (small 3-6 / medium 7-12 / large 13-20)
2. **Kritik path** üretilir: giriş odasından başlayıp hedef uzunluğa kadar zincir
3. Path'in **son node'u boss odası** olarak sabitlenir (random değil, atama)
4. Kalan oda kotası **yan dallar** olarak random walk ile eklenir
5. Her adımda **constraint**: sadece mevcut açık kapıyla uyumlu odalar candidate olur
6. Çok kapılı odalar (2-3 çıkışlı) labirent hissi yaratır — dallanma noktaları
7. Seçilen odalar void world'deki grid slot'una async paste edilir

**Rotation:** Aynı schematic 90/180/270 döndürülerek farklı kapı yönelimleri elde
edilir. Bu yüzden her oda için ayrı kapı varyantı schematic'i üretilmez.

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
