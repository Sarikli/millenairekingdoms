# Millénaire Kingdoms 1.1.1 — bakım ve doğrulama raporu

22 Ekim 2026 · Minecraft 1.21.1 · NeoForge 21.1.259 · Millénaire 9.0.2 · Java 21.

## A. Başlangıç durumu ve bağımsız inceleme

Temel: `6b826c27314512ec917d4290ae5ca3923b57a865`, 1.1.0. Çalışma dalı: `codex/stability-1.1.1`.
Asıl proje `/home/berdan/Masaüstü/millenairekingdoms`; bakım çalışması ayrı Git worktree içinde:
`/home/berdan/Masaüstü/MİLL MOD/maintenance-1.1.1`.

Başlangıçtaki üç silinmiş belge ve iki izlenmeyen kullanıcı belgesi asıl klasörde aynen bırakıldı. `main`, mevcut
`v1.1.0` etiketi, önceki test kanıtları ve 1.1.0 JAR değiştirilmedi. GitHub'a gönderim yapılmadı.

Üretim sınıfları, veri yükleme/doğrulama, komutlar, sunucu paketleri, GUI işlemleri, NPC karar döngüsü,
ekonomi, kuruluş ve gerçek Millénaire bağlantıları incelendi. Dosya/satır, tetikleyici ve önem dereceleri
[başlangıç incelemesinde](docs/MAINTENANCE_AUDIT_1_1_1.md) bulunur. O belgedeki satırlar temel commit'e aittir.

Doğrulanan oyun hataları: süresi geçmiş emrin çalışabilmesi, aktarım yapan fetih zaferinde diplomatik gerilimin
atlanması, hükümdar ayrılınca katılım başvurularının kilitlenmesi, aynı hükümdarın hemen geri dönerek eski
askerî emirleri yeniden devralabilmesi. Bunlar yeni regresyon testleriyle önce başarısız olarak gösterildi.
Ek olarak isimle komut desteğindeki eksiklik, dağıtım lisansı metadata farkı ve başkent testinin gün-1 varsayımı doğrulandı.

Mimari karar: `KingdomSavedData` çok sorumluluk taşıyor; ancak değişmez `State` üzerinden bir arada doğrulanan
siyasi geçişler güçlü bir tutarlılık sınırı oluşturuyor. Bu bakımda sınıfı sırf kısaltmak için parçalamak veya
kaydı ayrı dosyalara bölmek yerine yetki değişiminin bütün sonuçlarını aynı işlemde tutmak tercih edildi.
İsim çözümleme için küçük bir ortak yardımcı eklendi. Yeni bir altyapı, önbellek veya Mixin eklenmedi.

## B. Uygulanan düzeltmeler ve değişen dosyalar

Üretim kaynakları `src/main/java/com/millenairekingdoms/` altındadır:

| Dosya | Değişiklik ve korunan davranış |
|---|---|
| `autonomy/RaidOrderService.java` | Başlatmadan önce emrin hâlâ kayıtlı ve süresinin geçmemiş olması aranır. Millénaire uygunluk kontrolleri, askerler, hedef bekleme ve hazırlık süresi korunur. |
| `conquest/ConquestService.java` | Kapanışta baskının kampanya bağlamı, üyelik aktarımından sonra da diplomasi servisine aktarılır. Normal baskın yolu ve NPC temizliği korunur. |
| `politics/DiplomacyService.java` | Savaş fetihlerinde taraflar savaş kaydından alınır; ikinci zaferin −10 gerilim olayı atlanmaz. Normal/bağımsız baskın ve yıkılmış krallık davranışı korunur. |
| `kingdom/KingdomSavedData.java` | Hükümdarlık dönemi değişince bekleyen emirler aynı `State` geçişinde kaldırılır. NPC yönetimine dönüşte karara bağlanmamış başvurular geri çekilir. Çalışan baskınlar korunur. |
| `politics/KingdomDay.java` | Eski sürümden kalan sahipsiz başvurular normal günlük adımda serbest bırakılır; yükleme sırasında sessiz veri silme yapılmaz. |
| `command/PoliticalArguments.java` | Ortak tam ad/UUID çözümleme; aynı adlı köylerde rastgele hedef seçmek yerine UUID istenir. |
| `command/KingdomCommands.java`, `command/KingdomsCommands.java` | Köy ve yönetici krallık hedefleri ortak çözümleyiciyi kullanır. Yetkiler aynı sunucu servislerinde yeniden kontrol edilir. Savaş kimlikleri UUID olarak kalır. |

