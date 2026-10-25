package com.millenairekingdoms.history;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** Immutable per-event commits, bounded startup index and indexed/paginated queries. No routine full-file rewrite.
 * A server owns one instance per dimension. New instances rebuild indices once; queries never reread all records. */
public final class HistoryStore {
    public static final int MAX_EVENTS=100_000, PAGE_SIZE=24;
    private record Key(long day,long time,UUID id) implements Comparable<Key>{
        public int compareTo(Key other){int c=Long.compare(day,other.day);if(c==0)c=Long.compare(time,other.time);return c==0?id.compareTo(other.id):c;}
    }
    public record Query(UUID kingdom,HistoryEvent.Kind kind,long fromDay,long toDay,UUID before,int limit){
        public Query {if(fromDay<0 || toDay<fromDay || limit<1 || limit>PAGE_SIZE)throw new IllegalArgumentException("Invalid historical query bounds");}
    }
    public record Page(List<HistoryEvent> events,UUID next,int examined){public Page{events=List.copyOf(events);}}
    private final Path root;
    private final NavigableSet<Key> timeline=new TreeSet<>();
    private final Map<UUID,Key> identities=new HashMap<>();
    private final Map<UUID,NavigableSet<Key>> kingdoms=new HashMap<>();
    private final Map<HistoryEvent.Kind,NavigableSet<Key>> kinds=new HashMap<>();
    private final Map<UUID,HistoryEvent.Realm> archives=new HashMap<>();
    public HistoryStore(Path root){
        this.root=root.toAbsolutePath().normalize();
        if(Files.exists(this.root.resolve("schema.dat")))validateSchema();
        else if(Files.exists(this.root.resolve("events")))throw new IllegalStateException("Historical schema missing; preserve archive");
        try {
            if(Files.exists(this.root.resolve("events")))try(var files=Files.list(this.root.resolve("events"))){
                var paths=files.filter(p->p.getFileName().toString().endsWith(".dat")).limit(MAX_EVENTS+1L).sorted().toList();
                if(paths.size()>MAX_EVENTS)throw new IllegalStateException("Historical archive limit exceeded");
                for(var path:paths){var event=HistoryEvent.load(HistoryFiles.read(path));if(!path.equals(file(event.id())))throw new IllegalStateException("Historical filename/identity mismatch");index(event);}
            }
        }catch(IOException e){throw new IllegalStateException("Historical archive unavailable; no reset",e);}
    }
    public synchronized boolean append(HistoryEvent event){
        try {
            Files.createDirectories(root);
            try(var ch=FileChannel.open(root.resolve("archive.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=ch.tryLock()){
                if(lock==null)throw new IllegalStateException("Historical archive busy");
                if(Files.exists(root.resolve("schema.dat")))validateSchema();else{var schema=new CompoundTag();schema.putInt("format",1);HistoryFiles.write(root.resolve("schema.dat"),schema);}
                var file=file(event.id());
                if(Files.exists(file)){var saved=HistoryEvent.load(HistoryFiles.read(file));if(!saved.equals(event))throw new IllegalArgumentException("Historical identity already binds different facts");if(!identities.containsKey(event.id()))index(saved);return false;}
                if(identities.size()>=MAX_EVENTS)throw new IllegalStateException("Historical archive full; preserve records for archival");
                HistoryFiles.write(file,event.save());index(event);return true;
            }
        }catch(IOException e){throw new IllegalStateException("Historical archive write unavailable",e);}
    }
    public synchronized HistoryEvent get(UUID id){return identities.containsKey(id)?HistoryEvent.load(HistoryFiles.read(file(id))):null;}
    public synchronized int size(){return identities.size();}
    public synchronized boolean contains(UUID id){return identities.containsKey(id);}
    public synchronized List<HistoryEvent.Realm> archives(){return archives.values().stream().sorted(Comparator.comparing(HistoryEvent.Realm::name).thenComparing(HistoryEvent.Realm::id)).toList();}
    public synchronized Page query(Query query){
        NavigableSet<Key> candidates=timeline;
        if(query.kingdom()!=null)candidates=kingdoms.getOrDefault(query.kingdom(),new TreeSet<>());
        if(query.kind()!=null){var byKind=kinds.getOrDefault(query.kind(),new TreeSet<>());if(byKind.size()<candidates.size())candidates=byKind;}
        var key=query.before()==null?null:identities.get(query.before());if(query.before()!=null && key==null)throw new IllegalArgumentException("Unknown historical page cursor");
        var from=new Key(query.fromDay(),0,new UUID(Long.MIN_VALUE,Long.MIN_VALUE));
        var to=new Key(query.toDay(),Long.MAX_VALUE,new UUID(Long.MAX_VALUE,Long.MAX_VALUE));
        if(key!=null && key.compareTo(from)<=0)return new Page(List.of(),null,0);
        var range=candidates.subSet(from,true,to,true);if(key!=null && key.compareTo(to)<=0)range=range.headSet(key,false);
        var found=new ArrayList<HistoryEvent>();int examined=0;UUID next=null;
        for(var candidate:range.descendingSet()){
            examined++;
            if(query.kingdom()!=null && !kingdoms.getOrDefault(query.kingdom(),new TreeSet<>()).contains(candidate))continue;
            if(query.kind()!=null && !kinds.getOrDefault(query.kind(),new TreeSet<>()).contains(candidate))continue;
            if(found.size()==query.limit()){next=found.getLast().id();break;}found.add(get(candidate.id()));
        }
        return new Page(found,next,examined);
    }
    private Path file(UUID id){return root.resolve("events").resolve(id+".dat");}
    private void index(HistoryEvent event){
        var key=new Key(event.day(),event.gameTime(),event.id());if(identities.putIfAbsent(event.id(),key)!=null)throw new IllegalStateException("Duplicate history ID");timeline.add(key);kinds.computeIfAbsent(event.kind(),k->new TreeSet<>()).add(key);
        for(var realm:event.realms()){kingdoms.computeIfAbsent(realm.id(),k->new TreeSet<>()).add(key);archives.putIfAbsent(realm.id(),realm);}
    }
    private void validateSchema(){var t=HistoryFiles.read(root.resolve("schema.dat"));HistoryFiles.keys(t,Set.of("format"));if(!t.contains("format",3)||t.getInt("format")!=1)throw new IllegalStateException("Unsupported historical archive; original bytes preserved");}
}
