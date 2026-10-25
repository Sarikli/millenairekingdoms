package com.millenairekingdoms.atlas.geography;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.io.IOException;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

/** Own per-player/dimension observed masks. A small atomic root commits immutable shard/tile versions. */
public final class GeographyStore {
    public static final int MAX_TILES=16384,PAGE_SIZE=64,HOT_TILES=128,MAX_BATCH=8,ORPHAN_ALLOWANCE=64;
    public static final int SHARDS=64,MAX_SHARD_TILES=512;
    private record Meta(GeographyTile.Position position,long revision,UUID file){}
    private record Shard(int slot,long revision,int count,UUID file){}
    public record Page(List<GeographyTile> tiles,long watermark,long next,boolean more,boolean reset){public Page{tiles=List.copyOf(tiles);}}
    private final Path root;private final UUID player;private final String dimension;
    private final Map<GeographyTile.Position,Meta> metadata=new HashMap<>();
    private final NavigableMap<Long,Meta> revisions=new TreeMap<>();
    private final Map<Integer,Shard> shards=new HashMap<>();
    private final Map<Integer,Map<GeographyTile.Position,Meta>> entries=new HashMap<>();
    private long revision;private int tileFiles,shardFiles,temporaryFiles;private CompoundTag acknowledged;
    private final Map<UUID,GeographyTile> hot=new LinkedHashMap<>(16,.75f,true){protected boolean removeEldestEntry(Map.Entry<UUID,GeographyTile> e){return size()>HOT_TILES;}};

