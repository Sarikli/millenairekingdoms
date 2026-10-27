package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;

/** Conservative capability estimate from canonical native records/equipment. No claim that physical entities fought. */
public record MilitarySnapshot(UUID village,UUID owner,String villageType,long observedTick,String provenance,
                               List<Unit> raiders,List<Unit> defenders,int militaryBuildings){
    public record Unit(UUID id,ResourceLocation type,int nativeStrength){public Unit{Objects.requireNonNull(id);Objects.requireNonNull(type);if(nativeStrength<=0)throw new IllegalArgumentException("Unverified military strength");}}
    public MilitarySnapshot{Objects.requireNonNull(village);if(ResourceLocation.tryParse(villageType)==null || observedTick<0 || !Set.of("NATIVE_REGISTERED_RECORDS","NATIVE_LOADED_RECORDS").contains(provenance) || militaryBuildings<0)throw new IllegalArgumentException("Invalid military provenance");raiders=List.copyOf(raiders);defenders=List.copyOf(defenders);if(raiders.stream().map(Unit::id).distinct().count()!=raiders.size() || defenders.stream().map(Unit::id).distinct().count()!=defenders.size())throw new IllegalArgumentException("Duplicate native military unit");if(raiders.size()>1024 || defenders.size()>1024)throw new IllegalArgumentException("Military snapshot exceeds supported native record budget");}
    public long attack(){return raiders.stream().mapToLong(Unit::nativeStrength).reduce(0,Math::addExact);}
    public long defense(){return defenders.stream().mapToLong(Unit::nativeStrength).reduce(0,Math::addExact);}
    public static MilitarySnapshot capture(ServerLevel level,Village village){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Native military snapshots require server thread");
        var owner=KingdomSavedData.get(level).owner(village.getId().uuid());if(village.getVillagerRecords().size()>1024)throw new IllegalArgumentException("Registered native military metadata unavailable");
        var attack=new ArrayList<Unit>();var defense=new ArrayList<Unit>();
        for(var r:village.getVillagerRecords().values()){
            if(r.isKilled() || r.isAwayRaiding() || r.isRaidingVillage() || r.isAwayHired())continue;
            var type=ModCultures.getVillagerType(r.getVillagerTypeId());if(type==null)throw new IllegalArgumentException("Unknown native villager type; military capability deferred");
            if(type.isChild())continue;int strength=r.getMilitaryStrength();if(strength<=0)continue;var unit=new Unit(r.getUuid(),type.id(),strength);
            if(type.isRaider())attack.add(unit);if(type.isHelpInAttacks())defense.add(unit);
        }
        attack.sort(Comparator.comparing(Unit::id));defense.sort(Comparator.comparing(Unit::id));
        int infrastructure=(int)village.getBuildings().stream().filter(b->b.getStatus()==org.millenaire.building.BuildingInstance.Status.COMPLETE && RoyalInvestmentPolicy.role(b.getPlanSetId())==RoyalInvestmentPolicy.Role.MILITARY).count();
        return new MilitarySnapshot(village.getId().uuid(),owner,village.getVillageTypeId().toString(),Math.max(0,level.getGameTime()),village.isActive() && level.isLoaded(village.getCenter())?"NATIVE_LOADED_RECORDS":"NATIVE_REGISTERED_RECORDS",attack,defense,infrastructure);
    }
    public CompoundTag save(){var t=new CompoundTag();t.putUUID("village",village);if(owner!=null)t.putUUID("owner",owner);t.putString("type",villageType);t.putLong("tick",observedTick);t.putString("provenance",provenance);t.putInt("militaryBuildings",militaryBuildings);t.put("raiders",units(raiders));t.put("defenders",units(defenders));return t;}
    public static MilitarySnapshot read(CompoundTag t){var keys=new HashSet<>(Set.of("village","type","tick","provenance","militaryBuildings","raiders","defenders"));if(t.contains("owner"))keys.add("owner");com.millenairekingdoms.history.HistoryFiles.keys(t,keys);
        if(!t.hasUUID("village") || t.contains("owner") && !t.hasUUID("owner") || !t.contains("type",Tag.TAG_STRING) || !t.contains("tick",Tag.TAG_LONG) || !t.contains("provenance",Tag.TAG_STRING) || !t.contains("militaryBuildings",Tag.TAG_INT))throw new IllegalArgumentException("Invalid military snapshot types");
        return new MilitarySnapshot(t.getUUID("village"),t.contains("owner")?t.getUUID("owner"):null,t.getString("type"),t.getLong("tick"),t.getString("provenance"),units(t,"raiders"),units(t,"defenders"),t.getInt("militaryBuildings"));}
    private static ListTag units(List<Unit> units){var out=new ListTag();for(var u:units){var t=new CompoundTag();t.putUUID("id",u.id());t.putString("type",u.type().toString());t.putInt("strength",u.nativeStrength());out.add(t);}return out;}
    private static List<Unit> units(CompoundTag t,String key){if(!t.contains(key,Tag.TAG_LIST))throw new IllegalArgumentException("Missing military unit list");var list=(ListTag)t.get(key);if(list.size()>1024 || !list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Invalid military unit list");var out=new ArrayList<Unit>();var ids=new HashSet<UUID>();for(var raw:list){var u=(CompoundTag)raw;com.millenairekingdoms.history.HistoryFiles.keys(u,Set.of("id","type","strength"));if(!u.hasUUID("id") || !u.contains("type",Tag.TAG_STRING) || !u.contains("strength",Tag.TAG_INT) || !ids.add(u.getUUID("id")))throw new IllegalArgumentException("Invalid/duplicate military unit");out.add(new Unit(u.getUUID("id"),ResourceLocation.parse(u.getString("type")),u.getInt("strength")));}return out;}
}
