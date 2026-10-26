package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.RoyalInvestmentPolicy;
import com.millenairekingdoms.autonomy.RoyalNpcConfig;
import com.millenairekingdoms.autonomy.RoyalNpcEconomyService;
import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore.*;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.shipping.RoyalShipmentConfig;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import com.millenairekingdoms.shipping.RoyalVillageStock;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.AnywoodHelper;
import org.millenaire.culture.ModCultures;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.VillagerSpawnFactory;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;

/** Actual need discovery, native builder entities, protected stocks and shared fees/domains. No completed-building claim. */
@PrefixGameTestTemplate(false)
public final class RoyalNpcEconomyGameTests {
    private static final String NS="millenairekingdoms_royal_npc_economy_tests";
    private static final ResourceLocation BUILD=ResourceLocation.parse("millenaire:build");
    private static RoyalNpcEconomyService service(RoyalShipmentSupport.Realm r){return new RoyalNpcEconomyService(r.level());}
    private static RoyalNpcDecisionStore decisions(RoyalShipmentSupport.Realm r){return new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(r.level()));}
    static void money(RoyalShipmentSupport.Realm r,int silver){r.chest(r.capital()).clearContent();r.chest(r.capital()).setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),silver));r.capital().getTownhall().getInventory().invalidateCache();}
    private static void clear(RoyalShipmentSupport.Realm r,Village v){for(var pos:v.getTownhall().getInventory().getChestPositions())((net.minecraft.world.level.block.entity.ChestBlockEntity)r.level().getBlockEntity(pos)).clearContent();v.getTownhall().getInventory().invalidateCache();}
    private static void add(GameTestHelper h,RoyalShipmentSupport.Realm r,Village v,Item item,int quantity){int added=v.getTownhall().getInventory().add(r.level(),item,quantity);h.assertTrue(added==quantity,"Known fixture materials enter actual native storage in full: "+item+" "+quantity+"/"+added);v.getTownhall().getInventory().invalidateCache();}
    static int count(RoyalShipmentSupport.Realm r,Village v,Item item){v.getTownhall().getInventory().invalidateCache();return v.getTownhall().getInventory().getCount(r.level(),item);}
    static RoyalConstructionCatalog.Option option(RoyalShipmentSupport.Realm r,Village v){return RoyalConstructionCatalog.forNpc(r.level(),r.kingdom(),v.getId().uuid()).options().stream()
            .filter(o->RoyalInvestmentPolicy.role(o.planSet())!=RoyalInvestmentPolicy.Role.UNCLASSIFIED && !o.requiredMaterials().isEmpty())
            .sorted(java.util.Comparator.comparingInt(o->o.requiredMaterials().size())).findFirst().orElseThrow();}
    static void materials(GameTestHelper h,RoyalShipmentSupport.Realm r,Village v,Map<ResourceLocation,Integer> required,ResourceLocation except){
        clear(r,v);for(var entry:required.entrySet())if(!entry.getKey().equals(except) && !AnywoodHelper.isAnywood(entry.getKey()) && entry.getValue()>0)add(h,r,v,BuiltInRegistries.ITEM.getOptional(entry.getKey()).orElseThrow(),entry.getValue());
        if(required.getOrDefault(AnywoodHelper.ANYWOOD_LOG,0)>0 && !AnywoodHelper.ANYWOOD_LOG.equals(except))add(h,r,v,Items.CHERRY_LOG,required.get(AnywoodHelper.ANYWOOD_LOG));
    }
    static MillVillager builder(GameTestHelper h,RoyalShipmentSupport.Realm r){
        var type=ModCultures.getAllVillagerTypes().values().stream().filter(t->t.culture().equals(r.source().getCultureId()) && !t.isChild() && t.goals().contains(BUILD)).sorted(java.util.Comparator.comparing(t->t.id().toString())).findFirst().orElseThrow();
        var entity=VillagerSpawnFactory.spawnInVillage(r.level(),r.source(),type.id(),r.source().getCenter().above(),r.source().getTownhall().getId());
        h.assertTrue(entity!=null && entity.isAlive() && r.level().getEntity(entity.getUUID())==entity && r.source().getVillagerRecords().containsKey(entity.getUUID()),"Actual original factory creates and registers a genuine loaded builder fixture; no fake record/NPC");return entity;
    }
    record Shortage(RoyalConstructionCatalog.Option option,ResourceLocation requirement,Item product,int needed,int sourceBefore,int retained,Village.PendingProject pending){}
    static Shortage shortage(GameTestHelper h,RoyalShipmentSupport.Realm r){
        money(r,4);clear(r,r.source());
        var chosen=RoyalConstructionCatalog.forNpc(r.level(),r.kingdom(),r.to()).options().stream()
                .filter(o->o.requiredMaterials().entrySet().stream().anyMatch(e->e.getValue()>0 && e.getValue()<=64))
                .sorted(java.util.Comparator.comparingInt(o->o.requiredMaterials().size())).findFirst().orElseThrow();
        var required=chosen.requiredMaterials().entrySet().stream().filter(e->e.getValue()>0 && e.getValue()<=64)
                .filter(e->AnywoodHelper.isAnywood(e.getKey()) || BuiltInRegistries.ITEM.getOptional(e.getKey()).isPresent()).findFirst().orElseThrow();
        Item product=AnywoodHelper.isAnywood(required.getKey()) ? Items.CHERRY_LOG : BuiltInRegistries.ITEM.getOptional(required.getKey()).orElseThrow();
        materials(h,r,r.target(),chosen.requiredMaterials(),required.getKey());var pending=new Village.PendingProject(chosen.planSet(),chosen.variant(),0,false,null);r.target().setPendingProject(pending);
        int retained=RoyalVillageStock.forNpc(r.level(),r.kingdom(),r.from()).availability(product).retained();int supplied=Math.addExact(retained,required.getValue()+16);add(h,r,r.source(),product,supplied);
        h.assertTrue(RoyalVillageStock.forNpc(r.level(),r.kingdom(),r.to()).missing(chosen.requiredMaterials()).getOrDefault(required.getKey(),0)>0,"Actual native pending project genuinely lacks the chosen material");
        return new Shortage(chosen,required.getKey(),product,required.getValue(),supplied,retained,pending);
    }
    private static void deliver(GameTestHelper h,RoyalShipmentSupport.Realm r,UUID id){
        var clock=(ServerLevelData)r.level().getLevelData();long before=clock.getGameTime();
        try{
            for(int n=0;n<12;n++){var entry=r.store().load(id);if(entry.terminal())return;if(entry.dispatched() && clock.getGameTime()<entry.due())clock.setGameTime(entry.due());r.service().observe(id);}
            h.fail("Shared shipment did not finish after bounded direct fixture steps");
        }finally{clock.setGameTime(before);}
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeAnywoodAndFixedLogDeficitsAreReadOnlyAndDoNotDoubleCountTheSameTimber(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-needs-anywood")){
            var stock=RoyalVillageStock.forNpc(r.level(),r.kingdom(),r.from());var cherry=ResourceLocation.parse("minecraft:cherry_log");var before=VillageSavedData.get(r.level()).save(new CompoundTag(),r.level().registryAccess());
            var missing=stock.missing(Map.of(cherry,40,AnywoodHelper.ANYWOOD_LOG,80));
            h.assertTrue(missing.equals(Map.of(AnywoodHelper.ANYWOOD_LOG,24)) && r.goods(r.source())==96 && before.equals(VillageSavedData.get(r.level()).save(new CompoundTag(),r.level().registryAccess())),"96 actual logs cover fixed40 plus only56 generic; deficit24, no goods/project/record mutation");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void richVillageWithoutLoadedOriginalBuilderCannotPurchaseMerelyBecauseCoinsExist(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-no-builder")){
            money(r,16);var o=option(r,r.source());materials(h,r,r.source(),o.requiredMaterials(),null);var preview=service(r).inspect(r.kingdom(),10);var result=service(r).evaluate(r.kingdom(),10,RandomSource.create(1));
            h.assertTrue(preview.candidates().isEmpty() && preview.reason().contains("builder") && result.kind()==Kind.NONE && result.outcome()==Outcome.SKIPPED && r.coins()==1024 && r.source().getPendingProject()==null,"Actual rich/material-ready but builderless villages retain all coins and get an explanatory persisted non-action");
            h.assertTrue(decisions(r).load(result.attempt()).nextDay()==11 && service(r).evaluate(r.kingdom(),10,RandomSource.create(1)).attempt()==null,"Failed/non-action attempts cannot form an immediate repeated decision loop");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualBuilderAndMissingFunctionUseTheSharedPaidNativeConstructionPathOnce(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-native-construction")){
            money(r,4);var o=option(r,r.source());materials(h,r,r.source(),o.requiredMaterials(),null);var builder=builder(h,r);
            try{var svc=service(r);var before=VillageSavedData.get(r.level()).save(new CompoundTag(),r.level().registryAccess());var preview=svc.inspect(r.kingdom(),10);
                h.assertTrue(!preview.candidates().isEmpty() && preview.candidates().stream().allMatch(p->p.choice().kind()==Kind.CONSTRUCTION) && before.equals(VillageSavedData.get(r.level()).save(new CompoundTag(),r.level().registryAccess())),"Readonly inspection finds genuine legal/resourced functional gaps with a real loaded original builder");
                var result=svc.evaluate(r.kingdom(),10,RandomSource.create(2));var d=decisions(r).load(result.attempt());var pending=r.source().getPendingProject();
                h.assertTrue(result.kind()==Kind.CONSTRUCTION && result.outcome()==Outcome.ACCEPTED && r.coins()==192 && pending!=null && pending.planSetId().equals(d.choice().selection()) && r.payments().load(result.attempt()).state()==State.COMMITTED,"Actual NPC fee64 follows persisted cooldown and uses existing original pending/SavedData acknowledgement, never free or instantly built work");
                h.assertTrue(svc.evaluate(r.kingdom(),10,RandomSource.create(2)).attempt()==null && r.coins()==192,"Same-period evaluation cannot issue another fee or request");
                var later=svc.evaluate(r.kingdom(),13,RandomSource.create(2));h.assertTrue(later.kind()==Kind.NONE && r.coins()==192 && r.source().getPendingProject()==pending,"A tracked major project blocks another commission after the generic attempt cooldown");h.succeed();
            }finally{builder.discard();}
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void existingResourcedNaturalProjectIsNeitherReplacedNorCharged(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-natural-preserved")){
            money(r,4);var o=option(r,r.source());materials(h,r,r.source(),o.requiredMaterials(),null);var builder=builder(h,r);var pending=new Village.PendingProject(o.planSet(),o.variant(),0,false,null);r.source().setPendingProject(pending);
            try{var result=service(r).evaluate(r.kingdom(),10,RandomSource.create(3));h.assertTrue(result.kind()==Kind.NONE && r.source().getPendingProject()==pending && r.coins()==256,"Ready natural work keeps its exact original pending object, without new royal queue or fee");h.succeed();}finally{builder.discard();}
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void activePlayerRulerAndLocallyOwnedCapitalExcludeNpcEconomicDecisions(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-player-exclusion")){
            money(r,4);var setup=shortage(h,r);r.ruler();var svc=service(r);var result=svc.evaluate(r.kingdom(),10,RandomSource.create(4));
            h.assertTrue(result.attempt()==null && svc.inspect(r.kingdom(),10).candidates().isEmpty() && r.coins()==256 && r.target().getPendingProject()==setup.pending(),"Player rule excludes actual otherwise actionable native shortages without even a policy attempt");
        }
        try(var r=RoyalShipmentSupport.realm(h,"npc-local-exclusion")){
            shortage(h,r);r.capital().setOwner(UUID.randomUUID(),"Local owner");var result=service(r).evaluate(r.kingdom(),10,RandomSource.create(5));
            h.assertTrue(result.kind()==Kind.NONE && r.coins()==256 && result.reason().contains("capital"),"Local player ownership excludes policy even if political NPC rule remains");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void genuineNaturalPendingShortageShipsProtectedActualGoodsWithoutARoyalConstructionOrder(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-natural-shipping")){
            var setup=shortage(h,r);var svc=service(r);var preview=svc.inspect(r.kingdom(),10);
            h.assertTrue(preview.candidates().stream().anyMatch(p->p.choice().kind()==Kind.SHIPMENT && p.choice().target().equals(r.to())) && r.coins()==256,"Actual native deficit and surplus produce a quoted transport need without a royal construction consumer");
            var result=svc.evaluate(r.kingdom(),10,RandomSource.create(6));var decision=decisions(r).load(result.attempt());var choice=decision.choice();var product=BuiltInRegistries.ITEM.get(choice.selection());int before=count(r,r.source(),product)+count(r,r.target(),product);
            h.assertTrue(result.kind()==Kind.SHIPMENT && result.outcome()==Outcome.ACCEPTED && r.coins()==256-choice.fee() && r.store().load(result.attempt()).stage()==RoyalShipmentStore.Stage.COLLECTING && r.target().getPendingProject()==setup.pending(),"Real normal logistics fee precedes cargo; original natural project is unchanged");
            deliver(h,r,result.attempt());var finalEntry=r.store().load(result.attempt());
            h.assertTrue(finalEntry.stage()==RoyalShipmentStore.Stage.DELIVERED && finalEntry.delivered()==choice.quantity() && finalEntry.escrow()==0 && before==count(r,r.source(),product)+count(r,r.target(),product)
                    && count(r,r.source(),product)>=setup.retained() && r.coins()==256-choice.fee() && RoyalVillageStock.forNpc(r.level(),r.kingdom(),r.to()).missing(setup.option().requiredMaterials()).isEmpty(),"Actual pickup/timed shared delivery conserves goods, protects source and resolves the genuine original pending deficit with one fee");
            var later=svc.evaluate(r.kingdom(),13,RandomSource.create(6));h.assertTrue(later.kind()==Kind.NONE && r.coins()==256-choice.fee(),"Resolved native shortage is not sent again merely because funds exist");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void protectedSourceAndActualReservePreventUnjustifiedNativeShipment(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-protected-shortage")){
            var setup=shortage(h,r);clear(r,r.source());add(h,r,r.source(),setup.product(),Math.max(1,setup.retained()));var before=r.coins();var result=service(r).evaluate(r.kingdom(),10,RandomSource.create(7));
            h.assertTrue(result.kind()==Kind.NONE && r.coins()==before && r.target().getPendingProject()==setup.pending(),"Actual protected source goods cannot be drained to solve another village's shortage");
        }
        try(var r=RoyalShipmentSupport.realm(h,"npc-poor-shortage")){
            shortage(h,r);money(r,2);var result=service(r).evaluate(r.kingdom(),10,RandomSource.create(8));h.assertTrue(result.kind()==Kind.NONE && r.coins()==128 && result.reason().contains("reserve"),"Real128 cannot pay transport while retaining default128 reserve; no free cargo/fee intent");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unavailableNativeSourceDefersWithoutForcingItsRemoteStorage(GameTestHelper h) throws Exception {
        try(var r=RoyalShipmentSupport.realm(h,"npc-unloaded-storage")){
            shortage(h,r);var hall=r.source().getTownhall();var original=hall.getInventory();var field=org.millenaire.building.BuildingInstance.class.getDeclaredField("inventory");field.setAccessible(true);
            var remote=new net.minecraft.core.BlockPos(8_000_000,64,8_000_000);var positions=new java.util.ArrayList<>(original.getChestPositions());positions.add(remote);
            try{field.set(hall,new org.millenaire.building.BuildingInventory(positions));var result=service(r).evaluate(r.kingdom(),10,RandomSource.create(9));h.assertTrue(result.kind()==Kind.NONE && r.coins()==256 && !r.level().isLoaded(remote),"Automatic need discovery does not force unavailable source storage or spend on incomplete stock evidence");}finally{field.set(hall,original);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void disabledNewEconomicsDoesNotInspectOrCreateAnAutomaticAttempt(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-economy-disabled")){
            shortage(h,r);boolean old=RoyalNpcConfig.ECONOMIC_ENABLED.get();try{RoyalNpcConfig.ECONOMIC_ENABLED.set(false);var result=service(r).evaluate(r.kingdom(),10,RandomSource.create(10));h.assertTrue(result.attempt()==null && r.coins()==256,"Disabled policy does not issue a new attempt, fee or cargo operation");}finally{RoyalNpcConfig.ECONOMIC_ENABLED.set(old);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void bidirectionalRouteCooldownPreventsBackflowUntilANewActualNeedIsEligible(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"npc-route-cooldown")){
            var setup=shortage(h,r);int old=RoyalNpcConfig.ROUTE_DAYS.get();
            try{
                RoyalNpcConfig.ROUTE_DAYS.set(12);var svc=service(r);var first=svc.evaluate(r.kingdom(),10,RandomSource.create(20));deliver(h,r,first.attempt());
                // Known fixture withdrawal/addition creates a genuine NEW reverse-direction native deficit/surplus.
                clear(r,r.source());r.source().setPendingProject(new Village.PendingProject(setup.option().planSet(),setup.option().variant(),0,false,null));
                materials(h,r,r.source(),setup.option().requiredMaterials(),setup.requirement());
                int retained=RoyalVillageStock.forNpc(r.level(),r.kingdom(),r.to()).availability(setup.product()).retained();add(h,r,r.target(),setup.product(),retained+setup.needed()+16);
                var blocked=svc.evaluate(r.kingdom(),13,RandomSource.create(21));h.assertTrue(blocked.kind()==Kind.NONE && blocked.reason().contains("route cooldown"),"The same village pair cannot immediately ship back even with a new real deficit and surplus");
                var later=svc.evaluate(r.kingdom(),22,RandomSource.create(22));var choice=decisions(r).load(later.attempt()).choice();
                h.assertTrue(later.kind()==Kind.SHIPMENT && choice.source().equals(r.to()) && choice.target().equals(r.from()) && r.coins()==256-decisions(r).load(first.attempt()).choice().fee()-choice.fee(),
                        "After persistent bidirectional cooldown a genuinely new reverse need may pay one independent normal fee, without a forced loop");
            }finally{RoyalNpcConfig.ROUTE_DAYS.set(old);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unsupportedDecisionHistoryCannotBecomeAnEmptyAutomaticBudgetRegistry(GameTestHelper h) throws Exception {
        try(var r=RoyalShipmentSupport.realm(h,"npc-economy-future")){
            var setup=shortage(h,r);var path=RoyalNpcDecisionStore.directory(r.level()).resolve("decisions.dat");Files.createDirectories(path.getParent());byte[] original=Files.exists(path) ? Files.readAllBytes(path) : null;
            var future=new CompoundTag();future.putInt("format",999);future.put("decisions",new net.minecraft.nbt.ListTag());
            try{NbtIo.writeCompressed(future,path);var bytes=Files.readAllBytes(path);boolean refused=false;
                try{service(r).evaluate(r.kingdom(),10,RandomSource.create(23));}catch(IllegalArgumentException|IllegalStateException expected){refused=true;}
                h.assertTrue(refused && java.util.Arrays.equals(bytes,Files.readAllBytes(path)) && r.coins()==256 && r.target().getPendingProject()==setup.pending(),"Actual otherwise actionable native need cannot erase unsupported policy history, charge coins or change native project metadata");
            }finally{if(original==null)Files.delete(path);else Files.write(path,original);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",batch="npc_economy_restart",timeoutTicks=1200)
    public static void actualNpcSelectedPaidAndCollectedShipmentReopensWithItsOriginalAttemptAndRefundsOnce(GameTestHelper h) throws Exception {
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-npc-economy-restart.dat");
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024));try(var r=RoyalShipmentSupport.restore(h,tag,true)){
                var id=tag.getUUID("order");var d=decisions(r).load(id);var before=r.store().load(id);var product=BuiltInRegistries.ITEM.get(d.choice().selection());
                h.assertTrue(d.choice().kind()==Kind.SHIPMENT && d.outcome()==Outcome.ACCEPTED && before.collected()==d.choice().quantity() && before.escrow()==d.choice().quantity() && r.coins()==256-d.choice().fee(),"Actual original chosen need/budget/cooldown, native debit/source and held goods reopen without repair");
                h.assertTrue(service(r).evaluate(r.kingdom(),10,RandomSource.create(11)).attempt()==null,"New JVM cannot replay the original policy proposal during its persisted cooldown");
                for(int n=0;n<6 && !r.store().load(id).terminal();n++)r.service().cancelNpc(r.kingdom(),id);
                h.assertTrue(r.store().load(id).stage()==RoyalShipmentStore.Stage.CANCELLED && r.coins()==256 && count(r,r.source(),product)==tag.getInt("sourceBefore") && r.store().load(id).escrow()==0,"Original pre-dispatch cancellation returns exact actual cargo and9 fee once across JVMs; attempt history remains retained");
                System.out.println("ROYAL_NPC_ECONOMY_RESTART_READ_PASS treasury=256 returned="+before.collected()+" attempt="+d.outcome());h.succeed();return;
            }
        }
        var r=RoyalShipmentSupport.realm(h,"npc-economy-restart");var setup=shortage(h,r);var result=service(r).evaluate(r.kingdom(),10,RandomSource.create(11));h.assertTrue(result.kind()==Kind.SHIPMENT,"Actual native need selected shipment before restart");r.service().observe(result.attempt());var tag=r.marker();tag.putUUID("order",result.attempt());tag.putInt("sourceBefore",setup.sourceBefore());NbtIo.writeCompressed(tag,marker);r.save();r.capital().setActive(false);r.source().setActive(false);r.target().setActive(false);VillageSavedData.get(level).setDirty();
        System.out.println("ROYAL_NPC_ECONOMY_RESTART_WRITE_PASS treasury="+r.coins()+" held="+r.store().load(result.attempt()).escrow());h.succeed();
    }
    private RoyalNpcEconomyGameTests(){}
}
