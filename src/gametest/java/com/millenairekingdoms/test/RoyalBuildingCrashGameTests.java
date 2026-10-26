package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalVillagePersistence;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.VillageChunkLoader;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Separate real-block fault probe. Never changes original task/status, places the commissioned structure or repairs a save. */
@PrefixGameTestTemplate(false)
public final class RoyalBuildingCrashGameTests {
    @GameTest(templateNamespace="millenairekingdoms_building_crash_prepare",template="empty",timeoutTicks=120_000)
    public static void prepareActualResidentAndPartialStructure(GameTestHelper h) {
        var probe=new RoyalRealConstructionGameTests.Probe(h,false,new RoyalConstructionService(h.getLevel()));
        h.onEachTick(()->run(probe));
    }

    @GameTest(templateNamespace="millenairekingdoms_building_crash_interrupt",template="empty",timeoutTicks=120_000)
    public static void haltAfterOriginalCompleteMetadataAcknowledgement(GameTestHelper h) throws Exception {
        var level=h.getLevel(); var reference=new RoyalRealConstructionGameTests.Probe[1];
        var fault=new RoyalVillagePersistence() {
            @Override public void building(ServerLevel l,UUID village,BuildingInstance expected) {
                super.building(l,village,expected);
                if(expected.getStatus()==BuildingInstance.Status.COMPLETE && expected.getConstructionTask()!=null && expected.getConstructionTask().isComplete()) {
                    var probe=reference[0]; double live=probe.match();
                    h.assertTrue(live>=0.99,"Actual resident must physically finish the genuine structure before abrupt probe");
                    System.out.println("ROYAL_BUILD_CRASH_BOUNDARY_REACHED liveMatch="+live+" task="+expected.getConstructionTask().getNextStepIndex()+"/"+expected.getConstructionTask().totalSteps()+" halt=70");
                    System.out.flush(); Runtime.getRuntime().halt(70);
                }
            }
        };
        var constructor=RoyalConstructionService.class.getDeclaredConstructor(ServerLevel.class,RoyalVillagePersistence.class); constructor.setAccessible(true);
        reference[0]=new RoyalRealConstructionGameTests.Probe(h,true,constructor.newInstance(level,fault));
        h.onEachTick(()->run(reference[0]));
    }

