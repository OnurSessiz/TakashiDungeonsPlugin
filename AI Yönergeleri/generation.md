# GENERATION — Dungeon Üretim Sistemi

> **Durum:** FAZ 1A, **1B** ve **1C** yazıldı, üçü de sunucuda doğrulandı. 1D bu dosyadaki
> tasarıma göre yazılacak — o kısmın kodu **henüz yok**.
>
> 1B'de kapanan iki açık soru (§3 rotasyon işareti, §9 tek sayı kenar) artık **ölçülmüş**
> durumda; bu dosyadaki formüller varsayım değil.
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

> ✅ **ÖLÇÜLDÜ (2026-08-25) — `+1` doğru, işaret saat yönü.**
>
> `SchematicService`'in paste'te kullandığı `AffineTransform().rotateY(-derece)` doğrudan
> JVM'de çalıştırıldı (sunucu gerekmedi — transform saf matematik):
>
> | R | nokta | yön dönüşümü |
> |---|---|---|
> | 0 | `( x, y,  z)` | K→K D→D G→G B→B |
> | 1 | `(-z, y,  x)` | **K→D D→G G→B B→K** |
> | 2 | `(-x, y, -z)` | K→G D→B G→K B→D |
> | 3 | `( z, y, -x)` | K→B D→K G→D B→G |
>
> Dördü de yukarıdaki nokta formülleriyle birebir eşleşti. `d' = (d - R) mod 4` hipotezi
> R=1 ve R=3'te elendi. `test_corner` (K+D) 90° döndürülünce **D+G** çıkıyor — beklenen sonuç.
>
> Ölçüm neden koddan önce yapıldı: işaret ters olsaydı üretilen bütün dungeon'ların kapıları
> tutmazdı ve hata *sessiz* olurdu — oda pastelenir, geçit kapalı çıkardı.

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

**Bu tuzak için kalıcı test odası var:** `test_long` (9 geniş × 25 uzun), doğu duvarında
güney ucuna yakın kapı → `v = (+4, +10)`. Naif kural `|dz| > |dx|` olduğu için "güney" der;
normalize edilmiş kural `nx = 4/4 = 1.00 > nz = 10/12 = 0.83` ile doğru cevabı (**doğu**)
verir. Sunucuda doğrulandı. Formül bozulursa bu oda anında yakalar.

**Normalizasyon yön başına ayrı yapılıyor** — doğu ve batı uzanımları ayrı okunuyor.
Sebebi §9: tek sayı kenar kuralı kalktığı için origin artık odanın tam ortasında olmak
zorunda değil, oda origin'e göre asimetrik olabiliyor. Tek bir "yarı genişlik" değeri
asimetrik odada yanlış sonuç verirdi.

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

### 5.4 Aday seçimi — ağırlık kime ait

> **Karar (2026-08-25): `agirlik` ŞABLONA ait, `(şablon × kapı)` çiftine değil.**
> Seçim iki aşamalı yapılır.

```
1) ŞABLON seç        — ağırlıklı rastgele, havuzdan çekilir (yerine konmadan)
2) KAPI seç          — o şablonun kapıları karıştırılır, sırayla denenir
3) hiçbiri oturmazsa — şablon havuzdan düşer, 1'e dön
4) havuz boşalırsa   — kapı ÖLÜ (§7 tıpa)
```

**Neden çifte değil.** Aday bir `(şablon × kapı)` çifti olduğu için 4 kapılı bir oda
4 aday üretiyor. Ağırlık çifte ait olsaydı o oda ağırlığını 4 kez sayardı — haritacının
yazmadığı bir özellik (kapı sayısı) yazdığı değeri ezerdi.

Mevcut test setiyle somut sonuç:

| Oda | Kapı | Ağırlık | Çifte ait olsa | **Şablona ait (seçilen)** |
|---|---|---|---|---|
| test_corridor | 2 | **150** | %21.4 | **%25.4** |
| test_corner | 2 | 120 | %17.1 | %20.3 |
| test_cross | 4 | 100 | **%28.6** | %16.9 |
| test_even | 3 | 80 | %17.1 | %13.6 |
| test_long | 2 | 80 | %11.4 | %13.6 |
| test_deadend | 1 | 60 | **%4.3** | %10.2 |

