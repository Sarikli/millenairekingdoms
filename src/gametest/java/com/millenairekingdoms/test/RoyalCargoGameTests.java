package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalCargoContainers;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import com.millenairekingdoms.shipping.RoyalShipmentStore.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingInventory;
import org.millenaire.village.VillageSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData;

/** Actual native chest/receipt/journal operations with synthetic route/mandate inputs. No fee, village-route authority,
 * runtime, due-date gameplay or abrupt-process guarantee is claimed by this low-level container gate. */
@PrefixGameTestTemplate(false)
public final class RoyalCargoGameTests {
    private static final String NS="millenairekingdoms_royal_shipment_tests",PROOFS="millenairekingdoms:royal_operations";
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualCollectionAndDeliveryConserveNativeCargoAndUnrelatedGoods(GameTestHelper h)throws Exception{
        try(var f=fixture(h,"cargo-transfer")){
            var source=f.source();var destination=f.destination();source.setItem(5,new ItemStack(Items.DIAMOND,3));destination.setItem(4,new ItemStack(Items.EMERALD,4));
            collect(f);h.assertTrue(f.sourceCount()==56 && f.entry().escrow()==40,"Actual source removal is acknowledged before40 enters escrow");ready(f);
            var delivery=plan(f,Role.DELIVER,f.to,40);f.store.prepare(f.id,delivery);settle(f,delivery,new RoyalTreasuryPersistence());f.store.finish(f.id);
            h.assertTrue(f.sourceCount()==56 && f.destinationCount()==44 && f.entry().escrow()==0 && f.entry().delivered()==40,"Native source56 +destination44 retains original100 goods");
            h.assertTrue(source.getItem(5).getCount()==3 && destination.getItem(4).getCount()==4,"Unrelated native goods/slots are untouched");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeDepositRestoringCollectionBeforeImageDoesNotRemoveCargoAgain(GameTestHelper h)throws Exception{
        try(var f=fixture(h,"cargo-collect-activity")){
            var op=plan(f,Role.COLLECT,f.from,40);f.store.prepare(f.id,op);interrupted(h,()->settle(f,op,lostAck()));
            h.assertTrue(f.sourceCount()==56 && f.entry().escrow()==0,"Physical source save alone does not acknowledge journal escrow");
            h.assertTrue(new BuildingInventory(List.of(f.from)).add(f.level(),Items.SPRUCE_LOG,40)==40,"Original native API deposits40 known extra input");flush(f,f.from);
            settle(f,op,new RoyalTreasuryPersistence());h.assertTrue(f.sourceCount()==96 && f.entry().escrow()==40,"Matching proof preserves deposit even when exact old before image returns");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeWithdrawalAfterDeliveryAckLossCannotDuplicateCredit(GameTestHelper h)throws Exception{
        try(var f=fixture(h,"cargo-deliver-activity")){
            collect(f);ready(f);var op=plan(f,Role.DELIVER,f.to,40);f.store.prepare(f.id,op);interrupted(h,()->settle(f,op,lostAck()));
            h.assertTrue(f.destinationCount()==44 && f.entry().escrow()==40 && f.entry().allocatable()==0,"Pending delivery keeps all40 reserved before journal acknowledgement");
            var recipient=withdraw(f,f.to);settle(f,op,new RoyalTreasuryPersistence());f.store.finish(f.id);
            h.assertTrue(f.sourceCount()+f.destinationCount()+count(f.level(),recipient)==100 && f.destinationCount()==4 && f.entry().delivered()==40,"Native recipient40 +source56 +destination4 stays100; delivery is never credited twice");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeWithdrawalAfterReturnAckLossCannotDuplicateReturn(GameTestHelper h)throws Exception{
        try(var f=fixture(h,"cargo-return-activity")){
            collect(f);f.store.returning(f.id);var op=plan(f,Role.RETURN,f.from,40);f.store.prepare(f.id,op);interrupted(h,()->settle(f,op,lostAck()));
            var recipient=withdraw(f,f.from);settle(f,op,new RoyalTreasuryPersistence());f.store.finish(f.id);
            h.assertTrue(f.sourceCount()==56 && f.sourceCount()+f.destinationCount()+count(f.level(),recipient)==100 && f.entry().returned()==40,"Applied return survives native withdrawal without producing another40");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unreceiptedAfterImageQuarantinesWithoutInventingEscrow(GameTestHelper h)throws Exception{
        try(var f=fixture(h,"cargo-missing-proof")){
            var op=plan(f,Role.COLLECT,f.from,40);f.store.prepare(f.id,op);interrupted(h,()->settle(f,op,lostAck()));f.source().getPersistentData().remove(PROOFS);
            var entry=settle(f,op,new RoyalTreasuryPersistence());h.assertTrue(entry.stage()==Stage.RECOVERY_REQUIRED && entry.escrow()==0 && f.sourceCount()==56 && entry.operations().getFirst().pending(),"Ambiguous physical evidence stays intact; no guessed escrow or further item mutation");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void replacementFutureProofAndWrongImageQuantityNeverMutateItems(GameTestHelper h)throws Exception{
        for(int mode=0;mode<3;mode++)try(var f=fixture(h,"cargo-invalid-"+mode)){
            var op=plan(f,Role.COLLECT,f.from,40);
            if(mode==2)op=new Operation(op.id(),op.role(),op.container(),op.position(),39,op.image(),Outcome.PREPARED);f.store.prepare(f.id,op);
            CompoundTag future=null;
            if(mode==0){f.level().setBlock(f.from,Blocks.AIR.defaultBlockState(),2);f.level().setBlock(f.from,Blocks.CHEST.defaultBlockState(),2);fund(f.source(),96);}
            if(mode==1){future=new CompoundTag();future.putInt("format",999);future.putString("future","preserve");f.source().getPersistentData().put(PROOFS,future);}
            var entry=settle(f,op,new RoyalTreasuryPersistence());h.assertTrue(entry.stage()==Stage.RECOVERY_REQUIRED && f.sourceCount()==96 && entry.escrow()==0,"Identity/format/native delta mismatch must stop before slot writes mode="+mode);
            if(future!=null)h.assertTrue(future.equals(f.source().getPersistentData().get(PROOFS)),"Unknown proof bytes survive quarantine");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void cancelledUnperformedCollectionAndDeliveryNeverFabricateReturnedGoods(GameTestHelper h)throws Exception{
        try(var f=fixture(h,"cargo-cancel-before")){
            var op=plan(f,Role.COLLECT,f.from,40);f.store.prepare(f.id,op);f.store.returning(f.id);
            var entry=RoyalCargoContainers.settle(f.level(),f.store,f.id,op.id(),new RoyalTreasuryPersistence(),()->{throw new AssertionError("Cancelled unapplied source must never request a new debit");});f.store.finish(f.id);
            h.assertTrue(f.sourceCount()==96 && entry.collected()==0 && entry.operations().getFirst().outcome()==Outcome.CANCELLED,"Unperformed source tombstone does not create40 fictitious return cargo");
        }
        try(var f=fixture(h,"cargo-cancel-delivery")){
            collect(f);ready(f);var op=plan(f,Role.DELIVER,f.to,40);f.store.prepare(f.id,op);f.store.returning(f.id);settle(f,op,new RoyalTreasuryPersistence());
            h.assertTrue(f.destinationCount()==4 && f.entry().allocatable()==40,"Cancelled unperformed delivery releases escrow without crediting destination");
            var returned=plan(f,Role.RETURN,f.from,40);f.store.prepare(f.id,returned);settle(f,returned,new RoyalTreasuryPersistence());f.store.finish(f.id);
            h.assertTrue(f.sourceCount()==96 && f.destinationCount()==4 && f.entry().returned()==40,"Only actually collected goods return to original source");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void durableAcknowledgementPreventsStaleReplayAfterCleanupFailure(GameTestHelper h)throws Exception{
        try(var f=fixture(h,"cargo-cleanup")){
            var op=plan(f,Role.COLLECT,f.from,40);f.store.prepare(f.id,op);
            var persistence=new RoyalTreasuryPersistence(){int count;@Override public void flush(ServerLevel l,List<BlockPos> p){if(++count==2)throw new IllegalStateException("Deliberate post-ack cleanup failure");super.flush(l,p);}};
            h.assertTrue(settle(f,op,persistence).escrow()==40 && f.sourceCount()==56,"Cleanup failure cannot undo acknowledged collection");
            new BuildingInventory(List.of(f.from)).add(f.level(),Items.SPRUCE_LOG,40);
            var entry=RoyalCargoContainers.settle(f.level(),f.store,f.id,op.id(),new RoyalTreasuryPersistence(),()->{throw new AssertionError("Stale replay must reload durable APPLIED state");});
            h.assertTrue(f.sourceCount()==96 && entry.escrow()==40,"Stale operation UUID cannot charge again after proof cleanup and before-image coincidence");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void fullAndUnloadedContainersDeferWithoutForcingOrPartialCredits(GameTestHelper h)throws Exception{
        try(var f=fixture(h,"cargo-unavailable")){
            collect(f);ready(f);for(int slot=0;slot<27;slot++)f.destination().setItem(slot,new ItemStack(Items.DIRT,64));
            interrupted(h,()->plan(f,Role.DELIVER,f.to,40));h.assertTrue(f.entry().escrow()==40 && f.entry().operations().size()==1,"Full target has no partial allocation/credit and retains cargo");
            var first=f.entry().operations().getFirst();var far=new BlockPos(8_000_000,64,8_000_000);var image=first.image();var before=image.get("before").copy();image.put("before",image.get("after").copy());image.put("after",before);var pending=new Operation(UUID.randomUUID(),Role.DELIVER,UUID.randomUUID(),far,40,image,Outcome.PREPARED);f.store.prepare(f.id,pending);
            var entry=settle(f,pending,new RoyalTreasuryPersistence());h.assertTrue(!f.level().isLoaded(far) && entry.stage()==Stage.AWAITING_DELIVERY && entry.blocked()==Blocked.UNLOADED && entry.escrow()==40 && entry.allocatable()==0,"Unavailable pending physical operation defers without chunk forcing, quarantine or releasing its reservation");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void newPhysicalDeltaRevalidatesAndHonorsSynchronousMandateRetirement(GameTestHelper h)throws Exception{
        try(var f=fixture(h,"cargo-revalidation")){
            var op=plan(f,Role.COLLECT,f.from,40);f.store.prepare(f.id,op);
            interrupted(h,()->RoyalCargoContainers.settle(f.level(),f.store,f.id,op.id(),new RoyalTreasuryPersistence(),()->{throw new IllegalStateException("Current native stock/mandate no longer permits removal");}));
            h.assertTrue(f.sourceCount()==96 && f.entry().escrow()==0 && f.entry().operations().getFirst().pending(),"Rejected current validation precedes the first native slot write");
            var entry=RoyalCargoContainers.settle(f.level(),f.store,f.id,op.id(),new RoyalTreasuryPersistence(),()->f.store.retire(i->true));
            h.assertTrue(f.sourceCount()==96 && entry.escrow()==0 && entry.cancellationRequested() && entry.operations().getFirst().outcome()==Outcome.CANCELLED,"A synchronous mandate retirement during revalidation cannot publish a stale new cargo debit");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeCollectionProofAndActivityReopenInSeparateJvm(GameTestHelper h)throws Exception{reopen(h,Role.COLLECT);}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeDeliveryProofAndActivityReopenInSeparateJvm(GameTestHelper h)throws Exception{reopen(h,Role.DELIVER);}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeReturnProofAndActivityReopenInSeparateJvm(GameTestHelper h)throws Exception{reopen(h,Role.RETURN);}

    private static void reopen(GameTestHelper h,Role role)throws Exception{
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("native-cargo-reopen-"+role+".dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());var store=new RoyalShipmentStore(Path.of(tag.getString("directory")));var id=tag.getUUID("id");var operation=tag.getUUID("operation");var entry=store.load(id);var village=VillageSavedData.get(level).getVillageManager().getVillage(new org.millenaire.village.VillageId(entry.intent().source().village()));
            h.assertTrue(village!=null && village.getTownhall().getInventory()!=null,"Original native village rehydrates without inventory repair");for(var pos:village.getTownhall().getInventory().getChestPositions())level.getChunkAt(pos);
            var from=BlockPos.of(tag.getLong("from"));var to=BlockPos.of(tag.getLong("to"));var recipient=BlockPos.of(tag.getLong("recipient"));if(role!=Role.COLLECT)level.getChunkAt(recipient);
            long before=count(level,from)+count(level,to)+(role==Role.COLLECT ? 0 : count(level,recipient));
            entry=RoyalCargoContainers.settle(level,store,id,operation,new RoyalTreasuryPersistence(),()->{throw new AssertionError("Saved applied proof must never run new-delta authorization");});
            h.assertTrue(entry.stage()!=Stage.RECOVERY_REQUIRED && before==100 && (role!=Role.COLLECT || before+entry.escrow()==140)
                    && count(level,from)+count(level,to)+(role==Role.COLLECT ? 0 : count(level,recipient))==before,"Actual native activity and pending proof reopen/reconcile without adding/removing goods role="+role);
            h.assertTrue(role==Role.COLLECT ? entry.escrow()==40 : entry.escrow()==0 && (role==Role.DELIVER ? entry.delivered()==40 : entry.returned()==40),"Only verified native proof changes escrow accounting");
            System.out.println("ROYAL_CARGO_NATIVE_REOPEN role="+role+" physicalTotal="+before+" escrow="+entry.escrow()+" delivered="+entry.delivered()+" returned="+entry.returned());h.succeed();return;
        }
        var f=fixture(h,"cargo-reopen-"+role);Operation op;
        if(role==Role.COLLECT){op=plan(f,role,f.from,40);f.store.prepare(f.id,op);interrupted(h,()->settle(f,op,lostAck()));new BuildingInventory(List.of(f.from)).add(level,Items.SPRUCE_LOG,40);flush(f,f.from);}
        else{collect(f);if(role==Role.DELIVER)ready(f);else f.store.returning(f.id);op=plan(f,role,role==Role.DELIVER ? f.to : f.from,40);f.store.prepare(f.id,op);interrupted(h,()->settle(f,op,lostAck()));}
        var recipient=role==Role.COLLECT ? f.from : withdraw(f,op.position());var tag=new CompoundTag();tag.putString("directory",f.directory.toString());tag.putUUID("id",f.id);tag.putUUID("operation",op.id());tag.putLong("from",f.from.asLong());tag.putLong("to",f.to.asLong());tag.putLong("recipient",recipient.asLong());NbtIo.writeCompressed(tag,marker);
        VillageSavedData.get(level).setDirty();KingdomSavedData.get(level).setDirty();h.succeed();
    }
    record Fixture(RoyalPaymentGameTests.Fixture nativeFixture,RoyalShipmentStore store,UUID id,BlockPos from,BlockPos to,Path directory)implements AutoCloseable{
        ServerLevel level(){return nativeFixture.level();}ChestBlockEntity source(){return (ChestBlockEntity)level().getBlockEntity(from);}ChestBlockEntity destination(){return (ChestBlockEntity)level().getBlockEntity(to);}
        int sourceCount(){return count(level(),from);}int destinationCount(){return count(level(),to);}Entry entry(){return store.load(id);}@Override public void close(){nativeFixture.close();}
    }
    static Fixture fixture(GameTestHelper h,String name){
        var nativeFixture=RoyalPaymentGameTests.persistentFixture(h,name);var from=nativeFixture.position();var to=nativeFixture.village().getTownhall().getInventory().getChestPositions().getLast();
        fund(nativeFixture.chest(),96);((ChestBlockEntity)h.getLevel().getBlockEntity(to)).setItem(0,new ItemStack(Items.SPRUCE_LOG,4));
        var source=new Endpoint(nativeFixture.village().getId().uuid(),nativeFixture.village().getVillageTypeId(),nativeFixture.village().getTownhall().getId().uuid(),nativeFixture.village().getCenter(),null);
        var destination=new Endpoint(UUID.randomUUID(),source.type(),UUID.randomUUID(),to,null);var id=UUID.randomUUID();var intent=new Intent(id,h.getLevel().dimension().location().toString(),nativeFixture.kingdom(),nativeFixture.village().getId().uuid(),null,0,source,destination,BuiltInRegistries.ITEM.getKey(Items.SPRUCE_LOG),40,7,100,1000);
        var directory=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("physical-cargo-tests").resolve(UUID.randomUUID().toString());var store=new RoyalShipmentStore(directory);store.reserve(intent);store.collecting(id);return new Fixture(nativeFixture,store,id,from,to,directory);
    }
    private static void fund(ChestBlockEntity chest,int amount){chest.setItem(0,new ItemStack(Items.SPRUCE_LOG,Math.min(64,amount)));if(amount>64)chest.setItem(1,new ItemStack(Items.SPRUCE_LOG,amount-64));}
    static Operation plan(Fixture f,Role role,BlockPos position,int amount){return RoyalCargoContainers.plan(f.level(),f.entry().intent(),role,position,amount,new RoyalTreasuryPersistence());}
    static Entry settle(Fixture f,Operation op,RoyalTreasuryPersistence persistence){return RoyalCargoContainers.settle(f.level(),f.store,f.id,op.id(),persistence,()->{});}
    private static void collect(Fixture f){var op=plan(f,Role.COLLECT,f.from,40);f.store.prepare(f.id,op);settle(f,op,new RoyalTreasuryPersistence());}
    private static void ready(Fixture f){f.store.dispatch(f.id,200);f.store.awaitDelivery(f.id,1200);}
    private static void flush(Fixture f,BlockPos position){new RoyalTreasuryPersistence().flush(f.level(),List.of(position));}
    private static BlockPos withdraw(Fixture f,BlockPos position){
        if(new BuildingInventory(List.of(position)).remove(f.level(),Items.SPRUCE_LOG,40)!=40)throw new IllegalStateException("Native withdrawal failed");var recipient=f.from.offset(0,0,64);f.level().getChunkAt(recipient);f.level().setBlock(recipient,Blocks.CHEST.defaultBlockState(),2);((ChestBlockEntity)f.level().getBlockEntity(recipient)).setItem(0,new ItemStack(Items.SPRUCE_LOG,40));new RoyalTreasuryPersistence().flush(f.level(),List.of(position,recipient));return recipient;
    }
    private static int count(ServerLevel level,BlockPos pos){var chest=(ChestBlockEntity)level.getBlockEntity(pos);int count=0;for(int slot=0;slot<27;slot++)if(chest.getItem(slot).is(Items.SPRUCE_LOG))count+=chest.getItem(slot).getCount();return count;}
    private static RoyalTreasuryPersistence lostAck(){return new RoyalTreasuryPersistence(){@Override public void flush(ServerLevel l,List<BlockPos> p){super.flush(l,p);throw new IllegalStateException("Lost acknowledgement after real native Items+proof save");}};}
    private static void interrupted(GameTestHelper h,Runnable action){boolean failed=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){failed=true;}h.assertTrue(failed,"Invalid or deliberately interrupted native operation must not report successful acknowledgement");}
}
