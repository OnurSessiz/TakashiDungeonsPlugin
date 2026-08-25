# ROADMAP — TakashiDungeonsPlugin

> Kural: Bir faz **çalışır durumda** bitmeden sonraki faza geçilmez.
> Tamamlananın önüne `[x]` konur.

---

## FAZ 0 — Kurulum ✅

> Hedef sürüm: **MC 1.21.8** (`paper-api 1.21.8-R0.1-SNAPSHOT`, `api-version: '1.21'`, **Java 21**)
> Paket: `com.takashi.dungeons` — Koordinat: `com.takashi:TakashiDungeons:0.1.0-SNAPSHOT`

- [x] GitHub repo oluştur (Java .gitignore + Maven/IDE/run eklemeleri)
- [x] Maven projesi kur (`pom.xml`, Paper API dependency)
- [x] `plugin.yml` — main class, softdepend listesi
- [x] Boş plugin sunucuda başarıyla enable oluyor
- [x] `maven-shade-plugin` yapılandır (SQLite driver shading için)
- [x] Test sunucusu (Paper) kurulumu + hızlı build→deploy akışı

## FAZ 1 — Çekirdek Generation (EN KRİTİK)

> Adım adım ilerliyor: **1A** altyapı ✅ → **1B** metadata + yerleştirme geometrisi ✅ →
> 1C kapı eşleştirme + çakışma → 1D graph üretimi
>
> **Algoritmanın tam spec'i: [`generation.md`](generation.md)** — 1B'ye başlamadan okunacak.

- [x] FAWE/WorldEdit API entegrasyonu, async paste çalışıyor
- [x] Void world oluşturma + grid slot yönetimi (instance başına konum ayırma)
- [x] Schematic metadata modeli (oda tipi, kapı yönleri, boyut)
      → oda başına `.yml`; kapı **anchor'ı** yazılıyor, yön ve boyut **türetiliyor**
- [x] Test için basit oda schematic'i (tek biome) — kod üretimli placeholder, `/tdungeons gen`
      → 8 oda: 5 temel + `test_giris` (1D için) + `test_long` (§4 tuzağı) + `test_even` (§9)
- [ ] Kapı eşleştirme mantığı (aday havuzu + ağırlıklı seçim + dönüş yanlılığı) ← **1C, sıradaki**
- [ ] 3B AABB çakışma testi + slot sınırı + ÖLÜ kapı işaretleme ← **1C**
      → `Aabb.intersects` yazıldı; eksik olan yerleşmiş odaları tutan katman
- [x] Rotation desteği (90/180/270 ile kapı yönü çevirme)
      → blok **ve** metadata seviyesinde çalışıyor. Rotasyon işareti ölçüldü: `+1` saat yönü.
        Gereken açı aranmıyor, `R = (d_p + 2 - d_c) mod 4` ile hesaplanıyor.
- [ ] Kritik path üretimi (giriş → boss zorunlu yolu)
- [ ] Yan dal (side room) ekleme
- [ ] Boyut değişkeni: small (3-6) / medium (7-12) / large (13-20)
- [ ] Çok kapılı odalar → labirent hissi (bir oda 2-3 odaya açılabilsin)
- [ ] **Milestone: Komutla boş ama gezilebilir bir dungeon üretiliyor**

## FAZ 2 — Instance Yaşam Döngüsü

