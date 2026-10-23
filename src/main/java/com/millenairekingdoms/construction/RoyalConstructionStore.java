package com.millenairekingdoms.construction;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Own atomic order intent/index, independent of political format 6 and Millenaire's building save.
 * States describe order coordination, not payment or proof of a finished building. Only the domain service may advance them. */
public final class RoyalConstructionStore {
    private static final int FORMAT=1, MAX_ORDERS=10_000;
    private static final long MAX_BYTES=16L*1024*1024;
    public enum Stage { REQUESTED, PAID, QUEUING, QUEUED, IN_PROGRESS, COMPLETED, CANCELLING, CANCELLED, FAILED, RECOVERY_REQUIRED }
    public enum Blocked { NONE, NATURAL_PROJECT, UNLOADED, MATERIALS, NO_SPACE, AUTHORITY, MEMBERSHIP, MISSING_PLAN, AMBIGUOUS_ENGINE_STATE }

    public record Intent(UUID id,String dimension,UUID kingdom,UUID capital,UUID village,UUID actor,long rulerSince,
                         ResourceLocation villageType,ResourceLocation planSet,ResourceLocation plan,String variant,
                         long fee,long issuedGameTime,Set<UUID> originalBuildings) {
        public Intent {
            Objects.requireNonNull(id); Objects.requireNonNull(kingdom); Objects.requireNonNull(capital); Objects.requireNonNull(village);
            Objects.requireNonNull(villageType); Objects.requireNonNull(planSet); Objects.requireNonNull(plan); Objects.requireNonNull(variant);
            if(dimension==null || ResourceLocation.tryParse(dimension)==null || variant.isBlank() || variant.length()>128 || fee<=0
                    || issuedGameTime<0 || rulerSince<0 || (actor==null && rulerSince!=0)) throw new IllegalArgumentException("Invalid royal construction intent");
            dimension=ResourceLocation.parse(dimension).toString();
            originalBuildings=Set.copyOf(originalBuildings);
            if(originalBuildings.size()>4096) throw new IllegalArgumentException("Too many original building identities");
        }
    }
    public record Entry(Intent intent,Stage stage,Blocked blocked,UUID building) {
        public Entry {
            Objects.requireNonNull(intent); Objects.requireNonNull(stage); Objects.requireNonNull(blocked);
            if((stage==Stage.IN_PROGRESS || stage==Stage.COMPLETED) && building==null) throw new IllegalArgumentException("Building identity required for construction progress");
            if(building!=null && intent.originalBuildings.contains(building)) throw new IllegalArgumentException("An existing building cannot fulfill a new royal construction order");
        }
        public boolean terminal() { return stage==Stage.COMPLETED || stage==Stage.CANCELLED || stage==Stage.FAILED; }
    }

    private final Path directory,file;
    public RoyalConstructionStore(Path directory) {
        this.directory=directory.toAbsolutePath().normalize(); this.file=this.directory.resolve("orders.dat");
        try {
            var created=new ArrayList<Path>();
            for(Path p=this.directory; !Files.exists(p);p=p.getParent()) created.add(p);
            Files.createDirectories(this.directory);
            for(Path p:created) sync(p);
            if(!created.isEmpty()) sync(created.getLast().getParent());
        } catch(IOException e) { throw failure(e); }
    }

    /** Bind a full intent and reserve the village in the same atomic index update, before any charge. */
    public synchronized Entry reserve(Intent intent) {
        return locked(entries->{
            Entry old=entries.get(intent.id);
            if(old!=null) {
                if(!old.intent.equals(intent)) throw new IllegalArgumentException("Royal order ID is already bound to a different intent");
                return old;
            }
            for(Entry entry:entries.values()) if(!entry.terminal() && sameVillage(entry.intent,intent)) {
                throw new IllegalStateException("Village already has active royal order "+entry.intent.id+" ("+entry.stage+")");
            }
            if(entries.size()>=MAX_ORDERS) throw new IllegalStateException("Royal order history limit reached; preserve data for audited archival");
            Entry entry=new Entry(intent,Stage.REQUESTED,Blocked.NONE,null); entries.put(intent.id,entry); write(entries); return entry;
        });
    }
    public synchronized Entry load(UUID id) { return locked(entries->entries.get(id)); }
    public synchronized List<Entry> entries() { return locked(entries->List.copyOf(entries.values())); }