Sadece dağılım kaymıyor — **sıralama tersine dönüyor.** Haritacı koridora 150, cross'a 100
yazmış; çifte ait olsaydı cross (%28.6) koridoru (%21.4) geçerdi. Config'in söylediğinin
tersi olurdu.

Ve sapma **birikiyor**: çok kapılı oda koydukça daha fazla boş kapı açılıyor, her boş kapı
yine çok kapılı odayı kayırıyor. 20 odalık dungeon'da tek seferlik değil, büyüyen bir sapma.

**Marketplace gerekçesi:** `agirlik`'i sunucu sahibi YAML'den düzenleyecek. "200 yazarsam
100'ün iki katı sıklıkta gelir" beklentisi tutulmak zorunda. Kapı sayısının bunu sessizce
ezmesi, sebebi hiçbir yerde yazmadığı için teşhis edilemeyen bir hata olurdu.

**Karşı argüman ve neden yetmiyor.** 4 kapılı oda motora gerçekten daha faydalı: daha çok
geometrik duruma cevap veriyor, grafın büyümesini sürdürüyor. Ama bu **ayrı bir endişe**,
haritacının ağırlığıyla karıştırılmamalı. Dallanmayı teşvik etmek istersek açık ve
kapatılabilir bir config düğmesi eklenir — §6.4'ün dönüş yanlılığı da tam olarak böyle,
ağırlığın içine gömülmüyor, aday sıralamasında ayrı bir katsayı olarak duruyor.

**Havuz filtresi:** `giris` ve `boss` normal aday havuzunda **yok**. İkisi de §6.2'de
atanıyor; havuzda kalsalardı boss odası dungeon'ın ortasında belirebilirdi.

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