Diğer değişiklikler:

- `src/main/resources/assets/millenairekingdoms/lang/{en_us,tr_tr}.json`: bilinmeyen/çakışan köy adı hataları.
- `MaintenanceGameTests.java`, `MaintenanceDataGameTests.java`: süre, yetki, kapanış ve kayıt sınır testleri.
- `GuiGameTests.java`: gerçek komut ayrıştırma, çift isim reddi, UUID uyumluluğu, operatör yetkisi ve adla oyuncu baskını.
- `ConquestTestMod.java`: yeni test sınıflarının kaydı.
- `NaturalCapitalGameTests.java`: geçmiş günün tekrar vergilendirilmediğini doğrular; test gelirini mevcut günün sonrasında çalıştırır.
- `src/gametest/resources/review-1.1.0-kingdoms.dat` ve açıklaması: gerçek 1.1.0 test dünyasından değişmez kayıt kopyası.
- Sürüm metadata, README, CHANGELOG, rapor, test kanıtları ve dağıtım JAR'ı.

### Eski kayıt ve hükümdarlık davranışı

NBT biçimi **6** olarak kalır. UUID, üyelik, başkent, savaş, ateşkes, hükümdar, gelir, emir ve takvim şeması
değiştirilmez. Mevcut 1–5 geçişleri ve bozuk/gelecek sürümü boş kayıtla ezmeme korumaları korunur.

Geçerli bekleyen emirler yükleme ve yeniden başlatmada aynen tutulur. Hükümdar gerçekten değişirse eski
dönemin bekleyen askerî emirleri artık geçerli değildir. Tahttan çekilip aynı tick'te geri gelmek bunları canlandıramaz.
Devam eden gerçek baskın kesilmez; olağan zafer/yenilgi, yağma engeli ve temizleme yolu tamamlanır.

Hükümdarı kalmayan krallığa yapılmış başvurular otomatik kabul edilmez: başvuru serbest bırakılır. NPC köyü
sonraki olağan değerlendirmede güncel koşullarla tekrar katılabilir/başvurabilir; oyuncu başvurusunu yeniden yapabilir.
Yeni hükümdar NPC yönetimini devralırken geçerli başvurular korunur. 1.1.0'dan kalmış sahipsiz başvurular ilk
yeni günlük işlemde temizlenir. Normal dosya okuması kaydı kendiliğinden değiştirmez.

### Komut kullanımı

UUID'ler kullanılmaya devam eder. Birden fazla kelimeli köy ve yönetici krallık hedeflerini tırnak içine alın:

```text
/kingdom raid "Hedef Köy" "Saldırgan Köy"
/kingdom accept "Başvuran Köy"
/kingdoms info "Krallık Adı"
/kingdoms attach "Krallık Adı" "Köy Adı"
```

İsimler mevcut boyuttaki kayıtlar arasında tam adla, büyük/küçük harf ayrımı olmadan çözülür. Aynı adlı köylerde
UUID gerekir. Yönetici komutları hâlâ yetki seviyesi 2 ister; oyuncu komutları hükümdarlık, itibar ve askerî koşulları atlayamaz.

## C. Test sonuçları ve ortam

Ortam: Linux, OpenJDK 21.0.12.1, Gradle wrapper 8.14.3, NeoForge 21.1.259, Minecraft 1.21.1,
Millénaire 9.0.2. Test sunucuları için 512 MB başlangıç / 3 GB azami Java heap kullanıldı.
Türkçe klasör adının NeoForge geliştirme çalıştırıcısıyla uyumsuzluğu nedeniyle derleme/test kopyası
`/tmp/kingdoms-maintenance-111` altında çalıştı. Üretim JAR'ı `mods` klasöründen yüklendi.