    public GeographyStore(Path root,UUID player,String dimension){
        if(!GeographyService.ENABLED)throw new UnsupportedOperationException("Ocean geography cache access is disabled; existing files are preserved");
        if(player==null || dimension==null || dimension.length()>256 || ResourceLocation.tryParse(dimension)==null)throw new IllegalArgumentException("Invalid geography owner/dimension");
        this.root=root.toAbsolutePath().normalize();this.player=player;this.dimension=dimension;
        if(Files.exists(index())){acknowledged=GeographyFiles.read(index(),65536);loadIndex(acknowledged);}
        var tileIds=new HashSet<UUID>();var shardIds=new HashSet<UUID>();
        try{if(Files.exists(this.root))try(var stream=Files.list(this.root)){
            var files=stream.limit(MAX_TILES+SHARDS+ORPHAN_ALLOWANCE*3L+2).toList();
            if(files.size()>MAX_TILES+SHARDS+ORPHAN_ALLOWANCE*3+1)throw new IllegalStateException("Geography directory exceeds its file limit; preserve it");
            for(var file:files){String name=file.getFileName().toString();if(name.equals("index.dat") || name.equals("cache.lock"))continue;
                if(name.endsWith(".tmp")){temporaryFiles++;continue;}
                try{if(name.startsWith("tile-") && name.endsWith(".dat"))tileIds.add(UUID.fromString(name.substring(5,name.length()-4)));
                    else if(name.startsWith("shard-") && name.endsWith(".dat"))shardIds.add(UUID.fromString(name.substring(6,name.length()-4)));
                    else throw new IllegalArgumentException("Unrecognized geography cache filename; preserve it");}
                catch(IllegalArgumentException failure){throw new IllegalStateException("Unexpected geography cache file; preserve it",failure);}
            }
        }}catch(IOException e){throw new IllegalStateException("Geography directory unavailable",e);}
        tileFiles=tileIds.size();shardFiles=shardIds.size();
        if(tileFiles>metadata.size()+ORPHAN_ALLOWANCE || shardFiles>shards.size()+ORPHAN_ALLOWANCE || temporaryFiles>ORPHAN_ALLOWANCE
                || !tileIds.containsAll(metadata.values().stream().map(Meta::file).toList()) || !shardIds.containsAll(shards.values().stream().map(Shard::file).toList())
                || acknowledged==null && (tileFiles>0 || shardFiles>0 || temporaryFiles>0))throw new IllegalStateException("Geography files/index missing or orphan limit exceeded; preserve files for review");
    }
    private Path index(){return root.resolve("index.dat");}
    private Path tileFile(UUID id){return root.resolve("tile-"+id+".dat");}
    private Path shardFile(UUID id){return root.resolve("shard-"+id+".dat");}
    public static int shard(GeographyTile.Position position){long value=(long)position.x()<<32 ^ (position.z()&0xffffffffL);value^=value>>>33;value*=0xff51afd7ed558ccdL;value^=value>>>33;value*=0xc4ceb9fe1a85ec53L;value^=value>>>33;return (int)value&(SHARDS-1);}
    public int size(){return metadata.size();}public long revision(){return revision;}public int hotSize(){return hot.size();}
    public boolean knows(GeographyTile.Position position){return metadata.containsKey(position);}
    public byte[] cells(GeographyTile.Position position){var meta=metadata.get(position);return meta==null?new byte[GeographyTile.BYTES]:tile(meta).cells();}
    private GeographyTile tile(Meta meta){
        var cached=hot.get(meta.file());if(cached!=null)return cached;
        var tag=GeographyFiles.read(tileFile(meta.file()),4096);GeographyFiles.keys(tag,Set.of("format","player","dimension","file","x","z","revision","cells"));header(tag);
        for(var key:List.of("x","z"))GeographyFiles.require(tag,key,Tag.TAG_INT);GeographyFiles.require(tag,"revision",Tag.TAG_LONG);GeographyFiles.require(tag,"cells",Tag.TAG_BYTE_ARRAY);
        if(!tag.hasUUID("file") || !tag.getUUID("file").equals(meta.file()) || tag.getInt("x")!=meta.position().x() || tag.getInt("z")!=meta.position().z() || tag.getLong("revision")!=meta.revision())throw new IllegalStateException("Geography tile/index binding changed; preserve bytes");
        var tile=new GeographyTile(meta.position(),meta.revision(),tag.getByteArray("cells"));hot.put(meta.file(),tile);return tile;
    }
    private void header(CompoundTag tag){
        GeographyFiles.require(tag,"format",Tag.TAG_INT);GeographyFiles.require(tag,"dimension",Tag.TAG_STRING);
        if(tag.getInt("format")!=1 || !tag.hasUUID("player") || !tag.getUUID("player").equals(player) || !tag.getString("dimension").equals(dimension))throw new IllegalArgumentException("Unsupported or foreign geography data; original bytes preserved");
    }
    private CompoundTag base(){var tag=new CompoundTag();tag.putInt("format",1);tag.putUUID("player",player);tag.putString("dimension",dimension);return tag;}
    private Map<GeographyTile.Position,Meta> readShard(Shard expected){
        var tag=GeographyFiles.read(shardFile(expected.file()),512*1024);GeographyFiles.keys(tag,Set.of("format","player","dimension","file","slot","revision","tiles"));header(tag);
        GeographyFiles.require(tag,"slot",Tag.TAG_INT);GeographyFiles.require(tag,"revision",Tag.TAG_LONG);GeographyFiles.require(tag,"tiles",Tag.TAG_LIST);
        if(!tag.hasUUID("file") || !tag.getUUID("file").equals(expected.file()) || tag.getInt("slot")!=expected.slot() || tag.getLong("revision")!=expected.revision())throw new IllegalArgumentException("Geography shard/root binding changed; preserve bytes");
        var list=(ListTag)tag.get("tiles");if(list.size()!=expected.count() || list.isEmpty() || list.size()>MAX_SHARD_TILES || list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Invalid geography shard size/type");
        var found=new HashMap<GeographyTile.Position,Meta>();var ids=new HashSet<UUID>();var versions=new HashSet<Long>();long highest=0;
        for(var value:list){var raw=(CompoundTag)value;GeographyFiles.keys(raw,Set.of("x","z","revision","file"));for(var key:List.of("x","z"))GeographyFiles.require(raw,key,Tag.TAG_INT);GeographyFiles.require(raw,"revision",Tag.TAG_LONG);
            if(!raw.hasUUID("file"))throw new IllegalArgumentException("Invalid geography tile file identity");var position=new GeographyTile.Position(raw.getInt("x"),raw.getInt("z"));var meta=new Meta(position,raw.getLong("revision"),raw.getUUID("file"));
            if(shard(position)!=expected.slot() || meta.revision()<1 || meta.revision()>expected.revision() || found.putIfAbsent(position,meta)!=null || !ids.add(meta.file()) || !versions.add(meta.revision()))throw new IllegalArgumentException("Invalid/repeated geography shard member");highest=Math.max(highest,meta.revision());
        }
        if(highest!=expected.revision())throw new IllegalArgumentException("Geography shard revision is not its newest tile");return found;
    }
    private void loadIndex(CompoundTag tag){
        GeographyFiles.keys(tag,Set.of("format","player","dimension","revision","shards"));header(tag);GeographyFiles.require(tag,"revision",Tag.TAG_LONG);GeographyFiles.require(tag,"shards",Tag.TAG_LIST);revision=tag.getLong("revision");var list=(ListTag)tag.get("shards");
        if(revision<0 || list.size()>SHARDS || !list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Invalid geography root index bound/type");var ids=new HashSet<UUID>();
        for(var value:list){var raw=(CompoundTag)value;GeographyFiles.keys(raw,Set.of("slot","revision","count","file"));for(var key:List.of("slot","count"))GeographyFiles.require(raw,key,Tag.TAG_INT);GeographyFiles.require(raw,"revision",Tag.TAG_LONG);if(!raw.hasUUID("file"))throw new IllegalArgumentException("Invalid geography shard UUID");
            var part=new Shard(raw.getInt("slot"),raw.getLong("revision"),raw.getInt("count"),raw.getUUID("file"));
            if(part.slot()<0 || part.slot()>=SHARDS || part.revision()<1 || part.revision()>revision || part.count()<1 || part.count()>MAX_SHARD_TILES || !ids.add(part.file()) || shards.putIfAbsent(part.slot(),part)!=null)throw new IllegalArgumentException("Duplicate/invalid geography shard identity");
            var members=readShard(part);entries.put(part.slot(),members);
            for(var meta:members.values())if(metadata.putIfAbsent(meta.position(),meta)!=null || revisions.putIfAbsent(meta.revision(),meta)!=null || !ids.add(meta.file()))throw new IllegalArgumentException("Duplicate geography tile identity/revision");
            if(metadata.size()>MAX_TILES)throw new IllegalArgumentException("Geography tile index exceeds capacity");
        }
        if(revision!=(revisions.isEmpty()?0:revisions.lastKey()))throw new IllegalArgumentException("Geography root revision is not its newest observation");
    }
    private CompoundTag indexTag(Map<Integer,Shard> parts,long version){
        var tag=base();tag.putLong("revision",version);var list=new ListTag();parts.values().stream().sorted(Comparator.comparingInt(Shard::slot)).forEach(part->{var raw=new CompoundTag();raw.putInt("slot",part.slot());raw.putLong("revision",part.revision());raw.putInt("count",part.count());raw.putUUID("file",part.file());list.add(raw);});tag.put("shards",list);return tag;
    }
    private CompoundTag shardTag(Shard part,Map<GeographyTile.Position,Meta> members){
        var tag=base();tag.putUUID("file",part.file());tag.putInt("slot",part.slot());tag.putLong("revision",part.revision());var list=new ListTag();members.values().stream().sorted(Comparator.comparingLong(Meta::revision)).forEach(meta->{var raw=new CompoundTag();raw.putInt("x",meta.position().x());raw.putInt("z",meta.position().z());raw.putLong("revision",meta.revision());raw.putUUID("file",meta.file());list.add(raw);});tag.put("tiles",list);return tag;
    }
    /** Read-only dirty check; service holds exclusive ownership until the asynchronous commit completes. */
    public boolean wouldChange(Map<GeographyTile.Position,byte[]> observations){
        if(observations.size()>MAX_BATCH)throw new IllegalArgumentException("Geography write batch exceeded");
        boolean changed=false;for(var entry:observations.entrySet()){if(entry.getKey()==null || entry.getValue()==null || entry.getValue().length!=GeographyTile.BYTES)throw new IllegalArgumentException("Invalid geography mask");if(!Arrays.equals(cells(entry.getKey()),entry.getValue()))changed=true;}return changed;
    }
    /** Already observed masks only. At most eight tile/shard versions and one small root are written per commit. */
    public int commit(Map<GeographyTile.Position,byte[]> observations){
        if(observations.size()>MAX_BATCH)throw new IllegalArgumentException("Geography write batch exceeded");var changed=new LinkedHashMap<GeographyTile.Position,byte[]>();var expected=new HashMap<GeographyTile.Position,byte[]>();
        for(var entry:observations.entrySet()){if(entry.getKey()==null || entry.getValue()==null || entry.getValue().length!=GeographyTile.BYTES)throw new IllegalArgumentException("Invalid geography mask");var original=cells(entry.getKey());if(!Arrays.equals(original,entry.getValue())){changed.put(entry.getKey(),entry.getValue().clone());expected.put(entry.getKey(),original);}}
        if(changed.isEmpty())return 0;long added=changed.keySet().stream().filter(key->!metadata.containsKey(key)).count();var touched=changed.keySet().stream().map(GeographyStore::shard).collect(java.util.stream.Collectors.toSet());long addedShards=touched.stream().filter(slot->!shards.containsKey(slot)).count();
        if(metadata.size()+added>MAX_TILES || tileFiles+changed.size()>metadata.size()+added+ORPHAN_ALLOWANCE || shardFiles+touched.size()>shards.size()+addedShards+ORPHAN_ALLOWANCE || revision>Long.MAX_VALUE-changed.size())throw new IllegalStateException("Geography capacity reached; preserve observations");
        for(int slot:touched){long count=entries.getOrDefault(slot,Map.of()).size()+changed.keySet().stream().filter(key->shard(key)==slot && !metadata.containsKey(key)).count();if(count>MAX_SHARD_TILES)throw new IllegalStateException("Geography shard capacity reached; preserve observations");}
        try{Files.createDirectories(root);try(var channel=FileChannel.open(root.resolve("cache.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IllegalStateException("Geography cache busy");var disk=Files.exists(index())?GeographyFiles.read(index(),65536):null;if(!Objects.equals(disk,acknowledged))throw new IllegalStateException("Geography root changed outside this session; preserve it");
            // A hot mask or in-memory shard must never hide future/corrupt disk bytes before replacement.
            for(int slot:touched)if(shards.containsKey(slot) && !readShard(shards.get(slot)).equals(entries.get(slot)))throw new IllegalStateException("Geography shard changed outside this session; preserve bytes");
            for(var position:changed.keySet()){var old=metadata.get(position);if(old!=null){hot.remove(old.file());if(!Arrays.equals(tile(old).cells(),expected.get(position)))throw new IllegalStateException("Geography tile changed outside this session; preserve bytes");}}
            var nextEntries=new HashMap<Integer,Map<GeographyTile.Position,Meta>>();for(int slot:touched)nextEntries.put(slot,new HashMap<>(entries.getOrDefault(slot,Map.of())));
            var replacements=new LinkedHashMap<GeographyTile.Position,Meta>();var created=new HashMap<UUID,GeographyTile>();long generation=revision;
            for(var entry:changed.entrySet()){
                var meta=new Meta(entry.getKey(),++generation,UUID.randomUUID());var tile=new GeographyTile(entry.getKey(),generation,entry.getValue());var tag=base();tag.putUUID("file",meta.file());tag.putInt("x",entry.getKey().x());tag.putInt("z",entry.getKey().z());tag.putLong("revision",generation);tag.putByteArray("cells",tile.cells());
                GeographyFiles.write(tileFile(meta.file()),tag);tileFiles++;nextEntries.get(shard(entry.getKey())).put(entry.getKey(),meta);replacements.put(entry.getKey(),meta);created.put(meta.file(),tile);
            }
            var nextShards=new HashMap<>(shards);var oldShards=new ArrayList<Shard>();
            for(int slot:touched){var members=nextEntries.get(slot);long high=members.values().stream().mapToLong(Meta::revision).max().orElseThrow();var part=new Shard(slot,high,members.size(),UUID.randomUUID());GeographyFiles.write(shardFile(part.file()),shardTag(part,members));shardFiles++;var previous=nextShards.put(slot,part);if(previous!=null)oldShards.add(previous);}
            var rootTag=indexTag(nextShards,generation);GeographyFiles.write(index(),rootTag);
            // Only root acknowledgement publishes revisions and permits deletion of replaced, revalidated versions.
            var oldTiles=new ArrayList<Meta>();for(var replacement:replacements.values()){var old=metadata.put(replacement.position(),replacement);if(old!=null){oldTiles.add(old);revisions.remove(old.revision());}revisions.put(replacement.revision(),replacement);}
            shards.clear();shards.putAll(nextShards);entries.putAll(nextEntries);revision=generation;acknowledged=rootTag;hot.putAll(created);
            for(var old:oldTiles){hot.remove(old.file());try{if(Files.deleteIfExists(tileFile(old.file())))tileFiles--;}catch(IOException ignored){/* Bounded stale evidence remains. */}}
            for(var old:oldShards)try{if(Files.deleteIfExists(shardFile(old.file())))shardFiles--;}catch(IOException ignored){/* Bounded stale evidence remains. */}
            return changed.size();
        }}catch(IOException e){throw new IllegalStateException("Cannot acknowledge geography cache update",e);}
    }
    /** Revision deltas need neither a world scan nor retained server snapshot buffers. */
    public Page query(long after,long through){
        if(after<0 || through<after)throw new IllegalArgumentException("Invalid geography revision window");long watermark=Math.min(through,revision);if(after>revision)return new Page(List.of(),revision,revision,false,true);if(after>=watermark)return new Page(List.of(),watermark,watermark,false,false);
        var selected=revisions.subMap(after,false,watermark,true).values().stream().limit(PAGE_SIZE+1L).toList();boolean more=selected.size()>PAGE_SIZE;var tiles=selected.stream().limit(PAGE_SIZE).map(this::tile).toList();long next=more?tiles.getLast().revision():watermark;return new Page(tiles,watermark,next,more,false);
    }
}
