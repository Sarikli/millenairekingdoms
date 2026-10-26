package com.millenairekingdoms.shipping;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.millenaire.building.AnywoodHelper;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.building.GoodAvailabilityHelper;
import org.millenaire.commerce.TradeGoodsLoader;
import org.millenaire.culture.ModCultures;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;

/** Authorized loaded physical townhall snapshot; no withdrawal, reservation, escrow or forced loading. */
public final class RoyalVillageStock {
    /** Total retained ordinary units per product, including every native need and configured floor. */
    public static final int MAXIMUM_RETAINED=50;
    public record Availability(ResourceLocation product,int physical,int nativeCount,int nativeExport,int construction,
                               int target,int residents,int retained,int available) {}
    private record Chest(BlockPos position,ChestBlockEntity entity,BlockState state) {}
    private final ServerLevel level;
    private final RoyalShipmentStore.Intent issued;
    private final RoyalShipmentStore.Endpoint endpoint;
    private final boolean returning;
    private final ServerPlayer player;
    private final UUID kingdom,villageId,capital,localOwner;
    private final long tenure;
    private final ResourceLocation villageType;
    private final Village village;
    private final BuildingInstance townhall;
    private final BuildingInventory inventory;
    private final NativeStockBridge.Positions declared;
    private final List<Chest> chests;
    private final List<ItemStack> slots;

