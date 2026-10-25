package com.millenairekingdoms.history;

import java.util.List;
import java.util.UUID;

/** Deterministic literary presentation of immutable facts. No invented incidents, quotations or named actors. */
public final class ChronicleNarrator {
    public record Account(String heading,String prose,String perspective,String culture,String ethos,int variant) {}
    private ChronicleNarrator(){}
    public static Account render(HistoryEvent event,UUID perspective,String language){
        boolean tr=language.equals("tr_tr");
        var voice=event.realms().stream().filter(r->r.id().equals(perspective)).findFirst().orElse(event.realms().getFirst());
        boolean opposing=event.realms().size()>1 && voice.id().equals(event.realms().get(1).id());
        int variant=Math.floorMod(event.id().hashCode() ^ voice.id().hashCode(),3);
        String a=event.realms().getFirst().name(),b=event.realms().size()>1?event.realms().get(1).name():"";
        String village=event.places().isEmpty()?(tr?"yerleşim":"the settlement"):event.places().getFirst().name();
        String target=event.places().size()>1?event.places().get(1).name():village;
        String body=tr?turkish(event,a,b,village,target,opposing,variant):english(event,a,b,village,target,opposing,variant);
        String opening=opening(voice.culture(),tr,variant);
        String title=tr?titleTr(event.kind()):titleEn(event.kind());
        return new Account(title,opening+body+" "+emphasis(voice.ethos(),tr,variant),voice.name(),tradition(voice.culture(),tr),voice.ethos(),variant);
    }
    private static String english(HistoryEvent e,String a,String b,String v,String target,boolean opponent,int variant){
        return switch(e.kind()){
            case FOUNDING->variant==0?"A new realm, "+a+", was founded with "+v+" as its capital. From this beginning its known history is reckoned.":"The settlements gained a new political centre: "+v+" became the capital of the newly founded realm of "+a+". Its earlier fortunes are beyond this record.";
            case INCORPORATION->variant==0?"The village of "+v+" entered the realm of "+a+". From this day their political fortunes stood together.":"The bond of membership was established between "+v+" and "+a+". Their political fortunes were now bound beneath the same authority.";
            case WAR->opponent?"The peace of the realm was broken when "+a+" declared war upon "+b+". From the latter realm's vantage, this date began the reckoning of war.":"The realm of "+a+" declared war upon "+b+". Arms now entered their political reckoning, and the course of their struggle lay ahead.";
            case PEACE->variant==0?"The recorded war between "+a+" and "+b+" ended in peace. Their altered allegiances remained as they stood when the agreement was made.":"Peace was entered between "+a+" and "+b+". The annals close this war here, without erasing the changes it had already brought.";
            case VILLAGE_CONQUEST->opponent?"The village of "+v+" passed from "+b+" to "+a+" by conquest. In the former realm's account, the loss narrowed the reach of its authority.":"By the recorded outcome of conquest, "+v+" came under the authority of "+a+". The reach of authority changed with that allegiance.";
            case CAPITAL_CONQUEST->opponent?"The capital at "+v+" was conquered by "+a+". For "+b+", this was the severing of its political centre.":"The capital at "+v+" passed to "+a+" by conquest. In the victor's annals this entry marks a change at the very centre of its opponent's realm.";
            case COLLAPSE->"The realm of "+a+" fell after its capital was conquered. Its living authority ended, but its name and these earlier records remain in the archive.";
            case RULER_CLAIM->"Authority over "+a+" was claimed by "+actor(e,false)+". The entry concerns government of the realm; the villages' own life continued under their established customs.";
            case RULER_RELEASE->"The authority formerly held by "+actor(e,false)+" over "+a+" came to an end. The keeping of the realm would henceforth rest upon other hands.";
            case CONSTRUCTION_ORDER->"The treasury of "+a+" supported "+(e.facts().getOrDefault("upgrade","false").equals("true")?"an improvement to an existing building":"a new building commission")+" at "+v+". The mandate was accepted; the labour and materials still belonged to the village's builders. Completion is recorded separately.";
            case CONSTRUCTION_COMPLETE->"The commissioned work at "+v+" was completed by the village's builders. Their finished work is now entered apart from the earlier promise of royal support.";
            case INVESTMENT->"The government of "+a+" committed treasury resources to a justified undertaking at "+v+". Treasury resources were entrusted to the work, whose fruits still depended upon village labour.";
            case SHIPMENT->"A significant shipment from "+v+" reached "+target+" within "+a+". The goods were received into village storage; the journey of this load had reached its appointed end.";
        };
    }
    private static String turkish(HistoryEvent e,String a,String b,String v,String target,boolean opponent,int variant){
        return switch(e.kind()){
            case FOUNDING->variant==0?a+" adlı krallık, "+v+" başkent olmak üzere kuruldu. Bilinen tarihi bu başlangıçtan sayılır.":"Yerleşimler arasında yeni bir siyasi merkez doğdu. "+v+", yeni kurulan "+a+" adlı krallığın başkenti oldu; daha eski günler bu kaydın bilgisi dışındadır.";
            case INCORPORATION->variant==0?v+" yerleşimi, "+a+" adlı krallığa katıldı. Bundan böyle iki yerin siyasi yazgısı aynı hüküm altında birleşti.":v+" yerleşimi ile "+a+" adlı krallık arasında üyelik bağı kuruldu. Bundan böyle siyasi yazgıları aynı hükme bağlandı.";
            case WAR->opponent?a+" adlı krallık, "+b+" adlı krallığa savaş ilan ettiğinde barış bozuldu. Savaşa maruz kalan tarafın yıllıklarında bu tarih, savaş günlerinin başlangıcı oldu.":a+" adlı krallık, "+b+" adlı krallığa savaş ilan etti. Artık siyasi hesaplarda silahın da payı vardı; mücadelenin nasıl sonuçlanacağını gelecek günler gösterecekti.";
            case PEACE->variant==0?a+" ve "+b+" adlı krallıklar arasındaki savaş barışla sona erdi. Değişmiş bağlılıklar, anlaşma anındaki hâliyle kaldı.":a+" ve "+b+" adlı krallıklar arasında barış kayda geçti. Yıllıklar bu savaşı burada kapattı; savaşın getirdiği değişiklikleri geçmişten silmedi.";
            case VILLAGE_CONQUEST->opponent?v+" yerleşimi fetihle "+b+" adlı krallıktan "+a+" adlı krallığa geçti. Önceki yönetimin yıllıklarında bu kayıp, hükmün eriştiği yerlerin daralmasıydı.":"Kayda geçmiş fethin ardından "+v+" yerleşimi, "+a+" adlı krallığın hükmüne girdi. Bu bağlılıkla birlikte hükmün eriştiği yerler de değişti.";
            case CAPITAL_CONQUEST->opponent?v+" başkenti, "+a+" adlı krallık tarafından fethedildi. "+b+" adlı krallık için bu, siyasi merkezin elden çıkışıydı.":v+" başkenti fetihle "+a+" adlı krallığın hükmüne geçti. Galip tarafın yıllıklarında bu tarih, karşı krallığın merkezindeki değişimi işaretledi.";
            case COLLAPSE->a+" adlı krallık, başkenti fethedildikten sonra çöktü. Yaşayan hükmü sona erdi; adı ve önceki kayıtları arşivde kaldı.";
            case RULER_CLAIM->actor(e,true)+", "+a+" adlı krallığın hükümdarlığını üstlendi. Bu kayıt krallığın yönetimine dairdir; köylerin kendi hayatı yerleşmiş usullerince sürdü.";
            case RULER_RELEASE->actor(e,true)+" tarafından "+a+" adlı krallık üzerinde kullanılan hükümdarlık yetkisi sona erdi. Ülkenin yönetimi bundan böyle başka ellere kalacaktı.";
            case CONSTRUCTION_ORDER->a+" adlı krallığın hazinesi, "+v+" yerleşiminde "+(e.facts().getOrDefault("upgrade","false").equals("true")?"mevcut bir binanın geliştirilmesine":"yeni bir binanın yapımına")+" destek ayırdı. Emir kabul edildi; emek ve malzeme işi köyün ustalarına kaldı. Yapının bitişi ayrı kayda geçecektir.";
            case CONSTRUCTION_COMPLETE->v+" yerleşimindeki sipariş edilmiş yapı işi, köyün ustaları tarafından tamamlandı. Bitmiş eser, daha önce verilen hazine desteğinden ayrı olarak yıllıklara işlendi.";
            case INVESTMENT->a+" adlı krallığın yönetimi, "+v+" yerleşimindeki gerekçelendirilmiş işe hazine kaynağı ayırdı. Hazineden ayrılan kaynak işe emanet edildi; vereceği meyve köyün emeğine bağlıydı.";
            case SHIPMENT->v+" yerleşiminden çıkan önemli sevkiyat, "+a+" adlı krallık içindeki "+target+" yerleşimine ulaştı. Mallar köy deposuna teslim edildi; bu yükün yolculuğu varacağı yere erişti.";
        };
    }
    private static String opening(String culture,boolean tr,int variant){
        String id=culture.contains(":")?culture.substring(culture.indexOf(':')+1):culture;
        if(tr)return switch(id){
            case "seljuk"->new String[]{"Vekayinamede bu günün karşısına şu kayıt düştü: ","Vakanüvis, yılların hesabına şu hadiseyi ekledi: ","Bu tarihin vekayinamesi şöyle anlatır: "}[variant];
            case "mayan"->new String[]{"Günlerin hesabında bu hadisenin yeri şöyle tutuldu: ","Günler birbiri ardına sayılırken bu değişim kayda geçti: ","Bu günün anlatısı şöyle saklandı: "}[variant];
            case "inuits"->new String[]{"Yerleşimlerin anlatısında bu değişim şöyle hatırlanır: ","Bu günün anlatısı, yerleşimlerin değişen bağlarına dairdir: ","Anlatının bu yerinde şu hadise hatırlanır: "}[variant];
            case "japanese"->new String[]{"Yıllıklarda bu günün altında şu kayıt bulunur: ","Günlerin sırası bu hadiseye geldi: ","Yıllıkların bu yaprağında şöyle anlatılır: "}[variant];
            case "indian"->new String[]{"Yerleşimlerin tarih anlatısı bu günü şöyle anar: ","Günlerin hesabında bu değişime de yer verildi: ","Tarih anlatısında bu güne şöyle dönülür: "}[variant];
            case "byzantines"->new String[]{"Kronikçinin hesabında bu gün şöyle yer buldu: ","Kroniğin bu tarihli yaprağına şu kayıt düştü: ","Yılların kaydı bu hadiseyi de saklar: "}[variant];
            default->new String[]{"Vakanüvis bu günü şöyle kayda geçirdi: ","Bu tarihin yanında şu hadise yer aldı: ","Yıllıkların bu yaprağına şu kayıt düştü: "}[variant];};
        return switch(id){
            case "seljuk"->new String[]{"The chronicle sets against this date: ","To the reckoning of years the chronicler added: ","The account of this day runs thus: "}[variant];
            case "mayan"->new String[]{"In the count of days this change is remembered: ","As the days were counted, this entry took its place: ","The account kept for this day tells: "}[variant];
            case "inuits"->new String[]{"In the telling of the settlements this change is remembered: ","This part of the telling concerns the bonds between settlements: ","The account turns here to this change: "}[variant];
            case "japanese"->new String[]{"The annals place under this day: ","The order of days brings this entry: ","Upon this leaf the annals relate: "}[variant];
            case "indian"->new String[]{"Among the accounts of the settlements this day is remembered: ","The reckoning of days made room for this change: ","The historical account turns to this day: "}[variant];
            case "byzantines"->new String[]{"In the chronicler's reckoning this day bears the entry: ","The chronicle sets down upon this dated leaf: ","The record of the years preserves this account: "}[variant];
            default->new String[]{"The chronicler set down this account: ","Against this date the annals bear the following entry: ","Upon this leaf of the record stands this account: "}[variant];};
    }
    private static String actor(HistoryEvent event,boolean tr){return event.actorName().isBlank()?(tr?"Kayda geçmiş hükümdar":"the recorded ruler"):event.actorName();}
    private static String emphasis(String ethos,boolean tr,int variant){
        if(tr)return switch(ethos){
            case "MILITARY"->variant==0?"Bir ülkenin kudreti, silahı kadar üstlendiği yükle de tartılır.":"Yerleşimlerin korunması söz konusu olduğunda şeref de ağır bir yüktür.";
            case "RELIGIOUS"->variant==0?"Yeni kurulan her düzen, geçmişten devralınmış usullerin yanında yerini bulmalıdır.":"Yıllar geçer; devralınan usullerin korunması, hükmün ölçülerinden biri olarak kalır.";
            case "AGRICULTURAL"->variant==0?"Geçimini topraktan sağlayanlar için değişimin değeri, yerleşik hayatın sürmesinde aranır.":"Hüküm, ancak altındaki haneler geçimini sürdürebildiği müddetçe meyve verir.";
            case "ARTISAN"->variant==0?"Bir ülkenin bolluğu, çalışan ellere ve yerleşimler arasındaki alışverişe dayanır.":"Verilen söz, bolluğu meydana getiren sabırlı emeğin yanında hafif kalır.";
            default->variant==0?"Böylece hükmün dengesi bir kez daha değişti; hüküm, ülkenin taşıyabileceği yükle tartılır.":"Kalıcı bir düzen, yükümlülüklerini eldeki imkânlarla tartmak zorundadır.";};
        return switch(ethos){
            case "MILITARY"->variant==0?"The strength of a realm is weighed in its obligations as well as its arms.":"Honour is a heavy burden where the defence of settlements is at stake.";
            case "RELIGIOUS"->variant==0?"What is newly made must find its place beside the customs handed down.":"The years pass; the keeping of inherited customs remains a measure of authority.";
            case "AGRICULTURAL"->variant==0?"To those who live by the land, the worth of change lies in the continuance of settled life.":"Authority bears fruit only while the households beneath it can keep their livelihood.";
            case "ARTISAN"->variant==0?"The prosperity of a realm rests upon working hands and the exchange between settlements.":"Promises are light beside the patient work from which prosperity is made.";
            default->variant==0?"Thus the balance of authority took another turn; judgement rests upon what the realm can sustain.":"A lasting settlement must weigh its obligations against the means at hand.";};
    }
    public static String tradition(String culture,boolean tr){
        String id=culture.contains(":")?culture.substring(culture.indexOf(':')+1):culture;
        return switch(id){case "norman"->tr?"Norman yıllıkları":"Norman annals";case "byzantines"->tr?"Bizans kroniği":"Byzantine chronicle";case "seljuk"->tr?"Selçuklu vekayinamesi":"Seljuk chronicle";case "indian"->tr?"Hint tarih anlatısı":"Indian historical account";case "japanese"->tr?"Japon yıllıkları":"Japanese annals";case "mayan"->tr?"Maya tarih anlatısı":"Maya historical account";case "inuits"->tr?"İnuit anlatı geleneği":"Inuit narrative tradition";default->tr?"Dünya yıllıkları":"World annals";};
    }
    public static String titleEn(HistoryEvent.Kind k){return switch(k){case FOUNDING->"A realm founded";case INCORPORATION->"A new allegiance";case WAR->"War proclaimed";case PEACE->"Peace entered";case VILLAGE_CONQUEST->"A village conquered";case CAPITAL_CONQUEST->"A capital conquered";case COLLAPSE->"A realm fallen";case RULER_CLAIM->"Authority claimed";case RULER_RELEASE->"Authority relinquished";case CONSTRUCTION_ORDER->"A royal commission";case CONSTRUCTION_COMPLETE->"The work completed";case INVESTMENT->"A treasury investment";case SHIPMENT->"Goods received";};}
    public static String titleTr(HistoryEvent.Kind k){return switch(k){case FOUNDING->"Bir krallığın kuruluşu";case INCORPORATION->"Yeni bir bağlılık";case WAR->"Savaş ilanı";case PEACE->"Barış kaydı";case VILLAGE_CONQUEST->"Bir köyün fethi";case CAPITAL_CONQUEST->"Başkent fethi";case COLLAPSE->"Bir krallığın çöküşü";case RULER_CLAIM->"Hükümdarlığın üstlenilmesi";case RULER_RELEASE->"Hükümdarlığın sona erişi";case CONSTRUCTION_ORDER->"Kraliyet yapım emri";case CONSTRUCTION_COMPLETE->"İşin tamamlanması";case INVESTMENT->"Hazine yatırımı";case SHIPMENT->"Malların teslimi";};}
}
