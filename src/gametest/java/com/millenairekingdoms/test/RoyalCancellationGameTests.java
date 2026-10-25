package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalConstructionStore.Intent;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentService.Purpose;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Cancellation finance and original queue preservation; no synthetic finished building is used. */
@PrefixGameTestTemplate(false)
public final class RoyalCancellationGameTests {
    private static final String NS="millenairekingdoms_royal_construction_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unpaidCancellationIsDurableAndNeverCreatesARefund(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"cancel-unpaid")) {
            var intent=intent(f); var store=store(f.level()); store.reserve(intent);
            var service=new RoyalConstructionService(f.level()); var cancelled=service.cancelNpc(f.kingdom(),intent.id());
            h.assertTrue(cancelled.stage()==Stage.CANCELLED && cancelled.cancellationFrom()==Stage.REQUESTED
                    && f.value()==128 && new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),intent.id())==null,"Unpaid cancellation creates neither a payment nor coins");
            h.assertTrue(service.retryNpc(f.kingdom(),intent.id()).stage()==Stage.CANCELLED
                    && service.cancelNpc(f.kingdom(),intent.id()).equals(cancelled) && f.village().getPendingProject()==null,"Cancelled request cannot be revived or queued by a retry"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unacceptedPaidCancellationRefundsOnlyOnceAndNeverQueues(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"cancel-paid")) {
            var intent=intent(f); var store=store(f.level()); store.reserve(intent); var payments=new RoyalPaymentService(f.level());
            payments.payNpc(f.kingdom(),intent.id(),Purpose.CONSTRUCTION,intent.id(),intent.fee()); store.change(intent.id(),Stage.REQUESTED,Stage.PAID,Blocked.NONE,null);
            h.assertTrue(f.value()==64,"Fixture uses an actual acknowledged debit");
            var service=new RoyalConstructionService(f.level()); var cancelled=service.cancelNpc(f.kingdom(),intent.id());
            h.assertTrue(cancelled.stage()==Stage.CANCELLED && cancelled.cancellationFrom()==Stage.PAID && f.value()==128
                    && payments.inspectNpc(f.kingdom(),intent.id()).state()==State.REFUNDED,"Pre-acceptance cancellation refunds the bound real patronage");
            service.retryNpc(f.kingdom(),intent.id()); service.cancelNpc(f.kingdom(),intent.id());
            h.assertTrue(f.value()==128 && f.village().getPendingProject()==null,"Refund replay cannot mint another fee or fulfill cancelled work"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void paidReceiptBeforeOrderAcknowledgementStillRefunds(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"cancel-paid-requested")) {
            var intent=intent(f); store(f.level()).reserve(intent);
            new RoyalPaymentService(f.level()).payNpc(f.kingdom(),intent.id(),Purpose.CONSTRUCTION,intent.id(),intent.fee());
            var result=new RoyalConstructionService(f.level()).cancelNpc(f.kingdom(),intent.id());
            h.assertTrue(result.stage()==Stage.CANCELLED && result.cancellationFrom()==Stage.REQUESTED && f.value()==128
                    && f.village().getPendingProject()==null,"Cancellation handles payment durability before the order PAID write without accepting the action"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void interruptedPreparedDebitIsCancelledWithoutFulfillingTheOrder(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"cancel-prepared")) {
            var intent=intent(f); store(f.level()).reserve(intent);
            var interruption=new RoyalTreasuryPersistence() {
                int flushes;
                @Override public void flush(ServerLevel level,List<BlockPos> positions) {
                    if(++flushes==2) throw new IllegalStateException("Injected interruption after live debit before its acknowledgement");
                    super.flush(level,positions);
                }
            };
            var directory=f.level().getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-payments");
            boolean interrupted=false;
            try { RoyalPaymentGameTests.service(f.level(),directory,interruption).payNpc(f.kingdom(),intent.id(),Purpose.CONSTRUCTION,intent.id(),intent.fee()); }
            catch(IllegalStateException expected) { interrupted=true; }
            h.assertTrue(interrupted && f.value()==64 && new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),intent.id()).state()==State.PREPARED,"Real unacknowledged debit remains PREPARED");
            var result=new RoyalConstructionService(f.level()).cancelNpc(f.kingdom(),intent.id());
            h.assertTrue(result.stage()==Stage.CANCELLED && f.value()==128 && f.village().getPendingProject()==null
                    && new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),intent.id()).state()==State.REFUNDED,"Durable cancellation reconciles and refunds PREPARED without briefly accepting work"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void fullTreasuryDefersRefundAndRetryCannotReviveTheOrder(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"cancel-full")) {
            var intent=intent(f); var store=store(f.level()); store.reserve(intent);
            var payments=new RoyalPaymentService(f.level()); payments.payNpc(f.kingdom(),intent.id(),Purpose.CONSTRUCTION,intent.id(),intent.fee());
            store.change(intent.id(),Stage.REQUESTED,Stage.PAID,Blocked.NONE,null);
            for(var pos:f.village().getTownhall().getInventory().getChestPositions()) {
                var chest=(ChestBlockEntity)f.level().getBlockEntity(pos);
                for(int i=0;i<chest.getContainerSize();i++) chest.setItem(i,new ItemStack(Items.COBBLESTONE,64));
            }
            var service=new RoyalConstructionService(f.level()); boolean deferred=false;
            try { service.cancelNpc(f.kingdom(),intent.id()); } catch(IllegalArgumentException|IllegalStateException expected) { deferred=true; }
            h.assertTrue(deferred && store.load(intent.id()).stage()==Stage.CANCELLING && store.load(intent.id()).cancellationFrom()==Stage.PAID
                    && payments.inspectNpc(f.kingdom(),intent.id()).state()==State.PAID && f.village().getPendingProject()==null,"Full inventory retains the cancellation decision and unrefunded receipt instead of deleting goods or resuming construction");
            f.chest().setItem(0,ItemStack.EMPTY); var result=service.retryNpc(f.kingdom(),intent.id());
            h.assertTrue(result.stage()==Stage.CANCELLED && f.value()==64 && f.chest().getItem(1).is(Items.COBBLESTONE)
                    && payments.inspectNpc(f.kingdom(),intent.id()).state()==State.REFUNDED && f.village().getPendingProject()==null,"Retry after space is available refunds once while preserving later legitimate goods"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void acceptedCancellationKeepsPatronageAndAutonomousPendingWork(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"cancel-accepted")) {
            var selected=choice(f); var id=UUID.randomUUID(); var service=new RoyalConstructionService(f.level());
            service.requestNpc(f.kingdom(),f.village().getId().uuid(),selected.planSet(),id); var pending=f.village().getPendingProject();
            var result=service.cancelNpc(f.kingdom(),id);
            h.assertTrue(result.stage()==Stage.CANCELLED && result.cancellationFrom()==Stage.QUEUED && f.value()==64
                    && new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),id).state()==State.COMMITTED
                    && f.village().getPendingProject()==pending && f.village().getOwnerUUID()==null,"Accepted cancellation stops tracking, preserves earned patronage and does not erase/control autonomous work");
            service.cancelNpc(f.kingdom(),id); service.retryNpc(f.kingdom(),id);
            h.assertTrue(f.value()==64 && f.village().getPendingProject()==pending,"Accepted cancellation replay cannot refund or duplicate pending work"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void uncertainPublicationNeverRefundsOrClearsCoincidentNaturalWork(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"cancel-uncertain")) {
            var intent=intent(f); var store=store(f.level()); store.reserve(intent);
            new RoyalPaymentService(f.level()).payNpc(f.kingdom(),intent.id(),Purpose.CONSTRUCTION,intent.id(),intent.fee());
            store.change(intent.id(),Stage.REQUESTED,Stage.PAID,Blocked.NONE,null); store.change(intent.id(),Stage.PAID,Stage.QUEUING,Blocked.NONE,null);
            var coincident=new Village.PendingProject(intent.planSet(),intent.variant(),0,false,null); f.village().setPendingProject(coincident);
            var result=new RoyalConstructionService(f.level()).cancelNpc(f.kingdom(),intent.id());
            h.assertTrue(result.stage()==Stage.RECOVERY_REQUIRED && result.cancellationFrom()==Stage.QUEUING
                    && result.blocked()==Blocked.AMBIGUOUS_ENGINE_STATE && f.value()==64 && f.village().getPendingProject()==coincident
                    && new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),intent.id()).state()==State.PAID,"Ambiguous cross-file publication preserves coins, receipt, reservation and possibly natural work for explicit recovery"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void cancellationCannotRefundAnUnrelatedPaymentId(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"cancel-collision")) {
            var intent=intent(f); store(f.level()).reserve(intent); var payments=new RoyalPaymentService(f.level());
            payments.payNpc(f.kingdom(),intent.id(),Purpose.ADMINISTRATIVE,UUID.randomUUID(),64);
            var result=new RoyalConstructionService(f.level()).cancelNpc(f.kingdom(),intent.id());
            h.assertTrue(result.stage()==Stage.RECOVERY_REQUIRED && result.cancellationFrom()==Stage.REQUESTED && f.value()==64
                    && payments.inspectNpc(f.kingdom(),intent.id()).state()==State.PAID,"UUID collision cannot refund an administrative charge or change its purpose/reference"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void versionOneMigrationRetainsAmbiguousCancellationAndTerminalIdentities(GameTestHelper h) throws Exception {
        var directory=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("royal-v1-migration-"+UUID.randomUUID());
        var store=new RoyalConstructionStore(directory);
        try(var f=fixture(h,"cancel-migration")) {
            var pending=intent(f); store.reserve(pending); store.change(pending.id(),Stage.REQUESTED,Stage.CANCELLING,Blocked.AUTHORITY,null);
            var file=directory.resolve("orders.dat"); var tag=NbtIo.readCompressed(file,NbtAccounter.create(1024*1024)); tag.putInt("format",1);
            for(Tag raw:tag.getList("orders",Tag.TAG_COMPOUND)) { ((CompoundTag)raw).remove("cancellationFrom"); ((CompoundTag)raw).remove("recoveryDecision"); } NbtIo.writeCompressed(tag,file);
            var bytes=Files.readAllBytes(file); var migrated=new RoyalConstructionStore(directory).load(pending.id());
            h.assertTrue(migrated.stage()==Stage.CANCELLING && migrated.cancellationFrom()==Stage.CANCELLING && Arrays.equals(bytes,Files.readAllBytes(file)),"Reading v1 preserves bytes and explicitly retains unknown cancellation origin");
            store.change(pending.id(),Stage.CANCELLING,Stage.RECOVERY_REQUIRED,Blocked.AMBIGUOUS_ENGINE_STATE,null);
            var saved=NbtIo.readCompressed(file,NbtAccounter.create(1024*1024));
            h.assertTrue(saved.getInt("format")==4 && store.load(pending.id()).cancellationFrom()==Stage.CANCELLING && store.load(pending.id()).intent().equals(pending),"Next atomic write migrates v1 intent without guessing refund eligibility");
            var freshDirectory=directory.resolve("terminal"); var terminalStore=new RoyalConstructionStore(freshDirectory); terminalStore.reserve(pending);
            terminalStore.change(pending.id(),Stage.REQUESTED,Stage.CANCELLING,Blocked.NONE,null); terminalStore.change(pending.id(),Stage.CANCELLING,Stage.CANCELLED,Blocked.NONE,null);
            var terminalFile=freshDirectory.resolve("orders.dat"); tag=NbtIo.readCompressed(terminalFile,NbtAccounter.create(1024*1024)); tag.putInt("format",1);
            for(Tag raw:tag.getList("orders",Tag.TAG_COMPOUND)) { ((CompoundTag)raw).remove("cancellationFrom"); ((CompoundTag)raw).remove("recoveryDecision"); } NbtIo.writeCompressed(tag,terminalFile);
            h.assertTrue(new RoyalConstructionStore(freshDirectory).reserve(pending).stage()==Stage.CANCELLED,"A migrated legacy terminal ID remains a tombstone, never a new unpaid order"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void everyVersionOneStageMigratesWithoutResettingItsBoundIntent(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"all-stage-migration")) {
            var path=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("royal-all-stages-"+UUID.randomUUID()); var store=new RoyalConstructionStore(path);
            var base=intent(f); store.reserve(base); var file=path.resolve("orders.dat"); var tag=NbtIo.readCompressed(file,NbtAccounter.create(1024*1024));
            var prototype=tag.getList("orders",Tag.TAG_COMPOUND).getCompound(0); var list=new net.minecraft.nbt.ListTag();
            for(var stage:Stage.values()) {
                if(stage==Stage.WAITING)continue; // WAITING did not exist in legacy format1.
                var item=prototype.copy(); item.putString("stage",stage.name()); item.getCompound("intent").putUUID("id",UUID.randomUUID());
                item.getCompound("intent").putUUID("village",UUID.randomUUID());
                if(stage==Stage.IN_PROGRESS || stage==Stage.COMPLETED) item.putUUID("building",UUID.randomUUID()); list.add(item);
            }
            tag.putInt("format",1); tag.put("orders",list); NbtIo.writeCompressed(tag,file); var before=Files.readAllBytes(file); var entries=store.entries();
            h.assertTrue(entries.size()==Stage.values().length-1 && Arrays.equals(before,Files.readAllBytes(file)),"Every v1 stage reads without destructive eager conversion");
            store.reserve(base); var reopened=new RoyalConstructionStore(path).entries();
            for(var entry:entries) h.assertTrue(reopened.stream().anyMatch(entry::equals),"Migration retains exact intent, quote, building, stage and cancellation ambiguity for "+entry.stage());
            h.assertTrue(NbtIo.readCompressed(file,NbtAccounter.create(1024*1024)).getInt("format")==4,"Only a successful locked atomic mutation upgrades the index"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void cancellationDecisionAndActualRefundSurviveSeparateJvm(GameTestHelper h) throws Exception {
        var level=h.getLevel(); var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-cancellations.dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tags=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024));
            for(String key:List.of("unaccepted","accepted")) {
                var tag=tags.getCompound(key); var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));
                h.assertTrue(village!=null && village.getTownhall().getInventory()!=null,"Original persisted village/treasury reopens without repair");
                for(var pos:village.getTownhall().getInventory().getChestPositions()) level.getChunkAt(pos);
                var id=tag.getUUID("id"); var result=new RoyalConstructionService(level).retryNpc(tag.getUUID("kingdom"),id);
                var receipt=new RoyalPaymentService(level).inspectNpc(tag.getUUID("kingdom"),id); boolean accepted=key.equals("accepted");
                h.assertTrue(result.stage()==Stage.CANCELLED && result.cancellationFrom()==(accepted ? Stage.QUEUED : Stage.PAID)
                        && RoyalTreasurySnapshot.forNpc(level,tag.getUUID("kingdom")).spendableValue()==(accepted ? 64 : 128)
                        && receipt.state()==(accepted ? State.COMMITTED : State.REFUNDED)
                        && (village.getPendingProject()!=null)==accepted,"Separate JVM resumes the durable decision: only unaccepted patronage refunds, autonomous accepted work survives");
            }
            System.out.println("ROYAL_CANCELLATION_RESTART_READ_PASS"); h.succeed(); return;
        }
        var tags=new CompoundTag();
        for(String key:List.of("unaccepted","accepted")) {
            var f=fixture(h,"cancel-restart-"+key); var intent=intent(f); var store=store(level);
            if(key.equals("accepted")) new RoyalConstructionService(level).requestNpc(f.kingdom(),f.village().getId().uuid(),intent.planSet(),intent.id());
            else {
                store.reserve(intent); new RoyalPaymentService(level).payNpc(f.kingdom(),intent.id(),Purpose.CONSTRUCTION,intent.id(),intent.fee());
                store.change(intent.id(),Stage.REQUESTED,Stage.PAID,Blocked.NONE,null);
            }
            var entry=store.load(intent.id()); store.change(intent.id(),entry.stage(),Stage.CANCELLING,Blocked.NONE,null);
            f.village().setActive(false); var tag=new CompoundTag(); tag.putUUID("id",intent.id()); tag.putUUID("kingdom",f.kingdom()); tag.putUUID("village",f.village().getId().uuid()); tags.put(key,tag);
        }
        NbtIo.writeCompressed(tags,marker); VillageSavedData.get(level).setDirty();
        System.out.println("ROYAL_CANCELLATION_RESTART_WRITE_PASS"); h.succeed();
    }

    private static RoyalPaymentGameTests.Fixture fixture(GameTestHelper h,String name) {
        var f=RoyalPaymentGameTests.persistentFixture(h,name); f.village().setActive(true);
        f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2)); return f;
    }
    private static RoyalConstructionCatalog.Option choice(RoyalPaymentGameTests.Fixture f) { return RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst(); }
    private static Intent intent(RoyalPaymentGameTests.Fixture f) {
        var choice=choice(f); return new Intent(UUID.randomUUID(),f.level().dimension().location().toString(),f.kingdom(),f.village().getId().uuid(),f.village().getId().uuid(),
                null,0,f.village().getVillageTypeId(),choice.planSet(),choice.plan(),choice.variant(),choice.patronageFee(),Math.max(0,f.level().getGameTime()),
                f.village().getBuildings().stream().map(b->b.getId().uuid()).collect(Collectors.toSet()));
    }
    private static RoyalConstructionStore store(ServerLevel level) { return new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction")); }
}