    public static RoyalVillageStock forNpc(ServerLevel level,UUID kingdom,UUID village) {return new RoyalVillageStock(level,null,kingdom,village);}
    public static RoyalVillageStock forPlayer(ServerPlayer player,UUID village) {
        if(!player.serverLevel().getServer().isSameThread())throw new IllegalStateException("Village stock access requires the server thread");
        return new RoyalVillageStock(player.serverLevel(),player,PoliticsService.ruledKingdom(player).id(),village);
    }
    /** Existing paid mandate only: an offline actor keeps UUID/tenure/reputation checks, never NPC impersonation. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static RoyalVillageStock forIssued(ServerLevel level,RoyalShipmentStore.Intent intent,RoyalShipmentStore.Endpoint endpoint) {
        return issued(level,intent,endpoint,false);
    }
    /** Previously acquired cargo return only; no removal/new spending. Original active kingdom membership and exact
     * original physical village identity remain required, even after its ruler changes. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static RoyalVillageStock forReturn(ServerLevel level,RoyalShipmentStore.Intent intent) {
        return issued(level,intent,intent.source(),true);
    }
    private static RoyalVillageStock issued(ServerLevel level,RoyalShipmentStore.Intent intent,RoyalShipmentStore.Endpoint endpoint,boolean returning) {
        if(!intent.dimension().equals(level.dimension().location().toString()) || !endpoint.equals(intent.source()) && !endpoint.equals(intent.destination()))
            throw new IllegalArgumentException("Issued stock endpoint does not belong to this original shipment/dimension");
        return new RoyalVillageStock(level,null,intent.kingdom(),endpoint.village(),intent,endpoint,returning);
    }
    private RoyalVillageStock(ServerLevel level,ServerPlayer player,UUID kingdom,UUID villageId) {
        this(level,player,kingdom,villageId,null,null,false);
    }
    private RoyalVillageStock(ServerLevel level,ServerPlayer player,UUID kingdom,UUID villageId,RoyalShipmentStore.Intent issued,RoyalShipmentStore.Endpoint endpoint,boolean returning) {
        this.level=level;this.player=player;this.kingdom=kingdom;this.villageId=villageId;this.issued=issued;this.endpoint=endpoint;this.returning=returning;
        var k=authorize();capital=k.capital();tenure=player==null ? 0 : KingdomSavedData.get(level).ruler(kingdom).sinceGameTime();
        village=PoliticsService.village(level,villageId);villageType=village.getVillageTypeId();localOwner=village.getOwnerUUID();
        townhall=village.getTownhall();inventory=townhall==null ? null : townhall.getInventory();
        if(inventory==null)throw new IllegalStateException("Original townhall stock is unavailable");
        declared=NativeStockBridge.positions(inventory);
        if(declared.chests().isEmpty() || declared.chests().size()>128)throw new IllegalStateException("Unsupported village chest count");
        guardAuxiliary();var physical=new LinkedHashMap<BlockPos,Chest>();
        for(var position:declared.chests()) {
            var chest=resolve(position);physical.putIfAbsent(position,chest);
            if(chest.state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE) {
                var neighbor=position.relative(ChestBlock.getConnectedDirection(chest.state));var other=resolve(neighbor);
                if(other.state.getBlock()!=chest.state.getBlock() || other.state.getValue(ChestBlock.FACING)!=chest.state.getValue(ChestBlock.FACING)
                        || other.state.getValue(ChestBlock.TYPE)==ChestType.SINGLE || !neighbor.relative(ChestBlock.getConnectedDirection(other.state)).equals(position))
                    throw new IllegalStateException("Village double chest connection changed");
                physical.putIfAbsent(neighbor,other);
            }
        }
        if(physical.size()>128)throw new IllegalStateException("Too many physical village chest halves");
        chests=List.copyOf(physical.values());slots=readSlots();
    }

    public UUID kingdom(){return kingdom;}
    public RoyalShipmentStore.Endpoint endpoint(){requireUnchanged();return new RoyalShipmentStore.Endpoint(villageId,villageType,townhall.getId().uuid(),village.getCenter(),localOwner);}
    public UUID village(){return villageId;}
    public List<BlockPos> positions(){return chests.stream().map(Chest::position).toList();}
    public List<ItemStack> slots(){return slots.stream().map(ItemStack::copy).toList();}
    public List<Item> products(){return slots.stream().filter(RoyalVillageStock::ordinary).map(ItemStack::getItem).distinct().sorted(java.util.Comparator.comparing(i->BuiltInRegistries.ITEM.getKey(i).toString())).toList();}

    /** Read-only current native project deficits, with original anywood/fixed-log overlap semantics. All native
     * auxiliary storage has already passed loaded guards. This never reserves/removes/creates materials. */
    public java.util.Map<ResourceLocation,Integer> missing(java.util.Map<ResourceLocation,Integer> required){
        requireUnchanged();if(required.values().stream().anyMatch(q->q<0))throw new IllegalArgumentException("Unknown native material quantities");
        inventory.invalidateCache();var missing=new java.util.LinkedHashMap<ResourceLocation,Integer>();
        for(var entry:required.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()){
            if(AnywoodHelper.isAnywood(entry.getKey()))continue;
            var item=BuiltInRegistries.ITEM.getOptional(entry.getKey()).orElseThrow(()->new IllegalStateException("Unknown native project material"));
            int deficit=Math.max(0,entry.getValue()-inventory.getCount(level,item));if(deficit>0)missing.put(entry.getKey(),deficit);
        }
        int anywood=required.getOrDefault(AnywoodHelper.ANYWOOD_LOG,0);
        if(anywood>0){
            int count=AnywoodHelper.anywoodAvailable(inventory.getCountByTag(level,AnywoodHelper.LOGS_TAG),required,key->{
                var item=BuiltInRegistries.ITEM.getOptional(key).orElseThrow(()->new IllegalStateException("Unknown fixed-log material"));return inventory.getCount(level,item);
            });
            if(count<anywood)missing.put(AnywoodHelper.ANYWOOD_LOG,anywood-count);
        }
        requireUnchanged();return java.util.Map.copyOf(missing);
    }