- [ ] Instance oluşturma / kayıt / temizleme (chunk unload)
- [ ] Dungeon süre sistemi (doğada: silinme / lobby'de: sıfırlanma)
- [ ] Süre bitince içerideki oyuncuyu obje konumuna ışınlama
- [ ] Giriş objesi (sağ tık ile giriş) + obje spawn mantığı
- [ ] Doğada obje spawn sıklığı (op ayarlı)
- [ ] Lobby'de sabit nokta + yenilenme saati
- [ ] `/tp` `/tpa` engelleme (admin dahil)

## FAZ 3 — Mob Sistemi

- [ ] `MobProvider` interface tasarımı
- [ ] `VanillaMobProvider` (fallback, her zaman çalışır)
- [ ] `MythicMobsProvider` (softdepend, varsa aktif)
- [ ] Mob registry — komutla mob tanımlama
- [ ] Mob class sistemi: weak / normal / strong / super strong / boss
- [ ] Zorluk seviyeleri: easy / medium / hard (zırh + stat farkı)
- [ ] Attribute kontrolü (health / damage / speed) — aralık bazlı
- [ ] **`statOverride` flag'i** (custom mob statlarını koruma)
- [ ] Oda tipine göre spawn dağılımı
- [ ] Boss odası özel spawn mantığı

## FAZ 4 — Loot Sistemi

- [ ] Item class sistemi: common / uncommon / rare / ultra rare / legendary
- [ ] Weighted random selection algoritması (1000 tabanlı weight)
- [ ] Zorluk multiplier'ı (easy x1 / medium x1.75 / hard x2.5)
      → sadece rare ve üstüne uygulanır, fark common'dan düşülür
- [ ] Chest doldurma sistemi
- [ ] Op tarafından item tanımlama (nadirlik + özellik)
- [ ] Mob drop tablosu

## FAZ 5 — Party Sistemi

- [ ] Party veri modeli
- [ ] `/party invite`, `/party leave`, `/party kick` komutları
- [ ] Chat üzerinden davet
- [ ] Obje sağ tık → party üyelerine davet gönderme
- [ ] Party ile birlikte instance'a giriş
- [ ] Party dağılırsa / oyuncu çıkarsa ne olacağı

## FAZ 6 — Supply Mob (Opsiyonel Modül)

- [ ] Dungeon girişine supply mob yerleştirme (op tanımlı)
- [ ] `PlayerInteractEntityEvent` cancel + custom GUI shop
- [ ] Satılan item tanımlama (heal, golden apple, yemek, teçhizat)
- [ ] Coin entegrasyonu (Market addon varsa)

## FAZ 7 — Veritabanı

- [ ] SQLite bağlantı katmanı
- [ ] MySQL opsiyonu
- [ ] Şema tasarımı + migration mantığı (versiyon alanı)
- [ ] Async DB erişimi (main thread bloklanmayacak)
- [ ] Concurrency kontrolü (race condition önleme)

## FAZ 8 — Public API

- [ ] Addon'ların kullanacağı interface'ler
- [ ] Custom event'ler (DungeonEnterEvent, MobKillEvent, DungeonCompleteEvent…)
- [ ] API dokümantasyonu
- [ ] Versiyon garantisi (breaking change yok)

## FAZ 9 — GUI Editörler

- [ ] Mob ayar GUI'si (stat aralıkları)
- [ ] Loot ayar GUI'si (class + weight)
- [ ] Dungeon ayar GUI'si (boyut, zorluk, süre)
- [ ] Sayfalama + input handling

## FAZ 10 — Harita İnşası (Paralel yürür, 2-3 kişi)

- [ ] Biome: Dark Forest — 10 oda tipi
- [ ] Biome: End — 10 oda tipi
- [ ] Biome: Nether — 10 oda tipi
- [ ] Biome: Maden — 10 oda tipi
- [ ] Her oda için schematic export + metadata girişi
- [ ] Boss odaları (biome başına ayrı)

## FAZ 11 — Addon: TakashiRanks

- [ ] Ayrı repo + Maven projesi
- [ ] Core API'ye hook
- [ ] XP kazanma (mob kill, dungeon complete)
- [ ] Rank tier sistemi (threshold bazlı)
- [ ] Prestige alanı (baştan veri modelinde olsun)

## FAZ 12 — Addon: TakashiMarket

- [ ] Ayrı repo + Maven projesi
- [ ] Vault entegrasyonu (custom currency)
- [ ] Coin kazanma mantığı
- [ ] Market GUI
- [ ] Concurrency güvenli coin işlemleri

## FAZ 13 — Marketplace Hazırlığı

- [ ] Çoklu sunucu versiyonunda test (1.20+ / 1.21+)
- [ ] Performans testi (çok instance açıkken TPS)
- [ ] Default config'ler + örnek schematic paketi
- [ ] Kullanıcı dokümantasyonu
- [ ] Tanıtım görselleri / video
