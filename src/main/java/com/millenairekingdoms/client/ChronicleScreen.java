package com.millenairekingdoms.client;

import com.millenairekingdoms.history.*;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Dedicated chronicle, with the actual previous management screen retained as its parent. */
final class ChronicleScreen extends Screen {
    final Screen parent;
    final UUID session=UUID.randomUUID();
    UUID scope,selected,next;
    String kind="";long from=0,to=Long.MAX_VALUE,serial;
    private CompoundTag view=new CompoundTag();
    private List<HistoryEvent> events=List.of();
    private final List<UUID> previous=new ArrayList<>();
    private UUID cursor;
    private KingdomScreen.RowList entries,prose;
    private int left,top,pw,ph;
    ChronicleScreen(Screen parent,UUID scope){super(t("title"));this.parent=parent;this.scope=scope;}
    static Component t(String key,Object...args){return Component.translatable("millenairekingdoms.history."+key,args);}
    void request(UUID cursor){this.cursor=cursor;serial++;PacketDistributor.sendToServer(new ChronicleRequestPayload(session,serial,"CHRONICLE",Optional.ofNullable(scope),kind,from,to,Optional.ofNullable(cursor),"",Optional.empty()));}
    void refresh(){previous.clear();request(null);}
    void receive(CompoundTag incoming){if(!incoming.getUUID("session").equals(session)||incoming.getLong("serial")!=serial)return;view=incoming;events=incoming.getList("events",10).stream().map(t->HistoryEvent.load((CompoundTag)t)).toList();next=incoming.hasUUID("next")?incoming.getUUID("next"):null;if(events.stream().noneMatch(e->e.id().equals(selected)))selected=events.isEmpty()?null:events.getFirst().id();rebuildWidgets();}
    @Override protected void init(){
        pw=Math.min(740,width-16);ph=Math.min(420,height-16);left=(width-pw)/2;top=(height-ph)/2;int third=(pw-24)/3;
        button(left+8,top+25,third,t("kingdom"),()->{minecraft.setScreen(new ChronicleFilterScreen(this,true));});
        button(left+10+third,top+25,third,t("world"),()->{scope=null;refresh();});
        var atlas=button(left+12+2*third,top+25,third,t("atlas"),()->{});atlas.active=false;
        button(left+8,top+47,74,t("filters"),()->minecraft.setScreen(new ChronicleFilterScreen(this,false)));
        int mainTop=top+70,bottom=top+ph-48,listWidth=Math.max(96,(pw-24)*2/5);
        entries=new KingdomScreen.RowList(left+8,mainTop,listWidth,Math.max(22,bottom-mainTop),22);
        for(var e:events){String date=Component.translatable("millenairekingdoms.history.day",e.day()).getString();var account=ChronicleNarrator.render(e,scope,language());entries.add(Component.literal(date+" · "+account.heading()),0xFF493321,e.id().equals(selected),()->{selected=e.id();rebuildWidgets();});}
        prose=new KingdomScreen.RowList(left+16+listWidth,mainTop,pw-listWidth-24,Math.max(22,bottom-mainTop));
        var chosen=events.stream().filter(e->e.id().equals(selected)).findFirst().orElse(null);
        if(chosen!=null){var account=ChronicleNarrator.render(chosen,scope,language());prose.addWrapped(font,Component.literal(account.heading()),0xFF493321);prose.addWrapped(font,t("voice",account.perspective(),account.culture()),0xFF705237);prose.addWrapped(font,Component.literal(account.prose()),0xFF493321);
            for(var place:chosen.places())prose.addWrapped(font,t(place.located()?"place":"place_hidden",place.name(),place.x(),place.z()),0xFF705237);
        }else prose.addWrapped(font,t(view.contains("error")?"unavailable":"empty"),0xFF493321);
        int fy=top+ph-43,half=(pw-20)/2;button(left+8,fy,half,t("previous"),()->{var back=previous.removeLast();request(back);}).active=!previous.isEmpty();
        button(left+12+half,fy,half,t("next"),()->{previous.add(cursor);request(next);}).active=next!=null;
        int by=top+ph-22;button(left+8,by,74,Component.translatable("gui.back"),this::onClose);button(left+pw-82,by,74,t("refresh"),()->request(cursor));
    }
    private String language(){return minecraft.options.languageCode;}
    private Button button(int x,int y,int w,Component label,Runnable action){return addRenderableWidget(Button.builder(label,b->action.run()).bounds(x,y,w,18).build());}
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float tick){g.fill(left,top,left+pw,top+ph,0xFFF0DDB0);g.renderOutline(left,top,pw,ph,0xFF62442B);}
    @Override public void render(GuiGraphics g,int mx,int my,float tick){super.render(g,mx,my,tick);g.drawString(font,title,left+8,top+9,0xFF493321,false);g.drawString(font,font.plainSubstrByWidth(view.getString("name"),pw-100),left+88,top+52,0xFF705237,false);entries.render(g,font,mx,my);prose.render(g,font,mx,my);}
    @Override public boolean mouseClicked(double x,double y,int b){return super.mouseClicked(x,y,b)||b==0&&(entries.click(x,y)||prose.click(x,y));}
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy){return entries.scroll(x,y,dy)||prose.scroll(x,y,dy)||super.mouseScrolled(x,y,dx,dy);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
