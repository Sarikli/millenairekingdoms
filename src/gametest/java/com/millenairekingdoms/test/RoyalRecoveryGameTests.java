package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionRuntime;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalConstructionStore.Intent;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.construction.RoyalRecoveryService;
import com.millenairekingdoms.construction.RoyalRecoveryStore;
import com.millenairekingdoms.construction.RoyalRecoveryStore.Mode;
import com.millenairekingdoms.construction.RoyalRecoveryStore.State;
import com.millenairekingdoms.construction.RoyalVillagePersistence;
import com.millenairekingdoms.economy.RoyalPaymentJournal;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentService.Purpose;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingInstance;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Existing engine work and real acknowledged coins; operator review never manufactures fulfillment. */
@PrefixGameTestTemplate(false)
public final class RoyalRecoveryGameTests {
    private static final String NS="millenairekingdoms_royal_recovery_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void serviceAndCommandsRequireOperatorBeforeAuditOrFinance(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"recovery-permission")) {
            var i=quarantine(f,true); var service=new RoyalRecoveryService(f.level()); var low=op(f.level()).withPermission(0);
            var before=audit(f.level()).entries(); var decision=UUID.randomUUID();
            refused(h,()->service.authorize(low,decision,i.id(),Mode.ADOPT_PENDING,null,"Reviewed pending"));
            refused(h,()->service.entries(low)); refused(h,()->service.inspectOrder(low,i.id()));
            refused(h,()->service.resume(low,decision)); refused(h,()->service.abandon(low,decision,"No authority"));
            var command=f.level().getServer().getCommands().getDispatcher().getRoot().getChild("kingdoms").getChild("royal_recovery");
            h.assertTrue(!command.canUse(low) && command.canUse(op(f.level())) && audit(f.level()).entries().equals(before)
                    && f.value()==64 && orders(f.level()).load(i.id()).stage()==Stage.RECOVERY_REQUIRED,"Both command and service deny unprivileged review without any economic or audit mutation"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void exactPendingAdoptionBindsActorReasonAndReplaysWithoutNewDebit(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"recovery-pending")) {
            var i=quarantine(f,true); var pending=f.village().getPendingProject(); var service=new RoyalRecoveryService(f.level()); var id=UUID.randomUUID();
            var authorized=service.authorize(op(f.level()),id,i.id(),Mode.ADOPT_PENDING,null,"Reviewed exact original pending after restart");
            h.assertTrue(authorized.state()==State.AUTHORIZED && authorized.intent().order().equals(i) && f.value()==64
                    && orders(f.level()).load(i.id()).stage()==Stage.RECOVERY_REQUIRED,"Audit must be durable while the order is still quarantined");
            h.assertTrue(service.authorize(op(f.level()),id,i.id(),Mode.ADOPT_PENDING,null,authorized.intent().reason()).equals(authorized),"Exact authorization retry is immutable");
            refused(h,()->service.authorize(op(f.level()),id,i.id(),Mode.ADOPT_PENDING,null,"Different review"));
            refused(h,()->service.authorize(op(f.level()),id,i.id(),Mode.FORFEIT_FEE,null,authorized.intent().reason()));
            refused(h,()->service.authorize(op(f.level()),UUID.randomUUID(),i.id(),Mode.ADOPT_PENDING,null,"Overlapping decision"));
            var done=service.resume(op(f.level()),id); var order=orders(f.level()).load(i.id());
            h.assertTrue(done.state()==State.DONE && order.stage()==Stage.QUEUED && id.equals(order.recoveryDecision()) && f.value()==64
                    && f.village().getPendingProject()==pending && paid(f,i).state()==RoyalPaymentJournal.State.COMMITTED,"Adoption binds only existing original work and retains the one paid fee");
            h.assertTrue(service.resume(op(f.level()),id).equals(done) && f.value()==64,"Resolution retry returns immutable completed audit");
            new RoyalConstructionService(f.level()).cancelNpc(f.kingdom(),i.id()); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=2400)
    public static void exactQuarantinedOrderRecoversPastUnrelatedNativeSizeWithoutAnotherFee(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"recovery-native-size");var other=fixture(h,"recovery-native-size-other")) {
            var intent=quarantine(f,true);var pending=f.village().getPendingProject();
            new RoyalVillagePersistence().pending(f.level(),f.village().getId().uuid(),pending);
            var file=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(f.level().dimension(),f.level().getServer().getWorldPath(LevelResource.ROOT)).resolve("data/millenaire_villages.dat");
            var original=Files.readAllBytes(file);
            try {
                var root=NbtIo.readCompressed(file,NbtAccounter.create(64L*1024*1024));boolean added=false;
                for(var raw:root.getCompound("data").getList("villages",Tag.TAG_COMPOUND)) {
                    var tag=(CompoundTag)raw;if(tag.hasUUID("id") && tag.getUUID("id").equals(other.village().getId().uuid())) {
                        tag.putByteArray("kingdoms_test_extension",new byte[64*1024*1024]);added=true;
                    }
                }
                h.assertTrue(added,"Size pressure is isolated to a different genuine native village");NbtIo.writeCompressed(root,file);
                var service=new RoyalRecoveryService(f.level());var decision=UUID.randomUUID();
                service.authorize(op(f.level()),decision,intent.id(),Mode.ADOPT_PENDING,null,"Reviewed existing pending after native readback size failure");
                var done=service.resume(op(f.level()),decision);var order=orders(f.level()).load(intent.id());
                h.assertTrue(done.state()==State.DONE && order.stage()==Stage.QUEUED && order.intent().id().equals(intent.id())
                        && f.value()==64 && paid(f,intent).state()==RoyalPaymentJournal.State.COMMITTED && f.village().getPendingProject()==pending,
                        "Operator recovery acknowledges the same exact original pending/paid64 identity without a replacement request, project or debit");
                h.assertTrue(service.resume(op(f.level()),decision).equals(done) && f.value()==64,"Recovery replay cannot charge another fee");
            }finally{Files.write(file,original);}
            h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void explicitForfeitRetainsPaidFeeAndOriginalAutonomousWork(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"recovery-forfeit")) {
            var i=quarantine(f,true); var pending=f.village().getPendingProject(); var service=new RoyalRecoveryService(f.level()); var id=UUID.randomUUID();
            service.authorize(op(f.level()),id,i.id(),Mode.FORFEIT_FEE,null,"Preserve natural work; retain paid patronage");
            service.resume(op(f.level()),id); var order=orders(f.level()).load(i.id());
            h.assertTrue(order.stage()==Stage.CANCELLED && order.blocked()==Blocked.OPERATOR_FORFEIT && f.value()==64
                    && paid(f,i).state()==RoyalPaymentJournal.State.COMMITTED && f.village().getPendingProject()==pending,"Explicit forfeiture never refunds or erases independent world work");
            refused(h,()->service.authorize(op(f.level()),UUID.randomUUID(),i.id(),Mode.ADOPT_PENDING,null,"Try reviving cancelled order"));
            h.assertTrue(new RoyalConstructionService(f.level()).retryNpc(f.kingdom(),i.id()).stage()==Stage.CANCELLED,"Ordinary retry cannot revive OP forfeiture"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void abandonedDecisionIsAuditedAndCannotBeResumed(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"recovery-abandon")) {
            var i=quarantine(f,true); var service=new RoyalRecoveryService(f.level()); var id=UUID.randomUUID();
            service.authorize(op(f.level()),id,i.id(),Mode.ADOPT_PENDING,null,"Initial inspection");
            var abandoned=service.abandon(op(f.level()),id,"Operator withdrew review; leave quarantined");
            h.assertTrue(abandoned.state()==State.ABANDONED && service.resume(op(f.level()),id).equals(abandoned)
                    && f.value()==64 && orders(f.level()).load(i.id()).stage()==Stage.RECOVERY_REQUIRED,"Abandon only ends the audit decision, preserving finance and quarantine");
            var replacement=UUID.randomUUID(); service.authorize(op(f.level()),replacement,i.id(),Mode.FORFEIT_FEE,null,"Second explicit review"); service.resume(op(f.level()),replacement);
            h.assertTrue(audit(f.level()).load(id).equals(abandoned),"Superseded audit history remains immutable"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void missingOrChangedEvidenceAndExistingBuildingCannotBeAdopted(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"recovery-evidence")) {
            var i=quarantine(f,false); var service=new RoyalRecoveryService(f.level()); var before=audit(f.level()).entries();
            refused(h,()->service.authorize(op(f.level()),UUID.randomUUID(),i.id(),Mode.ADOPT_PENDING,null,"No pending exists"));
            refused(h,()->service.authorize(op(f.level()),UUID.randomUUID(),i.id(),Mode.ADOPT_BUILDING,f.village().getTownhall().getId().uuid(),"Existing townhall is not a new order"));
            refused(h,()->service.authorize(op(f.level()),UUID.randomUUID(),i.id(),Mode.ADOPT_BUILDING,UUID.randomUUID(),"Unknown building"));
            h.assertTrue(audit(f.level()).entries().equals(before) && f.value()==64,"Missing or wrong original evidence cannot authorize adoption");
            f.village().setPendingProject(pending(i)); var id=UUID.randomUUID(); service.authorize(op(f.level()),id,i.id(),Mode.ADOPT_PENDING,null,"Exact pending observed");
            f.village().setPendingProject(null); refused(h,()->service.resume(op(f.level()),id));
            h.assertTrue(audit(f.level()).load(id).state()==State.AUTHORIZED && paid(f,i).state()==RoyalPaymentJournal.State.PAID,"Changed evidence pauses before fee acknowledgment and order resolution");
            service.abandon(op(f.level()),id,"Pending no longer exists"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void authorityLossBeforeResumeCannotReviveEvenAfterSameTickRestoration(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"recovery-retired")) {
            var i=quarantine(f,true); var service=new RoyalRecoveryService(f.level()); var id=UUID.randomUUID();
            service.authorize(op(f.level()),id,i.id(),Mode.ADOPT_PENDING,null,"Reviewed under original mandate");
            var data=KingdomSavedData.get(f.level()); data.setActive(f.kingdom(),false); data.setActive(f.kingdom(),true);
            refused(h,()->service.resume(op(f.level()),id)); var retired=orders(f.level()).load(i.id());
            h.assertTrue(retired.stage()==Stage.RECOVERY_REQUIRED && retired.blocked()==Blocked.AUTHORITY && retired.cancellationFrom()!=null
                    && f.value()==64,"Quarantined mandate keeps permanent retirement even if political state returns in the same tick");
            service.abandon(op(f.level()),id,"Original mandate was retired"); var next=UUID.randomUUID();
            service.authorize(op(f.level()),next,i.id(),Mode.FORFEIT_FEE,null,"Stop retired tracking without refunding accepted-risk patronage"); service.resume(op(f.level()),next);
            h.assertTrue(orders(f.level()).load(i.id()).stage()==Stage.CANCELLED && f.village().getPendingProject()!=null && f.value()==64,"Retired recovery can forfeit but never adopt"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void paidAuthorityCancellationCanBeClosedWithoutRedirectingCoins(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"recovery-fallen")) {
            var i=intent(f); orders(f.level()).reserve(i); pay(f,i); KingdomSavedData.get(f.level()).setActive(f.kingdom(),false);
            var entry=orders(f.level()).load(i.id()); h.assertTrue(entry.stage()==Stage.CANCELLING && entry.blocked()==Blocked.AUTHORITY,"Real political retirement precedes resolution");
            var service=new RoyalRecoveryService(f.level()); var id=UUID.randomUUID();
            service.authorize(op(f.level()),id,i.id(),Mode.FORFEIT_FEE,null,"Explicitly retain paid fee in fallen kingdom; no alternate treasury"); service.resume(op(f.level()),id);
            h.assertTrue(orders(f.level()).load(i.id()).stage()==Stage.CANCELLED && f.value()==64 && paid(f,i).state()==RoyalPaymentJournal.State.COMMITTED
                    && f.village().getPendingProject()==null,"OP closes a paid retired liability without refund redirection or new engine work"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void missingAndWrongPurposeReceiptsCannotAuthorizeRecovery(GameTestHelper h) throws Exception {
        for(boolean wrong:List.of(false,true)) try(var f=fixture(h,"recovery-receipt-"+wrong)) {
            var i=intent(f); orders(f.level()).reserve(i);
            if(wrong) new RoyalPaymentService(f.level()).payNpc(f.kingdom(),i.id(),Purpose.ADMINISTRATIVE,i.id(),i.fee());
            orders(f.level()).change(i.id(),Stage.REQUESTED,Stage.RECOVERY_REQUIRED,Blocked.AMBIGUOUS_ENGINE_STATE,null);
            var service=new RoyalRecoveryService(f.level()); var id=UUID.randomUUID();
            refused(h,()->service.authorize(op(f.level()),id,i.id(),Mode.FORFEIT_FEE,null,"Cannot invent a matching financial receipt"));
            h.assertTrue(audit(f.level()).load(id)==null && f.value()==(wrong ? 64 : 128),"OP permission cannot replace exact purpose/actor/capital/order/amount binding");
        }
        h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void preparedAndAmbiguousPhysicalPaymentsAreNeverResetByOperatorRecovery(GameTestHelper h) throws Exception {
        for(boolean ambiguous:List.of(false,true)) try(var f=fixture(h,"recovery-physical-"+ambiguous)) {
            var i=intent(f); orders(f.level()).reserve(i);
            var failure=new RoyalTreasuryPersistence() {
                int flushes;
                @Override public void flush(ServerLevel level,List<BlockPos> positions) {
                    if(++flushes==2) throw new IllegalStateException("Test cuts debit acknowledgement"); super.flush(level,positions);
                }
            };
            refused(h,()->RoyalPaymentGameTests.service(f.level(),payments(f.level()),failure).payNpc(f.kingdom(),i.id(),Purpose.CONSTRUCTION,i.id(),i.fee()));
            h.assertTrue(paid(f,i).state()==RoyalPaymentJournal.State.PREPARED && f.value()==64,"Cut leaves an unacknowledged journal and an actual applied native debit proof");
            if(ambiguous) {
                // Genuine ambiguous evidence requires losing the same-container proof. A matching proof deliberately
                // permits legitimate later native inventory activity; the old image-only fixture is obsolete.
                f.chest().getPersistentData().remove("millenairekingdoms:royal_operations");
                f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),63));
                new RoyalPaymentService(f.level()).payNpc(f.kingdom(),i.id(),Purpose.CONSTRUCTION,i.id(),i.fee());
            }
            orders(f.level()).change(i.id(),Stage.REQUESTED,Stage.RECOVERY_REQUIRED,Blocked.AMBIGUOUS_ENGINE_STATE,null);
            var service=new RoyalRecoveryService(f.level()); var id=UUID.randomUUID();
            refused(h,()->service.authorize(op(f.level()),id,i.id(),Mode.FORFEIT_FEE,null,"No unsafe financial reset"));
            h.assertTrue(audit(f.level()).load(id)==null && paid(f,i).state()==(ambiguous ? RoyalPaymentJournal.State.RECOVERY_REQUIRED : RoyalPaymentJournal.State.PREPARED)
                    && f.value()==(ambiguous ? 63 : 64),"Neither PREPARED nor RECOVERY_REQUIRED is proof of durable patronage; all physical evidence remains unchanged");
        }
        h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void matchingNativeReceiptReconcilesLaterActivityBeforeOperatorForfeitWithoutInventingCoins(GameTestHelper h)throws Exception{
        try(var f=fixture(h,"recovery-receipted-native-activity")){
            var i=intent(f);orders(f.level()).reserve(i);
            var cut=new RoyalTreasuryPersistence(){int flushes;@Override public void flush(ServerLevel level,List<BlockPos> positions){if(++flushes==2)throw new IllegalStateException("Cut applied debit before journal acknowledgement");super.flush(level,positions);}};
            refused(h,()->RoyalPaymentGameTests.service(f.level(),payments(f.level()),cut).payNpc(f.kingdom(),i.id(),Purpose.CONSTRUCTION,i.id(),i.fee()));
            var inventory=f.village().getTownhall().getInventory();
            h.assertTrue(inventory.remove(f.level(),ModItems.DENIER_ARGENT.get(),1)==1 && inventory.add(f.level(),ModItems.DENIER.get(),63)==63,"Declared native inventory activity spends the remaining64 and supplies63 after the already applied debit");
            var receipt=new RoyalPaymentService(f.level()).payNpc(f.kingdom(),i.id(),Purpose.CONSTRUCTION,i.id(),i.fee());
            h.assertTrue(receipt.state()==RoyalPaymentJournal.State.PAID && f.value()==63,"Matching bound proof preserves later native63 exactly, without recharging, restoring64 or false ambiguity");
            orders(f.level()).change(i.id(),Stage.REQUESTED,Stage.RECOVERY_REQUIRED,Blocked.AMBIGUOUS_ENGINE_STATE,null);var service=new RoyalRecoveryService(f.level());var decision=UUID.randomUUID();
            service.authorize(op(f.level()),decision,i.id(),Mode.FORFEIT_FEE,null,"Exact native proof reconciled; retain paid patronage and current inventory");service.resume(op(f.level()),decision);
            h.assertTrue(f.value()==63 && paid(f,i).state()==RoyalPaymentJournal.State.COMMITTED && orders(f.level()).load(i.id()).stage()==Stage.CANCELLED,"Audited forfeit commits only the original paid fee and never credits guessed coins");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void failedOriginalSaveKeepsAuthorizedDecisionAndPaidQuarantine(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"recovery-domain-ack")) {
            var i=quarantine(f,true); var id=UUID.randomUUID();
            var persistence=new RoyalVillagePersistence() {@Override public void pending(ServerLevel level,UUID village,Village.PendingProject project) {throw new IllegalStateException("Test original save failure");}};
            var service=service(f.level(),persistence); service.authorize(op(f.level()),id,i.id(),Mode.ADOPT_PENDING,null,"Reviewed exact pending");
            refused(h,()->service.resume(op(f.level()),id));
            h.assertTrue(orders(f.level()).load(i.id()).stage()==Stage.RECOVERY_REQUIRED && audit(f.level()).load(id).state()==State.AUTHORIZED
                    && paid(f,i).state()==RoyalPaymentJournal.State.PAID && f.value()==64,"No acceptance/commit can precede acknowledged original evidence");
            new RoyalRecoveryService(f.level()).resume(op(f.level()),id); new RoyalConstructionService(f.level()).cancelNpc(f.kingdom(),i.id()); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void auditAcknowledgmentFailureRetainsAtomicProvenanceAcrossLaterRetirement(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"recovery-audit-ack")) {
            var i=quarantine(f,true); var id=UUID.randomUUID(); var lockFile=RoyalRecoveryService.directory(f.level()).resolve(".lock");
            try(var channel=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
                FileLock[] held={null};
                var persistence=new RoyalVillagePersistence() {
                    @Override public void pending(ServerLevel level,UUID village,Village.PendingProject project) {
                        super.pending(level,village,project);
                        try {held[0]=channel.lock();} catch(Exception failure) {throw new IllegalStateException(failure);}
                    }
                };
                var service=service(f.level(),persistence); service.authorize(op(f.level()),id,i.id(),Mode.ADOPT_PENDING,null,"Reviewed before deliberately failed audit acknowledgment");
                try {refused(h,()->service.resume(op(f.level()),id));} finally {if(held[0]!=null) held[0].close();}
            }
            h.assertTrue(orders(f.level()).load(i.id()).stage()==Stage.QUEUED && id.equals(orders(f.level()).load(i.id()).recoveryDecision())
                    && audit(f.level()).load(id).state()==State.AUTHORIZED && paid(f,i).state()==RoyalPaymentJournal.State.COMMITTED,"Order and decision provenance commit atomically before the failed audit write");
            KingdomSavedData.get(f.level()).setActive(f.kingdom(),false); var retired=orders(f.level()).load(i.id()); var service=new RoyalRecoveryService(f.level());
            refused(h,()->service.abandon(op(f.level()),id,"Cannot abandon an already applied decision"));
            var done=service.resume(op(f.level()),id);
            h.assertTrue(done.state()==State.DONE && orders(f.level()).load(i.id()).equals(retired) && retired.stage()==Stage.CANCELLING
                    && f.value()==64 && f.village().getPendingProject()!=null,"Retry only closes its audit after later retirement; it never reopens tracking or charges again"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void auditUnknownFutureCorruptAndContendedDataRemainUntouched(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"recovery-strict-audit")) {
            var i=quarantine(f,true); var path=root(f.level()).resolve("audit-guards-"+UUID.randomUUID()); var store=new RoyalRecoveryStore(path);
            var intent=new RoyalRecoveryStore.Intent(UUID.randomUUID(),i,Mode.FORFEIT_FEE,null,null,"test-console","Retained audit",0); store.authorize(intent);
            var file=path.resolve("decisions.dat"); var good=Files.readAllBytes(file); var tag=NbtIo.readCompressed(file,NbtAccounter.create(1024*1024));
            for(String corruption:List.of("future","unknown","wrong-type","missing-result","malformed")) {
                var changed=tag.copy();
                switch(corruption) {
                    case "future" -> changed.putInt("format",999);
                    case "unknown" -> changed.putString("unrecognized","retain");
                    case "wrong-type" -> changed.putString("decisions","not a list");
                    case "missing-result" -> changed.getList("decisions",Tag.TAG_COMPOUND).getCompound(0).putString("state","DONE");
                    default -> {}
                }
                if(corruption.equals("malformed")) Files.write(file,new byte[]{1,2,3}); else NbtIo.writeCompressed(changed,file);
                var before=Files.readAllBytes(file); refused(h,store::entries);
                refused(h,()->store.authorize(new RoyalRecoveryStore.Intent(UUID.randomUUID(),i,Mode.FORFEIT_FEE,null,null,"test-console","Do not overwrite",1)));
                h.assertTrue(Arrays.equals(before,Files.readAllBytes(file)),"Invalid audit is preserved byte-for-byte: "+corruption); Files.write(file,good);
            }
            try(var channel=FileChannel.open(path.resolve(".lock"),StandardOpenOption.WRITE);var lock=channel.lock()) {refused(h,store::entries);}
            h.assertTrue(store.load(intent.id()).intent().equals(intent),"Lock failure cannot erase the retained decision"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void versionTwoOrderMigrationPreservesCancellationAndTerminalTombstones(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"recovery-v2")) {
            var path=root(f.level()).resolve("order-v2-"+UUID.randomUUID()); var store=new RoyalConstructionStore(path); var i=intent(f); store.reserve(i);
            store.change(i.id(),Stage.REQUESTED,Stage.CANCELLING,Blocked.AUTHORITY,null); var file=path.resolve("orders.dat");
            var tag=NbtIo.readCompressed(file,NbtAccounter.create(1024*1024)); tag.putInt("format",2); NbtIo.writeCompressed(tag,file); var bytes=Files.readAllBytes(file);
            var before=store.load(i.id()); h.assertTrue(before.cancellationFrom()==Stage.REQUESTED && before.recoveryDecision()==null && Arrays.equals(bytes,Files.readAllBytes(file)),"v2 cancellation reads without eager rewrite or invented recovery provenance");
            store.change(i.id(),Stage.CANCELLING,Stage.CANCELLED,Blocked.AUTHORITY,null);
            h.assertTrue(NbtIo.readCompressed(file,NbtAccounter.create(1024*1024)).getInt("format")==5 && store.reserve(i).stage()==Stage.CANCELLED
                    && store.load(i.id()).intent().equals(i) && store.load(i.id()).cancellationFrom()==Stage.REQUESTED,"First successful locked mutation migrates v2 while retaining intent, cancellation and terminal ID"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",batch="zz_recovery_restart",timeoutTicks=1200)
    public static void authorizedDecisionReopensAndResumesUnderBoundedRuntimeInSeparateJvm(GameTestHelper h) throws Exception {
        var level=h.getLevel(); var marker=root(level).resolve("royal-recovery-restart.dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024)); var id=tag.getUUID("decision"); var order=tag.getUUID("order");
            var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));
            h.assertTrue(village!=null && village.getPendingProject()!=null && audit(level).load(id).state()==State.AUTHORIZED
                    && orders(level).load(order).stage()==Stage.RECOVERY_REQUIRED,"New JVM retains explicit OP decision and genuine pending evidence before resuming");
            for(int n=0;n<100 && audit(level).load(id).state()==State.AUTHORIZED;n++) h.assertTrue(RoyalConstructionRuntime.step(level,1)<=1,"Recovery shares the same one-order total runtime budget");
            h.assertTrue(audit(level).load(id).state()==State.DONE && orders(level).load(order).stage()==Stage.QUEUED
                    && id.equals(orders(level).load(order).recoveryDecision()),"Bounded production scheduler resumes only the OP's existing persisted decision");
            for(var pos:village.getTownhall().getInventory().getChestPositions()) level.getChunkAt(pos);
            h.assertTrue(RoyalTreasurySnapshot.forNpc(level,tag.getUUID("kingdom")).spendableValue()==64
                    && new RoyalPaymentService(level).inspectNpc(tag.getUUID("kingdom"),order).state()==RoyalPaymentJournal.State.COMMITTED,"Separate JVM adoption retains exactly one real patronage debit");
            new RoyalConstructionService(level).cancelNpc(tag.getUUID("kingdom"),order);
            System.out.println("ROYAL_RECOVERY_RESTART_READ_PASS decision="+id+" remaining=64"); h.succeed(); return;
        }
        var f=fixture(h,"recovery-restart"); var i=quarantine(f,true); var id=UUID.randomUUID();
        new RoyalVillagePersistence().pending(level,f.village().getId().uuid(),pending(i));
        new RoyalRecoveryService(level).authorize(op(level),id,i.id(),Mode.ADOPT_PENDING,null,"Persisted operator review; deliberately leave authorized for separate JVM");
        f.village().setActive(false); var tag=new CompoundTag(); tag.putUUID("decision",id); tag.putUUID("order",i.id()); tag.putUUID("village",f.village().getId().uuid()); tag.putUUID("kingdom",f.kingdom());
        NbtIo.writeCompressed(tag,marker); VillageSavedData.get(level).setDirty();
        System.out.println("ROYAL_RECOVERY_RESTART_WRITE_PASS decision="+id+" remaining=64"); h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",batch="royal_recovery_builders",timeoutTicks=120_000)
    public static void genuineOriginalNpcBuildingCanBeAdoptedAndFinishAcrossSeparateJvm(GameTestHelper h) throws Exception {
        boolean read="read".equals(System.getProperty("kingdoms.tests.persistence")); boolean[] cut={false};
        var persistence=new RoyalVillagePersistence() {
            @Override public void building(ServerLevel level,UUID village,BuildingInstance building) {
                super.building(level,village,building);
                if(!read && !cut[0]) {
                    var entry=orders(level).entries().stream().filter(e->e.intent().village().equals(village) && e.stage()==Stage.QUEUED).findFirst().orElse(null);
                    if(entry!=null) {cut[0]=true;orders(level).change(entry.intent().id(),Stage.QUEUED,Stage.RECOVERY_REQUIRED,Blocked.AMBIGUOUS_ENGINE_STATE,null);throw new IllegalStateException("Test lost royal building acknowledgement after real original growth");}
                }
            }
        };
        var constructor=RoyalConstructionService.class.getDeclaredConstructor(ServerLevel.class,RoyalVillagePersistence.class); constructor.setAccessible(true);
        var probe=new RoyalRealConstructionGameTests.Probe(h,read,constructor.newInstance(h.getLevel(),persistence));
        var decisionMarker=root(h.getLevel()).resolve("royal-recovery-builder-decision.dat");
        h.onEachTick(()->{
            try {
                if(read && probe.id!=null) {
                    var marker=NbtIo.readCompressed(decisionMarker,NbtAccounter.create(1024*1024)); var entry=orders(probe.level).load(probe.id);
                    h.assertTrue(marker.getUUID("decision").equals(entry.recoveryDecision()) && audit(probe.level).load(entry.recoveryDecision()).state()==State.DONE,"Actual NPC completion retains its exact audited building adoption across JVMs");
                }
                probe.tick();
            } catch(Exception failure) {
                if(!read && cut[0] && orders(probe.level).load(probe.id).stage()==Stage.RECOVERY_REQUIRED) {
                    try {
                        var i=orders(probe.level).load(probe.id).intent(); var real=probe.village.getBuildings().stream().filter(b->!i.originalBuildings().contains(b.getId().uuid()) && i.planSet().equals(b.getPlanSetId())).findFirst().orElseThrow();
                        var service=new RoyalRecoveryService(probe.level); var id=UUID.randomUUID();
                        service.authorize(op(probe.level),id,probe.id,Mode.ADOPT_BUILDING,real.getId().uuid(),"Reviewed actual original NPC task after lost royal acknowledgement");
                        service.resume(op(probe.level),id); var tag=new CompoundTag(); tag.putUUID("decision",id); NbtIo.writeCompressed(tag,decisionMarker);
                        System.out.println("ROYAL_RECOVERY_REAL_BUILDING_ADOPTED decision="+id+" building="+real.getId().uuid()+" task="+real.getConstructionTask().getNextStepIndex()+"/"+real.getConstructionTask().totalSteps());
                    } catch(Exception recoveryFailure) {probe.close();throw new IllegalStateException("Real building adoption failed",recoveryFailure);}
                } else {probe.close();throw new IllegalStateException("Real recovery fixture "+probe.diagnostic(),failure);}
            }
        });
    }

    private static RoyalPaymentGameTests.Fixture fixture(GameTestHelper h,String name) {
        var f=RoyalPaymentGameTests.persistentFixture(h,name); f.village().setActive(true); f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2)); return f;
    }
    private static Intent intent(RoyalPaymentGameTests.Fixture f) {
        var c=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst();
        return new Intent(UUID.randomUUID(),f.level().dimension().location().toString(),f.kingdom(),f.village().getId().uuid(),f.village().getId().uuid(),null,0,f.village().getVillageTypeId(),c.planSet(),c.plan(),c.variant(),c.patronageFee(),Math.max(0,f.level().getGameTime()),f.village().getBuildings().stream().map(b->b.getId().uuid()).collect(Collectors.toSet()));
    }
    private static Intent quarantine(RoyalPaymentGameTests.Fixture f,boolean pending) {
        var i=intent(f); orders(f.level()).reserve(i); pay(f,i);
        orders(f.level()).change(i.id(),Stage.REQUESTED,Stage.RECOVERY_REQUIRED,Blocked.AMBIGUOUS_ENGINE_STATE,null);
        if(pending) f.village().setPendingProject(pending(i)); return i;
    }
    private static void pay(RoyalPaymentGameTests.Fixture f,Intent i) {new RoyalPaymentService(f.level()).payNpc(f.kingdom(),i.id(),Purpose.CONSTRUCTION,i.id(),i.fee());}
    private static RoyalPaymentService.Receipt paid(RoyalPaymentGameTests.Fixture f,Intent i) {return new RoyalPaymentService(f.level()).inspectConstruction(i.id(),i.kingdom(),i.capital(),i.actor(),i.fee());}
    private static Village.PendingProject pending(Intent i) {return new Village.PendingProject(i.planSet(),i.variant(),0,false,null);}
    private static RoyalRecoveryService service(ServerLevel level,RoyalVillagePersistence persistence) throws Exception {var c=RoyalRecoveryService.class.getDeclaredConstructor(ServerLevel.class,RoyalVillagePersistence.class);c.setAccessible(true);return c.newInstance(level,persistence);}
    private static CommandSourceStack op(ServerLevel level) {return level.getServer().createCommandSourceStack().withLevel(level);}
    private static Path root(ServerLevel level) {return level.getServer().getWorldPath(LevelResource.ROOT);}
    private static Path payments(ServerLevel level) {return root(level).resolve("data/millenairekingdoms/royal-payments");}
    private static RoyalConstructionStore orders(ServerLevel level) {return new RoyalConstructionStore(root(level).resolve("data/millenairekingdoms/royal-construction"));}
    private static RoyalRecoveryStore audit(ServerLevel level) {return new RoyalRecoveryStore(RoyalRecoveryService.directory(level));}
    private static void refused(GameTestHelper h,Runnable action) {boolean rejected=false;try {action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"Unsafe/conflicting/unauthorized recovery must fail closed");}
}