### Son aday JAR ile yedi paket

| Paket | Yazma | Ayrı JVM yeniden açma | İki turun toplam çalışma süresi |
|---|---|---|---|
| `verifyWars` | 30/30 | 30/30 | 24.2 s |
| `verifyConquest` | 25/25 | 25/25 | 23.8 s |
| `verifyStage5` | 24/24 | 24/24 | 25.1 s |
| `verifyCapitals` | 5/5 | 5/5 | 93.1 s |
| `verifyAutonomy` | 22/22 | 22/22 | 24.6 s |
| `verifyGui` | 7/7 | 7/7 | 27.7 s |
| `verifyFounding` | 12/12 | 12/12 | 30.3 s |

**125/125 + 125/125 başarılı.** Önceki 113 test korunur; 12 ek test bulunur. Her paketin kendi dünyası vardır;
yazma ve okuma turu aynı dünyayı iki ayrı sunucu sürecinde açar. Yedi paketin günlüklerinde toplam 14 adet
`All N required tests passed` satırı, 1.1.1 başlangıç mesajı ve Millénaire 9.0.2 bağımlılığı doğrulandı.
`build` de başarılıdır; GameTest başarısının kanıtı olarak tek başına kullanılmamıştır.

Kanıtlar: [son test günlükleri](verification/1.1.1/final/),
[sayısal sonuçlar](verification/1.1.1/final/results.json).

### Yeniden üretilen başarısızlıklar

- `before-autonomy.log`: düzeltmeden önce dört yeni hata testi başarısız; 16 kontrol/mevcut test başarılı.
- `before-names.log`: isimle yönetici hedefi sözdiziminde reddedildi; altı mevcut GUI testi başarılı.
- `before-capital-calendar.log`: önceden ilerlemiş ortak test dünyasında başkent testinin gün-1 varsayımı başarısız.
  Ekonomi kodu değiştirilmedi; test geçerli sonraki günü kullanacak şekilde düzeltildi ve geçmiş günün reddini de sınadı.
- Düzeltmelerden sonra ilgili paketler tekrar çalıştırıldı. Son koşuda başarısız GameTest kalmadı.

### Kayıt uyumluluğu

- Orijinal 1.1.0 üretim kodunun yazdığı gerçek test kaydı kopyası, 1.1.1 ile ayrı bir SavedData anahtarına
  kaydedildi ve ikinci JVM'de tüm alanlarıyla yeniden karşılaştırıldı. NBT harita listelerinin sırası normalize edilir;
  gzip başlığı ve Minecraft dış zarfının byte eşitliği iddia edilmez.
- Temsili biçim-6 kayıt; hükümdar, üyelik, savaş, ateşkes, barış teklifi, geçerli emir, başvuru, vergi borcu,
  karar günü, fetih ilerlemesi ve makbuzu yeniden başlatmada korudu. Aynı gün tekrar gelir yazılamadı.
- Önceki kayıt formatları, bozuk/gelecek kayıt korumaları ve kuruluş takvimi mevcut paketlerde yeniden geçti.

### Gerçek Millénaire davranışları ve sınır durumları

- Normal yağmalı baskın, başarılı/başarısız fetih, çift zafer engeli, üyelik aktarımı ve kapanış mevcut paketlerde geçti.
- Süresi dolan, barışla kaldırılan ve silinmiş hedefe ait emirlerin baskın başlatması engellendi.
- Hükümdar değişimi bekleyen emri iptal etti; başlamış baskın normal şekilde tamamlanabildi.
- Tek hükümdar/tek krallık, rakip hükümdarlık talebinin değişiklik yapmaması, çifte üyelik ve başkent kuralları geçti.
- Doğal başkent testi 31 köy tipinde gerçek merkez planlarını kontrol etti. Bir doğal başkentte 15 NPC canlı,
  bunlardan 12'si hareket ederken gözlendi.
- Gelişerek kuruluş testi gerçek Bizans köyünde seviye 0/1/2, UPGRADING ve COMPLETE koşullarını sınadı;
  %5 sınır örnekleriyle kuruluş sonrası **29 bina, 16 köylü ve 26 sandık değişmedi**.
