package com.millenairekingdoms.construction;

import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.millenaire.block.mock.MockBlock;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.ConstructionTask;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.world.BuildingPlacer;

/** Before acknowledging completed task metadata, acknowledge its loaded physical blocks/storage and actual resident
 * inventories. Original serializers still own blocks/entities/tasks; this helper never places, creates or advances them. */
final class RoyalConstructionPersistence {
    static final class Unloaded extends IllegalStateException { Unloaded(String reason) { super(reason); } }
    static final class MissingStructure extends IllegalStateException { MissingStructure(String reason) { super(reason); } }
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES=PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY,BlockState.CODEC,PalettedContainer.Strategy.SECTION_STATES,Blocks.AIR.defaultBlockState());
    private record EntityApi(Field manager,Field storage,Field regions,Method store) {}
    private static final class Pinned { static final EntityApi API=entityApi(); }
    private RoyalConstructionPersistence() {}

    static void completed(ServerLevel level,Village village,BuildingInstance building) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Completed construction persistence requires the server thread");
        var plan=ModCultures.getBuildingPlan(building.getPlanId());
        if(plan==null) throw new IllegalStateException("Completed building plan missing; retain order");
        var positions=new java.util.LinkedHashSet<BlockPos>(); var expected=new LinkedHashMap<BlockPos,BlockState>();
        var rotation=new StructurePlaceSettings().setRotation(building.getRotation());
        for(var step:BuildingPlacer.compilePreviewSteps(level,plan)) {
            var state=step.blockState();
            if(state.getBlock() instanceof MockBlock || state.is(net.minecraft.tags.BlockTags.DIRT) || state.isAir()) continue;
            var position=building.getOrigin().offset(StructureTemplate.calculateRelativePosition(rotation,step.relativePos())).immutable();
            loaded(level,position); expected.put(position,state); positions.add(position);
        }
        if(expected.isEmpty()) throw new MissingStructure("Completed original template has no verifiable physical blocks");
        long matching=expected.entrySet().stream().filter(e->sameBlock(level.getBlockState(e.getKey()),e.getValue())).count();
        // The dependency's markers/paint/plants can differ; the existing natural-capital structural calibration is 90%.
        if(matching*10<expected.size()*9L) throw new MissingStructure("Original COMPLETE task has missing physical structure: "+matching+"/"+expected.size()+"; preserve order and restore/review the saved world");
        var task=building.getConstructionTask();
        if(task!=null) {
            if(task.totalSteps()>1_000_000) throw new IllegalStateException("Construction task exceeds bounded persistence budget");
            var detached=task.save(); detached.putInt("cursor",0);
            // Read-only detached extent; the live original cursor/task is never reset or advanced.
            var extent=ConstructionTask.load(detached,BuiltInRegistries.BLOCK.asLookup());
            for(var position:extent.upcomingAbsolutePositions(building.getOrigin(),extent.totalSteps())) {
                // Native terrain tasks may attempt cells below a flat world's minimum height; those are not world data.
                if(!level.isOutsideBuildHeight(position)) { loaded(level,position); positions.add(position.immutable()); }
            }
        }
        var inventory=village.getTownhall()==null ? null : village.getTownhall().getInventory();
        if(inventory==null) throw new Unloaded("Original material storage is unavailable; defer completion acknowledgement");
        for(var position:inventory.getChestPositions()) {
            loaded(level,position); positions.add(position.immutable()); var state=level.getBlockState(position);
            if(state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE) {
                var half=position.relative(ChestBlock.getConnectedDirection(state)); loaded(level,half); positions.add(half.immutable());
            }
        }
        var states=new LinkedHashMap<BlockPos,BlockState>(); var blockEntities=new HashMap<BlockPos,CompoundTag>();
        for(var position:positions) {
            states.put(position,level.getBlockState(position)); var entity=level.getBlockEntity(position);
            if(entity!=null) blockEntities.put(position,entity.saveWithFullMetadata(level.registryAccess()));
        }
        new RoyalTreasuryPersistence().flush(level,new ArrayList<>(positions));
        verifyBlocks(level,states,blockEntities);
        residents(level,village);
        // Original record inventories and entity inventories must describe the same consumed supplies before task save.
        village.syncRecords(level);
        for(var item:states.entrySet()) {
            loaded(level,item.getKey());
            if(!item.getValue().equals(level.getBlockState(item.getKey()))) throw new IllegalStateException("Building blocks changed during completion persistence: "+item.getKey());
            var entity=level.getBlockEntity(item.getKey());
            if(entity==null ? blockEntities.containsKey(item.getKey()) : !entity.saveWithFullMetadata(level.registryAccess()).equals(blockEntities.get(item.getKey()))) {
                throw new IllegalStateException("Building/material block entity changed during completion persistence: "+item.getKey());
            }
        }
    }

    private static boolean sameBlock(BlockState actual,BlockState expected) {
        return actual.getBlock()==expected.getBlock() || actual.getBlock() instanceof org.millenaire.block.IPaintedBlock && expected.getBlock() instanceof org.millenaire.block.IPaintedBlock;
    }
    private static void loaded(ServerLevel level,BlockPos position) {
        if(!level.isLoaded(position)) throw new Unloaded("Construction/material chunk unloaded; defer completion without forcing: "+position);
    }
    private static void verifyBlocks(ServerLevel level,Map<BlockPos,BlockState> expected,Map<BlockPos,CompoundTag> entities) {
        var chunks=new HashMap<ChunkPos,CompoundTag>(); var sections=new HashMap<String,PalettedContainer<BlockState>>();
        for(var item:expected.entrySet()) {
            var position=item.getKey(); var cp=new ChunkPos(position);
            var chunk=chunks.computeIfAbsent(cp,key->level.getChunkSource().chunkMap.read(key).join().orElseThrow(()->new IllegalStateException("Completed building chunk was not written: "+key)));
            int y=Math.floorDiv(position.getY(),16); String key=cp.toLong()+"/"+y;
            if(!sections.containsKey(key)) {
                PalettedContainer<BlockState> palette=null; boolean found=false;
                for(Tag raw:chunk.getList("sections",Tag.TAG_COMPOUND)) {
                    var section=(CompoundTag)raw;
                    if(section.getByte("Y")==y) {
                        if(found) throw new IllegalStateException("Duplicate completed-building saved section"); found=true;
                        if(section.contains("block_states",Tag.TAG_COMPOUND)) palette=STATES.parse(NbtOps.INSTANCE,section.getCompound("block_states")).getOrThrow();
                    }
                }
                sections.put(key,palette);
            }
            var palette=sections.get(key); var state=palette==null ? Blocks.AIR.defaultBlockState() : palette.get(position.getX()&15,position.getY()&15,position.getZ()&15);
            if(!state.equals(item.getValue())) throw new IllegalStateException("Persisted completed building block differs at "+position);
            CompoundTag saved=null;
            for(Tag raw:chunk.getList("block_entities",Tag.TAG_COMPOUND)) {
                var tag=(CompoundTag)raw;
                if(BlockEntity.getPosFromTag(tag).equals(position)) { if(saved!=null) throw new IllegalStateException("Duplicate completed-building block entity"); saved=tag.copy(); saved.remove("keepPacked"); }
            }
            if(!java.util.Objects.equals(saved,entities.get(position))) throw new IllegalStateException("Persisted completed building/material block entity differs at "+position);
        }
    }

    @SuppressWarnings("unchecked")
    private static void residents(ServerLevel level,Village village) {
        try {
            var api=Pinned.API; var manager=(PersistentEntitySectionManager<Entity>)api.manager.get(level);
            if(!(api.storage.get(manager) instanceof EntityStorage storage)) throw new IllegalStateException("Audited entity storage unavailable");
            var regions=(SimpleRegionStorage)api.regions.get(storage);
            var roots=new LinkedHashMap<UUID,Entity>();
            for(UUID id:village.getVillagerUuids()) {
                var resident=level.getEntity(id);
                if(resident!=null && resident.isAlive()) { var root=resident.getRootVehicle(); roots.put(root.getUUID(),root); }
            }
            var snapshots=new LinkedHashMap<UUID,CompoundTag>(); var chunks=new LinkedHashMap<ChunkPos,Boolean>();
            for(var root:roots.values()) {
                loaded(level,root.blockPosition()); var cp=root.chunkPosition();
                if(!manager.areEntitiesLoaded(cp.toLong())) throw new Unloaded("Resident entity section not fully loaded; defer without replacing its saved entities: "+cp);
                var snapshot=new CompoundTag(); if(!root.save(snapshot)) throw new IllegalStateException("Original resident/root entity cannot be saved");
                snapshots.put(root.getUUID(),snapshot); chunks.put(cp,true);
            }
            for(var cp:chunks.keySet()) if(!(boolean)api.store.invoke(manager,cp.toLong(),(Consumer<Entity>)entity->{})) throw new Unloaded("Resident entity section is pending: "+cp);
            storage.flush(true);
            for(var cp:chunks.keySet()) {
                var saved=regions.read(cp).join().orElseThrow(()->new IllegalStateException("Resident entity chunk missing after completion flush"));
                for(var root:roots.values()) if(root.chunkPosition().equals(cp)) {
                    CompoundTag found=null;
                    for(Tag raw:saved.getList("Entities",Tag.TAG_COMPOUND)) {
                        var tag=(CompoundTag)raw;
                        if(tag.hasUUID("UUID") && tag.getUUID("UUID").equals(root.getUUID())) { if(found!=null) throw new IllegalStateException("Duplicate saved resident root UUID"); found=tag; }
                    }
                    var current=new CompoundTag();
                    if(!snapshots.get(root.getUUID()).equals(found) || !root.save(current) || !current.equals(snapshots.get(root.getUUID()))) {
                        throw new IllegalStateException("Actual saved/live resident inventory/state differs; do not acknowledge completion: "+root.getUUID());
                    }
                }
            }
        } catch(InvocationTargetException e) { throw new IllegalStateException("Original entity persistence failed; retain completion",e.getCause()); }
        catch(ReflectiveOperationException e) { throw new IllegalStateException("Audited entity persistence unavailable; retain completion",e); }
    }
    private static EntityApi entityApi() {
        try {
            var manager=ServerLevel.class.getDeclaredField("entityManager"); manager.setAccessible(true);
            var storage=PersistentEntitySectionManager.class.getDeclaredField("permanentStorage"); storage.setAccessible(true);
            var regions=EntityStorage.class.getDeclaredField("simpleRegionStorage"); regions.setAccessible(true);
            var store=PersistentEntitySectionManager.class.getDeclaredMethod("storeChunkSections",long.class,Consumer.class); store.setAccessible(true);
            return new EntityApi(manager,storage,regions,store);
        } catch(ReflectiveOperationException e) { throw new IllegalStateException("Pinned 1.21.1 entity persistence API is unavailable; no completed royal acknowledgement",e); }
    }
}
