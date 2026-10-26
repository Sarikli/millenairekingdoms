package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.RoyalNpcConfig;
import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore.*;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;
import org.millenaire.village.VillageSavedData;

/** Real strict files and first-fee policy provenance. Decision discovery/need-based NPC policy is a later consumer. */
@PrefixGameTestTemplate(false)
public final class RoyalNpcBudgetGameTests {
    private static final String NS="millenairekingdoms_royal_npc_budget_tests";
    private static ResourceLocation id(String value){return ResourceLocation.parse(value);}
    private static RoyalNpcDecisionStore store(GameTestHelper h){return new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(h.getLevel()));}
    private static RoyalNpcDecisionStore isolated(GameTestHelper h){return new RoyalNpcDecisionStore(h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("npc-store-tests/"+UUID.randomUUID()));}
    private static RoyalConstructionStore orders(GameTestHelper h){return new RoyalConstructionStore(h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"));}
    private static void funds(RoyalShipmentSupport.Realm r,int silver){r.chest(r.capital()).clearContent();r.chest(r.capital()).setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),silver));r.capital().getTownhall().getInventory().invalidateCache();}
    private static RoyalConstructionCatalog.Option choice(RoyalShipmentSupport.Realm r){return RoyalConstructionCatalog.forNpc(r.level(),r.kingdom(),r.from()).options().getFirst();}
    private static Decision approval(RoyalShipmentSupport.Realm r,UUID order,Choice choice,long reserve,int share){
        return new Decision(order,r.level().dimension().location().toString(),r.kingdom(),10,16,new Budget(r.capital().getId().uuid(),r.capital().getVillageTypeId(),reserve,share),choice,Outcome.ISSUING,"Budget primitive approval; no automatic need-based decision claimed");
    }
    private static Decision approval(RoyalShipmentSupport.Realm r,UUID order,ResourceLocation plan,long reserve,int share){return approval(r,order,new Choice(Kind.CONSTRUCTION,r.from(),null,plan,0,64,0,null),reserve,share);}
    private static void refused(GameTestHelper h,Runnable action){boolean failed=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){failed=true;}h.assertTrue(failed,"Unapproved/cooldown/unsupported request must refuse before new spending");}

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void strictImmutableAttemptsReserveCooldownBeforeIssueAndRetainUncertainResults(GameTestHelper h){
        var store=isolated(h);var k=UUID.randomUUID();var cap=UUID.randomUUID();var id=UUID.randomUUID();var b=new Budget(cap,id("millenaire:norman/militaire"),128,250);var c=new Choice(Kind.CONSTRUCTION,cap,null,id("millenaire:norman/guardhouse"),0,64,0,null);
        var d=new Decision(id,"minecraft:overworld",k,10,16,b,c,Outcome.ISSUING,"Before domain issue");store.begin(d);
        h.assertTrue(store.load(id).equals(d) && store.nextDay("minecraft:overworld",k)==16,"Exact durable authorization/cooldown exists before any caller issues a domain request");
        refused(h,()->store.begin(d));refused(h,()->store.begin(new Decision(UUID.randomUUID(),d.dimension(),k,15,21,b,c,Outcome.ISSUING,"Too soon")));
        var result=store.finish(id,Outcome.DEFERRED,"Caller acknowledgement interrupted");h.assertTrue(result.choice().equals(c) && result.budget().equals(b) && store.nextDay(d.dimension(),k)==16,"Outcome cannot erase selection/budget/cooldown or pretend domain completion");
        refused(h,()->store.finish(id,Outcome.ACCEPTED,"Rewrite result"));store.begin(new Decision(UUID.randomUUID(),d.dimension(),k,16,22,b,Choice.none(),Outcome.SKIPPED,"No real opportunity"));
        h.assertTrue(store.entries().size()==2 && store.nextDay(d.dimension(),k)==22,"An exact later cooldown permits one new attempt; old UUID stays a tombstone");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualFilesystemLockPreventsOverlappingAttemptAcknowledgement(GameTestHelper h) throws Exception {
        var path=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("npc-lock-tests/"+UUID.randomUUID());var store=new RoyalNpcDecisionStore(path);
        var d=new Decision(UUID.randomUUID(),"minecraft:overworld",UUID.randomUUID(),0,3,new Budget(UUID.randomUUID(),null,128,250),Choice.none(),Outcome.SKIPPED,"No action");
        try(var file=FileChannel.open(path.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=file.lock()){
            refused(h,()->store.begin(d));h.assertTrue(!Files.exists(path.resolve("decisions.dat")),"Real lock failure creates no acknowledged attempt/history");
        }store.begin(d);h.assertTrue(store.load(d.id()).equals(d),"Exact attempt persists after releasing actual OS lock");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void futureWrongTypedAndUnknownFieldsRemainByteIdentical(GameTestHelper h) throws Exception {
        var path=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("npc-future-tests/"+UUID.randomUUID());var store=new RoyalNpcDecisionStore(path);var file=path.resolve("decisions.dat");
        for(int mode=0;mode<3;mode++){
            var root=new CompoundTag();root.putInt("format",mode==0 ? 999 : 1);if(mode==1)root.putString("decisions","invalid");else root.put("decisions",new ListTag());if(mode==2)root.putString("future","preserve");NbtIo.writeCompressed(root,file);var bytes=Files.readAllBytes(file);
            refused(h,store::entries);refused(h,()->store.begin(new Decision(UUID.randomUUID(),"minecraft:overworld",UUID.randomUUID(),0,3,new Budget(UUID.randomUUID(),null,0,1000),Choice.none(),Outcome.SKIPPED,"Must not overwrite")));
            h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(file)),"Unknown/future/wrong-typed data is never normalized to empty history");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualApprovedNpcConstructionPaysOnceAndLaterStricterReserveDoesNotRevokeEarnedFee(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-budget-paid")){
            funds(r,4);var c=choice(r);var id=UUID.randomUUID();store(h).begin(approval(r,id,c.planSet(),128,250));var service=new RoyalConstructionService(r.level());var e=service.requestNpc(r.kingdom(),r.from(),c.planSet(),id);
            h.assertTrue(e.stage()==RoyalConstructionStore.Stage.QUEUED && r.coins()==192 && new RoyalPaymentService(r.level()).inspectNpc(r.kingdom(),id).state()==State.COMMITTED,"Real NPC fee64 from256 obeys quarter/reserve and precedes native pending acknowledgement");
            int old=RoyalNpcConfig.TREASURY_RESERVE.get();try{RoyalNpcConfig.TREASURY_RESERVE.set(256);service.retryNpc(r.kingdom(),id);h.assertTrue(r.coins()==192 && r.source().getPendingProject()!=null,"Existing paid/committed work is not recharged or guessed unpaid under a later higher reserve");}finally{RoyalNpcConfig.TREASURY_RESERVE.set(old);}
            store(h).finish(id,Outcome.ACCEPTED,"Actual original pending acknowledged");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void delayedFirstConstructionFeeRechecksCurrentActualReserveBeforeJournalAndNativeDebit(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-budget-delay")){
            funds(r,4);var c=choice(r);var id=UUID.randomUUID();store(h).begin(approval(r,id,c.planSet(),128,250));funds(r,2); // Known fixture withdrawal128 after approval.
            var service=new RoyalConstructionService(r.level());refused(h,()->service.requestNpc(r.kingdom(),r.from(),c.planSet(),id));
            h.assertTrue(orders(h).load(id).stage()==RoyalConstructionStore.Stage.REQUESTED && r.coins()==128 && r.source().getPendingProject()==null && new RoyalPaymentService(r.level()).inspectNpc(r.kingdom(),id)==null,"Original unpaid domain intent cannot debit below reserve or create financial proof during delayed recovery");
            funds(r,4);service.retryNpc(r.kingdom(),id);h.assertTrue(orders(h).load(id).stage()==RoyalConstructionStore.Stage.QUEUED && r.coins()==192,"Known fixture deposit128 permits the same bound first fee, with one expense and no new request ID");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void acceptedBudgetCannotBeWeakenedByLaterConfiguration(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-budget-frozen")){
            funds(r,8);var c=choice(r);var id=UUID.randomUUID();store(h).begin(approval(r,id,c.planSet(),200,250));funds(r,4);
            int reserve=RoyalNpcConfig.TREASURY_RESERVE.get(),share=RoyalNpcConfig.FEE_SHARE.get();
            try{RoyalNpcConfig.TREASURY_RESERVE.set(0);RoyalNpcConfig.FEE_SHARE.set(1000);var service=new RoyalConstructionService(r.level());refused(h,()->service.requestNpc(r.kingdom(),r.from(),c.planSet(),id));h.assertTrue(r.coins()==256 && new RoyalPaymentService(r.level()).inspectNpc(r.kingdom(),id)==null,"New weaker config cannot erase original200-denier budget;256-64 is insufficient");funds(r,8);service.retryNpc(r.kingdom(),id);h.assertTrue(r.coins()==448,"Actual512 permits exact64 under both retained and current limits");}
            finally{RoyalNpcConfig.TREASURY_RESERVE.set(reserve);RoyalNpcConfig.FEE_SHARE.set(share);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void firstFeeMustMatchTheExactBoundDomainSelectionAndCapitalType(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-budget-binding")){
            funds(r,4);var choices=RoyalConstructionCatalog.forNpc(r.level(),r.kingdom(),r.from()).options();h.assertTrue(choices.size()>1,"Actual native catalog has multiple legal choices");var id=UUID.randomUUID();store(h).begin(approval(r,id,choices.get(0).planSet(),128,250));
            refused(h,()->new RoyalConstructionService(r.level()).requestNpc(r.kingdom(),r.from(),choices.get(1).planSet(),id));
            h.assertTrue(r.coins()==256 && new RoyalPaymentService(r.level()).inspectNpc(r.kingdom(),id)==null && r.source().getPendingProject()==null,"Same ID cannot authorize another otherwise legal plan or native pending under its budget");
        }
        try(var r=RoyalShipmentSupport.realm(h,"npc-budget-capital-type")){
            funds(r,4);var c=choice(r);var id=UUID.randomUUID();var approved=approval(r,id,c.planSet(),128,250);
            store(h).begin(new Decision(id,approved.dimension(),approved.kingdom(),approved.day(),approved.nextDay(),
                    new Budget(approved.budget().capital(),id("millenaire:norman/militaire"),128,250),approved.choice(),Outcome.ISSUING,"Wrong original capital type"));
            refused(h,()->new RoyalConstructionService(r.level()).requestNpc(r.kingdom(),r.from(),c.planSet(),id));
            h.assertTrue(r.coins()==256 && new RoyalPaymentService(r.level()).inspectNpc(r.kingdom(),id)==null,"A mismatching original capital type cannot authorize even the exact current plan/fee");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualPreparedNativeDebitProofReconcilesAfterDisablingNewSpendingAndRaisingReserve(GameTestHelper h) throws Exception {
        try(var r=RoyalShipmentSupport.realm(h,"npc-budget-prepared")){
            funds(r,4);var c=choice(r);var id=UUID.randomUUID();store(h).begin(approval(r,id,c.planSet(),128,250));var directory=r.level().getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-payments");
            var held=new java.util.concurrent.atomic.AtomicReference<FileChannel>();
            try{
                var persistence=new com.millenairekingdoms.economy.RoyalTreasuryPersistence(){int flushes;
                    @Override public void flush(net.minecraft.server.level.ServerLevel level,java.util.List<net.minecraft.core.BlockPos> positions){super.flush(level,positions);
                        if(++flushes==2)try{var channel=FileChannel.open(directory.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);held.set(channel);channel.lock();}
                        catch(java.io.IOException failure){throw new IllegalStateException(failure);}
                    }
                };
                var payments=RoyalPaymentGameTests.service(r.level(),directory,persistence);
                var constructor=RoyalConstructionService.class.getDeclaredConstructor(net.minecraft.server.level.ServerLevel.class,com.millenairekingdoms.construction.RoyalVillagePersistence.class,RoyalPaymentService.class);constructor.setAccessible(true);
                var service=constructor.newInstance(r.level(),new com.millenairekingdoms.construction.RoyalVillagePersistence(),payments);
                refused(h,()->service.requestNpc(r.kingdom(),r.from(),c.planSet(),id));
            }finally{if(held.get()!=null)held.get().close();}
            h.assertTrue(r.payments().load(id).state()==State.PREPARED && r.coins()==192 && r.source().getPendingProject()==null,"Real native64 debit/proof survives an actual OS-lock failure before PAID acknowledgement");
            int old=RoyalNpcConfig.TREASURY_RESERVE.get();boolean enabled=RoyalNpcConfig.ECONOMIC_ENABLED.get();
            try{RoyalNpcConfig.TREASURY_RESERVE.set(256);RoyalNpcConfig.ECONOMIC_ENABLED.set(false);new RoyalConstructionService(r.level()).retryNpc(r.kingdom(),id);
                h.assertTrue(r.coins()==192 && r.payments().load(id).state()==State.COMMITTED && orders(h).load(id).stage()==RoyalConstructionStore.Stage.QUEUED,"Original applied proof reconciles without new expense/refund despite disabled new spending and current reserve256");
            }finally{RoyalNpcConfig.TREASURY_RESERVE.set(old);RoyalNpcConfig.ECONOMIC_ENABLED.set(enabled);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void bothFrozenAndCurrentFeeSharesApplyIndependentlyOfTheReserve(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-budget-share")){
            funds(r,4);var c=choice(r);var id=UUID.randomUUID();store(h).begin(approval(r,id,c.planSet(),0,250));funds(r,3);
            int reserve=RoyalNpcConfig.TREASURY_RESERVE.get(),share=RoyalNpcConfig.FEE_SHARE.get();var service=new RoyalConstructionService(r.level());
            try{
                RoyalNpcConfig.TREASURY_RESERVE.set(0);RoyalNpcConfig.FEE_SHARE.set(1000);refused(h,()->service.requestNpc(r.kingdom(),r.from(),c.planSet(),id));
                h.assertTrue(r.coins()==192 && r.payments().load(id)==null,"Frozen quarter share blocks64/192 despite weaker current100% and zero reserve");
                funds(r,4);RoyalNpcConfig.FEE_SHARE.set(200);refused(h,()->service.retryNpc(r.kingdom(),id));
                h.assertTrue(r.coins()==256 && r.payments().load(id)==null,"New stricter20% blocks64/256 even though frozen25% would permit it");
                funds(r,5);service.retryNpc(r.kingdom(),id);h.assertTrue(r.coins()==256 && r.payments().load(id).state()==State.COMMITTED,"Actual320 permits exact64 under both independent share limits; no prior debit or duplicate request");
            }finally{RoyalNpcConfig.TREASURY_RESERVE.set(reserve);RoyalNpcConfig.FEE_SHARE.set(share);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void localCapitalOwnershipChangeBlocksAnOtherwiseApprovedDelayedFirstNpcFee(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-budget-local-capital")){
            funds(r,4);var c=choice(r);var id=UUID.randomUUID();store(h).begin(approval(r,id,c.planSet(),128,250));
            r.capital().setOwner(UUID.randomUUID(),"Local player owner");refused(h,()->new RoyalConstructionService(r.level()).requestNpc(r.kingdom(),r.from(),c.planSet(),id));
            h.assertTrue(r.coins()==256 && r.payments().load(id)==null && r.source().getPendingProject()==null,
                    "Policy first-fee guard rechecks local native capital control even while political NPC rule and original type remain unchanged");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualShipmentFirstFeeUsesTheSameDelayedReserveGuard(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-budget-shipping")){
            funds(r,4);var product=id("minecraft:cherry_log");var q=r.service().quoteNpc(r.kingdom(),r.from(),r.to(),product,40);var id=UUID.randomUUID();
            // Synthetic need reference is explicit: this primitive tests bound transport budgeting, not need discovery.
            var c=new Choice(Kind.SHIPMENT,r.from(),r.to(),product,40,q.fee(),q.travelTicks(),UUID.randomUUID());store(h).begin(approval(r,id,c,128,250));funds(r,2);
            var e=r.service().requestNpc(r.kingdom(),r.from(),r.to(),product,40,id);h.assertTrue(e.stage()==RoyalShipmentStore.Stage.PREPARING && e.blocked()==RoyalShipmentStore.Blocked.PAYMENT && r.coins()==128 && r.goods(r.source())==96 && r.payments().load(id)==null,"Unpaid actual route cannot begin cargo or native fee below current reserve");
            funds(r,4);r.service().resumeNpc(r.kingdom(),id);h.assertTrue(r.store().load(id).stage()==RoyalShipmentStore.Stage.COLLECTING && r.coins()==247 && r.goods(r.source())==96 && r.payments().load(id).state()==State.PAID,"Actual deposit permits exact9 first fee and unchanged native goods before later collection");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unknownPolicyHistoryBlocksNewNpcFeesButDoesNotGrantOrRevokePlayerAuthority(GameTestHelper h) throws Exception {
        try(var r=RoyalShipmentSupport.realm(h,"npc-budget-unknown")){
            funds(r,4);var file=RoyalNpcDecisionStore.directory(h.getLevel()).resolve("decisions.dat");Files.createDirectories(file.getParent());byte[] old=Files.exists(file) ? Files.readAllBytes(file) : null;var future=new CompoundTag();future.putInt("format",999);future.put("decisions",new ListTag());
            try{NbtIo.writeCompressed(future,file);var bytes=Files.readAllBytes(file);var npcId=UUID.randomUUID();var payments=new RoyalPaymentService(r.level());refused(h,()->payments.payNpc(r.kingdom(),npcId,RoyalPaymentService.Purpose.ADMINISTRATIVE,npcId,64));h.assertTrue(r.coins()==256 && payments.inspectNpc(r.kingdom(),npcId)==null,"Unknown provenance cannot be treated as untracked new NPC spending");
                var player=r.ruler();var playerId=UUID.randomUUID();payments.payPlayer(player,playerId,RoyalPaymentService.Purpose.ADMINISTRATIVE,playerId,64);
                h.assertTrue(r.coins()==192 && Arrays.equals(bytes,Files.readAllBytes(file)),"Current original player spending remains authorized and future NPC history remains byte-identical");
            }finally{if(old==null)Files.delete(file);else Files.write(file,old);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",batch="npc_budget_restart",timeoutTicks=1200)
    public static void actualUnpaidIntentAndUnacknowledgedAttemptReopenBeforeTheirFirstBudgetedFee(GameTestHelper h) throws Exception {
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-npc-budget-restart.dat");
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024));try(var r=RoyalShipmentSupport.restore(h,tag,true)){
                var id=tag.getUUID("order");var d=store(h).load(id);h.assertTrue(d.outcome()==Outcome.ISSUING && store(h).nextDay(d.dimension(),r.kingdom())==16 && orders(h).load(id).stage()==RoyalConstructionStore.Stage.REQUESTED && r.coins()==128 && r.payments().load(id)==null,"Actual original unpaid intent, attempt UUID/budget/cooldown and native128 reopen without a guessed debit or acknowledgement");
                var service=new RoyalConstructionService(level);refused(h,()->service.retryNpc(r.kingdom(),id));funds(r,4);service.retryNpc(r.kingdom(),id);
                h.assertTrue(r.coins()==192 && orders(h).load(id).stage()==RoyalConstructionStore.Stage.QUEUED && r.payments().load(id).state()==State.COMMITTED && store(h).load(id).outcome()==Outcome.ISSUING,"New JVM domain recovery rechecks actual reserve before first64; uncertain policy result never becomes a replayed second proposal");
                System.out.println("ROYAL_NPC_BUDGET_RESTART_READ_PASS treasury=192 fee=64 nextDay=16 attempt=ISSUING");h.succeed();return;
            }
        }
        var r=RoyalShipmentSupport.realm(h,"npc-budget-restart");funds(r,4);var c=choice(r);var id=UUID.randomUUID();store(h).begin(approval(r,id,c.planSet(),128,250));funds(r,2);refused(h,()->new RoyalConstructionService(level).requestNpc(r.kingdom(),r.from(),c.planSet(),id));var tag=r.marker();tag.putUUID("order",id);NbtIo.writeCompressed(tag,marker);r.save();r.capital().setActive(false);r.source().setActive(false);r.target().setActive(false);VillageSavedData.get(level).setDirty();
        System.out.println("ROYAL_NPC_BUDGET_RESTART_WRITE_PASS treasury=128 noFee nextDay=16 attempt=ISSUING");h.succeed();
    }
    private RoyalNpcBudgetGameTests(){}
}
