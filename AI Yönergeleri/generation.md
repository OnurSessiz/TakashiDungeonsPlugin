# GENERATION — Dungeon Üretim Sistemi

> **Durum:** FAZ 1A yazıldı ve sunucuda doğrulandı. 1B / 1C / 1D bu dosyadaki tasarıma göre
> yazılacak — o kısımların kodu **henüz yok**.
>
> **Bu dosya nedir:** 2026-08-24 tasarım oturumunun temize çekilmiş çıktısı. Yeni bir oturum
> bu dosyayı okuyup `anahedef.md` + `Roadmap.md` dışında başka bir şeye ihtiyaç duymadan
> 1B'ye başlayabilmeli.
>
> **Kural:** Tasarım değişirse burası güncellenir. `isleyis.md` sistem *kayıtlarını* tutar,
> bu dosya *algoritmayı* tutar.

---

## 0. Neden bu tasarım

Odalar prosedürel olarak **üretilmiyor**, prosedürel olarak **diziliyor**. Odalar harita
ekibinin elle çizdiği schematic'ler; motorun işi hangi odayı, nereye, hangi açıyla koyacağına
karar vermek.

Bu yüzden cellular automata (mağara hissi verir, oda vermez) ve BSP (dikdörtgen bölme, elle
çizilmiş odaya uymaz) elendi. Kullanılan yaklaşım **socket / jigsaw tabanlı yerleştirme** —
Mojang'ın köy, bastion, ancient city ve trial chamber üretiminde kullandığı yöntemin aynısı.

---

## 1. İki ayrı katman — karıştırma

Projede iki farklı "grid" var. Aynı şey değiller.

| Katman | Ne yapar | Durum |
|---|---|---|
| **Instance slot grid'i** | İki party'nin dungeon'ını birbirinden ayırır. 512 bloklık kareler, `index → konum` deterministik. | **Yazıldı** (1A) |
| **Dungeon içi yerleşim** | Odaları birbirine bağlar. Hücre yok, **anchor tabanlı serbest yerleşim**. | Tasarlandı (1B-1D) |

Bir dungeon tek bir slot'un içinde yaşar. Slot'un içinde odalar hücrelere oturmaz — kapı
noktalarından birbirine kenetlenir.

> Eski tasarımda dungeon içi de sabit hücre grid'iydi. **Terk edildi.** Sebebi §2'de.

---

## 2. Temel kavramlar

### Oda şablonu (room template)
Bir `.schem` dosyası + yanındaki `.yml` metadata. Şablon **döndürülerek** kullanılır, kapı
varyantı olarak çoğaltılmaz.

### Origin = odanın merkezi
Clipboard origin'i odanın **yatay merkezinde, taban seviyesinde**. Rotation her zaman origin
etrafında döner; merkezde olduğu için oda kendi ekseninde dönüyor ve paste hedefi doğrudan
hesaplanabiliyor.

### Kapı anchor'ı
Kapı açıklığının **taban-merkez bloğu**, origin'e göre yerel koordinat olarak saklanır.

Bunun iki sonucu var ve ikisi de kritik:

1. **Yön saklanmaz, türetilir.** Anchor vektörünün kendisi zaten merkeze göre delta olduğu
   için duvar ondan hesaplanır (§4). Metadata'da `yon: KUZEY` yazmıyoruz — "metadata kuzey
   diyor ama anchor doğu duvarında" tutarsızlığı doğamaz.
2. **Kapı duvarın ortasında olmak zorunda değil.** Anchor açıkça yazıldığı için kapı duvarın
   herhangi bir yerinde olabilir, aynı duvarda birden çok kapı olabilir.

### Neden anchor, neden hücre değil
- Odalar **aynı boyutta olmak zorunda değil** — 9×25 koridor, 17×17 salon, 33×33 boss odası
  aynı dungeon'da yaşayabiliyor. Hücre grid'i hepsini tek ölçüye zorluyordu.
- **Rotasyon aranmıyor, hesaplanıyor** (§5). Hücre şemasında "batıya bakan kapısı olan odalar"
  diye aday havuzu daraltılıyordu; burada her oda her bağlantıya aday.
- **Düz sıra kendiliğinden kırılıyor** — kapı ofsetleri yanal kayma üretiyor (§6.4).

Bedeli: çakışma testi artık zorunlu (§5.3). Hücre grid'inde çakışma matematiksel olarak
imkânsızdı.

---

## 3. Koordinat konvansiyonları

