package com.millenairekingdoms.client;

import com.millenairekingdoms.atlas.geography.*;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Two bounded viewport textures. One asynchronous raster job at a time; input never touches server terrain. */
final class AtlasGeographyRenderer implements AutoCloseable {
    private static final java.util.concurrent.atomic.AtomicBoolean RASTER_RUNNING=new java.util.concurrent.atomic.AtomicBoolean();
    private record Stamp(GeographyRaster.View view,long revision){}
    private Map<GeographyTile.Position,GeographyTile> observed=Map.of();private long revision,epoch,lastStart=-100;
    private Stamp displayed;private GeographyRaster.Image image;private boolean busy;
    private DynamicTexture ground,ocean;private ResourceLocation groundId,oceanId;
    void update(GeographySnapshot snapshot){var next=new HashMap<GeographyTile.Position,GeographyTile>();snapshot.tiles().forEach(tile->next.put(tile.position(),tile));observed=Map.copyOf(next);revision++;}
    void prepare(GeographyRaster.View view,long ticks){
        var wanted=new Stamp(view,revision);if(busy || wanted.equals(displayed) || ticks-lastStart<5 || !RASTER_RUNNING.compareAndSet(false,true))return;
        busy=true;lastStart=ticks;long requestedEpoch=epoch;var snapshot=observed;
        CompletableFuture.supplyAsync(()->GeographyRaster.build(snapshot,view)).whenComplete((result,failure)->Minecraft.getInstance().execute(()->{
            busy=false;RASTER_RUNNING.set(false);if(requestedEpoch!=epoch)return;if(failure!=null){com.mojang.logging.LogUtils.getLogger().warn("[Kingdoms] Geography viewport unavailable",failure);return;}
            upload(result);image=result;displayed=wanted;
        }));
    }
    private static NativeImage pixels(int width,int height,int[] colors){var pixels=new NativeImage(width,height,false);for(int z=0;z<height;z++)for(int x=0;x<width;x++){int argb=colors[x+z*width];int abgr=argb&0xFF00FF00|(argb>>16&255)|(argb&255)<<16;pixels.setPixelRGBA(x,z,abgr);}return pixels;}
    private void upload(GeographyRaster.Image image){
        var manager=Minecraft.getInstance().getTextureManager();if(ground==null || ground.getPixels()==null || ground.getPixels().getWidth()!=image.width() || ground.getPixels().getHeight()!=image.height()){
            release();ground=new DynamicTexture(image.width(),image.height(),false);ocean=new DynamicTexture(image.width(),image.height(),false);groundId=manager.register("kingdoms_observed_ground",ground);oceanId=manager.register("kingdoms_observed_ocean",ocean);ground.setFilter(false,false);ocean.setFilter(false,false);
        }
        ground.setPixels(pixels(image.width(),image.height(),image.ground()));ocean.setPixels(pixels(image.width(),image.height(),image.ocean()));ground.upload();ocean.upload();
    }
    void draw(GuiGraphics graphics,GeographyRaster.View current,int left,int top,boolean mask){
        if(image==null)return;var source=image.view();double minX=source.worldX(0),minZ=source.worldZ(0),maxX=source.worldX(source.width()),maxZ=source.worldZ(source.height());
        int x=left+(int)Math.floor(current.screenX(minX)),z=top+(int)Math.floor(current.screenZ(minZ)),right=left+(int)Math.ceil(current.screenX(maxX)),bottom=top+(int)Math.ceil(current.screenZ(maxZ));
        graphics.blit(mask?oceanId:groundId,x,z,right-x,bottom-z,0,0,image.width(),image.height(),image.width(),image.height());
    }
    private void release(){var manager=Minecraft.getInstance().getTextureManager();if(groundId!=null)manager.release(groundId);if(oceanId!=null)manager.release(oceanId);ground=null;ocean=null;groundId=null;oceanId=null;}
    @Override public void close(){epoch++;release();image=null;displayed=null;observed=Map.of();lastStart=-100;}
}
