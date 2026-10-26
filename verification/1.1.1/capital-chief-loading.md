# Başkent liderinin entity yüklenmesini bekleme

## İncelenen sürüm

- Millénaire Kingdoms **1.1.1**, başlangıç commit'i `52a6bc3a4ca4b717775dbd319bbe1df6f733ee0b`.
- Gerçekte yüklenen `build/libs/millenairekingdoms-1.1.1.jar` SHA-256:
  `7ecdc176cee847d78398bb8f8095941df1c54a2698cabe752dab099bbd62dbe4`.
- Depodaki `releases/1.1.1/millenairekingdoms-1.1.1.jar` farklı bir artefakt:
  `cd4395a87675db083bf04d7f53697a935ffec9b1de5117132b1c44f308144346`.
- Özgün Millénaire **9.0.2** SHA-256:
  `8665fcb513dc17eed08fee4b30dae988f45157f72d332ab795611b3761cbc92f`.
- Ortam: Temurin JDK 21.0.13+9, Gradle 8.14.3, NeoForge 21.1.259, Minecraft 1.21.1.

## Doğrulanan neden

Önceki başarısız günlüklerde C köyünün lider kaydı vardı; sorguda ise yalnızca madenci ve eşi görünüyordu.
Eski hazır olma koşulu, arazinin ve herhangi bir köylünün yüklenmesini yeterli sayıyordu.
Chunk arazisinin hazır olması, o chunk'ın entity verilerinin de yüklenmiş olması anlamına gelmiyor.

Yeni tanı, ilk temiz dünyada aynı liderin yüklenme geçişini yakaladı:

| Oyun tick'i | Beklenen tick | Arazi | Entity chunk yüklü | Entity ticking | Test chunk'ı zorla açık tutuyor | Lider sorguda |
|---|---:|---|---|---|---|---|
| 53 | 1 | hazır | hayır | evet | evet | hayır; UUID sorgusu da boş |
| 54 | 2 | hazır | evet | evet | evet | evet; canlı, kaldırılmamış |

Her iki gözlemde lider UUID'si `92c4c8a7-c717-4c83-9005-068784b2475d`,
kayıt/gerçek konum `(-984, -45, 28)`, chunk `[-62, 1]`.
Köy merkezi `(-983, -46, 19)` olduğundan konum mevcut sorgu sınırlarının içinde.
Kayıt `killed=false`; aynı UUID'li gerçek entity ikinci tick'te `inQuery=true` oldu.
Dolayısıyla kayıp kayıt, yanlış konum veya lider tanıma hatası değil, asenkron entity yüklenme yarışı doğrulandı.
Eski günlükteki `forced=false`, Millénaire'in köy bayrağıdır; testin vanilla forced-chunk kümesiyle aynı ölçüm değildir.

## Düzeltme

Yalnızca `NaturalCapitalGameTests` değişti. A ve C başkentlerinin arazisi ve kendi köylerine ait canlı liderleri
sorguda görünene kadar bekleniyor. Mevcut lider doğrulaması ve NPC hareket/kalıcılık kontrolleri korundu.
600 tick sınırı aşıldığında açık hata veriliyor; kayıt UUID/tür/konumu, ölüm bayrağı, gerçek entity konumu,
sorguya dahil olma ve chunk'ın arazi/entity/ticking/forced durumları tanıya ekleniyor.
Lider oluşturma, yeniden doğurtma, doğrulama kaldırma veya test atlama eklenmedi. Üretim kodu değişmedi.

## Gerçek doğrulama

Yalnızca tam `verifyCapitals` paketi çalıştırıldı; `capitals.only` filtresi kullanılmadı.
İki ayrı temiz dünya kullanıldı. Her yazma turu başarılı olduktan sonra aynı dünyanın
`runCapitalGameTestServerReload` görevi ayrı JVM'de çalıştı; başarısız yazmayı atlayan bir seçenek kullanılmadı.

| Temiz dünya | Yazma günlüğü | Ayrı JVM yeniden açma günlüğü |
|---|---|---|
| `chief-wait-1` | `All 5 required tests passed` | `All 5 required tests passed` |
| `chief-wait-seed0` (`level-seed=0`) | `All 5 required tests passed` | `All 5 required tests passed` |

Her iki yeniden açma turunda `CAPITAL_RESTART_READ_PASS` da doğrulandı.
İkinci dünyada iki lider ilk kontrolde hazırdı; koşul gereksiz sabit bir gecikme eklemiyor.
600 tick süre aşımı dalı bu başarılı koşularda tetiklenmedi.
Her iki dünyadaki `mods/millenairekingdoms.jar` yukarıdaki `7ecdc176...` SHA-256 değerini korudu.
Ham günlükler: `/workspace/capitals-investigation/chief-wait-1.log` ve `chief-wait-seed0.log`.

Mevcut dış init betiği yalnızca çalışma dizini/JAR kopyalama hedefini ayırmak için kullanıldı;
ortam ayarları, kurulum betiği, başlangıç talimatları ve kurulum taslağı değiştirilmedi.
Başlangıçta mevcut olan `gradlew.bat` değişikliği bu düzeltmeye dahil edilmedi.
