package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalPaymentJournal;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import com.millenairekingdoms.economy.RoyalCoinPlan;

import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalPaymentService.Purpose;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.building.SpecialPoint;
import org.millenaire.culture.ModCultures;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Real coins, chests, chunk writes, filesystem locks and journal/physical save skew across two JVMs.
 * Injected exceptions exercise process-interruption boundaries; these are not host power-loss tests. */
@PrefixGameTestTemplate(false)
public final class RoyalPaymentGameTests {
    private static final String NS="millenairekingdoms_royal_economy_tests";
    private static final AtomicInteger NEXT=new AtomicInteger();
    private static final UUID REFERENCE=new UUID(90,1);

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void realDebitRefundAndReplayConserveCoins(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"real-refund")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER_OR.get())); f.chest().setItem(5,new ItemStack(Items.DIAMOND,7));
            var s=f.service(); var id=UUID.randomUUID();
            h.assertTrue(s.payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1).state()==State.PAID && f.value()==4095,"Real gold is broken into exact silver/bronze change");
            h.assertTrue(s.payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1).state()==State.PAID && f.value()==4095,"Identical retry does not debit twice");
            f.chest().setItem(8,new ItemStack(ModItems.DENIER_ARGENT.get()));
            h.assertTrue(s.refundNpc(f.kingdom,id).state()==State.REFUNDED && f.value()==4160,"Refund adds only one denier to current coins, retaining later deposit");
            h.assertTrue(s.refundNpc(f.kingdom,id).state()==State.REFUNDED && f.value()==4160,"Repeated refund creates no coins");
            h.assertTrue(f.chest().getItem(5).is(Items.DIAMOND) && f.chest().getItem(5).getCount()==7,"Goods keep their original slot and count");
            h.assertTrue(s.payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1).state()==State.REFUNDED && f.value()==4160,"Refunded request never becomes payable again");
            h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void requestBindingReservationsAndCommitAreEnforced(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"binding")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),3)); var id=UUID.randomUUID(); var s=f.service();
            s.payNpc(f.kingdom,id,Purpose.ADMINISTRATIVE,REFERENCE,64);
            refused(h,()->f.service().payNpc(f.kingdom,UUID.randomUUID(),Purpose.ADMINISTRATIVE,REFERENCE,1));
            refused(h,()->s.payNpc(f.kingdom,id,Purpose.ADMINISTRATIVE,REFERENCE,65));
            refused(h,()->s.payNpc(f.kingdom,id,Purpose.SHIPMENT_FEE,REFERENCE,64));
            refused(h,()->s.payNpc(f.kingdom,id,Purpose.ADMINISTRATIVE,UUID.randomUUID(),64));
            h.assertTrue(f.value()==128,"Conflicting or overlapping requests never spend coins");
            h.assertTrue(s.commitNpc(f.kingdom,id).state()==State.COMMITTED && s.commitNpc(f.kingdom,id).state()==State.COMMITTED,"Fulfillment commit is idempotent");
            refused(h,()->s.refundNpc(f.kingdom,id));
            h.assertTrue(s.payNpc(f.kingdom,UUID.randomUUID(),Purpose.ADMINISTRATIVE,REFERENCE,1).state()==State.PAID && f.value()==127,"Committed expense releases reservation, never a refund"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void insufficientFullAndUnloadedStorageNeverPay(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"refused")) {
            var s=f.service(); refused(h,()->s.payNpc(f.kingdom,UUID.randomUUID(),Purpose.CONSTRUCTION,REFERENCE,1));
            f.chest().setItem(0,new ItemStack(ModItems.DENIER_OR.get()));
            for(int i=1;i<27;i++) f.chest().setItem(i,new ItemStack(Items.DIRT,64));
            refused(h,()->s.payNpc(f.kingdom,UUID.randomUUID(),Purpose.CONSTRUCTION,REFERENCE,1));
            h.assertTrue(f.value()==4096 && f.journal().entries().isEmpty(),"Rejected change has neither financial intent nor partial debit");
            var far=new BlockPos(8_000_000,64,8_000_000); inventory(f.village,List.of(f.position,far));
            refused(h,()->s.payNpc(f.kingdom,UUID.randomUUID(),Purpose.CONSTRUCTION,REFERENCE,4096));
            h.assertTrue(!f.level.isLoaded(far) && f.value()==4096,"Actual unloaded treasury remains unloaded and unspent"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void lostAuthorityDuringSaveCannotDebit(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"authority")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10));
            var failing=new RoyalTreasuryPersistence() {
                @Override public void flush(ServerLevel level,List<BlockPos> positions) {
                    super.flush(level,positions); KingdomSavedData.get(level).setActive(f.kingdom,false);
                }
            };
            refused(h,()->f.service(failing).payNpc(f.kingdom,UUID.randomUUID(),Purpose.CONSTRUCTION,REFERENCE,1));
            h.assertTrue(f.value()==10 && f.journal().entries().isEmpty(),"Authority is rechecked after persistence and before preparing/mutating money"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void playerPaymentsUseRealRulerAndReputationChecks(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"player")) {
            var p=new FakePlayer(f.level,new GameProfile(UUID.randomUUID(),"Royal payer"));
            f.village.addReputation(p.getUUID(),32768-f.village.getCombinedReputation(f.level,p.getUUID()));
            KingdomSavedData.get(f.level).claimRule(f.kingdom,p.getUUID(),"Royal payer",1);
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var s=f.service(); var id=UUID.randomUUID();
            refused(h,()->s.payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1));
            h.assertTrue(s.payPlayer(p,id,Purpose.CONSTRUCTION,REFERENCE,1).state()==State.PAID && f.value()==9,"Ruler path spends actual coins");
            f.village.addReputation(p.getUUID(),-f.village.getCombinedReputation(f.level,p.getUUID()));
            refused(h,()->s.refundPlayer(p,id)); refused(h,()->s.commitPlayer(p,id));
            h.assertTrue(f.value()==9 && f.journal().load(id).state()==State.PAID,"Lost reputation cannot refund or fulfill the reserved payment"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void writeFailureNeverIssuesPaidReceiptAndRetryDoesNotDoubleDebit(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"write-failure")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var id=UUID.randomUUID();
            refused(h,()->f.service(failSecondFlush(false)).payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1));
            h.assertTrue(f.journal().load(id).state()==State.PREPARED && f.value()==9,"Failed post-mutation persistence retains PREPARED, never PAID");
            h.assertTrue(f.service().payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1).state()==State.PAID && f.value()==9,"After-image retry is not a second debit"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void staleDiskReadBackCannotProvePayment(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"read-back")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var id=UUID.randomUUID();
            var lostWrite=new RoyalTreasuryPersistence() {
                int count;
                @Override public void flush(ServerLevel level,List<BlockPos> positions) { if(++count==1) super.flush(level,positions); }
            };
            refused(h,()->f.service(lostWrite).payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1));
            h.assertTrue(f.journal().load(id).state()==State.PREPARED,"A flush method returning normally with stale disk content cannot issue PAID");
            h.assertTrue(f.service().payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1).state()==State.PAID && f.value()==9,"Real flush and read-back complete the same debit"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unexpectedContentsAndReplacementRequireExplicitRecovery(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"third-image")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var id=UUID.randomUUID();
            refused(h,()->f.service(failSecondFlush(false)).payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1));
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),8));
            h.assertTrue(f.service().payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1).state()==State.RECOVERY_REQUIRED && f.value()==8,"Third image is preserved without minting or silently deleting coins");
            h.assertTrue(f.journal().load(id).recovery().getString("diagnosis").contains("both recorded"),"Quarantine includes physical diagnosis");
            refused(h,()->f.service().payNpc(f.kingdom,UUID.randomUUID(),Purpose.CONSTRUCTION,REFERENCE,1)); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void replacementChestCannotReceiveRefund(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"replacement")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var id=UUID.randomUUID(); var s=f.service();
            s.payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1);
            f.level.setBlockAndUpdate(f.position,Blocks.AIR.defaultBlockState()); f.level.setBlockAndUpdate(f.position,Blocks.CHEST.defaultBlockState());
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),9));
            h.assertTrue(s.refundNpc(f.kingdom,id).state()==State.RECOVERY_REQUIRED && f.value()==9,"Same-position replacement with identical coins cannot inherit refund identity"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void fullRefundWaitsWithoutCreatingOrLosingMoney(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"full-refund")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),1)); var id=UUID.randomUUID(); var s=f.service();
            s.payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1);
            for(int i=0;i<27;i++) f.chest().setItem(i,new ItemStack(Items.DIRT,64));
            refused(h,()->s.refundNpc(f.kingdom,id));
            h.assertTrue(f.value()==0 && f.journal().load(id).state()==State.PAID,"Full storage preserves outstanding refund rather than silently cancelling it");
            f.chest().setItem(4,ItemStack.EMPTY);
            h.assertTrue(s.refundNpc(f.kingdom,id).state()==State.REFUNDED && f.value()==1,"Refund succeeds once real capacity is available"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualJournalLockRejectsPaymentBeforeMutation(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"lock")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var s=f.service();
            try(var channel=FileChannel.open(f.directory.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()) {
                refused(h,()->s.payNpc(f.kingdom,UUID.randomUUID(),Purpose.CONSTRUCTION,REFERENCE,1));
                h.assertTrue(f.value()==10,"Actual contending file lock prevents any debit");
            }
            h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void completedRequestReplaySurvivesAnotherOutstandingExpense(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"terminal-replay")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var old=UUID.randomUUID(); var s=f.service();
            s.payNpc(f.kingdom,old,Purpose.CONSTRUCTION,REFERENCE,1); s.refundNpc(f.kingdom,old);
            s.payNpc(f.kingdom,UUID.randomUUID(),Purpose.CONSTRUCTION,REFERENCE,1);
            h.assertTrue(s.payNpc(f.kingdom,old,Purpose.CONSTRUCTION,REFERENCE,1).state()==State.REFUNDED
                    && s.refundNpc(f.kingdom,old).state()==State.REFUNDED && f.value()==9,"Old terminal replay returns its receipt without changing or blocking a new expense"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void savedCoinsWithoutJournalAcknowledgementAreNotReportedPaid(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"ack-failure")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var id=UUID.randomUUID();
            var held=new java.util.concurrent.atomic.AtomicReference<FileChannel>();
            try {
                var interrupted=new RoyalTreasuryPersistence() {
                    int count;
                    @Override public void flush(ServerLevel level,List<BlockPos> positions) {
                        super.flush(level,positions);
                        if(++count==2) try {
                            var channel=FileChannel.open(f.directory.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                            held.set(channel); channel.lock(); // Real journal transition fails after acknowledged physical writes.
                        } catch(java.io.IOException e) { throw new IllegalStateException(e); }
                    }
                };
                refused(h,()->f.service(interrupted).payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1));
            } finally { if(held.get()!=null) held.get().close(); }
            h.assertTrue(f.journal().load(id).state()==State.PREPARED && f.value()==9,"Actual failed journal acknowledgement retains durable debit intent");
            h.assertTrue(f.service().payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1).state()==State.PAID && f.value()==9,"Same request reconciles without charging saved coins again"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void refundWriteFailureCannotMintTheRefundTwice(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"refund-failure")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var id=UUID.randomUUID();
            f.service().payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1);
            refused(h,()->f.service(failSecondFlush(true)).refundNpc(f.kingdom,id));
            h.assertTrue(f.journal().load(id).state()==State.REFUND_PREPARED && f.value()==10,"Saved refund without completion flag remains explicitly pending");
            h.assertTrue(f.service().refundNpc(f.kingdom,id).state()==State.REFUNDED && f.value()==10,"Retry confirms existing refund, never creates a second one"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void lostGovernmentCannotResumePreparedNpcDebit(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"prepared-authority")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var id=UUID.randomUUID();
            refused(h,()->f.service(failSecondFlush(false)).payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1));
            var data=KingdomSavedData.get(f.level); var ruler=UUID.randomUUID(); data.claimRule(f.kingdom,ruler,"New ruler",1);
            refused(h,()->f.service().payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1));
            h.assertTrue(f.value()==9 && f.journal().load(id).state()==State.PREPARED,"Changed government cannot issue a paid receipt or discard the unresolved debit"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unacknowledgedBeforeImageCanBeResumedWithoutFreeExpense(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"before-image")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var id=UUID.randomUUID();
            refused(h,()->f.service(failSecondFlush(false)).payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1));
            // Model the process losing unsaved memory. This exact before image is what the initial barrier saved.
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10));
            new RoyalTreasuryPersistence().flush(f.level,List.of(f.position));
            h.assertTrue(f.service().payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1).state()==State.PAID && f.value()==9,"Before-image recovery actually removes coins before issuing PAID"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void cancellationOfPreparedAfterImageReturnsOnlyOriginalPayment(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"cancel-after")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var id=UUID.randomUUID();
            refused(h,()->f.service(failSecondFlush(true)).payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1));
            h.assertTrue(f.service().refundNpc(f.kingdom,id).state()==State.REFUNDED && f.value()==10,"Cancellation reconciles prepared debit and returns exactly its one denier");
            f.service().refundNpc(f.kingdom,id);
            h.assertTrue(f.value()==10 && f.service().payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1).state()==State.REFUNDED,"Cancelled request cannot charge/refund again"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void cancellationOfPreparedBeforeImageDoesNotCreateAFreeRefund(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"cancel-before")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); var id=UUID.randomUUID();
            refused(h,()->f.service(failSecondFlush(false)).payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,1));
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10)); // Discard the unsaved debit, as at the tested before-write crash boundary.
            new RoyalTreasuryPersistence().flush(f.level,List.of(f.position));
            h.assertTrue(f.service().refundNpc(f.kingdom,id).state()==State.REFUNDED && f.value()==10,"Unpersisted debit cancellation has zero net effect, never credits an unpaid denier"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void preparedDebitWithIndependentlySavedChunksRecoversAfterJvmRestart(GameTestHelper h) throws Exception {
        restart(h,"partial-debit",false,false);
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void durableDebitBeforePaidFlagRecoversAfterJvmRestart(GameTestHelper h) throws Exception {
        restart(h,"durable-debit",false,true);
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void durableRefundBeforeRefundedFlagRecoversAfterJvmRestart(GameTestHelper h) throws Exception {
        restart(h,"durable-refund",true,true);
    }

    private static void restart(GameTestHelper h,String name,boolean refund,boolean saveAfter) throws Exception {
        var level=h.getLevel(); var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-payment-"+name+".dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap()); var id=tag.getUUID("id"); var kingdom=tag.getUUID("kingdom");
            var first=BlockPos.of(tag.getLong("first")); var second=BlockPos.of(tag.getLong("second"));
            var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(KingdomSavedData.get(level).kingdom(kingdom).capital()));
            h.assertTrue(village!=null && village.getTownhall().getInventory()!=null,"Original plan metadata rehydrates real storage without repairing the fixture after reload");
            for(var position:village.getTownhall().getInventory().getChestPositions()) level.getChunkAt(position);
            level.getChunkAt(first); level.getChunkAt(second); // Fixture controls loading; the production service never loads chunks.
            var journal=new RoyalPaymentJournal(Path.of(tag.getString("directory")));
            h.assertTrue(journal.load(id).state()==(refund ? State.REFUND_PREPARED : State.PREPARED),"Previous JVM left the intended unresolved financial state");
            var service=service(level,Path.of(tag.getString("directory")),new RoyalTreasuryPersistence());
            var receipt=refund ? service.refundNpc(kingdom,id) : service.payNpc(kingdom,id,Purpose.CONSTRUCTION,REFERENCE,64);
            long expected=refund ? 128 : 64;
            h.assertTrue(receipt.state()==(refund ? State.REFUNDED : State.PAID) && value((ChestBlockEntity)level.getBlockEntity(first),(ChestBlockEntity)level.getBlockEntity(second))==expected,
                    "Mixed/all-after real chunk images reconcile once after separate-JVM restart");
            if(!refund) service.commitNpc(kingdom,id);
            System.out.println("ROYAL_PAYMENT_RESTART_READ_PASS "+name); h.succeed(); return;
        }
        var f=persistentFixture(h,"restart-"+name); // Original plan metadata, retained for genuine engine rehydration.
        var second=f.village.getTownhall().getInventory().getChestPositions().stream()
                .filter(p->!new net.minecraft.world.level.ChunkPos(p).equals(new net.minecraft.world.level.ChunkPos(f.position))).findFirst().orElseThrow();
        f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),32));
        var other=(ChestBlockEntity)level.getBlockEntity(second); other.setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get()));
        other.setItem(1,new ItemStack(ModItems.DENIER.get(),32));
        var id=UUID.randomUUID();
        if(refund) {
            f.service().payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,64);
            refused(h,()->f.service(failSecondFlush(true)).refundNpc(f.kingdom,id));
        } else {
            refused(h,()->f.service(failSecondFlush(saveAfter)).payNpc(f.kingdom,id,Purpose.CONSTRUCTION,REFERENCE,64));
            if(!saveAfter) {
                // Model a process losing one chunk's write: one physical half is before, the other is after.
                // Persist this independent ordering and reopen it in the next JVM; never claim it is a SIGKILL test.
                other.setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get())); other.setChanged();
                other.setItem(1,new ItemStack(ModItems.DENIER.get(),32));
                new RoyalTreasuryPersistence().flush(level,List.of(second));
            }
        }
        var tag=new CompoundTag(); tag.putUUID("id",id); tag.putUUID("kingdom",f.kingdom); tag.putLong("first",f.position.asLong()); tag.putLong("second",second.asLong());
        tag.putString("directory",f.directory.toString()); NbtIo.writeCompressed(tag,marker);
        VillageSavedData.get(level).setDirty(); KingdomSavedData.get(level).setDirty();
        h.assertTrue(f.journal().load(id).state()==(refund ? State.REFUND_PREPARED : State.PREPARED),"Write JVM leaves intent ahead of completion flag");
        System.out.println("ROYAL_PAYMENT_RESTART_WRITE_PASS "+name); h.succeed();
    }

    static RoyalPaymentService service(ServerLevel level,Path directory,RoyalTreasuryPersistence persistence) {
        try {
            var constructor=RoyalPaymentService.class.getDeclaredConstructor(ServerLevel.class,Path.class,RoyalTreasuryPersistence.class);
            constructor.setAccessible(true); return constructor.newInstance(level,directory,persistence);
        } catch(ReflectiveOperationException e) { throw new IllegalStateException("Cannot create isolated payment test service",e); }
    }

    private static RoyalTreasuryPersistence failSecondFlush(boolean persist) {
        return new RoyalTreasuryPersistence() {
            int count;
            @Override public void flush(ServerLevel level,List<BlockPos> positions) {
                if(++count==2) { if(persist) super.flush(level,positions); throw new IllegalStateException("Injected interruption at physical save boundary"); }
                super.flush(level,positions);
            }
        };
    }

    record Fixture(ServerLevel level,Village village,UUID kingdom,BlockPos position,Path directory) implements AutoCloseable {
        ChestBlockEntity chest() { return (ChestBlockEntity)level.getBlockEntity(position); }
        long value() { return RoyalPaymentGameTests.value(chest()); }
        RoyalPaymentJournal journal() { return new RoyalPaymentJournal(directory); }
        RoyalPaymentService service() { return service(new RoyalTreasuryPersistence()); }
        RoyalPaymentService service(RoyalTreasuryPersistence persistence) { return RoyalPaymentGameTests.service(level,directory,persistence); }
        @Override public void close() { VillageSavedData.get(level).getVillageManager().removeVillage(village.getId()); }
    }
    private static Fixture fixture(GameTestHelper h,String name) throws Exception {
        var level=h.getLevel(); var pos=new BlockPos(("read".equals(System.getProperty("kingdoms.tests.persistence")) ? 110_000 : 10_000)+NEXT.incrementAndGet()*64,-50,20_000);
        level.getChunkAt(pos); level.setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState());
        var type=ModCultures.getAllVillageTypes().values().stream().filter(t->!t.loneBuilding()).findFirst().orElseThrow();
        var village=new Village(VillageId.random(),type.culture(),type.id(),pos); village.setVillageName("Royal "+name);
        var townhall=new BuildingInstance(BuildingId.random(),type.id(),pos,Rotation.NONE,BuildingInstance.Status.COMPLETE,null,"a",0);
        village.addBuilding(townhall); inventory(village,List.of(pos)); VillageSavedData.get(level).getVillageManager().addVillage(village);
        var k=KingdomSavedData.get(level).create("Royal "+name+" "+UUID.randomUUID().toString().substring(0,8),village.getId().uuid());
        var path=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-payment-tests").resolve(UUID.randomUUID().toString());
        return new Fixture(level,village,k.id(),pos,path.toAbsolutePath());
    }

    static Fixture persistentFixture(GameTestHelper h,String name) {
        var level=h.getLevel();
        var plan=ModCultures.getAllBuildingPlans().values().stream()
                .filter(p->p.getPointsByType(SpecialPoint.CHEST).stream().map(s->s.pos().getX()).distinct().count()>1)
                .sorted(java.util.Comparator.comparing(p->p.id().toString())).findFirst().orElseThrow();
        int minX=plan.getPointsByType(SpecialPoint.CHEST).stream().mapToInt(p->p.pos().getX()).min().orElseThrow();
        var origin=new BlockPos(("read".equals(System.getProperty("kingdoms.tests.persistence")) ? 110_000 : 10_000)+NEXT.incrementAndGet()*128+15-minX,-50,24_000);
        var type=ModCultures.getAllVillageTypes().values().stream().filter(t->!t.loneBuilding() && t.culture().equals(plan.culture())).findFirst().orElseThrow();
        var village=new Village(VillageId.random(),type.culture(),type.id(),origin); village.setVillageName("Royal "+name);
        var townhall=new BuildingInstance(BuildingId.random(),plan.id(),origin,Rotation.NONE,BuildingInstance.Status.COMPLETE);
        townhall.resolveSpecialPoints(plan); townhall.initInventory(); village.addBuilding(townhall);
        var positions=townhall.getInventory().getChestPositions();
        for(var position:positions) { level.getChunkAt(position); level.setBlock(position,Blocks.CHEST.defaultBlockState(),2); }
        var first=positions.getFirst();
        if(positions.stream().noneMatch(p->!new net.minecraft.world.level.ChunkPos(p).equals(new net.minecraft.world.level.ChunkPos(first)))) {
            throw new IllegalStateException("Persistent fixture must span independently saved chunks");
        }
        VillageSavedData.get(level).getVillageManager().addVillage(village);
        var kingdom=KingdomSavedData.get(level).create("Royal "+name+" "+UUID.randomUUID().toString().substring(0,8),village.getId().uuid());
        var path=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-payment-tests").resolve(UUID.randomUUID().toString());
        System.out.println("ROYAL_PERSISTENT_PLAN "+plan.id()+" chests="+positions);
        return new Fixture(level,village,kingdom.id(),first,path.toAbsolutePath());
    }
    private static void inventory(Village village,List<BlockPos> positions) throws Exception {
        Field field=BuildingInstance.class.getDeclaredField("inventory"); field.setAccessible(true); field.set(village.getTownhall(),new BuildingInventory(positions));
    }
    private static long value(ChestBlockEntity... chests) {
        var slots=new ArrayList<ItemStack>(); for(var chest:chests) for(int i=0;i<chest.getContainerSize();i++) slots.add(chest.getItem(i));
        return RoyalCoinPlan.spendableValue(slots);
    }
    private static void refused(GameTestHelper h,Runnable action) {
        boolean refused=false; try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { refused=true; }
        h.assertTrue(refused,"Invalid or interrupted payment must not return a successful receipt");
    }
}
