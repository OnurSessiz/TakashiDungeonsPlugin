# geo-probe — generation geometrisinin sunucusuz testi

FAZ 1B'de yazılan yerleştirme matematiğinin regresyon koruması. **Sunucu gerekmiyor**,
saniyeler içinde koşuyor — `generation` paketi bilerek saf Java olduğu için mümkün.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\geo-probe\run.ps1
```

Önce `scripts\build.ps1` çalıştırılmış olmalı (`target\classes` gerekiyor).

## İki dosya

| Dosya | Ne yapar | Ne zaman koşulur |
|---|---|---|
| `GeoProbe.java` | 53 kontrol: rotasyon, `align`, duvar türetme, çakışma kuralları, 48 yerleştirme kombinasyonu | `generation` paketinde her değişiklikte |
| `RotProbe.java` | WorldEdit'in `AffineTransform().rotateY(-derece)` işaretini ölçer | Sadece WorldEdit sürümü değişince |

`RotProbe` bir kereye mahsus bir ölçüm değil, **canlı bir varsayım kontrolü**:
`Rotation` sınıfının bütün formülleri o transform'un saat yönü olmasına dayanıyor.
WorldEdit bir gün işareti değiştirirse üretilen dungeon'ların kapıları tutmaz ve hata
*sessiz* olur — oda pastelenir, geçit kapalı çıkar. Sürüm yükseltmeden sonra koşulmalı.

## Neden JUnit değil (henüz)

`pom.xml`'de test dependency yok; eklemek build'i değiştirir ve o kararı FAZ 1C'ye
bıraktık. `surefire` zaten yapılandırılmış durumda, test kaynağı olmadığı için atlıyor —
`src/test/java` altına taşımak sadece bir dependency satırı meselesi.

Bu klasördeki dosyalar **derlemeye girmiyor** (`src/` altında değiller), bu yüzden
build'i bozma riski yok.

## Beklenen çıktı

```
GECEN: 53   KALAN: 0
```

Bir kontrol düşerse hangi formülün bozulduğu satır satır yazılıyor.

## Ne kapsamıyor

Bloğun dünyada gerçekten doğru yere yazıldığını **göstermiyor** — o paste yolunun işi,
sunucuda `execute if block` ile doğrulanıyor (`generation.md` §10). Burası sadece
"motor hangi koordinatı hesaplıyor" sorusunu cevaplıyor.
