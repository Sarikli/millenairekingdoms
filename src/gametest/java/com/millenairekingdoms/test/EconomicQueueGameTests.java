package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.autonomy.RoyalStrategyStore.*;
import com.millenairekingdoms.construction.*;
import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.history.HistoryFiles;
import com.millenairekingdoms.shipping.RoyalVillageStock;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real domain requests and physical fees are distinguished from durable unpaid plans. */
@PrefixGameTestTemplate(false)
public final class EconomicQueueGameTests {
    private static final String NS = "millenairekingdoms_economic_queue_tests";

    private static final class Policy implements AutoCloseable {
        final boolean construction = RoyalConstructionConfig.TRACKING.get();
        final boolean shipping = com.millenairekingdoms.shipping.RoyalShipmentConfig.TRACKING.get();
        Policy() { RoyalConstructionConfig.TRACKING.set(true); com.millenairekingdoms.shipping.RoyalShipmentConfig.TRACKING.set(true); }
        public void close() { RoyalConstructionConfig.TRACKING.set(construction); com.millenairekingdoms.shipping.RoyalShipmentConfig.TRACKING.set(shipping); }
    }
    private static Plan plan(RoyalShipmentSupport.Realm r, UUID id, UUID target, net.minecraft.resources.ResourceLocation selection, boolean logistics) {
        return new Plan(r.kingdom(),r.capital().getId().uuid(),r.capital().getVillageTypeId(),r.level().dimension().location().toString(),
                id,target,selection,logistics,10,11,Math.max(0,r.level().getGameTime()),UUID.randomUUID().toString(),Status.CONDITIONAL,"Controlled unpaid queue fixture");
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void resourcePoorKingdomRetainsOneUnpaidProjectAcrossDailyChecks(GameTestHelper h) {
        try (var policy = new Policy(); var r = RoyalShipmentSupport.realm(h,"unpaid-development")) {
            RoyalNpcEconomyGameTests.money(r,0);
            var service = new RoyalStrategyService(r.level());service.step(r.kingdom(),10,RandomSource.create(1));
            var first = service.portfolio(r.kingdom()).development();
            h.assertTrue(first != null && first.id() != null && first.status() == Status.CONDITIONAL,
                    "Legal development need is queued before money, materials or a loaded builder exists");
            for (int day=11;day<=13;day++) service.step(r.kingdom(),day,RandomSource.create(day));
            var current = service.portfolio(r.kingdom()).development();
            h.assertTrue(current.id().equals(first.id()) && current.selection().equals(first.selection()) && r.coins()==0
                    && r.source().getPendingProject()==null && r.target().getPendingProject()==null,
                    "Daily checks retain one genuine unpaid target without invented construction or coins");
            h.assertTrue(new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(r.level())).entries().stream()
                    .noneMatch(d -> d.kingdom().equals(r.kingdom()) && d.choice().kind()!=RoyalNpcDecisionStore.Kind.NONE),
                    "A queued project is not counted as a fee authorization or accepted commission");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void plannedProjectCanBuyActualSuppliesBeforeAnyNativePendingProjectExists(GameTestHelper h) {
        try (var policy = new Policy(); var r = RoyalShipmentSupport.realm(h,"supply-unpaid-project")) {
            var shortage = RoyalNpcEconomyGameTests.shortage(h,r);r.target().setPendingProject(null);
            var id=UUID.randomUUID();var project=plan(r,id,r.to(),shortage.option().planSet(),false);
            var store=new RoyalStrategyStore(r.level());store.write(Lane.DEVELOPMENT,null,project);
            int before=RoyalNpcEconomyGameTests.count(r,r.source(),shortage.product());long coins=r.coins();
            var service=new RoyalStrategyService(r.level());var result=service.step(r.kingdom(),10,RandomSource.create(2));
            h.assertTrue(result!=null && result.kind()==RoyalNpcDecisionStore.Kind.SHIPMENT && result.attempt()!=null,
                    "Real future-building deficit creates a shipment even before original native work is queued");
            h.assertTrue(r.store().load(result.attempt()).stage()==com.millenairekingdoms.shipping.RoyalShipmentStore.Stage.COLLECTING,
                    "The shared service acknowledges the real fee before the separate physical collection step");
            r.service().observe(result.attempt());
            var shipment=r.store().load(result.attempt());
            h.assertTrue(shipment!=null && shipment.collected()>0 && r.coins()==coins-shipment.intent().fee()
                    && RoyalNpcEconomyGameTests.count(r,r.source(),shortage.product())==before-shipment.collected()
                    && r.target().getPendingProject()==null && store.read(r.kingdom(),Lane.DEVELOPMENT).id().equals(id),
                    "Shared domain collects actual goods and one transport fee; unpaid building remains the same separate project");
            long paid=r.coins();int goods=RoyalNpcEconomyGameTests.count(r,r.source(),shortage.product());
            service.step(r.kingdom(),10,RandomSource.create(3));service.step(r.kingdom(),11,RandomSource.create(4));
            h.assertTrue(r.coins()==paid && RoyalNpcEconomyGameTests.count(r,r.source(),shortage.product())==goods,
                    "Existing in-transit supplies cannot be collected or charged again while the parent remains unpaid");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeQueueKeepsItsIdentityWhileUnpaidDevelopmentWaits(GameTestHelper h) {
        try (var policy=new Policy();var r=RoyalShipmentSupport.realm(h,"native-first-queue")) {
            var shortage=RoyalNpcEconomyGameTests.shortage(h,r);var original=r.target().getPendingProject();
            RoyalNpcEconomyGameTests.money(r,0);
            var project=plan(r,UUID.randomUUID(),r.to(),shortage.option().planSet(),false);var store=new RoyalStrategyStore(r.level());store.write(Lane.DEVELOPMENT,null,project);
            new RoyalStrategyService(r.level()).step(r.kingdom(),10,RandomSource.create(5));
            h.assertTrue(r.target().getPendingProject()==original && store.read(r.kingdom(),Lane.DEVELOPMENT).id().equals(project.id())
                    && store.read(r.kingdom(),Lane.DEVELOPMENT).reason().contains("WAITING_NATIVE_PROJECT") && r.coins()==0,
                    "Existing native work is neither replaced nor charged by a queued NPC investment");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void independentLaneWritesCannotLoseOrReuseAnotherOperationsIdentity(GameTestHelper h) {
        try (var r=RoyalShipmentSupport.realm(h,"two-lane-identities")) {
            var option=RoyalNpcEconomyGameTests.option(r,r.source());var store=new RoyalStrategyStore(r.level());
            var development=plan(r,UUID.randomUUID(),r.from(),option.planSet(),false);
            var logistics=plan(r,UUID.randomUUID(),r.to(),option.planSet(),true);
            store.write(Lane.DEVELOPMENT,null,development);store.write(Lane.LOGISTICS,null,logistics);
            var linked=development.status(Status.LINKED,"Existing authorized construction continues independently");store.write(Lane.DEVELOPMENT,development,linked);
            h.assertTrue(store.read(r.kingdom(),Lane.LOGISTICS).equals(logistics) && store.read(r.kingdom(),Lane.DEVELOPMENT).equals(linked),
                    "Construction advancement preserves an independently persisted logistics lane");
            HistoryGameTests.refused(h,()->store.write(Lane.LOGISTICS,null,logistics));
            HistoryGameTests.refused(h,()->new Portfolio(development,plan(r,development.id(),r.to(),option.planSet(),true)));
            h.assertTrue(store.portfolio(r.kingdom()).equals(new Portfolio(linked,logistics)),"Stale writes and colliding domain identities leave both original lanes intact");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void formatOnePlansMigrateLazilyAndFutureFormatsRemainBytePreserved(GameTestHelper h) throws Exception {
        try (var r=RoyalShipmentSupport.realm(h,"legacy-strategy-queue")) {
            var option=RoyalNpcEconomyGameTests.option(r,r.source());var store=new RoyalStrategyStore(r.level());
            var legacy=plan(r,UUID.randomUUID(),r.from(),option.planSet(),false).status(Status.LINKED,"Legacy domain owner");
            store.write(Lane.DEVELOPMENT,null,legacy);var root=HistoryFiles.read(store.file(r.kingdom()));
            HistoryFiles.write(store.file(r.kingdom()),root.getCompound("development"));var original=Files.readAllBytes(store.file(r.kingdom()));
            h.assertTrue(store.read(r.kingdom()).equals(legacy) && Arrays.equals(original,Files.readAllBytes(store.file(r.kingdom()))),
                    "Actual version-one layout reads without rewriting its original domain identity");
            var supply=plan(r,UUID.randomUUID(),r.to(),option.planSet(),true);store.write(Lane.LOGISTICS,null,supply);
            h.assertTrue(HistoryFiles.read(store.file(r.kingdom())).getInt("format")==2 && store.read(r.kingdom(),Lane.DEVELOPMENT).equals(legacy),
                    "Adding an independent lane lazily migrates old strategy data without creating a replacement authorization");
            var future=HistoryFiles.read(store.file(r.kingdom()));future.putInt("format",99);HistoryFiles.write(store.file(r.kingdom()),future);var bytes=Files.readAllBytes(store.file(r.kingdom()));
            HistoryGameTests.refused(h,()->store.portfolio(r.kingdom()));h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(store.file(r.kingdom()))),"Unknown future data is preserved, never reset to an empty economy");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",batch="economic_queue_restart",timeoutTicks=1200)
    public static void bothUnpaidLanesSurviveAnIndependentJvmWithoutBecomingPayments(GameTestHelper h) {
        var marker=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("economic-queue-restart.dat");
        if (RoyalShipmentSupport.read()) {
            var saved=HistoryFiles.read(marker);try(var r=RoyalShipmentSupport.restore(h,saved.getCompound("realm"),false)) {
                var portfolio=new RoyalStrategyStore(r.level()).portfolio(r.kingdom());
                h.assertTrue(portfolio.development().id().equals(saved.getUUID("development")) && portfolio.logistics().id().equals(saved.getUUID("logistics"))
                        && portfolio.development().status()==Status.CONDITIONAL && portfolio.logistics().status()==Status.CONDITIONAL && r.coins()==0,
                        "Both exact unpaid operation identities reopen in an independent JVM without a debit or synthetic coins");h.succeed();return;
            }
        }
        var r=RoyalShipmentSupport.realm(h,"persist-two-unpaid-lanes");RoyalNpcEconomyGameTests.money(r,0);var option=RoyalNpcEconomyGameTests.option(r,r.source());
        var development=plan(r,UUID.randomUUID(),r.from(),option.planSet(),false);var logistics=plan(r,UUID.randomUUID(),r.to(),option.planSet(),true);
        var store=new RoyalStrategyStore(r.level());store.write(Lane.DEVELOPMENT,null,development);store.write(Lane.LOGISTICS,null,logistics);
        var saved=new CompoundTag();saved.put("realm",r.marker());saved.putUUID("development",development.id());saved.putUUID("logistics",logistics.id());HistoryFiles.write(marker,saved);r.save();h.succeed();
    }
}
