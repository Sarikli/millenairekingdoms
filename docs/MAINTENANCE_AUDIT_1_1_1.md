# 1.1.1 bakım incelemesi — başlangıç bulguları

22 Ekim 2026. Başlangıç: 1.1.0, `6b826c2`. İnceleme kapsamı: üretim sınıfları, yedi GameTest paketi, Gradle/metadata, dil dosyaları, mevcut test kanıtları ve değişmemiş Millénaire 9.0.2 bytecode imzaları. Aşağıdaki satırlar başlangıç commit'ine aittir.

## Doğrulanmış sorunlar

| No | Derece | Dosya / başlangıç satırı | Tetikleyici ve sonuç | Kanıt |
|---|---|---|---|---|
| M1 | P2 | `autonomy/RaidOrderService.java:62–69` | Son kullanma zamanı geçmiş emir için önce başlatma deneniyor; hazır hedefte süre kontrolü atlanıyor. | Yeni testte expired order gerçek Millénaire baskını başlattı. |
| M2 | P2 | `politics/DiplomacyService.java:103–105`, `conquest/ConquestService.java:133–136` | İkinci zafer önce üyeliği değiştiriyor; baskın olayı tarafları güncel üyelikten okuyunca −10 diplomasi uygulanmıyor. | Birinci zafer −10, aktarım yapan ikinci zafer 0; normal baskın kontrolü geçti. |
| M3 | P2 | `kingdom/KingdomSavedData.java:358–373`, `autonomy/AutonomyService.java:197–199`, `capital/DynamicFounding.java:157` | Hükümdar çekildikten sonra başvurular sahipsiz kalıyor; otomatik katılım ve kuruluş bloke oluyor. | Yeni test: NPC yönetimi, bekleyen başvuru ve karar NONE. |
| M4 | P2 | `kingdom/KingdomSavedData.java:358–373`, `autonomy/RaidOrderService.java:89–96` | Oyuncu aynı tick'te çekilip yeniden hükümdar olursa eski emir hâlâ aynı UUID'ye ait görünüyor; eski yetki dönemi yeniden canlanıyor. | Yeni test: çekilme/yeniden talep sonrasında eski emir hâlâ mevcut. |
| M5 | P2 / istenen davranış açığı | `command/KingdomCommands.java:54–68`, `command/KingdomsCommands.java:189–212` | Oyuncu krallık adları desteklenirken köy hedefleri ve yönetici hedefleri UUID argümanlarıyla sınırlı. Kullanıcının isimle komut koruma koşulu bu tabanda tam karşılanmıyor. | Adla yönetici komutu regresyon testi sözdizimi reddiyle başarısız. |
| M6 | P3 | `gradle.properties:16`, `releases/1.1.0/millenairekingdoms-1.1.0.jar` | Kaynak lisansı MIT; dağıtılan JAR etiketi All Rights Reserved. | JAR içerikleri karşılaştırıldı: sınıflar aynı, yalnızca metadata farklı. |
| M7 | P3 / test güvenilirliği | `test/NaturalCapitalGameTests.java:413–414` | Test kaydının günü ilerlemişken `startDay(0)` gün sayacını geri alamaz; ardından gün 1 vergisi haklı olarak reddedilir. Başkent testi bunu ekonomi arızası sanır. | `before-capital-calendar.log`: gerçek köy oluşturma geçti; gelir denemesi 0 → 0 ile başarısız oldu. Test mevcut günün sonrasını kullanır ve eski günün reddini ayrıca doğrular. |

`verification/1.1.1/before-autonomy.log`: 20 testten dört yeni hata testi başarısız; kalan 16 test geçti. `before-names.log`: yeni isim testi başarısız. Böylece düzeltmeler yalnızca varsayımlara dayanmaz.

## Mimari değerlendirme ve karar

`KingdomSavedData` başlangıçta 945 satırdır; siyasi kayıtlar, savaş, hükümdarlık, ekonomi defteri, emirler ve NBT doğrulamasını taşır. Buna rağmen bütün bu veriler aynı değişmez `State` işleminde doğrulanıp yayımlanır. Fetih sonucu, üyelik, sayaç ve makbuzu ayrı dosya/servislerin bağımsız yazımlarına bölmek tutarlılığı zayıflatır. Bu bakımda sınıfı sırf kısaltmak için bölmek uygun bulunmadı.

İş sınırları korunur: SavedData kayıt ve tutarlı geçişleri; PoliticsService oyuncu otoritesini; RaidOrderService gerçek baskın uygunluğunu; AutonomyService kasıtlı NPC karar farklarını; Economy/Treasury gerçek parayı; GUI/network istek ve gösterimi yönetir. İsim/UUID çözümlemesi için yalnızca küçük bir ortak argüman yardımcı sınıfı uygundur. NBT biçimi 6, UUID'ler ve mevcut tablolar korunmalıdır.

## Kontrol edilen sınırlar

