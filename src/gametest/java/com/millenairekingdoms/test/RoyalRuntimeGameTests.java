package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionRuntime;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalConstructionStore.Intent;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.construction.RoyalVillagePersistence;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentService.Purpose;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.mojang.authlib.GameProfile;
import java.nio.file.Path;
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
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

@PrefixGameTestTemplate(false)
public final class RoyalRuntimeGameTests {
    private static final String NS="millenairekingdoms_royal_construction_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200,batch="royal_runtime")
    public static void equalFieldsCannotAdoptAnUnknownPendingObject(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"publication-identity")) {
            var choice=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().getFirst(); var id=UUID.randomUUID();
            var stale=new RoyalVillagePersistence() { @Override protected void flush(ServerLevel level) {} };
            var constructor=RoyalConstructionService.class.getDeclaredConstructor(ServerLevel.class,RoyalVillagePersistence.class); constructor.setAccessible(true);
            var interrupted=constructor.newInstance(f.level(),stale); boolean failed=false;
            try { interrupted.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id); } catch(IllegalStateException expected) { failed=true; }
            h.assertTrue(failed && store(f.level()).load(id).stage()==Stage.QUEUING,"Original queue acknowledgement deliberately interrupted after real debit");
            var old=f.village().getPendingProject(); var coincident=new Village.PendingProject(old.planSetId(),old.variant(),old.level(),old.isUpgrade(),old.buildingId());
            f.village().setPendingProject(coincident); var result=new RoyalConstructionService(f.level()).retryNpc(f.kingdom(),id);
            h.assertTrue(result.stage()==Stage.RECOVERY_REQUIRED && f.value()==64 && f.village().getPendingProject()==coincident
                    && new RoyalPaymentService(f.level()).inspectNpc(f.kingdom(),id).state()==State.PAID,"Field equality does not establish publication ownership: preserve possibly natural work and paid recovery reservation"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200,batch="royal_runtime")
    public static void acknowledgedOfflinePlayerQueueCommitsWithoutAnotherDebitOrChunkAccess(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"offline-accepted")) {
            var player=player(f); var intent=intent(f,player); store(f.level()).reserve(intent);
            var payments=new RoyalPaymentService(f.level()); payments.payPlayer(player,intent.id(),Purpose.CONSTRUCTION,intent.id(),intent.fee()); queue(f,intent);
            f.village().setActive(false); int chunks=f.level().getChunkSource().getLoadedChunksCount();
            var result=new RoyalConstructionService(f.level()).observe(intent.id());
            h.assertTrue(result.stage()==Stage.QUEUED && result.blocked()==Blocked.UNLOADED && f.value()==64
                    && payments.inspectConstruction(intent.id(),intent.kingdom(),intent.capital(),intent.actor(),intent.fee()).state()==State.COMMITTED
                    && f.level().getServer().getPlayerList().getPlayer(player.getUUID())==null && f.level().getChunkSource().getLoadedChunksCount()==chunks,
                    "Existing durable acceptance commits while the player is offline and the village inactive, without new funds or chunk loading");
            boolean refused=false; try { payments.acknowledgeConstruction(intent.id(),intent.kingdom(),intent.capital(),UUID.randomUUID(),intent.fee()); } catch(IllegalArgumentException expected) { refused=true; }
            h.assertTrue(refused && f.value()==64,"Offline acknowledgement never rebinds a stored player receipt to another actor"); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200,batch="royal_runtime")
    public static void forgedAcceptedMetadataNeedsAnExactRealConstructionReceipt(GameTestHelper h) throws Exception {
        for(boolean collision:new boolean[]{false,true}) try(var f=fixture(h,"receipt-guard-"+collision)) {
            var intent=intent(f,null); store(f.level()).reserve(intent); var payments=new RoyalPaymentService(f.level());
            if(collision) payments.payNpc(f.kingdom(),intent.id(),Purpose.ADMINISTRATIVE,UUID.randomUUID(),intent.fee());
            // Deliberate malformed domain metadata is a negative security case, never proof of genuine paid construction.
            queue(f,intent); var result=new RoyalConstructionService(f.level()).observe(intent.id());
            h.assertTrue(result.stage()==Stage.RECOVERY_REQUIRED && result.blocked()==Blocked.AMBIGUOUS_ENGINE_STATE && f.value()==(collision ? 64 : 128)
                    && result.building()==null && f.village().getPendingProject()!=null,"Missing/wrong-purpose receipt cannot become accepted construction, free completion or an unrelated refund");
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
        h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200,batch="royal_runtime")
    public static void schedulerBudgetIsFairAndCannotSpendForOfflinePlayers(GameTestHelper h) throws Exception {
        try(var a=fixture(h,"budget-a");var b=fixture(h,"budget-b");var offline=fixture(h,"budget-offline")) {
            var ia=intent(a,null); var ib=intent(b,null); var player=player(offline); var ip=intent(offline,player); var store=store(a.level());
            store.reserve(ia); store.reserve(ib); store.reserve(ip); int chunks=a.level().getChunkSource().getLoadedChunksCount();
            int bound=store.entries().size()*3;
            for(int n=0;n<bound && (store.load(ia.id()).stage()!=Stage.QUEUED || store.load(ib.id()).stage()!=Stage.QUEUED);n++) {
                int jobs=RoyalConstructionRuntime.step(a.level(),1); h.assertTrue(jobs>=0 && jobs<=1,"Each scheduler step processes at most its distinct-order budget");
            }
            h.assertTrue(store.load(ia.id()).stage()==Stage.QUEUED && store.load(ib.id()).stage()==Stage.QUEUED && a.value()==64 && b.value()==64,
                    "Round-robin tracking eventually resumes both genuine authorized NPC requests, charging each once");
            h.assertTrue(store.load(ip.id()).stage()==Stage.REQUESTED && offline.value()==128 && offline.village().getPendingProject()==null
                    && new RoyalPaymentService(offline.level()).inspectConstruction(ip.id(),ip.kingdom(),ip.capital(),ip.actor(),ip.fee())==null
                    && a.level().getChunkSource().getLoadedChunksCount()==chunks,"Offline unaccepted player request never gains NPC agency, a charge or forced chunks");
            new RoyalConstructionService(a.level()).cancelNpc(a.kingdom(),ia.id()); new RoyalConstructionService(b.level()).cancelNpc(b.kingdom(),ib.id());
            new RoyalConstructionService(offline.level()).cancelPlayer(player,ip.id()); h.succeed();
        } finally { VillageSavedData.get(h.getLevel()).setDirty(); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200,batch="royal_runtime")
    public static void interruptedPublicationIsQuarantinedBeforeReloadEngineTicks(GameTestHelper h) throws Exception {
        var level=h.getLevel(); var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-interrupted-publication.dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024)); var id=tag.getUUID("id"); var order=store(level).load(id);
            var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));
            h.assertTrue(order.stage()==Stage.RECOVERY_REQUIRED && order.blocked()==Blocked.AMBIGUOUS_ENGINE_STATE
                    && village!=null && village.getPendingProject()!=null,"ServerStarted already quarantined uncertain publication without deleting the original project");
            for(var pos:village.getTownhall().getInventory().getChestPositions()) level.getChunkAt(pos);
            h.assertTrue(new RoyalPaymentService(level).inspectNpc(tag.getUUID("kingdom"),id).state()==State.PAID
                    && RoyalTreasurySnapshot.forNpc(level,tag.getUUID("kingdom")).spendableValue()==64,"Independent JVM preserves actual debit/receipt/reservation without a speculative refund or new request");
            System.out.println("ROYAL_PUBLICATION_STARTUP_READ_PASS"); h.succeed(); return;
        }
        var f=fixture(h,"publication-restart"); var intent=intent(f,null); store(level).reserve(intent);
        new RoyalPaymentService(level).payNpc(f.kingdom(),intent.id(),Purpose.CONSTRUCTION,intent.id(),intent.fee());
        store(level).change(intent.id(),Stage.REQUESTED,Stage.PAID,Blocked.NONE,null); store(level).change(intent.id(),Stage.PAID,Stage.QUEUING,Blocked.NONE,null);
        var pending=new Village.PendingProject(intent.planSet(),intent.variant(),0,false,null); f.village().setPendingProject(pending);
        new RoyalVillagePersistence().pending(level,intent.village(),pending); f.village().setActive(false);
        var tag=new CompoundTag(); tag.putUUID("id",intent.id()); tag.putUUID("kingdom",f.kingdom()); tag.putUUID("village",intent.village()); NbtIo.writeCompressed(tag,marker);
        VillageSavedData.get(level).setDirty(); System.out.println("ROYAL_PUBLICATION_STARTUP_WRITE_PASS"); h.succeed();
    }

    private static void queue(RoyalPaymentGameTests.Fixture f,Intent intent) {
        var store=store(f.level()); store.change(intent.id(),Stage.REQUESTED,Stage.PAID,Blocked.NONE,null); store.change(intent.id(),Stage.PAID,Stage.QUEUING,Blocked.NONE,null);
        var pending=new Village.PendingProject(intent.planSet(),intent.variant(),0,false,null); f.village().setPendingProject(pending);
        new RoyalVillagePersistence().pending(f.level(),intent.village(),pending); store.change(intent.id(),Stage.QUEUING,Stage.QUEUED,Blocked.NONE,null);
    }
    private static RoyalPaymentGameTests.Fixture fixture(GameTestHelper h,String name) {
        var f=RoyalPaymentGameTests.persistentFixture(h,name); f.village().setActive(true); f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2)); return f;
    }
    private static FakePlayer player(RoyalPaymentGameTests.Fixture f) {
        var player=new FakePlayer(f.level(),new GameProfile(UUID.randomUUID(),"Runtime ruler"));
        f.village().addReputation(player.getUUID(),32768-f.village().getCombinedReputation(f.level(),player.getUUID()));
        KingdomSavedData.get(f.level()).claimRule(f.kingdom(),player.getUUID(),"Runtime ruler",42); return player;
    }
    private static Intent intent(RoyalPaymentGameTests.Fixture f,FakePlayer player) {
        var choices=player==null ? RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()) : RoyalConstructionCatalog.forPlayer(player,f.village().getId().uuid());
        var choice=choices.options().getFirst(); return new Intent(UUID.randomUUID(),f.level().dimension().location().toString(),f.kingdom(),f.village().getId().uuid(),f.village().getId().uuid(),
                player==null ? null : player.getUUID(),player==null ? 0 : 42,f.village().getVillageTypeId(),choice.planSet(),choice.plan(),choice.variant(),choice.patronageFee(),Math.max(0,f.level().getGameTime()),
                f.village().getBuildings().stream().map(b->b.getId().uuid()).collect(Collectors.toSet()));
    }
    private static RoyalConstructionStore store(ServerLevel level) { return new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction")); }
}
