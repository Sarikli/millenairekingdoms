package com.millenairekingdoms.test;

import com.millenairekingdoms.history.*;
import com.millenairekingdoms.network.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Server/codec tests do not prove real client mouse/layout/rendering. */
@PrefixGameTestTemplate(false)
public final class ChronicleViewGameTests {
    private static final String NS="millenairekingdoms_history_tests";
    private static ChronicleRequestPayload request(String mode,UUID kingdom,String kind,long from,long to,UUID cursor,String search){return new ChronicleRequestPayload(UUID.randomUUID(),1,mode,Optional.ofNullable(kingdom),kind,from,to,Optional.ofNullable(cursor),search,Optional.empty());}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void requestCodecBindsSessionFiltersAndRejectsForgedModesAndBounds(GameTestHelper h){
        var request=request("CHRONICLE",UUID.randomUUID(),"WAR",12,80,UUID.randomUUID(),"İznik");var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());
        try{ChronicleRequestPayload.CODEC.encode(buf,request);h.assertTrue(ChronicleRequestPayload.CODEC.decode(buf).equals(request) && KingdomNetwork.VERSION.equals("11"),"New bounded read-only channel retains correlation and filters on matching clients");}finally{buf.release();}
        HistoryGameTests.refused(h,()->request("WRITE",null,"",0,1,null,""));HistoryGameTests.refused(h,()->request("CHRONICLE",null,"FAKE",0,1,null,""));HistoryGameTests.refused(h,()->request("CHRONICLE",null,"",8,1,null,""));HistoryGameTests.refused(h,()->request("CHRONICLE",null,"",0,1,null,"x".repeat(65)));h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void hiddenLocationsAndForeignEconomicAmountsNeverEnterTheResponse(GameTestHelper h){
        var e=NarrativeGameTests.event(HistoryEvent.Kind.SHIPMENT,11,"millenaire:norman","GENERAL");var withFees=HistoryEvent.create(e.kind(),e.day(),e.gameTime(),e.dimension(),e.source(),e.realms(),e.places(),null,"","","DELIVERED",Map.of("fee","9","quantity","64","product","minecraft:cobblestone"));
        var filtered=HistoryVisibility.redact(withFees,Set.of(e.places().getFirst().id()),false);
        h.assertTrue(filtered.places().getFirst().located() && !filtered.places().get(1).located() && filtered.places().get(1).x()==0 && filtered.places().get(1).z()==0 && !filtered.facts().containsKey("fee") && !filtered.facts().containsKey("quantity"),"Hidden historical coordinates/foreign treasury amounts are absent before encoding");
        h.assertTrue(withFees.places().get(1).x()==300 && withFees.facts().get("fee").equals("9") && filtered.id().equals(withFees.id()),"Redaction does not rewrite historical facts or identities");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=2400)
    public static void authoritativeServerFiltersAndPageCursorDoNotMutatePolitics(GameTestHelper h){
        var level=h.getLevel();var data=KingdomSavedData.get(level);var k=data.create("View archive "+UUID.randomUUID(),UUID.randomUUID());for(int n=0;n<36;n++)HistoryHooks.store(level).append(HistoryGameTests.sample(k.id(),n));var player=new Stage5Support.TestPlayer(level,"Chronicle reader");var before=data.save(new CompoundTag(),level.registryAccess());
        var first=ChronicleViews.build(player,request("CHRONICLE",k.id(),"WAR",0,100,null,""));var events=first.getList("events",10);
        h.assertTrue(events.size()==24 && first.hasUUID("next") && events.stream().allMatch(t->((CompoundTag)t).getString("kind").equals("WAR")),"Server applies type/kingdom/time and caps a page at24");
        var second=ChronicleViews.build(player,request("CHRONICLE",k.id(),"WAR",0,100,first.getUUID("next"),""));h.assertTrue(second.getList("events",10).size()==12 && !second.hasUUID("next") && data.save(new CompoundTag(),level.registryAccess()).equals(before),"Cursor finishes without authority/political/economic mutation");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void oldWorldKingdomCanBeBrowsedWithoutInventedFoundingDate(GameTestHelper h){
        var level=h.getLevel();var player=new Stage5Support.TestPlayer(level,"Unknown past reader");var id=UUID.randomUUID();var view=ChronicleViews.build(player,request("CHRONICLE",id,"",0,Long.MAX_VALUE,null,""));
        h.assertTrue(view.getList("events",10).isEmpty() && !view.contains("founded") && view.getString("dimension").equals(level.dimension().location().toString()),"Unknown past remains explicitly unknown and requests cannot name another dimension");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void archiveSearchIncludesHistoricalNamesAndRetainsFallenFlag(GameTestHelper h){
        var level=h.getLevel();var id=UUID.randomUUID();var name="Archived "+UUID.randomUUID();var e=HistoryEvent.create(HistoryEvent.Kind.COLLAPSE,2,48000,level.dimension().location().toString(),id.toString(),List.of(new HistoryEvent.Realm(id,name,"millenaire:seljuk","RELIGIOUS")),List.of(),null,"","CAPITAL_CONQUERED","FALLEN",Map.of());HistoryHooks.store(level).append(e);
        var player=new Stage5Support.TestPlayer(level,"Archive reader");var view=ChronicleViews.build(player,request("REALMS",null,"",0,Long.MAX_VALUE,null,name));var entries=view.getList("realms",10);
        h.assertTrue(entries.size()==1 && entries.getCompound(0).getUUID("id").equals(id) && entries.getCompound(0).getBoolean("fallen"),"Destroyed political entities remain selectable by their actual historical display name");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void boundedViewCodecDefendsSnapshotsAndRejectsOversizedMessages(GameTestHelper h){
        var tag=new CompoundTag();tag.putUUID("session",UUID.randomUUID());tag.putLong("serial",1);tag.putString("mode","CHRONICLE");var payload=new ChronicleViewPayload(tag);tag.putString("mode","MUTATED");
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());try{ChronicleViewPayload.CODEC.encode(buf,payload);h.assertTrue(ChronicleViewPayload.CODEC.decode(buf).view().getString("mode").equals("CHRONICLE"),"Snapshot has no aliases and wire retains request correlation");}finally{buf.release();}
        var huge=payload.view();huge.putString("extra","x".repeat(ChronicleViewPayload.MAX_BYTES));HistoryGameTests.refused(h,()->new ChronicleViewPayload(huge));h.succeed();
    }
}
