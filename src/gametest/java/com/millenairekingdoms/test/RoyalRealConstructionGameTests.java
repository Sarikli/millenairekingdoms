package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalConstructionStore.Entry;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.construction.RoyalVillagePersistence;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.block.mock.MockBlock;
import org.millenaire.building.AnywoodHelper;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.culture.ModCultures;
import org.millenaire.entity.MillVillager;
import org.millenaire.item.ItemHelper;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageChunkLoader;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;
import org.millenaire.world.BuildingPlacer;
import org.millenaire.world.VillageSpawner;

/** Original VillageSpawner creates baseline inhabitants/buildings. The commissioned building is never placed/rushed
 * by this test: original growth chooses its location and actual resident NPC goals fetch materials and place all steps.
 * Write stops during real construction; read must resume the saved task/entities and finish it in another JVM.
 * Chunk tickets and sprinting exist only in this simulation fixture, never in production background services. */
@PrefixGameTestTemplate(false)
public final class RoyalRealConstructionGameTests {
    private static final String NS="millenairekingdoms_royal_construction_tests";

    @GameTest(templateNamespace=NS,template="empty",batch="royal_real_builders",timeoutTicks=120_000)
    public static void actualNpcMaterialsPlacementAndTaskResumeAcrossSeparateJvm(GameTestHelper h) {
        var probe=new Probe(h); h.onEachTick(()->{
            try { probe.tick(); }
            catch(Exception failure) { probe.close(); throw new IllegalStateException("Real construction phase="+probe.phase+" ticks="+probe.ticks+": "+failure.getMessage(),failure); }
        });
    }

    static final class Probe {
        final GameTestHelper h;
        final ServerLevel level;
        final boolean read;
        final boolean automatic;
        final RoyalConstructionService service;
        final Path marker;
        final boolean daylight;
        BlockPos anchor=new BlockPos(220_000,-60,260_000);
        Village village;
        UUID kingdom,id,builder;
        BuildingInstance building;
        Map<BlockPos,Block> expected;
        Map<ResourceLocation,Integer> materials;
        Item watched;
        int stock,needed,carriedAtRestart,phase,ticks,started;
        long remaining;
        double initialMatch;
        boolean fetched,builderLoaded,closed;
        Probe(GameTestHelper h) {
            this(h,"read".equals(System.getProperty("kingdoms.tests.persistence")),new RoyalConstructionService(h.getLevel()));
        }
        Probe(GameTestHelper h,boolean read,RoyalConstructionService service) {
            this(h,read,service,false);
        }
        Probe(GameTestHelper h,boolean read,RoyalConstructionService service,boolean automatic) {
            this.h=h; level=h.getLevel(); this.read=read; this.service=service; this.automatic=automatic;
            marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-real-building.dat");
            daylight=level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer()); level.setDayTime(6000);
        }

