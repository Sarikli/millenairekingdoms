import com.millenairekingdoms.atlas.geography.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
/** Same synthetic 16k observation fixture as the flat-index benchmark; no GameTest/graphics claim. */
public class ProfileShardedGeography {
    public static void main(String[] args)throws Exception{
        var source=Path.of(args[0]);var root=Path.of(args[1]);Files.createDirectories(root);var flat=NbtIo.readCompressed(source.resolve("index.dat"),NbtAccounter.create(16L*1024*1024));var groups=new TreeMap<Integer,ListTag>();
        for(var raw:flat.getList("tiles",10)){var row=(CompoundTag)raw;var position=new GeographyTile.Position(row.getInt("x"),row.getInt("z"));groups.computeIfAbsent(GeographyStore.shard(position),slot->new ListTag()).add(row.copy());Files.copy(source.resolve(row.getUUID("file")+".dat"),root.resolve("tile-"+row.getUUID("file")+".dat"));}
        var parts=new ListTag();for(var group:groups.entrySet()){
            long high=group.getValue().stream().mapToLong(t->((CompoundTag)t).getLong("revision")).max().orElseThrow();var id=UUID.randomUUID();var shard=new CompoundTag();shard.putInt("format",1);shard.putUUID("player",flat.getUUID("player"));shard.putString("dimension","minecraft:overworld");shard.putUUID("file",id);shard.putInt("slot",group.getKey());shard.putLong("revision",high);shard.put("tiles",group.getValue());NbtIo.writeCompressed(shard,root.resolve("shard-"+id+".dat"));
            var part=new CompoundTag();part.putInt("slot",group.getKey());part.putLong("revision",high);part.putInt("count",group.getValue().size());part.putUUID("file",id);parts.add(part);
        }
        var index=new CompoundTag();index.putInt("format",1);index.putUUID("player",flat.getUUID("player"));index.putString("dimension","minecraft:overworld");index.putLong("revision",flat.getLong("revision"));index.put("shards",parts);NbtIo.writeCompressed(index,root.resolve("index.dat"));
        long start=System.nanoTime();var store=new GeographyStore(root,flat.getUUID("player"),"minecraft:overworld");long loaded=System.nanoTime();store.query(store.revision()-64,Long.MAX_VALUE);long queried=System.nanoTime();
        for(int n=0;n<10;n++){var pos=new GeographyTile.Position(n+100,0);var cells=store.cells(pos);GeographyTile.set(cells,1,0,GeographyTile.OCEAN);long a=System.nanoTime();store.commit(Map.of(pos,cells));System.out.println("SYNTHETIC_GEOGRAPHY_SHARDED_COMMIT tiles="+store.size()+" milliseconds="+(System.nanoTime()-a)/1_000_000.0);}
        System.out.println("SYNTHETIC_GEOGRAPHY_SHARDED_INDEX tiles="+store.size()+" startup_ms="+(loaded-start)/1_000_000.0+" page64_ms="+(queried-loaded)/1_000_000.0+" index_bytes="+Files.size(root.resolve("index.dat"))+" graphical_verification=false");
    }
}
