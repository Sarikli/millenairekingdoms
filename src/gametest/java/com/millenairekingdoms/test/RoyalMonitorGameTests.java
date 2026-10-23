package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.AnywoodHelper;
import org.millenaire.culture.ModCultures;
import org.millenaire.item.ItemHelper;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;

/** Observation/security cases with real paid queues; actual builder completion lives in RoyalRealConstructionGameTests. */
@PrefixGameTestTemplate(false)
public final class RoyalMonitorGameTests {
    private static final String NS="millenairekingdoms_royal_construction_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void realMaterialStockChangesOnlyTheBlockedReasonWithoutAnotherCharge(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"monitor-materials")) {
            var choice=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().stream().filter(o->!o.requiredMaterials().isEmpty()).findFirst().orElseThrow();
            var id=UUID.randomUUID(); var service=new RoyalConstructionService(f.level()); var order=service.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id);
            h.assertTrue(order.stage()==Stage.QUEUED && service.observe(id).blocked()==Blocked.MATERIALS && f.value()==64,"Missing real required stock blocks work after earned patronage, without pretending to construct");
            var definition=ModCultures.getBuildingPlanSet(order.intent().planSet()).getLevel(order.intent().variant(),0);
            for(var resource:definition.requiredResources().entrySet()) {
                var item=AnywoodHelper.isAnywood(resource.getKey()) ? net.minecraft.world.item.Items.OAK_LOG : ItemHelper.resolve(resource.getKey());
                h.assertTrue(item!=null && f.village().getTownhall().getInventory().add(f.level(),item,resource.getValue())==resource.getValue(),"Fixture supplies enter the original physical storage");
            }
            h.assertTrue(service.observe(id).blocked()==Blocked.NONE && f.value()==64 && new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),id).state()==State.COMMITTED,"Depositing actual materials unblocks the same order without another fee"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void localPlayerControlRetiresTrackingAndPreservesOriginalWork(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"monitor-control")) {
            var id=UUID.randomUUID(); var service=new RoyalConstructionService(f.level()); var choice=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst();
            service.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id); var pending=f.village().getPendingProject();
            f.village().setOwner(UUID.randomUUID(),"Local owner"); var result=service.observe(id);
            h.assertTrue(result.stage()==Stage.CANCELLING && result.blocked()==Blocked.AUTHORITY && result.cancellationFrom()==Stage.QUEUED,"Local village control change is observed independently of political snapshots");
            h.assertTrue(service.settleRetired(id).stage()==Stage.CANCELLED && f.value()==64 && f.village().getPendingProject()==pending && f.village().getOwnerUUID()!=null,"Retirement never overrides local ownership, deletes pending work or refunds accepted patronage"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void offlineRulersLostReputationCannotResumePaidAuthority(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"monitor-reputation")) {
            var player=new FakePlayer(f.level(),new GameProfile(UUID.randomUUID(),"Monitor ruler"));
            f.village().addReputation(player.getUUID(),32768-f.village().getCombinedReputation(f.level(),player.getUUID()));
            KingdomSavedData.get(f.level()).claimRule(f.kingdom(),player.getUUID(),"Monitor ruler",42);
            var choice=RoyalConstructionCatalog.forPlayer(player,f.village().getId().uuid()).options().getFirst(); var id=UUID.randomUUID(); var service=new RoyalConstructionService(f.level());
            service.requestPlayer(player,f.village().getId().uuid(),choice.planSet(),id); var pending=f.village().getPendingProject();
            f.village().addReputation(player.getUUID(),-f.village().getCombinedReputation(f.level(),player.getUUID())); var result=service.observe(id);
            h.assertTrue(result.stage()==Stage.CANCELLING && result.blocked()==Blocked.AUTHORITY && f.level().getServer().getPlayerList().getPlayer(player.getUUID())==null,"UUID-bound reputation checks work while the original ruler is offline");
            h.assertTrue(service.settleRetired(id).stage()==Stage.CANCELLED && f.value()==64 && f.village().getPendingProject()==pending,"Historical closure grants no new authority or spending to an offline low-reputation ruler"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unexplainedLostPendingWorkIsNotReissuedOrReportedComplete(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"monitor-lost-queue")) {
            var id=UUID.randomUUID(); var service=new RoyalConstructionService(f.level()); var choice=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst();
            service.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id); f.village().setPendingProject(null);
            var result=service.observe(id);
            h.assertTrue(result.stage()==Stage.RECOVERY_REQUIRED && result.blocked()==Blocked.AMBIGUOUS_ENGINE_STATE && f.value()==64
                    && f.village().getPendingProject()==null && result.building()==null,"Unexpected external queue loss retains paid recovery state instead of inventing completion or reissuing ambiguous work"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void originalVillageRemovalRetiresAcceptedTrackingWithoutCreatingReplacement(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"monitor-missing-village")) {
            var id=UUID.randomUUID(); var service=new RoyalConstructionService(f.level()); var choice=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst();
            service.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id);
            var manager=VillageSavedData.get(f.level()).getVillageManager(); manager.removeVillage(f.village().getId()); var result=service.observe(id);
            h.assertTrue(result.stage()==Stage.CANCELLING && result.blocked()==Blocked.AUTHORITY && service.settleRetired(id).stage()==Stage.CANCELLED
                    && manager.getVillage(f.village().getId())==null && f.value()==64,"Missing original village is retired explicitly, never replaced by a synthetic village, builder or treasury"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    private static RoyalPaymentGameTests.Fixture fixture(GameTestHelper h,String name) {
        var f=RoyalPaymentGameTests.persistentFixture(h,name); f.village().setActive(true); f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2)); return f;
    }
}
