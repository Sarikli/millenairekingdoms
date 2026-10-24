package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalCoinPlan;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;

/** Real registered currency and chest fixtures. These tests do not claim durable payment/replay semantics. */
@PrefixGameTestTemplate(false)
public final class RoyalCoinGameTests {
    private static final String NS = "millenairekingdoms_royal_economy_tests";

    @GameTest(templateNamespace=NS, template="empty", timeoutTicks=200)
    public static void goldPaysOneDenierWithExactChange(GameTestHelper h) {
        var slots = List.of(new ItemStack(ModItems.DENIER_OR.get()), ItemStack.EMPTY);
        var plan = RoyalCoinPlan.debit(slots, 1);
        h.assertTrue(plan.beforeValue() == 4096 && plan.afterValue() == 4095, "4096 minus one");
        h.assertTrue(count(plan.after(), ModItems.DENIER_ARGENT.get()) == 63
                && count(plan.after(), ModItems.DENIER.get()) == 63, "Exact 63 silver + 63 bronze change");
        h.assertTrue(slots.getFirst().getCount() == 1, "Planning does not mutate stock");
        h.succeed();
    }

    @GameTest(templateNamespace=NS, template="empty", timeoutTicks=200)
    public static void mixedCoinsPreserveGoodsAndValue(GameTestHelper h) {
        var slots = List.of(new ItemStack(ModItems.DENIER_OR.get(),2), new ItemStack(ModItems.DENIER_ARGENT.get(),3),
                new ItemStack(ModItems.DENIER.get(),10), new ItemStack(Items.DIAMOND,7));
        var plan = RoyalCoinPlan.debit(slots,4200);
        h.assertTrue(plan.beforeValue() == 8394 && plan.afterValue() == 4194, "Mixed denominations conserve value");
        h.assertTrue(RoyalCoinPlan.spendableValue(plan.after()) == 4194
                && ItemStack.matches(slots.get(3),plan.after().get(3)), "Goods remain in their slot");
        h.succeed();
    }

    @GameTest(templateNamespace=NS, template="empty", timeoutTicks=200)
    public static void invalidAndInsufficientAmountsDoNotAlterStock(GameTestHelper h) {
        var slots=List.of(new ItemStack(ModItems.DENIER.get(),10));
        rejected(h,()->RoyalCoinPlan.debit(slots,0)); rejected(h,()->RoyalCoinPlan.debit(slots,-1));
        rejected(h,()->RoyalCoinPlan.debit(slots,11)); rejected(h,()->RoyalCoinPlan.debit(slots,Long.MAX_VALUE));
        rejected(h,()->RoyalCoinPlan.credit(slots,Long.MAX_VALUE));
        h.assertTrue(slots.getFirst().getCount()==10,"Rejected requests do not touch physical stock"); h.succeed();
    }

    @GameTest(templateNamespace=NS, template="empty", timeoutTicks=200)
    public static void fullStorageRejectsUnstorableChange(GameTestHelper h) {
        var slots=new ArrayList<ItemStack>(); slots.add(new ItemStack(ModItems.DENIER_OR.get()));
        for(int i=1;i<27;i++) slots.add(new ItemStack(Items.DIRT,64));
        rejected(h,()->RoyalCoinPlan.debit(slots,1));
        h.assertTrue(RoyalCoinPlan.spendableValue(slots)==4096,"No partial debit on change overflow");
        h.assertTrue(RoyalCoinPlan.debit(slots,4096).after().getFirst().isEmpty(),"Exact payment needs no extra space"); h.succeed();
    }

    @GameTest(templateNamespace=NS, template="empty", timeoutTicks=200)
    public static void refundPlanUsesRealDenominationsAndRejectsFullStorage(GameTestHelper h) {
        var slots=List.of(new ItemStack(ModItems.DENIER.get(),64));
        var plan=RoyalCoinPlan.credit(slots,64);
        h.assertTrue(plan.afterValue()==128 && count(plan.after(),ModItems.DENIER_ARGENT.get())==2,"Refund consolidates actual currency");
        rejected(h,()->RoyalCoinPlan.credit(List.of(new ItemStack(Items.DIRT,64)),1));
        rejected(h,()->RoyalCoinPlan.credit(slots,0)); h.succeed();
    }

