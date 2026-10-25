package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalConstructionStore.Intent;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.economy.RoyalPaymentJournal;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentService.Purpose;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Outcome;
import com.mojang.authlib.GameProfile;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Real coins and durable mandate retirement on actual political snapshots. FakePlayer tests authority only. */
@PrefixGameTestTemplate(false)
public final class RoyalGovernanceGameTests {
    private static final String NS="millenairekingdoms_royal_construction_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void playerTakeoverRetiresNpcOrderBeforeItCanResume(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"takeover")) {
            var choice=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst(); var id=UUID.randomUUID();
            var service=new RoyalConstructionService(f.level()); service.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id); var pending=f.village().getPendingProject();
            var player=player(f); KingdomSavedData.get(f.level()).claimRule(f.kingdom(),player.getUUID(),"New ruler",42);
            h.assertTrue(store(f.level()).load(id).stage()==Stage.CANCELLING && store(f.level()).load(id).blocked()==Blocked.AUTHORITY
                    && store(f.level()).load(id).cancellationFrom()==Stage.QUEUED,"Taking rule durably retires the prior NPC mandate synchronously");
            refused(h,()->service.retryNpc(f.kingdom(),id)); var retired=service.settleRetired(id);
            h.assertTrue(retired.stage()==Stage.CANCELLED && f.value()==64 && f.village().getPendingProject()==pending
                    && journal(f.level()).load(id).state()==State.COMMITTED,"Accepted retirement preserves work and earned patronage without impersonating the new ruler"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void sameTickAbdicateAndReclaimCannotReviveAnOldMandate(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"same-tick")) {
            var player=player(f); var intent=paidPlayer(f,player); var data=KingdomSavedData.get(f.level());
            data.abdicate(f.kingdom(),player.getUUID()); data.claimRule(f.kingdom(),player.getUUID(),"Same ruler",42);
            h.assertTrue(data.ruler(f.kingdom()).sinceGameTime()==intent.rulerSince() && store(f.level()).load(intent.id()).stage()==Stage.CANCELLING,"Same timestamp cannot reset the persisted retirement");
            var result=new RoyalConstructionService(f.level()).retryPlayer(player,intent.id());
            h.assertTrue(result.stage()==Stage.CANCELLED && f.value()==128 && f.village().getPendingProject()==null
                    && journal(f.level()).load(intent.id()).state()==State.REFUNDED,"A retry from the new tenure can only finish the prior cancellation, never queue/recharge it"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void retiredOfflinePlayerRefundStaysInTheOriginalCapital(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"offline")) {
            var player=player(f); var intent=paidPlayer(f,player); KingdomSavedData.get(f.level()).abdicate(f.kingdom(),player.getUUID());
            h.assertTrue(f.level().getServer().getPlayerList().getPlayer(player.getUUID())==null,"Authority fixture is offline, not a logged-in player");
            var result=new RoyalConstructionService(f.level()).settleRetired(intent.id());
            h.assertTrue(result.stage()==Stage.CANCELLED && f.value()==128 && journal(f.level()).load(intent.id()).state()==State.REFUNDED,"Historical settlement refunds the exact bound player receipt to original physical storage");
            refused(h,()->new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),intent.id()));
            h.assertTrue(f.village().getPendingProject()==null,"An offline player's request is never treated as an NPC construction action"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void inactiveKingdomDefersRetiredRefundWithoutLosingTheDecision(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"inactive-retirement")) {
            var intent=paidNpc(f); var data=KingdomSavedData.get(f.level()); data.setActive(f.kingdom(),false); var service=new RoyalConstructionService(f.level());
            refused(h,()->service.settleRetired(intent.id()));
            h.assertTrue(store(f.level()).load(intent.id()).stage()==Stage.CANCELLING && f.value()==64
                    && journal(f.level()).load(intent.id()).state()==State.PAID,"An inactive treasury is not credited or replaced; retirement remains pending");
            data.setActive(f.kingdom(),true); var result=service.settleRetired(intent.id());
            h.assertTrue(result.stage()==Stage.CANCELLED && f.value()==128 && f.village().getPendingProject()==null,"Reactivation permits exact refund but cannot revive the old mandate"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void detachedPoliticalCopiesCannotRetireLiveOrders(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"detached")) {
            var player=player(f); var intent=paidPlayer(f,player); var data=KingdomSavedData.get(f.level());
            var tag=data.save(new CompoundTag(),f.level().registryAccess()); var detached=KingdomSavedData.load(tag,f.level().registryAccess()); detached.abdicate(f.kingdom(),player.getUUID());
            h.assertTrue(tag.getInt("format")==7 && data.ruler(f.kingdom())!=null && detached.ruler(f.kingdom())==null
                    && store(f.level()).load(intent.id()).stage()==Stage.PAID && f.value()==64,"Detached migrations/simulations retain political format 6 and have no live order side effects");
            new RoyalConstructionService(f.level()).cancelPlayer(player,intent.id()); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void failedRetirementWritePreventsPoliticalPublication(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"retirement-lock")) {
            var intent=paidNpc(f); var data=KingdomSavedData.get(f.level()); var player=player(f);
            try(var channel=FileChannel.open(directory(f.level()).resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()) {
                refused(h,()->data.claimRule(f.kingdom(),player.getUUID(),"Locked ruler",42));
            }
            h.assertTrue(data.ruler(f.kingdom())==null && store(f.level()).load(intent.id()).stage()==Stage.PAID && f.value()==64,"A real order-index file lock prevents publishing new authority before durable retirement");
            data.claimRule(f.kingdom(),player.getUUID(),"Unlocked ruler",42);
            h.assertTrue(store(f.level()).load(intent.id()).stage()==Stage.CANCELLING && new RoyalConstructionService(f.level()).settleRetired(intent.id()).stage()==Stage.CANCELLED
                    && f.value()==128,"After the actual lock releases, retirement and exact refund finish safely"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void capitalFallRetiresAcceptedWorkWithoutDemolitionOrRefund(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"fall-accepted"); var attacker=fixture(h,"victor-accepted")) {
            var choice=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst(); var id=UUID.randomUUID();
            new RoyalConstructionService(f.level()).requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id); var pending=f.village().getPendingProject(); fall(attacker,f);
            var data=KingdomSavedData.get(f.level()); var retired=new RoyalConstructionService(f.level()).settleRetired(id);
            h.assertTrue(data.kingdom(f.kingdom()).fallen() && attacker.kingdom().equals(data.owner(f.village().getId().uuid()))
                    && retired.stage()==Stage.CANCELLED && retired.cancellationFrom()==Stage.QUEUED && f.value()==64 && attacker.value()==128
                    && f.village().getPendingProject()==pending,"Actual capital-collapse snapshot retires old royal tracking while autonomous work and paid value survive"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void capitalFallNeverRedirectsAnUnacceptedRefundToTheVictor(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"fall-unaccepted"); var attacker=fixture(h,"victor-unaccepted")) {
            var intent=paidNpc(f); fall(attacker,f); refused(h,()->new RoyalConstructionService(f.level()).settleRetired(intent.id()));
            h.assertTrue(store(f.level()).load(intent.id()).stage()==Stage.CANCELLING && store(f.level()).load(intent.id()).blocked()==Blocked.AUTHORITY
                    && journal(f.level()).load(intent.id()).state()==State.PAID && f.value()==64 && attacker.value()==128
                    && f.village().getPendingProject()==null,"Destroyed original government keeps a diagnostic pending receipt; it cannot mint or redirect refunds into another kingdom"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void historicalSettlementCannotCancelAnActiveMandate(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"active-guard")) {
            var intent=paidNpc(f); refused(h,()->new RoyalConstructionService(f.level()).settleRetired(intent.id()));
            h.assertTrue(store(f.level()).load(intent.id()).stage()==Stage.PAID && f.value()==64 && journal(f.level()).load(intent.id()).state()==State.PAID,"The order recovery entry point requires an existing durable authority cancellation decision");
            new RoyalConstructionService(f.level()).cancelNpc(f.kingdom(),intent.id()); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void pendingPlayerCancellationRemainsSettleableAfterAbdication(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"cancelling-abdication")) {
            var player=player(f); var intent=paidPlayer(f,player); var store=store(f.level());
            store.change(intent.id(),Stage.PAID,Stage.CANCELLING,Blocked.NONE,null);
            KingdomSavedData.get(f.level()).abdicate(f.kingdom(),player.getUUID());
            h.assertTrue(store.load(intent.id()).blocked()==Blocked.AUTHORITY && store.load(intent.id()).cancellationFrom()==Stage.PAID,"Authority retirement adopts an already-persisted cancellation without losing its original refund policy");
            var result=new RoyalConstructionService(f.level()).settleRetired(intent.id());
            h.assertTrue(result.stage()==Stage.CANCELLED && f.value()==128 && journal(f.level()).load(intent.id()).state()==State.REFUNDED,"A cancellation interrupted before abdication can finish after authority is lost"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void acknowledgedRefundBeforeRetirementReplaysWithoutAccessingInactiveTreasury(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"refunded-before-retirement")) {
            var player=player(f); var intent=paidPlayer(f,player); var store=store(f.level());
            store.change(intent.id(),Stage.PAID,Stage.CANCELLING,Blocked.NONE,null);
            new RoyalPaymentService(f.level()).refundPlayer(player,intent.id()); var data=KingdomSavedData.get(f.level());
            data.abdicate(f.kingdom(),player.getUUID()); data.setActive(f.kingdom(),false);
            var result=new RoyalConstructionService(f.level()).settleRetired(intent.id());
            h.assertTrue(result.stage()==Stage.CANCELLED && f.value()==128 && journal(f.level()).load(intent.id()).state()==State.REFUNDED
                    && !data.kingdom(f.kingdom()).active(),"A refund already durable before order acknowledgement/authority loss only closes tracking; it never reads or credits an inactive treasury again"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void offlineRetirementReopensAndRefundsInSeparateJvm(GameTestHelper h) throws Exception {
        var level=h.getLevel(); var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-retired-player.dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024)); var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));
            h.assertTrue(village!=null && village.getTownhall().getInventory()!=null,"Original saved inventory reopens without fixture repair");
            for(var pos:village.getTownhall().getInventory().getChestPositions()) level.getChunkAt(pos);
            var id=tag.getUUID("id"); h.assertTrue(store(level).load(id).stage()==Stage.CANCELLING && store(level).load(id).intent().actor().equals(tag.getUUID("actor")),"Offline actor and retirement decision survived independent JVM replacement");
            var result=new RoyalConstructionService(level).settleRetired(id);
            h.assertTrue(result.stage()==Stage.CANCELLED && RoyalTreasurySnapshot.forNpc(level,tag.getUUID("kingdom")).spendableValue()==128
                    && journal(level).load(id).state()==State.REFUNDED && village.getPendingProject()==null,"Restart refunds original bound player receipt without NPC action or a logged-in original ruler");
            System.out.println("ROYAL_GOVERNANCE_RESTART_READ_PASS"); h.succeed(); return;
        }
        var f=fixture(h,"governance-restart"); var player=player(f); var intent=paidPlayer(f,player);
        KingdomSavedData.get(level).abdicate(f.kingdom(),player.getUUID()); f.village().setActive(false);
        var tag=new CompoundTag(); tag.putUUID("id",intent.id()); tag.putUUID("kingdom",f.kingdom()); tag.putUUID("village",intent.village()); tag.putUUID("actor",player.getUUID());
        NbtIo.writeCompressed(tag,marker); VillageSavedData.get(level).setDirty(); System.out.println("ROYAL_GOVERNANCE_RESTART_WRITE_PASS"); h.succeed();
    }

    private static RoyalPaymentGameTests.Fixture fixture(GameTestHelper h,String name) {
        var f=RoyalPaymentGameTests.persistentFixture(h,name); f.village().setActive(true); f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2)); return f;
    }
    private static FakePlayer player(RoyalPaymentGameTests.Fixture f) {
        var player=new FakePlayer(f.level(),new GameProfile(UUID.randomUUID(),"Royal test ruler"));
        f.village().addReputation(player.getUUID(),32768-f.village().getCombinedReputation(f.level(),player.getUUID())); return player;
    }
    private static Intent paidPlayer(RoyalPaymentGameTests.Fixture f,FakePlayer player) {
        KingdomSavedData.get(f.level()).claimRule(f.kingdom(),player.getUUID(),"Royal test ruler",42);
        return paid(f,player);
    }
    private static Intent paidNpc(RoyalPaymentGameTests.Fixture f) { return paid(f,null); }
    private static Intent paid(RoyalPaymentGameTests.Fixture f,FakePlayer player) {
        var catalog=player==null ? RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()) : RoyalConstructionCatalog.forPlayer(player,f.village().getId().uuid());
        var choice=catalog.options().getFirst(); var intent=new Intent(UUID.randomUUID(),f.level().dimension().location().toString(),f.kingdom(),f.village().getId().uuid(),f.village().getId().uuid(),
                player==null ? null : player.getUUID(),player==null ? 0 : 42,f.village().getVillageTypeId(),choice.planSet(),choice.plan(),choice.variant(),choice.patronageFee(),Math.max(0,f.level().getGameTime()),
                f.village().getBuildings().stream().map(b->b.getId().uuid()).collect(Collectors.toSet()));
        var store=store(f.level()); store.reserve(intent); var payments=new RoyalPaymentService(f.level());
        if(player==null) payments.payNpc(f.kingdom(),intent.id(),Purpose.CONSTRUCTION,intent.id(),intent.fee()); else payments.payPlayer(player,intent.id(),Purpose.CONSTRUCTION,intent.id(),intent.fee());
        store.change(intent.id(),Stage.REQUESTED,Stage.PAID,Blocked.NONE,null); return intent;
    }
    private static void fall(RoyalPaymentGameTests.Fixture attacker,RoyalPaymentGameTests.Fixture defender) {
        var data=KingdomSavedData.get(attacker.level()); data.declareWar(attacker.kingdom(),defender.kingdom(),1);
        for(int n=0;n<2;n++) { var raid=data.begin(attacker.village().getId().uuid(),defender.village().getId().uuid(),n+1,null); data.recordVictory(raid); data.finish(raid,Outcome.LOST); }
    }
    private static Path directory(ServerLevel level) { return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"); }
    private static RoyalConstructionStore store(ServerLevel level) { return new RoyalConstructionStore(directory(level)); }
    private static RoyalPaymentJournal journal(ServerLevel level) { return new RoyalPaymentJournal(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-payments")); }
    private static void refused(GameTestHelper h,Runnable action) {
        boolean refused=false; try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { refused=true; }
        h.assertTrue(refused,"Unauthorized, unsafe or unacknowledged governance action must fail closed");
    }
}
