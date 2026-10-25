import com.millenairekingdoms.atlas.geography.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;

/** Synthetic metadata/I/O microbenchmark, not a GameTest or a graphical verification. */
public class ProfileGeography {
    public static void main(String[] args)throws Exception{
        var root=Path.of(args[0]);Files.createDirectories(root);var owner=UUID.randomUUID();var list=new ListTag();var random=new Random(144);
        for(int n=0;n<16384;n++){
            var id=new UUID(random.nextLong(),random.nextLong());var meta=new CompoundTag();meta.putInt("x",n);meta.putInt("z",0);meta.putLong("revision",n+1);meta.putUUID("file",id);list.add(meta);
            var tile=new CompoundTag();tile.putInt("format",1);tile.putUUID("player",owner);tile.putString("dimension","minecraft:overworld");tile.putUUID("file",id);tile.putInt("x",n);tile.putInt("z",0);tile.putLong("revision",n+1);var cells=new byte[256];GeographyTile.set(cells,0,0,GeographyTile.LAND);tile.putByteArray("cells",cells);NbtIo.writeCompressed(tile,root.resolve(id+".dat"));
        }
        var index=new CompoundTag();index.putInt("format",1);index.putUUID("player",owner);index.putString("dimension","minecraft:overworld");index.putLong("revision",16384);index.put("tiles",list);NbtIo.writeCompressed(index,root.resolve("index.dat"));long start=System.nanoTime();var store=new GeographyStore(root,owner,"minecraft:overworld");long loaded=System.nanoTime();store.query(16320,Long.MAX_VALUE);long queried=System.nanoTime();
        for(int n=0;n<5;n++){var cells=store.cells(new GeographyTile.Position(n,0));GeographyTile.set(cells,1,0,GeographyTile.OCEAN);long a=System.nanoTime();store.commit(Map.of(new GeographyTile.Position(n,0),cells));System.out.println("SYNTHETIC_GEOGRAPHY_COMMIT tiles="+store.size()+" milliseconds="+(System.nanoTime()-a)/1_000_000.0);}
        System.out.println("SYNTHETIC_GEOGRAPHY_INDEX tiles="+store.size()+" startup_ms="+(loaded-start)/1_000_000.0+" page64_ms="+(queried-loaded)/1_000_000.0+" index_bytes="+Files.size(root.resolve("index.dat"))+" graphical_verification=false");
    }
}
