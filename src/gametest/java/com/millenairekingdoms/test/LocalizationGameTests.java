package com.millenairekingdoms.test;

import com.google.gson.*;
import com.millenairekingdoms.history.*;
import com.millenairekingdoms.localization.LocalizedResources;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.regex.Pattern;
import com.millenairekingdoms.localization.PublicText;
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
    public static void legacy4992EventFixturesKeepFactsAndIdentityWhileReceivingTheRevisedPresentation(GameTestHelper h)throws Exception{
        int count=0;try(var in=LocalizationGameTests.class.getResourceAsStream("/narrative-alpha1-golden.json.gz");var reader=new InputStreamReader(new GZIPInputStream(in),StandardCharsets.UTF_8)){
            for(var raw:JsonParser.parseReader(reader).getAsJsonArray()){var row=raw.getAsJsonObject();var event=GSON.fromJson(row.get("event"),HistoryEvent.class);var before=event.save();var actual=ChronicleNarrator.render(event,UUID.fromString(row.get("perspective").getAsString()),row.get("language").getAsString());var expected=GSON.fromJson(row.get("account"),ChronicleNarrator.Account.class);
                h.assertTrue(actual.perspective().equals(expected.perspective()) && actual.culture().equals(expected.culture()) && actual.ethos().equals(expected.ethos()) && actual.variant()==expected.variant()
                        && before.equals(event.save()) && before.equals(HistoryEvent.load(before).save()) && !actual.prose().isBlank(),"Legacy event identity, voice, culture, ethos, variant and immutable facts survive the intentional editorial revision at case"+count+" "+event.kind());count++;}
        }h.assertTrue(count==4992,"Independent legacy corpus extracted from the preserved first-alpha binary is retained without rewriting expectations");System.out.println("LOCALIZATION_LEGACY_EVENTS cases="+count+" facts_exact=true presentation_intentionally_revised=true");h.succeed();
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
    private static Map<String,Integer> slots(String value){
        var result=new TreeMap<String,Integer>();var pattern=Pattern.compile("%(?:(\\d+)\\$)?([sdf])|%%");var match=pattern.matcher(value);int next=1,end=0;
        while(match.find()){
            if(value.substring(end,match.start()).contains("%"))throw new IllegalArgumentException("Malformed percent instruction: "+value);
            String identity;if(match.group().equals("%%"))identity="literal-percent";else identity=(match.group(1)==null?Integer.toString(next++):match.group(1))+match.group(2);
            result.merge(identity,1,Integer::sum);end=match.end();
        }
        if(value.substring(end).contains("%"))throw new IllegalArgumentException("Trailing malformed percent instruction: "+value);return result;
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void everyOriginalLocaleHasEveryMandatoryKeyAndAllSemanticArguments(GameTestHelper h){
        var source=LocalizedResources.catalog("en_us");int checked=0;
        for(var locale:LocalizedResources.locales()){
            var target=LocalizedResources.catalog(locale);h.assertTrue(target.keySet().equals(source.keySet()) && LocalizedResources.missingKeys(locale).isEmpty(),"Complete mandatory resource key set for "+locale);
            for(var entry:source.entrySet()){
                var value=target.get(entry.getKey());h.assertTrue(!value.isBlank() && !value.contains("ZX000ZX") && slots(entry.getValue()).equals(slots(value)),"Semantic argument identities/multiplicity and format grammar: "+locale+"/"+entry.getKey());checked++;
            }
        }
        System.out.println("LOCALIZATION_COVERAGE locales="+LocalizedResources.locales().size()+" keys_per_locale="+source.size()+" values="+checked+" missing=0 malformed=0 native_review=false");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void everyLocaleRendersAll13KindsBothPerspectivesCulturesAndEthosWithoutEnglishFallback(GameTestHelper h){
        var cultures=List.of("norman","byzantines","seljuk","indian","japanese","mayan","inuits","unknown");var ethos=List.of("MILITARY","RELIGIOUS","AGRICULTURAL","ARTISAN","GENERAL");int cases=0;
        for(var locale:LocalizedResources.locales())for(var kind:HistoryEvent.Kind.values())for(int n=0;n<24;n++){
            var event=NarrativeGameTests.event(kind,n,"millenaire:"+cultures.get(n%cultures.size()),ethos.get(n%ethos.size()));var before=event.save();var voices=new HashSet<String>();
            for(var realm:event.realms()){
                var account=ChronicleNarrator.render(event,realm.id(),(key,args)->{
                    h.assertTrue(LocalizedResources.catalog(locale).containsKey(key),"Narrative must resolve directly in "+locale+" without English fallback: "+key);return LocalizedResources.text(locale,key,args);
                });
                h.assertTrue(!account.heading().isBlank() && !account.prose().isBlank() && !account.prose().contains("millenairekingdoms.") && account.prose().contains(kind==HistoryEvent.Kind.CONSTRUCTION_COMPLETE?"O'Neill %s":"İznik"),"All event kinds render localized prose while factual names remain literal: "+locale+"/"+kind);voices.add(account.prose());cases++;
            }
            if(kind==HistoryEvent.Kind.WAR)h.assertTrue(voices.size()==2,"War perspectives remain distinct in "+locale);
            h.assertTrue(before.equals(event.save()),"Rendering never changes facts for "+locale);
        }
        System.out.println("LOCALIZATION_NARRATIVES cases="+cases+" event_kinds="+HistoryEvent.Kind.values().length+" missing_narrative_keys=0 native_review=false");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void publicChatAndDynamicRefusalsKeepNamesFactsAndClickCommandsStructured(GameTestHelper h){
        String name="O'Neill %s / İznik";var message=PublicText.of("You now rule %s. The NPC leader keeps running the village.",name);
        h.assertTrue(message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents && message.getString().equals("You now rule "+name+". The NPC leader keeps running the village."),"Server fallback stays byte-identical and names are literal component parameters");
        var diagnostic=PublicText.error("no military progress for 12 days");h.assertTrue(diagnostic.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents && diagnostic.getString().equals("no military progress for 12 days"),"Dynamic reason is translated only at presentation; the factual day value/internal reason stay intact");
        var id=UUID.randomUUID();var structured=PublicText.of("%s",id);h.assertTrue(structured.getString().equals(id.toString()),"UUID/resource/enum identifiers become literal supported text arguments instead of invalid Minecraft component values");
        var refusal=new com.millenairekingdoms.util.KingdomException("millenairekingdoms.raid.refused","The raid cannot start: %s.",diagnostic);h.assertTrue(refusal.getMessage().equals("The raid cannot start: no military progress for 12 days."),"Structured localized reasons retain readable English exception/log fallback rather than component debug syntax");
        var typed=net.minecraft.network.chat.Component.translatableWithFallback("test.numeric","%s / %s",1234,net.minecraft.network.chat.Component.literal("1234")).withStyle(style->style.withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,"/kingdom gui")));
        var arabic=com.millenairekingdoms.localization.LocalizedComponents.numbers(typed,"ar_sa");h.assertTrue(arabic.getString().equals(LocalizedResources.number("ar_sa",1234)+" / 1234") && arabic.getStyle().getClickEvent().getValue().equals("/kingdom gui") && ((net.minecraft.network.chat.contents.TranslatableContents)typed.getContents()).getArgs()[0] instanceof Number,"Typed numbers follow the active locale; numeric-looking names, commands and original component facts remain literal/unchanged");
        var literal=PublicText.error("future operation "+UUID.randomUUID());h.assertTrue(!(literal.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents),"Unknown diagnostic preserves readable English evidence instead of inventing a translation");
        var button=com.millenairekingdoms.politics.PoliticsService.button(PublicText.of("[Accept]"),"/kingdom accept 00000000-0000-0000-0000-000000000001",PublicText.of("Attach %s",name));
        h.assertTrue(button.getStyle().getClickEvent().getValue().endsWith("00000000-0000-0000-0000-000000000001") && button.getStyle().getHoverEvent()!=null,"Translation preserves the original authoritative command/click and hover payloads");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void literalDiagnosticInventoryAndEveryDynamicTemplateHaveTranslationsInEveryLocale(GameTestHelper h)throws Exception{
        try(var in=PublicText.class.getResourceAsStream("/assets/millenairekingdoms/public-text-sources.json")){
            h.assertTrue(in!=null,"Production diagnostic resource is read through its owning mod module");var root=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();var keys=new HashSet<String>();root.getAsJsonObject("errors").entrySet().forEach(e->keys.add(e.getValue().getAsString()));root.getAsJsonArray("patterns").forEach(e->keys.add(PublicText.key(e.getAsString())));
            for(var locale:LocalizedResources.locales())h.assertTrue(LocalizedResources.catalog(locale).keySet().containsAll(keys),"Literal and dynamic player-facing diagnostic inventory present in "+locale);
            h.assertTrue(keys.size()>500,"Public error/notification inventory is included, alongside GUI and literary narrative resources");
        }h.succeed();
    }
}