```
Minecraft:  +X = Doğu    +Z = Güney    Kuzey = -Z
Yön index:  K=0   D=1   G=2   B=3      (saat yönü, yukarıdan bakışta)

karşıt(d)  = (d + 2) mod 4
adım(d)    = K:(0,0,-1)  D:(1,0,0)  G:(0,0,1)  B:(-1,0,0)
```

### Rotasyon
`R ∈ {0,1,2,3}` = 90°'lik saat yönü adım sayısı.

```
Yön:    d' = (d + R) mod 4

Nokta:  R=0 → ( x, y,  z)
        R=1 → (-z, y,  x)
        R=2 → (-x, y, -z)
        R=3 → ( z, y, -x)
```

Y rotasyondan etkilenmez — `rotateY` sadece X-Z düzleminde döndürür. Bu, çok katlı oda
desteğini bedavaya getiriyor (§5.2, adım 4).

**Doğrulama:** Kuzey birim vektörü `(0,-1)`. R=1 uygulanınca `(1,0)` = Doğu. Yön indeksinde
de `0+1=1` = Doğu. Tutarlı.

### WorldEdit tarafı
Kodda `AffineTransform().rotateY(-derece)` kullanılıyor — WorldEdit'in kendi `//rotate`
komutuyla aynı yönü versin diye. Haritacının editörde gördüğü yön ile motorun ürettiği yön
birebir aynı olmalı.

> ⚠️ **Açık:** İşaretin gerçekten saat yönü (`d+1`) olduğu **henüz kanıtlanmadı.** 1A testinde
> kullanılan `test_corridor` K-G simetrik olduğu için saat yönü / ters yön ayırt edilemedi.
> 1B'nin ilk işi: `test_corner` (K+D kapılı, asimetrik) 90° döndürülür.
> **D+G çıkarsa `+1` doğru, B+K çıkarsa `-1`.** Yanlışsa üretilen bütün dungeon'ların kapıları
> tutmaz — sessizce yanlış çalışan türden hata, koda geçmeden ölçülecek.

---

## 4. Kapı hangi duvarda — merkez-delta yöntemi

Anchor vektörü `v = (dx, dy, dz)` zaten merkeze göre delta. Duvar ondan çıkıyor.

**Naif kural (SADECE kare odada doğru):** hangi bileşen mutlak değerce büyükse o eksen.

Dikdörtgen odada patlıyor. 9 geniş × 25 uzun koridorda, doğu duvarında güney ucuna yakın kapı:

```
v = (dx=+4, dz=+11)
|dz| > |dx|  →  "güney duvarı"      ← YANLIŞ, kapı doğu duvarında
```

**Doğru kural — yarı boyutlara normalize et:**

```
nx = dx / yariGenislik      // 4 / 4  = 1.00
nz = dz / yariUzunluk       // 11 / 12 = 0.917

|nx| >= |nz|  →  dx > 0 ? DOĞU  : BATI
else          →  dz > 0 ? GÜNEY : KUZEY
```

Mantığı: hangi bileşen ±1'e ulaşıyorsa nokta o duvara değiyordur. Kare odada iki formül aynı
sonucu verir — bu yüzden test odalarında fark **edilmez**, ilk dikdörtgen oda geldiğinde patlar.

---

## 5. Yerleştirme algoritması

Ebeveyn odaya bağlı, boş bir kapıdan yeni oda takılıyor.

### 5.1 Girdi
```
A_p  = ebeveyn kapısının anchor'ı, DÜNYA koordinatı
d_p  = o kapının dışa bakan yönü, DÜNYA çerçevesinde
       (ebeveynin rotasyonu zaten uygulanmış)
```

### 5.2 Adımlar

**1) Çocuk adayı seç.** Aday = `(oda şablonu × o şablonun kapılarından biri)` çifti.
Rotasyonun üstünde değil, **bu çiftin** üstünde döndüğüne dikkat: 3 kapılı bir oda 3 farklı
şekilde oturtulabilir, hangi kapıdan bağlandığı diğer kapılarının nereye bakacağını belirler.
Çeşitlilik buradan geliyor.

**2) Rotasyonu hesapla.** Aranmaz, tek bir değerdir:

```
v_c = çocuk kapısının yerel anchor'ı
d_c = duvar(v_c)                       // §4
R   = (d_p + 2 - d_c) mod 4
```

*(Eşdeğeri: `d_c` karşıt(`d_p`) olana kadar +90 döndürmek. Aynı sonuç, 4 iterasyon.)*

**3) Konumu hesapla — sırt sırta konvansiyonu.**

```
O_c = A_p + adım(d_p) - döndür(v_c, R)
```

