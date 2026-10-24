package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalPaymentJournal;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real atomic journal files, including separate-JVM recovery; no inventory atomicity claims. */
@PrefixGameTestTemplate(false)
public final class RoyalJournalGameTests {
    private static final String NS="millenairekingdoms_royal_economy_tests";
    private static CompoundTag intent(long amount) {
        var tag=new CompoundTag(); tag.putUUID("kingdom",new UUID(1,2)); tag.putLong("amount",amount);
        tag.putString("purpose","construction"); return tag;
    }
    private static Path directory(GameTestHelper h) {
        return h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("royal-journal-tests").resolve(UUID.randomUUID().toString());
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void replayCannotResetPaidOrRefundedState(GameTestHelper h) {
        var journal=new RoyalPaymentJournal(directory(h)); var id=UUID.randomUUID(); var request=intent(64); var data=new CompoundTag();
        journal.prepare(id,request,data); journal.transition(id,State.PREPARED,State.PAID,data);
        h.assertTrue(journal.prepare(id,request,new CompoundTag()).state()==State.PAID,"Identical request does not charge/reset again");
        journal.transition(id,State.PAID,State.REFUND_PREPARED,data); journal.transition(id,State.REFUND_PREPARED,State.REFUNDED,data);
        h.assertTrue(journal.prepare(id,request,data).state()==State.REFUNDED,"Refunded request never becomes payable again"); h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void conflictingRequestCannotOverwriteOriginalIntent(GameTestHelper h) {
        var journal=new RoyalPaymentJournal(directory(h)); var id=UUID.randomUUID(); var request=intent(64);
        journal.prepare(id,request,new CompoundTag()); rejected(h,()->journal.prepare(id,intent(65),new CompoundTag()));
        var changed=request.copy(); changed.putString("purpose","shipment"); rejected(h,()->journal.prepare(id,changed,new CompoundTag()));
        h.assertTrue(journal.load(id).intent().equals(request),"Original amount/kingdom/purpose binding survives conflicts"); h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void invalidStateChangesCannotInventPaymentOrRefund(GameTestHelper h) {
        var journal=new RoyalPaymentJournal(directory(h)); var id=UUID.randomUUID(); var data=new CompoundTag();
        journal.prepare(id,intent(64),data);
        rejected(h,()->journal.transition(id,State.PREPARED,State.REFUNDED,data));
        journal.transition(id,State.PREPARED,State.RECOVERY_REQUIRED,data);
        rejected(h,()->journal.transition(id,State.RECOVERY_REQUIRED,State.PAID,data));
        h.assertTrue(journal.load(id).state()==State.RECOVERY_REQUIRED,"Ambiguous state is not silently treated as paid"); h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void callerCannotMutateFrozenJournalData(GameTestHelper h) {
        var journal=new RoyalPaymentJournal(directory(h)); var id=UUID.randomUUID(); var data=new CompoundTag(); data.putLong("before",4096);
        var request=intent(1); var entry=journal.prepare(id,request,data);
        request.putLong("amount",999); data.putLong("before",0); entry.intent().putLong("amount",100); entry.recovery().putLong("before",10);
        h.assertTrue(entry.intent().getLong("amount")==1 && entry.recovery().getLong("before")==4096,"Returned entry owns defensive snapshots");
        h.assertTrue(journal.load(id).intent().getLong("amount")==1,"On-disk intent cannot be changed by reference mutation"); h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void futureOrCorruptJournalIsPreserved(GameTestHelper h) throws Exception {
        var path=directory(h); var journal=new RoyalPaymentJournal(path); var id=UUID.randomUUID(); var file=path.resolve(id+".dat");
        var future=new CompoundTag(); future.putInt("format",999); future.putString("futureData","must survive"); NbtIo.writeCompressed(future,file);
        var bytes=Files.readAllBytes(file); rejected(h,()->journal.prepare(id,intent(1),new CompoundTag()));
        h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(file)),"Future data never overwritten with a fresh transaction");
        Files.write(file,new byte[]{1,2,3}); bytes=Files.readAllBytes(file); rejected(h,()->journal.load(id));
        h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(file)),"Corrupt journal preserved for diagnosis"); h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void preparedAndPaidRecordsReopenInSeparateJvm(GameTestHelper h) {
        var path=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("royal-journal-restart");
        var journal=new RoyalPaymentJournal(path); var prepared=new UUID(40,1); var paid=new UUID(40,2); var data=new CompoundTag();
        data.putString("snapshotEvidence","before/after must be reconciled by future coordinator");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            h.assertTrue(journal.load(prepared).state()==State.PREPARED && journal.load(paid).state()==State.PAID,"Prepared intent and paid state survive JVM replacement");
            h.assertTrue(journal.prepare(paid,intent(64),data).state()==State.PAID,"Restart replay cannot reset paid state");
            System.out.println("ROYAL_JOURNAL_RESTART_READ_PASS");
        } else {
            journal.prepare(prepared,intent(1),data); journal.prepare(paid,intent(64),data);
            journal.transition(paid,State.PREPARED,State.PAID,data);
            System.out.println("ROYAL_JOURNAL_RESTART_WRITE_PASS");
        }
        h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void busyJournalCannotAcceptASecondWriter(GameTestHelper h) throws Exception {
        var path=directory(h); var journal=new RoyalPaymentJournal(path); var id=UUID.randomUUID();
        try(var channel=FileChannel.open(path.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.lock()) {
            rejected(h,()->journal.prepare(id,intent(1),new CompoundTag()));
            h.assertTrue(!Files.exists(path.resolve(id+".dat")),"Contending writer cannot create/overwrite a payment");
        }
        h.assertTrue(journal.prepare(id,intent(1),new CompoundTag()).state()==State.PREPARED,
                "Retry works once the actual filesystem lock is released"); h.succeed();
    }

    private static void rejected(GameTestHelper h,Runnable action) {
        boolean rejected=false; try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}
        h.assertTrue(rejected,"Invalid or ambiguous journal operation must fail closed");
    }
}
