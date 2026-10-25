package com.millenairekingdoms.test;

import com.google.gson.*;
import com.millenairekingdoms.history.*;
import com.millenairekingdoms.localization.LocalizedResources;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class LocalizationGameTests {
    private static final String NS="millenairekingdoms_localization_tests";
    private static final Gson GSON=new Gson();
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void original903LocaleManifestHasExactlyTheVerified26Codes(GameTestHelper h){
        var expected=List.of("ar_sa","cs_cz","da_dk","de_de","en_us","es_es","et_ee","fr_fr","hi_in","hu_hu","it_it","ja_jp","ko_kr","nb_no","nl_nl","pl_pl","pt_br","pt_pt","ru_ru","sl_si","sv_se","th_th","tr_tr","uk_ua","zh_cn","zh_tw");
        h.assertTrue(LocalizedResources.locales().equals(expected),"Exact original9.0.3 asset locales, not legacy quest language aliases or a guessed list");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void releasedEnglishTurkishAccountsRemainByteIdenticalAcross4992IndependentGoldenCases(GameTestHelper h)throws Exception{
        int count=0;try(var in=LocalizationGameTests.class.getResourceAsStream("/narrative-alpha1-golden.json.gz");var reader=new InputStreamReader(new GZIPInputStream(in),StandardCharsets.UTF_8)){
            for(var raw:JsonParser.parseReader(reader).getAsJsonArray()){var row=raw.getAsJsonObject();var event=GSON.fromJson(row.get("event"),HistoryEvent.class);var before=event.save();var actual=ChronicleNarrator.render(event,UUID.fromString(row.get("perspective").getAsString()),row.get("language").getAsString());var expected=GSON.fromJson(row.get("account"),ChronicleNarrator.Account.class);
                h.assertTrue(actual.equals(expected) && before.equals(event.save()),"Released EN/TR prose/heading/voice/culture/ethos/variant/facts unchanged at golden case"+count+" "+event.kind());count++;}
        }h.assertTrue(count==4992,"Golden corpus extracted from the preserved first-alpha binary");System.out.println("LOCALIZATION_ALPHA1_GOLDEN cases="+count+" exact=true");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void literalNamesIndexedArgumentsUnicodeNumbersAndUnknownLanguageFallbackAreSafe(GameTestHelper h){
        h.assertTrue(LocalizedResources.format("en_us","%2$s / %1$s %2$s","O'Neill %s","İznik").equals("İznik / O'Neill %s İznik"),"Arguments are literal, indexed/reordered, and never recursively interpreted");
        h.assertTrue(!LocalizedResources.number("ar_sa",1234).equals(LocalizedResources.number("en_us",1234)),"Standard Java locale number formatting supports Arabic digits/grouping");
        var e=NarrativeGameTests.event(HistoryEvent.Kind.WAR,3,"millenaire:seljuk","MILITARY");var expected=ChronicleNarrator.render(e,null,"en_us");h.assertTrue(expected.equals(ChronicleNarrator.render(e,null,"unsupported_unknown")),"Unknown locale safely falls back to bundled English");
        var customized=ChronicleNarrator.render(e,null,(key,args)->"Resource pack: "+LocalizedResources.text("en_us",key,args));h.assertTrue(customized.prose().contains("Resource pack:") && !customized.heading().equals(expected.heading()),"Presentation accepts the actual current Minecraft resource-pack resolver, rather than hardcoded English/Turkish");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",batch="localization_restart",timeoutTicks=1200)
    public static void selectingDisplayLanguageDoesNotRewriteHistoricalFactsAcrossSeparateJvms(GameTestHelper h){
        var file=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("localization-history-restart.dat");boolean read="read".equals(System.getProperty("kingdoms.tests.persistence"));
        if(!read){var e=NarrativeGameTests.event(HistoryEvent.Kind.SHIPMENT,13,"millenaire:japanese","ARTISAN");var tag=new CompoundTag();tag.put("event",e.save());HistoryFiles.write(file,tag);}
        var before=HistoryFiles.read(file);var e=HistoryEvent.load(before.getCompound("event"));for(var locale:LocalizedResources.locales())ChronicleNarrator.render(e,null,locale);
        h.assertTrue(before.equals(HistoryFiles.read(file)) && before.getCompound("event").equals(e.save()),"Locale selection produces no event/save writes or duplicated facts; same native fact bytes survive a different JVM");h.succeed();
    }
}