Yerleştirdikten sonra çocuğun kapı anchor'ı `A_p + adım(d_p)` oluyor: ebeveynin anchor'ından
tam bir blok dışarıda. İki duvar sırt sırta geliyor, ikisinde de delik var, geçit açık.

> **Neden sırt sırta, neden çakışık değil:** Duvarlar çakışırsa ikinci paste birincinin
> duvarını eziyor ve sonuç **paste sırasına** bağlı hâle geliyor. Sıra bağımlılığı olan üretim
> hata ayıklanamaz. Sırt sırtada hiçbir oda başkasının bloğuna dokunmuyor.
> Yan fayda: 2 bloklu geçit, kalın kapı çerçevesi gibi duruyor.

**4) Y kendiliğinden hizalanıyor.** Rotasyon Y'yi korduğu için `O_c.y = A_p.y - v_c.y`.
Yani çocuğun tabanı, kapısının Y'si ebeveynin kapısının Y'siyle çakışacak şekilde oturuyor.
Çok katlı oda (alttan giriş, üstten devam) ek kod istemiyor — anchor'ın Y'si işi görüyor.

**5) Çakışma testi.** ← *Bu adım algoritmanın geri kalanı kadar zorunlu.*

```
kutu = çocuğun 3B AABB'si, R ve O_c uygulanmış

eğer kutu slot sınırını taşıyorsa            → adayı reddet
eğer kutu yerleşmiş odalardan biriyle kesişiyorsa → adayı reddet
```

**3B olmak zorunda**, 2B ayak izi yetmez: çok katlı dungeon'da iki odanın ayak izi çakışıp
hacimleri çakışmayabilir — zaten istenen şey budur.

### 5.3 Geri çekilme

```
adaylar = karıştır( tüm (şablon × kapı) çiftleri )
her aday için:
    R, O_c hesapla
    çakışma testi geçerse → yerleştir, bitir
hiçbiri geçmediyse:
    bu kapıyı ÖLÜ işaretle → §7 tıpa basılacak
```

Ölçek endişesi yok: en büyük dungeon 20 oda, kaba kuvvetle 20 kutu testi. Spatial hash gereksiz.

---

## 6. Graf üretimi

### 6.1 Boyut → oda sayısı
| Boyut | Hedef oda |
|---|---|
| small | 3-6 |
| medium | 7-12 |
| large | 13-20 |

### 6.2 Kritik path önce
```
1. hedef oda sayısı seçilir                       (medium → örn. 10)
2. path uzunluğu = round(hedef × 0.65), min 2     (→ 7)
3. giriş odasından (tip: giris) zincir kurulur
4. path'in SON düğümü boss odası — random değil, ATAMA
5. kalan kota (10-7 = 3) yan dallara gider
```

**Neden path önce, boss sona atanıyor:** Odaları rastgele serpip "en uzaktakine boss koyalım"
denirse boss'un giriş'ten kaç oda uzakta olduğu kontrol edilemez — bazı dungeon'lar 2 odada
biter, bazıları 15. Path'i önce kurmak **oynanış süresini garanti altına alır**. Prosedürel
üretimde aranan şey bu: rastgelelik çeşitlilik için, iskelet garanti için.

### 6.3 Yan dallar
Kritik path bir odaya girdiğinde odanın diğer kapıları **kullanılmamış** kalıyor. Yan dal
üretimi bu boş kapılardan başlıyor, kota bitene ya da yer kalmayana kadar.

Çok kapılı odalar (3-4 çıkışlı) dallanma noktası. Oyuncu odaya girip 3 çıkış görüyor; biri
boss'a, ikisi ganimete gidiyor, hangisinin hangisi olduğunu bilmiyor. Labirent hissi bu.

### 6.4 Düz sıra nasıl kırılıyor
İki mekanizma, ikisi de kullanılacak:

**Kapı ofsetleri (asıl motor).** Kapılar hep duvarın ortasındaysa kuzeye zincirlenen odalar
cetvelle çizilmiş gibi dizilir. A'nın kuzey kapısı ortadan +5 sağda, B'nin güney kapısı −3
soldaysa B yanal olarak 8 blok kayarak oturur. Zincir boyunca kaymalar birikince yerleşim
kendiliğinden kıvrılıyor.

**Dönüş yanlılığı.** Aday sıralamasında "ebeveynle aynı yöne devam eden" seçeneklere ceza
uygulanır. Basit ağırlık, tek satır.

---

## 7. Tıpa — boş kapı kapatma

