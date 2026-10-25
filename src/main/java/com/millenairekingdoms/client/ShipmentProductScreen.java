package com.millenairekingdoms.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Client selection only: the server still validates the source/product and issues its own price/stock offer. */
final class ShipmentProductScreen extends Screen {
    private Object languageState;
    private final KingdomScreen parent;
    private final List<ResourceLocation> products=new ArrayList<>();
    private final String selected;
    private EditBox search;
    private KingdomScreen.RowList rows;
    private String query="";
    private int left,top,panelWidth,panelHeight;
    ShipmentProductScreen(KingdomScreen parent,ListTag products,String selected){
        super(Component.translatable("millenairekingdoms.gui.shipment.choose_product"));this.parent=parent;this.selected=selected;
        for(int n=0;n<Math.min(128,products.size());n++){
            var id=ResourceLocation.tryParse(products.getCompound(n).getString("id"));
            if(id!=null && BuiltInRegistries.ITEM.containsKey(id))this.products.add(id);
        }
    }
    @Override protected void init(){
        languageState=net.minecraft.locale.Language.getInstance();
        panelWidth=Math.min(360,width-16);panelHeight=Math.min(260,height-16);left=(width-panelWidth)/2;top=(height-panelHeight)/2;
        search=new EditBox(font,left+12,top+28,panelWidth-24,18,Component.translatable("millenairekingdoms.gui.shipment.search_product"));
        search.setMaxLength(128);search.setHint(Component.translatable("millenairekingdoms.gui.shipment.search_product"));search.setValue(query);
        search.setResponder(value->{query=value;rebuildRows();});addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),b->onClose()).bounds(left+panelWidth-82,top+panelHeight-24,70,18).build());
        rebuildRows();setInitialFocus(search);
    }
    private void rebuildRows(){
        rows=new KingdomScreen.RowList(left+12,top+52,panelWidth-24,Math.max(20,panelHeight-84),20);
        String needle=query.strip().toLowerCase(Locale.ROOT);
        for(var id:products){var item=BuiltInRegistries.ITEM.get(id);var stack=new ItemStack(item);var name=stack.getHoverName();
            if(!name.getString().toLowerCase(Locale.ROOT).contains(needle) && !id.toString().contains(needle))continue;
            rows.addIcon(name,id.toString().equals(selected),()->{minecraft.setScreen(parent);parent.chooseShipmentProduct(id.toString());},stack);
        }
    }
    @Override public void renderBackground(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        g.fill(left,top,left+panelWidth,top+panelHeight,0xFFF0DDB0);g.renderOutline(left,top,panelWidth,panelHeight,0xFF62442B);
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        super.render(g,mouseX,mouseY,partialTick);g.drawString(font,title,left+12,top+10,0xFF493321,false);rows.render(g,font,mouseX,mouseY);
        if(rows.rows.isEmpty())g.drawString(font,Component.translatable("millenairekingdoms.gui.shipment.no_product_match"),left+16,top+58,0xFF493321,false);
    }
    @Override public boolean mouseClicked(double x,double y,int button){return super.mouseClicked(x,y,button) || button==0 && rows.click(x,y);}
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy){return rows.scroll(x,y,dy) || super.mouseScrolled(x,y,dx,dy);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void tick(){super.tick();if(languageState!=net.minecraft.locale.Language.getInstance()){languageState=net.minecraft.locale.Language.getInstance();rebuildWidgets();}}
    @Override public boolean isPauseScreen(){return false;}
}
