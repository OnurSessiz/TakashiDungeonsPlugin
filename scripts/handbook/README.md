# Handbook — PDF üretici

`docs/TakashiDungeons-Handbook.pdf` bu klasörden üretiliyor. Kaynak **HTML**, çıktı PDF.

```powershell
python scripts\handbook\build.py
```

Çıktı doğrudan `docs/TakashiDungeons-Handbook.pdf` üzerine yazılıyor.

---

## Kapsam kararı — dil katmanı kitaba GİRMİYOR

**Karar 2026-09-06 (Onur).** `Messages` / `lang/*.yml` katmanı ve komut çıktısının
İngilizce'ye geçmesi (`FAZ 3#BONUS`) bu kitapta **anlatılmayacak**. Yeni bölüm açılmayacak,
mevcut bölümlere de sıkıştırılmayacak.

Kitap **üretim algoritmasını ve sistemlerin nasıl çalıştığını** anlatıyor; dil katmanı bir
altyapı tercihi, o anlatının parçası değil. Anlatılacak yer `AI Yönergeleri/isleyis.md`
(“Dil Katmanı”) ve operatör kılavuzu yazılırsa orası.

Bunun bir bedeli var, bilerek kabul edildi: kitaptaki komut çıktısı örnekleri **Türkçe** ve kod
artık öyle davranmıyor. O örnekler tazelenirse İngilizce hâlleriyle tazelenmeli — ama bu, dil
katmanını anlatmak demek değil.

> Kitabın **gerçekten** eksik olduğu yer ayrı bir konu: Part III, FAZ 3C'yi (boss odası, PDC
> sahipliği, öldürme sinyali) hâlâ "kalan borç" diye anlatıyor. O güncellenmeli.

---

## Dosyalar

| Dosya | Ne |
|---|---|
| `build.py` | Bütün boru hattı |
| `shell.html` | Sayfa iskeleti + CSS (kapak, bölüm ayracı, kod bloğu, tablo, callout stilleri) |
| `content_01..15.html` | Kitabın içeriği. Sıralı okunuyor, `content_*.html` glob'u alfabetik. |
| `out/` | Ara çıktılar (git'te değil): `book.html`, `pass1.pdf`, `pass2.pdf`, ölçeklenmiş görseller |

## Boru hattı

1. `content_*.html` birleştirilir, makrolar açılır (aşağıda), Java/YAML **sözdizimi renklendirmesi** uygulanır
2. `h1`/`h2` başlıklarına id + görünmez sayfa işareti (`[[hN]]`) basılır, içindekiler üretilir
3. **1. geçiş:** headless Chrome ile PDF (`--print-to-pdf`)
4. PDF'ten sayfa sayfa metin çıkarılıp `[[hN]]` işaretleri aranır → başlık başına **gerçek sayfa numarası**
5. **2. geçiş:** içindekiler sayfa numaralarıyla yeniden basılır
6. reportlab ile alt bilgi (bölüm adı + sayfa numarası) damgalanır, pypdf ile **PDF yer imleri** eklenir
7. pikepdf ile yeniden kaydedilir

> **6. adımdan sonra pikepdf şart.** pypdf, Chrome'un object stream'lerini tek tek nesnelere
> açıyor ve dosya ~10 katına çıkıyor: **51 MB → 3 MB**. İçerik aynı.

## İçerik makroları

`content_*.html` düz HTML, artı iki makro:

```
@@CODE java|Açıklama satırı@@
public void örnek() { ... }
@@END@@
```

Diller: `java`, `yaml`, `text`, `none`. Açıklamanın başına `!` konursa blok sayfa
bölünmesine karşı korunur (`page-break-inside: avoid`).

```
@@FILE src/main/java/.../Rotation.java|60-75|java|Açıklama@@
```

Depodan **satır aralığı** çekip gömer. Kod değişince kitap da değişsin isteniyorsa bu tercih
edilir; ama satır numaraları kayabilir, o yüzden çoğu blok `@@CODE@@` ile elle yazıldı.

## Sayfa yapısı işaretleri

- `<h1 class="nolist">` / `<h2 class="nolist">` — içindekilere **girmez** (kapak, bölüm ayracı, "Contents")
- `<span class="chapnum">Chapter 7</span>` — başlığın etiketi; alt bilgide ve içindekilerde ayrı gösteriliyor
- `<section class="part">` içindeki `<span class="pgmark">[[PARTPAGE]]</span>` — o sayfaya
  alt bilgi **basılmasın** diye (siyah ayraç sayfaları)

## Gereksinimler

```
pip install pypdf reportlab pikepdf pillow
```

Ayrıca **Google Chrome** (`build.py` içinde `CHROME` sabiti). WeasyPrint denendi ve elendi:
Windows'ta GTK/Pango gerektiriyor.

## Görseller

`docs/images/*.png` her koşuda `out/img/*.jpg` olarak **1500 px'e ölçekleniyor**. Chrome kaynak
PNG'yi tam çözünürlükte gömüyor; iki ekran görüntüsü tek başına PDF'i 50 MB'ın üstüne çıkarıyordu.
İçerikte yol `img/<isim>.jpg` — `out/book.html`'e göre göreli.
