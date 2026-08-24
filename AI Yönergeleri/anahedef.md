# ANA HEDEF — TakashiDungeonsPlugin

> Bu dosya her yeni oturumun BAŞINDA okunacak. Projenin değişmeyen çerçevesi ve
> oturumlar arası hatırlanması gereken kurallar burada.
>
> Okuma sırasını ve build/test akışını kökteki `CLAUDE.md` taşıyor — yeni oturum onu
> otomatik yükler ve buraya yönlendirir.

---

## 1. Proje Nedir

Minecraft (Paper/Spigot) için **prosedürel dungeon sistemi** plugin'i.
Belirli bir sunucu için değil — **marketplace'te satılmak üzere** geliştiriliyor.
Bu yüzden her şey konfigüre edilebilir ve kurulum senaryosundan bağımsız çalışmalı.

## 2. Ürün Mimarisi

| Jar | İçerik | Durum |
|---|---|---|
| **TakashiDungeons** (core) | Generation, mob spawn, loot, party, instancing | Ana ürün |
| **TakashiRanks** (addon) | XP + rank sistemi | Ayrı satılır |
| **TakashiMarket** (addon) | Coin + market sistemi | Ayrı satılır |

Addon'lar **ayrı jar, ayrı repo**. Core'un expose ettiği **public API** üzerinden
haberleşirler. Core, addon'lar olmadan tek başına tam fonksiyonel çalışmalı.

## 3. Teknik Kararlar (DEĞİŞTİRİLMEYECEK)

- **Build tool:** Maven (`pom.xml`)
- **Platform:** Paper API (Spigot uyumlu kalacak şekilde)
- **Veritabanı:** SQLite (default) + MySQL (opsiyon). YAML'de player data TUTULMAZ.
- **Schematic:** FAWE/WorldEdit `.schem` + async paste
- **Economy:** Vault API üzerinden (custom currency Vault'a bağlanır)
- **Dungeon yerleşimi:** Ayrı **void world**, grid slot'lu **instancing**
- **Oda çeşitliliği:** Schematic **rotation** ile (90/180/270) — kapı varyantı ayrı schematic olarak çoğaltılmaz

## 4. Kalıcı Kurallar — HER ZAMAN YAP

- [x] Her yeni sistem yazıldığında `isleyis.md` güncellenecek
- [x] Her oturum sonunda `sonislem.md` güncellenecek
- [x] Mob spawn'da **`statOverride: true/false`** flag'i her zaman bulunacak
      (custom mob'ların kendi stat sistemi ezilmesin diye)
- [x] MythicMobs ve benzeri entegrasyonlar `softdepend` — **zorunlu bağımlılık değil**
- [x] Hiçbir mob plugin'i yokken **vanilla fallback** çalışacak (out-of-box garanti)
- [x] Yeni mob kaynağı eklemek `MobProvider` interface'i implement etmekle olacak
- [x] Konfigürasyon önce **YAML** ile çalışır hale getirilir, GUI editör SONRA gelir

## 5. Kalıcı Kurallar — ASLA YAPMA

- [ ] Player data'yı YAML/flat file'da tutma
- [ ] Core plugin'i bir mob plugin'ine **hard depend** etme
- [ ] Public API'de breaking change yapma (addon'lar kırılır)
- [ ] Aynı anda hem "instanced dungeon" hem "dünyaya kalıcı yazma" destekleme —
      **sadece instanced** (karar verildi)
- [ ] Vanilla villager trade sistemiyle custom coin bağlamaya çalışma —
      çalışmaz, `PlayerInteractEntityEvent` cancel + custom GUI kullanılacak
- [ ] Dungeon içinde `/tp`, `/tpa` çalışmayacak (admin dahil) — engellenecek

## 6. Çalışma Prensibi (Onur'un tercihi)

- Komple çözüm yerine **kavramsal rehberlik + "neden" açıklaması**
- Hedge'li çok seçenekli cevap değil, **net ve taahhütlü** cevap
- Adım adım ilerleme: her faz **çalışır** halde bitmeden sonrakine geçilmez

## 7. Gerçekçi Zaman Beklentisi

Marketplace kalitesinde tam ürün: **2-4 ay** (tek geliştirici).
1 haftada bitmez — faz faz ilerleniyor. Bkz. `Roadmap.md`.

Harita inşası 2-3 kişilik ekiple paralel yürüyecek:
**4 biome × 10 oda tipi** = 40 base schematic (rotation ile çoğaltılacak).
