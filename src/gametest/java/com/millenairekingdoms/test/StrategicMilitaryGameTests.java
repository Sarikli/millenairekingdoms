package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.*;

/** Dice/model/native-record/domain checks; actual autonomous scheduling and physical combat are separate probes. */
@PrefixGameTestTemplate(false)
public final class StrategicMilitaryGameTests {
    private static final String NS="millenairekingdoms_strategic_military_tests";
    static VillagerRecord soldier(Village v,boolean attack){
        var type=ModCultures.getAllVillagerTypes().values().stream().filter(t->t.culture().equals(v.getCultureId()) && !t.isChild() && (attack?t.isRaider():t.isHelpInAttacks()) && t.baseAttackStrength()>0).sorted(Comparator.comparing(t->t.id().toString())).findFirst().orElseThrow();
        var record=new VillagerRecord(UUID.randomUUID(),type.id(),v.getTownhall().getId());v.addVillager(record);return record;
    }
    static final class Fixture implements AutoCloseable{
        final GameTestHelper h;final PersonalityGameTests.Villages villages;final Village a,b;final KingdomSavedData data;final KingdomSavedData.Kingdom ka,kb;
        Fixture(GameTestHelper h){this(h,false);}
        Fixture(GameTestHelper h,boolean independent){this.h=h;villages=new PersonalityGameTests.Villages(h);a=villages.add("norman/militaire");b=villages.add("norman/ecclesiastique");for(int n=0;n<4;n++)soldier(a,true);soldier(b,false);data=KingdomSavedData.get(h.getLevel());ka=villages.kingdom(a);kb=independent?null:villages.kingdom(b);if(independent)a.setRelation(b.getId(),-100);else data.declareWar(ka.id(),kb.id(),Math.max(1,h.getLevel().getGameTime()));}
        KingdomSavedData.RaidOrder order(UUID target){long now=Math.max(1,h.getLevel().getGameTime());return data.addOrder(ka.id(),target,a.getId().uuid(),null,now,now,now+24000);}
        @Override public void close(){villages.close();}
    }
    static StrategicBattleStore.Operation operation(GameTestHelper h,KingdomSavedData.RaidOrder order,boolean win){
        var level=h.getLevel();var a=MilitarySnapshot.capture(level,com.millenairekingdoms.politics.PoliticsService.village(level,order.source()));var b=MilitarySnapshot.capture(level,com.millenairekingdoms.politics.PoliticsService.village(level,order.target()));var settings=OpposedDice.Settings.DEFAULT;
        var roll=OpposedDice.compare(a.attack(),b.defense(),win?20:1,win?1:20,settings);var first=win?a.owner():b.owner();var second=win?b.owner():a.owner();long tick=Math.max(1,level.getGameTime()),day=Math.max(0,level.getDayTime())/24000;
        var realms=b.owner()==null?List.of(HistoryHooks.realm(level,a.owner())):List.of(HistoryHooks.realm(level,first),HistoryHooks.realm(level,second));
        var event=HistoryEvent.create(HistoryEvent.Kind.STRATEGIC_BATTLE,day,tick,level.dimension().location().toString(),order.id().toString(),realms,List.of(HistoryHooks.place(level,order.source()),HistoryHooks.place(level,order.target())),null,"","UNATTENDED_OPPOSED_DICE",win?"ATTACKER_VICTORY":"DEFENDED",b.owner()==null?Map.of("mode","STRATEGIC","independentTarget","true"):Map.of("mode","STRATEGIC"));
        return new StrategicBattleStore.Operation(order,KingdomSavedData.get(level).revision(order.target()),a,b,settings,roll,day,tick,event,StrategicBattleStore.Status.ROLL_COMMITTED,"");
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void exactOpposedDiceProbabilitiesRewardStrengthButPermitSurprises(GameTestHelper h){
        var s=OpposedDice.Settings.DEFAULT;double weak=OpposedDice.attackWinProbability(60,80,s),equal=OpposedDice.attackWinProbability(80,80,s),strong=OpposedDice.attackWinProbability(160,80,s),defense=OpposedDice.attackWinProbability(80,120,s);
        h.assertTrue(weak>0 && weak<equal && equal<strong && strong<1 && defense<equal,"All400 independent dice pairs give strength/defense advantages while preserving eligible surprise victories");
        System.out.println("STRATEGIC_DICE exactPairs=400 weak60v80="+weak+" equal80v80="+equal+" strong160v80="+strong+" defense80v120="+defense);h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void diceBoundsTiesZerosAndExtremeStrengthRemainSafe(GameTestHelper h){
        var s=OpposedDice.Settings.DEFAULT;HistoryGameTests.refused(h,()->OpposedDice.roll(0,20,RandomSource.create(1),s));HistoryGameTests.refused(h,()->OpposedDice.modifier(20,0,s));
        h.assertTrue(Math.abs(OpposedDice.modifier(Long.MAX_VALUE,1,s))==8 && Math.abs(OpposedDice.modifier(1,Long.MAX_VALUE,s))==8 && !OpposedDice.compare(80,80,12,10,s).attackerWon(),"Bounded log-ratio does not overflow and an exact adjusted tie favors defense");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void canonicalNativePowerExcludesUnavailableRecordsAndNeverReadsChunks(GameTestHelper h){try(var f=new Fixture(h)){
        var level=h.getLevel();var unavailable=soldier(f.a,true);unavailable.setAwayRaiding(true);var killed=soldier(f.a,true);killed.setKilled(true);var snapshot=MilitarySnapshot.capture(level,f.a);
        long expected=f.a.getVillagerRecords().values().stream().filter(r->!r.isKilled() && !r.isAwayRaiding()).mapToLong(VillagerRecord::getMilitaryStrength).sum();
        h.assertTrue(snapshot.raiders().size()==4 && snapshot.attack()==expected && snapshot.provenance().equals("NATIVE_REGISTERED_RECORDS") && !level.isLoaded(f.a.getCenter()),"Only actual native isRaider types and original equipment-based record strength count; no imaginary army, loaded entities or terrain");
        h.assertTrue(MilitarySnapshot.read(snapshot.save()).equals(snapshot),"Exact native capability provenance survives strict serialization");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void committedOperationCannotRollOrAwardPoliticalProgressTwice(GameTestHelper h){try(var f=new Fixture(h)){
        var level=h.getLevel();var order=f.order(f.b.getId().uuid());var op=operation(h,order,true);var store=new StrategicBattleStore(level);var before=VillageSavedData.get(level).save(new CompoundTag(),level.registryAccess());store.commit(op);StrategicBattleService.resume(level,store,op);
        var receipt=f.data.receipt(order.id());var once=f.data.wins(op.campaign());StrategicBattleService.resume(level,store,store.read(order.id()));com.millenairekingdoms.conquest.ConquestService.reconcile(level);
        h.assertTrue(receipt!=null && receipt.outcome()==KingdomSavedData.Outcome.WON && once==1 && f.data.wins(op.campaign())==1 && f.data.order(order.id())==null && f.data.activeRaids().isEmpty(),"One committed die pair yields one receipt/counter/cooldown, never a second native or strategic operation");
        h.assertTrue(before.equals(VillageSavedData.get(level).save(new CompoundTag(),level.registryAccess())) && HistoryHooks.store(level).contains(op.narrative().id()),"The actual acknowledged political result gets one truthful strategic Chronicle without NPC death, loot, equipment or native building mutations");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void opposedDiceDefeatCreatesFailureAndCooldownWithoutConquest(GameTestHelper h){try(var f=new Fixture(h)){
        var op=operation(h,f.order(f.b.getId().uuid()),false);var store=new StrategicBattleStore(h.getLevel());store.commit(op);StrategicBattleService.resume(h.getLevel(),store,op);
        h.assertTrue(f.data.receipt(op.id()).outcome()==KingdomSavedData.Outcome.LOST && f.data.wins(op.campaign())==0 && f.data.owner(f.b.getId().uuid()).equals(f.kb.id()) && RaidOrderService.cooldownLeft(f.data,f.b.getId().uuid(),AutonomyConfig.current())>0,"Defeat consumes the legitimate operation, records a failure and applies existing military cooldown, without fake casualties");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=2400)
    public static void legitimateMajorityAndTwoWinCapitalRulesRemainMandatory(GameTestHelper h){try(var f=new Fixture(h)){
        var member=f.villages.add("norman/agricole");soldier(member,false);f.data.attach(f.kb.id(),member.getId().uuid());
        // The initial member set belongs to the war declaration. Start a new legitimate war context with this member.
        f.data.peace(f.data.activeWar(f.ka.id()).id(),KingdomSavedData.PEACE_REASON,AutonomyConfig.current().truceDays());
        f.data.startDay(Math.max(0,f.data.day())+AutonomyConfig.current().truceDays(),Map.of());f.data.declareWar(f.ka.id(),f.kb.id(),Math.max(1,h.getLevel().getGameTime()));
        HistoryGameTests.refused(h,()->f.order(f.b.getId().uuid()));var store=new StrategicBattleStore(h.getLevel());
        for(var target:List.of(member,f.b))for(int n=0;n<2;n++){
            f.data.startDay(Math.max(0,f.data.day())+1,Map.of());var op=operation(h,f.order(target.getId().uuid()),true);store.commit(op);StrategicBattleService.resume(h.getLevel(),store,op);
            if(n==0)h.assertTrue(f.data.owner(target.getId().uuid()).equals(f.kb.id()),"The first military victory cannot bypass two-win conquest");
        }
        h.assertTrue(f.data.kingdom(f.kb.id()).fallen() && f.data.members(f.kb.id()).isEmpty() && f.data.owner(member.getId().uuid()).equals(f.ka.id()) && f.data.owner(f.b.getId().uuid()).equals(f.ka.id()),"Verified strategic victories pass through existing majority, capital collapse, membership/history and two-win transitions");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void peaceAfterRollInvalidatesTheOperationWithoutRerolling(GameTestHelper h){try(var f=new Fixture(h)){
        var op=operation(h,f.order(f.b.getId().uuid()),true);var store=new StrategicBattleStore(h.getLevel());store.commit(op);f.data.peace(op.order().warId(),KingdomSavedData.PEACE_REASON,AutonomyConfig.current().truceDays());StrategicBattleService.resume(h.getLevel(),store,op);
        var saved=store.read(op.id());h.assertTrue(saved.status()==StrategicBattleStore.Status.ABORTED && saved.roll().equals(op.roll()) && f.data.receipt(op.id())==null && f.data.owner(op.order().target()).equals(f.kb.id()) && !HistoryHooks.store(h.getLevel()).contains(op.narrative().id()),"Ended war cancels an unexecuted strategic result without inventing victory or drawing again");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void activeRegionsRetainTheNativeMilitaryPath(GameTestHelper h){try(var f=new Fixture(h)){
        var order=f.order(f.b.getId().uuid());h.getLevel().getChunkAt(f.a.getCenter());h.assertTrue(StrategicBattleService.attempt(h.getLevel(),f.data,order)==null && !new StrategicBattleStore(h.getLevel()).owns(order.id()),"Even partial region activity defers to the original native path; no strategic roll overrides loaded forces");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty")
    public static void unknownOperationDataRemainsByteExact(GameTestHelper h)throws Exception{
        var root=HistoryHooks.directory(h.getLevel()).resolve("future-military-fixture");var id=UUID.randomUUID();var file=root.resolve("pending").resolve(id+".dat");var future=new CompoundTag();future.putInt("format",99);HistoryFiles.write(file,future);var before=Files.readAllBytes(file);
        HistoryGameTests.refused(h,()->new StrategicBattleStore(root,h.getLevel().dimension().location().toString()).read(id));h.assertTrue(Arrays.equals(before,Files.readAllBytes(file)),"Unsupported strategic state is never reset to obtain another roll");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unavailableOrdersCannotStarveAReadyArmyOutsideTheFirstBudgetSlots(GameTestHelper h){
        var clock=(net.minecraft.world.level.storage.ServerLevelData)h.getLevel().getLevelData();long before=clock.getGameTime();
        try(var first=new Fixture(h);var second=new Fixture(h);var ready=new Fixture(h)){
            first.a.getVillagerRecords().values().forEach(r->r.setKilled(true));second.a.getVillagerRecords().values().forEach(r->r.setKilled(true));first.order(first.b.getId().uuid());second.order(second.b.getId().uuid());var order=ready.order(ready.b.getId().uuid());
            StrategicBattleService.clear(h.getLevel().getServer());RaidOrderService.process(h.getLevel());clock.setGameTime(before+50);RaidOrderService.process(h.getLevel());
            h.assertTrue(ready.data.receipt(order.id())!=null && first.data.orderOf(first.ka.id())!=null && second.data.orderOf(second.ka.id())!=null,"Two bounded round-robin passes reach the ready third army even when two other orders lack available troops; no army or clock progression is fabricated by production");h.succeed();
        }finally{clock.setGameTime(before);StrategicBattleService.clear(h.getLevel().getServer());}
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void aNativeUnitCannotProvideBothAttackingAndDefendingPower(GameTestHelper h){try(var f=new Fixture(h)){
        var defender=f.b.getVillagerRecords().values().iterator().next();var attacker=f.a.getVillagerRecords().values().iterator().next();f.b.addVillager(new VillagerRecord(attacker.getUuid(),defender.getVillagerTypeId(),f.b.getTownhall().getId()));var order=f.order(f.b.getId().uuid());StrategicBattleService.clear(h.getLevel().getServer());
        var attempt=StrategicBattleService.attempt(h.getLevel(),f.data,order);h.assertTrue(attempt!=null && attempt.status()==RaidOrderService.Status.WAITING && attempt.reason().contains("same native unit") && !new StrategicBattleStore(h.getLevel()).owns(order.id()),"Inconsistent duplicated native identities defer instead of inventing another army or a battle result");h.succeed();
    }finally{StrategicBattleService.clear(h.getLevel().getServer());}}
    @GameTest(templateNamespace="millenairekingdoms_strategic_restart_tests",template="empty",timeoutTicks=1200)
    public static void separateJvmPreservesCommittedDiceIdentityAndOnePoliticalOutcome(GameTestHelper h){
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("strategic-restart.dat");var store=new StrategicBattleStore(level);
        if(RoyalShipmentSupport.read()){
            var saved=HistoryFiles.read(marker);var op=store.read(saved.getUUID("id"));h.assertTrue(op.roll().equals(StrategicBattleStore.decode(saved.getCompound("operation")).roll()),"Exact committed dice/powers/identity reopen without replacement");StrategicBattleService.resume(level,store,op);
            var data=KingdomSavedData.get(level);h.assertTrue(data.receipt(op.id())!=null && data.wins(op.campaign())==1 && HistoryHooks.store(level).contains(op.narrative().id()),"Reopen resumes the same military result and publishes one acknowledged political receipt/Chronicle");StrategicBattleService.resume(level,store,store.read(op.id()));h.assertTrue(data.wins(op.campaign())==1,"Another restart-style replay cannot duplicate campaign credit");h.succeed();return;
        }
        var fixture=new Fixture(h);var op=operation(h,fixture.order(fixture.b.getId().uuid()),true);store.commit(op);var saved=new CompoundTag();saved.putUUID("id",op.id());saved.put("operation",StrategicBattleStore.encode(op));HistoryFiles.write(marker,saved);VillageSavedData.get(level).setDirty();StrategicBattleService.acknowledgePolitical(level,fixture.data);h.succeed();
    }
}
