# Millénaire Kingdoms 1.1.0 — Kullanım rehberi (Türkçe)

Minecraft 1.21.1 · NeoForge 21.1.259 · Millénaire 9.0.2 · Java 21

Millénaire Kingdoms, Millénaire köylerinin üstüne siyasi bir katman ekler: krallıklar, başkentler, hükümdarlar,
savaş ve barış, gerçek Millénaire baskınlarıyla fetih, günlük vergi geliri, kendi kararlarını veren NPC krallıkları ve
geliştikçe kendi krallığını kuran bağımsız köyler.
Millénaire'in köy simülasyonu (köylüler, binalar, kültürler, köy sahipliği, ticaret, kendi baskınları) değişmez.

## 1. Kurulum

### Tek oyunculu
1. Minecraft 1.21.1 için NeoForge 21.1.259 kur.
2. `mods` klasörüne özgün **Millénaire 9.0.2** JAR'ını ve `millenairekingdoms-1.1.0.jar` dosyasını koy.
3. **Eski Millénaire Kingdoms JAR'larını (0.x ve 1.0.x sürümleri) mutlaka kaldır.** İki sürüm birlikte yüklenemez.
4. Var olan bir dünyayı açmadan önce **dünya klasörünün yedeğini al** (aşağıda "Kayıt biçimi" bölümüne bak).

### Adanmış sunucu (dedicated server)
1. Sunucuya NeoForge 21.1.259 kur, `mods` klasörüne aynı iki JAR'ı koy, eski Kingdoms JAR'ını kaldır.
2. Her oyuncunun istemcisinde de aynı iki JAR bulunmalı (krallık ekranı istemci tarafında çizilir; mod iki tarafta zorunludur).
3. Ayarlar dünya başına `world/serverconfig/millenairekingdoms-server.toml` dosyasındadır (ilk açılışta oluşur).
4. Operatör komutları OP seviyesi 2 ister (`/kingdoms ...`). Oyuncu komutları (`/kingdom ...`) herkese açıktır; bütün kurallar sunucuda denetlenir.

## 2. Krallık ekranı (GUI)

Açmak için: **K** tuşu, `/kingdom` veya `/kingdom gui`. Tuş, Ayarlar → Kontroller → Tuş Atamaları içinde
**Millénaire Kingdoms** kategorisindeki **Krallık Yönetimini Aç** satırından değiştirilebilir; sohbet, envanter ya da
başka bir ekran açıkken çalışmaz ve `/kingdom gui` ile aynı sunucu denetiminden geçer. Ayrıca bir başkentin NPC liderine sağ tıklayınca Millénaire'in kendi lider
ekranı her zamanki gibi açılır, sohbete gelen özetteki **[Open the kingdom screen]** düğmesi krallık ekranını açar.
Ekran Millénaire'in parşömen dokusunu kullanır ve oyunun diline göre Türkçe ya da İngilizce görünür.

Ekran yalnızca sunucunun gönderdiği bilgiyi gösterir; her düğme sunucuya bir istek yollar ve sunucu bütün kuralları
(hükümdarlık, itibar, mesafe, savaş, askerî hazırlık, hazine) yeniden denetler. Kapalı (gri) bir düğmenin üzerine
gelince nedeni görünür. Hükümdar olmayan oyuncular her şeyi görebilir ama karar veremez.

| Sekme | İçerik |
|---|---|
| **Krallığım** | Krallık adı, başkent adı ve kültürü, başkent koordinatları, hükümdar, bağlı köyler (seviye ve gelir), savaş durumu, günlük gelir, siyasi yetkilerin. `<` `>` ile diğer krallıklara bakılır. |
| **Askerî İşler** | Savaş bilgisi, başkent fethi koşulu (%60), fetih ilerlemesi (0/2, 1/2, 2/2), hedef köy listesi (isimleriyle, durum ve bekleme süresiyle), askerlerin gönderileceği köyün seçimi (ya da "en güçlü hazır köy"), **Fetih baskını başlat**, bekleyen emir ve süren baskınlar, savaş ilanı / liderden savaş isteme, barış yapma / barış teklif etme. |
| **Diplomasi** | Bilinen krallıklar, ilişki puanları, savaş/ateşkes durumu, barış teklifleri, katılım başvurularını kabul/ret, durduğun bağımsız köyü bir krallığa katma, kendi gelişmiş köyünle krallık kurma. |
| **Ekonomi ve Hazine** | Toplam günlük gelir, köylere göre gelir, başkent belediye binası deposundaki gerçek denier miktarı (altın/gümüş/bakır), depoda yer bekleyen gelir, **sikke çekme**. |
| **Hükümdarlık** | Başkentteki itibarın, hükümdarlık şartları, hükümdarlığı devralma, hükümdarlıktan çekilme, yetki engelleri ve nedenleri. |

