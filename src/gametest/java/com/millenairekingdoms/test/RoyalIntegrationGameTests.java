package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.RoyalNpcEconomyService;
import com.millenairekingdoms.autonomy.RoyalNpcRuntime;
import com.millenairekingdoms.construction.RoyalConstructionConfig;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore.Kind;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.shipping.RoyalShipmentConfig;
import com.millenairekingdoms.shipping.RoyalShipmentStore.Stage;
import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real inventory/payment/political integration. Controlled daily clock/raid receipts are not physical work/combat. */
@PrefixGameTestTemplate(false)
public final class RoyalIntegrationGameTests {
    private static final String NS="millenairekingdoms_royal_integration_tests";
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void automaticEconomicsDoesNotChargeForTwoPausedDomainTrackers(GameTestHelper h){
        boolean construction=RoyalConstructionConfig.TRACKING.get(),shipping=RoyalShipmentConfig.TRACKING.get();
        try(var r=RoyalShipmentSupport.realm(h,"Both paused")){
            RoyalNpcEconomyGameTests.shortage(h,r);RoyalConstructionConfig.TRACKING.set(false);RoyalShipmentConfig.TRACKING.set(false);
            var report=RoyalNpcRuntime.step(h.getLevel(),16);
            h.assertTrue(report.attempts()==0 && r.coins()==256 && new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(r.level())).entries().stream().noneMatch(d->d.kingdom().equals(r.kingdom())),"Paused automatic domain trackers must not create paid work or consume a decision cooldown");h.succeed();
        }finally{RoyalConstructionConfig.TRACKING.set(construction);RoyalShipmentConfig.TRACKING.set(shipping);}
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeShortageShippingWorksWithAutomaticConstructionPaused(GameTestHelper h){
        boolean construction=RoyalConstructionConfig.TRACKING.get(),shipping=RoyalShipmentConfig.TRACKING.get();
        try(var r=RoyalShipmentSupport.realm(h,"Shipping only")){
            RoyalNpcEconomyGameTests.shortage(h,r);RoyalConstructionConfig.TRACKING.set(false);RoyalShipmentConfig.TRACKING.set(true);
            var result=RoyalNpcEconomyService.automatic(r.level()).evaluate(r.kingdom(),10,RandomSource.create(1));
            h.assertTrue(result.kind()==Kind.SHIPMENT && r.coins()==247 && r.payments().load(result.attempt()).state()==State.PAID,"Automatic native shortages use paid shipping without enabling construction orders; dispatch has not yet earned the refundable fee");h.succeed();
        }finally{RoyalConstructionConfig.TRACKING.set(construction);RoyalShipmentConfig.TRACKING.set(shipping);}
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeCommissionWorksWithoutShippingEvenWithAnUnrelatedNaturalShortage(GameTestHelper h){
        boolean construction=RoyalConstructionConfig.TRACKING.get(),shipping=RoyalShipmentConfig.TRACKING.get();
        try(var r=RoyalShipmentSupport.realm(h,"Construction only")){
            var shortage=RoyalNpcEconomyGameTests.shortage(h,r);var option=RoyalNpcEconomyGameTests.option(r,r.source());RoyalNpcEconomyGameTests.materials(h,r,r.source(),option.requiredMaterials(),null);
            var builder=RoyalNpcEconomyGameTests.builder(h,r);
            try{RoyalConstructionConfig.TRACKING.set(true);RoyalShipmentConfig.TRACKING.set(false);
                var result=RoyalNpcEconomyService.automatic(r.level()).evaluate(r.kingdom(),10,RandomSource.create(2));
                h.assertTrue(result.kind()==Kind.CONSTRUCTION && r.coins()==192 && r.source().getPendingProject()!=null && r.target().getPendingProject()==shortage.pending(),"Automatic legal/resourced commission remains independent; paused shipping leaves the unrelated native shortage intact");h.succeed();
            }finally{builder.discard();}
        }finally{RoyalConstructionConfig.TRACKING.set(construction);RoyalShipmentConfig.TRACKING.set(shipping);}
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualDailyTaxReachesTheConservativeCommissionBudgetAndCannotPayAgainAfterRestart(GameTestHelper h)throws Exception{
        var level=h.getLevel();var data=KingdomSavedData.get(level);var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-integration-tax.dat");
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());
            try(var r=RoyalShipmentSupport.restore(h,tag,true)){
                var id=tag.getUUID("attempt");long day=tag.getLong("day");
                h.assertTrue(r.coins()==248 && data.owed(r.kingdom())==0 && data.day()==day && r.payments().load(id).state()==State.COMMITTED && r.source().getPendingProject()!=null,"Original tax-funded native pending, single fee and actual248 coins survive JVM restart without repair");
                h.assertTrue(!EconomyService.runDay(level,data,day) && new RoyalNpcEconomyService(level).evaluate(r.kingdom(),day,RandomSource.create(3)).attempt()==null && r.coins()==248,"Persisted tax day/decision cooldown forbid duplicated income or fees");
                System.out.println("ROYAL_INTEGRATION_TAX_RESTART_READ_PASS coins=248 owed=0 fee=64 dailyIncome=12");h.succeed();return;
            }
        }
        var r=RoyalShipmentSupport.realm(h,"Ten actual tax days");RoyalNpcEconomyGameTests.money(r,3);var option=RoyalNpcEconomyGameTests.option(r,r.source());RoyalNpcEconomyGameTests.materials(h,r,r.source(),option.requiredMaterials(),null);var builder=RoyalNpcEconomyGameTests.builder(h,r);boolean saved=false;
        try{
            h.assertTrue(EconomyService.dailyIncome(level,data,r.kingdom())==12,"Capital and two attached completed level1 halls each yield4 deniers");long start=Math.max(0,data.day());UUID paid=null;
            for(int n=1;n<=10;n++){
                h.assertTrue(EconomyService.runDay(level,data,start+n),"Actual native tax day advances once");var result=new RoyalNpcEconomyService(level).evaluate(r.kingdom(),start+n,RandomSource.create(3));
                if(result.kind()==Kind.CONSTRUCTION){h.assertTrue(n==7 && paid==null,"Reserve/share protect the first six days, then permit one justified commission");paid=result.attempt();}
                h.assertTrue(r.coins()==192+12*n-(paid==null?0:64) && data.owed(r.kingdom())==0,"Physical currency equals initial192 plus actual daily income minus one fee, never owed balances");
            }
            h.assertTrue(paid!=null && r.coins()==248,"Ten real taxed days support one64 commission with248 retained");var tag=r.marker();tag.putUUID("attempt",paid);tag.putLong("day",start+10);NbtIo.writeCompressed(tag,marker);r.save();saved=true;r.capital().setActive(false);r.source().setActive(false);r.target().setActive(false);
            System.out.println("ROYAL_INTEGRATION_TAX_WRITE_PASS coins=248 owed=0 fee=64 dailyIncome=12 days=10");h.succeed();
        }finally{builder.discard();if(!saved)r.close();}
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void playerClaimRetiresAnActualNpcSelectedCargoAndCannotReviveIt(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"NPC to player")){
            var need=RoyalNpcEconomyGameTests.shortage(h,r);var svc=new RoyalNpcEconomyService(r.level());var result=svc.evaluate(r.kingdom(),10,RandomSource.create(4));var id=result.attempt();r.service().observe(id);
            h.assertTrue(r.store().load(id).escrow()==need.needed(),"Actual selected native goods are held before player takeover");r.ruler();
            h.assertTrue(r.store().load(id).cancellationRequested() && svc.evaluate(r.kingdom(),20,RandomSource.create(4)).attempt()==null,"Actual player authority retires old intent before publication and excludes new NPC decisions");
            settle(r,id);h.assertTrue(r.store().load(id).stage()==Stage.CANCELLED && r.coins()==256 && RoyalNpcEconomyGameTests.count(r,r.source(),need.product())==need.sourceBefore() && r.store().load(id).escrow()==0,"Predispatch retirement conserves all actual goods and refunds the single9 fee");
            KingdomSavedData.get(r.level()).clearRuler(r.kingdom());r.service().observe(id);h.assertTrue(r.store().load(id).stage()==Stage.CANCELLED && r.coins()==256,"Abdication cannot revive old policy/domain UUID or create another refund");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualNpcSelectedCargoIsRetiredBeforeTwoWinMemberConquestPublishes(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"NPC target conquest");var attacker=RoyalShipmentSupport.realm(h,"Integration attacker")){
            var need=RoyalNpcEconomyGameTests.shortage(h,r);var result=new RoyalNpcEconomyService(r.level()).evaluate(r.kingdom(),10,RandomSource.create(5));var id=result.attempt();r.service().observe(id);
            var data=KingdomSavedData.get(r.level());data.declareWar(attacker.kingdom(),r.kingdom(),1);
            for(int n=0;n<2;n++){var raid=data.begin(attacker.from(),r.to(),n+1,null);data.recordVictory(raid);data.finish(raid,KingdomSavedData.Outcome.LOST);}
            h.assertTrue(attacker.kingdom().equals(data.owner(r.to())) && r.store().load(id).cancellationRequested(),"Actual two-win political transition retires selected NPC cargo before changing target membership; combat is a separate existing suite");
            settle(r,id);h.assertTrue(r.store().load(id).stage()==Stage.CANCELLED && r.coins()==256 && RoyalNpcEconomyGameTests.count(r,r.source(),need.product())==need.sourceBefore() && RoyalNpcEconomyGameTests.count(r,r.target(),need.product())==0,"Conquered target receives no cargo, original source receives its held5 back and unstarted fee refunds once");h.succeed();
        }
    }
    private static void settle(RoyalShipmentSupport.Realm r,UUID id){for(int n=0;n<8 && !r.store().load(id).terminal();n++)r.service().observe(id);}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void destroyedNpcKingdomPreservesHeldCargoAndUnsettledFeeWithoutRedirectingIt(GameTestHelper h){
        try(var r=RoyalShipmentSupport.realm(h,"NPC collapse");var attacker=RoyalShipmentSupport.realm(h,"Collapse attacker")){
            var need=RoyalNpcEconomyGameTests.shortage(h,r);var svc=new RoyalNpcEconomyService(r.level());var result=svc.evaluate(r.kingdom(),10,RandomSource.create(6));var id=result.attempt();r.service().observe(id);
            var data=KingdomSavedData.get(r.level());data.declareWar(attacker.kingdom(),r.kingdom(),1);
            for(var target:java.util.List.of(r.from(),r.to(),r.capital().getId().uuid()))for(int n=0;n<2;n++){
                var raid=data.begin(attacker.from(),target,n+1,null);data.recordVictory(raid);data.finish(raid,KingdomSavedData.Outcome.LOST);
            }
            h.assertTrue(data.kingdom(r.kingdom()).fallen() && r.store().load(id).cancellationRequested() && svc.evaluate(r.kingdom(),20,RandomSource.create(6)).attempt()==null,"Actual two-win member/capital transitions apply the60-percent rule and exclude further decisions for the fallen kingdom");
            settle(r,id);var held=r.store().load(id);
            h.assertTrue(!held.terminal() && held.blocked()==com.millenairekingdoms.shipping.RoyalShipmentStore.Blocked.MEMBERSHIP && held.escrow()==need.needed() && r.coins()==247 && RoyalNpcEconomyGameTests.count(r,r.source(),need.product())+held.escrow()==need.sourceBefore() && RoyalNpcEconomyGameTests.count(r,r.target(),need.product())==0,"Captured original source/capital cannot authorize a return/refund; held goods and fee liability remain explicit, never deleted, credited to the conqueror or assumed complete");
            System.out.println("ROYAL_INTEGRATION_COLLAPSE_LIABILITY escrow="+held.escrow()+" fee=9 stage="+held.stage()+" blocked="+held.blocked());h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void original111PoliticalAndEconomicSaveSurvivesNewEconomicsAndSeparateJvm(GameTestHelper h)throws Exception{legacy(h,"1.1.1");}
    @GameTest(templateNamespace=NS,template="empty")
    public static void original110ConquestSaveSurvivesNewEconomicsAndSeparateJvm(GameTestHelper h)throws Exception{legacy(h,"1.1.0");}
    private static void legacy(GameTestHelper h,String version)throws Exception{
        CompoundTag original;try(var in=RoyalIntegrationGameTests.class.getResourceAsStream("/review-"+version+"-kingdoms.dat")){
            if(in==null)throw new IllegalStateException("Missing original serializer fixture "+version);original=NbtIo.readCompressed(in,NbtAccounter.unlimitedHeap()).getCompound("data");
        }
        var storage=h.getLevel().getDataStorage();String name="royal_120_legacy_"+version.replace('.','_');KingdomSavedData loaded;
        if(RoyalShipmentSupport.read())loaded=storage.get(KingdomSavedData.factory(),name);
        else{loaded=KingdomSavedData.load(original.copy(),h.getLevel().registryAccess());storage.set(name,loaded);loaded.setDirty();}
        h.assertTrue(loaded!=null && KingdomSavedData.FORMAT==6 && canonical(original).equals(canonical(loaded.save(new CompoundTag(),h.getLevel().registryAccess()))),"Every original "+version+" political/economic field remains unchanged through the current serializer and JVM reopen; no guessed royal intents");
        if(version.equals("1.1.1"))h.assertTrue(loaded.day()==42 && loaded.nextDecisionDay()==70 && loaded.owed().values().stream().mapToLong(Long::longValue).sum()==177,"Original1.1.1 daily marker, NPC schedule and177 undelivered deniers remain ledger values, not spendable new currency");
        System.out.println("ROYAL_LEGACY_"+version+"_"+(RoyalShipmentSupport.read()?"RESTART_READ_PASS":"WRITE_PASS"));h.succeed();
    }
    private static net.minecraft.nbt.Tag canonical(net.minecraft.nbt.Tag tag){
        if(tag instanceof CompoundTag c){var out=new CompoundTag();for(var key:c.getAllKeys())out.put(key,canonical(c.get(key)));return out;}
        if(tag instanceof net.minecraft.nbt.ListTag list){var values=new java.util.ArrayList<net.minecraft.nbt.Tag>();list.forEach(v->values.add(canonical(v)));values.sort(java.util.Comparator.comparing(Object::toString));var out=new net.minecraft.nbt.ListTag();out.addAll(values);return out;}return tag.copy();
    }
    private RoyalIntegrationGameTests(){}
}
