# geo-probe — generation paketinin sunucusuz testleri

FAZ 1B (geometri), 1C (seçim + çakışma) ve 1D (graf üretimi) için regresyon koruması.
**Sunucu gerekmiyor**, saniyeler içinde koşuyor — `generation` paketi bilerek saf Java
olduğu için mümkün. Toplam **112 kontrol**.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\geo-probe\run.ps1
```

Önce `scripts\build.ps1` çalıştırılmış olmalı (`target\classes` gerekiyor).

## Dosyalar

| Dosya | Ne yapar | Ne zaman koşulur |
|---|---|---|
| `GeoProbe.java` | **1B — 53 kontrol:** rotasyon, `align`, duvar türetme, çakışma kuralları, 48 yerleştirme kombinasyonu | `generation` paketinde her değişiklikte |
| `GenProbe.java` | **1C — 28 kontrol:** 200.000 çekilişte ağırlık dağılımı, havuz filtresi, geri çekilme, 500 seed'de tutarlılık, ÖLÜ kapı, tekrarlanabilirlik | aynı |
| `DungeonProbe.java` | **1D — 31 kontrol:** kritik path garantisi (3×1000 üretim), boss ataması, boyut aralıkları, tıpa kapsamı, ardışık tohum bağımsızlığı, out-of-box fallback'ler | aynı |
| `Rooms.java` | Ortak test oda seti — **alfabetik sırada**, sunucudaki `SchematicService.list()` ile aynı | (kütüphane) |
| `RotProbe.java` | WorldEdit'in `AffineTransform().rotateY(-derece)` işaretini ölçer | Sadece WorldEdit sürümü değişince |

## `GenProbe`'un iki özel bölümü

**Ağırlık dağılımı** — `generation.md` §5.4 kararının ampirik kanıtı. `agirlik`'in ŞABLONA
ait olduğunu (kapı sayısından bağımsız) gösteriyor, ve çifte ait olsaydı haritacının yazdığı
sıralamanın tersine döneceğini yan yana hesaplıyor. Bu bölüm düşerse config yalan söylüyor
demektir.

**Dallanma sönümlenmesi** — hata değil, **ölçüm**. Naif "her boş kapıyı doldur"
stratejisinin hedef oda sayısını garanti etmediğini ve tıkanmaların çakışmadan değil kapı
frontier'ının tükenmesinden kaynaklandığını raporluyor. 1D'nin kritik path tasarımı bu
sayılara dayanıyor; değişirlerse 1D'nin varsayımı da değişmiş demektir.

## `DungeonProbe`'un iki özel bölümü

**Ardışık tohum bağımsızlığı** — `new Random(seed)`'in ardışık tohumlarda korelasyonlu
olduğunu *önce kanıtlıyor*, sonra `Seeds` karıştırmasının düzelttiğini gösteriyor. Bu bölüm
düşerse `small` dungeon'ların oda sayısı aralık yerine tek bir değere sabitlenmiş demektir.

**Out-of-box fallback'ler** — boss odası yokken, giriş odası yokken, hiç oda yokken ve
sadece tek kapılı odalar varken üretimin durmadığını sınıyor. `anahedef.md`'nin out-of-box
garantisi bu.

## Sunucuyla aynı sonucu verirler

`Rooms.java` şablonları **alfabetik** sırada tutuyor, çünkü sunucuda sıra
`SchematicService.list()`'ten geliyor ve o metot dosya adlarını sıralıyor. Ağırlıklı seçim
kümülatif tarama yaptığı için sıra sonucu değiştiriyor.

Pratik faydası: probe'lar sunucunun ne üreteceğini **önceden hesaplayabiliyor**. Blok
testlerinin beklenen koordinatları böyle çıkarılıyor — offline hesap ile sunucu çıktısının
birebir eşleşmesi, üretimin tekrarlanabilirliğinin uçtan uca kanıtı.

`RotProbe` bir kereye mahsus bir ölçüm değil, **canlı bir varsayım kontrolü**:
`Rotation` sınıfının bütün formülleri o transform'un saat yönü olmasına dayanıyor.
WorldEdit bir gün işareti değiştirirse üretilen dungeon'ların kapıları tutmaz ve hata
*sessiz* olur — oda pastelenir, geçit kapalı çıkar. Sürüm yükseltmeden sonra koşulmalı.

## Neden JUnit değil (henüz)

`pom.xml`'de test dependency yok; eklemek build'i değiştirir ve bu karar henüz verilmedi.
`surefire` zaten yapılandırılmış durumda, test kaynağı olmadığı için atlıyor —
`src/test/java` altına taşımak sadece bir dependency satırı meselesi.

Ertelenmesinin sebebi kararsızlık değil: FAZ 1 bitene kadar test odaları ve beklenen
değerler hızla değişiyor, bu dosyalar da onlarla birlikte. Şekil oturunca taşınacak.

Bu klasördeki dosyalar **derlemeye girmiyor** (`src/` altında değiller), bu yüzden
build'i bozma riski yok.

## Beklenen çıktı

```
################ FAZ 1B - geometri ################
GECEN: 53   KALAN: 0

################ FAZ 1C - secim + cakisma ################
GECEN: 28   KALAN: 0

################ FAZ 1D - graf uretimi ################
GECEN: 31   KALAN: 0

TUM TESTLER GECTI
```

Bir kontrol düşerse hangi formülün bozulduğu satır satır yazılıyor.

## Ne kapsamıyor

Bloğun dünyada gerçekten doğru yere yazıldığını **göstermiyor** — o paste yolunun işi,
sunucuda `execute if block` ile doğrulanıyor (`generation.md` §10). Burası sadece
"motor hangi koordinatı hesaplıyor" sorusunu cevaplıyor.
