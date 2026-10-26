package com.millenairekingdoms.test;

import com.millenairekingdoms.history.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Synthetic factual inputs test presentation only, never autonomous gameplay. */
@PrefixGameTestTemplate(false)
public final class NarrativeGameTests {
    private static final String NS="millenairekingdoms_history_tests";
    private static final UUID A=new UUID(10,1),B=new UUID(10,2);
    static HistoryEvent event(HistoryEvent.Kind kind,int variant,String culture,String ethos){return HistoryEvent.create(kind,81,1944000,"minecraft:overworld","narrative/"+variant,
            List.of(new HistoryEvent.Realm(A,"İznik",culture,ethos),new HistoryEvent.Realm(B,"Bellefontaine","millenaire:norman","AGRICULTURAL")),
            List.of(new HistoryEvent.Place(new UUID(10,3),"O'Neill %s",true,100,200),new HistoryEvent.Place(new UUID(10,4),"Aşağıköy",true,300,400)),null,"","","RECORDED",Map.of("plan","millenaire:norman/forge"));}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void everyKindHasDistinctEnglishTurkishProseAndNoInventedIncident(GameTestHelper h){
        for(var kind:HistoryEvent.Kind.values()){
            var e=event(kind,1,"millenaire:norman","GENERAL");var facts=e.save();var en=ChronicleNarrator.render(e,A,"en_us");var tr=ChronicleNarrator.render(e,A,"tr_tr");
            h.assertTrue(!en.prose().isBlank() && !tr.prose().isBlank() && !en.prose().equals(tr.prose()) && e.save().equals(facts),"Every kind presents its factual event without padding or changing evidence: "+kind);
            for(var text:List.of(en.prose(),tr.prose()))h.assertTrue(!text.contains("religious insult") && !text.contains("border atrocity") && !text.contains("dine hakaret") && !text.contains("sınır katliamı") && !text.contains("1944000") && !text.contains(e.id().toString()),"No invented incident or raw debug identity in literary account");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void sameFactualWarHasAttackerAndDefenderAccountsWithoutChangingWhoDeclaredIt(GameTestHelper h){
        var e=event(HistoryEvent.Kind.WAR,2,"millenaire:seljuk","MILITARY");
        for(var lang:List.of("en_us","tr_tr")){var attacker=ChronicleNarrator.render(e,A,lang);var defender=ChronicleNarrator.render(e,B,lang);
            h.assertTrue(!attacker.prose().equals(defender.prose()) && attacker.perspective().equals("İznik") && defender.perspective().equals("Bellefontaine") && attacker.prose().contains("İznik") && defender.prose().contains("İznik"),"Shared ID retains actual declaration direction with distinct participant emphasis");}
        h.assertTrue(e.realms().getFirst().id().equals(A) && e.kind()==HistoryEvent.Kind.WAR,"Perspective does not reverse historical aggressor");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void ethosUsesOccasionalWordChoicesAndCulturesKeepTheirOwnCaptions(GameTestHelper h){
        for(var lang:List.of("en_us","tr_tr")){
            int source=0;while(ChronicleNarrator.render(event(HistoryEvent.Kind.FOUNDING,source,"millenaire:norman","GENERAL"),A,lang).variant()!=0)source++;
            var words=lang.equals("en_us")?List.of("was established","was founded","took shape","emerged","came into being"):List.of("kuruldu","doğdu","oluştu","yükseldi","ortaya çıktı");
            var ethos=List.of("MILITARY","RELIGIOUS","AGRICULTURAL","ARTISAN","GENERAL");var voices=new HashSet<String>();var frames=new HashSet<String>();
            for(int n=0;n<ethos.size();n++){var account=ChronicleNarrator.render(event(HistoryEvent.Kind.FOUNDING,source,"millenaire:norman",ethos.get(n)),A,lang);voices.add(account.prose());h.assertTrue(account.prose().contains(words.get(n)),"The actual founding verb follows the selected ethos");frames.add(account.prose().replace(words.get(n),"<verb>"));}
            h.assertTrue(voices.size()==5 && frames.size()==1,"Ethos changes only a word-level choice inside the event, never an added sentence");var cultures=new HashSet<String>();
            for(var culture:List.of("norman","byzantines","seljuk","indian","japanese","mayan","inuits"))cultures.add(ChronicleNarrator.render(event(HistoryEvent.Kind.FOUNDING,7,"millenaire:"+culture,"GENERAL"),A,lang).culture());
            h.assertTrue(cultures.size()==7,"Seven cultural identities remain in the caption without compulsory preambles");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void everyKindHasMultipleStableVariantsAfterSerialization(GameTestHelper h){
        for(var kind:HistoryEvent.Kind.values())for(var lang:List.of("en_us","tr_tr")){
            var variants=new HashSet<Integer>();var prose=new HashSet<String>();for(int n=0;n<48;n++){var e=event(kind,n,"millenaire:byzantines","GENERAL");var rendered=ChronicleNarrator.render(e,A,lang);variants.add(rendered.variant());prose.add(rendered.prose());h.assertTrue(rendered.equals(ChronicleNarrator.render(HistoryEvent.load(e.save()),A,lang)),"Reload does not reroll phrasing or perspective");}
            h.assertTrue(variants.size()==3 && prose.size()==3,"Every EN/TR event type has three actual bodies, rather than identical prose behind different variant numbers: "+kind);
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void turkishNamesAreLiteralAndNeverReceiveGuessedSuffixOrFormatInterpretation(GameTestHelper h){
        var e=event(HistoryEvent.Kind.SHIPMENT,5,"millenaire:inuits","ARTISAN");var tr=ChronicleNarrator.render(e,A,"tr_tr").prose();
        h.assertTrue(tr.contains("O'Neill %s") && tr.contains("Aşağıköy") && tr.contains("İznik") && !tr.contains("O'Neill %s'ın") && !tr.contains("İznik'nın"),"Names survive literally; settlement/realm noun phrases avoid unsafe Turkish suffix guesses");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=400)
    public static void noEventRequestsAnEthosMottoOrACompulsoryChroniclerPreamble(GameTestHelper h){
        for(var kind:HistoryEvent.Kind.values())for(var ethos:List.of("MILITARY","RELIGIOUS","AGRICULTURAL","ARTISAN","GENERAL"))for(int variant=0;variant<12;variant++){
            var e=event(kind,variant,"millenaire:norman",ethos);var keys=new ArrayList<String>();var before=e.save();
            var account=ChronicleNarrator.render(e,A,(key,args)->{keys.add(key);return com.millenairekingdoms.localization.LocalizedResources.text("tr_tr",key,args);});
            h.assertTrue(keys.stream().noneMatch(k->k.contains(".opening.") || k.contains(".emphasis.")) && keys.stream().anyMatch(k->k.contains(".compact.body.")),"No per-event stock preamble, ethos moral or legacy body may be appended");
            h.assertTrue(before.equals(e.save()) && !account.prose().contains("\n"),"Presentation never changes facts or appends a second boilerplate paragraph");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=400)
    public static void reviewedExamplesDistinguishOrdersCompletionAndBothMilitaryPerspectives(GameTestHelper h)throws Exception{
        var samples=new net.minecraft.nbt.ListTag();
        for(var kind:HistoryEvent.Kind.values())for(var lang:List.of("en_us","tr_tr"))for(boolean opposing:List.of(false,true)){
            if(opposing && !Set.of(HistoryEvent.Kind.WAR,HistoryEvent.Kind.VILLAGE_CONQUEST,HistoryEvent.Kind.CAPITAL_CONQUEST,HistoryEvent.Kind.STRATEGIC_BATTLE).contains(kind))continue;
            var perspective=opposing?B:A;HistoryEvent e=null;ChronicleNarrator.Account account=null;
            for(int n=0;n<100;n++){e=example(kind,n,false);account=ChronicleNarrator.render(e,perspective,lang);if(account.variant()==0)break;}
            h.assertTrue(account.variant()==0,"A deterministic reviewed example exists");
            if(kind==HistoryEvent.Kind.WAR && !opposing)h.assertTrue(account.prose().equals(lang.equals("en_us")?"Akçay declared war on Karadağ.":"Akçay, Karadağ adlı krallığa savaş ilan etti."),"Reviewed war wording names the real declarer and target without an invented incident");
            if(kind==HistoryEvent.Kind.CONSTRUCTION_ORDER)h.assertTrue(account.prose().equals(lang.equals("en_us")?"Akçay commissioned work on “Demirhane” at Çamlıdere.":"Akçay, Çamlıdere yerleşiminde “Demirhane” için yapım emri verdi."),"An accepted order is a commission, never an invented completed building");
            if(kind==HistoryEvent.Kind.CONSTRUCTION_COMPLETE)h.assertTrue(account.prose().equals(lang.equals("en_us")?"Work on “Demirhane” at Çamlıdere was completed.":"Çamlıdere yerleşiminde “Demirhane” için verilen iş tamamlandı."),"Completion has its own clear factual statement");
            if(kind==HistoryEvent.Kind.SHIPMENT)h.assertTrue(account.prose().equals(lang.equals("en_us")?"A shipment from Çamlıdere reached Göktepe on behalf of Akçay.":"Akçay adına Çamlıdere yerleşiminden gönderilen yük, Göktepe deposuna ulaştı."),"Delivery states the recorded source/destination, with no repeated principle about prosperity");
            var row=new CompoundTag();row.putString("language",lang);row.putString("kind",kind.name());row.putBoolean("opposing",opposing);row.putString("heading",account.heading());row.putString("prose",account.prose());samples.add(row);
            System.out.println("NARRATIVE_EDITORIAL_SAMPLE "+lang+" "+kind+" opposing="+opposing+" | "+account.prose());
        }
        for(var lang:List.of("en_us","tr_tr")){var prose=ChronicleNarrator.render(example(HistoryEvent.Kind.CONSTRUCTION_ORDER,4,true),A,lang).prose();h.assertTrue(lang.equals("en_us")?prose.contains("improv") || prose.contains("upgrad"):prose.contains("geliştirme"),"Upgrade commissions retain their actual work type");}
        var saved=new CompoundTag();saved.put("samples",samples);HistoryFiles.write(h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("narrative-editorial-samples.dat"),saved);h.succeed();
    }
    private static HistoryEvent example(HistoryEvent.Kind kind,int n,boolean upgrade){
        return HistoryEvent.create(kind,81,1944000,"minecraft:overworld","editorial/"+kind+"/"+n,
                List.of(new HistoryEvent.Realm(A,"Akçay","millenaire:norman","GENERAL"),new HistoryEvent.Realm(B,"Karadağ","millenaire:seljuk","RELIGIOUS")),
                List.of(new HistoryEvent.Place(new UUID(10,3),"Çamlıdere",true,100,200),new HistoryEvent.Place(new UUID(10,4),"Göktepe",true,300,400)),new UUID(10,5),"Alparslan","","RECORDED",Map.of("buildingName","Demirhane","upgrade",Boolean.toString(upgrade)));
    }
    @GameTest(templateNamespace=NS,template="empty",batch="narrative_restart",timeoutTicks=200)
    public static void separateJvmKeepsTheSameEnglishAndTurkishAccounts(GameTestHelper h){
        var file=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("narrative-restart.dat");
        if(System.getProperty("kingdoms.tests.persistence","write").equals("read")){var saved=HistoryFiles.read(file);var e=HistoryEvent.load(saved.getCompound("event"));h.assertTrue(ChronicleNarrator.render(e,B,"en_us").prose().equals(saved.getString("en")) && ChronicleNarrator.render(e,B,"tr_tr").prose().equals(saved.getString("tr")),"Independent JVM produces byte-identical prose without random seeds/Internet");h.succeed();return;}
        var e=event(HistoryEvent.Kind.WAR,9,"millenaire:seljuk","RELIGIOUS");var saved=new CompoundTag();saved.put("event",e.save());saved.putString("en",ChronicleNarrator.render(e,B,"en_us").prose());saved.putString("tr",ChronicleNarrator.render(e,B,"tr_tr").prose());HistoryFiles.write(file,saved);h.succeed();
    }
}