Graf bitince bazı kapılar boşluğa açılıyor olacak. İki sebepten:
- yan dal kotası dolduğu için hiç denenmedi
- denendi ama bütün adaylar çakıştı (§5.3 → ÖLÜ)

**Karar: odayı çoğaltma, kapıyı kapat.**

Kapı açıklığının yeri spesifikasyonla sabit olduğu için motor oraya duvar basabilir.
İki uygulama, ikisi de desteklenecek:

| Yöntem | Harita işi | Görünüm |
|---|---|---|
| **Prosedürel** — motor açıklığı tek blok tipiyle doldurur | 0 dosya | Düz duvarlı odada ayırt edilemez |
| **Tıpa schematic'i** — biome'un duvar dokusuna uygun küçük parça | **4 dosya** (biome başına 1) | Neredeyse görünmez |

### Neden kapı-sayısı varyantı yapılmıyor
"Her odanın 1/2/3 kapılı versiyonu olsun, boş kapı çıkınca bir eksiğini kullan" fikri
değerlendirildi ve **elendi**. Sebebi matematiksel:

Kapı **sayısı** versiyonu tanımlamıyor, kapı **kümesi** tanımlıyor. 3 kapılı `{K,D,G}` odada
doğu boşta kalırsa `{K,G}` (karşılıklı çift) lazım; başka senaryoda `{K,D}` (komşu çift)
lazım — farklı geometri, farklı duvar. Yani "2 kapılı versiyon" diye tek dosya yok.

4 yönle 15 olası kombinasyon var. Rotasyon bunları 5 şekle indiriyor:

| Şekil | Örnek | Rotasyonla ürettiği |
|---|---|---|
| Tek kapı | {K} | 4 |
| Karşılıklı çift | {K,G} | 2 |
| Komşu çift | {K,D} | 4 |
| Üç kapı | {K,D,G} | 4 |
| Dört kapı | {K,D,G,B} | 1 |
| | | **toplam 15** ✓ |

Matematik temiz ama maliyet: 40 oda × 5 şekil = **200 schematic**. Rotasyon kararıyla
120'den 40'a indirdiğimiz harita yükü 200'e çıkardı. Harita ekibi projenin en dar boğazı.

Tıpa yolu aynı görsel sonucun neredeyse tamamını **4 dosyayla** veriyor.

**Kaçış kapısı bırakılıyor:** Metadata'da bir oda kendi varyantlarını *ilan edebilir*. Boss
odası, girişteki imza odası gibi "burası mükemmel görünsün" denen yerlerde tam çizilmiş
varyant konur; motor varsa onu, yoksa tıpayı kullanır. Motor kodu aynı, harita yatırımı
isteğe bağlı.

---

## 8. Metadata şeması

Her schematic'in yanında aynı adlı `.yml`. **Merkezi tek dosya değil** — harita ekibi paralel
çalışacağı için merge conflict çıkmasın diye.

```yaml
# schematics/test_cross.yml
tip: normal          # giris | normal | boss
agirlik: 100         # aday seçiminde ağırlıklı rastgele (loot weight mantığı)

# Kapı anchor'ları: ORIGIN'E (oda merkezine) göre yerel koordinat.
# [x, y, z] — kapı açıklığının taban-merkez bloğu.
# Yön BURADA YAZMIYOR, §4 ile hesaplanıyor.
kapilar:
  - [ 0, 1, -8]      # kuzey duvarı
  - [ 8, 1,  0]      # doğu duvarı
  - [ 0, 1,  8]      # güney duvarı
  - [-8, 1,  0]      # batı duvarı
```

**`y: 1` neden:** kapı açıklığının taban bloğu, oda tabanının bir üstü. Çift katlı odada üst
kapı `[0, 9, -8]` olur — sistem değişmez.

**`door1/door2/door3` nerede:** listedeki sıra. Doldurma sırası **değil** (hangi kapının
dolacağına geometri karar veriyor, yazım sırası değil), **adres**. Hangi kapı bağlandı, hangi
kapıya tıpa basılacak — bu indeksle takip ediliyor.

### Çalışma zamanı durumu (dosyada değil, bellekte)
```
her yerleştirilmiş oda için:
    sablon, R, O_c, AABB
    kapı[i] → BAGLI (hangi odaya) | BOS | OLU
```

---

## 9. Harita ekibi kuralları

- **Origin odanın yatay merkezinde, taban seviyesinde** olacak. Şablon dosyasında işaretli.
- **Kapı anchor'ı = açıklığın taban-merkez bloğu.** Bir blok kayarsa duvarlar iç içe geçer ya
  da arada delik kalır. Bu sistemlerin kırıldığı tek yer burası.
