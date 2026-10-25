package com.millenairekingdoms.history;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Immutable facts, including names at the event, never pre-rendered or invented narrative. */
public record HistoryEvent(UUID id, Kind kind, long day, long gameTime, String dimension, String source,
        List<Realm> realms, List<Place> places, UUID actor, String actorName, String reason, String outcome, Map<String,String> facts) {
    public enum Kind { FOUNDING, INCORPORATION, WAR, PEACE, VILLAGE_CONQUEST, CAPITAL_CONQUEST, COLLAPSE,
        RULER_CLAIM, RULER_RELEASE, CONSTRUCTION_ORDER, CONSTRUCTION_COMPLETE, INVESTMENT, SHIPMENT }
    public record Realm(UUID id, String name, String culture, String ethos) {
        public Realm { if (id == null) throw new IllegalArgumentException("Missing historical realm"); text(name,64); text(culture,128); text(ethos,32); }
    }
    public record Place(UUID id, String name, boolean located, int x, int z) {
        public Place { if (id == null) throw new IllegalArgumentException("Missing historical village"); text(name,128);
            if (Math.abs((long)x)>30_000_000 || Math.abs((long)z)>30_000_000 || !located && (x!=0 || z!=0)) throw new IllegalArgumentException("Invalid historical location"); }
    }
    public HistoryEvent {
        if (id==null || kind==null || day<0 || gameTime<0 || ResourceLocation.tryParse(dimension)==null) throw new IllegalArgumentException("Invalid historical identity/time");
        text(source,192); text(actorName,64); text(reason,256); text(outcome,64);
        realms=List.copyOf(realms); places=List.copyOf(places); facts=Map.copyOf(facts);
        if (realms.isEmpty() || realms.size()>2 || places.size()>3 || facts.size()>12 || actor==null && !actorName.isEmpty()
                || realms.stream().map(Realm::id).distinct().count()!=realms.size() || places.stream().map(Place::id).distinct().count()!=places.size()) throw new IllegalArgumentException("Invalid historical participants");
        facts.forEach((key,value)->{text(key,32);text(value,256);});
        if (!id.equals(identity(dimension,kind,source))) throw new IllegalArgumentException("Historical ID does not match original operation");
    }
    public static UUID identity(String dimension, Kind kind, String source) {
        return UUID.nameUUIDFromBytes((dimension+"|"+kind+"|"+source).getBytes(StandardCharsets.UTF_8));
    }
    public static HistoryEvent create(Kind kind,long day,long time,String dimension,String source,List<Realm> realms,List<Place> places,UUID actor,String actorName,String reason,String outcome,Map<String,String> facts) {
        return new HistoryEvent(identity(dimension,kind,source),kind,day,time,dimension,source,realms,places,actor,actorName,reason,outcome,facts);
    }
    private static void text(String text,int max) { if(text==null || text.length()>max || text.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid historical text"); }
    public CompoundTag save() {
        var t=new CompoundTag();t.putInt("format",1);t.putUUID("id",id);t.putString("kind",kind.name());t.putLong("day",day);t.putLong("time",gameTime);t.putString("dimension",dimension);t.putString("source",source);
        var rs=new ListTag(); for(var r:realms){var e=new CompoundTag();e.putUUID("id",r.id);e.putString("name",r.name);e.putString("culture",r.culture);e.putString("ethos",r.ethos);rs.add(e);} t.put("realms",rs);
        var ps=new ListTag();for(var p:places){var e=new CompoundTag();e.putUUID("id",p.id);e.putString("name",p.name);e.putBoolean("located",p.located);e.putInt("x",p.x);e.putInt("z",p.z);ps.add(e);}t.put("places",ps);
        if(actor!=null)t.putUUID("actor",actor);t.putString("actorName",actorName);t.putString("reason",reason);t.putString("outcome",outcome);
        var fs=new CompoundTag();facts.forEach(fs::putString);t.put("facts",fs);return t;
    }
    public static HistoryEvent load(CompoundTag t) {
        var keys=new java.util.HashSet<>(Set.of("format","id","kind","day","time","dimension","source","realms","places","actorName","reason","outcome","facts"));if(t.contains("actor"))keys.add("actor");HistoryFiles.keys(t,keys);
        require(t,"format",Tag.TAG_INT);if(t.getInt("format")!=1)throw new IllegalArgumentException("Unsupported historical event format");
        for(var k:List.of("day","time"))require(t,k,Tag.TAG_LONG);
        for(var k:List.of("kind","dimension","source","actorName","reason","outcome"))require(t,k,Tag.TAG_STRING);
        if(!t.hasUUID("id") || t.contains("actor") && !t.hasUUID("actor"))throw new IllegalArgumentException("Invalid historical UUID");
        var realms=new ArrayList<Realm>();for(var raw:list(t,"realms")){var r=(CompoundTag)raw;HistoryFiles.keys(r,Set.of("id","name","culture","ethos"));if(!r.hasUUID("id"))throw new IllegalArgumentException("Invalid realm UUID");for(var k:List.of("name","culture","ethos"))require(r,k,Tag.TAG_STRING);realms.add(new Realm(r.getUUID("id"),r.getString("name"),r.getString("culture"),r.getString("ethos")));}
        var places=new ArrayList<Place>();for(var raw:list(t,"places")){var p=(CompoundTag)raw;HistoryFiles.keys(p,Set.of("id","name","located","x","z"));if(!p.hasUUID("id"))throw new IllegalArgumentException("Invalid village UUID");require(p,"name",Tag.TAG_STRING);require(p,"located",Tag.TAG_BYTE);require(p,"x",Tag.TAG_INT);require(p,"z",Tag.TAG_INT);if(p.getByte("located")<0 || p.getByte("located")>1)throw new IllegalArgumentException("Invalid location flag");places.add(new Place(p.getUUID("id"),p.getString("name"),p.getBoolean("located"),p.getInt("x"),p.getInt("z")));}
        require(t,"facts",Tag.TAG_COMPOUND);var facts=new LinkedHashMap<String,String>();var fs=t.getCompound("facts");for(var k:fs.getAllKeys()){require(fs,k,Tag.TAG_STRING);facts.put(k,fs.getString(k));}
        return new HistoryEvent(t.getUUID("id"),Kind.valueOf(t.getString("kind")),t.getLong("day"),t.getLong("time"),t.getString("dimension"),t.getString("source"),realms,places,t.hasUUID("actor")?t.getUUID("actor"):null,t.getString("actorName"),t.getString("reason"),t.getString("outcome"),facts);
    }
    private static void require(CompoundTag t,String key,int type){if(!t.contains(key,type))throw new IllegalArgumentException("Missing/wrong historical field: "+key);}
    private static ListTag list(CompoundTag t,String key){require(t,key,Tag.TAG_LIST);var list=(ListTag)t.get(key);if(!list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Wrong historical list type");return list;}
}