    /** Native construction/household/target needs with a total ceiling of50 ordinary units per product.
     * Furnace/firepit/custom counts are never credited as transferable or as backing those protected ordinary goods. */
    public Availability availability(Item item) {
        if(item==null || item==Items.AIR || !ordinary(new ItemStack(item)))throw new IllegalArgumentException("Only ordinary noncurrency goods may be offered as royal cargo");
        requireUnchanged();int physical=0;
        for(var stack:slots)if(ordinary(stack) && stack.is(item))physical=Math.addExact(physical,stack.getCount());
        inventory.invalidateCache();int nativeCount=inventory.getCount(level,item);
        int nativeExport=GoodAvailabilityHelper.nbGoodAvailable(townhall,item,level,village,village.getCultureId(),false,false,true,null);
        if(nativeCount<physical || nativeExport<0 || nativeExport>nativeCount)throw new IllegalStateException("Native stock/export evidence is inconsistent; no goods may be removed");
        int construction=construction(item);
        int target=TradeGoodsLoader.getTargetQuantities(village.getCultureId()).getOrDefault(item,0),residents=0;
        for(var building:village.getBuildings())residents=Math.addExact(residents,GoodAvailabilityHelper.getSinkRequiredGoods(building,item,village));
        if(target<0 || residents<0)throw new IllegalStateException("Unknown native reserve data; preserve stock");
        long needs=Math.max((long)nativeCount-nativeExport,(long)construction+target+residents);
        int retained=(int)Math.min(MAXIMUM_RETAINED,Math.max(needs,RoyalShipmentConfig.current().minimumRemainder()));
        return new Availability(BuiltInRegistries.ITEM.getKey(item),physical,nativeCount,nativeExport,construction,target,residents,retained,Math.max(0,physical-retained));
    }

    private int construction(Item item) {
        long needs=0;var pending=village.getPendingProject();
        if(pending!=null)needs+=cost(definition(pending.planSetId(),pending.variant(),pending.level()),item);
        // Keep full costs even before a builder is reserved or while that builder already carries some materials.
        for(var building:village.getBuildings())if(building.isBeingBuilt())needs+=cost(definition(building.getPlanSetId(),building.getVariant(),building.getLevel()),item);
        var queued=village.getControlledQueue();if(queued.size()>256)throw new IllegalStateException("Unsupported controlled queue size; preserve village stock");
        for(var project:queued)needs+=cost(definition(project.planSetId(),project.variant(),project.level()),item);
        return Math.toIntExact(needs);
    }
    private static BuildingPlanSet.LevelDef definition(ResourceLocation set,String variant,int level) {
        var planSet=set==null ? null : ModCultures.getBuildingPlanSet(set);var def=planSet==null || variant==null ? null : planSet.getLevel(variant,level);
        if(def==null || def.requiredResources().values().stream().anyMatch(q->q<0))throw new IllegalStateException("Original work has unknown material requirements; preserve village stock");return def;
    }
    private static int cost(BuildingPlanSet.LevelDef def,Item item) {
        int count=def.requiredResources().getOrDefault(BuiltInRegistries.ITEM.getKey(item),0);
        if(new ItemStack(item).is(AnywoodHelper.LOGS_TAG))count=Math.addExact(count,def.requiredResources().getOrDefault(AnywoodHelper.ANYWOOD_LOG,0));return count;
    }

    public void requireUnchanged() {
        var k=authorize();var data=KingdomSavedData.get(level);var current=PoliticsService.village(level,villageId);
        if(!capital.equals(k.capital()) || player!=null && data.ruler(kingdom).sinceGameTime()!=tenure || current!=village
                || !villageType.equals(current.getVillageTypeId()) || !Objects.equals(localOwner,current.getOwnerUUID())
                || current.getTownhall()!=townhall || townhall.getInventory()!=inventory || !declared.equals(NativeStockBridge.positions(inventory)))
            throw new IllegalStateException("Village stock mandate, type or storage changed; obtain a new quote");
        guardAuxiliary();
        for(var chest:chests) if(!level.isLoaded(chest.position) || level.getBlockEntity(chest.position)!=chest.entity || !level.getBlockState(chest.position).equals(chest.state))
            throw new IllegalStateException("Village chest unloaded, replaced or reconnected; obtain a new quote");
        var actual=readSlots();for(int i=0;i<slots.size();i++)if(!ItemStack.matches(slots.get(i),actual.get(i)))throw new IllegalStateException("Village stock changed; obtain a new quote");
    }