- Kapı açıklığı standardı: **3 geniş × 3 yüksek** (`TestRoomFactory` bunu üretiyor).
- Oda içi aydınlatma schematic'in kendi ışık kaynaklarından gelecek — dungeon dünyasında
  güneş yok, `doDaylightCycle` kapalı.
- Schematic'e **entity gömmeyin.** Mob'lar FAZ 3'te motor tarafından spawn ediliyor;
  gömülü entity her paste'te çoğalır ve stat sisteminin dışında kalır (`copyEntities(false)`).

### Tek sayı kenar kuralı — KALDIRILDI
Eski hücre-grid tasarımında oda kenarları tek sayı (17, 33) olmak zorundaydı: çift kenarda
odanın gerçek merkez bloğu yok, rotasyon merkezi yarım blok kayıyor ve dönen oda hücreye
1 blok ofsetle oturuyordu.

Anchor tabanlı yerleşimde **bu kısıt gerekmiyor.** Yerleştirme bounding box'a göre değil
anchor'a göre yapıldığı için, odanın origin etrafında asimetrik olması kapı çakıştırmasını
bozmuyor — asimetri AABB'ye yansıyor, o da rotasyon sonrası hesaplandığı için sorun olmuyor.

> ⚠️ Bu, WorldEdit'in transform davranışı üzerine **akıl yürütmeyle** varılmış sonuç, testle
> değil. 1B'de çift kenarlı asimetrik bir test odasıyla doğrulanacak. Doğrulanana kadar harita
> ekibine "serbest" denmesin.

---

## 10. Yazılmış olan — FAZ 1A

Bu bölüm mevcut kodu tarif eder. Detaylı sistem kayıtları `isleyis.md`'de.

| Ne | Nerede |
|---|---|
| Void dünya + gamerule'lar | `world/DungeonWorldManager.java`, `world/VoidChunkGenerator.java` |
| Instance slot grid'i (512) | `world/GridSlotManager.java`, `world/GridSlot.java` |
| Async schematic yükleme + cache + rotasyonlu paste | `schematic/SchematicService.java` |
| Kod üretimli 5 placeholder oda | `schematic/TestRoomFactory.java` |
| Test komutları | `command/DungeonsCommand.java` — `gen`, `paste`, `slots`, `free`, `world`, `list` |

**Doğrulanmış:** 13/13 blok kontrolü (`execute if block`) — zemin, tavan ışığı, 4 kapı
açıklığı, köşe duvarı, iç hacmin boşluğu; rot=0 koridorda kapılar K-G, rot=90'da D-B ve
kuzey duvarı dolu. Paste 17³ oda için ~250-300 ms, async thread'de.

**Test tuzağı:** FAWE paste sonrası chunk'ları yüklü bırakmıyor. `execute if block` ile
kontrol etmeden önce `forceload add` gerekiyor.

---

## 11. Açık sorular — 1B'de kapanacak

1. **Rotasyon işareti.** `test_corner` (K+D) 90° döndürülecek. D+G → `+1` doğru.
   B+K → `-1`, bütün formüller ters çevrilecek. *(§3)*
2. **Tek sayı kenar kuralının kalktığı** çift kenarlı asimetrik test odasıyla doğrulanacak. *(§9)*
3. **Tıpa yöntemi:** prosedürel mi, biome schematic'i mi ilk yazılacak? Prosedürel daha hızlı
   ve 1D'yi bloklamaz; biome tıpaları FAZ 10 ile birlikte gelebilir. *(§7)*
4. **Koridor parçası olacak mı?** Odalar doğrudan sırt sırta bağlanabiliyor. Araya 2 kapılı
   ince koridor parçaları sokmak yerleşimi daha organik yapar ama oda kotasını nasıl etkileyeceği
   (koridor "oda" sayılacak mı) karara bağlanmalı. *(§6)*
5. **Giriş odası** tekil mi, her biome'un kendi giriş odası mı?

---

## 12. Sıradaki adım — FAZ 1B

1. `test_corner` ile rotasyon işaretini ölç *(her şeyden önce)*
2. `RoomTemplate` + `DoorAnchor` veri modeli
3. `.yml` okuyucu, schematic yanındaki dosyadan
4. `TestRoomFactory` üretilen odalar için `.yml` de yazsın
5. §4'ün duvar hesabı + §3'ün rotasyon fonksiyonları (saf fonksiyon, unit test edilebilir)
6. **Doğrulama:** iki odayı kapılarından bağla, geçidin gerçekten açık olduğunu blok testiyle göster
