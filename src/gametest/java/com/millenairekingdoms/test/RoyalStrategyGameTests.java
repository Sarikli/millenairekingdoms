package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.history.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Explicit service gates are distinct from the long actual-event/naturally-unloaded scheduling probe below. */
@PrefixGameTestTemplate(false)
public final class RoyalStrategyGameTests {
    private static final String NS="millenairekingdoms_royal_strategy_tests";
    private static long day(GameTestHelper h){return Math.max(0,h.getLevel().getDayTime())/24000;}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void activePlanningPrioritizesActuallyResourcedNativeOpportunities(GameTestHelper h){
        boolean tracking=com.millenairekingdoms.construction.RoyalConstructionConfig.TRACKING.get();com.millenairekingdoms.construction.RoyalConstructionConfig.TRACKING.set(true);
        try(var r=RoyalShipmentSupport.realm(h,"ready-native-opportunity")){
            RoyalNpcEconomyGameTests.money(r,4);var option=RoyalNpcEconomyGameTests.option(r,r.source());RoyalNpcEconomyGameTests.materials(h,r,r.source(),option.requiredMaterials(),null);var builder=RoyalNpcEconomyGameTests.builder(h,r);
            try{var service=new RoyalStrategyService(r.level());var result=service.step(r.kingdom(),day(h),RandomSource.create(1));
                h.assertTrue(result!=null && result.kind()==RoyalNpcDecisionStore.Kind.CONSTRUCTION && r.coins()==192 && r.source().getPendingProject()!=null,"A wealthy active kingdom with a genuine eligible builder/material opportunity invests now instead of selecting an unaffordable/unresourced metadata idea");h.succeed();
            }finally{builder.discard();}
        }finally{com.millenairekingdoms.construction.RoyalConstructionConfig.TRACKING.set(tracking);}
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unavailableCapitalPlansKnownNativeNeedWithoutPretendingToOwnInventory(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"conditional-native-need")){
            RoyalNpcEconomyGameTests.shortage(h,r);r.capital().setActive(false);var service=new RoyalStrategyService(r.level());var result=service.step(r.kingdom(),day(h),RandomSource.create(1));var plan=service.inspect(r.kingdom());
            h.assertTrue(result==null && plan!=null && plan.status()==RoyalStrategyStore.Status.CONDITIONAL && plan.logistics() && plan.target().equals(r.to()) && plan.reason().contains("UNKNOWN")==false && plan.reason().contains("no shortage, cargo or fee is assumed"),"Known native pending work yields a conditional logistical evaluation, not an invented stock snapshot");
            h.assertTrue(r.coins()==256 && new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(r.level())).load(plan.id())==null && r.store().load(plan.id())==null,"Planning neither authorizes a fee nor creates cargo/buildings");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void delayedPlanUsesTheExistingSharedFinancialDomainExactlyOnce(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"conditional-execution")){
            RoyalNpcEconomyGameTests.shortage(h,r);r.capital().setActive(false);var service=new RoyalStrategyService(r.level());service.step(r.kingdom(),day(h),RandomSource.create(2));var plan=service.inspect(r.kingdom());r.capital().setActive(true);
            var result=service.step(r.kingdom(),day(h),RandomSource.create(2));
            h.assertTrue(result!=null && result.attempt().equals(plan.id()) && r.coins()==247 && r.store().load(plan.id())!=null && service.inspect(r.kingdom()).status()==RoyalStrategyStore.Status.LINKED,"Loaded real treasury/stocks revalidate the old conditional intention and pay one real9 fee through the existing shipment service");
            service.step(r.kingdom(),day(h)+10,RandomSource.create(3));h.assertTrue(r.coins()==247 && new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(r.level())).entries().stream().filter(d->d.kingdom().equals(r.kingdom())).count()==1,"An existing nonterminal domain operation cannot be reissued after its generic policy cooldown");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void changedResourcesAndGovernmentCannotExecuteAnOldPlan(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"conditional-invalidated")){
            RoyalNpcEconomyGameTests.shortage(h,r);r.capital().setActive(false);var service=new RoyalStrategyService(r.level());service.step(r.kingdom(),day(h),RandomSource.create(4));var plan=service.inspect(r.kingdom());
            r.chest(r.source()).clearContent();r.source().getTownhall().getInventory().invalidateCache();r.capital().setActive(true);
            h.assertTrue(service.step(r.kingdom(),day(h),RandomSource.create(4))==null && r.coins()==256 && r.store().load(plan.id())==null,"Remembered need never substitutes for current available safe surplus");
            r.ruler();service.step(r.kingdom(),day(h),RandomSource.create(4));h.assertTrue(service.inspect(r.kingdom()).status()==RoyalStrategyStore.Status.INVALIDATED && r.coins()==256 && r.store().load(plan.id())==null,"Player government invalidates an unexecuted plan without authorizing a fee or resetting the native project");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unsupportedStrategicDataCannotBeResetOrSpentAround(GameTestHelper h)throws Exception{
        try(var r=RoyalShipmentSupport.realm(h,"conditional-future")){
            var store=new RoyalStrategyStore(r.level());var file=store.file(r.kingdom());var future=new CompoundTag();future.putInt("format",99);HistoryFiles.write(file,future);var bytes=Files.readAllBytes(file);
            HistoryGameTests.refused(h,()->new RoyalStrategyService(r.level()).step(r.kingdom(),day(h),RandomSource.create(5)));h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(file)) && r.coins()==128,"Unknown strategic formats preserve bytes and cannot be treated as an empty investment queue");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",batch="strategy_restart",timeoutTicks=1200)
    public static void sameConditionalIdentitySurvivesSeparateJvmAndResumesOnce(GameTestHelper h)throws Exception{
        var marker=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("royal-strategy-restart.dat");
        if(RoyalShipmentSupport.read()){
            var saved=HistoryFiles.read(marker);try(var r=RoyalShipmentSupport.restore(h,saved,true)){
                var service=new RoyalStrategyService(r.level());var plan=service.inspect(r.kingdom());h.assertTrue(plan.id().equals(saved.getUUID("intent")) && plan.status()==RoyalStrategyStore.Status.CONDITIONAL && r.coins()==256,"Actual native storage and the same unpaid conditional identity reopen without repair");
                var result=service.step(r.kingdom(),day(h),RandomSource.create(6));h.assertTrue(result!=null && result.attempt().equals(plan.id()) && r.coins()==247,"Separate JVM revalidates the old conditional need and executes one real9 fee");service.step(r.kingdom(),day(h),RandomSource.create(6));h.assertTrue(r.coins()==247,"Reopened intention cannot duplicate its payment/domain order");h.succeed();return;
            }
        }
        var r=RoyalShipmentSupport.realm(h,"persistent-conditional");RoyalNpcEconomyGameTests.shortage(h,r);r.capital().setActive(false);var service=new RoyalStrategyService(r.level());service.step(r.kingdom(),day(h),RandomSource.create(6));var saved=r.marker();saved.putUUID("intent",service.inspect(r.kingdom()).id());HistoryFiles.write(marker,saved);r.save();h.assertTrue(r.coins()==256,"Write phase saves an unpaid conditional plan and real256 physical coins");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",batch="strategy_unattended",timeoutTicks=40000)
    public static void normalSchedulerRevisitsNaturallyUnloadedVillagesWithoutPhysicalAuthorizations(GameTestHelper h){
        h.assertTrue(AutonomyConfig.current().autonomy(),"Actual production economic events are enabled");var r=RoyalShipmentSupport.realm(h,"unattended-strategy");RoyalNpcEconomyGameTests.shortage(h,r);
        r.chest(r.source()).clearContent();r.source().getTownhall().getInventory().invalidateCache();r.save();long startDay=day(h);long[] cold={-1};
        h.getLevel().getServer().tickRateManager().requestGameToSprint(38000);
        class Probe implements Runnable{public void run(){
            boolean unloaded=!r.level().isLoaded(r.capital().getCenter()) && !r.level().isLoaded(r.source().getCenter()) && !r.level().isLoaded(r.target().getCenter());
            if(unloaded && cold[0]<0)cold[0]=r.level().getGameTime();
            var plan=new RoyalStrategyStore(r.level()).read(r.kingdom());
            if(unloaded && cold[0]>=0 && plan!=null && plan.evaluatedDay()>startDay && plan.metadataObservedTick()>cold[0]){
                h.assertTrue(plan.status()==RoyalStrategyStore.Status.CONDITIONAL && plan.logistics() && r.level().getServer().getPlayerList().getPlayers().isEmpty(),"Normal events reevaluate a justified conditional native need after actual natural chunk unloading and a real day, with no nearby player");
                h.assertTrue(new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(r.level())).entries().stream().noneMatch(d->d.kingdom().equals(r.kingdom())),"No physical spending authorization or imaginary stock transaction is created while the villages are unavailable");
                System.out.println("STRATEGY_UNATTENDED coldTick="+cold[0]+" evaluatedDay="+plan.evaluatedDay()+" observedTick="+plan.metadataObservedTick()+" status="+plan.status()+" players=0 physicalAuthorizations=0");r.close();h.succeed();return;
            }
            h.runAfterDelay(1,()->run());
        }}h.runAfterDelay(1,new Probe());
    }
}
