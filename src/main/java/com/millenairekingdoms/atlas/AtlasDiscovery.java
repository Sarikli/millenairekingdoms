package com.millenairekingdoms.atlas;

import com.millenairekingdoms.history.HistoryFiles;
import com.millenairekingdoms.history.HistoryHooks;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import org.millenaire.village.VillageSavedData;

/** Separate per-player, per-dimension remembered settlement identities. No coordinates are accepted from the client. */
public final class AtlasDiscovery {
    public static final int LIMIT=16384;
    private AtlasDiscovery(){}
    public static Set<UUID> known(ServerPlayer player){
        var level=player.serverLevel();var file=HistoryHooks.directory(level).resolve("discovery").resolve(player.getUUID()+".dat");var known=new HashSet<UUID>();
        if(Files.exists(file)){
            var tag=HistoryFiles.read(file);HistoryFiles.keys(tag,Set.of("format","villages"));if(!tag.contains("format",Tag.TAG_INT) || tag.getInt("format")!=1 || !tag.contains("villages",Tag.TAG_LIST))throw new IllegalStateException("Unsupported discovery history; bytes preserved");
            var list=(ListTag)tag.get("villages");if(list.size()>LIMIT || !list.isEmpty() && list.getElementType()!=Tag.TAG_INT_ARRAY)throw new IllegalStateException("Invalid discovery history");
            for(var raw:list){var array=((IntArrayTag)raw).getAsIntArray();if(array.length!=4)throw new IllegalStateException("Invalid discovered village UUID");var id=net.minecraft.core.UUIDUtil.uuidFromIntArray(array);if(!known.add(id))throw new IllegalStateException("Duplicate discovered village");}
        }
        var before=Set.copyOf(known);var data=KingdomSavedData.get(level);var ruled=data.ruledBy(player.getUUID());if(ruled!=null)known.addAll(data.members(ruled));
        int radius=AtlasConfig.DISCOVERY.get();boolean all=AtlasConfig.PUBLIC.get();
        for(var village:VillageSavedData.get(level).getVillageManager().getAllVillages())if(all || player.getUUID().equals(village.getOwnerUUID()) || level.isLoaded(village.getCenter()) && Math.hypot(player.getX()-village.getCenter().getX(),player.getZ()-village.getCenter().getZ())<=radius)known.add(village.getId().uuid());
        if(known.size()>LIMIT)throw new IllegalStateException("Discovery limit exceeded; preserve archive for explicit migration");
        if(!known.equals(before)){var tag=new CompoundTag();tag.putInt("format",1);var list=new ListTag();known.stream().sorted().forEach(id->list.add(new IntArrayTag(net.minecraft.core.UUIDUtil.uuidToIntArray(id))));tag.put("villages",list);HistoryFiles.write(file,tag);}
        return Set.copyOf(known);
    }
}