> ### ⚠️ Bu artık teorik bir gerekçe değil — 1C'de ölçüldü
>
> 1C'nin "her boş kapıyı doldur" stratejisi hedef oda sayısını **garanti etmiyor**.
> 2000 seed, 12 oda hedefi, 512'lik slot:
>
> | Ölçüm | Sonuç |
> |---|---|
> | Hedefe ulaşan | **%70.8** |
> | Tıkanan | 584 koşum |
> | Bunların içinde **ölü kapı** olan | sadece **81** |
> | Tam **2 odada** duran | **204** (%10.2) |
>
> Yani tıkanmaların **%86'sı çakışma değil** — kapı frontier'ının tükenmesi. Bu bir
> **dallanma süreci (Galton-Watson) sönümlenmesi**: her yerleştirme 1 kapı tüketip
> `(kapı − 1)` kapı ekliyor, yani net `(kapı − 2)`. Mevcut oda setinde beklenen net
> değişim **+0.373 / oda** — pozitif, ama tek kapılı bir kökten başlarken erken sönme
> olasılığı yüksek.
>
> **%10.2 rakamı tesadüf değil:** `test_deadend`'in çekiliş oranı %10.17. Kök
> (`test_giris`) tek kapılı; ilk çekilen oda çıkmazsa frontier anında sıfırlanıyor ve
> dungeon 2 odada bitiyor.
>
> **1D'nin somut gereksinimi:** kritik path kurulurken **tek kapılı şablonlar havuzdan
> çıkarılmalı** (boss son düğüm olarak atanana kadar). Aynı 2000 seed, tek kapılı odalar
> elenince: **%97.1**. Yan dallarda çıkmaz odalar serbest — orada zaten sona ermeleri
> isteniyor.
>
> **Çözüm kod tarafında, harita tarafında değil.** "Giriş odası en az 2 kapılı olsun" da
> akla geliyor ama ölçüm bunun yanlış teşhis olduğunu gösteriyor (3000 seed):
>
> | Giriş kapısı | Normal havuz | Hedefe ulaşan |
> |---|---|---|
> | 1 | tam | %70.0 |
> | 1 | **tek kapılılar elenmiş** | **%97.3** |
> | 2 | tam | %92.1 |
> | 2 | tek kapılılar elenmiş | %99.8 |
> | 4 | tam | %99.2 |
>
> Giriş tek kapılı kalırken bile havuz filtresi tek başına yetiyor. 2 kapılı giriş de
> yardım ediyor ama daha zayıf ve 4 biome'un giriş tasarımını kısıtlıyor. **Giriş odasının
> kapı sayısı harita ekibine kural olarak geçilmiyor** — oynanış kararı olarak serbest
> (§9'daki kurallar listesine girmez).
>
> Not: bu sayılar `ChainGenerator`'ın "her boş kapıyı doldur" stratejisiyle ölçüldü.
> 1D'nin path-önce yaklaşımı hedef uzunluğu açıkça kovaladığı ve yeniden deneyebileceği
> için **en az bu kadar** iyi olacak — yani tablo alt sınır.

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

**Dönüş yanlılığı.** "Ebeveynle aynı yöne devam eden" seçeneklere ceza uygulanır.

Ceza **kapı seçimi aşamasında** (§5.4 adım 2), şablon seçimi aşamasında değil. Sebebi §5.4:
şablon aşamasına dokunmak `agirlik`'in anlamını bozar — az önce tam bundan kaçındık.
Çocuğun hangi kapısından bağlandığı yönelimini belirliyor, dolayısıyla kalan kapılarının
nereye bakacağını da; ceza oraya ait.

Bir kapı seçeneği "düz" sayılır: yerleştirmeden sonra çocuğun **başka bir kapısı**
ebeveynin dışa bakan yönünü gösteriyorsa. Karşılıklı çift kapılı odada (koridor) iki seçenek
de düz olduğu için ceza etkisiz kalır — bu dürüst bir sonuç, asıl motor zaten kapı
ofsetleri (yukarıdaki madde).

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
agirlik: 100         # ŞABLONUN aday seçimindeki payı (loot weight mantığı).
                     # Kapı sayısından BAĞIMSIZ — bkz. §5.4. 4 kapılı oda da
                     # 1 kapılı oda da ağırlığını bir kez sayar.

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

> ✅ **DOĞRULANDI (2026-08-25).** `test_even` (10 geniş × 16 uzun, üç kapısı da ofsetli)
> bunun için üretildi. Origin `(5,0,8)` → kutu `-5..4` (X) / `-8..7` (Z), yani **iki eksende
> de asimetrik**. Sonuçlar:
>
> - Üç kapının duvarı da doğru türetildi (kuzey / güney / doğu).
> - 3 kapı × 4 yön = 12 kombinasyonun hepsinde kapı anchor'ı tam hedefine oturdu.
> - Sunucuda: `test_cross` kuzey kapısına 90° dönmüş hâlde bağlandı, geçit açık çıktı,
>   kutular çakışmadı, odanın **diğer iki kapısı da** hesaplanan yerde bulundu.
>
> **Harita ekibine "kenar uzunluğu serbest" denebilir.** Bağlayıcı olan kural kenar değil,
> anchor'ın doğru bloğa konması (yukarıdaki madde).

---

## 10. Yazılmış olan — FAZ 1A + 1B + 1C

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

### FAZ 1B — oda modeli ve yerleştirme geometrisi

| Ne | Nerede |
|---|---|
| Yön / rotasyon / kutu — saf geometri | `generation/Direction.java`, `Rotation.java`, `Aabb.java`, `Vec3i.java` |
| Kapı anchor'ı, duvar türetme | `generation/DoorAnchor.java` (§4 hesabı `Direction.ofAnchor`) |
| Şablon + metadata modeli | `generation/RoomTemplate.java`, `RoomType.java`, `RoomMetadata.java` |
| `.schem` + `.yml` birleştiren depo | `generation/RoomTemplateStore.java` |
| Yerleştirilmiş oda (R + origin + kutu) | `generation/PlacedRoom.java` |
| Yerleştirme formülü (§5.2 adım 2-4) | `RoomTemplate.attachTo(...)` |
| Test odaları + metadata üretimi | `schematic/TestRoomFactory.java` — artık `.yml` de yazıyor |
| Doğrulama komutları | `command/DungeonsCommand.java` — `rooms`, `room`, `connect` |

**`generation` paketi bilerek saf Java** — ne Bukkit ne WorldEdit tipi kullanıyor
(`RoomMetadata` ve `RoomTemplateStore` hariç; onlar sınırda duruyor). İki sebep:
yerleştirme matematiği sunucu açmadan test edilebiliyor, ve FAZ 8'de dışarı açılacak
API'ye üçüncü parti tip sızmıyor (breaking change riski).

**Doğrulanmış:**
- 53 saf geometri kontrolü (sunucusuz): rotasyon tur kapanışı, 16 `align` kombinasyonu,
  kare + dikdörtgen + asimetrik odada duvar türetme, çakışma kuralları,
  5 oda × tüm kapılar × tüm yönler = 48 yerleştirme.
- 23 blok kontrolü (sunucuda, `execute if block`): iki ayrı bağlantı senaryosunda
  geçidin açık olduğu, duvarların sırt sırta durduğu, kapı dışının dolu kaldığı,
  rotasyonun odanın **diğer** kapılarını da doğru yere taşıdığı.
- Plugin'in raporladığı R / origin / kutu değerleri, spec formüllerinden elle
  hesaplanan değerlerle birebir eşleşti (rot=270 origin `(273,64,256)`;
  rot=90 origin `(771,64,243)`, kutu 10×8×16 → 16×8×10).

**Yeni test odaları:** `test_giris` (tip `giris` — 1D'nin kritik path başlangıcı),
`test_long` (§4 tuzağı), `test_even` (§9 doğrulaması). Set 5'ten 8'e çıktı.

**Kenar durumu yakalandı:** `test_long`'un doğu kapısı ofset **+11** ile duvarın köşe
bloğuna taşıyordu (25 uzunlukta 3 bloklu açıklık için son geçerli merkez 22). `carveDoor`'a
konan sınır kontrolü bunu `/tdungeons gen` anında patlatıyor; ofset +10'a çekildi.

---

### FAZ 1C — aday seçimi, çakışma, geri çekilme

| Ne | Nerede |
|---|---|
| Kapı durumu (BOŞ / BAĞLI / ÖLÜ) | `generation/DoorState.java` |
| Doldurulmayı bekleyen kapı | `generation/OpenDoor.java` |
| Yerleşmiş oda + kapı durumları | `generation/LayoutNode.java` |
| Yerleşim, çakışma testi, slot sınırı, kendi kendini denetleme | `generation/DungeonLayout.java` |
| Havuz + **ağırlıklı seçim** (§5.4) | `generation/RoomLibrary.java` |
| Aday deneme, geri çekilme, dönüş yanlılığı, ÖLÜ işaretleme | `generation/RoomPlacer.java` |
| 1C doğrulaması için basit zincir üretici | `generation/ChainGenerator.java` |
| Komutlar | `command/DungeonsCommand.java` — `weights`, `build` |

**`DungeonLayout.validate()`** üretim yolunda çağrılmıyor; bir hata varsa *hangi* değişmezin
bozulduğunu söylemesi için var: çakışma, slot taşması, hizasız geçit, kopuk graf. Prosedürel
üretimde en pahalı hata bozuk çıktının sessizce kabul edilmesi.

**Doğrulanmış:**
- 29 kontrol (sunucusuz, `GenProbe`): 200.000 çekilişte ağırlık dağılımı (sapma < %0.3),
  havuz filtresi, yerine koymadan çekme, 500 seed'de sıfır tutarsızlık, dar slot'ta sıfır
  taşma, ÖLÜ işaretleme, tekrarlanabilirlik.
- 13 blok kontrolü (sunucuda): üretilmiş dungeon'da geçitler açık, duvarlar sırt sırta,
  döndürülmüş odanın tavan ışığı yerinde, **ÖLÜ kapının açıklığı duruyor** (tıpa 1D'de).
- Sunucudaki `weights` çıktısı `GenProbe`'un ölçtüğü dağılımla birebir aynı.
- 3 seed × 12 oda sunucuda üretildi, üçü de `validate()` temiz.

**Üretim tekrarlanabilir:** `/tdungeons build <oda> <seed>` aynı seed'de aynı dungeon'ı
veriyor. Hata ayıklanamayan prosedürel üretim hata ayıklanmaz — seed olmadan "şu bozuk
dungeon"u geri getirmek imkânsız olurdu.

---

## 11. Açık sorular

### Kapananlar (1B)

1. ~~**Rotasyon işareti.**~~ ✅ **`+1`, saat yönü.** Ölçüldü, bkz. §3.
2. ~~**Tek sayı kenar kuralı.**~~ ✅ **Kalktı, doğrulandı.** `test_even` ile, bkz. §9.
3. ~~**Ağırlık şablona mı, çifte mi ait?**~~ ✅ **Şablona.** İki aşamalı seçim, bkz. §5.4.

### 1C'de ortaya çıkan, 1D'de kapanacak

7. **Kritik path havuzu.** Tek kapılı şablonlar path kurulurken elenmeli — ölçüm ve
   gerekçe §6.2'deki uyarı kutusunda. Yan dallarda serbest kalacaklar.
8. **Tıkanma durumunda ne yapılacak?** Path hedefe ulaşamazsa: yeniden mi denenecek
   (farklı seed), yoksa kısa dungeon kabul mü edilecek? Marketplace tarafı "kullanıcının
   istediği boyut" sözünü tutmayı gerektiriyor — muhtemel cevap: N kez yeniden dene,
   olmazsa en iyisini kullan ve logla.

### Duranlar (1C / 1D'de karara bağlanacak)

4. **Tıpa yöntemi:** prosedürel mi, biome schematic'i mi ilk yazılacak? Prosedürel daha hızlı
   ve 1D'yi bloklamaz; biome tıpaları FAZ 10 ile birlikte gelebilir. *(§7)*
5. **Koridor parçası olacak mı?** Odalar doğrudan sırt sırta bağlanabiliyor. Araya 2 kapılı
   ince koridor parçaları sokmak yerleşimi daha organik yapar ama oda kotasını nasıl etkileyeceği
   (koridor "oda" sayılacak mı) karara bağlanmalı. *(§6)*
6. **Giriş odası** tekil mi, her biome'un kendi giriş odası mı?
*(Ağırlık sorusu 2026-08-25'te kapandı — §5.4'e taşındı.)*

---

## 12. FAZ 1B — bitti ✅

1. ✅ Rotasyon işareti ölçüldü — `+1`, saat yönü (§3)
2. ✅ `RoomTemplate` + `DoorAnchor` veri modeli
3. ✅ `.yml` okuyucu, schematic yanındaki dosyadan
4. ✅ `TestRoomFactory` üretilen odalar için `.yml` de yazıyor
5. ✅ §4'ün duvar hesabı + §3'ün rotasyon fonksiyonları — saf, sunucusuz test edilebilir
6. ✅ **Doğrulama:** iki oda kapılarından bağlandı, geçit blok testiyle açık gösterildi

---

## 13. FAZ 1C — bitti ✅

1. ✅ Aday havuzu + iki aşamalı ağırlıklı seçim (§5.4)
2. ✅ 3B çakışma testi + slot sınırı (`DungeonLayout`)
3. ✅ Dönüş yanlılığı, kapı seçimi aşamasında (§6.4)
4. ✅ ÖLÜ kapı işaretleme + geri çekilme (§5.3)
5. ✅ **Doğrulama:** 500 seed sunucusuz + 3 seed sunucuda, hepsi tutarlı

---

## 14. Sıradaki adım — FAZ 1D (graf üretimi, gezilebilir dungeon milestone'u)

1C "bir kapıya oda takabiliyorum"u verdi. 1D'nin işi **grafın şeklini garanti altına almak**.

1. **Kritik path** — §6.2. Hedef oda sayısı → path uzunluğu `round(hedef × 0.65)`, min 2.
   Giriş odasından zincir. **Tek kapılı şablonlar bu aşamada havuzdan çıkarılacak**
   (açık soru #7; gerekçe ve ölçüm §6.2'deki uyarı kutusunda).
2. **Boss ataması** — path'in son düğümü, random değil.
3. **Yan dallar** — kalan kota, path odalarının boş kapılarından (§6.3).
4. **Tıpa** — kalan BOŞ ve ÖLÜ kapılar kapatılacak (§7). Prosedürel yöntem önce
   (0 dosya, 1D'yi bloklamaz); biome tıpaları FAZ 10 ile gelebilir — açık soru #4.
5. **Boyut seçimi** — small 3-6 / medium 7-12 / large 13-20 (§6.1).
6. **Tıkanma politikası** — açık soru #8.
7. **Milestone:** komutla boş ama **gezilebilir** bir dungeon üretiliyor.

`LayoutNode.depth` ve `deadDoors()` 1D için şimdiden hazır duruyor.
