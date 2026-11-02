# Millénaire Kingdoms 1.2.1 — By Royal Decree

Minecraft 1.21.1 · NeoForge 21.1.259 · Java 21 · original Millénaire 9.0.3

## English

Eligible NPC kingdoms can now resolve hostile independent-village conquest orders while both villages are unloaded. The operation uses actual registered native military records, preserves its exact opposed dice before political effects, and applies the existing one-victory independent-conquest rule. Loaded participants retain original native combat. Existing war, two-victory capital, majority, readiness, authority, cooldown and probability rules remain.

Independent defenders appear as actual villages in Chronicles, with truthful winning-side wording and no invented defending kingdom, physical casualties or loot. Changed ownership, player control and friendly relations invalidate an unapplied operation. Restart/recovery reuses the original result and cannot award conquest twice.

Maintenance retains an order's real blocker when it expires, quietly defers new treasury work at cold capitals, preserves separate financial recovery and all disk-readback safeguards, and packages the unchanged MIT notice. Reproducible ZIP permissions, focused bounded test runners, read-only CI, contributor templates and a current architecture guide accompany the [released-1.2.0 engineering audit](https://github.com/Sarikli/millenairekingdoms/blob/1.2.1/docs/1.2.1_ENGINEERING_REVIEW.md).

Use matching 1.2.1 clients and servers (network protocol 11). Existing supported native, political and financial data remain readable; independent strategic operations use a separate strict format 2. Keep a whole-world pre-upgrade backup. Downgrading a world containing new independent operations to 1.2.0 is unsupported. The original dependency, balance settings, published 1.2.0 assets and previous verification artifacts are preserved.

Validation includes real original-native independent conquest on day 13 and lawful two-victory capital conquest on day 5, each with an actual separate-JVM reopen. Fifteen affected package pairs retain their exact `All N required tests passed` lines and image attribution. Eight actual military/financial process interruptions each recover with `All 1 required tests passed`; deliberate halts are not counted as passing tests. See [complete results and retained failures](https://github.com/Sarikli/millenairekingdoms/blob/1.2.1/docs/1.2.1_PROGRESS.md); server-side GUI/action checks do not establish graphical input or authenticated login.

Known audit limits remain explicit: synchronous physical durability can exceed a tick budget; legacy income delivery has a source-identified cross-save crash-consistency exposure that this patch does not claim to fix; a synthetic 500-record world failed native reopen with a 3 GiB heap. Large financial/architectural redesigns and native-language approval remain deferred. The audit distinguishes observed failures, source risks and unverified behavior.

## Türkçe

Uygun NPC krallıkları, her iki köy de yüklü değilken düşman bağımsız köylere karşı stratejik fetih gerçekleştirebilir. Gerçek Millénaire askerî kayıtları kullanılır; zar sonucu siyasi etkiden önce kaydedilir ve bağımsız köy için mevcut tek zafer kuralı uygulanır. Yüklü bölgelerde özgün NPC savaşları sürer. Savaş, başkent için iki zafer ve çoğunluk, hazırlık, yetki, bekleme süresi ve olasılık kuralları korunur.

Tarihçe bağımsız savunucuyu gerçek köy adıyla ve doğru kazananla anlatır; hayalî bir savunucu krallık, fiziksel kayıp veya ganimet oluşturmaz. Sahiplik, oyuncu yönetimi veya dostluk değişirse uygulanmamış işlem geçersizleşir. Yeniden açma ve toparlanma özgün zar sonucunu kullanır; aynı fetih ikinci kez verilmez.

Süresi dolan emirlerde gerçek engel nedeni korunur. Yüklü olmayan başkentlerde yeni hazine bakımı sessizce ertelenir; ayrı mali toparlanma ve disk doğrulama güvenceleri sürer. Özgün MIT lisansı pakete eklenir. Tekrarlanabilir arşiv izinleri, süreli test çalıştırıcıları, CI, katkı şablonları ve güncel mimari rehberi [kod tabanı denetimine](https://github.com/Sarikli/millenairekingdoms/blob/1.2.1/docs/1.2.1_ENGINEERING_REVIEW.md) eşlik eder.

İstemci ve sunucuda aynı 1.2.1 sürümünü kullanın; ağ protokolü 11'dir. Desteklenen eski kayıtlar okunur; bağımsız stratejik işlemler ayrı ve sıkı biçim 2 kullanır. Yükseltmeden önce tüm dünyayı yedekleyin. Yeni işlemler içeren kaydı 1.2.0'a düşürmek desteklenmez. Özgün Millénaire JAR'ı, denge ayarları, yayımlanmış 1.2.0 dosyaları ve önceki doğrulama kanıtları korunur.

Gerçek özgün NPC'lerle bağımsız köy fethi 13. günde, iki zafer gerektiren başkent fethi 5. günde gözlendi; her iki dünya ayrı JVM'de yeniden açıldı. Etkilenen 15 paket çiftinin gerçek `All N required tests passed` satırları ve hangi JAR ile çalıştıkları saklandı. Sekiz gerçek askerî/mali JVM kesintisinin her toparlanma turu `All 1 required tests passed` verdi; kasıtlı durdurmalar test başarısı sayılmaz. [Tam sonuçlar ve korunan başarısız denemeler](https://github.com/Sarikli/millenairekingdoms/blob/1.2.1/docs/1.2.1_PROGRESS.md) grafik istemci ve çevrimiçi giriş kontrollerini ayrı belirtir.

Denetimdeki sınırlar gizlenmez: fiziksel disk doğrulaması bir tick süresini aşabilir; eski vergi teslimindeki kayıtlar arası kesinti riski bu yamayla çözülmüş sayılmaz; sentetik 500 kayıtlı dünyanın 3 GiB ile yeniden açılması özgün yükleme yolunda başarısız oldu. Büyük mali/mimari dönüşümler ve ana dili konuşanların çeviri incelemesi sonraki çalışmalardır.
