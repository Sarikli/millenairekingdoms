package com.millenairekingdoms.test;

import com.millenairekingdoms.shipping.RoyalShipmentStore;
import com.millenairekingdoms.shipping.RoyalShipmentStore.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real atomic filesystem/accounting tests. Acknowledgements here are synthetic ledger inputs, NOT physical shipment proof. */
@PrefixGameTestTemplate(false)
public final class RoyalShipmentGameTests {
    private static final java.util.concurrent.atomic.AtomicInteger NEXT=new java.util.concurrent.atomic.AtomicInteger();
    private static final String NS="millenairekingdoms_royal_shipment_tests";
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void immutableRequestReservationAndTerminalTombstone(GameTestHelper h)throws Exception{
        var store=store(h);var intent=intent();var entry=store.reserve(intent);
        h.assertTrue(entry.stage()==Stage.PREPARING && store.reserve(intent).equals(entry),"Exact request retry retains immutable intent and lifecycle");
        var conflict=new Intent(intent.id(),intent.dimension(),intent.kingdom(),intent.capital(),intent.actor(),intent.rulerSince(),intent.source(),intent.destination(),intent.product(),41,intent.fee(),intent.issued(),intent.travelTicks());
        refused(h,()->store.reserve(conflict));refused(h,()->store.reserve(withSource(intent(),intent.source())));
        store.returning(intent.id());store.finish(intent.id());h.assertTrue(store.reserve(intent).stage()==Stage.CANCELLED,"Old request cannot be revived after cancellation");
        h.assertTrue(store.reserve(withSource(intent(),intent.source())).stage()==Stage.PREPARING,"A different request may use a released source while old tombstone stays");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unacknowledgedCollectionIsNotSpendableEscrow(GameTestHelper h)throws Exception{
        var store=store(h);var intent=intent();store.reserve(intent);store.collecting(intent.id());var collect=operation(Role.COLLECT,40);
        var entry=store.prepare(intent.id(),collect);h.assertTrue(entry.escrow()==0 && entry.allocatable()==0,"Prepared source removal is not acknowledged cargo");
        refused(h,()->store.prepare(intent.id(),operation(Role.COLLECT,1)));refused(h,()->store.dispatch(intent.id(),100));refused(h,()->store.prepare(intent.id(),operation(Role.DELIVER,1)));
        entry=store.acknowledge(intent.id(),collect.id());h.assertTrue(entry.escrow()==40 && entry.allocatable()==40,"Only acknowledged removal enters escrow accounting");
        entry=store.dispatch(intent.id(),200);h.assertTrue(entry.due()==1200,"Arrival is based on dispatch time, not stale issue time");
        h.assertTrue(store.dispatch(intent.id(),900).due()==1200,"Dispatch replay cannot reset the shipment timer");refused(h,()->store.awaitDelivery(intent.id(),1199));
        h.assertTrue(store.awaitDelivery(intent.id(),1200).stage()==Stage.AWAITING_DELIVERY,"Only due shipments become deliverable");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void pendingDeliveryReservationCannotBeSpentTwiceDuringReturn(GameTestHelper h)throws Exception{
        var store=store(h);var intent=ready(store);var delivery=operation(Role.DELIVER,15);store.prepare(intent.id(),delivery);
        var entry=store.returning(intent.id());h.assertTrue(entry.escrow()==40 && entry.allocatable()==25,"Cancellation retains pending destination allocation");
        refused(h,()->store.prepare(intent.id(),operation(Role.RETURN,26)));refused(h,()->store.finish(intent.id()));
        store.acknowledge(intent.id(),delivery.id());var returned=operation(Role.RETURN,25);store.prepare(intent.id(),returned);store.acknowledge(intent.id(),returned.id());entry=store.finish(intent.id());
        h.assertTrue(entry.stage()==Stage.CANCELLED && entry.collected()==40 && entry.delivered()==15 && entry.returned()==25 && entry.escrow()==0,"Synthetic acknowledged application inputs stay separately accountable through cancellation");
        refused(h,()->store.cancelOperation(intent.id(),delivery.id()));h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unperformedOperationsReleaseOnlyAfterDurableCancellation(GameTestHelper h)throws Exception{
        var store=store(h);var intent=intent();store.reserve(intent);store.collecting(intent.id());var collect=operation(Role.COLLECT,40);store.prepare(intent.id(),collect);
        refused(h,()->store.cancelOperation(intent.id(),collect.id()));store.returning(intent.id());store.cancelOperation(intent.id(),collect.id());
        var entry=store.finish(intent.id());h.assertTrue(entry.escrow()==0 && entry.collected()==0 && entry.operations().getFirst().outcome()==Outcome.CANCELLED,"Unapplied source operation keeps its tombstone without inventing return cargo");
        refused(h,()->store.acknowledge(intent.id(),collect.id()));
        var other=ready(store);var delivery=operation(Role.DELIVER,40);store.prepare(other.id(),delivery);store.returning(other.id());store.cancelOperation(other.id(),delivery.id());
        h.assertTrue(store.load(other.id()).allocatable()==40,"Proved unapplied delivery releases its allocation for original-source return");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void physicalContainerAndOperationConflictsSurviveStoreReopen(GameTestHelper h)throws Exception{
        var store=store(h);var first=intent();var second=intent();store.reserve(first);store.reserve(second);store.collecting(first.id());store.collecting(second.id());var op=operation(Role.COLLECT,10);store.prepare(first.id(),op);
        var collision=new Operation(UUID.randomUUID(),Role.COLLECT,UUID.randomUUID(),op.position(),10,image(),Outcome.PREPARED);
        var samePosition=collision;refused(h,()->store.prepare(second.id(),samePosition));collision=new Operation(UUID.randomUUID(),Role.COLLECT,op.container(),op.position().offset(100,0,0),10,image(),Outcome.PREPARED);var alias=collision;refused(h,()->store.prepare(second.id(),alias));
        h.assertTrue(store.hasPendingContainer(first.dimension(),op.position()),"Exact physical pending guard is visible to other economic consumers");store.acknowledge(first.id(),op.id());
        h.assertTrue(!store.hasPendingContainer(first.dimension(),op.position()),"Acknowledged operation releases physical lock while immutable history remains");store.prepare(second.id(),new Operation(UUID.randomUUID(),Role.COLLECT,op.container(),op.position(),10,image(),Outcome.PREPARED));
        refused(h,()->store.prepare(second.id(),op));var different=new Operation(op.id(),op.role(),op.container(),op.position(),11,op.image(),Outcome.PREPARED);refused(h,()->store.prepare(first.id(),different));h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void futureCorruptAndUnknownShipmentDataPreserveOriginalBytes(GameTestHelper h)throws Exception{
        for(int mode=0;mode<8;mode++){
            var directory=directory(h);var store=new RoyalShipmentStore(directory);var intent=intent();store.reserve(intent);if(mode>=4 && mode!=5){store.collecting(intent.id());store.prepare(intent.id(),operation(Role.COLLECT,40));}var file=directory.resolve("shipments.dat");var tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());
            if(mode==0)tag.putInt("format",999);if(mode==1)tag.putString("unknown","keep");if(mode==2)tag.getList("shipments",10).getCompound(0).getCompound("intent").putLong("quantity",40);
            if(mode==3)tag.getList("shipments",10).getCompound(0).putString("stage","FUTURE_STAGE");
            if(mode==4)tag.getList("shipments",10).getCompound(0).getList("operations",10).getCompound(0).getCompound("image").putString("future","preserve");
            if(mode==5){var entry=tag.getList("shipments",10).getCompound(0);entry.putString("stage","IN_TRANSIT");entry.putLong("due",1200);}
            if(mode==6){var operations=tag.getList("shipments",10).getCompound(0).getList("operations",10);operations.add(operations.get(0).copy());}
            if(mode==7){var list=new ListTag();list.add(net.minecraft.nbt.StringTag.valueOf("bad operation type"));tag.getList("shipments",10).getCompound(0).put("operations",list);}
            NbtIo.writeCompressed(tag,file);var before=Files.readAllBytes(file);
            refused(h,()->new RoyalShipmentStore(directory).entries());refused(h,()->store.reserve(intent()));h.assertTrue(java.util.Arrays.equals(before,Files.readAllBytes(file)),"Unknown/corrupt shipment bytes remain untouched mode="+mode);
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void realFilesystemLockAndImmutableImagesProtectAccounting(GameTestHelper h)throws Exception{
        var directory=directory(h);var store=new RoyalShipmentStore(directory);var intent=intent();store.reserve(intent);store.collecting(intent.id());var image=image();var op=new Operation(UUID.randomUUID(),Role.COLLECT,UUID.randomUUID(),new BlockPos(20,64,20),40,image,Outcome.PREPARED);
        image.putInt("format",999);op.image().putInt("format",888);store.prepare(intent.id(),op);var file=directory.resolve("shipments.dat");var before=Files.readAllBytes(file);
        try(var channel=FileChannel.open(directory.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){
            refused(h,()->store.acknowledge(intent.id(),op.id()));h.assertTrue(java.util.Arrays.equals(before,Files.readAllBytes(file)),"Actual contending lock prevents journal acknowledgement");
        }
        var reopened=new RoyalShipmentStore(directory);reopened.load(intent.id()).operations().getFirst().image().putString("future","external edit");
        h.assertTrue(reopened.load(intent.id()).operations().getFirst().image().getInt("format")==1 && reopened.load(intent.id()).escrow()==0,"Caller mutations cannot alter stored immutable images/accounting");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void retiredAndQuarantinedMandatesCannotBecomeActiveAgain(GameTestHelper h)throws Exception{
        var store=store(h);var active=intent();store.reserve(active);store.collecting(active.id());var quarantined=intent();store.reserve(quarantined);store.quarantine(quarantined.id(),"Missing native operation proof");
        h.assertTrue(store.retire(i->true)==2,"Both active and quarantined original mandates retire durably before political changes");
        var first=store.load(active.id());var second=store.load(quarantined.id());h.assertTrue(first.stage()==Stage.RETURNING && first.cancellationRequested()
                && second.stage()==Stage.RECOVERY_REQUIRED && second.cancellationRequested(),"Quarantine retains cancellation provenance without resetting uncertain cargo");
        refused(h,()->store.collecting(active.id()));refused(h,()->store.collecting(quarantined.id()));h.assertTrue(store.retire(i->true)==0,"Retirement replay never revives a mandate");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void accountingAndPendingAllocationsPersistAcrossSeparateJvm(GameTestHelper h)throws Exception{
        var marker=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("shipment-ledger-reopen.dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());var store=new RoyalShipmentStore(Path.of(tag.getString("directory")));var id=tag.getUUID("id");var op=tag.getUUID("operation");var entry=store.load(id);
            h.assertTrue(entry.stage()==Stage.RETURNING && entry.due()==1200 && entry.escrow()==40 && entry.allocatable()==25,"Original immutable timer and unresolved destination reservation reopen intact");
            refused(h,()->store.prepare(id,operation(Role.RETURN,26)));store.acknowledge(id,op);var returned=operation(Role.RETURN,25);store.prepare(id,returned);store.acknowledge(id,returned.id());entry=store.finish(id);
            h.assertTrue(entry.collected()==40 && entry.delivered()==15 && entry.returned()==25 && entry.escrow()==0 && entry.stage()==Stage.CANCELLED,"Separate-JVM accounting conserves synthetic acknowledged quantities with permanent operation history");
            System.out.println("ROYAL_SHIPMENT_LEDGER_REOPEN collected=40 delivered=15 returned=25 escrow=0 (metadata-only)");h.succeed();return;
        }
        var directory=directory(h);var store=new RoyalShipmentStore(directory);var intent=ready(store);var delivery=operation(Role.DELIVER,15);store.prepare(intent.id(),delivery);store.returning(intent.id());
        var tag=new CompoundTag();tag.putString("directory",directory.toString());tag.putUUID("id",intent.id());tag.putUUID("operation",delivery.id());NbtIo.writeCompressed(tag,marker);h.succeed();
    }
    private static Intent ready(RoyalShipmentStore store){var intent=intent();store.reserve(intent);store.collecting(intent.id());var op=operation(Role.COLLECT,40);store.prepare(intent.id(),op);store.acknowledge(intent.id(),op.id());store.dispatch(intent.id(),200);store.awaitDelivery(intent.id(),1200);return intent;}
    private static Path directory(GameTestHelper h){return h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("shipment-ledger-tests").resolve(UUID.randomUUID().toString());}
    private static RoyalShipmentStore store(GameTestHelper h){return new RoyalShipmentStore(directory(h));}
    private static Intent intent(){return new Intent(UUID.randomUUID(),"minecraft:overworld",UUID.randomUUID(),UUID.randomUUID(),null,0,endpoint(),endpoint(),ResourceLocation.parse("minecraft:spruce_log"),40,7,100,1000);}
    private static Endpoint endpoint(){return new Endpoint(UUID.randomUUID(),ResourceLocation.parse("millenaire:norman/village"),UUID.randomUUID(),new BlockPos(0,64,0),null);}
    private static Intent withSource(Intent i,Endpoint source){return new Intent(i.id(),i.dimension(),i.kingdom(),i.capital(),i.actor(),i.rulerSince(),source,i.destination(),i.product(),i.quantity(),i.fee(),i.issued(),i.travelTicks());}
    private static Operation operation(Role role,int count){var id=UUID.randomUUID();return new Operation(id,role,UUID.randomUUID(),new BlockPos(NEXT.incrementAndGet()*16,64,0),count,image(),Outcome.PREPARED);}
    private static CompoundTag image(){var tag=new CompoundTag();tag.putInt("format",1);tag.put("state",NbtUtils.writeBlockState(Blocks.CHEST.defaultBlockState()));var before=new ListTag();var after=new ListTag();for(int i=0;i<27;i++){before.add(new CompoundTag());after.add(new CompoundTag());}tag.put("before",before);tag.put("after",after);return tag;}
    private static void refused(GameTestHelper h,Runnable action){boolean rejected=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"Invalid/unavailable ledger operation must fail without rewriting history");}
}
