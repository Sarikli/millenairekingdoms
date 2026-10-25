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

/** Controlled source facts exercise view/navigation queries, not autonomous-world behavior. */
@PrefixGameTestTemplate(false)
public final class ChronicleIntegrationGameTests {
    private static final String NS="millenairekingdoms_history_tests";
    static ChronicleRequestPayload request(UUID scope,UUID village,UUID cursor,String mode,String search){return new ChronicleRequestPayload(UUID.randomUUID(),1,mode,Optional.ofNullable(scope),"",0,Long.MAX_VALUE,Optional.ofNullable(cursor),search,Optional.empty(),Optional.ofNullable(village));}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void villageLinkQueriesOnlyItsActualAssociatedEvents(GameTestHelper h){
        var level=h.getLevel();var realm=UUID.randomUUID();var first=UUID.randomUUID();var second=UUID.randomUUID();var r=new HistoryEvent.Realm(realm,"Linked realm","millenaire:norman","GENERAL");
        for(var place:List.of(first,second))HistoryHooks.store(level).append(HistoryEvent.create(HistoryEvent.Kind.INCORPORATION,7,168000,level.dimension().location().toString(),place.toString(),List.of(r),List.of(new HistoryEvent.Place(place,"Linked village",true,100,100)),null,"","","INCORPORATED",Map.of()));
        var view=ChronicleViews.build(new Stage5Support.TestPlayer(level,"Village reader"),request(realm,first,null,"CHRONICLE",""));var events=view.getList("events",10);
        h.assertTrue(events.size()==1 && HistoryEvent.load(events.getCompound(0)).places().getFirst().id().equals(first),"Atlas village link is an indexed server filter, not all kingdom events mislabeled as village history");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void eventAndVillageNavigationIdentitiesSurviveTheBoundedWireCodec(GameTestHelper h){
        var request=new ChronicleRequestPayload(UUID.randomUUID(),42,"ATLAS",Optional.of(UUID.randomUUID()),"",0,Long.MAX_VALUE,Optional.empty(),"",Optional.of(UUID.randomUUID()),Optional.of(UUID.randomUUID()));var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());try{ChronicleRequestPayload.CODEC.encode(buf,request);h.assertTrue(ChronicleRequestPayload.CODEC.decode(buf).equals(request),"Event/participant/village/session remain distinct, with no client actor/dimension field");}finally{buf.release();}h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void throttledNavigationGetsAnExplicitRetryWithoutScanningOrMutatingPolitics(GameTestHelper h){
        var level=h.getLevel();var player=new Stage5Support.TestPlayer(level,"Fast chronicle reader");var before=KingdomSavedData.get(level).save(new CompoundTag(),level.registryAccess());var request=request(null,null,null,"CHRONICLE","");ChronicleViews.forget(player.getUUID());
        var first=ChronicleViews.respond(player,request);var second=ChronicleViews.respond(player,request);
        h.assertTrue(first.contains("events") && second.getInt("retryAfter")==4 && !second.contains("events") && second.getUUID("session").equals(request.session()) && second.getLong("serial")==request.serial(),"Fast parent/child navigation can reschedule instead of waiting forever for a silently dropped response");
        h.assertTrue(KingdomSavedData.get(level).save(new CompoundTag(),level.registryAccess()).equals(before),"Read-only retry cannot issue political/economic work");ChronicleViews.forget(player.getUUID());h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void twoKingdomChroniclesShareOneWarIdentityWithDifferentFactualPerspectives(GameTestHelper h){
        var level=h.getLevel();var a=UUID.randomUUID();var b=UUID.randomUUID();var event=HistoryEvent.create(HistoryEvent.Kind.WAR,11,264000,level.dimension().location().toString(),UUID.randomUUID().toString(),List.of(new HistoryEvent.Realm(a,"West realm","millenaire:norman","MILITARY"),new HistoryEvent.Realm(b,"East realm","millenaire:seljuk","RELIGIOUS")),List.of(),null,"","","ACTIVE",Map.of());HistoryHooks.store(level).append(event);var player=new Stage5Support.TestPlayer(level,"Perspective reader");
        var left=HistoryEvent.load(ChronicleViews.build(player,request(a,null,null,"CHRONICLE","")).getList("events",10).getCompound(0));var right=HistoryEvent.load(ChronicleViews.build(player,request(b,null,null,"CHRONICLE","")).getList("events",10).getCompound(0));
        h.assertTrue(left.id().equals(right.id()) && left.realms().equals(right.realms()) && !ChronicleNarrator.render(left,a,"tr_tr").prose().equals(ChronicleNarrator.render(right,b,"tr_tr").prose()),"One actual source event supports two accounts without duplicating/reversing facts");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=2400)
    public static void archiveSearchPagesRemainUsableWithManyHistoricalRealms(GameTestHelper h){
        var level=h.getLevel();String prefix="Archives "+UUID.randomUUID()+" ";for(int n=0;n<75;n++){var id=UUID.randomUUID();HistoryHooks.store(level).append(HistoryEvent.create(HistoryEvent.Kind.COLLAPSE,4,96000,level.dimension().location().toString(),id.toString(),List.of(new HistoryEvent.Realm(id,prefix+String.format(java.util.Locale.ROOT,"%02d",n),"millenaire:norman","GENERAL")),List.of(),null,"","","FALLEN",Map.of()));}
        var player=new Stage5Support.TestPlayer(level,"Large archive reader");var first=ChronicleViews.build(player,request(null,null,null,"REALMS",prefix));var second=ChronicleViews.build(player,request(null,null,first.getUUID("next"),"REALMS",prefix));h.assertTrue(first.getList("realms",10).size()==64 && second.getList("realms",10).size()==11 && !second.hasUUID("next"),"Known archive catalog is searchable/paginated after dozens of collapsed realms");h.succeed();
    }
}
