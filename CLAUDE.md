# TakashiDungeonsPlugin

Minecraft **Paper 1.21.8 / Java 21** için prosedürel dungeon sistemi. Belirli bir sunucu için
değil, **ücretsiz + açık kaynak (GPLv3)** dağıtılmak üzere geliştiriliyor — her şey konfigüre
edilebilir ve kurulum senaryosundan bağımsız çalışmalı. Amaç satış değil görünürlük;
ücretsiz olması standardı düşürmüyor (`anahedef.md` §1 ve §8).

`com.takashi:TakashiDungeons` · paket `com.takashi.dungeons`

---

## Oturuma başlarken oku — bu sırayla

Proje belleği `AI Yönergeleri/` altında. Kod okumadan önce bunlar okunur:

> **Bu klasör repoda YOK** — `.gitignore`'da, sadece Onur'un makinesinde duruyor. Temiz bir
> klonda bulunmazlar; o durumda okunacak yer `README.md` ve `docs/generation.md`.
> `generation.md`'nin İngilizce ve public karşılığı `docs/generation.md`; **algoritma
> değişirse ikisi birden güncellenir**, yoksa public doküman yalan söylemeye başlar.

| # | Dosya | Ne için |
|---|---|---|
| 1 | `AI Yönergeleri/anahedef.md` | Değişmez çerçeve + **ASLA YAPMA** listesi |
| 2 | `AI Yönergeleri/sonislem.md` | **En üstteki girdi** = nerede kaldık |
| 3 | `AI Yönergeleri/Roadmap.md` | Faz durumu, sıradaki madde |
| 4 | `AI Yönergeleri/generation.md` | Üretim algoritmasının tam spec'i — **FAZ 1 üzerinde çalışıyorsan şart** |
| 5 | `AI Yönergeleri/isleyis.md` | Yazılmış sistemler nasıl çalışıyor |

## Oturum biterken güncelle

- `sonislem.md` — **en üste** yeni girdi (şablon dosyanın içinde). Yazılmazsa bir sonraki
  oturum "nerede kalmıştık" diye kod okumak zorunda kalır.
- `isleyis.md` — yeni sistem yazıldıysa ya da mevcut sistem değiştiyse
- `generation.md` — üretim algoritması değiştiyse
- `Roadmap.md` — biten maddeler `[x]`

Doküman güncellenmezse yalan söylemeye başlar; bu projede en pahalı hata bu.

---

## Bozulmaz kurallar

Tam liste `anahedef.md`'de. Sessizce ihlal edilmesi en kolay olanlar:

- **Bir faz çalışır durumda bitmeden sonrakine geçilmez.**
- Player data **SQL**'de (SQLite default, MySQL opsiyon). YAML/flat file'da **tutulmaz**.
- Core plugin hiçbir mob plugin'ine **hard depend etmez**. MythicMobs vb. `softdepend`;
  hiçbiri yokken **vanilla fallback** çalışmak zorunda (out-of-box garantisi).
- Mob spawn'da **`statOverride: true/false`** flag'i her zaman bulunur — custom mob'ların
  kendi stat sistemi ezilmesin diye.
- Public API'de **breaking change yok** (addon'lar kırılır).
- Sadece **instanced** dungeon. "Dünyaya kalıcı yazma" desteklenmez.
- Dungeon içinde `/tp` ve `/tpa` çalışmaz — **adminler hariç**.
- Konfigürasyon önce **YAML** ile çalışır hale gelir, GUI editör **sonra**.

## Çalışma tarzı (Onur'un tercihi)

- Komple çözüm yerine **kavramsal rehberlik + "neden" açıklaması**
- Hedge'li çok seçenekli cevap değil, **net ve taahhütlü** cevap
- Commit mesajlarında AI atıfı yok (`Co-Authored-By`, "Generated with" satırları eklenmez)

---

## Build ve test

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1          # derle -> run\plugins\
powershell -ExecutionPolicy Bypass -File scripts\server.ps1         # Paper 1.21.8 baslat
powershell -ExecutionPolicy Bypass -File scripts\geo-probe\run.ps1  # generation geometrisi (sunucusuz)
```

`generation` paketi saf Java — yerleştirme matematiği sunucu açmadan test edilebiliyor.
Geometriye dokunduysan `geo-probe` koş, sunucu açmadan önce. Detay:
`scripts/geo-probe/README.md`.

**JDK 21 zorunlu.** Sistem PATH'inde JDK 26 var ve Paper 1.21.8 onu kabul etmiyor; bu yüzden
her iki script de Temurin JDK 21 yolunu **sabit** tutuyor. `mvn` / `java` PATH'ten çağrılırsa
build ya da sunucu kırılır.

### Test sunucusu (`run/`) repoda değil
`run/` .gitignore'da. Başka bir makinede test edecek kişi şunları kendi kurar:
- `run/paper.jar` — Paper 1.21.8
- `run/plugins/` — **FastAsyncWorldEdit** (schematic paste için gerekli; yoksa plugin enable
  olur ama generation devre dışı kalır)

### Sunucuyu konsoldan sürmek
Komutlar stdin'den beslenebiliyor ama dört tuzağı var — dördü de FAZ 1B'de yaşandı:

- **Blok doğrulamasından önce `forceload`.** FAWE paste'ten sonra chunk'ları yüklü
  bırakmıyor; `execute if block` yüklenmemiş chunk'ta sessizce sonuç vermiyor.
  Dünyanın dimension key'i: `minecraft:takashi_dungeons`.
- **`run/logs/latest.log` önce silinmeli.** Eski log'da "Done (" duruyor; script sunucuyu
  hazır sanıp komutları açılış sırasında gönderiyor.
- **PowerShell 5.1 stdin'e ilk yazımda UTF-8 BOM basıyor** (`StandardInputEncoding`
  .NET Framework'te yok) ve ilk komutu bozuyor. Bir kurban satırı gönderip yut.
- **Test dünyası silinmeli** (`run/takashi_dungeons`). Slot index'i her açılışta sıfırdan
  başlıyor, eski bloklar altta kalıyor (`release` temizlemiyor — FAZ 2) ve yanlış
  "geçti" üretiyor.
