package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.economy.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Proven absence at interrupted intention/decision boundaries, distinct from real paid domain recovery. */
@PrefixGameTestTemplate(false)
public final class RoyalStrategyRecoveryGameTests {
    private static final String NS="millenairekingdoms_strategy_recovery_tests";
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void intentionInterruptedBeforeDecisionCanBeReconsideredAfterCooldown(GameTestHelper h){emptyAuthorization(h,false);}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void skippedLinkedDecisionCannotStrandAllFutureInvestment(GameTestHelper h){emptyAuthorization(h,true);}
    private static void emptyAuthorization(GameTestHelper h,boolean skipped){
        try(var r=RoyalShipmentSupport.realm(h,"interrupted-strategy")){
            RoyalNpcEconomyGameTests.shortage(h,r);r.capital().setActive(false);var service=new RoyalStrategyService(r.level());long day=Math.max(0,r.level().getDayTime())/24000;service.step(r.kingdom(),day,RandomSource.create(1));var original=service.inspect(r.kingdom());var store=new RoyalStrategyStore(r.level());var stopped=original.status(skipped?RoyalStrategyStore.Status.LINKED:RoyalStrategyStore.Status.ISSUING,"Test interruption at persisted intention/authorization boundary");store.write(original,stopped);
            if(skipped){var limits=RoyalNpcConfig.budgets();new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(r.level())).begin(new RoyalNpcDecisionStore.Decision(stopped.id(),stopped.dimension(),stopped.kingdom(),stopped.evaluatedDay(),stopped.nextDay(),new RoyalNpcDecisionStore.Budget(stopped.capital(),stopped.capitalType(),limits.reserve(),limits.sharePermille()),RoyalNpcDecisionStore.Choice.none(),RoyalNpcDecisionStore.Outcome.SKIPPED,"No physical proposal was authorized"));}
            service.step(r.kingdom(),day,RandomSource.create(2));h.assertTrue(service.inspect(r.kingdom()).id().equals(stopped.id()) && service.inspect(r.kingdom()).status()!=RoyalStrategyStore.Status.INVALIDATED && r.coins()==256,"Original cooldown is retained; interrupted identity is never blindly replayed");
            long reconsider=day+100;service.step(r.kingdom(),reconsider,RandomSource.create(3));h.assertTrue(service.inspect(r.kingdom()).status()==RoyalStrategyStore.Status.INVALIDATED && r.coins()==256 && r.store().load(stopped.id())==null && r.payments().load(stopped.id())==null,"Strict absence of both domain records and any payment after cooldown safely retires the unperformed intention");
            r.capital().setActive(true);var result=service.step(r.kingdom(),reconsider,RandomSource.create(4));h.assertTrue(result!=null && result.attempt()!=null && !result.attempt().equals(stopped.id()) && r.coins()==247 && r.store().load(result.attempt())!=null,"A fresh current native shortage evaluation pays exactly one9 fee under a new ID, preserving old decision history");service.step(r.kingdom(),reconsider+1,RandomSource.create(5));h.assertTrue(r.coins()==247,"Nonterminal newly issued domain work is never duplicated");System.out.println("STRATEGY_SAFE_RECONSIDERATION skipped="+skipped+" original="+stopped.id()+" fresh="+result.attempt()+" before=256 after=247 fee=9");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void financialOrDomainFootprintKeepsOriginalRecoveryOwner(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"paid-strategy-recovery")){
            RoyalNpcEconomyGameTests.shortage(h,r);var service=new RoyalStrategyService(r.level());long day=Math.max(0,r.level().getDayTime())/24000;var result=service.step(r.kingdom(),day,RandomSource.create(7));var linked=service.inspect(r.kingdom());h.assertTrue(result!=null && r.coins()==247 && r.store().load(linked.id())!=null && r.payments().load(linked.id())!=null,"Fixture has a real paid domain operation, not a fabricated financial receipt");new RoyalStrategyStore(r.level()).write(linked,linked.status(RoyalStrategyStore.Status.ISSUING,"Simulated missing strategic acknowledgement after actual shared fee/domain save"));service.step(r.kingdom(),day+100,RandomSource.create(8));h.assertTrue(service.inspect(r.kingdom()).status()==RoyalStrategyStore.Status.LINKED && service.inspect(r.kingdom()).id().equals(linked.id()) && r.coins()==247,"Any original payment/domain footprint prevents abandonment or a replacement authorization even after cooldown");h.succeed();
        }
    }
}