        void tick() throws Exception {
            if(closed) return;
            ticks++;
            if(ticks>=119_000) { String reason=diagnostic(); close(); h.fail("Genuine NPC construction timeout: "+reason); return; }
            if(phase==0) {
                if(read) {
                    var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024)); kingdom=tag.getUUID("kingdom"); id=tag.getUUID("order"); builder=tag.getUUID("builder");
                    remaining=tag.getLong("remaining"); village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));
                    watched=BuiltInRegistries.ITEM.get(ResourceLocation.parse(tag.getString("watched"))); needed=tag.getInt("needed"); carriedAtRestart=tag.getInt("carried");
                    h.assertTrue(village!=null,"The original village must reopen without metadata repair"); anchor=village.getCenter();
                    building=village.getBuilding(new BuildingId(tag.getUUID("building")));
                    h.assertTrue(building!=null && building.isBeingBuilt() && building.getConstructionTask()!=null
                            && building.getConstructionTask().getNextStepIndex()>=tag.getInt("cursor") && building.getConstructionTask().totalSteps()==tag.getInt("total"),"Real nonempty in-progress task reopens at its saved cursor, never a fabricated completed building");
                    var order=store().load(id); h.assertTrue(order.stage()==Stage.IN_PROGRESS && order.building().equals(building.getId().uuid()),"Royal order keeps the exact acknowledged new engine building UUID");
                    initialMatch=tag.getDouble("initialMatch");
                }
                force(true); phase=1; return;
            }
            if(phase==1) {
                if(!level.hasChunksAt(anchor.offset(-120,0,-120),anchor.offset(120,0,120))) return;
                if(!read) {
                    var manager=VillageSavedData.get(level).getVillageManager(); var before=manager.getAllVillages().stream().map(Village::getId).collect(java.util.stream.Collectors.toSet());
                    var type=ModCultures.getVillageType(ResourceLocation.parse("millenaire:norman/hameau_artisans"));
                    var surface=anchor.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,anchor.getX(),anchor.getZ()));
                    var failure=VillageSpawner.spawnVillage(level,surface,type,0);
                    h.assertTrue(failure==null,"Original baseline village spawn: "+(failure==null ? "ok" : failure.getString()));
                    village=manager.getAllVillages().stream().filter(v->!before.contains(v.getId())).findFirst().orElseThrow();
                    village.setNoProjectsLeftUntil(Long.MAX_VALUE); kingdom=KingdomSavedData.get(level).create("Real royal builders",village.getId().uuid()).id();
                }
                village.setForceActive(true); village.setActive(true); level.getServer().tickRateManager().requestGameToSprint(120_000);
                phase=2; started=ticks; return;
            }
            if(phase==2) {
                if(!village.isActive() || village.getVillagerUuids().stream().noneMatch(u->level.getEntity(u) instanceof MillVillager)) return;
                if(read) {
                    if(!(level.getEntity(builder) instanceof MillVillager saved)) return;
                    h.assertTrue(village.getId().equals(saved.getVillageId()) && saved.getInventory().getCount(watched)>=carriedAtRestart,
                            "Original resident and actual carried construction supplies reopen without NPC or inventory repair");
                    builderLoaded=true;
                    h.assertTrue(new RoyalPaymentService(level).inspectNpc(kingdom,id).state()==State.COMMITTED
                            && RoyalTreasurySnapshot.forNpc(level,kingdom).spendableValue()==remaining,"Reload retains real patronage without paying it again");
                    expected=expected(building); phase=4; return;
                }
                var inventory=village.getTownhall().getInventory(); h.assertTrue(inventory!=null && inventory.add(level,ModItems.DENIER_ARGENT.get(),2)==2,"Known test funding enters actual existing townhall chests");
                long before=RoyalTreasurySnapshot.forNpc(level,kingdom).spendableValue();
                var choice=RoyalConstructionCatalog.forNpc(level,kingdom,village.getId().uuid()).options().stream()
                        .filter(o->o.requiredMaterials().values().stream().mapToInt(Integer::intValue).sum()>0)
                        .min(Comparator.comparingInt(o->o.requiredMaterials().values().stream().mapToInt(Integer::intValue).sum())).orElseThrow(()->new IllegalStateException("Original village has no material-requiring normal-growth candidate"));
                id=UUID.randomUUID(); var order=service.requestNpc(kingdom,village.getId().uuid(),choice.planSet(),id); remaining=before-order.intent().fee();
                h.assertTrue(order.stage()==Stage.QUEUED && RoyalTreasurySnapshot.forNpc(level,kingdom).spendableValue()==remaining && order.building()==null,"Paid request does not inject a building or choose a location");
                materials=ModCultures.getBuildingPlanSet(order.intent().planSet()).getLevel(order.intent().variant(),0).requiredResources();
                for(var resource:materials.entrySet()) {
                    if(AnywoodHelper.isAnywood(resource.getKey())) inventory.removeByTag(level,AnywoodHelper.LOGS_TAG,Integer.MAX_VALUE,Set.of());
                    else { var item=ItemHelper.resolve(resource.getKey()); h.assertTrue(item!=null,"Real resource item resolves: "+resource.getKey()); inventory.remove(level,item,inventory.getCount(level,item)); }
                }
                if(!automatic) h.assertTrue(observed().blocked()==Blocked.MATERIALS,"With actual required stock absent, the engine-backed monitor reports MATERIALS");
                started=ticks; phase=3; return;
            }
            if(phase==3) {
                if(ticks-started<(automatic ? 220 : 60)) return;
                h.assertTrue(observed().stage()==Stage.QUEUED && village.getPendingProject()!=null,"Material starvation preserves ordinary pending work without fake construction");
                if(automatic) h.assertTrue(observed().blocked()==Blocked.MATERIALS,"Actual scheduled tracking must observe missing materials without a direct service/step call");
                var inventory=village.getTownhall().getInventory();
                for(var resource:materials.entrySet()) {
                    var item=AnywoodHelper.isAnywood(resource.getKey()) ? Items.OAK_LOG : ItemHelper.resolve(resource.getKey());
                    h.assertTrue(inventory.add(level,item,resource.getValue())==resource.getValue(),"All known fixture supplies must enter real physical storage: "+resource.getKey());
                    if(watched==null && resource.getValue()>0) { watched=item; needed=resource.getValue(); }
                }
                inventory.invalidateCache(); stock=inventory.getCount(level,watched);
                if(!automatic) h.assertTrue(observed().blocked()==Blocked.NONE,"Real material deposit unblocks the existing paid order without another fee");
                System.out.println("ROYAL_REAL_BUILD_STOCK order="+id+" materials="+materials+" residents="+village.getVillagerUuids().size());
                phase=4; return;
            }
            if(phase==4) {
                if(ticks%20!=0) return;
                Entry order=observed();
                if(order.stage()==Stage.IN_PROGRESS || order.stage()==Stage.COMPLETED) {
                    if(building==null) {
                        building=village.getBuilding(new BuildingId(order.building())); expected=expected(building); initialMatch=match();
                        h.assertTrue(initialMatch<0.2,"Original location starts without the commissioned structure; initial template match="+initialMatch);
                    }
                    var task=building.getConstructionTask();
                    if(read && level.getEntity(builder) instanceof MillVillager saved && village.getId().equals(saved.getVillageId())) builderLoaded=true;
                    if(task.getReservedBuilder()!=null && level.getEntity(task.getReservedBuilder()) instanceof MillVillager actual && village.getId().equals(actual.getVillageId())) {
                        if(!read) builder=actual.getUUID();
                        if(actual.getUUID().equals(builder)) builderLoaded=true;
                        if(!read && actual.getInventory().getCount(watched)>=needed) {
                            village.getTownhall().getInventory().invalidateCache();
                            fetched|=village.getTownhall().getInventory().getCount(level,watched)<stock;
                        }
                    }
                    // Terrain clearing can occupy >90% of original task steps. Use a genuinely partial structure,
                    // rather than an arbitrary cursor percentage, to choose the independent-JVM boundary.
                    if(!read && builderLoaded && fetched && task.progress()>=0.1F && !task.isComplete() && match()>initialMatch && match()<0.5) {
                        h.assertTrue(task.totalSteps()>100 && task.getNextStepIndex()>0,"Actual original builder advanced a substantial nonempty task");
                        village.syncRecords(level); new RoyalVillagePersistence().building(level,village.getId().uuid(),building);
                        var tag=new CompoundTag(); tag.putUUID("kingdom",kingdom); tag.putUUID("village",village.getId().uuid()); tag.putUUID("order",id); tag.putUUID("building",building.getId().uuid());
                        tag.putUUID("builder",builder); tag.putInt("cursor",task.getNextStepIndex()); tag.putInt("total",task.totalSteps()); tag.putLong("remaining",remaining); tag.putDouble("initialMatch",initialMatch);
                        tag.putString("watched",BuiltInRegistries.ITEM.getKey(watched).toString()); tag.putInt("needed",needed);
                        tag.putInt("carried",((MillVillager)level.getEntity(builder)).getInventory().getCount(watched));
                        NbtIo.writeCompressed(tag,marker);
                        System.out.println("ROYAL_REAL_BUILD_RESTART_WRITE_PASS "+diagnostic()+" match="+match()+" materialsFetched="+fetched);
                        close(); h.succeed(); return;
                    }
                    if(read && order.stage()==Stage.COMPLETED) {
                        double placed=match();
                        h.assertTrue(builderLoaded && task.isComplete() && task.totalSteps()>100 && building.getStatus()==BuildingInstance.Status.COMPLETE
                                && placed>=0.90 && village.getOwnerUUID()==null && RoyalTreasurySnapshot.forNpc(level,kingdom).spendableValue()==remaining,
                                "Actual saved resident must resume and complete original construction; final match="+placed+" builderLoaded="+builderLoaded);
                        h.assertTrue(level.getEntity(builder) instanceof MillVillager actual && actual.getInventory().getCount(watched)<=carriedAtRestart-needed,
                                "Original builder consumes the real carried construction materials after completion");
                        System.out.println("ROYAL_REAL_BUILD_RESTART_READ_PASS "+diagnostic()+" finalMatch="+placed);
                        close(); h.succeed(); return;
                    }
                    if(!read && order.stage()==Stage.COMPLETED) { String reason=diagnostic(); close(); h.fail("Test missed its required in-progress checkpoint: "+reason); return; }
                } else if(order.stage()==Stage.RECOVERY_REQUIRED || order.stage()==Stage.CANCELLING || order.stage()==Stage.FAILED) {
                    String reason=diagnostic(); close(); h.fail("Original construction did not progress: "+reason); return;
                }
                if(ticks%1000==0) System.out.println("ROYAL_REAL_BUILD_PROGRESS "+diagnostic());
            }
        }

        Map<BlockPos,Block> expected(BuildingInstance b) {
            var out=expectedBlocks(level,b);
            h.assertTrue(out.size()>100,"The actual original template must contain substantial real structure"); return out;
        }
        double match() { return (double)expected.entrySet().stream().filter(e->{
            var actual=level.getBlockState(e.getKey()).getBlock(); return actual==e.getValue()
                    || actual instanceof org.millenaire.block.IPaintedBlock && e.getValue() instanceof org.millenaire.block.IPaintedBlock;
        }).count()/expected.size(); }
        String diagnostic() {
            var order=id==null ? null : store().load(id); var task=building==null ? null : building.getConstructionTask();
            return "phase="+phase+" ticks="+ticks+" order="+(order==null ? "absent" : order.stage()+"/"+order.blocked())
                    +" task="+(task==null ? "absent" : task.getNextStepIndex()+"/"+task.totalSteps()+" reserved="+task.getReservedBuilder())
                    +" builderSeen="+builderLoaded+" fetched="+fetched+" watched="+watched+" stock="+stock+" needed="+needed
                    +" carried="+(builder==null || !(level.getEntity(builder) instanceof MillVillager actual) || watched==null ? -1 : actual.getInventory().getCount(watched))
                    +" match="+(expected==null ? -1 : match())+" initialMatch="+initialMatch
                    +" residents="+(village==null ? 0 : village.getVillagerUuids().stream().filter(u->level.getEntity(u)!=null).count());
        }
        RoyalConstructionStore store() { return new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction")); }
        // Automatic mode never calls observe/step after the initial paid request; only real event handlers may progress it.
        Entry observed() { return automatic ? store().load(id) : service.observe(id); }
        void force(boolean add) { var c=new ChunkPos(anchor); for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++) level.setChunkForced(c.x+x,c.z+z,add); }
        void close() {
            if(closed) return; closed=true;
            if(village!=null) {
                village.syncRecords(level); village.setForceActive(false); village.setActive(false);
                VillageChunkLoader.releaseVillageChunks(level,village.getCenter(),village.getLoadedChunks()); village.setChunksForceLoaded(false); village.setLoadedChunks(Set.of());
            }
            force(false); level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,level.getServer());
            level.getServer().tickRateManager().stopSprinting(); VillageSavedData.get(level).setDirty();
        }
    }
    static Map<BlockPos,Block> expectedBlocks(ServerLevel level,BuildingInstance building) {
        var out=new HashMap<BlockPos,Block>(); var plan=ModCultures.getBuildingPlan(building.getPlanId());
        for(var step:BuildingPlacer.compilePlacementSteps(level,plan,building.getOrigin(),building.getRotation(),building)) {
            var state=step.blockState();
            if(!state.isAir() && !(state.getBlock() instanceof MockBlock) && !state.is(net.minecraft.tags.BlockTags.DIRT) && state.getBlock()!=Blocks.STRUCTURE_VOID)
                out.put(building.getOrigin().offset(step.relativePos()),state.getBlock());
        }
        return out;
    }
}
