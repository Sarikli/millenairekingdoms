package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.village.VillageSavedData;

/** Controlled domain/recovery cases. Original generated automatic gameplay has a separate live gate. */
@PrefixGameTestTemplate(false)
public final class IndependentStrategicGameTests {
    private static final String NS="millenairekingdoms_strategic_military_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void independentVictoryTransfersOneVillageOnceWithoutNativeMutation(GameTestHelper h){
        try(var f=new StrategicMilitaryGameTests.Fixture(h,true)){
            var level=h.getLevel();var before=VillageSavedData.get(level).save(new CompoundTag(),level.registryAccess());
            var op=StrategicMilitaryGameTests.operation(h,f.order(f.b.getId().uuid()),true);var store=new StrategicBattleStore(level);
            h.assertTrue(op.defense().owner()==null && !op.defense().save().contains("owner") && StrategicBattleStore.encode(op).getInt("format")==2,"Independent native defense has no invented kingdom; new operation format is explicit");
            store.commit(op);StrategicBattleService.resume(level,store,op);StrategicBattleService.resume(level,store,store.read(op.id()));
            h.assertTrue(f.data.owner(f.b.getId().uuid()).equals(f.ka.id()) && f.data.receipt(op.id()).transferred() && f.data.wins(op.campaign())==1 && f.data.activeRaids().isEmpty(),"One legitimate independent victory yields one receipt and one transfer across exact replay");
            h.assertTrue(before.equals(VillageSavedData.get(level).save(new CompoundTag(),level.registryAccess())) && HistoryHooks.store(level).get(op.narrative().id()).realms().size()==1,"No native NPC, building, equipment or loot mutation and no fictitious defender realm");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void independentDefenseKeepsOwnershipAndNarratesTheActualWinner(GameTestHelper h){
        try(var f=new StrategicMilitaryGameTests.Fixture(h,true)){
            var op=StrategicMilitaryGameTests.operation(h,f.order(f.b.getId().uuid()),false);var store=new StrategicBattleStore(h.getLevel());store.commit(op);StrategicBattleService.resume(h.getLevel(),store,op);
            h.assertTrue(f.data.owner(f.b.getId().uuid())==null && f.data.receipt(op.id()).outcome()==KingdomSavedData.Outcome.LOST && !f.data.receipt(op.id()).transferred() && RaidOrderService.cooldownLeft(f.data,f.b.getId().uuid(),AutonomyConfig.current())>0,"A defended independent village remains independent and applies the existing cooldown");
            for(var lang:List.of("en_us","tr_tr")){
                var prose=ChronicleNarrator.render(op.narrative(),f.ka.id(),lang).prose();
                h.assertTrue(prose.contains(f.ka.name()) && prose.contains(f.b.getVillageName()),"Both actual participants appear without an empty/fictitious opposing kingdom: "+prose);
                System.out.println("INDEPENDENT_DEFENSE_PROSE "+lang+" "+prose);
            }h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void ordinaryOrderProcessingResolvesAnUnloadedIndependentWithoutPlayerPresence(GameTestHelper h){
        try(var f=new StrategicMilitaryGameTests.Fixture(h,true)){
            var level=h.getLevel();var forced=Set.copyOf(level.getForcedChunks());var order=f.order(f.b.getId().uuid());StrategicBattleService.clear(level.getServer());
            RaidOrderService.process(level);var receipt=f.data.receipt(order.id());
            h.assertTrue(receipt!=null && new StrategicBattleStore(level).owns(order.id()) && receipt.transferred()==(receipt.outcome()==KingdomSavedData.Outcome.WON),"Actual unforced world RNG and ordinary order processing use the independent strategic path");
            h.assertTrue(!level.isLoaded(f.a.getCenter()) && !level.isLoaded(f.b.getCenter()) && forced.equals(level.getForcedChunks()),"No player proximity or chunk loading was supplied");h.succeed();
        }finally{StrategicBattleService.clear(h.getLevel().getServer());}
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void recoveredFriendlyRelationsInvalidateAnUnappliedIndependentRoll(GameTestHelper h){
        try(var f=new StrategicMilitaryGameTests.Fixture(h,true)){
            var op=StrategicMilitaryGameTests.operation(h,f.order(f.b.getId().uuid()),true);var store=new StrategicBattleStore(h.getLevel());store.commit(op);f.a.setRelation(f.b.getId(),50);StrategicBattleService.resume(h.getLevel(),store,op);
            h.assertTrue(store.read(op.id()).status()==StrategicBattleStore.Status.ABORTED && store.read(op.id()).roll().equals(op.roll()) && f.data.owner(f.b.getId().uuid())==null && f.data.receipt(op.id())==null && !HistoryHooks.store(h.getLevel()).contains(op.narrative().id()),"A now-friendly village cannot be conquered using stale hostility or a fresh replacement roll");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void joiningOrLocalPlayerControlInvalidateTheOriginalIndependentAuthority(GameTestHelper h){
        for(boolean joins:List.of(false,true))try(var f=new StrategicMilitaryGameTests.Fixture(h,true)){
            var op=StrategicMilitaryGameTests.operation(h,f.order(f.b.getId().uuid()),true);var store=new StrategicBattleStore(h.getLevel());store.commit(op);
            if(joins)f.villages.kingdom(f.b);else f.b.setOwner(UUID.randomUUID(),"Local owner fixture");
            var expected=f.data.owner(f.b.getId().uuid());StrategicBattleService.resume(h.getLevel(),store,op);
            h.assertTrue(store.read(op.id()).status()==StrategicBattleStore.Status.ABORTED && Objects.equals(expected,f.data.owner(f.b.getId().uuid())) && f.data.receipt(op.id())==null,"Changed actual government or local player control cancels stale independent military authority");
        }h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void loadedAndNativeBusyIndependentForcesRetainNativeExclusivity(GameTestHelper h){
        try(var f=new StrategicMilitaryGameTests.Fixture(h,true)){
            var order=f.order(f.b.getId().uuid());h.getLevel().getChunkAt(f.a.getCenter());
            h.assertTrue(StrategicBattleService.attempt(h.getLevel(),f.data,order)==null && !new StrategicBattleStore(h.getLevel()).owns(order.id()),"Partially loaded independent regions stay on the original physical path");
        }
        try(var f=new StrategicMilitaryGameTests.Fixture(h,true)){
            f.b.getVillagerRecords().values().iterator().next().setAwayRaiding(true);var order=f.order(f.b.getId().uuid());StrategicBattleService.clear(h.getLevel().getServer());var attempt=StrategicBattleService.attempt(h.getLevel(),f.data,order);
            h.assertTrue(attempt.status()==RaidOrderService.Status.WAITING && attempt.reason().contains("Native physical") && !new StrategicBattleStore(h.getLevel()).owns(order.id()),"Existing native military commitments cannot acquire a second strategic result");
        }finally{StrategicBattleService.clear(h.getLevel().getServer());}h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void expirationCannotMasqueradeAsBudgetStarvation(GameTestHelper h){
        var clock=(net.minecraft.world.level.storage.ServerLevelData)h.getLevel().getLevelData();long before=clock.getGameTime();
        try(var f=new StrategicMilitaryGameTests.Fixture(h,true)){
            f.a.getVillagerRecords().values().iterator().next().setAwayRaiding(true);var order=f.order(f.b.getId().uuid());StrategicBattleService.clear(h.getLevel().getServer());RaidOrderService.process(h.getLevel());
            h.assertTrue(RaidOrderService.waitingReason(order.id()).contains("Native physical"),"The actual pre-expiry native blocker is recorded");clock.setGameTime(order.expiresGameTime());
            var attempt=StrategicBattleService.attempt(h.getLevel(),f.data,order);h.assertTrue(attempt.status()==RaidOrderService.Status.DROPPED && attempt.reason().equals("the order expired"),"An expired order is rejected before the inspection budget; it is not a budget failure");
            RaidOrderService.process(h.getLevel());h.assertTrue(f.data.order(order.id())==null && !new StrategicBattleStore(h.getLevel()).owns(order.id()),"Expiry creates no battle or political credit; stdout retains the previous true blocker");h.succeed();
        }finally{clock.setGameTime(before);StrategicBattleService.clear(h.getLevel().getServer());}
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void releasedWarOperationAndNewIndependentSchemasRemainStrict(GameTestHelper h)throws Exception{
        try(var input=IndependentStrategicGameTests.class.getClassLoader().getResourceAsStream("fixtures/strategic-war-1.2.0.dat")){
            h.assertTrue(input!=null,"An actual retained1.2.0 operation is present");var original=NbtIo.readCompressed(input,NbtAccounter.create(1_048_576));var op=StrategicBattleStore.decode(original);
            h.assertTrue(original.getInt("format")==1 && op.order().warId()!=null && StrategicBattleStore.encode(op).equals(original),"The real released war-operation file roundtrips without migration, identity/roll or field changes");
        }
        try(var f=new StrategicMilitaryGameTests.Fixture(h,true)){
            var op=StrategicMilitaryGameTests.operation(h,f.order(f.b.getId().uuid()),true);var tag=StrategicBattleStore.encode(op);
            h.assertTrue(StrategicBattleStore.decode(tag).equals(op),"Independent nullable ownership and exact committed dice roundtrip");
            var malformed=tag.copy();malformed.getCompound("order").putUUID("war",UUID.randomUUID());HistoryGameTests.refused(h,()->StrategicBattleStore.decode(malformed));
            var future=tag.copy();future.putInt("format",99);HistoryGameTests.refused(h,()->StrategicBattleStore.decode(future));h.succeed();
        }
    }

    @GameTest(templateNamespace="millenairekingdoms_strategic_restart_tests",template="empty",timeoutTicks=1200)
    public static void independentPendingAndCompletedConquestsReopenWithoutRerollOrDuplicateTransfer(GameTestHelper h){
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("independent-strategic-restart.dat");var store=new StrategicBattleStore(level);
        if(RoyalShipmentSupport.read()){
            for(var raw:HistoryFiles.read(marker).getList("operations",10)){
                var saved=(CompoundTag)raw;var expected=StrategicBattleStore.decode(saved);var current=store.read(expected.id());
                h.assertTrue(current!=null && current.roll().equals(expected.roll()),"Pending/completed independent operations retain their exact pre-restart dice");StrategicBattleService.resume(level,store,current);StrategicBattleService.resume(level,store,store.read(expected.id()));var data=KingdomSavedData.get(level);
                h.assertTrue(data.receipt(expected.id()).transferred() && data.owner(expected.order().target()).equals(expected.order().kingdom()) && data.wins(expected.campaign())==1 && HistoryHooks.store(level).contains(expected.narrative().id()),"Both unapplied recovery and completed replay yield exactly one independent transfer and Chronicle");
            }h.succeed();return;
        }
        var saved=new CompoundTag();var operations=new ListTag();
        for(boolean completed:List.of(false,true)){
            var f=new StrategicMilitaryGameTests.Fixture(h,true);var op=StrategicMilitaryGameTests.operation(h,f.order(f.b.getId().uuid()),true);store.commit(op);if(completed)StrategicBattleService.resume(level,store,op);operations.add(StrategicBattleStore.encode(op));
        }
        saved.put("operations",operations);HistoryFiles.write(marker,saved);VillageSavedData.get(level).setDirty();StrategicBattleService.acknowledgePolitical(level,KingdomSavedData.get(level));h.succeed();
    }
}