- Hazine, tekrar vergi engeli, dolu/yüklenmemiş depolarda borç, para çekme yetkileri ve sahte ağ istekleri
  mevcut ekonomi/GUI paketlerinde geçti.
- Testlerin fiziksel köy oluşturma/yükseltme adımlarında Millénaire banner/pathfinding uyarıları ve Minecraft
  `POI data mismatch` mesajları görüldü. Bunlar gizlenmedi veya düzeltilmiş sayılmadı; bütün zorunlu yapı,
  kayıt ve NPC kontrolleri yine geçti. Orijinal Millénaire JAR'ına müdahale edilmedi.

### Normal dedicated sunucu

Üretim JAR'ı, test modu yüklenmeden `runServerJar` ile yalnızca `127.0.0.1:25579` adresinde açıldı.
`kingdoms list`, `kingdoms founding status`, `save-all flush` ve `stop` konsol komutları çalıştırıldı;
aynı boş test dünyası ikinci JVM'de yeniden açıldı ve temiz kapandı. Son iki koşuda `Done`, 1.1.1 ve
Millénaire 9.0.2 yükleme mesajları, komut çıktıları ve bütün boyutların kaydı doğrulandı.

[İlk tur](verification/1.1.1/final/server-write.log),
[yeniden başlatma](verification/1.1.1/final/server-restart.log),
[sonuçlar](verification/1.1.1/final/server-results.json).
Bu açılış testi siyasi kayıt taşıyan bir oyuncu dünyası değildir; siyasi kalıcılık yukarıdaki ayrı JVM
GameTest'leriyle doğrulanır. Yeni bir tam süreli NPC meydan savaşı veya grafik istemcisi testi yapılmadı.

İlk dedicated denemesinde geçici `server.properties` düz dünya katmanlarını belirtmediği için vanilla
`No key layers in MapLike[{}]` uyarısı verdi. Geçici ayar tamamlandı; son açılış/yeniden açılış günlüklerinde
ERROR satırı kalmadı. İlk günlükler `verification/1.1.1/server-initial/` altında saklandı. Mod kodu bu nedenle değiştirilmedi.

### Performans değerlendirmesi

Gün kontrolü 20 tick'te bir, emir denemesi 100 tick'te bir yapılır; günlük siyasi değerlendirme ve yedi günlük
kuruluş kuralı her tick yeniden çalıştırılmaz. Kuruluş kontrolü kayıtlardan okur, köy chunk'larını zorla yüklemez.

Son kuruluş simülasyonunda 28 köy ve 2.080 değerlendirmede kuruluş değerlendirmesi olan gün başına ortalama
**0,079 ms**, olmayan gün başına **0,008 ms** ölçüldü. 36 köylü yoğun bölge senaryolarında siyasi işlemler dahil
**0,129–0,484 ms/simüle gün** aralığı görüldü. Bunlar takvim atlamalı yerel simülasyon ölçümleridir; gerçek
sunucu TPS değeri veya binlerce köylü dünya garantisi değildir. Ölçüm gerekçesi olmadan karmaşık optimizasyon eklenmedi.

## D. Kalan teknik borç ve doğrulamanın sınırları

- `KingdomSavedData` hâlâ geniş bir sınıftır. Gelecekte mantık ayrılırken tek üyelik haritası, zafer makbuzları,
  kampanya revizyonları ve atomik `State` yayımı birlikte korunmalıdır.
- Geçmiş savaş/makbuz listeleri büyüdükçe kopyalama/doğrulama maliyeti büyür. Binlerce köy ve yıllarca birikmiş
  gerçek dünya verisi için uzun süreli stres testi yapılmadı; ölçülmemiş bir soruna karşı önbellek eklenmedi.
- GUI uygunluk görünümü anlık kopyadır; işlem anında sunucu yeniden doğrular. Birkaç yönetici/günlük metni
  İngilizce kalır. Beş sekmenin görsel yerleşimi ve K tuşu bu bakımda elle yeniden denenmedi.
