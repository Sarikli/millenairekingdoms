package com.millenairekingdoms.construction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import org.millenaire.building.BuildingInstance;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Acknowledge original Millenaire metadata through its own serializer/NeoForge worker, then inspect the actual file.
 * No chunk load, queue insertion, building creation, completion claim or direct rewrite of the dependency's format. */
@org.jetbrains.annotations.ApiStatus.Internal
public class RoyalVillagePersistence {

    public void pending(ServerLevel level,UUID villageId,Village.PendingProject expected) {
        thread(level); var village=village(level,villageId);
        if(expected==null || !expected.equals(village.getPendingProject())) throw new IllegalStateException("Live pending project does not match royal acceptance intent");
        preflight(level); flush(level);
        var saved=readVillage(level,villageId);
        if(!saved.contains("pending_project",Tag.TAG_COMPOUND) || !expectedPending(expected).equals(saved.getCompound("pending_project"))
                || !expected.equals(village.getPendingProject())) {
            throw new IllegalStateException("Saved/live pending project differs from royal acceptance intent; do not commit patronage");
        }
    }

    public void building(ServerLevel level,UUID villageId,BuildingInstance expected) {
        thread(level); var village=village(level,villageId);
        if(village.getBuilding(expected.getId())!=expected) throw new IllegalStateException("Construction building is not the current village record");
        preflight(level);
        if(expected.getStatus()==BuildingInstance.Status.COMPLETE && (expected.getLevel()>0
                || expected.getConstructionTask()!=null && expected.getConstructionTask().isComplete() && expected.getConstructionTask().totalSteps()>0)) {
            RoyalConstructionPersistence.completed(level,village,expected);
        }
        flush(level);
        var saved=readVillage(level,villageId); CompoundTag found=null;
        for(Tag raw:saved.getList("buildings",Tag.TAG_COMPOUND)) {
            var tag=(CompoundTag)raw;
            if(tag.hasUUID("id") && tag.getUUID("id").equals(expected.getId().uuid())) {
                if(found!=null) throw new IllegalStateException("Duplicate saved construction building identity"); found=tag;
            }
        }
        if(found==null || !found.getString("plan").equals(expected.getPlanId().toString()) || !found.getString("status").equals(expected.getStatus().name())
                || found.getInt("level")!=expected.getLevel()
                || !java.util.Arrays.equals(found.getIntArray("origin"),new int[]{expected.getOrigin().getX(),expected.getOrigin().getY(),expected.getOrigin().getZ()})
                || !found.getString("rotation").equals(expected.getRotation().name())
                || (expected.getPlanSetId()==null ? found.contains("plan_set") : !found.getString("plan_set").equals(expected.getPlanSetId().toString()))
                || (expected.getVariant()==null ? found.contains("variant") : !found.getString("variant").equals(expected.getVariant()))
                || (expected.getConstructionTask()==null ? found.contains("construction_task") : !found.getCompound("construction_task").equals(expected.getConstructionTask().save()))) {
            throw new IllegalStateException("Saved construction building differs from current engine record; do not commit patronage or progress");
        }
    }

    /** Cancellation needs durable removal, not just a null in memory. Never remove a project in this helper. */
    public void absentPending(ServerLevel level,UUID villageId) {
        thread(level); var village=village(level,villageId);
        if(village.getPendingProject()!=null) throw new IllegalStateException("A live natural project must not be erased by royal cancellation");
        preflight(level); flush(level);
        if(readVillage(level,villageId).contains("pending_project") || village.getPendingProject()!=null) {
            throw new IllegalStateException("Pending-project removal is not durable; retain cancellation intent");
        }
    }

    protected void flush(ServerLevel level) {
        VillageSavedData.get(level).setDirty(); level.getDataStorage().save();
        // SavedData.save schedules asynchronous work and can swallow IOException. Wait, then require file evidence.
        IOUtilities.waitUntilIOWorkerComplete();
    }

    private static CompoundTag expectedPending(Village.PendingProject expected) {
        var tag=new CompoundTag(); tag.putString("plan_set",expected.planSetId().toString()); tag.putString("variant",expected.variant());
        tag.putInt("level",expected.level()); tag.putBoolean("is_upgrade",expected.isUpgrade());
        if(expected.buildingId()!=null) tag.putUUID("building_id",expected.buildingId().uuid());
        if(expected.plannedLocation()!=null) {
            var location=expected.plannedLocation(); tag.putInt("planned_x",location.position().getX()); tag.putInt("planned_y",location.position().getY());
            tag.putInt("planned_z",location.position().getZ()); tag.putInt("planned_rotation",location.rotation().ordinal());
        }
        return tag;
    }
    private static Village village(ServerLevel level,UUID id) {
        var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(id));
        if(village==null) throw new IllegalStateException("Construction village is missing; retain intent for recovery"); return village;
    }
    private static CompoundTag readVillage(ServerLevel level,UUID id) {
        return NativeVillageReadback.village(file(level),id);
    }
    private static void preflight(ServerLevel level) { if(Files.exists(file(level))) NativeVillageReadback.validate(file(level)); }
    private static Path file(ServerLevel level) {
        return DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data/millenaire_villages.dat");
    }
    private static void thread(ServerLevel level) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Construction persistence requires the server thread");
    }
}
