package com.millenairekingdoms.client;

import com.millenairekingdoms.atlas.AtlasGeometry;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** Independent full-screen atlas. No terrain requests, world mutation or external mapping mod. */
final class AtlasScreen extends Screen {
    private Object languageState;
    private record Pending(String mode,UUID kingdom,UUID cursor,int generation){}
    private final Screen parent;private final UUID session=UUID.randomUUID(),event,village;
    private final Map<UUID,CompoundTag> sites=new LinkedHashMap<>(),kingdoms=new LinkedHashMap<>(),wars=new LinkedHashMap<>();
    private final ArrayDeque<Pending> queue=new ArrayDeque<>();private final Map<Long,Pending> waiting=new HashMap<>();
    private long serial,ticks,lastSent=-100,lastRefresh;private int generation,restarts,radius=500;private String revision="",dimension="minecraft:overworld",requestedDimension="",status="loading";
    private final com.millenairekingdoms.atlas.AtlasSnapshotAssembler assembler=new com.millenairekingdoms.atlas.AtlasSnapshotAssembler();
    private final Map<UUID,CompoundTag> capitals=new HashMap<>();
    private AtlasGeometry.Mesh mesh;private CompoundTag info=new CompoundTag(),pin=new CompoundTag();private UUID selectedKingdom,selectedVillage;
    private double centerX,centerZ,zoom=.09;private boolean centered,showPanel=true,showInfo,dragging;
    private UUID paneRealm;private boolean paneInfo;
    private KingdomScreen.RowList rows;private int mapLeft,mapTop,mapRight,mapBottom,panelX,panelWidth;
    AtlasScreen(Screen parent,UUID kingdom,UUID event,UUID village){super(t("title"));this.parent=parent;this.selectedKingdom=kingdom;this.event=event;this.village=village;this.showInfo=kingdom!=null;}
    static Component t(String key,Object...args){return ClientText.component("millenairekingdoms.atlas."+key,args);}
    void refresh(){generation++;queue.clear();waiting.clear();restarts=0;status="loading";queue.add(new Pending("ATLAS",null,null,generation));}
    @Override public void tick(){
        ticks++;
        if(minecraft.level!=null && !requestedDimension.equals(minecraft.level.dimension().location().toString())){
            requestedDimension=minecraft.level.dimension().location().toString();mesh=null;sites.clear();kingdoms.clear();wars.clear();capitals.clear();info=new CompoundTag();pin=new CompoundTag();centered=false;refresh();
        }
        if(languageState!=net.minecraft.locale.Language.getInstance()){languageState=net.minecraft.locale.Language.getInstance();rebuildWidgets();}if((status.equals("ready") || status.equals("empty")) && queue.isEmpty() && waiting.isEmpty() && ticks-lastRefresh>=100)refresh();if((status.equals("ready") || status.equals("empty")) && ticks-lastRefresh>=100 && waiting.isEmpty() && !queue.isEmpty() && queue.stream().noneMatch(p->p.mode.equals("ATLAS"))){queue.addFirst(new Pending("ATLAS",null,null,generation));lastRefresh=ticks;}
        if(ticks-lastSent<5 || queue.isEmpty())return;lastSent=ticks;var p=queue.remove();if(p.generation!=generation)return;long id=++serial;waiting.put(id,p);
        PacketDistributor.sendToServer(new ChronicleRequestPayload(session,id,p.mode,Optional.ofNullable(p.kingdom),"",0,Long.MAX_VALUE,Optional.ofNullable(p.cursor),"",Optional.ofNullable(event),Optional.ofNullable(village)));
    }
    void receive(CompoundTag view){
        if(!view.hasUUID("session") || !view.getUUID("session").equals(session))return;var pending=waiting.remove(view.getLong("serial"));if(pending==null || pending.generation!=generation)return;
        if(view.getInt("retryAfter")>0){lastSent=ticks+Math.max(0,Math.min(200,view.getInt("retryAfter"))-5);queue.addFirst(pending);return;}
        if(view.contains("error")){status="unavailable";return;}
        if(pending.mode.equals("INFO")){
            if(!pending.kingdom.equals(selectedKingdom))return;var incoming=view.getCompound("info");boolean same=info.hasUUID("id") && info.getUUID("id").equals(pending.kingdom) && incoming.getString("memberRevision").equals(info.getString("memberRevision"));
            if(pending.cursor!=null && !same){queue.add(new Pending("INFO",selectedKingdom,null,generation));return;}
            if(same){var oldMembers=info.getList("members",10);var fresh=incoming.getList("members",10);if(pending.cursor!=null){var ids=new HashSet<UUID>();for(var raw:oldMembers)ids.add(((CompoundTag)raw).getUUID("id"));for(var raw:fresh)if(ids.add(((CompoundTag)raw).getUUID("id")))oldMembers.add(raw.copy());if(oldMembers.size()>com.millenairekingdoms.atlas.AtlasDiscovery.LIMIT){status="unavailable";return;}incoming.put("members",oldMembers);}else if(oldMembers.size()>fresh.size()){incoming.put("members",oldMembers);if(info.hasUUID("next"))incoming.putUUID("next",info.getUUID("next"));else incoming.remove("next");}}
            info=incoming;rebuildWidgets();return;
        }

        if(pending.cursor==null && mesh!=null && view.getString("revision").equals(revision)){pin=view.getCompound("historicalPin").copy();status=sites.isEmpty()?"empty":"ready";lastRefresh=ticks;if(selectedKingdom!=null)queue.add(new Pending("INFO",selectedKingdom,null,generation));return;}
        com.millenairekingdoms.atlas.AtlasSnapshotAssembler.Frame frame;
        try{frame=assembler.accept(view,pending.cursor);}catch(com.millenairekingdoms.atlas.AtlasSnapshotAssembler.Changed changed){if(++restarts>3){status="changed";return;}generation++;queue.clear();waiting.clear();queue.add(new Pending("ATLAS",null,null,generation));return;}catch(IllegalArgumentException malformed){status="unavailable";return;}
        if(frame==null){queue.add(new Pending("ATLAS",null,view.getUUID("next"),generation));return;}
        var newSites=frame.settlements();var newKingdoms=frame.kingdoms();var newWars=frame.wars();var newPin=frame.pin();
        var geometry=newSites.stream().map(s->new AtlasGeometry.Site(s.getUUID("id"),s.hasUUID("kingdom")?s.getUUID("kingdom"):null,s.getInt("x"),s.getInt("z"))).toList();int epoch=generation;
        CompletableFuture.supplyAsync(()->AtlasGeometry.cached(frame.dimension(),frame.radius(),geometry)).whenComplete((result,error)->minecraft.execute(()->{
            if(epoch!=generation)return;if(error!=null){status="geometry_unavailable";return;}
            if(!dimension.equals(frame.dimension()))centered=false;
            sites.clear();kingdoms.clear();wars.clear();capitals.clear();newSites.forEach(s->{sites.put(s.getUUID("id"),s);if(s.getBoolean("capital") && s.hasUUID("kingdom"))capitals.put(s.getUUID("kingdom"),s);});newKingdoms.forEach(k->kingdoms.put(k.getUUID("id"),k));newWars.forEach(w->wars.put(w.getUUID("id"),w));pin=newPin;revision=frame.revision();dimension=frame.dimension();radius=frame.radius();mesh=result;
            if(!centered){var focus=sites.values().stream().filter(site->selectedKingdom!=null && site.hasUUID("kingdom") && site.getUUID("kingdom").equals(selectedKingdom)).sorted(Comparator.comparing(site->!site.getBoolean("capital"))).findFirst().orElse(sites.values().stream().findFirst().orElse(null));if(focus!=null){centerX=focus.getInt("x");centerZ=focus.getInt("z");}else{centerX=view.getInt("playerX");centerZ=view.getInt("playerZ");}if(!pin.isEmpty()){centerX=pin.getInt("x");centerZ=pin.getInt("z");}centered=true;}
            if(selectedVillage!=null && sites.containsKey(selectedVillage)){var selected=sites.get(selectedVillage);selectedKingdom=selected.hasUUID("kingdom")?selected.getUUID("kingdom"):null;}
            status=sites.isEmpty()?"empty":"ready";lastRefresh=ticks;rebuildWidgets();if(selectedKingdom!=null)queue.add(new Pending("INFO",selectedKingdom,null,generation));
        }));
    }