    public synchronized Entry change(UUID id,Stage expected,Stage next,Blocked blocked,UUID building) {
        return locked(entries->{
            Entry old=entries.get(id);
            if(old==null) throw new IllegalArgumentException("Unknown royal construction order");
            Entry changed=new Entry(old.intent,next,blocked,building);
            if(old.equals(changed)) return old;
            if(old.stage!=expected || !allowed(expected,next) || (old.building!=null && !old.building.equals(building))) {
                throw new IllegalStateException("Invalid royal order transition: "+old.stage+" -> "+next);
            }
            entries.put(id,changed); write(entries); return changed;
        });
    }

    private static boolean allowed(Stage from,Stage to) {
        if(from==to) return from!=Stage.COMPLETED && from!=Stage.CANCELLED && from!=Stage.FAILED && from!=Stage.RECOVERY_REQUIRED;
        return switch(from) {
            case REQUESTED -> to==Stage.PAID || to==Stage.CANCELLING || to==Stage.FAILED || to==Stage.RECOVERY_REQUIRED;
            case PAID -> to==Stage.QUEUING || to==Stage.CANCELLING || to==Stage.RECOVERY_REQUIRED;
            case QUEUING -> to==Stage.QUEUED || to==Stage.IN_PROGRESS || to==Stage.CANCELLING || to==Stage.RECOVERY_REQUIRED;
            case QUEUED -> to==Stage.IN_PROGRESS || to==Stage.CANCELLING || to==Stage.FAILED || to==Stage.RECOVERY_REQUIRED;
            case IN_PROGRESS -> to==Stage.COMPLETED || to==Stage.CANCELLED || to==Stage.FAILED || to==Stage.RECOVERY_REQUIRED;
            case CANCELLING -> to==Stage.CANCELLED || to==Stage.RECOVERY_REQUIRED;
            case COMPLETED, CANCELLED, FAILED, RECOVERY_REQUIRED -> false;
        };
    }

    private Map<UUID,Entry> read() throws IOException {
        var result=new LinkedHashMap<UUID,Entry>();
        if(!Files.exists(file)) return result;
        var tag=NbtIo.readCompressed(file,NbtAccounter.create(MAX_BYTES));
        keys(tag,Set.of("format","orders"));
        if(!tag.contains("format",Tag.TAG_INT) || tag.getInt("format")!=FORMAT || !tag.contains("orders",Tag.TAG_LIST)) throw new IllegalStateException("Unsupported royal order format; original file preserved");
        var list=tag.getList("orders",Tag.TAG_COMPOUND);
        if(list.size()>MAX_ORDERS || list.size()!=((ListTag)tag.get("orders")).size()) {
            throw new IllegalStateException("Invalid royal order index");
        }
        var active=new HashSet<String>();
        for(Tag raw:list) {
            var item=(CompoundTag)raw; keys(item,Set.of("intent","stage","blocked","building"));
            if(!item.contains("intent",Tag.TAG_COMPOUND) || !item.contains("stage",Tag.TAG_STRING) || !item.contains("blocked",Tag.TAG_STRING)
                    || (item.contains("building") && !item.hasUUID("building"))) throw new IllegalStateException("Incomplete royal order entry");
            var entry=new Entry(loadIntent(item.getCompound("intent")),Stage.valueOf(item.getString("stage")),Blocked.valueOf(item.getString("blocked")),
                    item.hasUUID("building") ? item.getUUID("building") : null);
            if(result.putIfAbsent(entry.intent.id,entry)!=null) throw new IllegalStateException("Duplicate royal order identity");
            if(!entry.terminal() && !active.add(entry.intent.dimension+"/"+entry.intent.village)) throw new IllegalStateException("Duplicate active royal orders for a village");
        }
        return result;
    }

