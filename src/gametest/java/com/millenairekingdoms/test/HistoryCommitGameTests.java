package com.millenairekingdoms.test;

import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.util.UUID;
import java.util.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import com.millenairekingdoms.construction.*;
import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.shipping.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.millenaire.item.ModItems;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class HistoryCommitGameTests {
    private static final String NS="millenairekingdoms_history_tests";
    @GameTest(templateNamespace="millenairekingdoms_history_tests",template="empty",timeoutTicks=1200)
    public static void failedCompositeFoundingDoesNotPersistAnIntermediateKingdomOrHistoricalSuccess(GameTestHelper h){
        var level=h.getLevel();var data=KingdomSavedData.get(level);var before=data.save(new CompoundTag(),level.registryAccess());int recorded=HistoryHooks.store(level).size();
        HistoryGameTests.refused(h,()->data.found("Refused founding "+UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"",level.getGameTime()));
        h.assertTrue(before.equals(data.save(new CompoundTag(),level.registryAccess())),"Failed final ruler validation preserves the original live political snapshot");
        h.assertTrue(HistoryHooks.store(level).size()==recorded,"A failed composite founding must not archive or persist its intermediate kingdom as a successful founding");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void oldFormatSixStaysCompatibleUntilANewLiveHistoricalCommit(GameTestHelper h){
        var level=h.getLevel();var old=new KingdomSavedData();old.create("Legacy "+UUID.randomUUID(),UUID.randomUUID());var tag=old.save(new CompoundTag(),level.registryAccess());var read=KingdomSavedData.load(tag,level.registryAccess());
        h.assertTrue(tag.getInt("format")==6 && !tag.contains("historyCommit") && tag.equals(read.save(new CompoundTag(),level.registryAccess())) && !read.isDirty(),"Reading a6 world does not invent a marker/event or eagerly rewrite its compatible fields");
        var disguised=tag.copy();disguised.putUUID("historyCommit",UUID.randomUUID());HistoryGameTests.refused(h,()->KingdomSavedData.load(disguised,level.registryAccess()));var malformed=tag.copy();malformed.putInt("format",7);HistoryGameTests.refused(h,()->KingdomSavedData.load(malformed,level.registryAccess()));h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void sameTickRulerCyclesHaveDistinctCausalCommitsAndNeverDuplicateOnRecovery(GameTestHelper h){
        var level=h.getLevel();var d=KingdomSavedData.get(level);var k=d.create("Authority cycles "+UUID.randomUUID(),UUID.randomUUID());var player=UUID.randomUUID();long tick=level.getGameTime();d.claimRule(k.id(),player,"Same ruler",tick);var first=d.save(new CompoundTag(),level.registryAccess()).getUUID("historyCommit");d.abdicate(k.id(),player);d.claimRule(k.id(),player,"Same ruler",tick);var second=d.save(new CompoundTag(),level.registryAccess()).getUUID("historyCommit");
        var query=new HistoryStore.Query(k.id(),HistoryEvent.Kind.RULER_CLAIM,0,Long.MAX_VALUE,null,24);h.assertTrue(!first.equals(second) && HistoryHooks.store(level).query(query).events().size()==2,"Two actual same-tick tenures are separate political commits");HistoryHooks.recover(level);HistoryHooks.recover(level);h.assertTrue(HistoryHooks.store(level).query(query).events().size()==2,"Retried recovery cannot create additional authority transitions");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void anUncommittedCandidateCannotBorrowALaterIdenticalRulerState(GameTestHelper h){
        var level=h.getLevel();var d=KingdomSavedData.get(level);var k=d.create("No borrowed commit "+UUID.randomUUID(),UUID.randomUUID());var before=d.save(new CompoundTag(),level.registryAccess());var detached=KingdomSavedData.load(before,level.registryAccess());var player=UUID.randomUUID();detached.claimRule(k.id(),player,"Ruler",level.getGameTime());var proposed=detached.save(new CompoundTag(),level.registryAccess());var staleCommit=UUID.randomUUID();proposed.putUUID("historyCommit",staleCommit);proposed.putInt("format",7);
        var pending=HistoryHooks.stagePolitical(level,before,proposed);h.assertTrue(pending!=null,"The uncommitted hypothetical candidate was staged only");var staleEvent=HistoryEvent.load(HistoryFiles.read(pending).getList("events",10).getCompound(0));d.claimRule(k.id(),player,"Ruler",level.getGameTime());HistoryHooks.recover(level);
        h.assertTrue(!HistoryHooks.store(level).contains(staleEvent.id()) && Files.exists(pending) && HistoryHooks.store(level).query(new HistoryStore.Query(k.id(),HistoryEvent.Kind.RULER_CLAIM,0,Long.MAX_VALUE,null,24)).events().size()==1,"Exactly one real claim; an equal ruler/time tuple does not authenticate an older failed operation");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void confirmedPoliticalBacklogSurvivesLaterStateChangesAndArchiveLock(GameTestHelper h)throws Exception{
        var level=h.getLevel();var d=KingdomSavedData.get(level);var k=d.create("Locked annals "+UUID.randomUUID(),UUID.randomUUID());var player=UUID.randomUUID();var lockFile=HistoryHooks.directory(level).resolve("archive.lock");
        try(var channel=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){d.claimRule(k.id(),player,"Ruler",level.getGameTime());d.abdicate(k.id(),player);}
        HistoryHooks.recover(level);var page=HistoryHooks.store(level).query(new HistoryStore.Query(k.id(),null,0,Long.MAX_VALUE,null,24));h.assertTrue(d.ruler(k.id())==null && page.events().size()==3 && page.events().stream().anyMatch(e->e.kind()==HistoryEvent.Kind.RULER_CLAIM) && page.events().stream().anyMatch(e->e.kind()==HistoryEvent.Kind.RULER_RELEASE),"A verified claim is not lost when later release supersedes the current marker while archive append is blocked");HistoryHooks.recover(level);h.assertTrue(HistoryHooks.store(level).query(new HistoryStore.Query(k.id(),null,0,Long.MAX_VALUE,null,24)).events().size()==3,"Backlog acknowledgements are idempotent");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void futurePendingDomainBytesArePreservedAndNeverBecomeHistory(GameTestHelper h)throws Exception{
        var file=HistoryHooks.directory(h.getLevel()).resolve("candidates").resolve(UUID.randomUUID()+".dat");var tag=new CompoundTag();tag.putInt("format",99);HistoryFiles.write(file,tag);var bytes=Files.readAllBytes(file);int count=HistoryHooks.store(h.getLevel()).size();
        try{HistoryGameTests.refused(h,()->HistoryDomain.confirm(h.getLevel(),file));h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(file)) && count==HistoryHooks.store(h.getLevel()).size(),"Unknown candidate preserved without fabricated success or migration");}finally{Files.delete(file);}h.succeed();
    }
    private static RoyalConstructionStore orders(net.minecraft.server.level.ServerLevel level){return new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"));}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unpaidDomainCandidateCannotBecomeAnAcceptedOrCompletedBuilding(GameTestHelper h)throws Exception{try(var f=RoyalPaymentGameTests.persistentFixture(h,"history-unpaid")){
        f.village().setActive(true);var choice=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst();var id=UUID.randomUUID();var intent=new RoyalConstructionStore.Intent(id,f.level().dimension().location().toString(),f.kingdom(),f.village().getId().uuid(),f.village().getId().uuid(),null,0,f.village().getVillageTypeId(),choice.planSet(),choice.plan(),choice.variant(),64,f.level().getGameTime(),f.village().getBuildings().stream().map(b->b.getId().uuid()).collect(java.util.stream.Collectors.toSet()));var order=orders(f.level()).reserve(intent);
        HistoryDomain.prepareConstruction(f.level(),order,false);HistoryDomain.finishConstruction(f.level(),id,false);var eventId=HistoryEvent.identity(intent.dimension(),HistoryEvent.Kind.CONSTRUCTION_ORDER,id.toString());h.assertTrue(!HistoryHooks.store(f.level()).contains(eventId) && f.village().getPendingProject()==null,"Staged event outcome does not prove native acceptance, payment or completion");new RoyalConstructionService(f.level()).cancelNpc(f.kingdom(),id);HistoryHooks.recover(f.level());h.assertTrue(!HistoryHooks.store(f.level()).contains(eventId),"Unpaid cancellation cannot turn a candidate into successful history");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualPaidNativeAcceptanceProducesOneOrderAndNoPrematureCompletion(GameTestHelper h)throws Exception{try(var f=RoyalPaymentGameTests.persistentFixture(h,"history-accepted")){
        f.village().setActive(true);f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));var option=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst();var id=UUID.randomUUID();var service=new RoyalConstructionService(f.level());var order=service.requestNpc(f.kingdom(),f.village().getId().uuid(),option.planSet(),id);var recorded=HistoryHooks.store(f.level()).get(HistoryEvent.identity(order.intent().dimension(),HistoryEvent.Kind.CONSTRUCTION_ORDER,id.toString()));service.requestNpc(f.kingdom(),f.village().getId().uuid(),option.planSet(),id);
        h.assertTrue(recorded!=null && recorded.outcome().equals("ACCEPTED") && f.value()==64 && f.village().getPendingProject()!=null && !HistoryHooks.store(f.level()).contains(HistoryEvent.identity(order.intent().dimension(),HistoryEvent.Kind.CONSTRUCTION_COMPLETE,id.toString())),"Real64 fee/durable original pending yields one accepted mandate, no fabricated finished building");h.assertTrue(recorded.equals(HistoryHooks.store(f.level()).get(recorded.id())),"Exact retry retains first event time/names/facts");service.cancelNpc(f.kingdom(),id);h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualGoodsDeliveryIsHistoricalOnlyAfterNativeCreditAndCommittedFee(GameTestHelper h)throws Exception{try(var r=RoyalShipmentSupport.realm(h,"history-cargo")){
        var id=UUID.randomUUID();var e=RoyalShipmentSupport.fastRequest(r,null,id);var eventId=HistoryEvent.identity(e.intent().dimension(),HistoryEvent.Kind.SHIPMENT,id.toString());for(int n=0;n<20 && e.stage()!=RoyalShipmentStore.Stage.IN_TRANSIT;n++)e=r.service().observe(id);
        h.assertTrue(e.stage()==RoyalShipmentStore.Stage.IN_TRANSIT && !HistoryHooks.store(r.level()).contains(eventId),"Departed cargo is not a successful delivery entry");var clock=(net.minecraft.world.level.storage.ServerLevelData)r.level().getLevelData();clock.setGameTime(Math.max(clock.getGameTime(),e.due()));for(int n=0;n<20 && !e.terminal();n++)e=r.service().observe(id);
        var event=HistoryHooks.store(r.level()).get(eventId);h.assertTrue(e.stage()==RoyalShipmentStore.Stage.DELIVERED && r.goods(r.source())==56 && r.goods(r.target())==44 && r.coins()==119 && event!=null && event.outcome().equals("DELIVERED"),"Real source56/target44/one9 fee/DELIVERED proof precedes significant shipment history");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=2400)
    public static void twoThousandEventArchiveServesAnIndexedSmallPageAfterColdReload(GameTestHelper h){
        var path=HistoryGameTests.directory(h);var store=new HistoryStore(path);var a=UUID.randomUUID();var b=UUID.randomUUID();for(int n=0;n<2000;n++)store.append(HistoryGameTests.sample(n%10==0?a:b,n));long start=System.nanoTime();var cold=new HistoryStore(path);long reload=System.nanoTime()-start;start=System.nanoTime();var page=cold.query(new HistoryStore.Query(a,HistoryEvent.Kind.WAR,100,150,null,24));long query=System.nanoTime()-start;
        h.assertTrue(cold.size()==2000 && page.events().size()==24 && page.examined()==25 && page.next()!=null,"Cold startup indices are reused; a24-row dated kingdom page examines25 metadata keys instead of reading2000 event bodies");System.out.println("HISTORY_PERF events=2000 reloadMicros="+reload/1000+" queryMicros="+query/1000+" examined="+page.examined());h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void futureMinecraftContainerCannotAuthenticateOrOverwritePoliticalHistory(GameTestHelper h)throws Exception{
        var file=HistoryGameTests.directory(h).resolve("future-political.dat");var root=new CompoundTag();root.putInt("DataVersion",Integer.MAX_VALUE);root.put("data",new KingdomSavedData().save(new CompoundTag(),h.getLevel().registryAccess()));HistoryFiles.write(file,root);var bytes=Files.readAllBytes(file);
        var read=HistoryHooks.class.getDeclaredMethod("readPolitical",Path.class);read.setAccessible(true);boolean refused=false;try{read.invoke(null,file);}catch(java.lang.reflect.InvocationTargetException e){refused=e.getCause() instanceof IllegalStateException;}
        h.assertTrue(refused && Arrays.equals(bytes,Files.readAllBytes(file)),"A newer Minecraft container remains untouched and cannot prove a history commit");h.succeed();
    }
}
