package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.shipping.RoyalShipmentService;
import com.millenairekingdoms.shipping.RoyalShipmentStore.*;
import com.millenairekingdoms.test.RoyalShipmentSupport.Realm;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real member villages, native reserves/storage, actual logistics fees and server clock. Test-only fast transit config
 * reduces the accepted duration; no stage/item/clock edits. Separate live scheduler/UI/abrupt-domain gates follow. */
@PrefixGameTestTemplate(false)
public final class RoyalShipmentServiceGameTests {
    private static final String NS="millenairekingdoms_royal_shipment_tests";
    private static final ResourceLocation CHERRY=ResourceLocation.parse("minecraft:cherry_log");
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void realPaidTimedMemberShipmentConservesGoodsAndRejectsDuplicateIntent(GameTestHelper h){
        var r=RoyalShipmentSupport.realm(h,"Paid timed shipping");var id=UUID.randomUUID();var first=RoyalShipmentSupport.fastRequest(r,null,id);
        h.assertTrue(first.stage()==Stage.COLLECTING && r.coins()==119 && r.goods(r.source())==96,"Actual9 logistics deniers are paid before collecting real goods");
        var second=r.service().requestNpc(r.kingdom(),r.from(),r.to(),CHERRY,40,id);h.assertTrue(second.collected()==40 && r.coins()==119 && r.goods(r.source())==56,"Same request advances original work without charging another fee");
        refused(h,()->r.service().requestNpc(r.kingdom(),r.from(),r.to(),CHERRY,41,id));var dispatched=r.service().observe(id);
        h.assertTrue(dispatched.stage()==Stage.IN_TRANSIT && dispatched.due()>r.level().getGameTime() && r.goods(r.target())==4,"Durable distance-based arrival time prevents instant delivery");
        finish(h,r,id,()->{var result=r.store().load(id);h.assertTrue(result.delivered()==40 && result.escrow()==0 && r.goods(r.source())==56 && r.goods(r.target())==44 && r.coins()==119,"Exact goods and actual paid transport expense survive completed workflow");
            r.service().requestNpc(r.kingdom(),r.from(),r.to(),CHERRY,40,id);h.assertTrue(r.coins()==119 && r.goods(r.target())==44 && r.payments().load(id).state()==State.COMMITTED,"Terminal UUID replay never charges/delivers again");});
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void existingPaidPlayerShipmentContinuesWithOriginalOfflineUuid(GameTestHelper h){
        var r=RoyalShipmentSupport.realm(h,"Offline paid shipping");var player=r.ruler();var id=UUID.randomUUID();RoyalShipmentSupport.fastRequest(r,player,id);
        h.assertTrue(r.level().getServer().getPlayerList().getPlayer(player.getUUID())==null,"Original actor is absent from online player list during existing-work tracking");
        finish(h,r,id,()->h.assertTrue(r.coins()==119 && r.goods(r.target())==44 && r.store().load(id).intent().actor().equals(player.getUUID()),"Paid offline mandate uses original UUID/tenure/reputation without new NPC spending"));
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void cancellingBeforeCollectionRefundsOnlyTheActualPaidFee(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"Cancel before pickup")){
            var id=UUID.randomUUID();RoyalShipmentSupport.fastRequest(r,null,id);var result=r.service().cancelNpc(r.kingdom(),id);
            h.assertTrue(result.stage()==Stage.CANCELLED && r.coins()==128 && r.goods(r.source())==96 && r.goods(r.target())==4 && r.payments().load(id).state()==State.REFUNDED,"No pickup cancellation restores actual9 fee and never fabricates goods");
            r.service().cancelNpc(r.kingdom(),id);r.service().requestNpc(r.kingdom(),r.from(),r.to(),CHERRY,40,id);h.assertTrue(r.coins()==128 && r.goods(r.source())==96,"Cancelled UUID cannot restart or credit another refund");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void cancellingHeldGoodsBeforeDispatchReturnsGoodsThenRefundsFee(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"Cancel held cargo")){
            var id=UUID.randomUUID();RoyalShipmentSupport.fastRequest(r,null,id);r.service().observe(id);h.assertTrue(r.goods(r.source())==56 && r.store().load(id).escrow()==40,"Actual40 removal backs held cargo");
            r.service().cancelNpc(r.kingdom(),id);var result=r.service().observe(id);
            h.assertTrue(result.stage()==Stage.CANCELLED && result.returned()==40 && r.goods(r.source())==96 && r.goods(r.target())==4 && r.coins()==128,"Actual held goods return before the pre-dispatch9 refund closes cancellation");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void dispatchMakesOriginalFeeNonrefundableEvenBeforeItsCommitAck(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"Cancel departed cargo")){
            var id=UUID.randomUUID();RoyalShipmentSupport.fastRequest(r,null,id);r.service().observe(id);var dispatched=r.service().observe(id);
            h.assertTrue(dispatched.dispatched() && r.payments().load(id).state()==State.PAID,"Actual dispatch record precedes existing-fee commit boundary");
            r.service().cancelNpc(r.kingdom(),id);var result=r.service().observe(id);
            h.assertTrue(result.stage()==Stage.CANCELLED && result.returned()==40 && r.coins()==119 && r.goods(r.source())==96 && r.payments().load(id).state()==State.COMMITTED,"Returned departed cargo retains the original nonrefundable9 logistics expense");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void sameTickRetirementDuringInFlightFeeCannotReviveOrOrphanTheCharge(GameTestHelper h)throws Exception{
        try(var r=RoyalShipmentSupport.realm(h,"In flight fee retirement")){
            var id=UUID.randomUUID();var persistence=new RoyalTreasuryPersistence(){int count;@Override public void flush(ServerLevel level,List<BlockPos> positions){super.flush(level,positions);if(++count==1){var data=KingdomSavedData.get(level);data.claimRule(r.kingdom(),UUID.randomUUID(),"Temporary ruler",level.getGameTime());data.clearRuler(r.kingdom());}}};
            var money=RoyalPaymentGameTests.service(r.level(),r.level().getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-payments"),persistence);
            var constructor=RoyalShipmentService.class.getDeclaredConstructor(ServerLevel.class,RoyalTreasuryPersistence.class,RoyalPaymentService.class);constructor.setAccessible(true);var service=constructor.newInstance(r.level(),new RoyalTreasuryPersistence(),money);
            var result=RoyalShipmentSupport.fastRequest(r,null,id,service);
            h.assertTrue(result.stage()==Stage.CANCELLED && result.cancellationRequested() && r.coins()==128 && r.goods(r.source())==96
                    && r.payments().load(id).state()==State.REFUNDED,"A fee created after same-tick abdicate/reclaim retirement still refunds; old mandate never collects goods");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void currentMemberSurplusAndAgencyChecksPrecedeAnyFeeOrIntent(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"Shipping eligibility")){
            r.chest(r.source()).setItem(2,new ItemStack(Items.SPRUCE_LOG,64));r.chest(r.source()).setItem(3,new ItemStack(Items.SPRUCE_LOG,32));var id=UUID.randomUUID();
            refused(h,()->r.service().requestNpc(r.kingdom(),r.from(),r.to(),ResourceLocation.parse("minecraft:spruce_log"),40,id));
            refused(h,()->r.service().requestNpc(r.kingdom(),r.from(),UUID.randomUUID(),CHERRY,40,id));refused(h,()->r.service().requestNpc(r.kingdom(),r.from(),r.from(),CHERRY,40,id));
            h.assertTrue(r.store().load(id)==null && r.payments().load(id)==null && r.coins()==128 && r.goods(r.source())==96,"Native spruce target1028 and invalid route prevent all intent/fee mutations");
            var player=r.ruler();refused(h,()->r.service().requestNpc(r.kingdom(),r.from(),r.to(),CHERRY,40,id));h.assertTrue(r.coins()==128 && r.store().load(id)==null,"NPC cannot issue shipping for an actually player-ruled kingdom");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void fullTargetRetainsCargoAndPreservesCustomStacksUntilRealCapacityExists(GameTestHelper h){
        var r=RoyalShipmentSupport.realm(h,"Full shipping target");var id=UUID.randomUUID();var custom=r.chest(r.target()).getItem(0);custom.set(DataComponents.CUSTOM_NAME,Component.literal("Private target goods"));
        for(int slot=1;slot<27;slot++)r.chest(r.target()).setItem(slot,new ItemStack(Items.DIRT,64));for(var pos:r.target().getTownhall().getInventory().getChestPositions())if(!pos.equals(r.target().getTownhall().getInventory().getChestPositions().getFirst())){var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)r.level().getBlockEntity(pos);for(int slot=0;slot<27;slot++)chest.setItem(slot,new ItemStack(Items.DIRT,64));}
        RoyalShipmentSupport.fastRequest(r,null,id);r.service().observe(id);r.service().observe(id);r.service().observe(id);
        waitFor(h,r,id,()->r.store().load(id).blocked()==Blocked.FULL,()->{
            h.assertTrue(r.store().load(id).escrow()==40 && r.goods(r.source())==56 && r.goods(r.target())==4,"Full target never partially credits or loses held cargo");r.chest(r.target()).setItem(1,ItemStack.EMPTY);
            finish(h,r,id,()->h.assertTrue(r.goods(r.target())==44 && r.goods(r.source())==56 && r.chest(r.target()).getItem(0).has(DataComponents.CUSTOM_NAME),"Actual released slot receives40 while original custom4 remains untouched"));
        },0,true);
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unavailableOriginalCapitalDefersRefundAndCannotReviveRetiredMandate(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"Unavailable original refund")){
            var id=UUID.randomUUID();RoyalShipmentSupport.fastRequest(r,null,id);var data=KingdomSavedData.get(r.level());data.setActive(r.kingdom(),false);var blocked=r.service().observe(id);
            h.assertTrue(blocked.cancellationRequested() && blocked.blocked()==Blocked.PAYMENT && r.coins()==119 && r.goods(r.source())==96,"Retired fee remains a liability when original capital cannot accept refund");
            data.setActive(r.kingdom(),true);var cancelled=r.service().observe(id);h.assertTrue(cancelled.stage()==Stage.CANCELLED && r.coins()==128 && r.goods(r.source())==96,"Restoring eligibility settles old refund without reviving collection");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualPoliticalConquestRetiresDestinationBeforeAnyCredit(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"Conquered shipment target");var attacker=RoyalShipmentSupport.realm(h,"Shipping conqueror")){
            var id=UUID.randomUUID();RoyalShipmentSupport.fastRequest(r,null,id);r.service().observe(id);r.service().observe(id);r.service().observe(id);var data=KingdomSavedData.get(r.level());data.declareWar(attacker.kingdom(),r.kingdom(),1);
            for(int n=0;n<2;n++){var raid=data.begin(attacker.source().getId().uuid(),r.to(),n+1,null);data.recordVictory(raid);data.finish(raid,KingdomSavedData.Outcome.LOST);}
            h.assertTrue(attacker.kingdom().equals(data.owner(r.to())) && r.store().load(id).cancellationRequested(),"Existing two-win political conquest publishes only after shipment retirement (not a raid-AI test)");
            r.service().observe(id);var result=r.service().observe(id);h.assertTrue(result.stage()==Stage.CANCELLED && result.returned()==40 && r.goods(r.source())==96 && r.goods(r.target())==4 && r.coins()==119,"Departed goods return to original member source without crediting conquered target or refunding transport");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void partialDeliveryCancellationReturnsOnlyRemainingActualEscrow(GameTestHelper h){
        var r=RoyalShipmentSupport.realm(h,"Partial paid delivery");var id=UUID.randomUUID();var target=r.chest(r.target());target.getItem(0).set(DataComponents.CUSTOM_NAME,Component.literal("Private original4"));target.setItem(1,new ItemStack(Items.CHERRY_LOG,54));
        for(int slot=2;slot<27;slot++)target.setItem(slot,new ItemStack(Items.DIRT,64));for(var pos:r.target().getTownhall().getInventory().getChestPositions())if(!pos.equals(r.target().getTownhall().getInventory().getChestPositions().getFirst())){var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)r.level().getBlockEntity(pos);for(int slot=0;slot<27;slot++)chest.setItem(slot,new ItemStack(Items.DIRT,64));}
        RoyalShipmentSupport.fastRequest(r,null,id);r.service().observe(id);r.service().observe(id);r.service().observe(id);
        waitFor(h,r,id,()->r.store().load(id).delivered()==10,()->{
            h.assertTrue(r.store().load(id).escrow()==30 && r.goods(r.target())==68 && r.goods(r.source())==56,"Exactly10 enters actual partial target capacity;30 remains held");r.service().cancelNpc(r.kingdom(),id);var result=r.service().observe(id);
            h.assertTrue(result.stage()==Stage.CANCELLED && result.delivered()==10 && result.returned()==30 && result.escrow()==0 && r.goods(r.source())==86 && r.goods(r.target())==68 && r.coins()==119,"Only remaining30 returns:154 physical goods including known54 target input, one9 fee");r.close();h.succeed();
        },0,true);
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unsupportedShipmentIndexCannotPublishChangedRulership(GameTestHelper h)throws Exception{
        try(var r=RoyalShipmentSupport.realm(h,"Unknown shipping politics")){
            var id=UUID.randomUUID();RoyalShipmentSupport.fastRequest(r,null,id);var file=RoyalShipmentService.directory(r.level()).resolve("shipments.dat");var original=java.nio.file.Files.readAllBytes(file);var tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());tag.putInt("format",999);NbtIo.writeCompressed(tag,file);var future=java.nio.file.Files.readAllBytes(file);
            try{refused(h,()->KingdomSavedData.get(r.level()).claimRule(r.kingdom(),UUID.randomUUID(),"Rejected replacement",r.level().getGameTime()));
                h.assertTrue(KingdomSavedData.get(r.level()).ruler(r.kingdom())==null && java.util.Arrays.equals(future,java.nio.file.Files.readAllBytes(file)) && r.coins()==119 && r.goods(r.source())==96,"Prepublication retirement failure preserves old political state, future file and physical inventory");
            }finally{java.nio.file.Files.write(file,original);}
            h.assertTrue(r.service().cancelNpc(r.kingdom(),id).stage()==Stage.CANCELLED && r.coins()==128,"Restored known test data can settle its original real fee");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void paidOfflineTransitSurvivesSeparateJvmWithNativeVillageRehydration(GameTestHelper h)throws Exception{
        var marker=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("paid-shipment-reopen.dat");
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());var r=RoyalShipmentSupport.restore(h,tag,true);var id=tag.getUUID("id");h.assertTrue(r.store().load(id).stage()==Stage.IN_TRANSIT && r.coins()==119 && r.goods(r.source())==56 && r.goods(r.target())==4,"Actual paid source debit and in-transit original mandate rehydrate without repair");
            h.assertTrue(r.level().getServer().getPlayerList().getPlayer(r.store().load(id).intent().actor())==null,"Original paid player remains offline in the new JVM");
            finish(h,r,id,()->h.assertTrue(r.coins()==119 && r.goods(r.source())==56 && r.goods(r.target())==44 && r.payments().load(id).state()==State.COMMITTED,"Separate JVM completes same paid shipment without recharging or regenerating goods"));return;
        }
        var r=RoyalShipmentSupport.realm(h,"Persisted paid transit");var player=r.ruler();var id=UUID.randomUUID();RoyalShipmentSupport.fastRequest(r,player,id);r.service().observe(id);r.service().observe(id);r.service().observe(id);
        var tag=r.marker();tag.putUUID("id",id);r.save();NbtIo.writeCompressed(tag,marker);h.assertTrue(r.store().load(id).stage()==Stage.IN_TRANSIT && r.payments().load(id).state()==State.COMMITTED,"Write JVM saves real paid dispatch with original offline actor and retained goods escrow");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void savedUnloadedDestinationCanBeQuotedAndDefersCreditWithoutForcing(GameTestHelper h)throws Exception{
        var marker=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("unloaded-shipment-target.dat");
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());var r=RoyalShipmentSupport.restore(h,tag,false);h.assertTrue(!r.level().isLoaded(r.target().getCenter()),"Actual saved destination chunk begins unloaded in the replacement JVM");var id=UUID.randomUUID();RoyalShipmentSupport.fastRequest(r,null,id);
            h.assertTrue(!r.level().isLoaded(r.target().getCenter()),"Quote and real fee use saved destination identity without forcing its chunk");r.service().observe(id);r.service().observe(id);r.service().observe(id);
            waitFor(h,r,id,()->r.store().load(id).blocked()==Blocked.UNLOADED,()->{
                h.assertTrue(!r.level().isLoaded(r.target().getCenter()) && r.store().load(id).escrow()==40,"Due shipment retains cargo while actual target chunk stays unloaded");r.activate(true);
                finish(h,r,id,()->h.assertTrue(r.goods(r.target())==44 && r.goods(r.source())==56 && r.coins()==119,"Explicit fixture loading permits deferred original delivery with one real fee"));
            },0,false);return;
        }
        var r=RoyalShipmentSupport.realm(h,"Unloaded target metadata");var tag=r.marker();r.save();NbtIo.writeCompressed(tag,marker);h.succeed();
    }
    private static void finish(GameTestHelper h,Realm realm,UUID id,Runnable assertions){waitFor(h,realm,id,()->realm.store().load(id).terminal(),()->{try{assertions.run();}finally{realm.close();}h.succeed();},0,true);}
    private static void waitFor(GameTestHelper h,Realm realm,UUID id,java.util.function.BooleanSupplier condition,Runnable complete,int attempts,boolean loadTarget){
        if(attempts>200){realm.close();h.fail("Real shipment did not reach expected state: "+realm.store().load(id));return;}
        h.runAfterDelay(1,()->{realm.activate(loadTarget);var entry=realm.service().observe(id);if(entry.stage()==Stage.RECOVERY_REQUIRED){realm.close();h.fail("Unexpected shipping quarantine: "+entry.diagnosis());return;}if(condition.getAsBoolean())complete.run();else waitFor(h,realm,id,condition,complete,attempts+1,loadTarget);});
    }
    private static void refused(GameTestHelper h,Runnable action){boolean failed=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){failed=true;}h.assertTrue(failed,"Invalid shipping request must fail before an unauthorized fee or physical operation");}
}
