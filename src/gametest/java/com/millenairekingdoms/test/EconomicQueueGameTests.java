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
    private static final net.minecraft.server.level.TicketType<UUID> REOPEN = net.minecraft.server.level.TicketType.create("economic_queue_reopen",UUID::compareTo,200);

    private static final class Policy implements AutoCloseable {
        final boolean construction = RoyalConstructionConfig.TRACKING.get();
        final boolean shipping = com.millenairekingdoms.shipping.RoyalShipmentConfig.TRACKING.get();
        Policy() { RoyalConstructionConfig.TRACKING.set(true); com.millenairekingdoms.shipping.RoyalShipmentConfig.TRACKING.set(true); }
        public void close() { RoyalConstructionConfig.TRACKING.set(construction); com.millenairekingdoms.shipping.RoyalShipmentConfig.TRACKING.set(shipping); }
    }
    private static final class HandoffPolicy implements AutoCloseable {
        final boolean autonomy=AutonomyConfig.ENABLED.get();
        HandoffPolicy(){AutonomyConfig.ENABLED.set(true);}
        public void close(){AutonomyConfig.ENABLED.set(autonomy);}
    }
    private static Plan plan(RoyalShipmentSupport.Realm r, UUID id, UUID target, net.minecraft.resources.ResourceLocation selection, boolean logistics) {
        return new Plan(r.kingdom(),r.capital().getId().uuid(),r.capital().getVillageTypeId(),r.level().dimension().location().toString(),
                id,target,selection,logistics,10,11,Math.max(0,r.level().getGameTime()),UUID.randomUUID().toString(),Status.CONDITIONAL,"Controlled unpaid queue fixture");
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unpaidNpcPlanClaimsTheNextNativeSlotBeforeAnotherNaturalChoice(GameTestHelper h) {
        try(var policy=new Policy();var autonomy=new HandoffPolicy();var r=RoyalShipmentSupport.realm(h,"npc-native-handoff")) {
            h.assertTrue(AutonomyConfig.current().autonomy(),"Native handoff uses the actual enabled autonomous policy");
            RoyalNpcEconomyGameTests.money(r,2);var option=RoyalNpcEconomyGameTests.option(r,r.source());
            RoyalNpcEconomyGameTests.materials(h,r,r.source(),option.requiredMaterials(),null);var builder=RoyalNpcEconomyGameTests.builder(h,r);
            try {
                var original=new org.millenaire.village.Village.PendingProject(option.planSet(),option.variant(),0,false,null);
                r.source().setPendingProject(original);
                var parent=plan(r,UUID.randomUUID(),r.from(),option.planSet(),false);var store=new RoyalStrategyStore(r.level());store.write(Lane.DEVELOPMENT,null,parent);
                var service=new RoyalStrategyService(r.level());service.step(r.kingdom(),10,RandomSource.create(21));
                h.assertTrue(r.source().getPendingProject()==original && r.coins()==128,"Unpaid NPC mandate leaves original work and money intact");
                RoyalConstructionRuntime.beforeNativeGrowth(r.level(),r.source());
                // Controlled metadata boundary: the original job has vacated its slot. Physical original building
                // execution/consumption is covered separately by the live native package.
                r.source().setPendingProject(null);
                RoyalConstructionRuntime.beforeNativeGrowth(r.level(),r.source());
                var decision=new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(r.level())).load(parent.id());
                h.assertTrue(decision!=null && decision.choice().kind()==RoyalNpcDecisionStore.Kind.CONSTRUCTION && r.coins()==64
                        && r.source().getPendingProject()!=null && store.read(r.kingdom(),Lane.DEVELOPMENT).id().equals(parent.id()),
                        "The native handoff honors the exact ready unpaid NPC mandate before selecting unrelated natural work; decision="+decision+" coins="+r.coins());
                RoyalConstructionRuntime.beforeNativeGrowth(r.level(),r.source());
                h.assertTrue(r.coins()==64 && r.payments().load(parent.id()).state()==RoyalPaymentJournal.State.COMMITTED,
                        "Repeated native growth callbacks cannot replay the original mandate's fee");h.succeed();
            }finally{builder.discard();}
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unrelatedSupplyCannotStealTheReadyUnpaidNativeHandoff(GameTestHelper h) {
        try(var policy=new Policy();var autonomy=new HandoffPolicy();var r=RoyalShipmentSupport.realm(h,"supply-versus-handoff")) {
            var shortage=RoyalNpcEconomyGameTests.shortage(h,r);var option=RoyalNpcEconomyGameTests.option(r,r.source());
            RoyalNpcEconomyGameTests.materials(h,r,r.source(),option.requiredMaterials(),null);
            int added=r.source().getTownhall().getInventory().add(r.level(),shortage.product(),shortage.sourceBefore());
            h.assertTrue(added==shortage.sourceBefore(),"Actual source storage holds both the planned building materials and genuine export goods");
            r.source().getTownhall().getInventory().invalidateCache();
            var builder=RoyalNpcEconomyGameTests.builder(h,r);
            try {
                var original=new org.millenaire.village.Village.PendingProject(option.planSet(),option.variant(),0,false,null);
                r.source().setPendingProject(original);RoyalNpcEconomyGameTests.money(r,0);
                var parent=plan(r,UUID.randomUUID(),r.from(),option.planSet(),false);var store=new RoyalStrategyStore(r.level());store.write(Lane.DEVELOPMENT,null,parent);
                var service=new RoyalStrategyService(r.level());service.step(r.kingdom(),10,RandomSource.create(22));
                RoyalConstructionRuntime.beforeNativeGrowth(r.level(),r.source());
                h.assertTrue(r.coins()==0 && r.source().getPendingProject()==original,"Unpaid planning preserves existing work without money");
                RoyalNpcEconomyGameTests.money(r,8);r.source().setPendingProject(null);
                h.assertTrue(RoyalNpcEconomyService.automatic(r.level()).inspect(r.kingdom(),10).candidates().stream()
                        .anyMatch(p->p.choice().kind()==RoyalNpcDecisionStore.Kind.SHIPMENT && r.to().equals(p.choice().target())),
                        "A different village really has a payable shipment proposal at this exact construction boundary");
                RoyalConstructionRuntime.beforeNativeGrowth(r.level(),r.source());
                var decision=new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(r.level())).load(parent.id());
                h.assertTrue(decision!=null && decision.choice().kind()==RoyalNpcDecisionStore.Kind.CONSTRUCTION
                        && r.coins()==448 && r.source().getPendingProject()!=null && r.target().getPendingProject()==shortage.pending(),
                        "A ready exact unpaid building must claim its native slot despite an unrelated supply proposal; decision="+decision+" coins="+r.coins()+" plan="+store.read(r.kingdom(),Lane.DEVELOPMENT));
                RoyalConstructionRuntime.beforeNativeGrowth(r.level(),r.source());
                h.assertTrue(r.coins()==448 && r.payments().load(parent.id()).state()==RoyalPaymentJournal.State.COMMITTED,
                        "Repeated handoffs retain the actual single64 fee");
                var supply=service.step(r.kingdom(),11,RandomSource.create(23));
                h.assertTrue(supply!=null && supply.kind()==RoyalNpcDecisionStore.Kind.SHIPMENT && !supply.attempt().equals(parent.id())
                        && store.read(r.kingdom(),Lane.DEVELOPMENT).id().equals(parent.id()) && r.target().getPendingProject()==shortage.pending(),
                        "The unrelated original shortage still receives its own paid logistics operation after the normal cooldown");
                h.succeed();
            }finally{builder.discard();}
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void newDefaultsAllowOneRealHalfTreasuryFeeWhileRetainingOneSilverCoin(GameTestHelper h) {
        try(var policy=new Policy();var r=RoyalShipmentSupport.realm(h,"new-default-budget")) {
            h.assertTrue(RoyalNpcConfig.budgets().reserve()==64 && RoyalNpcConfig.budgets().sharePermille()==500,"Fresh world uses the actual1.2.2 default reserve/share");
            RoyalNpcEconomyGameTests.money(r,2);var option=RoyalNpcEconomyGameTests.option(r,r.source());
            RoyalNpcEconomyGameTests.materials(h,r,r.source(),option.requiredMaterials(),null);var builder=RoyalNpcEconomyGameTests.builder(h,r);
            try {
                var result=new RoyalStrategyService(r.level()).step(r.kingdom(),10,RandomSource.create(14));
                h.assertTrue(result!=null && result.kind()==RoyalNpcDecisionStore.Kind.CONSTRUCTION && r.coins()==64 && r.source().getPendingProject()!=null
                        && r.payments().load(result.attempt()).state()==RoyalPaymentJournal.State.COMMITTED,
                        "An actual64 fee is half the128 treasury, leaves64 real coins and acknowledges genuine original pending work");h.succeed();
            } finally {builder.discard();}
        }
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
            HistoryGameTests.refused(h,()->store.write(linked,plan(r,UUID.randomUUID(),r.to(),option.planSet(),true)));
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

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void linkedPaidConstructionStillAllowsIndependentRealSupplyOrder(GameTestHelper h) {
        try(var policy=new Policy();var r=RoyalShipmentSupport.realm(h,"linked-project-resupply")) {
            RoyalNpcEconomyGameTests.money(r,8);var option=RoyalNpcEconomyGameTests.option(r,r.source());
            RoyalNpcEconomyGameTests.materials(h,r,r.source(),option.requiredMaterials(),null);
            var builder=RoyalNpcEconomyGameTests.builder(h,r);
            try {
                var service=new RoyalStrategyService(r.level());var build=service.step(r.kingdom(),10,RandomSource.create(8));
                h.assertTrue(build!=null && build.kind()==RoyalNpcDecisionStore.Kind.CONSTRUCTION && r.source().getPendingProject()!=null,
                        "Actual native builder and resources create a paid nonterminal construction domain");
                var original=new RoyalStrategyStore(r.level()).read(r.kingdom(),Lane.DEVELOPMENT);
                var pending=r.source().getPendingProject();var shortage=RoyalNpcEconomyGameTests.shortage(h,r); // Explicit fixture stock for a second original project.
                // Freeze the metadata guess on a supplied third village. Actual observations must supersede it.
                var strategyStore=new RoyalStrategyStore(r.level());
                var provisional=plan(r,UUID.randomUUID(),r.capital().getId().uuid(),option.planSet(),true);
                strategyStore.write(Lane.LOGISTICS,null,provisional);
                var shipment=service.step(r.kingdom(),11,RandomSource.create(9));
                var lanes=service.portfolio(r.kingdom());
                h.assertTrue(shipment!=null && shipment.kind()==RoyalNpcDecisionStore.Kind.SHIPMENT && !shipment.attempt().equals(build.attempt())
                        && lanes.development().equals(original) && lanes.logistics().id().equals(shipment.attempt())
                        && r.source().getPendingProject()==pending && r.target().getPendingProject()==shortage.pending(),
                        "A still-linked paid building keeps its ID/native slot while a separate justified shipment pays its own real fee: result="+shipment+" preview="+RoyalNpcEconomyService.automatic(r.level()).inspect(r.kingdom(),11)+" lanes="+lanes);
                r.service().observe(shipment.attempt());h.assertTrue(r.store().load(shipment.attempt()).collected()>0,"The independent supply domain physically collects goods");h.succeed();
            } finally {builder.discard();}
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void smallPartialShipmentLeavesTheSameUnpaidParentAndRealRemainingDeficit(GameTestHelper h) {
        int limit=RoyalNpcConfig.MAX_CARGO.get();
        try(var policy=new Policy();var r=RoyalShipmentSupport.realm(h,"partial-planned-supply")) {
            var shortage=RoyalNpcEconomyGameTests.shortage(h,r);r.target().setPendingProject(null);RoyalNpcConfig.MAX_CARGO.set(1);
            var project=plan(r,UUID.randomUUID(),r.to(),shortage.option().planSet(),false);var store=new RoyalStrategyStore(r.level());store.write(Lane.DEVELOPMENT,null,project);
            var service=new RoyalStrategyService(r.level());var result=service.step(r.kingdom(),10,RandomSource.create(10));
            h.assertTrue(result!=null && result.kind()==RoyalNpcDecisionStore.Kind.SHIPMENT,"A single real available unit is a valid partial supply rather than requiring a whole building batch");
            r.service().observe(result.attempt());var shipment=r.store().load(result.attempt());
            h.assertTrue(shipment.intent().quantity()==1 && shipment.collected()==1 && store.read(r.kingdom(),Lane.DEVELOPMENT).id().equals(project.id())
                    && RoyalNpcEconomyGameTests.count(r,r.target(),shortage.product())==0,
                    "Only one physical item is escrowed; cargo in transit is never credited as already present building material");
            service.step(r.kingdom(),20,RandomSource.create(11));
            h.assertTrue(store.read(r.kingdom(),Lane.LOGISTICS).id().equals(result.attempt()) && r.store().entries().stream().filter(e->e.intent().kingdom().equals(r.kingdom())).count()==1,
                    "An overdue but nonterminal partial shipment keeps its original domain owner; no second cargo is invented");
            RoyalNpcEconomyGameTests.deliver(h,r,result.attempt()); // Explicit controlled domain timer, not a natural-tick observation.
            h.assertTrue(r.store().load(result.attempt()).delivered()==1 && RoyalNpcEconomyGameTests.count(r,r.target(),shortage.product())==1,
                    "The first partial batch is credited once to real target storage before a new shortage is computed");
            var next=service.step(r.kingdom(),23,RandomSource.create(12));
            h.assertTrue(next!=null && next.kind()==RoyalNpcDecisionStore.Kind.SHIPMENT && !next.attempt().equals(result.attempt())
                    && store.read(r.kingdom(),Lane.DEVELOPMENT).id().equals(project.id()) && r.store().load(next.attempt()).intent().quantity()==1,
                    "Remaining physical deficit creates a distinct bounded child shipment after route cooldown, retaining the same unpaid building parent");
            long paid=r.coins();r.service().observe(next.attempt());service.step(r.kingdom(),24,RandomSource.create(13));
            h.assertTrue(r.store().load(next.attempt()).collected()==1 && r.coins()==paid && RoyalNpcEconomyGameTests.count(r,r.target(),shortage.product())==1
                    && r.store().entries().stream().filter(e->e.intent().kingdom().equals(r.kingdom())).count()==2,
                    "Second in-transit cargo is neither counted as delivered material nor duplicated by later policy checks");h.succeed();
        } finally {RoyalNpcConfig.MAX_CARGO.set(limit);}
    }

    @GameTest(templateNamespace=NS,template="empty",batch="economic_queue_restart",timeoutTicks=24000)
    public static void bothUnpaidLanesSurviveAnIndependentJvmWithoutBecomingPayments(GameTestHelper h) {
        var marker=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("economic-queue-restart.dat");
        if (RoyalShipmentSupport.read()) {
            var saved=HistoryFiles.read(marker);var r=RoyalShipmentSupport.restore(h,saved.getCompound("realm"),false);
                var portfolio=new RoyalStrategyStore(r.level()).portfolio(r.kingdom());
                h.assertTrue(portfolio.development().id().equals(saved.getUUID("development")) && portfolio.logistics().id().equals(saved.getUUID("logistics"))
                        && portfolio.development().status()==Status.CONDITIONAL && portfolio.logistics().status()==Status.CONDITIONAL && r.coins()==0,
                        "Both exact unpaid operation identities reopen in an independent JVM without a debit or synthetic coins");
                var chunk=new net.minecraft.world.level.ChunkPos(r.source().getCenter());
                r.level().getChunkSource().addRegionTicket(REOPEN,chunk,2,saved.getUUID("development"),true);
                restoredHandoff(h,r,saved,0);return;
        }
        var r=RoyalShipmentSupport.realm(h,"persist-two-unpaid-lanes");RoyalNpcEconomyGameTests.money(r,0);var option=RoyalNpcEconomyGameTests.option(r,r.source());
        var development=plan(r,UUID.randomUUID(),r.from(),option.planSet(),false);var logistics=plan(r,UUID.randomUUID(),r.to(),option.planSet(),true);
        var store=new RoyalStrategyStore(r.level());store.write(Lane.DEVELOPMENT,null,development);store.write(Lane.LOGISTICS,null,logistics);
        var saved=new CompoundTag();saved.put("realm",r.marker());saved.putUUID("development",development.id());saved.putUUID("logistics",logistics.id());HistoryFiles.write(marker,saved);r.save();h.succeed();
    }
    private static void restoredHandoff(GameTestHelper h,RoyalShipmentSupport.Realm r,CompoundTag saved,int waited){
        var chunk=new net.minecraft.world.level.ChunkPos(r.source().getCenter());
        if(waited%20==0)r.level().getChunkSource().addRegionTicket(REOPEN,chunk,2,saved.getUUID("development"),true);
        // GameTestServer runs ticks without real-time pacing. Give bounded asynchronous neighboring-chunk/entity IO
        // room to finish; never spawn a second NPC in an attempt to repair an unavailable first entity.
        if(!r.level().areEntitiesLoaded(chunk.toLong()) && waited<10000){h.runAfterDelay(1,()->restoredHandoff(h,r,saved,waited+1));return;}
        try{
            h.assertTrue(r.level().areEntitiesLoaded(chunk.toLong()),"Reopened source entity sections must be loaded before the original builder factory; waited="+waited+" centre="+r.source().getCenter()+" ticking="+r.level().isPositionEntityTicking(r.source().getCenter()));
            r.activate(false); // Reapply this fixture's declared capital/storage availability after asynchronous ticks.
            // No strategy step registers this restored mandate: the read-only startup cache must already know it.
            RoyalNpcEconomyGameTests.money(r,2);var option=RoyalNpcEconomyGameTests.option(r,r.source());
            RoyalNpcEconomyGameTests.materials(h,r,r.source(),option.requiredMaterials(),null);var builder=RoyalNpcEconomyGameTests.builder(h,r);
            try(var policy=new Policy();var autonomy=new HandoffPolicy()){
                RoyalConstructionRuntime.beforeNativeGrowth(r.level(),r.source());
                h.assertTrue(r.coins()==64 && r.payments().load(saved.getUUID("development"))!=null
                        && r.payments().load(saved.getUUID("development")).state()==RoyalPaymentJournal.State.COMMITTED
                        && new RoyalStrategyStore(r.level()).portfolio(r.kingdom()).logistics().id().equals(saved.getUUID("logistics")),
                        "The separate JVM rebuilds the exact unpaid handoff before native growth without replacing its logistics sibling; portfolio="+new RoyalStrategyStore(r.level()).portfolio(r.kingdom())+" coins="+r.coins());
                System.out.println("ECONOMIC_HANDOFF_REOPEN waited="+waited+" committedFee=64 retained=64 plan="+saved.getUUID("development"));
            }finally{builder.discard();}
            h.succeed();
        }finally{r.level().getChunkSource().removeRegionTicket(REOPEN,chunk,2,saved.getUUID("development"),true);r.close();}
    }
}
