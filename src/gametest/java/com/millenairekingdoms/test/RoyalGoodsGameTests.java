package com.millenairekingdoms.test;

import com.millenairekingdoms.shipping.RoyalGoodsPlan;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Pure stack plans and actual saved chest images. This is NOT a shipment, authorization or durable escrow test. */
@PrefixGameTestTemplate(false)
public final class RoyalGoodsGameTests {
    private static final String NS="millenairekingdoms_royal_goods_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void exactRemovalPreservesAllOtherGoodsAndOriginalInputs(GameTestHelper h) {
        var source=List.of(new ItemStack(Items.SPRUCE_LOG,32),new ItemStack(Items.DIAMOND,3),new ItemStack(Items.SPRUCE_LOG,50));
        var plan=RoyalGoodsPlan.remove(source,new ItemStack(Items.SPRUCE_LOG),40);
        h.assertTrue(source.get(0).getCount()==32 && source.get(2).getCount()==50 && plan.after().get(0).isEmpty()
                && ItemStack.matches(plan.after().get(1),source.get(1)) && plan.after().get(2).getCount()==42
                && plan.quantity()==40 && count(plan.cargo(),Items.SPRUCE_LOG)==40,"Exact quantity is removed in slot order without touching original or unrelated goods");h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void insertionUsesActualStackCapacityAndPreservesNoncargoSlots(GameTestHelper h) {
        var target=List.of(new ItemStack(Items.SPRUCE_LOG,60),new ItemStack(Items.DIAMOND,2),ItemStack.EMPTY);
        var cargo=List.of(new ItemStack(Items.SPRUCE_LOG,40));var plan=RoyalGoodsPlan.insert(target,cargo);
        h.assertTrue(target.get(0).getCount()==60 && target.get(2).isEmpty() && cargo.getFirst().getCount()==40
                && plan.after().get(0).getCount()==64 && plan.after().get(2).getCount()==36 && ItemStack.matches(plan.after().get(1),target.get(1)),
                "Complete insertion plan respects per-stack capacity and preserves all unrelated stock before any world mutation");h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void insufficientStockAndCapacityNeverReturnPartialPlans(GameTestHelper h) {
        var source=List.of(new ItemStack(Items.SPRUCE_LOG,12));var full=List.of(new ItemStack(Items.STONE,64));
        refused(h,()->RoyalGoodsPlan.remove(source,new ItemStack(Items.SPRUCE_LOG),13));
        refused(h,()->RoyalGoodsPlan.insert(full,List.of(new ItemStack(Items.SPRUCE_LOG,1))));
        var almost=List.of(new ItemStack(Items.SPRUCE_LOG,63));refused(h,()->RoyalGoodsPlan.insert(almost,List.of(new ItemStack(Items.SPRUCE_LOG,2))));
        h.assertTrue(source.getFirst().getCount()==12 && full.getFirst().is(Items.STONE) && full.getFirst().getCount()==64 && almost.getFirst().getCount()==63,
                "All-or-none planning failure preserves stock and cannot leak a partial removal/insertion");h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void componentDistinctProductsNeverSubstituteOrMerge(GameTestHelper h) {
        var named=new ItemStack(Items.SPRUCE_LOG,5);named.set(DataComponents.CUSTOM_NAME,Component.literal("Retained cargo identity"));
        var other=new ItemStack(Items.SPRUCE_LOG,63);other.set(DataComponents.CUSTOM_NAME,Component.literal("Different cargo identity"));
        var source=List.of(new ItemStack(Items.SPRUCE_LOG,40),named);var removed=RoyalGoodsPlan.remove(source,named,2);
        var inserted=RoyalGoodsPlan.insert(List.of(other,ItemStack.EMPTY),removed.cargo());
        h.assertTrue(removed.after().get(0).getCount()==40 && removed.after().get(1).getCount()==3 && ItemStack.matches(inserted.after().get(0),other)
                && inserted.after().get(1).getCount()==2 && ItemStack.isSameItemSameComponents(inserted.after().get(1),named),
                "Exact native components survive cargo planning; ordinary/differently named goods cannot be consumed or silently merged");h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void planSnapshotsHaveNoMutableAliasesAndRejectStaleInputs(GameTestHelper h) {
        var stack=new ItemStack(Items.SPRUCE_LOG,64);var source=new ArrayList<>(List.of(stack));var plan=RoyalGoodsPlan.remove(source,new ItemStack(Items.SPRUCE_LOG),40);
        stack.setCount(1);plan.after().getFirst().setCount(1);plan.cargo().getFirst().setCount(1);plan.before().getFirst().setCount(1);
        h.assertTrue(plan.before().getFirst().getCount()==64 && plan.after().getFirst().getCount()==24 && plan.cargo().getFirst().getCount()==40
                && !plan.matchesBefore(source) && plan.matchesBefore(List.of(new ItemStack(Items.SPRUCE_LOG,64))),"Caller/input/output stack edits cannot alter the immutable plan or make a stale inventory current");
        boolean fixed=false;try{plan.after().clear();}catch(UnsupportedOperationException expected){fixed=true;}h.assertTrue(fixed,"Returned slot lists are immutable");h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void invalidQuantitiesOverstacksAndUnstackableCapacityFailClosed(GameTestHelper h) {
        var source=List.of(new ItemStack(Items.SPRUCE_LOG,64));
        for(int n:new int[]{0,-1,Integer.MAX_VALUE})refused(h,()->RoyalGoodsPlan.remove(source,new ItemStack(Items.SPRUCE_LOG),n));
        refused(h,()->RoyalGoodsPlan.remove(source,ItemStack.EMPTY,1));refused(h,()->RoyalGoodsPlan.insert(source,List.of()));
        refused(h,()->RoyalGoodsPlan.insert(source,List.of(ItemStack.EMPTY)));refused(h,()->RoyalGoodsPlan.remove(List.of(new ItemStack(Items.SPRUCE_LOG,65)),new ItemStack(Items.SPRUCE_LOG),1));
        var sword=new ItemStack(Items.IRON_SWORD);refused(h,()->RoyalGoodsPlan.insert(List.of(ItemStack.EMPTY),List.of(sword,sword)));
        var plan=RoyalGoodsPlan.insert(List.of(ItemStack.EMPTY,ItemStack.EMPTY),List.of(sword,sword));
        h.assertTrue(plan.after().get(0).getCount()==1 && plan.after().get(1).getCount()==1 && plan.quantity()==2 && source.getFirst().getCount()==64,
                "Native unstackable capacity is respected, malformed plans do not mutate stock");h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void deterministicSlotCasesConserveSelectedAndUnrelatedGoods(GameTestHelper h) {
        var random=new java.util.Random(12004);
        for(int sample=0;sample<100;sample++) {
            int amount=1+random.nextInt(64);var source=List.of(new ItemStack(Items.SPRUCE_LOG,64),new ItemStack(Items.SPRUCE_LOG,1+random.nextInt(64)),new ItemStack(Items.DIAMOND,3));
            var destination=List.of(new ItemStack(Items.SPRUCE_LOG,random.nextInt(20)+1),ItemStack.EMPTY,new ItemStack(Items.EMERALD,4));
            var debit=RoyalGoodsPlan.remove(source,new ItemStack(Items.SPRUCE_LOG),amount);var credit=RoyalGoodsPlan.insert(destination,debit.cargo());
            h.assertTrue(count(source,Items.SPRUCE_LOG)+count(destination,Items.SPRUCE_LOG)==count(debit.after(),Items.SPRUCE_LOG)+count(credit.after(),Items.SPRUCE_LOG)
                    && count(debit.after(),Items.DIAMOND)==3 && count(credit.after(),Items.EMERALD)==4 && debit.quantity()==credit.quantity(),
                    "Seeded slot combinations conserve exact goods without changing unrelated items: "+sample);
        }
        h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualChestPlanImagesReopenInSeparateJvm(GameTestHelper h) throws Exception {
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-goods-plan.dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024));var a=BlockPos.of(tag.getLong("source"));var b=BlockPos.of(tag.getLong("destination"));level.getChunkAt(a);level.getChunkAt(b);
            var source=(ChestBlockEntity)level.getBlockEntity(a);var destination=(ChestBlockEntity)level.getBlockEntity(b);
            h.assertTrue(source!=null && destination!=null && source.getItem(0).is(Items.SPRUCE_LOG) && source.getItem(0).getCount()==24
                    && destination.getItem(0).is(Items.SPRUCE_LOG) && destination.getItem(0).getCount()==44
                    && source.getItem(1).is(Items.DIAMOND) && source.getItem(1).getCount()==3 && destination.getItem(1).is(Items.EMERALD) && destination.getItem(1).getCount()==4,
                    "Actual saved source/destination chest images retain conserved 68 logs and unrelated goods in another JVM");
            System.out.println("ROYAL_GOODS_PLAN_RESTART_READ_PASS source=24 destination=44 total=68");h.succeed();return;
        }
        var a=new BlockPos(400_000,-50,300_000);var b=a.offset(32,0,0);level.getChunkAt(a);level.getChunkAt(b);level.setBlock(a,Blocks.CHEST.defaultBlockState(),2);level.setBlock(b,Blocks.CHEST.defaultBlockState(),2);
        var source=(ChestBlockEntity)level.getBlockEntity(a);var destination=(ChestBlockEntity)level.getBlockEntity(b);
        source.setItem(0,new ItemStack(Items.SPRUCE_LOG,64));source.setItem(1,new ItemStack(Items.DIAMOND,3));destination.setItem(0,new ItemStack(Items.SPRUCE_LOG,4));destination.setItem(1,new ItemStack(Items.EMERALD,4));
        var debit=RoyalGoodsPlan.remove(slots(source),new ItemStack(Items.SPRUCE_LOG),40);var credit=RoyalGoodsPlan.insert(slots(destination),debit.cargo());
        h.assertTrue(debit.matchesBefore(slots(source)) && credit.matchesBefore(slots(destination)),"Plans match actual untouched physical chests before explicit test-only application");
        apply(source,debit.after());apply(destination,credit.after());new RoyalTreasuryPersistence().flush(level,List.of(a,b));
        var tag=new CompoundTag();tag.putLong("source",a.asLong());tag.putLong("destination",b.asLong());NbtIo.writeCompressed(tag,marker);
        System.out.println("ROYAL_GOODS_PLAN_RESTART_WRITE_PASS source=24 destination=44 total=68");h.succeed();
    }

    private static List<ItemStack> slots(ChestBlockEntity chest) {var result=new ArrayList<ItemStack>();for(int i=0;i<chest.getContainerSize();i++)result.add(chest.getItem(i).copy());return result;}
    private static void apply(ChestBlockEntity chest,List<ItemStack> slots) {for(int i=0;i<slots.size();i++)chest.setItem(i,slots.get(i).copy());chest.setChanged();}
    private static int count(List<ItemStack> stacks,net.minecraft.world.item.Item item) {return stacks.stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();}
    private static void refused(GameTestHelper h,Runnable action) {boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,"Invalid or incomplete cargo plan must fail before mutation");}
}
