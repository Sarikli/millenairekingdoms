package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.RoyalEconomicActivation;
import com.millenairekingdoms.autonomy.RoyalNpcConfig;
import com.millenairekingdoms.construction.RoyalConstructionService;
import java.util.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.VillageChunkLoader;

/** Original generated village/population and declared physical input bank. After unloading, only production economic
 * tickets/native activation/builders advance the actual paid job. The wrapper performs no requests or NPC repairs. */
@PrefixGameTestTemplate(false)
public final class EconomicNativeGameTests {
    @GameTest(templateNamespace="millenairekingdoms_economic_native_tests",template="empty",timeoutTicks=240000)
    public static void originalBuildersResumeColdPaidWorkUsingOnlyProductionEconomicLeases(GameTestHelper h) {
        RoyalNpcConfig.BACKGROUND_ENABLED.set(false);
        var probe=new RoyalRealConstructionGameTests.Probe(h,RoyalShipmentSupport.read(),new RoyalConstructionService(h.getLevel()),true,true);
        probe.kingdomName="Background native construction fixture";
        class Watch {
            boolean detached,measuring;long cold=-1;int ticks,terrainCursor;long visits;String lastPhase="";
            List<ChunkPos> terrain;int lastCarry=-999;
            void tick() {
                if(probe.closed)return;
                if(++ticks>235000){probe.close();h.fail("Economic native builder timeout: "+probe.diagnostic()+" lease="+RoyalEconomicActivation.report(h.getLevel()));return;}
                try {
                    if(!detached && probe.phase==4) {
                        if(terrain==null) {
                            probe.level.getServer().tickRateManager().stopSprinting();var generated=new LinkedHashSet<ChunkPos>();
                            for(var pos:probe.village.computeVillageChunks())for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)generated.add(new ChunkPos(pos.x+x,pos.z+z));
                            int radius=org.millenaire.culture.ModCultures.getVillageType(probe.village.getVillageTypeId()).radius()+32;var c=probe.village.getCenter();
                            for(int x=(c.getX()-radius)>>4;x<=(c.getX()+radius)>>4;x++)for(int z=(c.getZ()-radius)>>4;z<=(c.getZ()+radius)>>4;z++)generated.add(new ChunkPos(x,z));
                            terrain=new ArrayList<>(generated);
                        }
                        for(int n=0;n<4 && terrainCursor<terrain.size();n++){var pos=terrain.get(terrainCursor++);probe.level.getChunk(pos.x,pos.z);}
                        if(terrainCursor<terrain.size())return;
                        probe.force(false);var v=probe.village;v.setForceActive(false);
                        VillageChunkLoader.releaseVillageChunks(probe.level,v.getCenter(),v.getLoadedChunks());v.setChunksForceLoaded(false);v.setLoadedChunks(Set.of());v.setActive(false);
                        probe.level.getServer().saveEverything(true,true,true);probe.level.getServer().tickRateManager().stopSprinting();detached=true;return;
                    }
                    if(detached && !measuring) {
                        if(probe.level.isLoaded(probe.village.getCenter()))return;
                        if(cold<0)cold=probe.level.getGameTime();
                        if(probe.level.getGameTime()-cold<200)return;
                        probe.level.getServer().saveEverything(true,true,true);EconomicBackgroundGameTests.startTerrainObservation(probe.level);RoyalNpcConfig.BACKGROUND_ENABLED.set(true);probe.level.getServer().tickRateManager().requestGameToSprint(230000);measuring=true;
                        System.out.println("ECONOMIC_NATIVE_COLD read="+RoyalShipmentSupport.read()+" tick="+cold+" order="+probe.id+" residents="+probe.village.getVillagerRecords().size());return;
                    }
                    if(measuring) {
                        probe.captureCompletionCargo();
                        h.assertTrue(EconomicBackgroundGameTests.newChunks(probe.level)==0,"Native economic work must reuse already generated terrain");
                        h.assertTrue(!probe.village.isForceActive() && !probe.village.isChunksForceLoaded() && probe.level.getServer().getPlayerList().getPlayers().isEmpty(),"Physical measurement has no player, native force-active flag or fixture persistent village tickets");
                        var report=RoyalEconomicActivation.report(probe.level);
                        if(!report.phase().equals(lastPhase)){lastPhase=report.phase();if(lastPhase.equals("WORKING"))visits++;}
                        if(ticks%3000==0)System.out.println("ECONOMIC_NATIVE_PROGRESS phase="+probe.phase+" actualTicks="+ticks+" visits="+visits+" lease="+report);
                        if(probe.builder!=null && probe.level.getEntity(probe.builder) instanceof MillVillager npc && probe.watched!=null) {
                            int carry=npc.getInventory().getCount(probe.watched);
                            if(probe.building!=null && probe.building.isBeingBuilt() && carry>=probe.needed) {
                                var context=npc.buildGoalContext();
                                h.assertTrue(com.millenairekingdoms.autonomy.RoyalBuilderCargo.reservedConstructionCargo(context)
                                        && !org.millenaire.Millenaire.getGoalRegistry().get(org.millenaire.goal.impl.DeliverResourcesToShopGoal.ID).canStart(context),
                                        "Genuine held building supplies cannot be reassigned to native shop delivery");
                            }
                            if(carry!=lastCarry){
                                var record=probe.village.getVillagerRecord(probe.builder);
                                System.out.println("ECONOMIC_NATIVE_CARGO tick="+probe.level.getGameTime()+" phase="+report.phase()+" cursor="+(probe.building==null?-1:probe.building.getConstructionTask().getNextStepIndex())+" carry="+carry+" recordCarry="+(record==null?-1:record.getInventory().getCount(probe.watched))+" need="+probe.needed+" goal="+npc.getGoalScheduler().getCurrentGoalId()+" assignment="+npc.getConstructionBuildingId());lastCarry=carry;
                            }
                        }
                        // Do not let physical block/inventory assertions themselves load an unavailable work site.
                        if(!probe.village.isActive() || !RoyalEconomicActivation.ownsLease(probe.level) || !report.phase().equals("WORKING")
                                || probe.village.computeVillageChunks().stream().anyMatch(pos->probe.level.getChunkSource().getChunkNow(pos.x,pos.z)==null))return;
                        if(probe.village.getVillagerUuids().stream().noneMatch(id->probe.level.getEntity(id) instanceof MillVillager npc && npc.isAlive()))return;
                    }
                    probe.tick();
                    if(probe.closed){EconomicBackgroundGameTests.stopTerrainObservation(probe.level);RoyalNpcConfig.BACKGROUND_ENABLED.set(false);System.out.println("ECONOMIC_NATIVE_COMPLETE read="+RoyalShipmentSupport.read()+" coldTicks="+(probe.level.getGameTime()-cold)+" visits="+visits+" originalBuilder="+probe.builder);}
                } catch(Exception failure){EconomicBackgroundGameTests.stopTerrainObservation(probe.level);RoyalNpcConfig.BACKGROUND_ENABLED.set(false);probe.close();throw new IllegalStateException("Economic native fixture: "+probe.diagnostic()+" lease="+RoyalEconomicActivation.report(h.getLevel()),failure);}
            }
        }
        var watch=new Watch();GameTestTicks.each(h,watch::tick);
    }
}