    @GameTest(templateNamespace="millenairekingdoms_building_crash_recover",template="empty",timeoutTicks=1200)
    public static void inspectActualPersistedStructureWithoutRepair(GameTestHelper h) throws Exception {
        var level=h.getLevel(); var tag=NbtIo.readCompressed(level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-real-building.dat"),NbtAccounter.create(1024*1024));
        var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));
        h.assertTrue(village!=null,"Original village must reopen without repair");
        var building=village.getBuilding(new BuildingId(tag.getUUID("building")));
        h.assertTrue(building!=null && building.getStatus()==BuildingInstance.Status.COMPLETE && building.getConstructionTask()!=null && building.getConstructionTask().isComplete(),"Actual original COMPLETE metadata was durably acknowledged before abrupt exit");
        var order=new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction")).load(tag.getUUID("order"));
        h.assertTrue(order.stage()==RoyalConstructionStore.Stage.IN_PROGRESS,"Abrupt cut occurred before royal COMPLETED acknowledgement");
        var center=new ChunkPos(village.getCenter());
        for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++) level.setChunkForced(center.x+x,center.z+z,true);
        village.setNoProjectsLeftUntil(Long.MAX_VALUE); village.setForceActive(true);
        h.onEachTick(()->{
            if(!village.isActive() || !level.hasChunksAt(village.getCenter().offset(-120,0,-120),village.getCenter().offset(120,0,120)) || !(level.getEntity(tag.getUUID("builder")) instanceof MillVillager actual)) return;
            var expected=RoyalRealConstructionGameTests.expectedBlocks(level,building);
            long matches=expected.entrySet().stream().filter(e->{ var block=level.getBlockState(e.getKey()).getBlock();return block==e.getValue()
                    || block instanceof org.millenaire.block.IPaintedBlock && e.getValue() instanceof org.millenaire.block.IPaintedBlock; }).count();
            var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(tag.getString("watched"))); int carried=actual.getInventory().getCount(item);
            System.out.println("ROYAL_BUILD_CRASH_DISK_STATE matches="+matches+" total="+expected.size()+" carried="+carried+" expectedCarriedMax="+(tag.getInt("carried")-tag.getInt("needed")));
            // Observe while the original loaded/active conditions still hold; cleanup must follow the actual assertion operation.
            var result=matches==expected.size() ? new RoyalConstructionService(level).observe(tag.getUUID("order")) : order;
            village.syncRecords(level); village.setForceActive(false); village.setActive(false);
            VillageChunkLoader.releaseVillageChunks(level,village.getCenter(),village.getLoadedChunks()); village.setChunksForceLoaded(false); village.setLoadedChunks(Set.of());
            for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++) level.setChunkForced(center.x+x,center.z+z,false);
            h.assertTrue(expected.size()>100 && matches==expected.size(),"Persisted COMPLETE metadata must not skip missing physical structure blocks: "+matches+"/"+expected.size());
            h.assertTrue(carried<=tag.getInt("carried")-tag.getInt("needed"),"Already-consumed real construction supplies must not reappear on the saved resident");
            h.assertTrue(result.stage()==RoyalConstructionStore.Stage.COMPLETED,"Only physically durable original completion may finish royal tracking");
            System.out.println("ROYAL_BUILD_CRASH_RECOVERED"); h.succeed();
        });
    }

    @GameTest(templateNamespace="millenairekingdoms_building_crash_guard",template="empty",timeoutTicks=1200)
    public static void authenticDamagedSaveCannotBeReportedCompleteOrSilentlyRepaired(GameTestHelper h) throws Exception {
        var level=h.getLevel(); var tag=NbtIo.readCompressed(level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-real-building.dat"),NbtAccounter.create(1024*1024));
        var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));
        h.assertTrue(village!=null,"Authentic pre-fix failed world must load without metadata repair");
        var building=village.getBuilding(new BuildingId(tag.getUUID("building"))); var center=new ChunkPos(village.getCenter());
        h.assertTrue(building!=null && building.getStatus()==BuildingInstance.Status.COMPLETE && building.getConstructionTask().isComplete(),"Fixture retains the actual bad COMPLETE metadata; no synthetic status changes");
        village.setNoProjectsLeftUntil(Long.MAX_VALUE); village.setForceActive(true);
        for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++) level.setChunkForced(center.x+x,center.z+z,true);
        h.onEachTick(()->{
            if(!village.isActive() || !level.hasChunksAt(village.getCenter().offset(-120,0,-120),village.getCenter().offset(120,0,120)) || !(level.getEntity(tag.getUUID("builder")) instanceof MillVillager actual)) return;
            var expected=RoyalRealConstructionGameTests.expectedBlocks(level,building);
            long matches=expected.entrySet().stream().filter(e->level.getBlockState(e.getKey()).getBlock()==e.getValue()).count();
            var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(tag.getString("watched"))); int carried=actual.getInventory().getCount(item);
            h.assertTrue(matches==2 && expected.size()==233 && carried==14,"Fixture is the actual observed before-fix failure, not invented missing blocks/NPC inventory");
            var result=new RoyalConstructionService(level).observe(tag.getUUID("order"));
            long after=expected.entrySet().stream().filter(e->level.getBlockState(e.getKey()).getBlock()==e.getValue()).count();
            h.assertTrue(result.stage()==RoyalConstructionStore.Stage.RECOVERY_REQUIRED && result.blocked()==RoyalConstructionStore.Blocked.BUILDING_MISSING
                    && after==matches && actual.getInventory().getCount(item)==carried && building.getStatus()==BuildingInstance.Status.COMPLETE,
                    "Existing corrupt completion is quarantined; no fake completion, block repair, NPC fabrication or speculative inventory deletion");
            village.setForceActive(false); village.setActive(false); VillageChunkLoader.releaseVillageChunks(level,village.getCenter(),village.getLoadedChunks());
            village.setChunksForceLoaded(false); village.setLoadedChunks(Set.of());
            for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++) level.setChunkForced(center.x+x,center.z+z,false);
            System.out.println("ROYAL_BUILD_MISSING_GUARD_PASS matches="+matches+" total="+expected.size()+" carried="+carried+" order="+result.stage()+"/"+result.blocked()); h.succeed();
        });
    }
    private static void run(RoyalRealConstructionGameTests.Probe probe) {
        try { probe.tick(); } catch(Exception failure) { probe.close(); throw new IllegalStateException("Actual building probe: "+failure.getMessage(),failure); }
    }
}
