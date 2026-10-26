package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.construction.RoyalVillagePersistence;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentJournal;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.mojang.authlib.GameProfile;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import org.millenaire.building.AnywoodHelper;
import org.millenaire.culture.ModCultures;
import org.millenaire.item.ItemHelper;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageGrowthManager;
import org.millenaire.village.VillageSavedData;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Genuine original location rejection, not synthetic finished-building metadata or real-builder completion proof. */
@PrefixGameTestTemplate(false)
public final class RoyalSiteGameTests {
    private static final String NS="millenairekingdoms_royal_site_tests";
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void originalNoSiteCooldownCanRetryAcceptedWorkWithoutAnotherFee(GameTestHelper h) throws Exception {
        try(var c=reject(h,"site-rejection",false)) {
            released(c,()->{
                var resumed=new RoyalConstructionService(c.f.level()).observe(c.id());
                System.out.println("ROYAL_SITE_RETRY_PROBE stage="+resumed.stage()+" blocked="+resumed.blocked()+" pending="+c.f.village().getPendingProject()+" balance="+c.f.value());
                h.assertTrue(resumed.stage()==Stage.QUEUED && resumed.blocked()==Blocked.NONE && c.pending().equals(c.f.village().getPendingProject())
                        && c.f.value()==64,"After native cooldown, accepted work retries through an empty original slot without a new fee or forced location");
            }); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeCooldownAndUnavailableVillageNeverRequeueEarly(GameTestHelper h) throws Exception {
        try(var c=reject(h,"site-wait",false)) {
            var service=new RoyalConstructionService(c.f.level());
            for(int n=0;n<4;n++) service.observe(c.id());
            h.assertTrue(c.f.village().getPendingProject()==null && c.f.value()==64,"Repeated refresh preserves native placement cooldown without charging");
            released(c,()->{
                c.f.village().setActive(false); service.observe(c.id());
                h.assertTrue(c.f.village().getPendingProject()==null && c.f.value()==64,"Unavailable village is deferred without forcing activation or spending");
                c.f.village().setActive(true); service.observe(c.id());
                h.assertTrue(c.pending().equals(c.f.village().getPendingProject()),"Existing accepted work retries when the village is actually available");
            }); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void equalFieldNaturalPendingKeepsItsOwnSlotAfterCooldown(GameTestHelper h) throws Exception {
        try(var c=reject(h,"site-natural",false)) {
            var natural=c.pending(); c.f.village().setPendingProject(natural);
            released(c,()->{
                var result=new RoyalConstructionService(c.f.level()).observe(c.id());
                h.assertTrue(result.stage()==Stage.QUEUED && result.blocked()==Blocked.NO_SPACE && c.f.village().getPendingProject()==natural
                        && c.f.value()==64,"Even an equal-field natural pending object is preserved, never overwritten or adopted as a new retry publication");
            }); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void failedRetrySaveResumesSameJvmExactPublicationWithoutAnotherDebit(GameTestHelper h) throws Exception {
        try(var c=reject(h,"site-save-failure",false)) {
            released(c,()->{
                var failed=service(c.f.level(),failure(false)); refused(h,()->failed.observe(c.id())); var pending=c.f.village().getPendingProject();
                h.assertTrue(store(c.f.level()).load(c.id()).stage()==Stage.QUEUING && c.f.value()==64 && pending!=null,"Failed domain acknowledgement retains republishing boundary and previously earned fee");
                var result=new RoyalConstructionService(c.f.level()).observe(c.id());
                h.assertTrue(result.stage()==Stage.QUEUED && c.f.village().getPendingProject()==pending && c.f.value()==64
                        && receipt(c).state()==RoyalPaymentJournal.State.COMMITTED,"Same JVM retries exact publication acknowledgement without a new fee");
            }); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void replacedRetryPublicationIsQuarantinedWithoutDeletingNaturalWork(GameTestHelper h) throws Exception {
        try(var c=reject(h,"site-replaced-publication",false)) {
            released(c,()->{
                refused(h,()->service(c.f.level(),failure(false)).observe(c.id())); var natural=c.pending(); c.f.village().setPendingProject(natural);
                var result=new RoyalConstructionService(c.f.level()).observe(c.id());
                h.assertTrue(result.stage()==Stage.RECOVERY_REQUIRED && c.f.village().getPendingProject()==natural && c.f.value()==64
                        && receipt(c).state()==RoyalPaymentJournal.State.COMMITTED,"Field equality cannot prove ownership after interrupted site retry; preserve world and earned patronage");
            }); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void genuineNativePlacementAfterFailedRetryAcknowledgmentKeepsItsBuildingIdentity(GameTestHelper h) throws Exception {
        try(var c=reject(h,"site-native-advance",false)) {
            released(c,()->{
                refused(h,()->service(c.f.level(),failure(false)).observe(c.id()));
                var level=c.f.level(); var center=c.f.village().getCenter();
                int radius=ModCultures.getVillageType(c.f.village().getVillageTypeId()).radius()+16;
                var original=new java.util.HashMap<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState>();
                try {
                    // Real test terrain becomes buildable. The original finder chooses the location and creates its task.
                    for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) {
                        var pos=center.offset(x,-1,z); original.put(pos,level.getBlockState(pos)); level.setBlock(pos,net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(),2);
                    }
                    VillageGrowthManager.evaluateGrowth(level,c.f.village());
                    var i=c.order.intent(); var fresh=c.f.village().getBuildings().stream().filter(b->!i.originalBuildings().contains(b.getId().uuid()) && i.planSet().equals(b.getPlanSetId())).toList();
                    h.assertTrue(fresh.size()==1 && fresh.getFirst().getConstructionTask()!=null && fresh.getFirst().getConstructionTask().totalSteps()>0
                            && c.f.village().getPendingProject()==null,"Actual original growth starts a genuine nonempty task after terrain becomes legal, without assigning a forced site");
                    var result=new RoyalConstructionService(level).observe(c.id());
                    h.assertTrue(result.stage()==Stage.IN_PROGRESS && result.building().equals(fresh.getFirst().getId().uuid()) && c.f.value()==64,
                            "Same JVM resumes its real publication by acknowledging the exact native building, without reissuing or charging");
                } finally {for(var e:original.entrySet()) level.setBlock(e.getKey(),e.getValue(),2);}
            }); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void authorityRetirementDuringRetryCannotBeReversedByRestoration(GameTestHelper h) throws Exception {
        try(var c=reject(h,"site-retired",false)) {
            released(c,()->{
                refused(h,()->service(c.f.level(),failure(false)).observe(c.id())); var pending=c.f.village().getPendingProject();
                var data=KingdomSavedData.get(c.f.level()); data.setActive(c.f.kingdom(),false); data.setActive(c.f.kingdom(),true);
                var result=new RoyalConstructionService(c.f.level()).observe(c.id());
                h.assertTrue(result.stage()==Stage.CANCELLING && result.blocked()==Blocked.AUTHORITY && c.f.village().getPendingProject()==pending
                        && c.f.value()==64,"Permanent mandate retirement wins over same-tick restoration and original retry publication");
            }); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void offlinePlayerSiteRetryUsesOriginalMandateAndNeverNpcSpending(GameTestHelper h) throws Exception {
        try(var c=reject(h,"site-offline-player",true)) {
            h.assertTrue(c.f.level().getServer().getPlayerList().getPlayer(c.player.getUUID())==null,"Ruler is offline in the server player list");
            released(c,()->{
                var result=new RoyalConstructionService(c.f.level()).observe(c.id());
                h.assertTrue(result.stage()==Stage.QUEUED && result.intent().actor().equals(c.player.getUUID()) && c.pending().equals(c.f.village().getPendingProject())
                        && new RoyalPaymentService(c.f.level()).inspectPlayer(c.player,c.id()).state()==RoyalPaymentJournal.State.COMMITTED && c.f.value()==64,
                        "Existing player patronage is reused under the same UUID mandate without NPC agency or new spending");
            }); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void savedNoSpaceReasonRetriesWhenNativeTransientCooldownDoesNotReopen(GameTestHelper h) throws Exception {restart(h,false);}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void interruptedSitePublicationIsQuarantinedBySeparateJvmStartup(GameTestHelper h) throws Exception {restart(h,true);}

    private static void restart(GameTestHelper h,boolean interrupted) throws Exception {
        var level=h.getLevel(); var marker=root(level).resolve("royal-site-restart-"+interrupted+".dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024)); var id=tag.getUUID("order"); var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));
            h.assertTrue(village!=null && village.getPlacementCooldowns().isEmpty(),"Original runtime-only placement cooldown is genuinely absent after native reload");
            for(var pos:village.getTownhall().getInventory().getChestPositions()) level.getChunkAt(pos); village.setActive(true);
            var before=store(level).load(id); var result=new RoyalConstructionService(level).observe(id);
            h.assertTrue(before.stage()==(interrupted ? Stage.RECOVERY_REQUIRED : Stage.QUEUED)
                    && result.stage()==(interrupted ? Stage.RECOVERY_REQUIRED : Stage.QUEUED) && village.getPendingProject()!=null
                    && RoyalTreasurySnapshot.forNpc(level,tag.getUUID("kingdom")).spendableValue()==64
                    && new RoyalPaymentService(level).inspectNpc(tag.getUUID("kingdom"),id).state()==RoyalPaymentJournal.State.COMMITTED,
                    "Separate JVM retries only known acknowledged site failure; uncertain republishing stays quarantined with original pending and one earned fee");
            village.setActive(false); System.out.println("ROYAL_SITE_RESTART_READ_PASS interrupted="+interrupted+" stage="+result.stage()+" balance=64"); h.succeed(); return;
        }
        var c=reject(h,"site-restart-"+interrupted,false);
        if(interrupted) released(c,()->refused(h,()->service(level,failure(true)).observe(c.id())));
        else new RoyalVillagePersistence().absentPending(level,c.f.village().getId().uuid());
        var tag=new CompoundTag();tag.putUUID("order",c.id());tag.putUUID("village",c.f.village().getId().uuid());tag.putUUID("kingdom",c.f.kingdom());NbtIo.writeCompressed(tag,marker);
        c.f.village().setActive(false);VillageSavedData.get(level).setDirty();
        System.out.println("ROYAL_SITE_RESTART_WRITE_PASS interrupted="+interrupted+" stage="+store(level).load(c.id()).stage()+" balance="+c.f.value());h.succeed();
    }

    private record Case(RoyalPaymentGameTests.Fixture f,RoyalConstructionStore.Entry order,long until,ServerPlayer player) implements AutoCloseable {
        UUID id(){return order.intent().id();}
        Village.PendingProject pending(){var i=order.intent();return new Village.PendingProject(i.planSet(),i.variant(),0,false,null);}
        @Override public void close(){f.close();}
    }
    private static Case reject(GameTestHelper h,String name,boolean playerOrder) {
        var f=RoyalPaymentGameTests.persistentFixture(h,name);f.village().setActive(true);f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));
        ServerPlayer player=null;
        if(playerOrder){player=new FakePlayer(f.level(),new GameProfile(UUID.randomUUID(),"Site ruler"));f.village().addReputation(player.getUUID(),32768-f.village().getCombinedReputation(f.level(),player.getUUID()));KingdomSavedData.get(f.level()).claimRule(f.kingdom(),player.getUUID(),"Site ruler",42);}
        var choices=player==null ? RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options() : RoyalConstructionCatalog.forPlayer(player,f.village().getId().uuid()).options();var choice=choices.getFirst();
        var service=new RoyalConstructionService(f.level());var id=UUID.randomUUID();var order=player==null ? service.requestNpc(f.kingdom(),f.village().getId().uuid(),choice.planSet(),id) : service.requestPlayer(player,f.village().getId().uuid(),choice.planSet(),id);
        var def=ModCultures.getBuildingPlanSet(order.intent().planSet()).getLevel(order.intent().variant(),0);
        for(var r:def.requiredResources().entrySet()){var item=AnywoodHelper.isAnywood(r.getKey()) ? net.minecraft.world.item.Items.OAK_LOG : ItemHelper.resolve(r.getKey());h.assertTrue(f.village().getTownhall().getInventory().add(f.level(),item,r.getValue())==r.getValue(),"Real needed material enters physical storage");}
        // Isolate this plan as the available candidate using ordinary native cooldowns. No natural queue is deleted.
        for(var other:choices) if(!other.planSet().equals(choice.planSet()))f.village().addPlacementCooldown(other.planSet(),Long.MAX_VALUE);
        VillageGrowthManager.evaluateGrowth(f.level(),f.village());var until=f.village().getPlacementCooldowns().getOrDefault(choice.planSet(),0L);
        h.assertTrue(until>f.level().getGameTime() && f.village().getPendingProject()==null && f.village().getBuildings().size()==1,"Actual original placement rejects invalid terrain, clears pending and creates no commissioned building");
        h.assertTrue(service.observe(id).blocked()==Blocked.NO_SPACE && f.value()==64,"Native failure is NO_SPACE after the one paid fee");return new Case(f,order,until,player);
    }
    private static void released(Case c,Action action) throws Exception {var data=(ServerLevelData)c.f.level().getLevelData();long now=data.getGameTime();try{data.setGameTime(c.until+1);action.run();}finally{data.setGameTime(now);}}
    private static RoyalVillagePersistence failure(boolean saved) {return new RoyalVillagePersistence(){@Override public void pending(ServerLevel level,UUID village,Village.PendingProject expected){if(saved)super.pending(level,village,expected);throw new IllegalStateException("Injected site retry domain acknowledgement loss");}};}
    private static RoyalConstructionService service(ServerLevel level,RoyalVillagePersistence p){try{var c=RoyalConstructionService.class.getDeclaredConstructor(ServerLevel.class,RoyalVillagePersistence.class);c.setAccessible(true);return c.newInstance(level,p);}catch(Exception e){throw new IllegalStateException(e);}}
    private static RoyalPaymentService.Receipt receipt(Case c){var i=c.order.intent();return new RoyalPaymentService(c.f.level()).inspectConstruction(i.id(),i.kingdom(),i.capital(),i.actor(),i.fee());}
    private static Path root(ServerLevel l){return l.getServer().getWorldPath(LevelResource.ROOT);}
    private static RoyalConstructionStore store(ServerLevel l){return new RoyalConstructionStore(root(l).resolve("data/millenairekingdoms/royal-construction"));}
    private static void refused(GameTestHelper h,Runnable action){boolean rejected=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"Unacknowledged site retry must stay unresolved");}
    @FunctionalInterface private interface Action {void run() throws Exception;}
}