## 3. İtibar ve hükümdarlık

- Her krallığın en fazla bir oyuncu hükümdarı olur; bir oyuncu en fazla bir krallık yönetir.
- **Hükümdarlığı devralmak:** başkent merkezine en fazla 64 blok uzakta dur, başkentteki Millénaire birleşik itibarın
  (köy + kültür) **en az 32768** olsun ve krallığın hükümdarı olmasın.
- **Siyasi karar** (savaş, barış, fetih emri, başvuru kabulü, hazine): hükümdarın başkentteki itibarı **en az 8192**
  olmalı. İtibar düşerse hükümdarlık kalır ama kararlar itibar geri gelene kadar bekler.
- **Hükümdarsız krallık:** NPC lider yönetir. İtibarı en az 8192 olan bir oyuncu, başkentte liderden başka bir
  krallığa savaş isteyebilir; lider başkentler arası ilişki **−30 veya daha kötü** ise kabul eder.
- Hükümdarlık yalnızca siyasidir: Millénaire köy sahipliği (ownerUUID), yerel yönetim hakları ve sandık kilitleri değişmez.

## 4. Diplomasi ve fetih kuralları

- Krallık ilişkisi, iki başkent köyünün Millénaire'deki kendi ilişki değeridir (−100 … 100).
- **Barışçıl katılım:** bağımsız bir köy, başkentle ilişkisi **en az +50** ise katılabilir. Oyuncuya ait köy yalnızca
  sahibinin isteğiyle katılır. Hükümdarlı krallığa katılım hükümdarın onayını bekler.
- **Krallık kurma:** kendi Millénaire köyünün belediye binası seviyesi en az 3 ise o köy yeni bir krallığın başkenti
  olur, sen hükümdar olursun.
- **Fetih yalnızca gerçek Millénaire baskınıyla olur.** Baskını Millénaire başlatır, yürütür ve sonucunu verir; fetih
  baskınında yağma yapılmaz. Başarısız baskın ilerleme vermez.
  - Bağımsız köy: savaş gerekmez, **1 başarılı baskın**.
  - Rakip krallığın normal köyü: saldırgan olarak ilan edilmiş **aktif savaş** gerekir, aynı savaşta **2 başarılı baskın**.
  - Başkent: saldırgan, savunmacının savaş başındaki köylerinin **%60'ını** (yukarı yuvarlanır) elinde tutunca açılır,
    sonra **2 başarılı baskın**. Başkent düşünce savunmacı krallık yıkılır, kalan köyleri galibe geçer.
- Savaşlar yönlüdür: yalnızca savaşı ilan eden taraf fetih yapar (savunmacı karşı fetih yapamaz).
- Bir krallığın aynı anda tek aktif savaşı olabilir.
- **Fetih emri (hükümdar):** Askerî İşler sekmesinden hedef köyü seç, isteğe bağlı olarak askerlerin geleceği köyü seç,
  **Fetih baskını başlat**. Komutla da verilebilir: `/kingdom raid <hedefKöyUUID> [kaynakKöyUUID]`, durum için
  `/kingdom raid status`, bekleyen emri iptal için `/kingdom raid cancel`.
