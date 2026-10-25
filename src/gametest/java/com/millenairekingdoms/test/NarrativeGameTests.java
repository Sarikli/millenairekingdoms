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
            h.assertTrue(en.prose().length()>140 && tr.prose().length()>140 && !en.prose().equals(tr.prose()) && e.save().equals(facts),"Every kind has literary localized presentation without changing factual evidence: "+kind);
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
    public static void allFiveEthosAndSevenCulturesHaveStableDistinctVoices(GameTestHelper h){
        for(var lang:List.of("en_us","tr_tr")){
            var voices=new HashSet<String>();for(var ethos:List.of("MILITARY","RELIGIOUS","AGRICULTURAL","ARTISAN","GENERAL"))voices.add(ChronicleNarrator.render(event(HistoryEvent.Kind.FOUNDING,7,"millenaire:norman",ethos),A,lang).prose());
            h.assertTrue(voices.size()==5,"Five governing ethos change narrative emphasis");var cultures=new HashSet<String>();
            for(var culture:List.of("norman","byzantines","seljuk","indian","japanese","mayan","inuits"))cultures.add(ChronicleNarrator.render(event(HistoryEvent.Kind.FOUNDING,7,"millenaire:"+culture,"GENERAL"),A,lang).prose());
            h.assertTrue(cultures.size()==7,"Seven voices do not all assert European feudal institutions");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void everyKindHasMultipleStableVariantsAfterSerialization(GameTestHelper h){
        for(var kind:HistoryEvent.Kind.values())for(var lang:List.of("en_us","tr_tr")){
            var variants=new HashSet<Integer>();for(int n=0;n<48;n++){var e=event(kind,n,"millenaire:byzantines","GENERAL");var rendered=ChronicleNarrator.render(e,A,lang);variants.add(rendered.variant());h.assertTrue(rendered.equals(ChronicleNarrator.render(HistoryEvent.load(e.save()),A,lang)),"Reload does not reroll phrasing or perspective");}
            h.assertTrue(variants.size()==3,"Every event type has three deterministic literary variants");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void turkishNamesAreLiteralAndNeverReceiveGuessedSuffixOrFormatInterpretation(GameTestHelper h){
        var e=event(HistoryEvent.Kind.SHIPMENT,5,"millenaire:inuits","ARTISAN");var tr=ChronicleNarrator.render(e,A,"tr_tr").prose();
        h.assertTrue(tr.contains("O'Neill %s") && tr.contains("Aşağıköy") && tr.contains("İznik") && !tr.contains("O'Neill %s'ın") && !tr.contains("İznik'nın"),"Names survive literally; settlement/realm noun phrases avoid unsafe Turkish suffix guesses");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",batch="narrative_restart",timeoutTicks=200)
    public static void separateJvmKeepsTheSameEnglishAndTurkishAccounts(GameTestHelper h){
        var file=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("narrative-restart.dat");
        if(System.getProperty("kingdoms.tests.persistence","write").equals("read")){var saved=HistoryFiles.read(file);var e=HistoryEvent.load(saved.getCompound("event"));h.assertTrue(ChronicleNarrator.render(e,B,"en_us").prose().equals(saved.getString("en")) && ChronicleNarrator.render(e,B,"tr_tr").prose().equals(saved.getString("tr")),"Independent JVM produces byte-identical prose without random seeds/Internet");h.succeed();return;}
        var e=event(HistoryEvent.Kind.WAR,9,"millenaire:seljuk","RELIGIOUS");var saved=new CompoundTag();saved.put("event",e.save());saved.putString("en",ChronicleNarrator.render(e,B,"en_us").prose());saved.putString("tr",ChronicleNarrator.render(e,B,"tr_tr").prose());HistoryFiles.write(file,saved);h.succeed();
    }
}