    @GameTest(templateNamespace=NS, template="empty", timeoutTicks=200)
    public static void customCoinsAreNotDestroyedForChange(GameTestHelper h) {
        var special=new ItemStack(ModItems.DENIER_OR.get()); special.set(DataComponents.CUSTOM_NAME,Component.literal("Keepsake"));
        var slots=List.of(special,new ItemStack(ModItems.DENIER.get(),2));
        h.assertTrue(RoyalCoinPlan.spendableValue(slots)==2,"Custom-component coins are protected, not spendable loose stock");
        var plan=RoyalCoinPlan.debit(slots,1);
        h.assertTrue(ItemStack.matches(special,plan.after().getFirst()),"Named coin and its components survive unchanged"); h.succeed();
    }

    @GameTest(templateNamespace=NS, template="empty", timeoutTicks=200)
    public static void planSnapshotsCannotBeChangedByCallers(GameTestHelper h) {
        var original=new ItemStack(ModItems.DENIER.get(),10); var plan=RoyalCoinPlan.debit(List.of(original),1);
        original.setCount(1); plan.before().getFirst().setCount(2); plan.after().getFirst().setCount(3);
        h.assertTrue(plan.before().getFirst().getCount()==10 && plan.after().getFirst().getCount()==9,
                "Mutable input/output ItemStacks cannot corrupt the frozen plan"); h.succeed();
    }

    @GameTest(templateNamespace=NS, template="empty", timeoutTicks=200)
    public static void plannedChangeSurvivesInARealChestAfterRestart(GameTestHelper h) throws Exception {
        var level=h.getLevel(); var file=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-coins.properties");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var saved=new Properties(); try(var in=Files.newBufferedReader(file)){saved.load(in);}
            var pos=new BlockPos(Integer.parseInt(saved.getProperty("x")),-50,Integer.parseInt(saved.getProperty("z")));
            level.getChunkAt(pos); var chest=(ChestBlockEntity)level.getBlockEntity(pos);
            h.assertTrue(chest!=null && RoyalCoinPlan.spendableValue(snapshot(chest))==4095,"Saved real change reopens in a separate JVM");
            System.out.println("ROYAL_COINS_RESTART_READ_PASS"); h.succeed(); return;
        }
        var pos=level.getSharedSpawnPos().offset(400,0,400).atY(-50); level.getChunkAt(pos);
        level.setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState()); var chest=(ChestBlockEntity)level.getBlockEntity(pos);
        chest.setItem(0,new ItemStack(ModItems.DENIER_OR.get()));
        var plan=RoyalCoinPlan.debit(snapshot(chest),1); var after=plan.after();
        for(int slot=0;slot<after.size();slot++) chest.setItem(slot,after.get(slot)); chest.setChanged();
        h.assertTrue(chest.countItem(ModItems.DENIER_ARGENT.get())==63 && chest.countItem(ModItems.DENIER.get())==63,"Actual chest contains exact change");
        var saved=new Properties(); saved.setProperty("x",Integer.toString(pos.getX())); saved.setProperty("z",Integer.toString(pos.getZ()));
        try(var out=Files.newBufferedWriter(file)){saved.store(out,"Royal coin planner real-chest persistence");}
        System.out.println("ROYAL_COINS_RESTART_WRITE_PASS"); h.succeed();
    }

    private static List<ItemStack> snapshot(ChestBlockEntity chest) {
        var result=new ArrayList<ItemStack>(); for(int i=0;i<chest.getContainerSize();i++) result.add(chest.getItem(i).copy()); return result;
    }
    private static int count(List<ItemStack> slots,net.minecraft.world.item.Item item) {
        return slots.stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static void rejected(GameTestHelper h,Runnable action) {
        boolean rejected=false; try{action.run();}catch(IllegalArgumentException expected){rejected=true;}
        h.assertTrue(rejected,"Invalid operation must fail before mutation");
    }
}
