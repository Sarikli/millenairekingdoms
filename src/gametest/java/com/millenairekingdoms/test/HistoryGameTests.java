package com.millenairekingdoms.test;

import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Facts/persistence tests; controlled political calls are not described as autonomous AI. */
@PrefixGameTestTemplate(false)
public final class HistoryGameTests {
    private static final String NS="millenairekingdoms_history_tests";
    static HistoryEvent sample(UUID realm,int n){return HistoryEvent.create(HistoryEvent.Kind.WAR,n/10,n,"minecraft:overworld","test/"+n,List.of(new HistoryEvent.Realm(realm,"Realm "+realm,"millenaire:norman","MILITARY")),List.of(),null,"","","ACTIVE",Map.of());}
    static Path directory(GameTestHelper h){return h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("history-fixture-"+UUID.randomUUID());}
    static void refused(GameTestHelper h,Runnable action){boolean failed=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException e){failed=true;}h.assertTrue(failed,"Invalid history must be refused");}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void strictImmutableFactsRoundTripAndProtectUnknownFields(GameTestHelper h){
        var e=sample(UUID.randomUUID(),1);h.assertTrue(e.equals(HistoryEvent.load(e.save())),"All factual identity/context roundtrips");
        var tag=e.save();tag.putInt("future",1);refused(h,()->HistoryEvent.load(tag));tag.remove("future");tag.putString("day","1");refused(h,()->HistoryEvent.load(tag));
        var bad=e.save();bad.putUUID("id",UUID.randomUUID());refused(h,()->HistoryEvent.load(bad));
        h.assertTrue(!HistoryEvent.identity("minecraft:the_nether",e.kind(),e.source()).equals(e.id()),"Dimensions do not collide");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void realFileAppendIsIdempotentAndConflictingIdentityCannotRewriteFacts(GameTestHelper h)throws Exception{
        var dir=directory(h);var store=new HistoryStore(dir);var e=sample(UUID.randomUUID(),1);h.assertTrue(store.append(e) && !store.append(e) && store.size()==1,"Exact operation retries never duplicate");
        var changed=HistoryEvent.create(e.kind(),e.day(),e.gameTime(),e.dimension(),e.source(),e.realms(),e.places(),null,"","different","ACTIVE",Map.of());refused(h,()->store.append(changed));
        h.assertTrue(new HistoryStore(dir).get(e.id()).equals(e),"Independent reader sees original committed facts");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void futureArchivePreservesBytesAndRefusesNewWrites(GameTestHelper h)throws Exception{
        var dir=directory(h);var store=new HistoryStore(dir);store.append(sample(UUID.randomUUID(),1));var file=dir.resolve("schema.dat");var future=HistoryFiles.read(file);future.putInt("format",99);HistoryFiles.write(file,future);var bytes=Files.readAllBytes(file);
        refused(h,()->new HistoryStore(dir));refused(h,()->store.append(sample(UUID.randomUUID(),2)));h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(file)),"Future archive never reset/reencoded");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=2400)
    public static void indexedPagesFilterKingdomTypeDayAndKeepStableCursor(GameTestHelper h){
        var store=new HistoryStore(directory(h));var a=UUID.randomUUID();var b=UUID.randomUUID();for(int n=0;n<120;n++)store.append(sample(n%2==0?a:b,n));
        var first=store.query(new HistoryStore.Query(a,HistoryEvent.Kind.WAR,2,7,null,7));h.assertTrue(first.events().size()==7 && first.next()!=null && first.examined()==8,"Small page uses bounded indexed records");
        var second=store.query(new HistoryStore.Query(a,HistoryEvent.Kind.WAR,2,7,first.next(),7));var ids=new HashSet<UUID>();first.events().forEach(e->ids.add(e.id()));h.assertTrue(second.events().stream().noneMatch(e->ids.contains(e.id())) && second.events().stream().allMatch(e->e.day()>=2 && e.day()<=7 && e.realms().getFirst().id().equals(a)),"No repeated IDs or filter leaks");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=2400)
    public static void successfulPoliticalTransitionsRecordedAndFailedOrRepeatedActionsAreAbsent(GameTestHelper h){
        var level=h.getLevel();var data=KingdomSavedData.get(level);var a=data.create("History A "+UUID.randomUUID(),UUID.randomUUID());var b=data.create("History B "+UUID.randomUUID(),UUID.randomUUID());var village=UUID.randomUUID();data.attach(a.id(),village);data.attach(a.id(),village);
        refused(h,()->data.attach(b.id(),village));var war=data.declareWar(a.id(),b.id(),level.getGameTime());data.peace(war.id());data.peace(war.id());var ruler=UUID.randomUUID();data.claimRule(a.id(),ruler,"Historical ruler",level.getGameTime());data.abdicate(a.id(),ruler);
        var page=HistoryHooks.store(level).query(new HistoryStore.Query(a.id(),null,0,Long.MAX_VALUE,null,24));var kinds=page.events().stream().map(HistoryEvent::kind).toList();
        h.assertTrue(page.events().size()==6 && Collections.frequency(kinds,HistoryEvent.Kind.INCORPORATION)==1 && Collections.frequency(kinds,HistoryEvent.Kind.PEACE)==1 && kinds.containsAll(List.of(HistoryEvent.Kind.FOUNDING,HistoryEvent.Kind.WAR,HistoryEvent.Kind.RULER_CLAIM,HistoryEvent.Kind.RULER_RELEASE)),"Only six actual committed transitions, no duplicate/failed action history");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=2400)
    public static void capitalConquestCollapseAndArchiveSurviveActualPoliticalReceipts(GameTestHelper h){
        var level=h.getLevel();var d=KingdomSavedData.get(level);var a=d.create("Victor "+UUID.randomUUID(),UUID.randomUUID());var b=d.create("Fallen "+UUID.randomUUID(),UUID.randomUUID());d.declareWar(a.id(),b.id(),level.getGameTime());
        for(int n=0;n<2;n++){var raid=d.begin(a.capital(),b.capital(),level.getGameTime(),null);d.recordVictory(raid);d.finish(raid,KingdomSavedData.Outcome.LOST);}
        var page=HistoryHooks.store(level).query(new HistoryStore.Query(b.id(),null,0,Long.MAX_VALUE,null,24));
        h.assertTrue(d.kingdom(b.id()).fallen() && page.events().stream().anyMatch(e->e.kind()==HistoryEvent.Kind.CAPITAL_CONQUEST) && page.events().stream().anyMatch(e->e.kind()==HistoryEvent.Kind.COLLAPSE) && HistoryHooks.store(level).archives().stream().anyMatch(r->r.id().equals(b.id()) && r.name().equals(b.name())),"Genuine two-victory political receipts preserve fallen name and separate conquest/collapse facts");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=2400)
    public static void oldPoliticalSnapshotsNeverCreateFictitiousFoundingDates(GameTestHelper h){
        var detached=new KingdomSavedData();var old=detached.create("Old undisclosed "+UUID.randomUUID(),UUID.randomUUID());var level=h.getLevel();var before=HistoryHooks.store(level).size();var restored=KingdomSavedData.load(detached.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        h.assertTrue(restored.kingdom(old.id())!=null && HistoryHooks.store(level).size()==before && HistoryHooks.store(level).query(new HistoryStore.Query(old.id(),null,0,Long.MAX_VALUE,null,24)).events().isEmpty(),"Reading old6 data does not backfill events or invent old founding date");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",batch="history_restart",timeoutTicks=2400)
    public static void separateJvmRestoresActualHistoricalNamesAndNoRetryDuplicates(GameTestHelper h)throws Exception{
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("history-restart.dat");
        if(System.getProperty("kingdoms.tests.persistence","write").equals("read")){
            var saved=HistoryFiles.read(marker);var id=saved.getUUID("kingdom");var page=HistoryHooks.store(level).query(new HistoryStore.Query(id,null,0,Long.MAX_VALUE,null,24));
            h.assertTrue(page.events().size()==2 && page.events().stream().anyMatch(e->e.id().equals(saved.getUUID("founding")) && e.realms().getFirst().name().equals(saved.getString("name"))),"Actual original commit identities and display names reload without repair");HistoryHooks.recover(level);h.assertTrue(HistoryHooks.store(level).query(new HistoryStore.Query(id,null,0,Long.MAX_VALUE,null,24)).events().size()==2,"Recovery never replays historical transitions");h.succeed();return;
        }
        var data=KingdomSavedData.get(level);var kingdom=data.create("Persistent history "+UUID.randomUUID(),UUID.randomUUID());data.attach(kingdom.id(),UUID.randomUUID());var events=HistoryHooks.store(level).query(new HistoryStore.Query(kingdom.id(),null,0,Long.MAX_VALUE,null,24)).events();
        h.assertTrue(events.size()==2,"Two actual committed political events before restart");var saved=new CompoundTag();saved.putUUID("kingdom",kingdom.id());saved.putString("name",kingdom.name());saved.putUUID("founding",events.stream().filter(e->e.kind()==HistoryEvent.Kind.FOUNDING).findFirst().orElseThrow().id());HistoryFiles.write(marker,saved);h.succeed();
    }
}
