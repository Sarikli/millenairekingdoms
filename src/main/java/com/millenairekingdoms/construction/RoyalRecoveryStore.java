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

/** Separate nonfinancial audited decisions. Never resets payment journals or alters political data. */
public final class RoyalRecoveryStore {
    private static final int FORMAT=2,MAX_DECISIONS=1000;
    private static final long MAX_BYTES=16L*1024*1024;
    public enum Mode { ADOPT_PENDING, ADOPT_BUILDING, FORFEIT_FEE }
    public enum State { AUTHORIZED, DONE, ABANDONED }
    public record Intent(UUID id,RoyalConstructionStore.Intent order,Mode mode,UUID building,UUID operator,String source,String reason,long issued) {
        public Intent {
            Objects.requireNonNull(id); Objects.requireNonNull(order); Objects.requireNonNull(mode);
            source=valid(source,64); reason=valid(reason,256);
            if(issued<0 || (mode==Mode.ADOPT_BUILDING)!=(building!=null) || building!=null && (order.upgrade()?!building.equals(order.upgradeBuilding()):order.originalBuildings().contains(building))) throw new IllegalArgumentException("Invalid recovery evidence or time");
        }
    }
    public record Result(UUID operator,String source,String reason,long time) {
        public Result { source=valid(source,64); reason=valid(reason,256); if(time<0) throw new IllegalArgumentException("Invalid recovery result time"); }
    }
    public record Entry(Intent intent,State state,Result result) {
        public Entry { Objects.requireNonNull(intent); Objects.requireNonNull(state); if((state==State.AUTHORIZED)!=(result==null)) throw new IllegalArgumentException("Incomplete recovery audit result"); }
    }
    private final Path directory,file;
    public RoyalRecoveryStore(Path directory) {
        this.directory=directory.toAbsolutePath().normalize(); file=this.directory.resolve("decisions.dat");
        try {
            var created=new ArrayList<Path>(); for(Path p=this.directory;!Files.exists(p);p=p.getParent()) created.add(p);
            Files.createDirectories(this.directory); for(Path p:created) sync(p); if(!created.isEmpty()) sync(created.getLast().getParent());
        } catch(IOException e) { throw failure(e); }
    }
    public Entry load(UUID id) { return locked(entries->entries.get(id)); }
    public List<Entry> entries() { return locked(entries->List.copyOf(entries.values())); }
    public Entry authorize(Intent intent) {
        return locked(entries->{
            var old=entries.get(intent.id());
            if(old!=null) { if(!old.intent.equals(intent)) throw new IllegalArgumentException("Recovery decision ID is bound to another audit intent"); return old; }
            for(var entry:entries.values()) if(entry.state==State.AUTHORIZED && entry.intent.order.id().equals(intent.order.id())) throw new IllegalStateException("Order already has authorized recovery decision "+entry.intent.id()+"; resume or explicitly abandon it");
            if(entries.size()>=MAX_DECISIONS) throw new IllegalStateException("Recovery audit history is full; preserve it for reviewed archival");
            var entry=new Entry(intent,State.AUTHORIZED,null); entries.put(intent.id(),entry); write(entries); return entry;
        });
    }
    public Entry finish(UUID id,State next,Result result) {
        if(next==State.AUTHORIZED) throw new IllegalArgumentException("Recovery decisions cannot be reset");
        return locked(entries->{
            var old=entries.get(id); if(old==null) throw new IllegalArgumentException("Unknown recovery decision");
            var changed=new Entry(old.intent,next,result); if(old.equals(changed)) return old;
            if(old.state!=State.AUTHORIZED) throw new IllegalStateException("Completed/abandoned recovery audit is immutable");
            entries.put(id,changed); write(entries); return changed;
        });
    }
    private Map<UUID,Entry> read() throws IOException {
        var entries=new LinkedHashMap<UUID,Entry>(); if(!Files.exists(file)) return entries;
        var root=NbtIo.readCompressed(file,NbtAccounter.create(MAX_BYTES)); keys(root,Set.of("format","decisions"));
        if(!root.contains("format",Tag.TAG_INT) || (root.getInt("format")<1 || root.getInt("format")>FORMAT) || !root.contains("decisions",Tag.TAG_LIST)) throw new IllegalStateException("Unsupported recovery audit; original preserved");
        var list=root.getList("decisions",Tag.TAG_COMPOUND); if(list.size()>MAX_DECISIONS || list.size()!=((ListTag)root.get("decisions")).size()) throw new IllegalStateException("Invalid recovery audit list");
        var active=new HashSet<UUID>();
        for(Tag raw:list) {
            var tag=(CompoundTag)raw; keys(tag,Set.of("intent","state","result"));
            if(!tag.contains("intent",Tag.TAG_COMPOUND) || !tag.contains("state",Tag.TAG_STRING) || tag.contains("result") && !tag.contains("result",Tag.TAG_COMPOUND)) throw new IllegalStateException("Incomplete recovery audit");
            var intent=loadIntent(tag.getCompound("intent"),root.getInt("format")>=2); var state=State.valueOf(tag.getString("state"));
            var entry=new Entry(intent,state,tag.contains("result") ? loadResult(tag.getCompound("result")) : null);
            if(entries.putIfAbsent(intent.id(),entry)!=null || state==State.AUTHORIZED && !active.add(intent.order().id())) throw new IllegalStateException("Duplicate recovery identity/reservation");
        }
        return entries;
    }
    private void write(Map<UUID,Entry> entries) {
        var root=new CompoundTag(); root.putInt("format",FORMAT); var list=new ListTag();
        for(var entry:entries.values()) {
            var tag=new CompoundTag(); tag.put("intent",saveIntent(entry.intent)); tag.putString("state",entry.state.name());
            if(entry.result!=null) tag.put("result",saveResult(entry.result)); list.add(tag);
        }
        root.put("decisions",list);
        try {
            Path temporary=Files.createTempFile(directory,".decisions-",".tmp");
            try {
                NbtIo.writeCompressed(root,temporary); try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) {channel.force(true);}
                NbtIo.readCompressed(temporary,NbtAccounter.create(MAX_BYTES));
                Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); sync(directory);
            } finally { Files.deleteIfExists(temporary); }
        } catch(IOException e) {throw failure(e);}
    }
    private static CompoundTag saveIntent(Intent i) {
        var tag=new CompoundTag(); tag.putUUID("id",i.id()); tag.put("order",RoyalConstructionStore.saveIntent(i.order())); tag.putString("mode",i.mode().name());
        if(i.building()!=null) tag.putUUID("building",i.building()); if(i.operator()!=null) tag.putUUID("operator",i.operator());
        tag.putString("source",i.source()); tag.putString("reason",i.reason()); tag.putLong("issued",i.issued()); return tag;
    }
    private static Intent loadIntent(CompoundTag tag,boolean upgradesAllowed) {
        keys(tag,Set.of("id","order","mode","building","operator","source","reason","issued"));
        if(!tag.hasUUID("id") || !tag.contains("order",Tag.TAG_COMPOUND) || !tag.contains("mode",Tag.TAG_STRING) || !tag.contains("source",Tag.TAG_STRING)
                || !tag.contains("reason",Tag.TAG_STRING) || !tag.contains("issued",Tag.TAG_LONG) || tag.contains("building") && !tag.hasUUID("building") || tag.contains("operator") && !tag.hasUUID("operator")) throw new IllegalStateException("Incomplete recovery intent");
        return new Intent(tag.getUUID("id"),RoyalConstructionStore.loadIntent(tag.getCompound("order"),upgradesAllowed),Mode.valueOf(tag.getString("mode")),
                tag.hasUUID("building") ? tag.getUUID("building") : null,tag.hasUUID("operator") ? tag.getUUID("operator") : null,tag.getString("source"),tag.getString("reason"),tag.getLong("issued"));
    }
    private static CompoundTag saveResult(Result r) {
        var tag=new CompoundTag(); if(r.operator()!=null) tag.putUUID("operator",r.operator()); tag.putString("source",r.source()); tag.putString("reason",r.reason()); tag.putLong("time",r.time()); return tag;
    }
    private static Result loadResult(CompoundTag tag) {
        keys(tag,Set.of("operator","source","reason","time"));
        if(!tag.contains("source",Tag.TAG_STRING) || !tag.contains("reason",Tag.TAG_STRING) || !tag.contains("time",Tag.TAG_LONG) || tag.contains("operator") && !tag.hasUUID("operator")) throw new IllegalStateException("Incomplete recovery result");
        return new Result(tag.hasUUID("operator") ? tag.getUUID("operator") : null,tag.getString("source"),tag.getString("reason"),tag.getLong("time"));
    }
    private static String valid(String value,int max) {
        if(value==null || value.isBlank() || value.strip().length()>max || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Recovery source/reason must be bounded printable text"); return value.strip();
    }
    private static void keys(CompoundTag tag,Set<String> allowed) { if(!allowed.containsAll(tag.getAllKeys())) throw new IllegalStateException("Unknown recovery data; original preserved"); }
    private static void sync(Path path) throws IOException {try(var channel=FileChannel.open(path,StandardOpenOption.READ)){channel.force(true);}}
    private static IllegalStateException failure(IOException e) {return new IllegalStateException("Cannot safely access recovery audit; preserve original state",e);}
    private <T> T locked(Function<Map<UUID,Entry>,T> operation) {
        try(var channel=FileChannel.open(directory.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            if(lock==null) throw new IllegalStateException("Recovery audit busy; no financial or order resolution allowed"); return operation.apply(read());
        } catch(OverlappingFileLockException e) {throw new IllegalStateException("Recovery audit busy; no financial or order resolution allowed",e);}
        catch(IOException e) {throw failure(e);}
    }
}
