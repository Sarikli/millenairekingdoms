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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;

/** Actual native inventory interference after an acknowledged physical write but before its journal acknowledgement. */
@PrefixGameTestTemplate(false)
public final class RoyalReceiptGameTests {
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
}
