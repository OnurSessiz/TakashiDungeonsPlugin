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
