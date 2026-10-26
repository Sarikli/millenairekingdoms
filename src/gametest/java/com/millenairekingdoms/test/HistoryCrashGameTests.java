package com.millenairekingdoms.test;

import com.millenairekingdoms.history.*;
import com.millenairekingdoms.construction.*;
import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;
import org.millenaire.village.*;

/** Actual abrupt JVM cuts over real native storage/service writes; middle halt70 runs intentionally cannot pass. */
@PrefixGameTestTemplate(false)
public final class HistoryCrashGameTests {
    private static String boundary(){String b=System.getProperty("kingdoms.tests.historyBoundary","");if(!Set.of("before-order","after-order","after-political").contains(b))throw new IllegalArgumentException("Unknown history crash boundary");return b;}
    private static Path marker(ServerLevel l){return l.getServer().getWorldPath(LevelResource.ROOT).resolve("history-cut.dat");}
    private static RoyalConstructionStore orders(ServerLevel l){return new RoyalConstructionStore(l.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"));}
    @GameTest(templateNamespace="millenairekingdoms_history_cut_prepare",template="empty",timeoutTicks=1200)
    public static void prepareActualNativeStorageAndPoliticalState(GameTestHelper h){
        var f=RoyalPaymentGameTests.persistentFixture(h,"history-cut-"+boundary());f.village().setActive(true);f.village().setNoProjectsLeftUntil(Long.MAX_VALUE);f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));var choice=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst();
        var t=new CompoundTag();t.putString("boundary",boundary());t.putUUID("kingdom",f.kingdom());t.putUUID("village",f.village().getId().uuid());t.putUUID("operation",UUID.randomUUID());t.putUUID("actor",UUID.randomUUID());t.putString("plan",choice.planSet().toString());
        f.village().setActive(false);VillageSavedData.get(f.level()).setDirty();new RoyalVillagePersistence().absentPending(f.level(),f.village().getId().uuid());new RoyalTreasuryPersistence().flush(f.level(),f.village().getTownhall().getInventory().getChestPositions());HistoryFiles.write(marker(f.level()),t);
        h.assertTrue(RoyalTreasurySnapshot.forNpc(f.level(),f.kingdom()).spendableValue()==128 && orders(f.level()).load(t.getUUID("operation"))==null,"Fresh original save/chests contain128 real coins and no issued order");System.out.println("HISTORY_CUT_PREPARED "+boundary());h.succeed();
    }
    @GameTest(templateNamespace="millenairekingdoms_history_cut_interrupt",template="empty",timeoutTicks=1200)
    public static void haltAfterTheActualSelectedServiceBoundary(GameTestHelper h)throws Exception{
        var level=h.getLevel();var t=read(level);var village=load(level,t);UUID id=t.getUUID("operation"),kingdom=t.getUUID("kingdom");var history=HistoryHooks.directory(level);
        if(boundary().equals("after-political")){
            try(var channel=FileChannel.open(history.resolve("archive.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){
                KingdomSavedData.get(level).claimRule(kingdom,t.getUUID("actor"),"History cut ruler",level.getGameTime());var political=KingdomSavedData.get(level).save(new CompoundTag(),level.registryAccess());var pending=history.resolve("pending").resolve(political.getUUID("historyCommit")+".dat");var candidate=HistoryFiles.read(pending);h.assertTrue(candidate.getBoolean("confirmed"),"Actual political file acknowledged before blocked history append");t.put("event",candidate.getList("events",10).getCompound(0).copy());HistoryFiles.write(marker(level),t);halt(h,t,128);return;
            }
        }
        if(boundary().equals("after-order")){
            try(var channel=FileChannel.open(history.resolve("archive.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){
                var order=new RoyalConstructionService(level).requestNpc(kingdom,village.getId().uuid(),ResourceLocation.parse(t.getString("plan")),id);h.assertTrue(order.stage()==RoyalConstructionStore.Stage.QUEUED && new RoyalPaymentService(level).inspectNpc(kingdom,id).state()==RoyalPaymentJournal.State.COMMITTED,"Actual native pending and committed64 fee precede blocked history append");saveCandidate(level,t,true);halt(h,t,64);return;
            }
        }
        final FileChannel[] held=new FileChannel[1];final FileLock[] lock=new FileLock[1];
        var persistence=new RoyalVillagePersistence(){@Override public void pending(ServerLevel l,UUID v,Village.PendingProject pending){super.pending(l,v,pending);try{held[0]=FileChannel.open(l.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction/.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);lock[0]=held[0].lock();}catch(java.io.IOException e){throw new IllegalStateException(e);}}};
        var constructor=RoyalConstructionService.class.getDeclaredConstructor(ServerLevel.class,RoyalVillagePersistence.class);constructor.setAccessible(true);boolean interrupted=false;try{constructor.newInstance(level,persistence).requestNpc(kingdom,village.getId().uuid(),ResourceLocation.parse(t.getString("plan")),id);}catch(IllegalStateException expected){interrupted=true;}
        if(lock[0]!=null)lock[0].close();if(held[0]!=null)held[0].close();
        h.assertTrue(interrupted && orders(level).load(id).stage()==RoyalConstructionStore.Stage.QUEUING && new RoyalPaymentService(level).inspectNpc(kingdom,id).state()==RoyalPaymentJournal.State.PAID,"Actual index lock interrupts after native persistence/candidate preparation but BEFORE durable domain acceptance");saveCandidate(level,t,false);halt(h,t,64);
    }
    private static void saveCandidate(ServerLevel level,CompoundTag t,boolean confirmed){var id=HistoryEvent.identity(level.dimension().location().toString(),HistoryEvent.Kind.CONSTRUCTION_ORDER,t.getUUID("operation").toString());var candidate=HistoryFiles.read(HistoryHooks.directory(level).resolve("candidates").resolve(id+".dat"));if(candidate.getBoolean("confirmed")!=confirmed)throw new IllegalStateException("Unexpected candidate confirmation");t.put("event",candidate.getList("events",10).getCompound(0).copy());HistoryFiles.write(marker(level),t);}
    private static void halt(GameTestHelper h,CompoundTag t,long coins){var level=h.getLevel();var e=HistoryEvent.load(t.getCompound("event"));h.assertTrue(!HistoryHooks.store(level).contains(e.id()),"The selected history append must still be absent before actual JVM halt");long actual=coins(level,t);h.assertTrue(actual==coins,"Actual physical capital coins at the cut: "+actual);System.out.println("HISTORY_CUT_HALT boundary="+boundary()+" event="+e.id()+" coins="+coins+" politicalFormat="+KingdomSavedData.get(level).save(new CompoundTag(),level.registryAccess()).getInt("format"));System.out.flush();System.err.flush();Runtime.getRuntime().halt(70);}
    @GameTest(templateNamespace="millenairekingdoms_history_cut_recover",template="empty",timeoutTicks=1200)
    public static void recoverOnlyConfirmedHistoryWithoutReplayingGameplay(GameTestHelper h){
        var level=h.getLevel();var t=read(level);load(level,t);var expected=HistoryEvent.load(t.getCompound("event"));HistoryHooks.recover(level);var actual=HistoryHooks.store(level).get(expected.id());
        if(boundary().equals("before-order")){h.assertTrue(actual==null && orders(level).load(t.getUUID("operation")).stage()==RoyalConstructionStore.Stage.RECOVERY_REQUIRED && new RoyalPaymentService(level).inspectNpc(t.getUUID("kingdom"),t.getUUID("operation")).state()==RoyalPaymentJournal.State.PAID && coins(level,t)==64,"Uncertain original publication retains paid64/quarantine and never fabricates accepted/completed history");}
        else{h.assertTrue(expected.equals(actual),"Separate JVM recovers the exact original confirmed event time/names/facts");long balance=boundary().equals("after-order")?64:128;h.assertTrue(coins(level,t)==balance,"History recovery never replays a domain fee or alters native money");if(boundary().equals("after-political"))h.assertTrue(KingdomSavedData.get(level).ruler(t.getUUID("kingdom")).player().equals(t.getUUID("actor")),"Durable political ruler/commit reopens intact");else h.assertTrue(new RoyalPaymentService(level).inspectNpc(t.getUUID("kingdom"),t.getUUID("operation")).state()==RoyalPaymentJournal.State.COMMITTED,"Accepted original fee remains committed exactly once");}
        int count=HistoryHooks.store(level).size();HistoryHooks.recover(level);h.assertTrue(HistoryHooks.store(level).size()==count,"A second recovery cannot duplicate a cut event");System.out.println("HISTORY_CUT_RECOVERED boundary="+boundary()+" event="+expected.id()+" recorded="+(actual!=null)+" coins="+coins(level,t));h.succeed();
    }
    private static CompoundTag read(ServerLevel level){var t=HistoryFiles.read(marker(level));if(!t.getString("boundary").equals(boundary()))throw new IllegalStateException("History cut fixture mismatch");return t;}
    private static Village load(ServerLevel level,CompoundTag t){var v=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(t.getUUID("village")));if(v==null || v.getTownhall().getInventory()==null)throw new IllegalStateException("Original native storage must rehydrate without repair");v.getTownhall().getInventory().loadChunks(level);level.getChunkAt(v.getCenter());v.setActive(true);v.setNoProjectsLeftUntil(Long.MAX_VALUE);return v;}
    private static long coins(ServerLevel level,CompoundTag t){var v=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(t.getUUID("village")));var stacks=new ArrayList<ItemStack>();for(var pos:v.getTownhall().getInventory().getChestPositions()){var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(pos);for(int n=0;n<27;n++)stacks.add(chest.getItem(n));}return RoyalCoinPlan.spendableValue(stacks);}
}