| Alan | Mevcut koruma / test dayanağı | Bakım kararı |
|---|---|---|
| Aynı oyuncu/krallığa iki hükümdar | Tek ana iş parçacığı, claimRule ve State doğrulaması; RulerGameTests | Korunur; başarısız rakip talebin veri değiştirmediği eklenir. |
| Yeni hükümdar ve eski emirler | Sonradan issuer kontrolü var, aynı UUID yeniden talebinde yetersiz | Yetki değişiminde bekleyen emirler aynı State işleminde kaldırılır; çalışan NPC savaşı kesilmez. |
| Barış ve savaş sırasında köy aktarımı | Aktif savaş baskını barışı engeller; kampanya savaş/revizyonla bağlı; barış emirleri ayıklar | Mevcut kurallar korunur; eski emir nesnesi ve silinmiş hedef ayrıca sınanır. |
| Çift zafer / çifte üyelik | Makbuz, campaign revision, tek üyelik haritası ve atomik State | Conquest/Kingdom/War/Capital paketleri tekrar çalıştırılır. |
| Başkent düşmesi | Üyeler, hükümdar, savaş, bekleyen gelir aynı State'te aktarılır; emirler ayıklanır | Mevcut %60 ve iki zafer mantığı korunur. |
| Katılım / kuruluş | Üyelik ve aktif baskın yeniden kontrol edilir; günlük sıra katılım sonra kuruluş | Founding testleri korunur; sahipsiz başvuru sorunu giderilir. |
| Günlük gelir / fetih günü | Kalıcı gün işareti; gün başındaki üyelik vergilendirilir, gün içi fetih bir sonraki gün etkiler | Economy testleriyle korunur. |
| Dolu / yüklenmemiş hazine | Gerçekte eklenen miktar kadar borç düşülür; çekimde tam para ve yer kontrolü | GUI/Treasury/Economy paketleri korunur. |
| İki para çekme isteği | Ana sunucu thread'inde sıralı işlem; her istekte stok yeniden okunur | Mevcut arka arkaya çekim ve sahte paket testleri tekrar çalıştırılır. |
| Seviye / takvim | NPC kuruluşu 0 tabanlı COMPLETE plan seviyesi 3; %5 + seviye başına 5 puan; 7 günlük dönem | Oyuncu kuruluşu/ekonominin farklı 1 tabanlı gösterimiyle karıştırılmaz; kurallar değiştirilmez. |
| Ağ / dedicated server | Sınırlı metin codec'leri; bağlantının gerçek oyuncusu; servislerde yetki kontrolü | NeoForge PayloadRegistrar kaynağında varsayılan MAIN thread doğrulandı; ek Mixin gerekmez. |
| Millénaire hook'ları | applyLooting/endRaid/trySpawnVillage gerçek JAR descriptor'ları ve require=1 | Hedefler değiştirilmez; testlerde her iki Mixin yüklenir. |
| Eski / bozuk kayıt | Biçim 1–6 migration, duplicate ve referans doğrulama, yüklenemeyen dosyayı boş kayıtla ezmeme | Orijinal dosyalarda test yapılmaz; kopyalar ve ayrı JVM kullanılır. |

## Henüz hata olarak kanıtlanmayan riskler ve kapsam sınırı

- State kopyalama/doğrulaması geçmiş savaş ve makbuz sayısıyla büyür. Günlük değerlendirme ve GUI sorgularının büyük dünyalardaki maliyeti ölçülmeden önbellek veya yeni indeks altyapısı eklenmez. Mevcut takvim/kuruluş simülasyonlarının ölçümleri final raporda ayrılır.
- GUI görüntüsü anlık kopyadır; ekrandaki uygunluk bilgisi eskiyebilir. Asıl işlemde ortak servisler koşulları tekrar doğruladığı için bu tek başına yetki açığı değildir. Daha kapsamlı ortak gösterim modeli gelecekte değerlendirilebilir.
- SavedData, chunk ve oyuncu envanteri farklı Minecraft kayıt dosyalarıdır. Normal ana-thread işlemleri ve temiz yeniden başlatmalar sınanabilir; ani elektrik kesintisinde bütün dosyaları kapsayan bir disk işlemi garantisi verilmez. Yeni ekonomi altyapısı bu bakımın kapsamı değildir.
- Köy kaydı silinirse başkent verisini otomatik başka köye taşıma özelliği yoktur; hazine/otorite servisleri mevcut köyü gerektirir. Otomatik başkent seçimi eklenmez.
- Bazı yönetici ve günlük mesajları hâlâ İngilizcedir; var olan Türkçe/İngilizce GUI desteği ve yeni girdi hatalarının her iki çevirisi korunur.
- Java kaynakları sunucu thread'i varsayımıyla tasarlanmıştır. Doğrulanmış event/komut/payload girişleri ana thread'dedir; gelecekte asenkron çağrı eklenirken bu sınır korunmalıdır.

Yeni mekanik, siyasi harita, Kraliyet İnşaat Talebi veya büyük mimari yeniden yazım planlanmamıştır.

Son test koşularında paketler birbirinden ayrı geçici dünyalarda çalıştırılır; her paketin yazma ve ayrı JVM okuma turu aynı dünyayı paylaşır. Doğal başkent paketinin başlangıç noktası yakınında başka başkent olmaması koşulu korunur. Önceki başarısız test dünyası ve günlükleri tanı için saklanır; kullanıcı dünyaları kullanılmaz.