- Kapanış regresyonları gerçek Millénaire baskın kodunu kullanır; bazıları sonucu denetimli biçimde
  `applyLooting`/`endRaid` çağrılarıyla oluşturur. Bu sonuçlar yeni bir tam süreli NPC savaş gözlemi sayılmaz.
- Normal sunucu thread'i işlemleri ve temiz yeniden başlatma sınanır. Ani güç kesintisinin SavedData, chunk
  ve oyuncu envanteri dosyalarının tamamında atomik disk kaydı sağlaması garanti edilmez.
- Silinen/erişilemeyen başkent için otomatik başkent seçimi eklenmedi. Ulaşılamayan hazineye ödeme borçta kalır;
  silinmiş hedef baskın emri güvenli biçimde düşürülür.
- Gelecekte asenkron iş eklenirse dünya ve SavedData değişiklikleri ana sunucu thread'inde kalmalıdır.
- Doğal kuruluş oranları, seviye-3 şartı, yedi günlük dönem, %60 başkent koşulu, bir/iki zafer kuralları,
  oyuncu itibar eşikleri, beş sekme ve mevcut ekonomi tasarımı değiştirilmedi.

Kraliyet İnşaat Talebi, siyasi harita veya başka yeni oyun mekaniği eklenmedi.

## E. Sürüm hazırlığı ve teslim

- Sürüm: **1.1.1**. Kritik testler tamamlandıktan sonra kaynak sürüm bilgisi güncellendi.
- [JAR](releases/1.1.1/millenairekingdoms-1.1.1.jar): 255.791 byte.
- SHA-256: `cd4395a87675db083bf04d7f53697a935ffec9b1de5117132b1c44f308144346`.
- [SHA256SUMS](releases/1.1.1/SHA256SUMS), [CHANGELOG](CHANGELOG.md), [README](README.md).
- Test edilen ve teslim edilen üretim JAR'ı aynıdır. Kaynak kopyası karşılaştırıldı. JAR içinde test sınıfları,
  test kayıtları veya Millénaire sınıfları yoktur; sürüm ve MIT lisans metadata'sı doğrulandı.
- Orijinal Millénaire JAR SHA-256 değeri değişmedi:
  `8665fcb513dc17eed08fee4b30dae988f45157f72d332ab795611b3761cbc92f`.

İncelenebilir commit'ler:

| Commit | İçerik |
|---|---|
| `744180b` | İlk dört hatayı yeniden üreten regresyon testleri ve başarısızlık kanıtı |
| `71a8fe1` | Süre, hükümdarlık dönemi ve fetih diplomasisi düzeltmeleri |
| `a410492` | Ortak isim/UUID çözümleme ve komut testleri |
| `b99afc1` | Eski kayıt, ayrı JVM ve yetki sınır testleri; bağımsız inceleme |
| `78568fe` | Başkent testi takvim varsayımı ve başarısız/başarılı koşu kanıtları |

Son sürüm hazırlığı commit'i 1.1.1 metadata, belge, bütün son test kanıtları ve dağıtım JAR'ını kapsar.
Dal `codex/stability-1.1.1` içinde tutulur. `main` birleştirmesi, uzak depoya gönderim, kararlı sürüm etiketi
ve GitHub Release yapılmadı; bunlar ayrı yayın adımıdır.

Testleri tekrar çalıştırmak için Java 21 ve değişmemiş bağımlılık JAR'ıyla proje klasöründe
`./gradlew build` çalıştırılabilir. NeoForge'un Türkçe yol kısıtı için her paketi
`./scripts/run-dev.sh verifyWars` gibi çalıştırın; diğer görevler yukarıdaki tabloda listelidir.
Bu yardımcı Türkçe yolda her çağrı için ayrı ASCII test kopyası oluşturur. ASCII bir klasörde doğrudan
çalıştırıyorsanız paketler arasında ayrı test dünyası kullanın, fakat bir paketin yazma/yeniden açma çifti
arasında dünyayı değiştirmeyin. Hiçbir testi gerçek oyuncu dünyasına yönlendirmeyin.
