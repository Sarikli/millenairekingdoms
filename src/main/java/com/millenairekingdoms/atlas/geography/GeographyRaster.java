package com.millenairekingdoms.atlas.geography;

import java.util.*;

/** Pure, bounded viewport raster over immutable observed masks. It has no level, chunks or network access. */
public final class GeographyRaster {
    public static final int MAX_EDGE=1024;
    public static final int LAND_COLOR=0xFFB7B38A,OCEAN_COLOR=0xFF347CB5,OTHER_WATER_COLOR=0xFF79ADB3,COAST_COLOR=0xFFAFD4D5;
    public record View(double centerX,double centerZ,double zoom,int width,int height){
        public View{if(!Double.isFinite(centerX) || !Double.isFinite(centerZ) || !Double.isFinite(zoom) || zoom<.01 || zoom>4 || width<1 || height<1 || width>16384 || height>16384)throw new IllegalArgumentException("Invalid geography viewport");}
        public double worldX(double pixel){return centerX+(pixel-width/2.0)/zoom;}
        public double worldZ(double pixel){return centerZ+(pixel-height/2.0)/zoom;}
        public double screenX(double block){return width/2.0+(block-centerX)*zoom;}
        public double screenZ(double block){return height/2.0+(block-centerZ)*zoom;}
    }
    public record Image(View view,int width,int height,int[] ground,int[] ocean){
        public Image{if(width<1 || height<1 || width>MAX_EDGE || height>MAX_EDGE || ground.length!=width*height || ocean.length!=width*height)throw new IllegalArgumentException("Invalid geography raster bounds");}
    }
    private GeographyRaster(){}
    public static int at(Map<GeographyTile.Position,GeographyTile> tiles,int x,int z){
        if(Math.abs((long)x)>29999983 || Math.abs((long)z)>29999983)return GeographyTile.UNKNOWN;
        var tile=tiles.get(GeographyTile.Position.at(x,z));return tile==null?GeographyTile.UNKNOWN:tile.at(x&31,z&31);
    }
    /** Coast edges exist only between adjacent observed ocean/land cells. Unknown and inland water never invent a coast. */
    public static int coastEdges(Map<GeographyTile.Position,GeographyTile> tiles,int x,int z){
        if(at(tiles,x,z)!=GeographyTile.OCEAN)return 0;int edges=0;
        if(at(tiles,x-1,z)==GeographyTile.LAND)edges|=1;if(at(tiles,x+1,z)==GeographyTile.LAND)edges|=2;
        if(at(tiles,x,z-1)==GeographyTile.LAND)edges|=4;if(at(tiles,x,z+1)==GeographyTile.LAND)edges|=8;return edges;
    }
    public static Image build(Map<GeographyTile.Position,GeographyTile> tiles,View view){
        double scale=Math.min(1,MAX_EDGE/(double)Math.max(view.width(),view.height()));int width=Math.max(1,(int)Math.ceil(view.width()*scale)),height=Math.max(1,(int)Math.ceil(view.height()*scale));
        var ground=new int[width*height];var ocean=new int[width*height];
        for(int row=0;row<height;row++)for(int col=0;col<width;col++){
            double wx=view.worldX((col+.5)*view.width()/width),wz=view.worldZ((row+.5)*view.height()/height);if(wx<Integer.MIN_VALUE || wx>Integer.MAX_VALUE || wz<Integer.MIN_VALUE || wz>Integer.MAX_VALUE)continue;
            int x=(int)Math.floor(wx),z=(int)Math.floor(wz),value=at(tiles,x,z),index=col+row*width;
            ground[index]=switch(value){case GeographyTile.LAND->LAND_COLOR;case GeographyTile.OCEAN->OCEAN_COLOR;case GeographyTile.OTHER_WATER->OTHER_WATER_COLOR;default->0;};
            if(value==GeographyTile.OCEAN){
                int color=OCEAN_COLOR;
                // A coast is a true one-block boundary; at subpixel zoom omit its stroke rather than inventing geography.
                if(view.zoom()*scale>=.25){int edges=coastEdges(tiles,x,z);double stroke=Math.min(.5,1/(view.zoom()*scale)),fx=wx-x,fz=wz-z;
                    if((edges&1)!=0 && fx<stroke || (edges&2)!=0 && 1-fx<stroke || (edges&4)!=0 && fz<stroke || (edges&8)!=0 && 1-fz<stroke)color=COAST_COLOR;
                }
                ocean[index]=color;
            }
        }
        return new Image(view,width,height,ground,ocean);
    }
}