- **Askerî hazırlık** (oyuncu ve NPC için aynı, operatör test komutu hariç):
  - Askerleri gönderecek köyün türü Millénaire'de baskın yapabilen bir tür olmalı;
  - köyde evde, kiralanmamış, yaşayan ve gücü olan en az bir akıncı bulunmalı;
  - hedefin savunma gücü, gönderen köyün baskın gücünün iki katından az olmalı (Millénaire'in kendi hedef kuralı);
  - aynı hedefe son fetih baskınından sonra **3 gün** geçmiş olmalı;
  - krallığın aynı anda tek bekleyen emri ya da süren fetih baskını olur, bir hedefe aynı anda tek baskın gider.
- **Yüklenmemiş köyler:** baskın yalnızca hedef köy yüklüyken (yakında bir oyuncu varken) başlar; aksi hâlde emir
  bekler ve iki gün içinde başlayamazsa iptal olur. Eklenti yüklenmemiş köyler için sahte savaş üretmez, chunk açık tutmaz.

## 5. Hazine ve gelir

- Her Minecraft günü, başkent dışındaki her bağlı köy **belediye binası seviyesi × 4 denier** üretir.
- Gelir gerçek Millénaire sikkeleri olarak başkentin **mevcut belediye binası deposuna** konur (yeni sandık yok).
  Depo dolu ya da yüklü değilse gelir kaybolmaz, bekler ve yer açılınca eklenir; aynı gün iki kez ödenmez.
- **Sikke çekme:** hükümdar, başkentin belediye binasına en fazla 64 blok uzakta, Ekonomi ve Hazine sekmesinden
  (ya da `/kingdom treasury withdraw <denier>`) istediği tutarı çeker. Sikkeler depodan Millénaire'in kendi depo
  işlevleriyle alınır ve gerçek sikke eşyası olarak envantere verilir. Yalnızca sikkeler alınır; belediye binasındaki
  diğer eşyalara ve Millénaire'in sandık kilidine dokunulmaz.
  - Envanterde yer yoksa hiçbir şey alınmaz.
  - Depodaki sikkelerle tutar tam ödenemiyorsa (ör. yalnızca altın varken 10 denier) işlem yapılmaz ve ödenebilecek tutar söylenir.
  - Aynı sikkeler iki kez çekilemez; art arda gelen istekler sırayla işlenir.
  - `/kingdom treasury` hazinenin durumunu gösterir.

## 6. NPC krallıkları: diplomasi, savaş ve barış

Hükümdarsız krallıklar kendi kararlarını verir. Kararlar varsayılan olarak **3 Minecraft gününde bir** verilir,
kalıcı verilere (Millénaire ilişkileri ve köylü kayıtları) dayanır; yüklenmemiş köyler de siyasete katılır.
Hükümdarlı bir krallık adına NPC hiçbir zaman savaş ilan etmez, barış yapmaz, köy kabul etmez ya da baskın göndermez.

- **Katılım:** bağımsız, sahipsiz bir köy, başkentiyle ilişkisi en az +50 olan yakın bir krallığa ölçülü bir ihtimalle
  katılır (yakınlık ve krallığın büyüklüğü ihtimali etkiler). Hükümdarlı krallığa köy kendisi başvurur; hükümdar karar verir.
- **Savaş ilanı:** başkentler arası ilişki **−70 veya daha kötü**, iki krallığın köyleri Millénaire'in ilişki yarıçapı (varsayılan 2000 blok) içinde komşu, krallığın askerî hazırlığı var, hedefin bir köyüne
  baskın yapılabiliyor, iki tarafın da aktif savaşı ve aralarında ateşkes yok ise savaş ihtimali doğar. Güçlü taraf
  daha olasıdır ama hiçbir savaş kesin değildir.
- **Siyasi gerilimin kaynakları** (keyfî sürekli düşüş yok):
  - Millénaire'in kendi gece diplomasisi: farklı kültürler −30'dan başlayıp genellikle kötüleşir, aynı kültürler +50'den
    başlayıp iyileşir.
  - Sınır komşuluğu: iki krallığın birbirini tanıyan köyleri arasındaki ortalama Millénaire ilişkisi, başkentlerin
    ilişkisini günde en fazla 2 puan kendine çeker.
  - Baskınlar: iki krallığın köyleri arasındaki her Millénaire baskını (normal ya da fetih) başkentlerin ilişkisini 10 puan düşürür.
  - Barış antlaşması ilişkiyi bir kez 20 puan iyileştirir.
- **Fetih seferleri:** savaştaki NPC saldırgan, hazır köyleri arasında baskın gücü en yüksek olandan (eşitse en yakından) askerleri savunmacının köyüne (başkent açıksa
  başkente) gönderir; barıştaki NPC krallığı başkentiyle ilişkisi eşik altında olan, sahipsiz bağımsız bir köye sefer
  açabilir. NPC emri çeyrek gün hazırlanır, sonra yukarıdaki askerî hazırlık kurallarıyla gerçek Millénaire baskını olarak başlar.
- **Barış:** savaş en az **12 gün** sürdükten sonra NPC saldırgan şu nedenlerle barış yapabilir: askerî ilerleme
  olmaması (6 gün), hazır ordunun kalmaması, birkaç köy almış olmak, ilişkilerin düzelmesi ya da savaşın uzaması
  (36 gün). Saldırganın hükümdarı varsa NPC savunmacı yalnızca barış teklif eder. Barıştan sonra **10 gün ateşkes**
  vardır (oyuncu ve NPC ilanları için; operatör komutları hariç). Fethedilen köyler yeni krallıklarında kalır.

## 6a. Gelişerek krallık kurma (1.1.0)

Sıradan bir NPC köyü normal doğar ve Millénaire'in kendi inşaatıyla büyür. Belediye binası **3. seviyeyi
tamamladığında** (Millénaire'in sıfır tabanlı bina seviyesi; ilk inşaat seviye 0'dır) köy kendi krallığını kurmaya
aday olur.

- **Ne zaman:** her **7 Minecraft gününde bir**, o gün uygun olan her köy için bir kez zar atılır. Her gün ya da her tick
  zar atılmaz. Sunucu yeniden başlatılınca aynı dönem tekrarlanmaz; saat geri alınırsa fazladan deneme olmaz;
  `/time add` gibi büyük atlamalar tek değerlendirme sayılır.
- **İhtimal:** 3. seviyede %5, sonraki her tamamlanmış seviyede +5 puan, en fazla %100. Örnek: seviye 4 %10, 5 %15,
  7 %25, 10 %40, 17 %75. Bütün kültürler için aynı kural geçerlidir; ancak kültürlerin ve köy türlerinin belediye
  binası en yüksek seviyeleri farklıdır (ör. Bizans askerî köyü 17, Norman tarım köyü 10, İnuit av köyü 3, Selçuklu
  köylerinin çoğu 4–5). Seviyesi 3'e hiç ulaşamayan bir köy türü bu yolla krallık kuramaz.
- **Kimler kurabilir:** bağımsız (hiçbir krallığın başkenti ya da üyesi olmayan), Millénaire'in doğal olarak ürettiği
  türde, oyuncuya ait olmayan köyler. Oyuncu köyleri ve oyuncu kontrollü türler, mezralar, tek binalar, bir krallığın
  üyeleri, mevcut ve eski başkentler kurmaz. Fetih baskınındaki, baskın emri hedefi olan ya da bir krallığa katılmak
  için başvurmuş köy o dönem kurmaz. Başka krallığa bağlı köyler bağımsızlık ilan etmez.
- **Belediye binası tamamlanmış olmalı:** yükseltmesi süren bina (Millénaire durumu UPGRADING) uygun sayılmaz;
  değerlendirme bir sonraki döneme kalır.
- **Mesafe:** yaşayan her başkentten en az **1.500 blok**. Aynı değerlendirmede kurulan bir krallık da sonraki köyler
  için bu kurala girer.
- **Ne değişir:** yalnızca siyasi kayıt. Köy, adı "Kingdom of <köy>" olan yeni bir NPC krallığının başkenti ve tek
  üyesi olur; krallık aktif ve hükümdarsız başlar. Binalar, inşaat projeleri, köylüler, köy lideri, Millénaire köy
  sahibi, kültür, köy adı, envanterler, ekonomi ve itibar değişmez; köy yükseltilmez.
- **Sonrası:** yeni krallık mevcut NPC siyasetine katılır (köy kabulü, savaş, gerçek baskınla fetih, barış ve ateşkes,
  günlük gelir), krallık ekranında görünür ve yeterli itibarı olan oyuncu hükümdarlığını devralabilir.
- **Doğal başkentlerle ilişkisi:** dünya üretimindeki %2'lik doğal başkent sistemi aynen sürer. Doğal başkentler zaten
  başkent olduğundan ikinci kez krallık kurmaz.
- **Aynı gün katılım ve kuruluş:** NPC kararları (köy katılımı) önce, kuruluş sonra değerlendirilir. O gün bir krallığa
  katılan köy kuruluş yapamaz; yeni kurulan başkent katılım adayı olmaz.
- **Operatör:** `/kingdoms founding status` her köyün kuraldaki durumunu, seviyesini ve ihtimalini, sonraki
  değerlendirme gününü gösterir (yalnızca okur).

## 7. Ayarlar (`millenairekingdoms-server.toml`)

| Bölüm / anahtar | Varsayılan | Anlam |
|---|---|---|
| `naturalCapitals.enabled` | `true` | Yeni doğal köylerin nadiren NPC başkenti olması |
| `naturalCapitals.chancePercent` | `2.0` | Uygun yeni doğal köy başına başkent olasılığı (%) |
| `naturalCapitals.minCapitalDistance` | `1500` | Başkentler arası en az mesafe (blok) |
| `npcAutonomy.enabled` | `true` | NPC krallıklarının kendi kararları |
| `npcAutonomy.diplomaticJoining` | `true` | Bağımsız köylerin kendiliğinden katılması |
| `npcAutonomy.warFrequency` | `BALANCED` | `RARE`, `BALANCED`, `FREQUENT` |
| `npcAutonomy.decisionIntervalDays` | `3` | İki siyasi değerlendirme arası gün |
| `npcAutonomy.warRelationThreshold` | `-70` | NPC savaş ilanı için başkentler arası ilişki eşiği |
| `npcAutonomy.raidCooldownDays` | `3` | Aynı köye iki fetih baskını arası gün |
| `npcAutonomy.truceDays` | `10` | Barıştan sonraki ateşkes günü |
| `npcAutonomy.automaticCampaigns` | `true` | NPC krallıklarının kendi fetih baskınları |
| `dynamicFounding.enabled` | `true` | Gelişen bağımsız köylerin kendi krallığını kurması |
| `dynamicFounding.minimumTownhallLevel` | `3` | Kuruluş için en düşük tamamlanmış belediye binası seviyesi (0 tabanlı) |
| `dynamicFounding.checkIntervalDays` | `7` | İki kuruluş değerlendirmesi arası Minecraft günü |
| `dynamicFounding.baseChancePercent` | `5.0` | En düşük seviyedeki ihtimal (%) |
| `dynamicFounding.chanceIncreasePerLevel` | `5.0` | Her ek tamamlanmış seviyede eklenen puan (en çok %100) |
| `dynamicFounding.minCapitalDistance` | `1500` | Kurucu köyle yaşayan her başkent arası en az mesafe (blok) |

İhtimal formülü: `clamp(baseChancePercent + (seviye − minimumTownhallLevel) × chanceIncreasePerLevel, 0, 100)`;
en düşük seviyenin altında %0.

## 8. Kayıt biçimi ve yükseltme

- Krallık verisi her boyutta `data/millenairekingdoms_kingdoms.dat` dosyasındadır. 1.0.0 ve 1.1.0 **biçim 6** yazar.
  1.1.0 yeni kalıcı alan eklemez (kuruluş dönemi kayıtlı defter gününden hesaplanır); 1.0.x dünyaları olduğu gibi açılır.
- Biçim 1–5 (0.1.0 … 0.5.0) dünyalar açılır ve ilk kayıtta biçim 6'ya yükseltilir. Yükseltme bütün krallıkları,
  üyelikleri, hükümdarları, savaşları, fetih ilerlemesini ve defteri aynen korur; yalnızca boş NPC özerklik bölümü
  eklenir ve aktif her savaş, kayıtlı defter gününden başlayan bir saat alır.
- **Geri dönüş yoktur:** biçim 6 dosyasını 0.x sürümleri açamaz. Yükseltmeden önce dünya yedeği al.
- Bozuk ya da daha yeni bir kayıt dosyası boş kayıtla ezilmez; sunucu yedeği geri yüklemeni ister.

## 9. Oyuncu komutları (ekranın yedeği)

```text
/kingdom                 krallık ekranını açar (ya da /kingdom gui)
/kingdom info            sohbette özet
/kingdom claim | abdicate
/kingdom war declare <krallık> | war request <krallık> | war peace
/kingdom join <krallık> | accept <köyUUID> | refuse <köyUUID> | found <ad>
/kingdom raid <hedefUUID> [kaynakUUID] | raid status | raid cancel
/kingdom treasury | treasury withdraw <denier>
```

Operatör komutları: `/kingdoms list`, `info`, `create`, `attach`, `active`, `village`, `ruler clear`,
`war declare|peace|info|list`, `relation <krallık> <krallık> <değer>` (bakım/test), `autonomy status`, `founding status`,
`test conquest|status|villages` (yalnızca test: askerî hazırlığı atlar).