    private static void collect(Map<UUID,CompoundTag> map,CompoundTag view,String key){for(var raw:view.getList(key,10)){var tag=(CompoundTag)raw;if(tag.hasUUID("id"))map.put(tag.getUUID("id"),tag);}}
    @Override protected void init(){
        requestedDimension=minecraft.level==null?"":minecraft.level.dimension().location().toString();
        languageState=net.minecraft.locale.Language.getInstance();
        int retainedScroll=rows!=null && Objects.equals(paneRealm,selectedKingdom) && paneInfo==showInfo?rows.scroll:0;paneRealm=selectedKingdom;paneInfo=showInfo;
        panelWidth=Math.min(210,Math.max(104,width/3));mapLeft=6;mapTop=46;mapRight=width-6-(showPanel?panelWidth+8:0);mapBottom=height-26;panelX=mapRight+8;
        button(6,6,54,Component.translatable("gui.back"),this::onClose);button(64,6,84,ChronicleScreen.t("title"),()->HistoryClient.open(this,selectedKingdom));button(152,6,64,ChronicleScreen.t("refresh"),this::refresh);button(220,6,Math.min(94,width-226),t(showPanel?"hide_panel":"show_panel"),()->{showPanel=!showPanel;rebuildWidgets();});
        rows=null;if(!showPanel)return;int half=(panelWidth-4)/2;button(panelX,mapTop,half,t("kingdoms"),()->{showInfo=false;rebuildWidgets();});button(panelX+half+4,mapTop,half,t("information"),()->{showInfo=true;rebuildWidgets();});
        rows=new KingdomScreen.RowList(panelX,mapTop+23,panelWidth,Math.max(22,mapBottom-mapTop-69),showInfo?11:20);
        if(showInfo)buildInfo();else for(var k:kingdoms.values().stream().sorted(Comparator.comparing(k->k.getString("name"))).toList())rows.add(com.millenairekingdoms.localization.PublicText.of("%s", k.getString("name")),k.getInt("color"),k.getUUID("id").equals(selectedKingdom),()->selectKingdom(k.getUUID("id"),true));
        if(!showInfo)rows.add(t("independent"),0xFF625E51,selectedKingdom==null,()->{selectedKingdom=null;selectedVillage=null;info=new CompoundTag();rebuildWidgets();});
        rows.scroll=retainedScroll;
        button(panelX,mapBottom-41,panelWidth,t(selectedVillage==null?"chronicle":"village_history"),()->HistoryClient.open(this,selectedKingdom,selectedVillage));
        if(showInfo && info.hasUUID("next"))button(panelX,mapBottom-20,panelWidth,t("more_members"),()->queue.add(new Pending("INFO",selectedKingdom,info.getUUID("next"),generation)));
    }
    private void buildInfo(){
        if(info.isEmpty()){
            if(selectedVillage!=null && sites.containsKey(selectedVillage)){var site=sites.get(selectedVillage);line(com.millenairekingdoms.localization.PublicText.of("%s", site.getString("name")));line(t("independent"));line(com.millenairekingdoms.localization.PublicText.of("%s", site.getString("typeName")));}else line(t("select"));return;
        }
        line(com.millenairekingdoms.localization.PublicText.of("%s", info.getString("name")));line(t("culture",ClientText.culture(info.getString("culture"),info.getString("cultureName"))));line(Component.translatable("millenairekingdoms.gui.personality",Component.translatable("millenairekingdoms.gui.personality."+info.getString("ethos"))));
        line(info.contains("founded")?ChronicleScreen.t("founded",info.getLong("founded")):ChronicleScreen.t("founding_unknown"));
        if(info.getBoolean("fallen"))line(ChronicleScreen.t("fallen"));else{
            line(t("capital",info.getString("capital")));line(t("members",info.getInt("memberCount")));line(info.getString("ruler").isBlank()?t("npc_government"):t("ruler",info.getString("ruler")));line(info.getBoolean("war")?t("war",info.getString("opponent")):t("peace"));
            if(info.getBoolean("economicRights")){line(t("income",info.getLong("income")));line(t("owed",info.getLong("owed")));line(info.getBoolean("storageLoaded")?t("treasury",info.getLong("treasury")):t("storage_unloaded"));}
            for(var raw:info.getList("members",10)){var member=(CompoundTag)raw;rows.add(com.millenairekingdoms.localization.PublicText.of("%s", member.getString("name")),0xFF493321,false,()->{centerX=member.getInt("x");centerZ=member.getInt("z");selectedVillage=member.getUUID("id");});rows.addWrapped(font,com.millenairekingdoms.localization.PublicText.of("%s", member.getString("typeName")),0xFF705237);}
        }
    }
    private void line(Component text){rows.addWrapped(font,text,0xFF493321);}
    private Button button(int x,int y,int w,Component text,Runnable action){return addRenderableWidget(Button.builder(text,b->action.run()).bounds(x,y,Math.max(20,w),18).build());}
    private void selectKingdom(UUID id,boolean center){selectedKingdom=id;selectedVillage=null;info=new CompoundTag();showInfo=true;if(center){var capital=sites.values().stream().filter(s->s.hasUUID("kingdom") && s.getUUID("kingdom").equals(id)).sorted(Comparator.comparing(s->!s.getBoolean("capital"))).findFirst().orElse(null);if(capital!=null){centerX=capital.getInt("x");centerZ=capital.getInt("z");}}queue.add(new Pending("INFO",id,null,generation));rebuildWidgets();}
    private float sx(double x){return (float)((mapLeft+mapRight)/2.0+(x-centerX)*zoom);}
    private float sy(double z){return (float)((mapTop+mapBottom)/2.0+(z-centerZ)*zoom);}
    private double wx(double x){return centerX+(x-(mapLeft+mapRight)/2.0)/zoom;}
    private double wz(double y){return centerZ+(y-(mapTop+mapBottom)/2.0)/zoom;}
    private boolean inside(double x,double y){return x>=mapLeft && x<mapRight && y>=mapTop && y<mapBottom;}
    private CompoundTag hover(double x,double y){if(!inside(x,y))return null;CompoundTag nearest=null;double best=144;for(var site:sites.values()){double dx=sx(site.getInt("x"))-x,dy=sy(site.getInt("z"))-y,d=dx*dx+dy*dy;if(d<best){best=d;nearest=site;}}return nearest;}
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float tick){
        g.fill(0,0,width,height,0xFFF0DDB0);g.fill(mapLeft,mapTop,mapRight,mapBottom,0xFFCEC29E);g.enableScissor(mapLeft,mapTop,mapRight,mapBottom);
        if(mesh!=null){
            for(var cell:mesh.cells())if(cell.polygon().size()>=3 && sx(cell.site().x()+radius)>=mapLeft && sx(cell.site().x()-radius)<=mapRight && sy(cell.site().z()+radius)>=mapTop && sy(cell.site().z()-radius)<=mapBottom){int color=AtlasGeometry.color(cell.site().kingdom());if(selectedKingdom!=null && !selectedKingdom.equals(cell.site().kingdom()))color=shade(color,.68);color=color&0x00FFFFFF|0xFF000000;float x=sx(cell.site().x()),y=sy(cell.site().z());var polygon=cell.polygon();for(int n=0;n<polygon.size();n++){var a=polygon.get(n);var b=polygon.get((n+1)%polygon.size());quad(g,x,y,sx(a.x()),sy(a.z()),sx(b.x()),sy(b.z()),x,y,color);}}
            for(var edge:mesh.borders())line(g,sx(edge.start().x()),sy(edge.start().z()),sx(edge.end().x()),sy(edge.end().z()),selectedKingdom!=null && selectedKingdom.equals(edge.kingdom())?2:1,0xFF493321);
        }
        g.flush();
        for(var war:wars.values()){var a=capital(war.getUUID("attacker"));var b=capital(war.getUUID("defender"));if(a!=null && b!=null)line(g,sx(a.getInt("x")),sy(a.getInt("z")),sx(b.getInt("x")),sy(b.getInt("z")),1.5f,0xFF9C2921);}
        g.flush();
        for(var site:sites.values()){
            int x=Math.round(sx(site.getInt("x"))),y=Math.round(sy(site.getInt("z")));if(!inside(x,y))continue;
            boolean capital=site.getBoolean("capital"),selected=selectedVillage!=null && selectedVillage.equals(site.getUUID("id"));
            if(zoom<.025 && !capital && !selected){g.fill(x-2,y-2,x+2,y+2,0xFF493321);continue;}
            if(capital || selected){g.fill(x-10,y-10,x+10,y+10,capital?0xFFE4BB50:0xFFFFFFFF);g.renderOutline(x-10,y-10,20,20,0xFF493321);}
            var icon=ResourceLocation.tryParse(site.getString("icon"));g.renderItem(new ItemStack(icon!=null && BuiltInRegistries.ITEM.containsKey(icon)?BuiltInRegistries.ITEM.get(icon):Items.MAP),x-8,y-8);
            if(capital && site.hasUUID("kingdom") && kingdoms.containsKey(site.getUUID("kingdom")))g.drawCenteredString(font,kingdoms.get(site.getUUID("kingdom")).getString("name"),x,y-21,0xFFFFFFFF);
        }
        if(!pin.isEmpty()){int x=Math.round(sx(pin.getInt("x"))),y=Math.round(sy(pin.getInt("z")));g.renderOutline(x-12,y-12,24,24,0xFFFFFF66);g.drawCenteredString(font,t("historical_pin",pin.getString("name"),pin.getLong("day")),x,y+14,0xFFFFFF66);}
        g.disableScissor();g.renderOutline(mapLeft,mapTop,mapRight-mapLeft,mapBottom-mapTop,0xFF62442B);
    }
    private CompoundTag capital(UUID kingdom){return capitals.get(kingdom);}
    private static int shade(int color,double multiplier){return 0xFF000000|((int)((color>>16&255)*multiplier)<<16)|((int)((color>>8&255)*multiplier)<<8)|(int)((color&255)*multiplier);}
    private static void quad(GuiGraphics g,float ax,float ay,float bx,float by,float cx,float cy,float dx,float dy,int color){var pose=g.pose().last().pose();VertexConsumer vertices=g.bufferSource().getBuffer(RenderType.gui());vertices.addVertex(pose,ax,ay,0).setColor(color);vertices.addVertex(pose,bx,by,0).setColor(color);vertices.addVertex(pose,cx,cy,0).setColor(color);vertices.addVertex(pose,dx,dy,0).setColor(color);}
    private static void line(GuiGraphics g,float ax,float ay,float bx,float by,float width,int color){double len=Math.hypot(bx-ax,by-ay);if(len<.01)return;float dx=(float)((by-ay)/len*width/2),dy=(float)(-(bx-ax)/len*width/2);quad(g,ax+dx,ay+dy,bx+dx,by+dy,bx-dx,by-dy,ax-dx,ay-dy,color);}
    @Override public void render(GuiGraphics g,int mx,int my,float tick){super.render(g,mx,my,tick);g.drawString(font,net.minecraft.locale.Language.getInstance().getVisualOrder(font.substrByWidth(title,Math.max(10,width-16))),8,30,0xFF493321,false);g.drawString(font,font.plainSubstrByWidth(ChronicleScreen.t("current_map").getString(),width-16),8,height-17,0xFF493321,false);if(!status.equals("ready"))g.drawString(font,t(status),mapLeft+8,mapTop+8,0xFF493321,false);if(rows!=null)rows.render(g,font,mx,my);
        var hovered=hover(mx,my);if(hovered!=null)g.renderTooltip(font,List.of(com.millenairekingdoms.localization.PublicText.of("%s", hovered.getString("name")),com.millenairekingdoms.localization.PublicText.of("%s", hovered.getString("typeName")),t(hovered.getBoolean("capital")?"capital_marker":"village_marker")),Optional.empty(),mx,my);
    }
    @Override public boolean mouseClicked(double x,double y,int button){if(super.mouseClicked(x,y,button))return true;if(button==0 && rows!=null && rows.click(x,y))return true;if(button==0 && inside(x,y)){
        var hovered=hover(x,y);if(hovered!=null){UUID village=hovered.getUUID("id");if(hovered.hasUUID("kingdom"))selectKingdom(hovered.getUUID("kingdom"),false);else{selectedKingdom=null;info=new CompoundTag();showInfo=true;}selectedVillage=village;rebuildWidgets();}
        else{var point=new AtlasGeometry.Point(wx(x),wz(y));if(mesh!=null)for(var cell:mesh.cells())if(cell.site().kingdom()!=null && AtlasGeometry.contains(cell.polygon(),point)){selectKingdom(cell.site().kingdom(),false);break;}dragging=true;}return true;
        }return false;}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(button==0 && dragging){centerX-=dx/zoom;centerZ-=dy/zoom;return true;}return super.mouseDragged(x,y,button,dx,dy);}
    @Override public boolean mouseReleased(double x,double y,int button){dragging=false;return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy){if(rows!=null && rows.scroll(x,y,dy))return true;if(inside(x,y)){double beforeX=wx(x),beforeZ=wz(y);zoom=Math.max(.01,Math.min(4,zoom*Math.pow(1.2,dy)));centerX=beforeX-(x-(mapLeft+mapRight)/2.0)/zoom;centerZ=beforeZ-(y-(mapTop+mapBottom)/2.0)/zoom;return true;}return super.mouseScrolled(x,y,dx,dy);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    Screen previous(){return parent;}
    @Override public boolean isPauseScreen(){return false;}
}
