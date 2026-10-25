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
public final class RoyalUpgradeLiveGameTests {
    @GameTest(templateNamespace="millenairekingdoms_royal_live_tests",template="empty",batch="royal_player_upgrade",timeoutTicks=120_000)
    public static void originalNpcUpgradesPersistAndChargeIncreasingFees(GameTestHelper h) {
        var probe=new Probe(h);h.onEachTick(()->{try{probe.tick();}catch(Exception failure){probe.close();throw new IllegalStateException("Real upgrade: "+probe.describe(),failure);}});
    }
    private static final class Probe {
        final GameTestHelper h;final ServerLevel level;final boolean read,daylight;final Path marker;
        final Set<ChunkPos> forced=new HashSet<>();Village village,capital;UUID kingdom,id,builder;ServerPlayer ruler,oldOnline;Map<UUID,ServerPlayer> online;
        BlockPos anchor=new BlockPos(420_000,-60,440_000);BuildingInstance target;int ticks,phase;boolean closed;java.lang.reflect.Field growth;
        Probe(GameTestHelper h)throws RuntimeException {this.h=h;level=h.getLevel();read="read".equals(System.getProperty("kingdoms.tests.persistence"));marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-player-upgrade-live.dat");daylight=level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());level.setDayTime(6000);h.assertTrue(RoyalConstructionConfig.TRACKING.get(),"Actual scheduled tracking is enabled");}
        void tick()throws Exception{
            if(closed)return;if(++ticks>119000){var why=describe();close();h.fail("Real upgrade timeout: "+why);return;}
            if(phase==0){
                if(read){var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());kingdom=tag.getUUID("kingdom");id=tag.getUUID("order");builder=tag.getUUID("builder");var manager=VillageSavedData.get(level).getVillageManager();village=manager.getVillage(new VillageId(tag.getUUID("village")));capital=manager.getVillage(new VillageId(tag.getUUID("capital")));h.assertTrue(village!=null && capital!=null,"Original village and native storage reopen without repairs");anchor=village.getCenter();target=village.getBuilding(new BuildingId(tag.getUUID("building")));ruler=new FakePlayer(level,new GameProfile(tag.getUUID("ruler"),"Upgrade ruler"));
                    h.assertTrue(target!=null && target.getLevel()==1 && target.isBeingBuilt() && target.getConstructionTask()!=null && target.getConstructionTask().getNextStepIndex()>=tag.getInt("cursor") && store().load(id).intent().upgradeBuilding().equals(target.getId().uuid()),"Same original upgrade target/task/cursor reopens");
                }else{var bank=RoyalPaymentGameTests.persistentFixture(h,"real-upgrade-capital");capital=bank.village();capital.setActive(true);bank.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),6));kingdom=bank.kingdom();ruler=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"Upgrade ruler"));capital.addReputation(ruler.getUUID(),32768-capital.getCombinedReputation(level,ruler.getUUID()));KingdomSavedData.get(level).claimRule(kingdom,ruler.getUUID(),"Upgrade ruler",84);}
                forceArea(anchor,8);for(var pos:capital.getTownhall().getInventory().getChestPositions())forceArea(pos,1);registerOnline();phase=1;return;
            }
            if(phase==1){if(!level.hasChunksAt(anchor.offset(-120,0,-120),anchor.offset(120,0,120)))return;
                if(!read){var manager=VillageSavedData.get(level).getVillageManager();var before=manager.getAllVillages().stream().map(Village::getId).collect(java.util.stream.Collectors.toSet());var type=ModCultures.getVillageType(ResourceLocation.parse("millenaire:norman/hameau_artisans"));var surface=anchor.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,anchor.getX(),anchor.getZ()));var failure=VillageSpawner.spawnVillage(level,surface,type,0);h.assertTrue(failure==null,"Original native baseline spawn: "+failure);village=manager.getAllVillages().stream().filter(v->!before.contains(v.getId())).findFirst().orElseThrow();KingdomSavedData.get(level).attach(kingdom,village.getId().uuid());}
                growth=Village.class.getDeclaredField("growthTickCounter");growth.setAccessible(true);village.setForceActive(true);village.setActive(true);capital.setActive(true);level.getServer().tickRateManager().requestGameToSprint(120000);phase=2;return;
            }
            village.setForceActive(true);village.setActive(true);capital.setActive(true);
            // Test-only pin between requested projects keeps random unrelated growth from consuming supplied upgrade goods.
            // It never edits tasks/cursors/status, coins, royal stages or NPC goals; accepted native pending still launches normally.
            if(village.getPendingProject()==null)growth.setInt(village,0);
            if(phase==2){if(village.getVillagerUuids().stream().noneMatch(u->level.getEntity(u) instanceof MillVillager))return;
                if(read){if(!(level.getEntity(builder) instanceof MillVillager))return;h.assertTrue(treasury()==192 && new RoyalPaymentService(level).inspectPlayer(ruler,id).state()==State.COMMITTED,"Original128 debit and192 coins reopen without reconstruction");phase=4;return;}
                var choice=RoyalConstructionCatalog.forPlayer(ruler,village.getId().uuid()).options().stream().filter(c->c.planSet().equals(ResourceLocation.parse("millenaire:norman/lumbermanhut"))).findFirst().orElseThrow();var set=ModCultures.getBuildingPlanSet(choice.planSet());var bank=new HashMap<Item,Integer>();supply(bank,choice.requiredMaterials());supply(bank,set.getLevel(choice.variant(),1).requiredResources());supply(bank,set.getLevel(choice.variant(),2).requiredResources());
                for(var e:bank.entrySet()){var inventory=village.getTownhall().getInventory();int missing=Math.max(0,e.getValue()-inventory.getCount(level,e.getKey()));h.assertTrue(inventory.add(level,e.getKey(),missing)==missing,"All fixture materials supplied once into real native storage");}
                issue(false,64);phase=3;return;
            }
            var entry=store().load(id);
            if(entry.stage()==Stage.RECOVERY_REQUIRED || entry.stage()==Stage.CANCELLING || entry.stage()==Stage.FAILED)throw new IllegalStateException("Unexpected upgrade state "+entry);
            if(phase==3 && entry.stage()==Stage.COMPLETED){target=village.getBuilding(new BuildingId(entry.building()));h.assertTrue(target!=null && target!=village.getTownhall() && target.getLevel()==0 && match(RoyalRealConstructionGameTests.expectedBlocks(level,target))>=0.90 && treasury()==320,"Original NPCs first construct the real eligible non-centre building for64");issue(true,128);phase=4;return;}
            if(phase==4 && target.getLevel()==1 && target.isBeingBuilt() && target.getConstructionTask()!=null){var task=target.getConstructionTask();
                if(!read && task.getNextStepIndex()>100 && !task.isComplete() && task.getReservedBuilder()!=null && level.getEntity(task.getReservedBuilder()) instanceof MillVillager actual){builder=actual.getUUID();h.assertTrue(treasury()==192 && entry.intent().upgradeBuilding().equals(target.getId().uuid()) && entry.intent().fee()==128,"First real upgrade targets the same existing UUID and costs128 once");village.syncRecords(level);new RoyalVillagePersistence().building(level,village.getId().uuid(),target);var tag=new CompoundTag();tag.putUUID("kingdom",kingdom);tag.putUUID("capital",capital.getId().uuid());tag.putUUID("village",village.getId().uuid());tag.putUUID("building",target.getId().uuid());tag.putUUID("order",id);tag.putUUID("ruler",ruler.getUUID());tag.putUUID("builder",builder);tag.putInt("cursor",task.getNextStepIndex());NbtIo.writeCompressed(tag,marker);System.out.println("ROYAL_UPGRADE_WRITE_PASS level1 task="+task.getNextStepIndex()+"/"+task.totalSteps()+" treasury192 fee128 target="+target.getId());close();h.succeed();return;}
            }
            if(read && phase==4 && entry.stage()==Stage.COMPLETED){h.assertTrue(target.getLevel()==1 && target.getConstructionTask().isComplete() && match(RoyalRealConstructionGameTests.expectedBlocks(level,target))>=0.90 && treasury()==192,"Original resident completes the saved real first upgrade physically without another fee");issue(true,192);phase=5;return;}
            if(read && phase==5 && entry.stage()==Stage.COMPLETED){h.assertTrue(target.getLevel()==2 && target.getStatus()==BuildingInstance.Status.COMPLETE && target.getConstructionTask().isComplete() && match(RoyalRealConstructionGameTests.expectedBlocks(level,target))>=0.90 && treasury()==0 && new RoyalPaymentService(level).inspectPlayer(ruler,id).state()==State.COMMITTED && village.getOwnerUUID()==null,"Second original upgrade completes physically for192 once, same UUID, native ownership intact");System.out.println("ROYAL_UPGRADE_READ_PASS sameTarget=true level2 physicalMatch="+match(RoyalRealConstructionGameTests.expectedBlocks(level,target))+" fees64+128+192 treasury0");close();h.succeed();return;}
            if(ticks%2000==0)System.out.println("ROYAL_UPGRADE_PROGRESS "+describe());
        }
        void issue(boolean upgrade,long fee){RoyalConstructionView.select(ruler,village.getId().uuid());var frame=RoyalConstructionView.build(ruler,kingdom);CompoundTag offer=null;for(var raw:frame.getList("plans",Tag.TAG_COMPOUND)){var tag=(CompoundTag)raw;if(upgrade?tag.hasUUID("building") && tag.getUUID("building").equals(target.getId().uuid()):!tag.getBoolean("upgrade") && tag.getString("plan").equals("millenaire:norman/lumbermanhut")){offer=tag;break;}}
            h.assertTrue(offer!=null && offer.getBoolean("canOrder") && offer.getLong("fee")==fee,"Current original eligible "+(upgrade?"upgrade":"building")+" offer at"+fee+": "+frame);id=offer.getUUID("offer");RoyalConstructionView.order(ruler,village.getId().uuid(),id,ResourceLocation.parse(offer.getString("plan")));h.assertTrue(store().load(id).stage()==Stage.QUEUED,"Real offer accepted and persisted; only native engine/NPCs drive later work");}
        void supply(Map<Item,Integer> bank,Map<ResourceLocation,Integer> resources){for(var e:resources.entrySet()){Item item=AnywoodHelper.isAnywood(e.getKey())?Items.OAK_LOG:ItemHelper.resolve(e.getKey());h.assertTrue(item!=null,"Material resolves");bank.merge(item,Math.multiplyExact(2,e.getValue()),Math::addExact);}}
        double match(Map<BlockPos,Block> expected){return expected.isEmpty()?1:(double)expected.entrySet().stream().filter(e->level.isLoaded(e.getKey()) && level.getBlockState(e.getKey()).getBlock()==e.getValue()).count()/expected.size();}
        long treasury(){return RoyalTreasurySnapshot.forPlayer(ruler).spendableValue();}
        RoyalConstructionStore store(){return new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"));}
        String describe(){var entry=id==null?null:store().load(id);return "phase="+phase+" ticks="+ticks+" order="+(entry==null?"none":entry.stage()+"/"+entry.blocked())+" target="+(target==null?"none":target.getLevel()+"/"+target.getStatus()+"/"+(target.getConstructionTask()==null?"noTask":target.getConstructionTask().getNextStepIndex()+"/"+target.getConstructionTask().totalSteps()));}
        void forceArea(BlockPos center,int radius){var c=new ChunkPos(center);for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){var chunk=new ChunkPos(c.x+x,c.z+z);if(forced.add(chunk))level.setChunkForced(chunk.x,chunk.z,true);}}
        @SuppressWarnings("unchecked")void registerOnline()throws Exception{var field=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");field.setAccessible(true);online=(Map<UUID,ServerPlayer>)field.get(level.getServer().getPlayerList());oldOnline=online.put(ruler.getUUID(),ruler);}
        void close(){if(closed)return;closed=true;if(village!=null){village.syncRecords(level);village.setForceActive(false);village.setActive(false);VillageChunkLoader.releaseVillageChunks(level,village.getCenter(),village.getLoadedChunks());village.setChunksForceLoaded(false);village.setLoadedChunks(Set.of());}if(capital!=null)capital.setActive(false);if(online!=null){if(oldOnline==null)online.remove(ruler.getUUID());else online.put(ruler.getUUID(),oldOnline);}for(var c:forced)level.setChunkForced(c.x,c.z,false);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,level.getServer());level.getServer().tickRateManager().stopSprinting();VillageSavedData.get(level).setDirty();}
    }
}
