package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.history.HistoryFiles;
import com.millenairekingdoms.history.HistoryHooks;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** One bounded conditional intention per kingdom. Not a treasury snapshot, fee authorization or completed order. */
public final class RoyalStrategyStore {
    public enum Status { IDLE, CONDITIONAL, ISSUING, LINKED, INVALIDATED }
    public record Plan(UUID kingdom,UUID capital,ResourceLocation capitalType,String dimension,UUID id,UUID target,
                       ResourceLocation selection,boolean logistics,long evaluatedDay,long nextDay,long metadataObservedTick,
                       String fingerprint,Status status,String reason){
        public Plan{
            Objects.requireNonNull(kingdom);Objects.requireNonNull(capital);Objects.requireNonNull(capitalType);Objects.requireNonNull(status);
            if(ResourceLocation.tryParse(dimension)==null || evaluatedDay<0 || nextDay<=evaluatedDay || metadataObservedTick<0 || reason==null || reason.length()>512)throw new IllegalArgumentException("Invalid strategic evaluation");
            UUID.fromString(fingerprint);
            if((id==null)!=(target==null) || target!=null && !logistics && selection==null || target==null && (selection!=null || logistics) || (status==Status.CONDITIONAL || status==Status.ISSUING || status==Status.LINKED) && id==null)throw new IllegalArgumentException("Invalid conditional investment identity");
        }
        public Plan status(Status next,String why){return new Plan(kingdom,capital,capitalType,dimension,id,target,selection,logistics,evaluatedDay,nextDay,metadataObservedTick,fingerprint,next,bound(why));}
    }
    private final Path directory;
    private final String dimension;
    public RoyalStrategyStore(ServerLevel level){this(HistoryHooks.directory(level).resolve("royal-strategy"),level.dimension().location().toString());}
    public RoyalStrategyStore(Path directory,String dimension){if(ResourceLocation.tryParse(dimension)==null)throw new IllegalArgumentException("Invalid strategy dimension");this.directory=directory;this.dimension=dimension;}
    public Path file(UUID kingdom){return directory.resolve(kingdom+".dat");}
    public Plan read(UUID kingdom){
        var file=file(kingdom);if(!Files.exists(file))return null;var t=HistoryFiles.read(file);
        var required=Set.of("format","kingdom","capital","capitalType","dimension","logistics","evaluatedDay","nextDay","metadataObservedTick","fingerprint","status","reason");
        var allowed=new HashSet<>(required);allowed.addAll(Set.of("id","target","selection"));
        if(!allowed.containsAll(t.getAllKeys()) || !t.getAllKeys().containsAll(required) || !t.contains("format",Tag.TAG_INT) || t.getInt("format")!=1)throw new IllegalArgumentException("Unsupported strategic data; original preserved");
        for(var field:List.of("capitalType","dimension","fingerprint","status","reason"))if(!t.contains(field,Tag.TAG_STRING))throw new IllegalArgumentException("Invalid strategic text field");
        for(var field:List.of("evaluatedDay","nextDay","metadataObservedTick"))if(!t.contains(field,Tag.TAG_LONG))throw new IllegalArgumentException("Invalid strategic clock field");
        if(!t.contains("logistics",Tag.TAG_BYTE) || t.contains("selection") && !t.contains("selection",Tag.TAG_STRING))throw new IllegalArgumentException("Invalid strategic selection");
        var plan=new Plan(uuid(t,"kingdom"),uuid(t,"capital"),ResourceLocation.parse(t.getString("capitalType")),t.getString("dimension"),optional(t,"id"),optional(t,"target"),t.contains("selection")?ResourceLocation.parse(t.getString("selection")):null,t.getBoolean("logistics"),t.getLong("evaluatedDay"),t.getLong("nextDay"),t.getLong("metadataObservedTick"),t.getString("fingerprint"),Status.valueOf(t.getString("status")),t.getString("reason"));
        if(!plan.kingdom().equals(kingdom) || !plan.dimension().equals(dimension))throw new IllegalArgumentException("Strategic identity/dimension mismatch");return plan;
    }
    public void write(Plan expected,Plan next){
        if(!next.dimension().equals(dimension) || !Objects.equals(read(next.kingdom()),expected))throw new IllegalStateException("Strategic intention changed; re-evaluate");
        var t=new CompoundTag();t.putInt("format",1);t.putUUID("kingdom",next.kingdom());t.putUUID("capital",next.capital());t.putString("capitalType",next.capitalType().toString());t.putString("dimension",dimension);
        if(next.id()!=null)t.putUUID("id",next.id());if(next.target()!=null)t.putUUID("target",next.target());if(next.selection()!=null)t.putString("selection",next.selection().toString());
        t.putBoolean("logistics",next.logistics());t.putLong("evaluatedDay",next.evaluatedDay());t.putLong("nextDay",next.nextDay());t.putLong("metadataObservedTick",next.metadataObservedTick());t.putString("fingerprint",next.fingerprint());t.putString("status",next.status().name());t.putString("reason",next.reason());HistoryFiles.write(file(next.kingdom()),t);
    }
    static String bound(String text){return text.substring(0,Math.min(512,text.length()));}
    private static UUID uuid(CompoundTag tag,String key){if(!tag.hasUUID(key))throw new IllegalArgumentException("Invalid strategic UUID "+key);return tag.getUUID(key);}
    private static UUID optional(CompoundTag tag,String key){return tag.contains(key)?uuid(tag,key):null;}
}
