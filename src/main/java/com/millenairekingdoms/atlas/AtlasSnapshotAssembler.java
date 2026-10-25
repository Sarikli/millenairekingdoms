package com.millenairekingdoms.atlas;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Stages bounded pages of ONE revision. A renderer receives a complete immutable frame, never mixed borders/markers. */
public final class AtlasSnapshotAssembler {
    public static final class Changed extends IllegalArgumentException {public Changed(){super("Atlas revision changed while paging");}}
    public record Frame(String revision,String dimension,int radius,List<CompoundTag> settlements,List<CompoundTag> kingdoms,List<CompoundTag> wars,CompoundTag pin,long observedTime){
        public Frame{settlements=copy(settlements);kingdoms=copy(kingdoms);wars=copy(wars);pin=pin.copy();}
        private static List<CompoundTag> copy(List<CompoundTag> tags){return tags.stream().map(CompoundTag::copy).toList();}
        @Override public List<CompoundTag> settlements(){return copy(settlements);}
        @Override public List<CompoundTag> kingdoms(){return copy(kingdoms);}
        @Override public List<CompoundTag> wars(){return copy(wars);}
        @Override public CompoundTag pin(){return pin.copy();}
    }
    private String revision,dimension;private int radius,total;private UUID next,last;
    private final Map<UUID,CompoundTag> sites=new LinkedHashMap<>(),kingdoms=new LinkedHashMap<>(),wars=new LinkedHashMap<>();
    private CompoundTag pin=new CompoundTag();
    public Frame accept(CompoundTag page,UUID cursor){
        if(!page.contains("revision",Tag.TAG_STRING) || !page.contains("dimension",Tag.TAG_STRING) || ResourceLocation.tryParse(page.getString("dimension"))==null
                || !page.contains("radius",Tag.TAG_INT) || page.getInt("radius")<64 || page.getInt("radius")>4096 || !page.contains("total",Tag.TAG_INT) || page.getInt("total")<0 || page.getInt("total")>AtlasGeometry.MAX_SITES)throw new IllegalArgumentException("Invalid atlas snapshot header");
        UUID.fromString(page.getString("revision"));
        if(cursor==null){revision=page.getString("revision");dimension=page.getString("dimension");radius=page.getInt("radius");total=page.getInt("total");sites.clear();kingdoms.clear();wars.clear();pin=page.getCompound("historicalPin").copy();next=null;last=null;}
        else if(!cursor.equals(next) || !page.getString("revision").equals(revision) || !page.getString("dimension").equals(dimension) || page.getInt("radius")!=radius || page.getInt("total")!=total)throw new Changed();
        var list=list(page,"settlements");if(list.size()>AtlasViews.PAGE_SIZE || list.isEmpty() && total>0)throw new IllegalArgumentException("Invalid atlas page size");
        for(var raw:list){var site=(CompoundTag)raw;if(!site.hasUUID("id") || !site.contains("x",Tag.TAG_INT) || !site.contains("z",Tag.TAG_INT))throw new IllegalArgumentException("Invalid atlas village");var id=site.getUUID("id");if(last!=null && last.compareTo(id)>=0)throw new IllegalArgumentException("Repeated/out-of-order atlas village");last=id;
            new AtlasGeometry.Site(id,site.hasUUID("kingdom")?site.getUUID("kingdom"):null,site.getInt("x"),site.getInt("z"));
            for(var field:List.of("name","type","typeName","culture","ethos","icon"))if(!site.contains(field,Tag.TAG_STRING) || site.getString(field).length()>256)throw new IllegalArgumentException("Invalid atlas village description");
            if(ResourceLocation.tryParse(site.getString("icon"))==null || sites.putIfAbsent(id,site.copy())!=null)throw new IllegalArgumentException("Invalid atlas icon/identity");
        }
        merge(kingdoms,list(page,"kingdoms"));merge(wars,list(page,"wars"));if(sites.size()>total || kingdoms.size()>AtlasGeometry.MAX_SITES || wars.size()>AtlasGeometry.MAX_SITES)throw new IllegalArgumentException("Atlas frame exceeds declared limits");
        if(page.hasUUID("next")){next=page.getUUID("next");if(!next.equals(last) || sites.size()>=total)throw new IllegalArgumentException("Invalid atlas continuation cursor");return null;}
        next=null;if(sites.size()!=total)throw new IllegalArgumentException("Truncated atlas snapshot");
        return new Frame(revision,dimension,radius,List.copyOf(sites.values()),List.copyOf(kingdoms.values()),List.copyOf(wars.values()),pin,page.getLong("observedTime"));
    }
    private static ListTag list(CompoundTag page,String key){if(!page.contains(key,Tag.TAG_LIST))throw new IllegalArgumentException("Missing atlas list");var list=(ListTag)page.get(key);if(!list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Invalid atlas list type");return list;}
    private static void merge(Map<UUID,CompoundTag> into,ListTag list){if(list.size()>AtlasViews.PAGE_SIZE)throw new IllegalArgumentException("Atlas metadata page too large");for(var raw:list){var tag=(CompoundTag)raw;if(!tag.hasUUID("id"))throw new IllegalArgumentException("Invalid atlas metadata UUID");var old=into.putIfAbsent(tag.getUUID("id"),tag.copy());if(old!=null && !old.equals(tag))throw new Changed();}}
}
