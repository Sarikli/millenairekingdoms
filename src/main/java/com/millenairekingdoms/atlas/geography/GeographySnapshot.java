package com.millenairekingdoms.atlas.geography;

import java.util.*;
import net.minecraft.nbt.*;

/** Client/common bounded cache; validates a whole page before publishing. It cannot access Minecraft world data. */
public final class GeographySnapshot {
    private final Map<GeographyTile.Position,GeographyTile> tiles=new HashMap<>();
    private String dimension="";private long revision,through=Long.MAX_VALUE,next;private boolean paging;
    public Collection<GeographyTile> tiles(){return Collections.unmodifiableCollection(tiles.values());}
    public GeographyTile tile(GeographyTile.Position position){return tiles.get(position);}
    public int size(){return tiles.size();}public long after(){return paging?next:revision;}public long through(){return paging?through:Long.MAX_VALUE;}
    public String dimension(){return dimension;}
    public void clear(String dimension){tiles.clear();this.dimension=dimension;revision=0;through=Long.MAX_VALUE;next=0;paging=false;}
    public boolean accept(CompoundTag view){
        if(!view.getString("mode").equals("GEOGRAPHY") || !view.contains("geography",Tag.TAG_LIST) || !view.contains("watermark",Tag.TAG_LONG) || !view.contains("nextRevision",Tag.TAG_LONG) || !view.contains("dimension",Tag.TAG_STRING) || !view.contains("more",Tag.TAG_BYTE) || !view.contains("reset",Tag.TAG_BYTE))throw new IllegalArgumentException("Invalid observed geography page");
        String incoming=view.getString("dimension");if(net.minecraft.resources.ResourceLocation.tryParse(incoming)==null)throw new IllegalArgumentException("Invalid geography dimension");
        var raw=(ListTag)view.get("geography");if(raw.size()>GeographyStore.PAGE_SIZE || !raw.isEmpty() && raw.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Geography page exceeded limit");
        long high=view.getLong("watermark"),last=view.getLong("nextRevision");boolean more=view.getBoolean("more"),reset=view.getBoolean("reset");
        if(high<0 || last<0 || last>high || more && (last>=high || raw.isEmpty()) || !more && last!=high || reset && (!raw.isEmpty() || more))throw new IllegalArgumentException("Invalid geography revision cursor");
        boolean changedDimension=!dimension.equals(incoming);long previous=changedDimension?0:after();if(!reset && (high<previous || paging && !changedDimension && high!=through))throw new IllegalArgumentException("Inconsistent geography revision window");
        var additions=new LinkedHashMap<GeographyTile.Position,GeographyTile>();long order=previous;
        for(var value:raw){var tag=(CompoundTag)value;GeographyFiles.keys(tag,Set.of("x","z","revision","cells"));GeographyFiles.require(tag,"x",Tag.TAG_INT);GeographyFiles.require(tag,"z",Tag.TAG_INT);GeographyFiles.require(tag,"revision",Tag.TAG_LONG);GeographyFiles.require(tag,"cells",Tag.TAG_BYTE_ARRAY);
            var tile=new GeographyTile(new GeographyTile.Position(tag.getInt("x"),tag.getInt("z")),tag.getLong("revision"),tag.getByteArray("cells"));if(tile.revision()<=order || tile.revision()>high || additions.putIfAbsent(tile.position(),tile)!=null)throw new IllegalArgumentException("Repeated/invalid geography tile revision");order=tile.revision();}
        if(more && order!=last || !more && order>last)throw new IllegalArgumentException("Invalid geography page end");
        long extra=additions.keySet().stream().filter(key->changedDimension || !tiles.containsKey(key)).count();if((changedDimension?0:tiles.size())+extra>GeographyStore.MAX_TILES)throw new IllegalArgumentException("Client observed geography cache full");
        if(changedDimension || reset)clear(incoming);if(reset)return true;
        additions.forEach((position,tile)->{var old=tiles.get(position);if(old==null || old.revision()<tile.revision())tiles.put(position,tile);});through=high;next=last;paging=more;if(!more)revision=high;return more;
    }
    public int at(int blockX,int blockZ){var tile=tiles.get(GeographyTile.Position.at(blockX,blockZ));return tile==null?GeographyTile.UNKNOWN:tile.at(blockX&31,blockZ&31);}
}