    private void write(Map<UUID,Entry> entries) {
        var tag=new CompoundTag(); tag.putInt("format",FORMAT); var list=new ListTag();
        for(Entry entry:entries.values()) {
            var item=new CompoundTag(); item.put("intent",saveIntent(entry.intent)); item.putString("stage",entry.stage.name()); item.putString("blocked",entry.blocked.name());
            if(entry.building!=null) item.putUUID("building",entry.building); list.add(item);
        }
        tag.put("orders",list);
        try {
            Path temporary=Files.createTempFile(directory,".orders-",".tmp");
            try {
                NbtIo.writeCompressed(tag,temporary);
                try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) { channel.force(true); }
                NbtIo.readCompressed(temporary,NbtAccounter.create(MAX_BYTES));
                Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); sync(directory);
            } finally { Files.deleteIfExists(temporary); }
        } catch(IOException e) { throw failure(e); }
    }

    private static CompoundTag saveIntent(Intent intent) {
        var tag=new CompoundTag(); tag.putUUID("id",intent.id); tag.putString("dimension",intent.dimension); tag.putUUID("kingdom",intent.kingdom);
        tag.putUUID("capital",intent.capital); tag.putUUID("village",intent.village); if(intent.actor!=null) tag.putUUID("actor",intent.actor);
        tag.putLong("rulerSince",intent.rulerSince); tag.putString("villageType",intent.villageType.toString()); tag.putString("planSet",intent.planSet.toString());
        tag.putString("plan",intent.plan.toString()); tag.putString("variant",intent.variant); tag.putLong("fee",intent.fee); tag.putLong("issued",intent.issuedGameTime);
        var ids=new ListTag(); for(UUID id:intent.originalBuildings.stream().sorted().toList()) { var item=new CompoundTag(); item.putUUID("id",id); ids.add(item); }
        tag.put("originalBuildings",ids); return tag;
    }
    private static Intent loadIntent(CompoundTag tag) {
        keys(tag,Set.of("id","dimension","kingdom","capital","village","actor","rulerSince","villageType","planSet","plan","variant","fee","issued","originalBuildings"));
        for(String key:List.of("id","kingdom","capital","village")) if(!tag.hasUUID(key)) throw new IllegalStateException("Incomplete royal order identity: "+key);
        for(String key:List.of("dimension","villageType","planSet","plan","variant")) if(!tag.contains(key,Tag.TAG_STRING)) throw new IllegalStateException("Incomplete royal order field: "+key);
        for(String key:List.of("rulerSince","fee","issued")) if(!tag.contains(key,Tag.TAG_LONG)) throw new IllegalStateException("Incomplete royal order amount/time: "+key);
        if(!tag.contains("originalBuildings",Tag.TAG_LIST) || (tag.contains("actor") && !tag.hasUUID("actor"))) throw new IllegalStateException("Incomplete royal order authority/building snapshot");
        var ids=new HashSet<UUID>(); var list=tag.getList("originalBuildings",Tag.TAG_COMPOUND);
        if(list.size()!=((ListTag)tag.get("originalBuildings")).size()) throw new IllegalStateException("Invalid original building snapshot");
        for(Tag raw:list) {
            var item=(CompoundTag)raw; keys(item,Set.of("id"));
            if(!item.hasUUID("id") || !ids.add(item.getUUID("id"))) throw new IllegalStateException("Duplicate/invalid original building identity");
        }
        return new Intent(tag.getUUID("id"),tag.getString("dimension"),tag.getUUID("kingdom"),tag.getUUID("capital"),tag.getUUID("village"),
                tag.hasUUID("actor") ? tag.getUUID("actor") : null,tag.getLong("rulerSince"),ResourceLocation.parse(tag.getString("villageType")),
                ResourceLocation.parse(tag.getString("planSet")),ResourceLocation.parse(tag.getString("plan")),tag.getString("variant"),tag.getLong("fee"),tag.getLong("issued"),ids);
    }
    private static void keys(CompoundTag tag,Set<String> allowed) {
        if(!allowed.containsAll(tag.getAllKeys())) throw new IllegalStateException("Unknown royal order data; original file preserved");
    }
    private static boolean sameVillage(Intent a,Intent b) { return a.dimension.equals(b.dimension) && a.village.equals(b.village); }
    private static void sync(Path directory) throws IOException { try(var channel=FileChannel.open(directory,StandardOpenOption.READ)) { channel.force(true); } }
    private <T> T locked(Function<Map<UUID,Entry>,T> operation) {
        try(var channel=FileChannel.open(directory.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            if(lock==null) throw new IllegalStateException("Royal order index is busy; do not charge or mutate a village");
            return operation.apply(read());
        } catch(OverlappingFileLockException e) { throw new IllegalStateException("Royal order index is busy; do not charge or mutate a village",e); }
        catch(IOException e) { throw failure(e); }
    }
    private static IllegalStateException failure(IOException cause) { return new IllegalStateException("Cannot safely access royal orders; preserve state and do not charge or mutate a village",cause); }
}
