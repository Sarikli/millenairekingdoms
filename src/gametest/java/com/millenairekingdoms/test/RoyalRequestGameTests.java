package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.construction.RoyalVillagePersistence;
import com.millenairekingdoms.economy.RoyalEconomyConfig;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.mojang.authlib.GameProfile;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Real treasury + original engine pending slot + actual SavedData acknowledgment. Not a builder/completion test. */
@PrefixGameTestTemplate(false)
public final class RoyalRequestGameTests {
    private static final String NS="millenairekingdoms_royal_construction_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void paidQueueUsesRealCoinsAndExactReplayDoesNotPayOrQueueTwice(GameTestHelper h) throws Exception {
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"paid-queue")) {
            f.village().setActive(true); f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));
            var choice=choice(f); var id=UUID.randomUUID(); var service=new RoyalConstructionService(f.level()); int original=f.village().getBuildings().size();
            var order=service.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id);
            h.assertTrue(order.stage()==Stage.QUEUED && funds(f)==64,"Real patronage is deducted exactly once before queue acceptance");
            var pending=f.village().getPendingProject();
            h.assertTrue(pending!=null && pending.planSetId().equals(choice.planSet()) && pending.variant().equals(order.intent().variant())
                    && pending.plannedLocation()==null && !pending.isUpgrade() && f.village().getOwnerUUID()==null,"Village receives a normal unlocated pending request without ownership/placement control");
            h.assertTrue(new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),id).state()==State.COMMITTED,"Patronage commits only after original pending metadata was acknowledged");
            var replay=service.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id);
            h.assertTrue(replay.stage()==Stage.QUEUED && funds(f)==64 && pending.equals(f.village().getPendingProject()) && original==f.village().getBuildings().size(),
                    "Identical retry neither charges/queues twice nor injects a finished building");
            refused(h,()->service.requestNpc(f.kingdom(),f.village().getId().uuid(),ResourceLocation.parse("millenaire:forged"),id));
            refused(h,()->service.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),UUID.randomUUID()));
            h.assertTrue(funds(f)==64,"Conflicting/new active requests cannot take more coins");
            var ruler=new FakePlayer(f.level(),new GameProfile(UUID.randomUUID(),"Receipt ruler"));
            f.village().addReputation(ruler.getUUID(),32768-f.village().getCombinedReputation(f.level(),ruler.getUUID()));
            KingdomSavedData.get(f.level()).claimRule(f.kingdom(),ruler.getUUID(),"Receipt ruler",1);
            var receipts=new RoyalPaymentService(f.level());
            refused(h,()->receipts.inspectNpc(f.kingdom(),id)); refused(h,()->receipts.inspectPlayer(ruler,id));
            h.assertTrue(f.value()==64,"Changed government cannot inspect/rebind the old NPC receipt as its own player request"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void naturalPendingAndUnloadedVillageRejectBeforeFinancialIntent(GameTestHelper h) throws Exception {
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"queue-preconditions")) {
            f.village().setActive(true); f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2)); var choice=choice(f); var id=UUID.randomUUID();
            var pending=new Village.PendingProject(choice.planSet(),choice.variant(),0,false,null); f.village().setPendingProject(pending);
            var service=new RoyalConstructionService(f.level());
            refused(h,()->service.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id));
            h.assertTrue(f.village().getPendingProject()==pending && store(f.level()).load(id)==null
                    && new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),id)==null && funds(f)==128,"Existing natural pending work is untouched and not charged");
            f.village().setPendingProject(null); f.village().setActive(false);
            refused(h,()->service.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id));
            h.assertTrue(store(f.level()).load(id)==null && funds(f)==128,"Inactive village cannot be made active or charged by a royal request"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void failedDomainAcknowledgementKeepsPaidIntentUntilExactRetry(GameTestHelper h) throws Exception {
        int previous=RoyalEconomyConfig.CONSTRUCTION_FEE.get();
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"queue-ack")) {
            f.village().setActive(true); f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get())); var choice=choice(f); var id=UUID.randomUUID();
            var stale=new RoyalVillagePersistence() { @Override protected void flush(ServerLevel level) {} };
            var constructor=RoyalConstructionService.class.getDeclaredConstructor(ServerLevel.class,RoyalVillagePersistence.class); constructor.setAccessible(true);
            var interrupted=constructor.newInstance(f.level(),stale);
            refused(h,()->interrupted.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id));
            h.assertTrue(store(f.level()).load(id).stage()==Stage.QUEUING && new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),id).state()==State.PAID
                    && funds(f)==0,"A live pending slot alone cannot commit patronage or report a durable queued order");
            RoyalEconomyConfig.CONSTRUCTION_FEE.set(128);
            var recovered=new RoyalConstructionService(f.level()).retryNpc(f.kingdom(),id);
            h.assertTrue(recovered.stage()==Stage.QUEUED && recovered.intent().fee()==64 && funds(f)==0
                    && new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),id).state()==State.COMMITTED,"Actual domain save/read-back completes the original price without a second debit after config change"); h.succeed();
        } finally { RoyalEconomyConfig.CONSTRUCTION_FEE.set(previous); VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void paidOriginalPendingAndReceiptReopenInSeparateJvm(GameTestHelper h) throws Exception {
        var level=h.getLevel(); Path marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-paid-queue.dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024)); var kingdom=tag.getUUID("kingdom"); var id=tag.getUUID("id");
            var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));
            h.assertTrue(village!=null && village.getTownhall().getInventory()!=null && village.getPendingProject()!=null,"Original plan inventory and engine pending project both reopen without fixture repair");
            for(var pos:village.getTownhall().getInventory().getChestPositions()) level.getChunkAt(pos);
            var order=new RoyalConstructionService(level).retryNpc(kingdom,id);
            h.assertTrue(order.stage()==Stage.QUEUED && order.intent().planSet().toString().equals(tag.getString("planSet"))
                    && new RoyalPaymentService(level).inspectNpc(kingdom,id).state()==State.COMMITTED
                    && RoyalTreasurySnapshot.forNpc(level,kingdom).spendableValue()==0,"Separate JVM preserves the accepted order/payment and does not charge another patronage fee");
            System.out.println("ROYAL_PAID_QUEUE_RESTART_READ_PASS"); h.succeed(); return;
        }
        var f=RoyalPaymentGameTests.persistentFixture(h,"queue-restart"); f.village().setActive(true);
        f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get())); var choice=choice(f); var id=UUID.randomUUID();
        var order=new RoyalConstructionService(level).requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id);
        h.assertTrue(order.stage()==Stage.QUEUED && funds(f)==0,"Write JVM has real paid and acknowledged pending work");
        f.village().setActive(false); var tag=new CompoundTag(); tag.putUUID("kingdom",f.kingdom()); tag.putUUID("village",f.village().getId().uuid());
        tag.putUUID("id",id); tag.putString("planSet",choice.planSet().toString()); NbtIo.writeCompressed(tag,marker);
        VillageSavedData.get(level).setDirty(); System.out.println("ROYAL_PAID_QUEUE_RESTART_WRITE_PASS"); h.succeed();
    }

    private static RoyalConstructionCatalog.Option choice(RoyalPaymentGameTests.Fixture f) { return RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst(); }
    private static long funds(RoyalPaymentGameTests.Fixture f) { return RoyalTreasurySnapshot.forNpc(f.level(),f.kingdom()).spendableValue(); }
    private static RoyalConstructionStore store(ServerLevel level) { return new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction")); }
    private static void refused(GameTestHelper h,Runnable action) {
        boolean rejected=false; try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { rejected=true; }
        h.assertTrue(rejected,"Invalid/unacknowledged royal request cannot charge or report acceptance");
    }
}
