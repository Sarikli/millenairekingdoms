package com.millenairekingdoms.client;

import com.millenairekingdoms.history.HistoryEvent;
import com.millenairekingdoms.history.ChronicleNarrator;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Separate compact filter/archive chooser keeps the reading pane usable at small GUI sizes. */
final class ChronicleFilterScreen extends Screen {
    private final ChronicleScreen parent;private final UUID session=UUID.randomUUID();private long serial;private UUID next;
    private final boolean realms;private KingdomScreen.RowList rows;private EditBox from,to,search;private int left,top,pw,ph;private CompoundTag response=new CompoundTag();
    private String chosenKind;private ChronicleRequestPayload pending;private int retryTicks;
    ChronicleFilterScreen(ChronicleScreen parent,boolean realms){super(ChronicleScreen.t(realms?"choose_kingdom":"filters"));this.parent=parent;this.realms=realms;this.chosenKind=parent.kind;}
    @Override protected void init(){
        pw=Math.min(440,width-16);ph=Math.min(300,height-16);left=(width-pw)/2;top=(height-ph)/2;
        if(realms){search=new EditBox(font,left+8,top+27,pw-92,18,ChronicleScreen.t("search"));search.setMaxLength(64);addRenderableWidget(search);button(left+pw-80,top+27,72,ChronicleScreen.t("search"),()->request(null));}
        else{
            from=new EditBox(font,left+8,top+27,(pw-24)/2,18,ChronicleScreen.t("from"));to=new EditBox(font,left+16+(pw-24)/2,top+27,(pw-24)/2,18,ChronicleScreen.t("to"));
            from.setMaxLength(19);to.setMaxLength(19);from.setHint(ChronicleScreen.t("from"));to.setHint(ChronicleScreen.t("to"));from.setValue(parent.from==0?"":Long.toString(parent.from));to.setValue(parent.to==Long.MAX_VALUE?"":Long.toString(parent.to));addRenderableWidget(from);addRenderableWidget(to);
        }
        rebuildRows();button(left+8,top+ph-23,72,Component.translatable("gui.back"),this::onClose);
        if(realms){button(left+pw-80,top+ph-23,72,ChronicleScreen.t("next"),()->request(next)).active=next!=null;if(response.isEmpty())request(null);}
        else button(left+pw-80,top+ph-23,72,ChronicleScreen.t("apply"),()->{try{long start=from.getValue().isBlank()?0:Long.parseLong(from.getValue());long end=to.getValue().isBlank()?Long.MAX_VALUE:Long.parseLong(to.getValue());if(start<0||end<start)return;parent.from=start;parent.to=end;parent.kind=chosenKind;onClose();parent.refresh();}catch(NumberFormatException ignored){}});
    }
    private void rebuildRows(){rows=new KingdomScreen.RowList(left+8,top+51,pw-16,Math.max(22,ph-81),22);
        rows.add(ChronicleScreen.t("all"),0xFF493321,!realms && chosenKind.isEmpty(),()->{if(realms){parent.scope=null;parent.villageScope=null;onClose();parent.refresh();}else{chosenKind="";rebuildRows();}});
        if(realms)for(var raw:response.getList("realms",10)){var r=(CompoundTag)raw;rows.add(Component.literal(r.getString("name")+(r.getBoolean("fallen")?" †":"")),0xFF493321,parent.scope!=null && parent.scope.equals(r.getUUID("id")),()->{parent.scope=r.getUUID("id");parent.villageScope=null;onClose();parent.refresh();});}
        else for(var kind:HistoryEvent.Kind.values())rows.add(Component.literal(ChronicleNarrator.title(kind,ClientText::resolve)),0xFF493321,chosenKind.equals(kind.name()),()->{chosenKind=kind.name();rebuildRows();});
    }
    private void request(UUID cursor){serial++;retryTicks=0;pending=new ChronicleRequestPayload(session,serial,"REALMS",Optional.empty(),"",0,Long.MAX_VALUE,Optional.ofNullable(cursor),search.getValue(),Optional.empty());PacketDistributor.sendToServer(pending);}
    @Override public void tick(){if(retryTicks>0 && --retryTicks==0 && pending!=null)PacketDistributor.sendToServer(pending);}
    void receive(CompoundTag view){if(!view.getUUID("session").equals(session)||view.getLong("serial")!=serial)return;if(view.getInt("retryAfter")>0){retryTicks=Math.min(20,view.getInt("retryAfter"));return;}pending=null;String value=search.getValue();response=view;next=view.hasUUID("next")?view.getUUID("next"):null;rebuildWidgets();search.setValue(value);}
    private Button button(int x,int y,int w,Component label,Runnable action){return addRenderableWidget(Button.builder(label,b->action.run()).bounds(x,y,w,18).build());}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float tick){g.fill(left,top,left+pw,top+ph,0xFFF0DDB0);g.renderOutline(left,top,pw,ph,0xFF62442B);}
    @Override public void render(GuiGraphics g,int x,int y,float tick){super.render(g,x,y,tick);g.drawString(font,title,left+8,top+9,0xFF493321,false);rows.render(g,font,x,y);}
    @Override public boolean mouseClicked(double x,double y,int b){return super.mouseClicked(x,y,b)||b==0&&rows.click(x,y);}
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy){return rows.scroll(x,y,dy)||super.mouseScrolled(x,y,dx,dy);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    Screen previous(){return parent;}
    @Override public boolean isPauseScreen(){return false;}
}
