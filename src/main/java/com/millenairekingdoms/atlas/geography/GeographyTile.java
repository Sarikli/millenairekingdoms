package com.millenairekingdoms.atlas.geography;

import java.util.*;

/** One-block observed surface mask, not terrain generation. Zero means unknown, never assumed land or ocean. */
public record GeographyTile(Position position,long revision,byte[] cells) {
    public static final int SHIFT=5,SIZE=1<<SHIFT,BYTES=SIZE*SIZE/4;
    public static final int UNKNOWN=0,LAND=1,OCEAN=2,OTHER_WATER=3;
    public record Position(int x,int z){
        public Position{if(Math.abs((long)x)>937500 || Math.abs((long)z)>937500)throw new IllegalArgumentException("Geography tile outside supported world bounds");}
        public static Position at(int blockX,int blockZ){return new Position(blockX>>SHIFT,blockZ>>SHIFT);}
        public int blockX(){return x*SIZE;} public int blockZ(){return z*SIZE;}
    }
    public GeographyTile{if(position==null || revision<1 || cells==null || cells.length!=BYTES)throw new IllegalArgumentException("Invalid observed geography tile");cells=cells.clone();}
    @Override public byte[] cells(){return cells.clone();}
    public int at(int localX,int localZ){return get(cells,localX,localZ);}
    public static int get(byte[] cells,int x,int z){if(cells.length!=BYTES || x<0 || x>=SIZE || z<0 || z>=SIZE)throw new IllegalArgumentException("Invalid geography cell");int index=x+z*SIZE;return cells[index>>2]>>((index&3)*2)&3;}
    public static boolean set(byte[] cells,int x,int z,int value){if(value<UNKNOWN || value>OTHER_WATER)throw new IllegalArgumentException("Unknown surface classification");int previous=get(cells,x,z);if(previous==value)return false;int index=x+z*SIZE,shift=(index&3)*2;cells[index>>2]=(byte)((cells[index>>2]&~(3<<shift))|(value<<shift));return true;}
}
