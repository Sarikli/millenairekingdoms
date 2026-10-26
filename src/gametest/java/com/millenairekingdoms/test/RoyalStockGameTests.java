package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalVillagePersistence;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import com.millenairekingdoms.shipping.RoyalShipmentConfig;
import com.millenairekingdoms.shipping.RoyalVillageStock;
import com.mojang.authlib.GameProfile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.AnywoodHelper;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.building.GoodAvailabilityHelper;
import org.millenaire.commerce.TradeGoodsLoader;
import org.millenaire.culture.ModCultures;
import org.millenaire.item.ItemHelper;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageGrowthManager;
import org.millenaire.village.VillageSavedData;

/** Real native export inputs and physical snapshots. No shipment/debit/escrow is performed. */
@PrefixGameTestTemplate(false)
public final class RoyalStockGameTests {
    private static final String NS="millenairekingdoms_royal_goods_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeTargetAndConfiguredFloorProtectActualOrdinaryStock(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"stock-target")) {
            var entry=TradeGoodsLoader.getTargetQuantities(f.village().getCultureId()).entrySet().stream()
                    .filter(e->e.getValue()>0 && e.getValue()<=128 && e.getKey().getDefaultMaxStackSize()==64 && !coin(e.getKey()))
                    .sorted(java.util.Comparator.comparing(e->net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).toString())).findFirst().orElseThrow();
            int amount=entry.getValue()+80;put(f,entry.getKey(),amount);var quote=stock(f);var slots=quote.slots();var available=quote.availability(entry.getKey());
            h.assertTrue(available.physical()==amount && available.target()==entry.getValue() && available.available()==amount-Math.min(50,Math.max(entry.getValue(),16))
                    && available.nativeExport()==amount-entry.getValue(),"Original native target stock and configured minimum guard real ordinary chest units");
            var previous=RoyalShipmentConfig.MINIMUM_REMAINDER.get();
            try {RoyalShipmentConfig.MINIMUM_REMAINDER.set(amount+1);h.assertTrue(quote.availability(entry.getKey()).available()==amount-50,"Older excessive server floors are capped at50 without a withdrawal");}
            finally {RoyalShipmentConfig.MINIMUM_REMAINDER.set(previous);}
            quote.requireUnchanged();h.assertTrue(equal(slots,quote.slots()),"Reserve inspection never alters real goods");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void staleNativeCountCacheIsNotTrustedByAStockQuote(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"stock-cache")) {
            f.chest().setItem(0,new ItemStack(Items.DIAMOND,64));var inventory=f.village().getTownhall().getInventory();inventory.invalidateCache();inventory.getCount(f.level(),Items.DIAMOND);
            f.chest().setItem(0,new ItemStack(Items.DIAMOND,32));var result=stock(f).availability(Items.DIAMOND);
            h.assertTrue(result.physical()==32 && result.nativeCount()==32 && result.available()<=16 && f.chest().getItem(0).getCount()==32,
                    "Native stock is freshly scanned after a same-tick physical change; cached larger values cannot authorize exports");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void customGoodsAndCurrencyNeverBecomeRemovableOrdinaryStock(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"stock-custom")) {
            f.chest().setItem(0,new ItemStack(Items.DIAMOND,64));var custom=new ItemStack(Items.DIAMOND,64);custom.set(DataComponents.CUSTOM_NAME,Component.literal("Private component stock"));
            f.chest().setItem(1,custom);f.chest().setItem(2,new ItemStack(ModItems.DENIER_ARGENT.get(),64));var quote=stock(f);var result=quote.availability(Items.DIAMOND);
            h.assertTrue(result.physical()==64 && result.nativeCount()==128 && result.available()<=48 && !quote.products().contains(ModItems.DENIER_ARGENT.get()),
                    "Native totals may include custom stacks, but only ordinary actual chest goods can be offered; currency stays financial");
            refused(h,()->quote.availability(ModItems.DENIER_ARGENT.get()));quote.slots().getFirst().setCount(1);quote.requireUnchanged();
            h.assertTrue(f.chest().getItem(0).getCount()==64 && ItemStack.matches(f.chest().getItem(1),custom),"Returned snapshots do not mutate protected goods");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void membershipRulerReputationAndNpcExclusionAreRevalidated(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"stock-authority");var other=fixture(h,"stock-foreign")) {
            var original=stock(f);refused(h,()->RoyalVillageStock.forNpc(f.level(),other.kingdom(),f.village().getId().uuid()));
            var player=new FakePlayer(f.level(),new GameProfile(UUID.randomUUID(),"Stock ruler"));f.village().addReputation(player.getUUID(),32768-f.village().getCombinedReputation(f.level(),player.getUUID()));
            KingdomSavedData.get(f.level()).claimRule(f.kingdom(),player.getUUID(),"Stock ruler",42);refused(h,original::requireUnchanged);refused(h,()->stock(f));
            var quoted=RoyalVillageStock.forPlayer(player,f.village().getId().uuid());f.village().addReputation(player.getUUID(),-f.village().getCombinedReputation(f.level(),player.getUUID()));
            refused(h,quoted::requireUnchanged);h.assertTrue(f.chest().isEmpty(),"Read-only agency/reputation failures cannot charge or populate storage");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void changedSlotsReplacedChestsAndStorageReferencesInvalidateQuotes(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"stock-stale")) {
            put(f,Items.DIAMOND,32);var quote=stock(f);f.chest().setItem(0,new ItemStack(Items.DIAMOND,31));refused(h,quote::requireUnchanged);
            var replacement=stock(f);var pos=f.position();f.level().setBlock(pos,Blocks.AIR.defaultBlockState(),2);f.level().setBlock(pos,Blocks.CHEST.defaultBlockState(),2);f.chest().setItem(0,new ItemStack(Items.DIAMOND,31));
            refused(h,replacement::requireUnchanged);var reference=stock(f);var original=f.village().getTownhall().getInventory();
            try {inventory(f,new BuildingInventory(original.getChestPositions()));refused(h,reference::requireUnchanged);}
            finally {inventory(f,original);}h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unavailableChestFurnaceAndFirepitPositionsStayUnloaded(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"stock-unloaded")) {
            var original=f.village().getTownhall().getInventory();var remote=new BlockPos(8_000_000,64,8_000_000);var declared=new ArrayList<>(original.getChestPositions());declared.add(remote);
            try {
                for(var replacement:List.of(new BuildingInventory(declared),new BuildingInventory(original.getChestPositions(),List.of(remote)),new BuildingInventory(original.getChestPositions(),List.of(),List.of(remote)))) {
                    inventory(f,replacement);refused(h,()->stock(f));h.assertTrue(!f.level().isLoaded(remote),"Every native inventory position is guarded before native scans; distant stock chunks stay unloaded");
                }
            } finally {inventory(f,original);}h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void bothDoubleChestHalvesAreCountedExactlyOnceAndReconnectionInvalidates(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"stock-double")) {
            var a=f.position();var b=a.east();f.level().getChunkAt(b);
            var left=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.LEFT);
            var right=left.setValue(ChestBlock.TYPE,ChestType.RIGHT);f.level().setBlock(a,left,2);f.level().setBlock(b,right,2);
            ((ChestBlockEntity)f.level().getBlockEntity(a)).setItem(0,new ItemStack(Items.DIAMOND,32));((ChestBlockEntity)f.level().getBlockEntity(b)).setItem(0,new ItemStack(Items.DIAMOND,40));
            var original=f.village().getTownhall().getInventory();
            try {
                inventory(f,new BuildingInventory(List.of(a,b,a)));var quote=stock(f);var result=quote.availability(Items.DIAMOND);
                h.assertTrue(quote.positions().size()==2 && quote.slots().size()==54 && result.physical()==72 && result.nativeCount()==72,"Duplicate declared positions and connected halves never double-count goods");
                f.level().setBlock(a,left.setValue(ChestBlock.TYPE,ChestType.SINGLE),2);refused(h,quote::requireUnchanged);
            } finally {inventory(f,original);}h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unknownPendingRequirementsCannotBeInterpretedAsFreeExportStock(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"stock-unknown-plan")) {
            put(f,Items.DIAMOND,64);f.village().setPendingProject(new Village.PendingProject(ResourceLocation.parse("millenairekingdoms:unknown_native_work"),"a",0,false,null));
            var quote=stock(f);refused(h,()->quote.availability(Items.DIAMOND));h.assertTrue(f.chest().getItem(0).getCount()==64 && f.village().getPendingProject()!=null,
                    "Unknown existing work requirements preserve stock and project metadata rather than assuming no reserve");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void pendingAnywoodNeedsProtectEveryRealLogSpecies(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"stock-anywood")) {
            var choice=anywood();var item=Items.CHERRY_LOG;var key=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            int need=choice.definition.requiredResources().get(AnywoodHelper.ANYWOOD_LOG);
            int target=TradeGoodsLoader.getTargetQuantities(f.village().getCultureId()).getOrDefault(item,0);
            int specific=choice.definition.requiredResources().getOrDefault(key,0);put(f,item,target+need+specific+32);
            var pending=new Village.PendingProject(choice.set,choice.variant,0,false,null);f.village().setPendingProject(pending);
            var result=stock(f).availability(item);
            h.assertTrue(new ItemStack(item).is(AnywoodHelper.LOGS_TAG) && result.construction()>=need+specific && result.retained()<=50
                    && result.retained()>=Math.min(50,need+specific) && result.available()==Math.max(0,result.physical()-result.retained()) && f.village().getPendingProject()==pending,
                    "Actual original anywood needs remain visible while their total shipment protection obeys the50-unit ceiling");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void genuinelyStartedUnclaimedNativeWorkRetainsItsFullMaterialCost(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"stock-native-work")) {
            var choice=RoyalConstructionCatalog.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).options().stream().filter(c->c.requiredMaterials().values().stream().anyMatch(n->n>0))
                    .min(java.util.Comparator.comparingInt(c->c.requiredMaterials().values().stream().mapToInt(Integer::intValue).sum())).orElseThrow();
            var definition=ModCultures.getBuildingPlanSet(choice.planSet()).getLevel(choice.variant(),0);Item watched=null;int needed=0;
            for(var material:definition.requiredResources().entrySet()) {
                var item=AnywoodHelper.isAnywood(material.getKey()) ? Items.OAK_LOG : ItemHelper.resolve(material.getKey());put(f,item,material.getValue());
                if(watched==null && material.getValue()>0){watched=item;needed=material.getValue();}
            }
            var center=f.village().getCenter();int radius=ModCultures.getVillageType(f.village().getVillageTypeId()).radius()+16;
            var original=new java.util.HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
            try {
                for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){var pos=center.offset(x,-1,z);original.put(pos,f.level().getBlockState(pos));f.level().setBlock(pos,Blocks.GRASS_BLOCK.defaultBlockState(),2);}
                f.village().setPendingProject(new Village.PendingProject(choice.planSet(),choice.variant(),0,false,null));f.village().setNoProjectsLeftUntil(0);VillageGrowthManager.evaluateGrowth(f.level(),f.village());
                var building=f.village().getBuildings().stream().filter(b->choice.planSet().equals(b.getPlanSetId())).findFirst().orElseThrow();
                h.assertTrue(building.isBeingBuilt() && building.getConstructionTask()!=null && building.getConstructionTask().totalSteps()>0
                        && building.getConstructionTask().getReservedBuilder()==null,"Original finder/compiler genuinely starts nonempty native work before a builder reserves it");
                var result=stock(f).availability(watched);
                h.assertTrue(result.construction()>=needed && GoodAvailabilityHelper.getConstructionReservedQuantity(watched,f.level(),f.village(),null)==0
                        && result.retained()>=Math.min(50,needed) && result.retained()<=50,"Native work remains intact and observed while shipment protection is bounded at50");
            } finally {for(var e:original.entrySet())f.level().setBlock(e.getKey(),e.getValue(),2);}
            h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",batch="stock_real_residents",timeoutTicks=4000)
    public static void originalSpawnerResidentsKeepTheirWholeVillageRequiredGoods(GameTestHelper h) {
        var probe=new RoyalRealConstructionGameTests.Probe(h,false,new RoyalConstructionService(h.getLevel()));
        probe.kingdomName="Stock resident "+System.getProperty("kingdoms.tests.persistence");
        if("read".equals(System.getProperty("kingdoms.tests.persistence")))probe.anchor=new BlockPos(320_000,-60,260_000);
        int[] waiting={0};
        h.onEachTick(()->{
            if(probe.closed)return;
            try {
                if(probe.phase==2) {
                    probe.village.syncRecords(probe.level);int required=0;
                    for(var b:probe.village.getBuildings())required+=GoodAvailabilityHelper.getSinkRequiredGoods(b,Items.OAK_LOG,probe.village);
                    if(required==0) {if(++waiting[0]>1000)throw new IllegalStateException("Native households did not provide expected recorded log needs: "+probe.diagnostic());return;}
                    int added=probe.village.getTownhall().getInventory().add(probe.level,Items.OAK_LOG,256);h.assertTrue(added>0,"Known test goods enter real original townhall storage");
                    var quote=RoyalVillageStock.forNpc(probe.level,probe.kingdom,probe.village.getId().uuid());var stock=quote.availability(Items.OAK_LOG);
                    long living=probe.village.getVillagerUuids().stream().filter(id->probe.level.getEntity(id) instanceof org.millenaire.entity.MillVillager).count();
                    h.assertTrue(living>0 && stock.residents()>=required && stock.residents()>0 && stock.retained()>=Math.min(50,stock.residents())
                            && stock.retained()<=50 && stock.available()==Math.max(0,stock.physical()-stock.retained()),"All original households contribute to diagnostics but combined shipment protection never exceeds50");
                    quote.requireUnchanged();System.out.println("ROYAL_STOCK_REAL_RESIDENTS living="+living+" residentsReserve="+stock.residents()+" physical="+stock.physical()+" retained="+stock.retained()+" available="+stock.available());
                    probe.close();h.succeed();return;
                }
                probe.tick();
            } catch(Exception failure) {probe.close();throw new IllegalStateException("Native resident stock probe "+probe.diagnostic(),failure);}
        });
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeVillageStockAuthorityAndSurplusReopenWithoutRepair(GameTestHelper h) throws Exception {
        var level=h.getLevel();Path marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-native-stock.dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024));var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));
            h.assertTrue(village!=null && village.getTownhall()!=null,"Original native village/real plan reopens without metadata repair");village.getTownhall().getInventory().loadChunks(level);village.setActive(true);
            var quote=RoyalVillageStock.forNpc(level,tag.getUUID("kingdom"),village.getId().uuid());var stock=quote.availability(Items.CHERRY_LOG);
            h.assertTrue(stock.physical()==96 && stock.available()==tag.getInt("available") && stock.retained()==tag.getInt("retained"),"Saved actual goods, political agency and native surplus reopen consistently in a separate JVM");
            village.setActive(false);System.out.println("ROYAL_NATIVE_STOCK_RESTART_READ_PASS physical="+stock.physical()+" retained="+stock.retained()+" available="+stock.available());h.succeed();return;
        }
        var f=fixture(h,"stock-restart");put(f,Items.CHERRY_LOG,96);var quote=stock(f);var stock=quote.availability(Items.CHERRY_LOG);
        new RoyalTreasuryPersistence().flush(level,quote.positions());new RoyalVillagePersistence().absentPending(level,f.village().getId().uuid());
        var tag=new CompoundTag();tag.putUUID("kingdom",f.kingdom());tag.putUUID("village",f.village().getId().uuid());tag.putInt("available",stock.available());tag.putInt("retained",stock.retained());NbtIo.writeCompressed(tag,marker);
        f.village().setActive(false);VillageSavedData.get(level).setDirty();System.out.println("ROYAL_NATIVE_STOCK_RESTART_WRITE_PASS physical="+stock.physical()+" retained="+stock.retained()+" available="+stock.available());h.succeed();
    }

    private record Wood(ResourceLocation set,String variant,org.millenaire.building.BuildingPlanSet.LevelDef definition) {}
    private static Wood anywood() {
        for(var set:ModCultures.getAllBuildingPlanSets().values().stream().sorted(java.util.Comparator.comparing(s->s.id().toString())).toList())for(var variant:set.variants().keySet()) {
            var def=set.getLevel(variant,0);if(def!=null && def.requiredResources().getOrDefault(AnywoodHelper.ANYWOOD_LOG,0)>0
                    && def.requiredResources().get(AnywoodHelper.ANYWOOD_LOG)<=128)return new Wood(set.id(),variant,def);
        }
        throw new IllegalStateException("Original9.0.3 content has no bounded anywood plan for the reserve test");
    }
    private static RoyalPaymentGameTests.Fixture fixture(GameTestHelper h,String name) {
        var f=RoyalPaymentGameTests.persistentFixture(h,name);f.village().setActive(true);f.village().setNoProjectsLeftUntil(Long.MAX_VALUE);
        // Test-only loading of the native declared fixture. Production adapters never call loadChunks.
        f.village().getTownhall().getInventory().loadChunks(f.level());return f;
    }
    private static RoyalVillageStock stock(RoyalPaymentGameTests.Fixture f){return RoyalVillageStock.forNpc(f.level(),f.kingdom(),f.village().getId().uuid());}
    private static void inventory(RoyalPaymentGameTests.Fixture f,BuildingInventory inventory) throws Exception {var field=BuildingInstance.class.getDeclaredField("inventory");field.setAccessible(true);field.set(f.village().getTownhall(),inventory);}
    private static void put(RoyalPaymentGameTests.Fixture f,Item item,int amount) {int inserted=f.village().getTownhall().getInventory().add(f.level(),item,amount);if(inserted!=amount)throw new IllegalStateException("Known test goods do not fit original storage: "+amount+"/"+inserted);}
    private static boolean coin(Item item){return item==ModItems.DENIER.get() || item==ModItems.DENIER_ARGENT.get() || item==ModItems.DENIER_OR.get();}
    private static boolean equal(List<ItemStack> a,List<ItemStack> b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!ItemStack.matches(a.get(i),b.get(i)))return false;return true;}
    private static void refused(GameTestHelper h,Runnable action){boolean rejected=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"Unknown, unauthorized, unavailable or stale stock must fail closed");}
}
