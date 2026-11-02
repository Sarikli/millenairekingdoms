package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.history.HistoryFiles;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Three independent JVMs: durable intention, actual halt before domain/payment, then verified fresh reconsideration. */
@PrefixGameTestTemplate(false)
public final class RoyalStrategyCrashGameTests {
    @GameTest(templateNamespace="millenairekingdoms_strategy_economy_crash_tests",template="empty",timeoutTicks=1200)
    public static void actualInterruptedUnperformedIntentionCanRecoverWithoutReplayingItsAuthorization(GameTestHelper h){
        com.millenairekingdoms.shipping.RoyalShipmentConfig.TRACKING.set(true);var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("strategy-economy-crash.dat");var cutMarker=marker.resolveSibling("strategy-economy-cut.dat");var phase=System.getProperty("kingdoms.tests.strategyEconomyPhase","prepare");var cut=System.getProperty("kingdoms.tests.strategyEconomyCut","before-decision");
        if(phase.equals("prepare")){
            var f=RoyalShipmentSupport.realm(h,"strategy-process-cut");RoyalNpcEconomyGameTests.shortage(h,f);f.capital().setActive(false);var service=new RoyalStrategyService(level);service.step(f.kingdom(),Math.max(0,level.getDayTime())/24000,RandomSource.create(1));var plan=service.inspect(f.kingdom());var tag=f.marker();tag.putUUID("intention",plan.id());HistoryFiles.write(marker,tag);f.save();h.assertTrue(plan.status()==RoyalStrategyStore.Status.CONDITIONAL && f.coins()==256,"Persist real native shortage, unpaid intention and actual256 before independent cut JVM");h.succeed();return;
        }
        var f=RoyalShipmentSupport.restore(h,HistoryFiles.read(marker),true);var service=new RoyalStrategyService(level);var plan=service.inspect(f.kingdom());var decisions=new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(level));
        if(phase.equals("cut")){
            h.assertTrue(plan.status()==RoyalStrategyStore.Status.CONDITIONAL,"Original exact unpaid native intention reopens without repair");new RoyalStrategyStore(level).write(plan,plan.status(RoyalStrategyStore.Status.ISSUING,"Test actual halt at durable intention boundary"));
            if(cut.equals("after-decision")){
                var preview=RoyalNpcEconomyService.automatic(level).inspect(f.kingdom(),plan.evaluatedDay());var proposal=preview.candidates().stream().filter(p->p.choice().kind()==RoyalNpcDecisionStore.Kind.SHIPMENT && p.choice().target().equals(plan.target())).findFirst().orElseThrow();var limits=RoyalNpcConfig.budgets();decisions.begin(new RoyalNpcDecisionStore.Decision(plan.id(),plan.dimension(),plan.kingdom(),plan.evaluatedDay(),plan.nextDay(),new RoyalNpcDecisionStore.Budget(plan.capital(),plan.capitalType(),limits.reserve(),limits.sharePermille()),proposal.choice(),RoyalNpcDecisionStore.Outcome.ISSUING,"Actual bound native proposal, cut before shared domain service invocation"));
            }
            h.assertTrue(Set.of("before-decision","after-decision").contains(cut) && f.store().load(plan.id())==null && f.payments().load(plan.id())==null && f.coins()==256,"There is no domain/payment/physical mutation before the actual cut");var tag=new CompoundTag();tag.putUUID("id",plan.id());tag.putString("cut",cut);HistoryFiles.write(cutMarker,tag);System.out.println("EXPECTED_STRATEGY_ECONOMY_PROCESS_HALT cut="+cut+" intention="+plan.id()+" value=256 domain=false payment=false");System.out.flush();Runtime.getRuntime().halt(70);return;
        }
        h.assertTrue(phase.equals("read"),"Known actual strategy process recovery phase");var tag=HistoryFiles.read(cutMarker);var old=decisions.load(plan.id());h.assertTrue(plan.id().equals(tag.getUUID("id")) && f.coins()==256 && f.store().load(plan.id())==null && f.payments().load(plan.id())==null,"Actual halt preserves unspent256 and proves absence through strict domain/payment readers");service.step(f.kingdom(),plan.evaluatedDay(),RandomSource.create(2));h.assertTrue(service.inspect(f.kingdom()).id().equals(plan.id()) && f.coins()==256,"Original economic cooldown survives actual JVM interruption");long reconsider=plan.nextDay()+100;var result=service.step(f.kingdom(),reconsider,RandomSource.create(3));h.assertTrue(result!=null && result.attempt()!=null && !result.attempt().equals(plan.id()) && f.coins()==247 && f.store().load(result.attempt())!=null && Objects.equals(old,decisions.load(plan.id())) && f.payments().load(plan.id())==null,"Fresh native evaluation creates one new paid9 shipment; original authorization is never replayed, edited or repaid");service.step(f.kingdom(),reconsider+1,RandomSource.create(5));h.assertTrue(f.coins()==247,"Repeated policy pass cannot duplicate the newly paid domain work");System.out.println("STRATEGY_ECONOMY_CRASH_REOPEN cut="+tag.getString("cut")+" original="+plan.id()+" fresh="+result.attempt()+" before=256 after=247 fee=9 originalReplayed=false");h.succeed();
    }
}
