package com.millenairekingdoms.test;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.shipping.RoyalShipmentService;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import com.millenairekingdoms.shipping.RoyalShipmentConfig;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import com.millenairekingdoms.economy.RoyalPaymentJournal;
import com.millenairekingdoms.economy.RoyalCoinPlan;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.SpecialPoint;
import org.millenaire.culture.ModCultures;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Real original native plan/village storage and real political membership. Fixtures supply goods/currency and explicitly
 * load/activate their own villages. No commissioned building/NPC/caravan is fabricated or claimed by these cargo tests. */
final class RoyalShipmentSupport {
    private static final AtomicInteger NEXT=new AtomicInteger();
    record Realm(ServerLevel level,UUID kingdom,Village capital,Village source,Village target) implements AutoCloseable {
        RoyalShipmentService service(){return new RoyalShipmentService(level);}
        RoyalShipmentStore store(){return new RoyalShipmentStore(RoyalShipmentService.directory(level));}
        RoyalPaymentJournal payments(){return new RoyalPaymentJournal(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-payments"));}
        ChestBlockEntity chest(Village village){return (ChestBlockEntity)level.getBlockEntity(village.getTownhall().getInventory().getChestPositions().getFirst());}
        int goods(Village village){int total=0;for(var pos:village.getTownhall().getInventory().getChestPositions()){var c=(ChestBlockEntity)level.getBlockEntity(pos);for(int slot=0;slot<27;slot++)if(c.getItem(slot).is(Items.CHERRY_LOG))total+=c.getItem(slot).getCount();}return total;}
        long coins(){var slots=new ArrayList<ItemStack>();for(var pos:capital.getTownhall().getInventory().getChestPositions()){var c=(ChestBlockEntity)level.getBlockEntity(pos);for(int slot=0;slot<27;slot++)slots.add(c.getItem(slot));}return RoyalCoinPlan.spendableValue(slots);}
        void activate(boolean targetToo){activateVillage(level,capital);activateVillage(level,source);if(targetToo)activateVillage(level,target);}
        UUID from(){return source.getId().uuid();}UUID to(){return target.getId().uuid();}
        FakePlayer ruler(){var p=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"Shipment ruler"));capital.addReputation(p.getUUID(),40000-capital.getCombinedReputation(level,p.getUUID()));KingdomSavedData.get(level).claimRule(kingdom,p.getUUID(),"Shipment ruler",level.getGameTime());return p;}
        @Override public void close(){var manager=VillageSavedData.get(level).getVillageManager();manager.removeVillage(capital.getId());manager.removeVillage(source.getId());manager.removeVillage(target.getId());}
        CompoundTag marker(){var tag=new CompoundTag();tag.putUUID("kingdom",kingdom);tag.putUUID("capital",capital.getId().uuid());tag.putUUID("source",from());tag.putUUID("target",to());return tag;}
        void save(){VillageSavedData.get(level).setDirty();KingdomSavedData.get(level).setDirty();level.getServer().saveEverything(true,true,true);}
    }
    static Realm realm(GameTestHelper h,String name){
        var level=h.getLevel();int x=(read() ? 600_000 : 300_000)+NEXT.incrementAndGet()*8192;
        var capital=village(level,name+" capital",new BlockPos(x,-50,70_000));var source=village(level,name+" source",new BlockPos(x+512,-50,70_000));var target=village(level,name+" target",new BlockPos(x+1024,-50,70_000));
        var data=KingdomSavedData.get(level);var k=data.create(name+" "+UUID.randomUUID().toString().substring(0,8),capital.getId().uuid());data.attach(k.id(),source.getId().uuid());data.attach(k.id(),target.getId().uuid());var realm=new Realm(level,k.id(),capital,source,target);
        realm.chest(capital).setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));realm.chest(source).setItem(0,new ItemStack(Items.CHERRY_LOG,64));realm.chest(source).setItem(1,new ItemStack(Items.CHERRY_LOG,32));realm.chest(target).setItem(0,new ItemStack(Items.CHERRY_LOG,4));return realm;
    }
    static Realm restore(GameTestHelper h,CompoundTag tag,boolean loadTarget){
        var level=h.getLevel();var manager=VillageSavedData.get(level).getVillageManager();var capital=manager.getVillage(new VillageId(tag.getUUID("capital")));var source=manager.getVillage(new VillageId(tag.getUUID("source")));var target=manager.getVillage(new VillageId(tag.getUUID("target")));
        h.assertTrue(capital!=null && source!=null && target!=null && capital.getTownhall().getInventory()!=null && source.getTownhall().getInventory()!=null && target.getTownhall().getInventory()!=null,"Original native records rehydrate all three genuine storage definitions without repair");
        var realm=new Realm(level,tag.getUUID("kingdom"),capital,source,target);realm.activate(loadTarget);return realm;
    }
    static Village village(ServerLevel level,String name,BlockPos origin){
        var plan=ModCultures.getAllBuildingPlans().values().stream().filter(p->p.id().toString().equals("millenaire:byzantines/archives_a_0")).findFirst().orElseThrow();
        var type=ModCultures.getAllVillageTypes().values().stream().filter(t->t.culture().equals(plan.culture()) && !t.loneBuilding() && !t.playerControlled()).findFirst().orElseThrow();
        var village=new Village(VillageId.random(),type.culture(),type.id(),origin);village.setVillageName(name);var hall=new BuildingInstance(BuildingId.random(),plan.id(),origin,Rotation.NONE,BuildingInstance.Status.COMPLETE);hall.resolveSpecialPoints(plan);hall.initInventory();village.addBuilding(hall);
        level.getChunkAt(origin);for(var pos:hall.getInventory().getChestPositions()){level.getChunkAt(pos);level.setBlock(pos,Blocks.CHEST.defaultBlockState(),2);}VillageSavedData.get(level).getVillageManager().addVillage(village);activateVillage(level,village);return village;
    }
    static void activateVillage(ServerLevel level,Village village){village.setNoProjectsLeftUntil(Long.MAX_VALUE);village.setActive(true);level.getChunkAt(village.getCenter());village.getTownhall().getInventory().loadChunks(level);}
    static RoyalShipmentStore.Entry fastRequest(Realm realm,FakePlayer player,UUID id){return fastRequest(realm,player,id,realm.service());}
    static RoyalShipmentStore.Entry fastRequest(Realm realm,FakePlayer player,UUID id,RoyalShipmentService service){
        int base=RoyalShipmentConfig.BASE_TRAVEL.get(),scale=RoyalShipmentConfig.TRAVEL_PER128.get();try{RoyalShipmentConfig.BASE_TRAVEL.set(1);RoyalShipmentConfig.TRAVEL_PER128.set(1);
            return player==null ? service.requestNpc(realm.kingdom,realm.from(),realm.to(),net.minecraft.resources.ResourceLocation.parse("minecraft:cherry_log"),40,id) : service.requestPlayer(player,realm.from(),realm.to(),net.minecraft.resources.ResourceLocation.parse("minecraft:cherry_log"),40,id);
        }finally{RoyalShipmentConfig.BASE_TRAVEL.set(base);RoyalShipmentConfig.TRAVEL_PER128.set(scale);}
    }
    static boolean read(){return "read".equals(System.getProperty("kingdoms.tests.persistence"));}
    private RoyalShipmentSupport(){}
}