    private KingdomSavedData.Kingdom authorize() {
        if(!level.getServer().isSameThread())throw new IllegalStateException("Village stock access requires the server thread");
        if(player!=null && player.serverLevel()!=level)throw new IllegalArgumentException("Quoted ruler changed dimension");
        var data=KingdomSavedData.get(level);var k=data.kingdom(kingdom);
        if(issued==null) {
            if(k==null || !data.mayDecide(kingdom,player==null ? null : player.getUUID()) || !kingdom.equals(data.owner(villageId))
                    || player!=null && !PoliticsService.ruledKingdom(player).id().equals(kingdom))throw new IllegalArgumentException("Stock requires the current ruler and a member of their active kingdom");
        } else {
            if(k==null || !k.active() || k.fallen() || !kingdom.equals(data.owner(villageId)))throw new IllegalArgumentException("Original shipment village no longer belongs to its active kingdom");
            if(!returning) {
                var ruler=data.ruler(kingdom);
                if(!issued.capital().equals(k.capital()) || !data.mayDecide(kingdom,issued.actor())
                        || (issued.actor()==null ? ruler!=null : ruler==null || !issued.actor().equals(ruler.player()) || issued.rulerSince()!=ruler.sinceGameTime()
                        || PoliticsService.reputation(level,k,issued.actor())<PoliticsService.DECISION_REPUTATION))throw new IllegalArgumentException("Original shipment agency/tenure/reputation no longer permits a new physical delta");
            }
        }
        var v=PoliticsService.village(level,villageId);var type=v==null ? null : ModCultures.getVillageType(v.getVillageTypeId());
        if(type==null || type.loneBuilding())throw new IllegalArgumentException("Recognized original village storage is required");
        if(issued!=null && (!endpoint.type().equals(v.getVillageTypeId()) || !Objects.equals(endpoint.localOwner(),v.getOwnerUUID())
                || !endpoint.center().equals(v.getCenter()) || v.getTownhall()==null || !endpoint.townhall().equals(v.getTownhall().getId().uuid())))throw new IllegalArgumentException("Original shipment village/type/townhall/local-owner identity changed");
        if(player==null && (issued==null || issued.actor()==null && !returning) && (type.playerControlled() || v.getOwnerUUID()!=null))throw new IllegalArgumentException("NPC stock decisions cannot use player-controlled village storage");
        if(!v.isActive() || !level.isLoaded(v.getCenter()))throw new IllegalStateException("Village is unavailable; never force its chunks for stock");return k;
    }
    private void guardAuxiliary() {
        for(var list:List.of(declared.furnaces(),declared.firepits()))for(var position:list)if(!level.isLoaded(position))throw new IllegalStateException("Native auxiliary stock chunk is unavailable; postpone without loading it");
    }
    private Chest resolve(BlockPos position) {
        if(!level.isLoaded(position))throw new IllegalStateException("Village chest chunk is unavailable; postpone without loading it");
        var state=level.getBlockState(position);var entity=level.getBlockEntity(position);
        if(!(state.getBlock() instanceof ChestBlock) || !(entity instanceof ChestBlockEntity chest) || chest.getContainerSize()!=27)throw new IllegalStateException("Missing or unsupported ordinary village chest");
        return new Chest(position.immutable(),chest,state);
    }
    private List<ItemStack> readSlots() {
        var out=new ArrayList<ItemStack>();for(var chest:chests)for(int i=0;i<27;i++) {
            var stack=chest.entity.getItem(i);if(!stack.isEmpty() && stack.getCount()>Math.min(64,stack.getMaxStackSize()))throw new IllegalStateException("Invalid village stock stack; preserve it");out.add(stack.copy());
        }
        return List.copyOf(out);
    }
    private static boolean ordinary(ItemStack stack) {
        var item=stack.getItem();return !stack.isEmpty() && item!=ModItems.DENIER.get() && item!=ModItems.DENIER_ARGENT.get() && item!=ModItems.DENIER_OR.get()
                && ItemStack.isSameItemSameComponents(stack,new ItemStack(item));
    }
}
