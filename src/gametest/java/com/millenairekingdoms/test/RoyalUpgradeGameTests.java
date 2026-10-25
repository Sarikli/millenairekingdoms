package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.*;
import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.gui.RoyalConstructionView;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.*;
import org.millenaire.culture.ModCultures;
import org.millenaire.item.ModItems;
import org.millenaire.village.*;

/** Metadata/financial boundary fixtures; physical completion is a separate original-NPC live gate. */
@PrefixGameTestTemplate(false)
public final class RoyalUpgradeGameTests {
    static final String NS="millenairekingdoms_royal_gui_tests";
    record F(RoyalPaymentGameTests.Fixture bank,ServerPlayer player,BuildingInstance target) implements AutoCloseable{
        RoyalConstructionCatalog.Catalog catalog(){return RoyalConstructionCatalog.forPlayer(player,bank.village().getId().uuid());}
        CompoundTag frame(){return RoyalConstructionView.build(player,bank.kingdom());}
        RoyalConstructionStore store(){return new RoyalConstructionStore(bank.level().getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"));}
        @Override public void close(){RoyalConstructionView.forget(player.getUUID());bank.close();}
    }
    static F fixture(GameTestHelper h,String name){
        var bank=RoyalPaymentGameTests.persistentFixture(h,"upgrade-"+name);bank.village().setActive(true);bank.village().setNoProjectsLeftUntil(Long.MAX_VALUE);
        bank.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),8));var player=new Stage5Support.TestPlayer(bank.level(),"Upgrade ruler");
        bank.village().addReputation(player.getUUID(),32768);KingdomSavedData.get(bank.level()).claimRule(bank.kingdom(),player.getUUID(),"Upgrade ruler",72);
        for(var set:ModCultures.getAllBuildingPlanSets().values().stream().filter(s->s.culture().equals(bank.village().getCultureId()) && !s.isTownHall() && !s.isSubBuilding()).sorted(Comparator.comparing(s->s.id().toString())).toList())
            for(var variant:set.variants().keySet().stream().sorted().toList())if(set.hasNextLevel(variant,0)){
                var b=new BuildingInstance(BuildingId.random(),set.getPlanId(variant,0),bank.village().getCenter().offset(20,0,20),Rotation.NONE,BuildingInstance.Status.COMPLETE,set.id(),variant,0);b.setUpgradesAllowed(true);bank.village().addBuilding(b);
                if(RoyalConstructionCatalog.forPlayer(player,bank.village().getId().uuid()).upgrades().stream().anyMatch(o->b.getId().uuid().equals(o.upgradeBuilding())))return new F(bank,player,b);
                b.setUpgradesAllowed(false);
            }
        throw new IllegalStateException("No genuine non-centre upgrade passes original conditions for fixture culture "+bank.village().getCultureId());
    }
    static CompoundTag offer(F f){return f.frame().getList("plans",Tag.TAG_COMPOUND).stream().map(t->(CompoundTag)t).filter(t->t.hasUUID("building") && t.getUUID("building").equals(f.target.getId().uuid())).findFirst().orElseThrow();}
    static RoyalConstructionStore.Entry order(F f,CompoundTag offer){return RoyalConstructionView.order(f.player,f.bank.village().getId().uuid(),offer.getUUID("offer"),f.target.getPlanSetId());}
    @GameTest(templateNamespace=NS,template="empty",batch="upgrade_catalog",timeoutTicks=1200)
    public static void onlyOriginalNonCentreUpgradesAreOfferedAndPricesRise(GameTestHelper h){try(var f=fixture(h,"catalog")){
        var choice=f.catalog().upgrades().stream().filter(o->f.target.getId().uuid().equals(o.upgradeBuilding())).findFirst().orElseThrow();
        h.assertTrue(choice.targetLevel()==1 && choice.patronageFee()==128 && f.catalog().upgrades().stream().noneMatch(o->o.upgradeBuilding().equals(f.bank.village().getTownhall().getId().uuid())),"Only the real next level of a non-centre building is offered");
        h.assertTrue(RoyalPrices.upgrade(RoyalEconomyConfig.current(),1)==128 && RoyalPrices.upgrade(RoyalEconomyConfig.current(),2)==192 && RoyalPrices.upgrade(RoyalEconomyConfig.current(),3)==256,"Each next level costs one more64 base fee, independent of materials");
        boolean refused=false;try{new RoyalConstructionService(f.bank.level()).requestQuotedUpgrade(f.player,f.bank.village().getId().uuid(),f.bank.village().getTownhall().getId().uuid(),UUID.randomUUID(),128);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused && f.bank.value()==512,"A forged centre upgrade does not reserve/debit");f.target.setUpgradesAllowed(false);h.assertTrue(f.catalog().upgrades().isEmpty(),"Native upgrade permission is not bypassed");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",batch="upgrade_pay",timeoutTicks=1200)
    public static void guiBindsTheExistingBuildingAndChargesItsUpgradeOnce(GameTestHelper h){try(var f=fixture(h,"paid")){
        var offer=offer(f);var entry=order(f,offer);order(f,offer);var pending=f.bank.village().getPendingProject();
        h.assertTrue(entry.stage()==RoyalConstructionStore.Stage.QUEUED && entry.intent().upgrade() && entry.intent().upgradeBuilding().equals(f.target.getId().uuid()) && entry.intent().originalBuildings().contains(f.target.getId().uuid())
                && pending.isUpgrade() && pending.level()==1 && pending.buildingId().equals(f.target.getId()) && f.target.getLevel()==0 && f.target.getStatus()==BuildingInstance.Status.COMPLETE && f.bank.value()==384,"Actual GUI offer pays128 once and publishes original upgrade pending without fabricating work");
        h.assertTrue(new RoyalPaymentService(f.bank.level()).inspectPlayer(f.player,entry.intent().id()).state()==RoyalPaymentJournal.State.COMMITTED,"Fee follows durable native acceptance");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",batch="upgrade_wait",timeoutTicks=1200)
    public static void upgradeWaitsBehindNativeWorkAndCancelsWithoutChangingIt(GameTestHelper h){try(var f=fixture(h,"wait")){
        var other=f.catalog().options().getFirst();var pending=new Village.PendingProject(other.planSet(),other.variant(),0,false,null);f.bank.village().setPendingProject(pending);
        var offer=offer(f);var entry=order(f,offer);h.assertTrue(entry.stage()==RoyalConstructionStore.Stage.WAITING && f.bank.value()==512 && f.bank.village().getPendingProject()==pending,"Upgrade shares the unpaid single-slot queue");
        new RoyalConstructionService(f.bank.level()).cancelPlayer(f.player,entry.intent().id());h.assertTrue(f.bank.value()==512 && f.bank.village().getPendingProject()==pending && f.store().load(entry.intent().id()).stage()==RoyalConstructionStore.Stage.CANCELLED,"Unpaid cancellation preserves natural work and all coins");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",batch="upgrade_stale",timeoutTicks=1200)
    public static void changedNativeTargetOrQuoteCannotPurchaseAnotherUpgrade(GameTestHelper h){try(var f=fixture(h,"stale")){
        var offer=offer(f);f.target.setUpgradesAllowed(false);boolean refused=false;try{order(f,offer);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused && f.bank.value()==512 && f.store().load(offer.getUUID("offer"))==null,"Eligibility changes reject before intent/debit");
        f.target.setUpgradesAllowed(true);var old=RoyalEconomyConfig.CONSTRUCTION_FEE.get();try{RoyalEconomyConfig.CONSTRUCTION_FEE.set(80);refused=false;try{order(f,offer);}catch(IllegalArgumentException expected){refused=true;}h.assertTrue(refused && f.bank.value()==512,"Stale upgrade128 quote cannot silently become160");}finally{RoyalEconomyConfig.CONSTRUCTION_FEE.set(old);}h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",batch="upgrade_migration",timeoutTicks=1200)
    public static void formatFourReadsLazilyAndCannotContainNewUpgradeFields(GameTestHelper h)throws Exception{try(var f=fixture(h,"migration")){
        var normal=f.catalog().options().getFirst();var id=UUID.randomUUID();
        var isolated=f.bank.level().getServer().getWorldPath(LevelResource.ROOT).resolve("upgrade-legacy-"+id);var store=new RoyalConstructionStore(isolated);
        var i=new RoyalConstructionStore.Intent(id,f.bank.level().dimension().location().toString(),f.bank.kingdom(),f.bank.village().getId().uuid(),f.bank.village().getId().uuid(),f.player.getUUID(),72,f.bank.village().getVillageTypeId(),normal.planSet(),normal.plan(),normal.variant(),64,0,Set.of());store.reserve(i);
        var file=isolated.resolve("orders.dat");var tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());tag.putInt("format",4);NbtIo.writeCompressed(tag,file);var before=Files.readAllBytes(file);
        h.assertTrue(store.load(id)!=null && Arrays.equals(before,Files.readAllBytes(file)),"Legacy4 retains original bytes on read");
        store.change(id,RoyalConstructionStore.Stage.REQUESTED,RoyalConstructionStore.Stage.PAID,RoyalConstructionStore.Blocked.NONE,null);h.assertTrue(NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()).getInt("format")==5,"A successful locked mutation lazily writes5");
        var item=tag.getList("orders",Tag.TAG_COMPOUND).getCompound(0);item.getCompound("intent").putUUID("upgradeBuilding",f.target.getId().uuid());item.getCompound("intent").putInt("targetLevel",1);NbtIo.writeCompressed(tag,file);before=Files.readAllBytes(file);boolean refused=false;try{store.entries();}catch(IllegalStateException expected){refused=true;}
        h.assertTrue(refused && Arrays.equals(before,Files.readAllBytes(file)),"Upgrade data disguised as legacy4 is preserved/rejected");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",batch="upgrade_audit",timeoutTicks=1200)
    public static void upgradeRecoveryBindsTheSameExistingTargetAndLegacyAuditReadsLazily(GameTestHelper h)throws Exception{try(var f=fixture(h,"audit")){
        var entry=order(f,offer(f));var root=f.bank.level().getServer().getWorldPath(LevelResource.ROOT).resolve("upgrade-audit-"+UUID.randomUUID());var store=new RoyalRecoveryStore(root);
        var audit=new RoyalRecoveryStore.Intent(UUID.randomUUID(),entry.intent(),RoyalRecoveryStore.Mode.ADOPT_BUILDING,f.target.getId().uuid(),f.player.getUUID(),"test","Schema binding only; no recovery applied",0);store.authorize(audit);
        h.assertTrue(store.load(audit.id()).intent().equals(audit),"Format2 audit retains the full upgrade target/level and permits only that existing UUID");
        boolean refused=false;try{new RoyalRecoveryStore.Intent(UUID.randomUUID(),entry.intent(),RoyalRecoveryStore.Mode.ADOPT_BUILDING,UUID.randomUUID(),null,"test","wrong existing building",0);}catch(IllegalArgumentException expected){refused=true;}h.assertTrue(refused,"An upgrade audit cannot adopt a different building UUID");
        var file=root.resolve("decisions.dat");var tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());tag.putInt("format",1);NbtIo.writeCompressed(tag,file);var before=Files.readAllBytes(file);refused=false;try{store.entries();}catch(IllegalStateException expected){refused=true;}h.assertTrue(refused && Arrays.equals(before,Files.readAllBytes(file)),"New upgrade evidence disguised as legacy audit1 is preserved/rejected");
        var choice=f.catalog().options().getFirst();var i=entry.intent();var legacy=new RoyalConstructionStore.Intent(UUID.randomUUID(),i.dimension(),i.kingdom(),i.capital(),i.village(),i.actor(),i.rulerSince(),i.villageType(),choice.planSet(),choice.plan(),choice.variant(),64,0,i.originalBuildings());var legacyStore=new RoyalRecoveryStore(root.resolve("legacy"));var old=new RoyalRecoveryStore.Intent(UUID.randomUUID(),legacy,RoyalRecoveryStore.Mode.ADOPT_PENDING,null,null,"test","Original audit shape",0);legacyStore.authorize(old);file=root.resolve("legacy/decisions.dat");tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());tag.putInt("format",1);NbtIo.writeCompressed(tag,file);before=Files.readAllBytes(file);h.assertTrue(legacyStore.load(old.id()).intent().equals(old) && Arrays.equals(before,Files.readAllBytes(file)),"Legacy audit1 loads without eager rewriting");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",batch="upgrade_interrupted",timeoutTicks=1200)
    public static void unacknowledgedUpgradeIsQuarantinedAcrossJvmInsteadOfAdoptingOldTarget(GameTestHelper h)throws Exception{
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("upgrade-interrupted.dat");
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());var v=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));h.assertTrue(v!=null,"Original interrupted target village reopens");for(var pos:v.getTownhall().getInventory().getChestPositions())level.getChunkAt(pos);v.setActive(true);var player=new net.neoforged.neoforge.common.util.FakePlayer(level,new com.mojang.authlib.GameProfile(tag.getUUID("actor"),"Upgrade ruler"));
            var service=new RoyalConstructionService(level);service.quarantineInterruptedPublications();var store=new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"));var entry=store.load(tag.getUUID("order"));var target=v.getBuilding(new BuildingId(tag.getUUID("building")));service.retryPlayer(player,entry.intent().id());
            h.assertTrue(entry.stage()==RoyalConstructionStore.Stage.RECOVERY_REQUIRED && entry.building()==null && target.getLevel()==0 && target.getStatus()==BuildingInstance.Status.COMPLETE && RoyalTreasurySnapshot.forPlayer(player).spendableValue()==384 && new RoyalPaymentService(level).inspectPlayer(player,entry.intent().id()).state()==RoyalPaymentJournal.State.PAID,"Existing level0 building is not proof of upgrade publication; retain paid128 liability without reissue/refund/adoption");v.setActive(false);h.succeed();return;
        }
        var f=fixture(h,"interrupted");var id=UUID.randomUUID();var constructor=RoyalConstructionService.class.getDeclaredConstructor(net.minecraft.server.level.ServerLevel.class,RoyalVillagePersistence.class);constructor.setAccessible(true);
        var service=constructor.newInstance(level,new RoyalVillagePersistence(){@Override public void pending(net.minecraft.server.level.ServerLevel l,UUID v,Village.PendingProject pending){super.pending(l,v,pending);throw new IllegalStateException("Injected after actual native persistence, before order acknowledgement");}});boolean failed=false;
        try{service.requestQuotedUpgrade(f.player,f.bank.village().getId().uuid(),f.target.getId().uuid(),id,128);}catch(IllegalStateException expected){failed=true;}h.assertTrue(failed && f.store().load(id).stage()==RoyalConstructionStore.Stage.QUEUING && f.bank.value()==384 && f.target.getLevel()==0,"Real128 debit/publication interrupted before acknowledgement; target still original level0");
        var tag=new CompoundTag();tag.putUUID("village",f.bank.village().getId().uuid());tag.putUUID("actor",f.player.getUUID());tag.putUUID("order",id);tag.putUUID("building",f.target.getId().uuid());NbtIo.writeCompressed(tag,marker);f.bank.village().setActive(false);VillageSavedData.get(level).setDirty();h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",batch="upgrade_restart",timeoutTicks=1200)
    public static void paidUpgradeTargetAndQuoteSurviveSeparateJvm(GameTestHelper h)throws Exception{
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-upgrade-restart.dat");
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));h.assertTrue(village!=null,"Original native upgrade village reloads");
            for(var pos:village.getTownhall().getInventory().getChestPositions())level.getChunkAt(pos);village.setActive(true);var player=new net.neoforged.neoforge.common.util.FakePlayer(level,new com.mojang.authlib.GameProfile(tag.getUUID("actor"),"Upgrade ruler"));
            var store=new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"));var entry=store.load(tag.getUUID("order"));
            h.assertTrue(entry.intent().upgradeBuilding().equals(tag.getUUID("building")) && entry.intent().targetLevel()==1 && entry.intent().fee()==128 && entry.intent().pending().equals(village.getPendingProject()),"Target/level/quote and actual original pending persist without repair");
            new RoyalConstructionService(level).retryPlayer(player,entry.intent().id());h.assertTrue(RoyalTreasurySnapshot.forPlayer(player).spendableValue()==384 && new RoyalPaymentService(level).inspectPlayer(player,entry.intent().id()).state()==RoyalPaymentJournal.State.COMMITTED,"Reopen/replay does not debit128 again");village.setActive(false);h.succeed();return;
        }
        var f=fixture(h,"restart");var entry=order(f,offer(f));var tag=new CompoundTag();tag.putUUID("village",f.bank.village().getId().uuid());tag.putUUID("actor",f.player.getUUID());tag.putUUID("order",entry.intent().id());tag.putUUID("building",f.target.getId().uuid());NbtIo.writeCompressed(tag,marker);f.bank.village().setActive(false);VillageSavedData.get(level).setDirty();h.succeed();
    }
}
