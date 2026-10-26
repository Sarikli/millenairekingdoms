package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalPaymentJournal;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.item.Items;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import org.millenaire.village.VillageSavedData;
import org.millenaire.village.VillageId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;

/** Actual native inventory interference after an acknowledged physical write but before its journal acknowledgement. */
@PrefixGameTestTemplate(false)
public final class RoyalReceiptGameTests {
    private static final String KEY="millenairekingdoms:royal_operations";
    private static final String NS="millenairekingdoms_royal_receipt_tests";
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void refundRetryCannotMintMoneyWhenNativeWithdrawalRestoresBeforeImage(GameTestHelper h) throws Exception {
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"receipt-refund-interference")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));var id=UUID.randomUUID();var payments=f.service();
            payments.payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,64);
            var interrupted=new RoyalTreasuryPersistence() {
                int count;
                @Override public void flush(ServerLevel level,List<BlockPos> positions) {
                    super.flush(level,positions);if(++count==2)throw new IllegalStateException("Test acknowledgement loss AFTER actual refund chest save");
                }
            };
            boolean cut=false;try{f.service(interrupted).refundNpc(f.kingdom(),id);}catch(IllegalStateException expected){cut=true;}
            h.assertTrue(cut && f.journal().load(id).state()==RoyalPaymentJournal.State.REFUND_PREPARED && f.value()==128,
                    "Actual refund is physically saved before its deliberately lost journal acknowledgement");
            var outside=f.position().offset(0,0,64);f.level().getChunkAt(outside);f.level().setBlock(outside,Blocks.CHEST.defaultBlockState(),2);
            var recipient=(ChestBlockEntity)f.level().getBlockEntity(outside);
            h.assertTrue(f.village().getTownhall().getInventory().remove(f.level(),ModItems.DENIER_ARGENT.get(),1)==1,"Original native BuildingInventory removes one actual silver coin");
            recipient.setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get()));
            var positions=new ArrayList<>(f.village().getTownhall().getInventory().getChestPositions());positions.add(outside);new RoyalTreasuryPersistence().flush(f.level(),positions);
            h.assertTrue(f.value()==64 && recipient.getItem(0).getCount()==1,"Known native withdrawal conserves128:64 capital +64 recipient, coinciding with the old refund before image");
            var result=payments.refundNpc(f.kingdom(),id);long recipientValue=64L*recipient.getItem(0).getCount();long total=f.value()+recipientValue;
            System.out.println("ROYAL_REFUND_INTERFERENCE_RESULT capital="+f.value()+" recipient="+recipientValue+" total="+total+" expected=128 state="+result.state());
            h.assertTrue(total==128,"Refund replay must not mint another64 after native inventory activity restores before-image counts: actual total="+total);
            h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeDepositRestoringDebitBeforeImageCannotChargeTwice(GameTestHelper h) throws Exception {
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"receipt-debit-deposit")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));var id=UUID.randomUUID();
            interrupted(h,()->f.service(lostAcknowledgement()).payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,64));
            h.assertTrue(f.value()==64 && f.village().getTownhall().getInventory().add(f.level(),ModItems.DENIER_ARGENT.get(),1)==1,"Original native API deposits a real silver after debit");
            new RoyalTreasuryPersistence().flush(f.level(),f.village().getTownhall().getInventory().getChestPositions());
            var result=f.service().payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,64);
            h.assertTrue(result.state()==RoyalPaymentJournal.State.PAID && f.value()==128,"Previously applied debit preserves native deposit even when it restores old before-image counts");
            h.assertTrue(!f.chest().getPersistentData().contains(KEY),"Acknowledged proof is cleaned without losing durable journal history");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void boundReceiptPreservesThirdImageAndUntouchedGoods(GameTestHelper h) throws Exception {
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"receipt-third")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10));var id=UUID.randomUUID();
            interrupted(h,()->f.service(lostAcknowledgement()).payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1));
            f.village().getTownhall().getInventory().remove(f.level(),ModItems.DENIER.get(),2);
            f.chest().setItem(5,new ItemStack(Items.DIAMOND,3));
            var other=(ChestBlockEntity)f.level().getBlockEntity(f.village().getTownhall().getInventory().getChestPositions().getLast());
            other.setItem(3,new ItemStack(Items.EMERALD,4));
            h.assertTrue(f.service().payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1).state()==RoyalPaymentJournal.State.PAID
                    && f.value()==7 && f.chest().getItem(5).getCount()==3 && other.getItem(3).getCount()==4,"Bound proof preserves current changed and untouched halves instead of restoring old coins or goods");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void missingReceiptWithAfterImageQuarantinesWithoutGuessing(GameTestHelper h) throws Exception {
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"receipt-missing")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10));var id=UUID.randomUUID();
            interrupted(h,()->f.service(lostAcknowledgement()).payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1));
            f.chest().getPersistentData().remove(KEY); // Deliberate corrupt fixture, not legitimate inventory activity.
            h.assertTrue(f.service().payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1).state()==RoyalPaymentJournal.State.RECOVERY_REQUIRED
                    && f.value()==9,"An unreceipted after image is ambiguous and must never be inferred paid");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void receiptBindingCollisionPreservesAllEvidence(GameTestHelper h) throws Exception {
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"receipt-binding")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10));var id=UUID.randomUUID();
            interrupted(h,()->f.service(lostAcknowledgement()).payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1));
            var proof=f.chest().getPersistentData().getCompound(KEY);proof.getList("entries",Tag.TAG_COMPOUND).getCompound(0).getCompound("binding").putString("role","REFUND");
            var before=proof.copy();
            h.assertTrue(f.service().payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1).state()==RoyalPaymentJournal.State.RECOVERY_REQUIRED
                    && f.value()==9 && before.equals(f.chest().getPersistentData().get(KEY)),"Same operation UUID with mismatched immutable role cannot apply or erase evidence");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void futureAndMalformedReceiptDataNeverOverwriteOrDebit(GameTestHelper h) throws Exception {
        for(int mode=0;mode<4;mode++)try(var f=RoyalPaymentGameTests.persistentFixture(h,"receipt-unknown-"+mode)) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10));var id=UUID.randomUUID();
            var proof=new CompoundTag();proof.putInt("format",mode==0 ? 999 : 1);var entries=new ListTag();
            if(mode==1)entries.add(net.minecraft.nbt.StringTag.valueOf("wrong-list-type"));
            if(mode==2){var entry=new CompoundTag();entry.putUUID("id",UUID.randomUUID());entry.put("binding",new CompoundTag());entries.add(entry);}
            proof.put("entries",entries);if(mode==3)proof.putString("future","preserve");f.chest().getPersistentData().put(KEY,proof);
            var before=proof.copy();
            h.assertTrue(f.service().payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1).state()==RoyalPaymentJournal.State.RECOVERY_REQUIRED
                    && f.value()==10 && before.equals(f.chest().getPersistentData().get(KEY)),"Unsupported receipt data is preserved and slots remain unchanged mode="+mode);
        }h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void pendingLegacyImagesCannotGuessWhetherMoneyMoved(GameTestHelper h) throws Exception {
        for(boolean before:new boolean[]{true,false})try(var f=RoyalPaymentGameTests.persistentFixture(h,"receipt-legacy-pending-"+before)) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10));var id=UUID.randomUUID();
            interrupted(h,()->f.service(lostAcknowledgement()).payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1));
            legacy(f,id);f.chest().getPersistentData().remove(KEY);if(before)f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10));
            h.assertTrue(f.service().payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1).state()==RoyalPaymentJournal.State.RECOVERY_REQUIRED
                    && f.value()==(before ? 10 : 9),"Legacy pending image retains funds and requires explicit review despite matching old counts");
        }h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void acknowledgedLegacyPaymentCanRefundUsingNewProof(GameTestHelper h) throws Exception {
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"receipt-legacy-paid")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10));var id=UUID.randomUUID();
            f.service().payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1);legacy(f,id);
            h.assertTrue(f.service().refundNpc(f.kingdom(),id).state()==RoyalPaymentJournal.State.REFUNDED && f.value()==10,"Old acknowledged payment keeps its per-chest original refund entitlement with a new durable proof");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void cleanupWriteFailureCannotUndoAcknowledgedPayment(GameTestHelper h) throws Exception {
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"receipt-cleanup")) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10));var id=UUID.randomUUID();
            var persistence=new RoyalTreasuryPersistence(){int count;@Override public void flush(ServerLevel level,List<BlockPos> positions){if(++count==3)throw new IllegalStateException("Deliberate cleanup write failure after PAID ack");super.flush(level,positions);}};
            h.assertTrue(f.service(persistence).payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1).state()==RoyalPaymentJournal.State.PAID
                    && f.journal().load(id).state()==RoyalPaymentJournal.State.PAID && f.value()==9,"Post-ack cleanup failure returns the real PAID result");
            h.assertTrue(f.service().payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1).state()==RoyalPaymentJournal.State.PAID && f.value()==9,"Acknowledged replay never reapplies a saved stale proof");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void mismatchedImageParentAndIntentNeverSettle(GameTestHelper h) throws Exception {
        for(boolean parent:new boolean[]{true,false})try(var f=RoyalPaymentGameTests.persistentFixture(h,"receipt-image-binding-"+parent)) {
            f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),10));var id=UUID.randomUUID();
            interrupted(h,()->f.service(lostAcknowledgement()).payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1));
            var file=f.directory().resolve(id+".dat");var record=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());var image=record.getCompound("recovery").getCompound("debit");
            if(parent)image.putUUID("payment",UUID.randomUUID());else image.getCompound("intent").putLong("amount",2);NbtIo.writeCompressed(record,file);var bytes=Files.readAllBytes(file);
            interrupted(h,()->f.service().payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,1));
            h.assertTrue(f.value()==9 && java.util.Arrays.equals(bytes,Files.readAllBytes(file)),"Journal image binding mismatch preserves actual coins and original evidence");
        }h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void legitimateNativeActivityAfterDebitReceiptSurvivesSeparateJvm(GameTestHelper h) throws Exception { reopen(h,false); }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void legitimateNativeActivityAfterRefundReceiptSurvivesSeparateJvm(GameTestHelper h) throws Exception { reopen(h,true); }

    private static void reopen(GameTestHelper h,boolean refund) throws Exception {
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("native-receipt-"+refund+".dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());var kingdom=tag.getUUID("kingdom");var id=tag.getUUID("id");
            var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(KingdomSavedData.get(level).kingdom(kingdom).capital()));
            h.assertTrue(village!=null && village.getTownhall().getInventory()!=null,"Original saved village rehydrates without repairs");
            for(var pos:village.getTownhall().getInventory().getChestPositions())level.getChunkAt(pos);var position=BlockPos.of(tag.getLong("position"));
            var chest=(ChestBlockEntity)level.getBlockEntity(position);var before=coins(chest);h.assertTrue(before==(refund ? 64 : 128) && chest.getPersistentData().contains(KEY),"Actual native activity and same-BE proof both survived previous JVM");
            var service=RoyalPaymentGameTests.service(level,Path.of(tag.getString("directory")),new RoyalTreasuryPersistence());
            var result=refund ? service.refundNpc(kingdom,id) : service.payNpc(kingdom,id,RoyalPaymentService.Purpose.CONSTRUCTION,id,64);
            h.assertTrue(coins(chest)==before && result.state()==(refund ? RoyalPaymentJournal.State.REFUNDED : RoyalPaymentJournal.State.PAID),"Separate-JVM recovery preserves the legitimate changed count without duplicate debit or refund");
            System.out.println("ROYAL_RECEIPT_NATIVE_REOPEN refund="+refund+" before="+before+" after="+coins(chest)+" state="+result.state());h.succeed();return;
        }
        var f=RoyalPaymentGameTests.persistentFixture(h,"receipt-native-reopen-"+refund);f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));var id=UUID.randomUUID();
        if(refund){f.service().payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,64);interrupted(h,()->f.service(lostAcknowledgement()).refundNpc(f.kingdom(),id));
            h.assertTrue(f.village().getTownhall().getInventory().remove(level,ModItems.DENIER_ARGENT.get(),1)==1,"Native post-refund withdrawal removes an actual silver");}
        else{interrupted(h,()->f.service(lostAcknowledgement()).payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.CONSTRUCTION,id,64));
            h.assertTrue(f.village().getTownhall().getInventory().add(level,ModItems.DENIER_ARGENT.get(),1)==1,"Native post-debit deposit adds an actual silver");}
        new RoyalTreasuryPersistence().flush(level,f.village().getTownhall().getInventory().getChestPositions());
        var tag=new CompoundTag();tag.putUUID("kingdom",f.kingdom());tag.putUUID("id",id);tag.putLong("position",f.position().asLong());tag.putString("directory",f.directory().toString());NbtIo.writeCompressed(tag,marker);
        VillageSavedData.get(level).setDirty();KingdomSavedData.get(level).setDirty();h.succeed();
    }
    private static long coins(ChestBlockEntity chest){var slots=new ArrayList<ItemStack>();for(int i=0;i<chest.getContainerSize();i++)slots.add(chest.getItem(i));return com.millenairekingdoms.economy.RoyalCoinPlan.spendableValue(slots);}
    private static RoyalTreasuryPersistence lostAcknowledgement(){return new RoyalTreasuryPersistence(){int count;@Override public void flush(ServerLevel level,List<BlockPos> positions){super.flush(level,positions);if(++count==2)throw new IllegalStateException("Test acknowledgement loss after actual write");}};}
    private static void interrupted(GameTestHelper h,Runnable action){boolean cut=false;try{action.run();}catch(IllegalStateException expected){cut=true;}h.assertTrue(cut,"Deliberately interrupted or invalid operation must fail");}
    private static void legacy(RoyalPaymentGameTests.Fixture f,UUID id)throws Exception{var file=f.directory().resolve(id+".dat");var record=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());var image=record.getCompound("recovery").getCompound("debit");image.putInt("format",1);image.remove("payment");image.remove("intent");image.remove("role");NbtIo.writeCompressed(record,file);}
}
