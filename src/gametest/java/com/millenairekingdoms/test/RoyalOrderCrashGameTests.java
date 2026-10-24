package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.construction.RoyalVillagePersistence;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Opt-in only: each probe uses prepare / real abrupt halt(70) / recovery in three JVMs and an isolated world.
 * Actual service/payment/original serializers run; no manual domain-state fabrication or fake NPC completion. */
@PrefixGameTestTemplate(false)
public final class RoyalOrderCrashGameTests {
    private static final List<String> BOUNDARIES=List.of("before-debit","debit-before-order","before-village","after-village","before-commit","cancel-before-refund","cancel-physical","cancel-receipt");

    @GameTest(templateNamespace="millenairekingdoms_order_crash_prepare",template="empty",timeoutTicks=1200)
    public static void prepareActualConstructionCrashFixture(GameTestHelper h) throws Exception {
        String boundary=boundary(); var f=RoyalPaymentGameTests.persistentFixture(h,"order-crash-"+boundary); f.village().setActive(true);
        f.village().setNoProjectsLeftUntil(Long.MAX_VALUE); f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));
        var choice=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst();
        var tag=new CompoundTag(); tag.putString("boundary",boundary); tag.putUUID("id",UUID.randomUUID()); tag.putUUID("kingdom",f.kingdom());
        tag.putUUID("village",f.village().getId().uuid()); tag.putString("planSet",choice.planSet().toString());
        f.village().setActive(false); VillageSavedData.get(f.level()).setDirty(); KingdomSavedData.get(f.level()).setDirty();
        new RoyalVillagePersistence().absentPending(f.level(),f.village().getId().uuid());
        new RoyalTreasuryPersistence().flush(f.level(),f.village().getTownhall().getInventory().getChestPositions()); NbtIo.writeCompressed(tag,marker(f.level()));
        h.assertTrue(new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),tag.getUUID("id"))==null
                && store(f.level()).load(tag.getUUID("id"))==null && RoyalTreasurySnapshot.forNpc(f.level(),f.kingdom()).spendableValue()==128,"Fresh actual original storage/political/village files contain 128 coins and no order/payment");
        System.out.println("ROYAL_ORDER_CRASH_PREPARED "+boundary); h.succeed();
    }

    @GameTest(templateNamespace="millenairekingdoms_order_crash_interrupt",template="empty",timeoutTicks=1200)
    public static void abruptlyInterruptActualConstructionService(GameTestHelper h) throws Exception {
        var level=h.getLevel(); var tag=read(level); String boundary=boundary(); var village=load(level,tag); village.setActive(true);
        UUID id=tag.getUUID("id"),kingdom=tag.getUUID("kingdom"); var plan=net.minecraft.resources.ResourceLocation.parse(tag.getString("planSet"));
        if(boundary.equals("before-debit") || boundary.equals("debit-before-order")) {
            var fault=new RoyalTreasuryPersistence() {
                int count;
                @Override public void flush(ServerLevel l,List<BlockPos> positions) {
                    super.flush(l,positions);
                    if(++count==(boundary.equals("before-debit") ? 1 : 2)) halt(l,tag,Stage.REQUESTED,boundary.equals("before-debit") ? null : State.PREPARED,boundary.equals("before-debit") ? 128 : 64);
                }
            };
            service(level,new RoyalVillagePersistence(),payment(level,fault)).requestNpc(kingdom,village.getId().uuid(),plan,id);
        } else if(boundary.equals("before-village") || boundary.equals("after-village")) {
            var fault=new RoyalVillagePersistence() {
                @Override public void pending(ServerLevel l,UUID village,Village.PendingProject pending) {
                    if(boundary.equals("after-village")) super.pending(l,village,pending);
                    halt(l,tag,Stage.QUEUING,State.PAID,64);
                }
            };
            service(level,fault,new RoyalPaymentService(level)).requestNpc(kingdom,village.getId().uuid(),plan,id);
        } else if(boundary.equals("before-commit")) {
            try(var lock=new HeldLock()) {
                var fault=new RoyalVillagePersistence() {
                    @Override public void pending(ServerLevel l,UUID village,Village.PendingProject pending) {
                        super.pending(l,village,pending); lock.acquire(payments(level).resolve(".lock"));
                    }
                };
                boolean interrupted=false;
                try { service(level,fault,new RoyalPaymentService(level)).requestNpc(kingdom,village.getId().uuid(),plan,id); }
                catch(IllegalStateException expected) { interrupted=true; }
                lock.close(); h.assertTrue(interrupted,"Actual payment journal lock must reject receipt acknowledgement after QUEUED");
                halt(level,tag,Stage.QUEUED,State.PAID,64);
            }
        } else {
            // The real request pauses at PAID after treasury persistence changes village availability, before publication.
            var pause=new RoyalTreasuryPersistence() {
                int count;
                @Override public void flush(ServerLevel l,List<BlockPos> positions) { super.flush(l,positions); if(++count==2) village.setActive(false); }
            };
            var order=service(level,new RoyalVillagePersistence(),payment(level,pause)).requestNpc(kingdom,village.getId().uuid(),plan,id);
            h.assertTrue(order.stage()==Stage.PAID && village.getPendingProject()==null,"Genuine paid but unaccepted request is ready for exact cancellation");
            if(boundary.equals("cancel-before-refund")) {
                try(var lock=new HeldLock()) {
                    lock.acquire(payments(level).resolve(".lock")); boolean interrupted=false;
                    try { new RoyalConstructionService(level).cancelNpc(kingdom,id); } catch(IllegalStateException expected) { interrupted=true; }
                    lock.close(); h.assertTrue(interrupted,"Actual receipt lock must fail after durable cancellation and before refund");
                    halt(level,tag,Stage.CANCELLING,State.PAID,64);
                }
            } else try(var lock=new HeldLock()) {
                var refund=new RoyalTreasuryPersistence() {
                    int count;
                    @Override public void flush(ServerLevel l,List<BlockPos> positions) {
                        super.flush(l,positions);
                        if(++count==2) {
                            if(boundary.equals("cancel-physical")) halt(l,tag,Stage.CANCELLING,State.REFUND_PREPARED,128);
                            else lock.acquire(orders(level).resolve(".lock"));
                        }
                    }
                };
                boolean interrupted=false;
                try { service(level,new RoyalVillagePersistence(),payment(level,refund)).cancelNpc(kingdom,id); } catch(IllegalStateException expected) { interrupted=true; }
                lock.close(); h.assertTrue(interrupted,"Actual order lock must reject terminal order acknowledgement after real REFUNDED receipt");
                halt(level,tag,Stage.CANCELLING,State.REFUNDED,128);
            }
        }
        h.fail("Actual abrupt domain boundary was not reached; a graceful run is not a passing interruption probe");
    }

    @GameTest(templateNamespace="millenairekingdoms_order_crash_recover",template="empty",timeoutTicks=1200)
    public static void recoverActualConstructionAfterAbruptJvmTermination(GameTestHelper h) throws Exception {
        var level=h.getLevel(); var tag=read(level); String boundary=boundary(); var village=load(level,tag); var id=tag.getUUID("id"); var kingdom=tag.getUUID("kingdom");
        var order=store(level).load(id); var receipt=new RoyalPaymentService(level).inspectNpc(kingdom,id); long before=RoyalTreasurySnapshot.forNpc(level,kingdom).spendableValue();
        if(boundary.equals("before-village") || boundary.equals("after-village")) {
            h.assertTrue(order.stage()==Stage.RECOVERY_REQUIRED && receipt!=null && receipt.state()==State.PAID && before==64
                    && (village.getPendingProject()!=null)==boundary.equals("after-village"),"Startup preserves exact independently persisted original pending/coin state and quarantines uncertain publication");
            var result=new RoyalConstructionService(level).retryNpc(kingdom,id);
            h.assertTrue(result.stage()==Stage.RECOVERY_REQUIRED && RoyalTreasurySnapshot.forNpc(level,kingdom).spendableValue()==64,"Quarantined replay cannot refund/reissue or claim successful fulfillment");
            System.out.println("ROYAL_ORDER_CRASH_RECOVERED "+boundary+" disposition=QUARANTINED diskBefore="+before+" final=64");
        } else if(boundary.startsWith("cancel-")) {
            h.assertTrue(order.stage()==Stage.CANCELLING && order.cancellationFrom()==Stage.PAID && receipt!=null
                    && receipt.state()==(boundary.equals("cancel-before-refund") ? State.PAID : boundary.equals("cancel-physical") ? State.REFUND_PREPARED : State.REFUNDED)
                    && before==(boundary.equals("cancel-before-refund") ? 64 : 128),"Separate JVM retains the genuine cancellation origin and exact physical/refund acknowledgement boundary");
            var result=new RoyalConstructionService(level).retryNpc(kingdom,id); new RoyalConstructionService(level).retryNpc(kingdom,id);
            h.assertTrue(result.stage()==Stage.CANCELLED && village.getPendingProject()==null && RoyalTreasurySnapshot.forNpc(level,kingdom).spendableValue()==128
                    && new RoyalPaymentService(level).inspectNpc(kingdom,id).state()==State.REFUNDED,"Actual cancellation rolls forward once without accidentally accepting work or duplicating a refund");
            System.out.println("ROYAL_ORDER_CRASH_RECOVERED "+boundary+" disposition=CANCELLED diskBefore="+before+" final=128");
        } else {
            h.assertTrue(order.stage()==(boundary.equals("before-commit") ? Stage.QUEUED : Stage.REQUESTED)
                    && before==(boundary.equals("before-debit") ? 128 : 64),"Domain/payment files retain the selected real abrupt boundary");
            village.setActive(true); var result=boundary.equals("before-commit") ? new RoyalConstructionService(level).observe(id) : new RoyalConstructionService(level).retryNpc(kingdom,id);
            new RoyalConstructionService(level).retryNpc(kingdom,id);
            h.assertTrue(result.stage()==Stage.QUEUED && village.getPendingProject()!=null && RoyalTreasurySnapshot.forNpc(level,kingdom).spendableValue()==64
                    && new RoyalPaymentService(level).inspectNpc(kingdom,id).state()==State.COMMITTED,"Safe unambiguous request/acknowledgement recovery accepts original queue with one fee and no repeated charge");
            System.out.println("ROYAL_ORDER_CRASH_RECOVERED "+boundary+" disposition=QUEUED diskBefore="+before+" final=64");
        }
        village.setActive(false); VillageSavedData.get(level).setDirty(); h.succeed();
    }

    private static void halt(ServerLevel level,CompoundTag tag,Stage expected,State payment,long funds) {
        var order=store(level).load(tag.getUUID("id")); var receipt=new RoyalPaymentService(level).inspectNpc(tag.getUUID("kingdom"),tag.getUUID("id"));
        if(order==null || order.stage()!=expected || (receipt==null ? payment!=null : receipt.state()!=payment)
                || RoyalTreasurySnapshot.forNpc(level,tag.getUUID("kingdom")).spendableValue()!=funds) throw new IllegalStateException("Wrong real domain/payment/coin boundary; do not halt or report a passing probe");
        System.out.println("ROYAL_ORDER_CRASH_BOUNDARY_REACHED "+boundary()+" order="+expected+" payment="+payment+" funds="+funds+" halt=70"); System.out.flush();
        Runtime.getRuntime().halt(70); // No Minecraft shutdown, save hook, finally cleanup or GameTest success.
    }
    private static RoyalConstructionService service(ServerLevel level,RoyalVillagePersistence villages,RoyalPaymentService payments) throws Exception {
        var constructor=RoyalConstructionService.class.getDeclaredConstructor(ServerLevel.class,RoyalVillagePersistence.class,RoyalPaymentService.class);
        constructor.setAccessible(true); return constructor.newInstance(level,villages,payments);
    }
    private static RoyalPaymentService payment(ServerLevel level,RoyalTreasuryPersistence persistence) { return RoyalPaymentGameTests.service(level,payments(level),persistence); }
    private static Village load(ServerLevel level,CompoundTag tag) {
        if(!boundary().equals(tag.getString("boundary")) || KingdomSavedData.get(level).kingdom(tag.getUUID("kingdom"))==null) throw new IllegalStateException("Wrong original probe world");
        var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));
        if(village==null || village.getTownhall().getInventory()==null) throw new IllegalStateException("Original plan storage missing; never repair the fixture after a crash");
        for(var pos:village.getTownhall().getInventory().getChestPositions()) level.getChunkAt(pos); return village;
    }
    private static final class HeldLock implements AutoCloseable {
        FileChannel channel; FileLock lock;
        void acquire(Path file) {
            try { channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE); lock=channel.lock(); }
            catch(java.io.IOException failure) { throw new IllegalStateException("Cannot inject actual file-lock rejection",failure); }
        }
        @Override public void close() { try { if(lock!=null) {lock.close();lock=null;} if(channel!=null) {channel.close();channel=null;} } catch(java.io.IOException e) { throw new IllegalStateException(e); } }
    }
    private static Path payments(ServerLevel level) { return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-payments"); }
    private static Path orders(ServerLevel level) { return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"); }
    private static RoyalConstructionStore store(ServerLevel level) { return new RoyalConstructionStore(orders(level)); }
    private static String boundary() { String value=System.getProperty("kingdoms.tests.orderCrashBoundary",""); if(!BOUNDARIES.contains(value)) throw new IllegalStateException("Select an explicit isolated domain crash boundary"); return value; }
    private static Path marker(ServerLevel level) { return level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-order-crash.dat"); }
    private static CompoundTag read(ServerLevel level) throws Exception { return NbtIo.readCompressed(marker(level),NbtAccounter.create(1024*1024)); }
}
