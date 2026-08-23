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

_(Henüz yok — Faz 0 başlamadı)_
