package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import com.millenairekingdoms.shipping.RoyalShipmentStore.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;
import com.millenairekingdoms.test.RoyalCargoGameTests.Fixture;

/** Production-path container reservations with actual treasury payments/refunds/withdrawals.
 * The cargo route/fee remains a synthetic low-level fixture; no live timed shipping claim. */
@PrefixGameTestTemplate(false)
public final class RoyalReservationGameTests {
    private static final String NS="millenairekingdoms_royal_shipment_tests";
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void pendingCargoStopsNewExpenseBeforeFinancialIntentOrCoins(GameTestHelper h)throws Exception{
        try(var f=root(h,"reserve-expense")){
            silver(f);var op=RoyalCargoGameTests.plan(f,Role.COLLECT,f.from(),40);f.store().prepare(f.id(),op);var id=UUID.randomUUID();var payment=new RoyalPaymentService(f.level());
            refused(h,()->payment.payNpc(f.nativeFixture().kingdom(),id,RoyalPaymentService.Purpose.ADMINISTRATIVE,id,64));
            h.assertTrue(coins(f)==128 && journal(f).load(id)==null && f.sourceCount()==96,"Unresolved actual cargo container blocks before any monetary intent or debit");
            f.store().returning(f.id());RoyalCargoGameTests.settle(f,op,new RoyalTreasuryPersistence());f.store().finish(f.id());
            h.assertTrue(payment.payNpc(f.nativeFixture().kingdom(),id,RoyalPaymentService.Purpose.ADMINISTRATIVE,id,64).state()==RoyalPaymentJournal.State.PAID && coins(f)==64,"Proved-unperformed cargo cancellation releases physical expense reservation");payment.commitNpc(f.nativeFixture().kingdom(),id);h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void pendingCargoStopsRefundBeforeCreditOrRefundIntent(GameTestHelper h)throws Exception{
        try(var f=root(h,"reserve-refund")){
            silver(f);var id=UUID.randomUUID();var payment=new RoyalPaymentService(f.level());payment.payNpc(f.nativeFixture().kingdom(),id,RoyalPaymentService.Purpose.ADMINISTRATIVE,id,64);
            var op=RoyalCargoGameTests.plan(f,Role.COLLECT,f.from(),40);f.store().prepare(f.id(),op);refused(h,()->payment.refundNpc(f.nativeFixture().kingdom(),id));
            h.assertTrue(coins(f)==64 && journal(f).load(id).state()==RoyalPaymentJournal.State.PAID,"Pending cargo blocks before REFUND_PREPARED or native credit");
            f.store().returning(f.id());RoyalCargoGameTests.settle(f,op,new RoyalTreasuryPersistence());f.store().finish(f.id());
            h.assertTrue(payment.refundNpc(f.nativeFixture().kingdom(),id).state()==RoyalPaymentJournal.State.REFUNDED && coins(f)==128,"Real refund proceeds only after physical cargo reservation is resolved");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void pendingMoneyBlocksCargoPlanningAndPlayerWithdrawalUntilRealReconciliation(GameTestHelper h)throws Exception{
        try(var f=root(h,"reserve-money")){
            var ruler=ruler(f);f.source().setItem(6,new ItemStack(ModItems.DENIER.get(),10));var id=UUID.randomUUID();
            var persistence=new RoyalTreasuryPersistence(){int count;@Override public void flush(ServerLevel level,List<BlockPos> p){super.flush(level,p);if(++count==2)throw new IllegalStateException("Lost payment ack after real native debit/proof save");}};
            refused(h,()->RoyalPaymentGameTests.service(f.level(),moneyDirectory(f),persistence).payPlayer(ruler,id,RoyalPaymentService.Purpose.ADMINISTRATIVE,id,1));
            h.assertTrue(journal(f).load(id).state()==RoyalPaymentJournal.State.PREPARED && coins(f)==9,"Real saved debit remains unresolved before acknowledgement");
            refused(h,()->RoyalCargoGameTests.plan(f,Role.COLLECT,f.from(),40));refused(h,()->TreasuryService.withdraw(ruler,1));
            h.assertTrue(f.sourceCount()==96 && coins(f)==9 && ruler.getInventory().countItem(ModItems.DENIER.get())==0,"Neither cargo nor ruler withdrawal overwrites unresolved native financial evidence");
            var payment=new RoyalPaymentService(f.level());payment.payPlayer(ruler,id,RoyalPaymentService.Purpose.ADMINISTRATIVE,id,1);payment.commitPlayer(ruler,id);
            var op=RoyalCargoGameTests.plan(f,Role.COLLECT,f.from(),40);f.store().prepare(f.id(),op);RoyalCargoGameTests.settle(f,op,new RoyalTreasuryPersistence());
            h.assertTrue(f.sourceCount()==56 && f.entry().escrow()==40 && TreasuryService.withdraw(ruler,1)==1 && coins(f)==8,"Acknowledged financial/cargo operations release exact container guards for subsequent real work");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void rulerWithdrawalRespectsPendingCargoInActualCapitalStorage(GameTestHelper h)throws Exception{
        try(var f=root(h,"reserve-withdraw")){
            var ruler=ruler(f);f.source().setItem(6,new ItemStack(ModItems.DENIER.get(),10));var op=RoyalCargoGameTests.plan(f,Role.COLLECT,f.from(),40);f.store().prepare(f.id(),op);
            refused(h,()->TreasuryService.withdraw(ruler,1));h.assertTrue(coins(f)==10 && ruler.getInventory().countItem(ModItems.DENIER.get())==0,"Current authorized nearby ruler cannot mutate a pending cargo before image");
            f.store().returning(f.id());RoyalCargoGameTests.settle(f,op,new RoyalTreasuryPersistence());f.store().finish(f.id());
            h.assertTrue(TreasuryService.withdraw(ruler,1)==1 && coins(f)==9 && ruler.getInventory().countItem(ModItems.DENIER.get())==1 && f.sourceCount()==96,"Resolving unapplied cargo permits the original real-coin withdrawal without touching goods");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void futureShipmentIndexPreventsFinancialMutationsAndPreservesBytes(GameTestHelper h)throws Exception{
        try(var f=root(h,"reserve-future")){
            var ruler=ruler(f);f.source().setItem(6,new ItemStack(ModItems.DENIER.get(),10));var file=f.directory().resolve("shipments.dat");var original=Files.readAllBytes(file);var tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());tag.putInt("format",999);NbtIo.writeCompressed(tag,file);var future=Files.readAllBytes(file);var id=UUID.randomUUID();
            try{
                refused(h,()->new RoyalPaymentService(f.level()).payPlayer(ruler,id,RoyalPaymentService.Purpose.ADMINISTRATIVE,id,1));refused(h,()->TreasuryService.withdraw(ruler,1));
                h.assertTrue(coins(f)==10 && journal(f).load(id)==null && java.util.Arrays.equals(future,Files.readAllBytes(file)),"Unknown shipment reservations preserve native coins and future file without bypassing safety");
            }finally{Files.write(file,original);}
            h.assertTrue(f.store().load(f.id())!=null,"Only the deliberately changed test copy is restored after the preservation check");h.succeed();
        }
    }
    private static Fixture root(GameTestHelper h,String name){
        var f=RoyalCargoGameTests.fixture(h,name);var directory=f.level().getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-shipments");var store=new RoyalShipmentStore(directory);store.reserve(f.entry().intent());store.collecting(f.id());return new Fixture(f.nativeFixture(),store,f.id(),f.from(),f.to(),directory);
    }
    private static FakePlayer ruler(Fixture f){
        var ruler=new FakePlayer(f.level(),new GameProfile(UUID.randomUUID(),"Cargo ruler"));var village=f.nativeFixture().village();village.addReputation(ruler.getUUID(),40000-village.getCombinedReputation(f.level(),ruler.getUUID()));KingdomSavedData.get(f.level()).claimRule(f.nativeFixture().kingdom(),ruler.getUUID(),"Cargo ruler",1);var center=village.getCenter();ruler.moveTo(center.getX()+.5,center.getY(),center.getZ()+.5,0,0);return ruler;
    }
    private static void silver(Fixture f){f.source().setItem(6,new ItemStack(ModItems.DENIER_ARGENT.get(),2));}
    private static long coins(Fixture f){var slots=new java.util.ArrayList<ItemStack>();for(int slot=0;slot<27;slot++)slots.add(f.source().getItem(slot));return RoyalCoinPlan.spendableValue(slots);}
    private static Path moneyDirectory(Fixture f){return f.level().getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-payments");}
    private static RoyalPaymentJournal journal(Fixture f){return new RoyalPaymentJournal(moneyDirectory(f));}
    private static void refused(GameTestHelper h,Runnable action){boolean failed=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){failed=true;}h.assertTrue(failed,"Conflicting, unavailable or deliberately interrupted economic operation must fail before unsafe mutation");}
}
