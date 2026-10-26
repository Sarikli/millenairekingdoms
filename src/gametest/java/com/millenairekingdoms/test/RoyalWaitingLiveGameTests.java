package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionConfig;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.construction.RoyalVillagePersistence;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.gui.RoyalConstructionView;
import com.millenairekingdoms.gui.KingdomActions;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.network.KingdomActionPayload;
import com.mojang.authlib.GameProfile;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.AnywoodHelper;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.culture.ModCultures;
import org.millenaire.entity.MillVillager;
import org.millenaire.item.ItemHelper;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageChunkLoader;
import org.millenaire.village.VillageSavedData;
import org.millenaire.world.VillageSpawner;

/** A real pre-existing native upgrade crosses the JVM boundary with unpaid royal work waiting behind it.
 * Only original NPC goals finish both tasks; after setup no observe/retry/step drives the royal scheduler. */
@PrefixGameTestTemplate(false)
public final class RoyalWaitingLiveGameTests {
    @GameTest(templateNamespace="millenairekingdoms_royal_live_tests",template="empty",batch="royal_player_waiting",timeoutTicks=120_000)
    public static void originalNativeWorkFinishesBeforeAutomaticPlayerCommissionAcrossSeparateJvm(GameTestHelper h) {
        var probe=new Probe(h);GameTestTicks.each(h,()->{try{probe.tick();}catch(Exception failure){probe.close();throw new IllegalStateException("Native-to-royal queue: "+probe.describe(),failure);}});
    }
    private static final class Probe {
        final GameTestHelper h;final ServerLevel level;final boolean read,daylight;
        final Path marker;final Set<ChunkPos> forced=new HashSet<>();
        Village village,capital;UUID kingdom,id,builder;ServerPlayer ruler,oldOnline;
        Map<UUID,ServerPlayer> online;BuildingInstance first,royal;
        BlockPos anchor=new BlockPos(360_000,-60,380_000);
        Map<BlockPos,Block> nativeChanges,royalBlocks;int ticks,phase,firstFinishedTick;boolean closed,firstSeen,firstFinished,royalSeen;
        Probe(GameTestHelper h){this.h=h;level=h.getLevel();read="read".equals(System.getProperty("kingdoms.tests.persistence"));marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-player-waiting-live.dat");
            daylight=level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());level.setDayTime(6000);
            h.assertTrue(RoyalConstructionConfig.TRACKING.get() && RoyalConstructionConfig.INTERVAL.get()==100,"Actual production construction events must be enabled");}
        void tick() throws Exception {
            if(closed)return;ticks++;if(ticks>119000){var why=describe();close();h.fail("Native queue physical timeout: "+why);return;}
            if(phase==0){
                if(read){var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024));kingdom=tag.getUUID("kingdom");id=tag.getUUID("order");builder=tag.getUUID("builder");
                    var manager=VillageSavedData.get(level).getVillageManager();village=manager.getVillage(new VillageId(tag.getUUID("village")));capital=manager.getVillage(new VillageId(tag.getUUID("capital")));
                    h.assertTrue(village!=null && capital!=null,"Original native village and real treasury reopen without reconstruction");anchor=village.getCenter();first=village.getBuilding(new BuildingId(tag.getUUID("nativeBuilding")));
                    h.assertTrue(first!=null && first.isBeingBuilt() && first.getConstructionTask()!=null && first.getConstructionTask().getNextStepIndex()>=tag.getInt("cursor") && first.getConstructionTask().totalSteps()==tag.getInt("total"),"Original incomplete native task reopens at its saved cursor");
                    h.assertTrue(store().load(id).stage()==Stage.WAITING,"Royal mandate is still unpaid WAITING after JVM reopen");ruler=new FakePlayer(level,new GameProfile(tag.getUUID("ruler"),"Queued ruler"));firstSeen=true;
                }else{
                    var bank=RoyalPaymentGameTests.persistentFixture(h,"native-queue-live-capital");capital=bank.village();capital.setActive(true);bank.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));kingdom=bank.kingdom();ruler=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"Queued ruler"));
                    capital.addReputation(ruler.getUUID(),32768-capital.getCombinedReputation(level,ruler.getUUID()));KingdomSavedData.get(level).claimRule(kingdom,ruler.getUUID(),"Queued ruler",84);
                }
                forceArea(anchor,8);for(var pos:capital.getTownhall().getInventory().getChestPositions())forceArea(pos,1);registerOnline();phase=1;return;
            }
            if(phase==1){
                if(!level.hasChunksAt(anchor.offset(-120,0,-120),anchor.offset(120,0,120)))return;
                if(!read){var manager=VillageSavedData.get(level).getVillageManager();var before=manager.getAllVillages().stream().map(Village::getId).collect(java.util.stream.Collectors.toSet());
                    var type=ModCultures.getVillageType(ResourceLocation.parse("millenaire:norman/hameau_artisans"));var surface=anchor.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,anchor.getX(),anchor.getZ()));
                    var failure=VillageSpawner.spawnVillage(level,surface,type,0);h.assertTrue(failure==null,"Original native village spawn: "+failure);village=manager.getAllVillages().stream().filter(v->!before.contains(v.getId())).findFirst().orElseThrow();
                    KingdomSavedData.get(level).attach(kingdom,village.getId().uuid());village.setNoProjectsLeftUntil(Long.MAX_VALUE);
                }
                village.setForceActive(true);village.setActive(true);capital.setActive(true);level.getServer().tickRateManager().requestGameToSprint(120000);phase=2;return;
            }
            village.setForceActive(true);village.setActive(true);capital.setActive(true);
            if(phase==2){
                if(village.getVillagerUuids().stream().noneMatch(u->level.getEntity(u) instanceof MillVillager))return;
                if(read){if(!(level.getEntity(builder) instanceof MillVillager original))return;h.assertTrue(village.getId().equals(original.getVillageId()) && treasury()==128 && new RoyalPaymentService(level).inspectPlayer(ruler,id)==null,"Saved original resident and128 real coins reopen without an invented fee or actor repair");phase=3;return;}
                var choices=RoyalConstructionCatalog.forPlayer(ruler,village.getId().uuid()).options();var choice=choices.stream().filter(c->!c.requiredMaterials().isEmpty()).min(Comparator.comparingInt(c->c.requiredMaterials().values().stream().mapToInt(Integer::intValue).sum())).orElseThrow();
                first=village.getTownhall();var set=ModCultures.getBuildingPlanSet(first.getPlanSetId());var next=set.getLevel(first.getVariant(),first.getLevel()+1);h.assertTrue(next!=null,"The genuine native town hall has an ordinary next upgrade");
                var bank=new HashMap<Item,Integer>();supply(bank,next.requiredResources());supply(bank,choice.requiredMaterials());var inventory=first.getInventory();
                for(var entry:bank.entrySet()){int missing=Math.max(0,entry.getValue()-inventory.getCount(level,entry.getKey()));h.assertTrue(inventory.add(level,entry.getKey(),missing)==missing,"Known fixture materials enter actual native storage: "+entry.getKey());}
                var pending=new Village.PendingProject(set.id(),first.getVariant(),next.level(),true,first.getId());village.setPendingProject(pending);village.setNoProjectsLeftUntil(0);
                RoyalConstructionView.select(ruler,village.getId().uuid());var frame=RoyalConstructionView.build(ruler,kingdom);CompoundTag offer=null;
                for(var raw:frame.getList("plans",Tag.TAG_COMPOUND))if(!((CompoundTag)raw).getBoolean("upgrade") && ((CompoundTag)raw).getString("plan").equals(choice.planSet().toString()))offer=(CompoundTag)raw;
                h.assertTrue(offer!=null && offer.getBoolean("canOrder") && frame.getBoolean("naturalPending"),"Original native upgrade can have a reviewed royal plan waiting behind it");id=offer.getUUID("offer");
                KingdomActions.handle(ruler,new KingdomActionPayload("CONSTRUCTION_ORDER",Optional.of(village.getId().uuid()),Optional.of(id),0,choice.planSet().toString()));
                h.assertTrue(store().load(id).stage()==Stage.WAITING && treasury()==128 && village.getPendingProject()==pending,"Actual GUI action preserves the exact original pending project and all coins");
                System.out.println("ROYAL_NATIVE_QUEUE_SETUP native="+next.planId()+" royal="+choice.planSet()+" residents="+village.getVillagerUuids().size()+" treasury="+treasury());phase=3;return;
            }
            var entry=store().load(id);var task=first.getConstructionTask();
            if(first.isBeingBuilt() && task!=null){
                firstSeen=true;h.assertTrue(entry.stage()==Stage.WAITING && treasury()==128 && new RoyalPaymentService(level).inspectPlayer(ruler,id)==null,"No payment or royal publication preempts ongoing original native construction");
                if(!read && nativeChanges==null){nativeChanges=new HashMap<>();for(var e:RoyalRealConstructionGameTests.expectedBlocks(level,first).entrySet())if(level.getBlockState(e.getKey()).getBlock()!=e.getValue())nativeChanges.put(e.getKey(),e.getValue());h.assertTrue(nativeChanges.size()>10,"The native upgrade has genuinely missing physical work");}
                if(!read && task.getReservedBuilder()!=null && level.getEntity(task.getReservedBuilder()) instanceof MillVillager actual && village.getId().equals(actual.getVillageId()) && task.getNextStepIndex()>100 && !task.isComplete()){
                    double placed=match(nativeChanges);if(placed>0 && placed<0.8){builder=actual.getUUID();village.syncRecords(level);new RoyalVillagePersistence().building(level,village.getId().uuid(),first);
                        var tag=new CompoundTag();tag.putUUID("kingdom",kingdom);tag.putUUID("order",id);tag.putUUID("village",village.getId().uuid());tag.putUUID("capital",capital.getId().uuid());tag.putUUID("ruler",ruler.getUUID());tag.putUUID("nativeBuilding",first.getId().uuid());tag.putUUID("builder",builder);tag.putInt("cursor",task.getNextStepIndex());tag.putInt("total",task.totalSteps());NbtIo.writeCompressed(tag,marker);
                        System.out.println("ROYAL_NATIVE_QUEUE_WRITE_PASS native="+task.getNextStepIndex()+"/"+task.totalSteps()+" changedBlocks="+placed+" royal=WAITING treasury128 noFee builder="+builder);close();h.succeed();return;}
                }
            }
            if(read && firstSeen && first.getStatus()==BuildingInstance.Status.COMPLETE && task!=null && task.isComplete()){
                if(!firstFinished){h.assertTrue(match(RoyalRealConstructionGameTests.expectedBlocks(level,first))>=0.90,"The first native job really finishes its structure before royal work");firstFinished=true;firstFinishedTick=ticks;System.out.println("ROYAL_NATIVE_QUEUE_FIRST_FINISHED ticks="+ticks+" nativeSteps="+task.totalSteps()+" royal="+entry.stage());}
                if(ticks-firstFinishedTick>200 && entry.stage()==Stage.WAITING && entry.blocked()==RoyalConstructionStore.Blocked.NATURAL_PROJECT)
                    throw new IllegalStateException("Native growth claimed another project before the waiting royal mandate: "+entry+" pending="+village.getPendingProject());
                if(entry.stage()==Stage.WAITING && entry.blocked()==RoyalConstructionStore.Blocked.MISSING_PLAN)throw new IllegalStateException("Selected fixture plan became ineligible after genuine native upgrade: "+entry);
                if(entry.stage()==Stage.QUEUED || entry.stage()==Stage.IN_PROGRESS || entry.stage()==Stage.COMPLETED){h.assertTrue(treasury()==64 && new RoyalPaymentService(level).inspectPlayer(ruler,id).state()==State.COMMITTED,"Actual scheduler accepts the same waiting mandate for one64 fee");
                    if(entry.building()!=null){if(royal==null){royal=village.getBuilding(new BuildingId(entry.building()));h.assertTrue(royal!=null && !royal.getId().equals(first.getId()),"The royal building is a distinct original engine identity");royalBlocks=RoyalRealConstructionGameTests.expectedBlocks(level,royal);royalSeen=true;}
                        if(entry.stage()==Stage.COMPLETED){h.assertTrue(royalSeen && royal.getStatus()==BuildingInstance.Status.COMPLETE && royal.getConstructionTask()!=null && royal.getConstructionTask().isComplete() && match(royalBlocks)>=0.90 && village.getOwnerUUID()==null,"Original NPCs complete the royal structure after the original job, without instant construction or ownership changes");
                            System.out.println("ROYAL_NATIVE_QUEUE_READ_PASS nativeCompleted=true royalCompleted=true match="+match(royalBlocks)+" treasury64 oneFee order="+id);close();h.succeed();return;}
                    }
                }
            }
            if(entry.stage()==Stage.RECOVERY_REQUIRED || entry.stage()==Stage.CANCELLING || entry.stage()==Stage.FAILED)throw new IllegalStateException("Mandate stopped: "+entry);
            if(!read && firstSeen && first.getStatus()==BuildingInstance.Status.COMPLETE)throw new IllegalStateException("Missed required partial native-work checkpoint");
            if(ticks%1000==0)System.out.println("ROYAL_NATIVE_QUEUE_PROGRESS "+describe());
        }
        void supply(Map<Item,Integer> bank,Map<ResourceLocation,Integer> resources){for(var e:resources.entrySet()){Item item=AnywoodHelper.isAnywood(e.getKey())?Items.OAK_LOG:ItemHelper.resolve(e.getKey());h.assertTrue(item!=null,"Original material resolves: "+e.getKey());bank.merge(item,Math.multiplyExact(2,e.getValue()),Math::addExact);}}
        double match(Map<BlockPos,Block> expected){return expected.isEmpty()?1.0:(double)expected.entrySet().stream().filter(e->level.isLoaded(e.getKey()) && level.getBlockState(e.getKey()).getBlock()==e.getValue()).count()/expected.size();}
        long treasury(){return RoyalTreasurySnapshot.forPlayer(ruler).spendableValue();}
        RoyalConstructionStore store(){return new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"));}
        String describe(){var e=id==null?null:store().load(id);return "phase="+phase+" ticks="+ticks+" native="+(first==null?"none":first.getStatus()+"/"+(first.getConstructionTask()==null?"noTask":first.getConstructionTask().getNextStepIndex()+"/"+first.getConstructionTask().totalSteps()))+" royal="+(e==null?"none":e.stage()+"/"+e.blocked())+" firstFinished="+firstFinished+" royalSeen="+royalSeen;}
        void forceArea(BlockPos center,int radius){var c=new ChunkPos(center);for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){var chunk=new ChunkPos(c.x+x,c.z+z);if(forced.add(chunk))level.setChunkForced(chunk.x,chunk.z,true);}}
        @SuppressWarnings("unchecked") void registerOnline()throws Exception{var field=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");field.setAccessible(true);online=(Map<UUID,ServerPlayer>)field.get(level.getServer().getPlayerList());oldOnline=online.put(ruler.getUUID(),ruler);}
        void close(){if(closed)return;closed=true;if(village!=null){village.syncRecords(level);village.setForceActive(false);village.setActive(false);VillageChunkLoader.releaseVillageChunks(level,village.getCenter(),village.getLoadedChunks());village.setChunksForceLoaded(false);village.setLoadedChunks(Set.of());}if(capital!=null)capital.setActive(false);if(online!=null){if(oldOnline==null)online.remove(ruler.getUUID());else online.put(ruler.getUUID(),oldOnline);}for(var c:forced)level.setChunkForced(c.x,c.z,false);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,level.getServer());level.getServer().tickRateManager().stopSprinting();VillageSavedData.get(level).setDirty();}
    }
}
