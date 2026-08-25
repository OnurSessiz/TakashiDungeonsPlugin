# geo-probe — generation paketinin sunucusuz testleri

FAZ 1B (geometri) ve 1C (seçim + çakışma) için regresyon koruması. **Sunucu gerekmiyor**,
saniyeler içinde koşuyor — `generation` paketi bilerek saf Java olduğu için mümkün.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\geo-probe\run.ps1
```

Önce `scripts\build.ps1` çalıştırılmış olmalı (`target\classes` gerekiyor).

## Üç dosya

| Dosya | Ne yapar | Ne zaman koşulur |
|---|---|---|
| `GeoProbe.java` | **1B — 53 kontrol:** rotasyon, `align`, duvar türetme, çakışma kuralları, 48 yerleştirme kombinasyonu | `generation` paketinde her değişiklikte |
| `GenProbe.java` | **1C — 29 kontrol:** 200.000 çekilişte ağırlık dağılımı, havuz filtresi, geri çekilme, 500 seed'de tutarlılık, ÖLÜ kapı, tekrarlanabilirlik | aynı |
| `RotProbe.java` | WorldEdit'in `AffineTransform().rotateY(-derece)` işaretini ölçer | Sadece WorldEdit sürümü değişince |

## `GenProbe`'un iki özel bölümü

**Ağırlık dağılımı** — `generation.md` §5.4 kararının ampirik kanıtı. `agirlik`'in ŞABLONA
ait olduğunu (kapı sayısından bağımsız) gösteriyor, ve çifte ait olsaydı haritacının yazdığı
sıralamanın tersine döneceğini yan yana hesaplıyor. Bu bölüm düşerse config yalan söylüyor
demektir.

**Dallanma sönümlenmesi** — hata değil, **ölçüm**. 1C'nin hedef oda sayısını garanti
etmediğini ve tıkanmaların çakışmadan değil kapı frontier'ının tükenmesinden kaynaklandığını
raporluyor. 1D'nin kritik path tasarımı bu sayılara dayanıyor; değişirlerse 1D'nin varsayımı
da değişmiş demektir.

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
################ FAZ 1B — geometri ################
GECEN: 53   KALAN: 0

################ FAZ 1C — secim + cakisma ################
GECEN: 29   KALAN: 0

TUM TESTLER GECTI
```

Bir kontrol düşerse hangi formülün bozulduğu satır satır yazılıyor.

## Ne kapsamıyor

Bloğun dünyada gerçekten doğru yere yazıldığını **göstermiyor** — o paste yolunun işi,
sunucuda `execute if block` ile doğrulanıyor (`generation.md` §10). Burası sadece
"motor hangi koordinatı hesaplıyor" sorusunu cevaplıyor.
