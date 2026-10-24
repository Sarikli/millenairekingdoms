package com.millenairekingdoms.client;

import com.millenairekingdoms.network.KingdomActionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/** The kingdom screen uses Millénaire's own parchment and the server's snapshot. It decides nothing:
 * buttons only send requests, disabled buttons only mirror what the server reported, and every answer comes back as a
 * new snapshot. Millénaire's own village and chief screens are untouched. */
public final class KingdomScreen extends Screen {
    private static final ResourceLocation PARCHMENT = ResourceLocation.fromNamespaceAndPath("millenaire", "textures/gui/village_chief.png");
    // Millénaire's parchment palette (PanelRenderHelper).
    static final int TITLE = 0xFF2A1A00, TEXT = 0xFF333333, MUTED = 0xFF6E5B3E, GOOD = 0xFF2B6A1E, BAD = 0xFF8A1C1C, LINE = 0xFFAA8866;
    enum Tab { KINGDOM, MILITARY, DIPLOMACY, ECONOMY, RULERSHIP, CONSTRUCTION, SHIPMENTS }
    private static Tab lastTab = Tab.KINGDOM;

    private CompoundTag view;
    private Tab tab = lastTab;
    private UUID selectedTarget, selectedSource, selectedKingdom, selectedRequest, joinKingdom, enemyKingdom;
    private String amount = "", kingdomName = "";
    private boolean confirmAbdicate;
    private boolean confirmConstructionCancel;
    private String selectedConstructionPlan;
    private String shipmentQuantity="";
    private UUID selectedShipmentOrder;
    private boolean confirmShipmentCancel;
    private int left, top, panelWidth, panelHeight;
    private final List<Text> texts = new ArrayList<>();
    private final List<RowList> lists = new ArrayList<>();
    private EditBox amountBox, nameBox, shipmentQuantityBox;
    /** Scroll positions survive the rebuild after each click or snapshot. */
    private final java.util.Map<String, Integer> scrolls = new java.util.HashMap<>();
    private Tab listTab = tab;

    private record Text(int x, int y, Component text, int color, int width) {}

    KingdomScreen(CompoundTag view) {
        super(Component.translatable("millenairekingdoms.gui.title"));
        this.view = view;
    }

    void update(CompoundTag next) {
        if (amountBox != null) amount = amountBox.getValue();
        if (nameBox != null) kingdomName = nameBox.getValue();
        if (shipmentQuantityBox != null) shipmentQuantity=shipmentQuantityBox.getValue();
        var oldShipping=view.getCompound("shipments");var newShipping=next.getCompound("shipments");
        if(oldShipping.getInt("quantity")!=newShipping.getInt("quantity"))shipmentQuantity=Integer.toString(newShipping.getInt("quantity"));
        if(!oldShipping.hasUUID("source") || !newShipping.hasUUID("source") || !oldShipping.getUUID("source").equals(newShipping.getUUID("source"))){selectedShipmentOrder=null;confirmShipmentCancel=false;}
        var previous=view.getCompound("construction");var current=next.getCompound("construction");
        if(!previous.hasUUID("village") || !current.hasUUID("village") || !previous.getUUID("village").equals(current.getUUID("village"))) {
            selectedConstructionPlan=null;confirmConstructionCancel=false;
        }
        var oldOrder=previous.getCompound("order");var newOrder=current.getCompound("order");
        if(!oldOrder.hasUUID("id") || !newOrder.hasUUID("id") || !oldOrder.getUUID("id").equals(newOrder.getUUID("id"))) confirmConstructionCancel=false;
        this.view = next;
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- layout

    @Override protected void init() {
        for (int i = 0; i < lists.size(); i++) scrolls.put(listTab + ":" + i, lists.get(i).scroll);
        listTab = tab;
        texts.clear(); lists.clear(); amountBox = null; nameBox = null; shipmentQuantityBox=null;
        panelWidth = Math.min(420, width - 16); panelHeight = Math.min(250, height - 16);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        int tabWidth = (panelWidth - 24) / Tab.values().length;
        for (Tab t : Tab.values()) {
            Button b = Button.builder(Component.translatable("millenairekingdoms.gui.tab." + t.name().toLowerCase(java.util.Locale.ROOT)), x -> { tab = t; lastTab = t; confirmAbdicate = false; confirmConstructionCancel=false;confirmShipmentCancel=false; rebuildWidgets(); })
                    .bounds(left + 12 + t.ordinal() * tabWidth, top + 22, tabWidth - 2, 16).build();
            b.active = t != tab;
            addRenderableWidget(b);
        }
        addRenderableWidget(Button.builder(Component.translatable("millenairekingdoms.gui.refresh"), x -> send(KingdomActionPayload.of("REFRESH")))
                .bounds(left + panelWidth - 72, top + panelHeight - 22, 60, 16).build());
        if (!view.contains("focus")) { noKingdom(); return; }
        switch (tab) {
            case KINGDOM -> kingdomTab();
            case MILITARY -> militaryTab();
            case DIPLOMACY -> diplomacyTab();
            case ECONOMY -> economyTab();
            case RULERSHIP -> rulershipTab();
            case CONSTRUCTION -> constructionTab();
            case SHIPMENTS -> shipmentTab();
        }
    }

    private int x0() { return left + 14; }
    private int y0() { return top + 44; }
    private int contentWidth() { return panelWidth - 28; }
    private int bottom() { return top + panelHeight - 28; }

    private void constructionTab() {
        int x=x0(),y=y0(),w=contentWidth(),half=w*11/20;
        var c=view.getCompound("construction");var villages=c.getList("villages",Tag.TAG_COMPOUND);
        if(!c.hasUUID("village") || villages.isEmpty()) {
            wrapped(x,y,constructionReason(c.getString("reason")),w,BAD,5);return;
        }
        int index=0;for(int n=0;n<villages.size();n++) if(villages.getCompound(n).getUUID("id").equals(c.getUUID("village"))) index=n;
        var village=villages.getCompound(index);var next=villages.getCompound((index+1)%villages.size());
        Component villageLabel=t("construction.village",village.getString("name"));
        if(!c.getBoolean("available")) villageLabel=Component.empty().append(villageLabel).append(" · ").append(t("construction.unavailable"));
        button(x,y,w,villageLabel,b->send(KingdomActionPayload.of("CONSTRUCTION_SELECT",next.getUUID("id"))),villages.size()>1,null);y+=21;
        text(x,y,t("construction.plans"),TITLE,half-4);
        int rx=x+half+2,rw=w-half-2; text(rx,y,t("construction.current"),TITLE,rw);
        var plans=c.getList("plans",Tag.TAG_COMPOUND);CompoundTag selected=null;
        for(Tag raw:plans) {var plan=(CompoundTag)raw;if(plan.getString("plan").equals(selectedConstructionPlan))selected=plan;}
        if(selected==null && !plans.isEmpty()) {selected=plans.getCompound(0);selectedConstructionPlan=selected.getString("plan");}
        int listHeight=Math.max(33,bottom()-y-63);var choices=list(x,y+11,half-4,listHeight);
        for(Tag raw:plans) {
            var plan=(CompoundTag)raw;String id=plan.getString("plan");
            choices.add(Component.literal(plan.getString("name")+" · "+coins(plan.getLong("fee"))),plan.getBoolean("canOrder") ? TEXT : MUTED,id.equals(selectedConstructionPlan),
                    ()->{selectedConstructionPlan=id;rebuildWidgets();});
        }
        if(selected!=null) {
            var plan=selected;
            var purchase=Button.builder(t("construction.order",coins(plan.getLong("fee"))),b->send(new KingdomActionPayload("CONSTRUCTION_ORDER",
                    Optional.of(c.getUUID("village")),Optional.of(plan.getUUID("offer")),0,plan.getString("plan"))))
                    .bounds(x,y+listHeight+14,half-4,16).build();purchase.active=plan.getBoolean("canOrder");
            Component tip=Component.literal(plan.getString("name"));if(!purchase.active)tip=Component.empty().append(tip).append("\n").append(constructionReason(plan.getString("reason")));
            purchase.setTooltip(Tooltip.create(tip));addRenderableWidget(purchase);
        } else wrapped(x,y+listHeight+14,constructionReason(c.getString("reason")),half-4,MUTED,2);
        int ry=y+11;var order=c.getCompound("order");
        if(order.hasUUID("id")) {
            ry=line(rx,ry,Component.literal(order.getString("name")),rw);
            ry=line(rx,ry,t("construction.stage."+order.getString("stage")),rw,TITLE);
            if(!order.getString("blocked").equals("NONE")) ry=wrapped(rx,ry,t("construction.blocked."+order.getString("blocked")),rw,BAD,2);
            if(order.contains("progress"))ry=line(rx,ry,t("construction.progress",order.getInt("progress")),rw);
            ry=line(rx,ry,t("construction.price",coins(order.getLong("fee"))),rw);
            button(rx,ry,rw,t(confirmConstructionCancel ? "construction.confirm_cancel" : "construction.cancel"),b->{
                if(confirmConstructionCancel) {confirmConstructionCancel=false;send(KingdomActionPayload.of("CONSTRUCTION_CANCEL",order.getUUID("id")));}
                else {confirmConstructionCancel=true;rebuildWidgets();}
            },order.getBoolean("canCancel"),t("construction.cancel_unavailable"));ry+=20;
            if(order.getBoolean("canResume"))button(rx,ry,rw,t("construction.resume"),b->send(KingdomActionPayload.of("CONSTRUCTION_RESUME",order.getUUID("id"))),true,null);
        } else {
            ry=wrapped(rx,ry,t(c.getBoolean("stateKnown") ? "construction.no_order" : "construction.order_unknown"),rw,MUTED,2);
            if(c.contains("reason"))wrapped(rx,ry,constructionReason(c.getString("reason")),rw,BAD,4);
        }
        wrapped(x,bottom()-25,t(order.getBoolean("refundable") ? "construction.refund_note" : "construction.patronage_note"),w,MUTED,2);
    }
    private static Component constructionReason(String reason) {
        return t("construction.reason."+(reason.isEmpty() ? "NO_ELIGIBLE_PLAN" : reason));
    }

    private void shipmentTab(){
        int x=x0(),y=y0(),w=contentWidth(),lw=w/2-3,rx=x+w/2+3,rw=w/2-3;
        var s=view.getCompound("shipments");var villages=s.getList("villages",Tag.TAG_COMPOUND);
        if(!s.hasUUID("source") || !s.hasUUID("target") || villages.size()<2){wrapped(x,y,shipmentReason(s.getString("reason")),w,BAD,5);return;}
        shipmentVillage(x,y,w,s,villages,true);shipmentVillage(x,y+20,w,s,villages,false);y+=42;
        var products=s.getList("products",Tag.TAG_COMPOUND);int productIndex=0;
        for(int n=0;n<products.size();n++)if(products.getCompound(n).getString("id").equals(s.getString("product")))productIndex=n;
        if(!products.isEmpty()){
            String next=products.getCompound((productIndex+1)%products.size()).getString("id");
            shipmentButton(x,y,lw,shipmentItem(s.getString("product")),b->send(new KingdomActionPayload("SHIPMENT_PRODUCT",Optional.empty(),Optional.empty(),0,next)),products.size()>1,null);
        }else text(x,y,t("shipment.no_goods"),MUTED,lw);
        int sy=y+20;
        if(shipmentQuantity.isEmpty())shipmentQuantity=Integer.toString(s.getInt("quantity"));
        shipmentQuantityBox=new EditBox(font,x,sy,Math.max(30,lw-85),16,t("shipment.quantity"));shipmentQuantityBox.setMaxLength(6);
        shipmentQuantityBox.setFilter(value->value.matches("[0-9]*"));shipmentQuantityBox.setValue(shipmentQuantity);addRenderableWidget(shipmentQuantityBox);
        shipmentButton(x+lw-81,sy,81,t("shipment.set_quantity"),b->{
            String value=shipmentQuantityBox.getValue();long quantity=value.isEmpty() ? 0 : Long.parseLong(value);
            send(new KingdomActionPayload("SHIPMENT_QUANTITY",Optional.empty(),Optional.empty(),quantity,""));
        },true,t("shipment.quantity_hint",s.getInt("maximumQuantity")));
        sy+=20;
        if(s.contains("physical")){
            sy=line(x,sy,t("shipment.physical",s.getInt("physical")),lw);
            sy=line(x,sy,t("shipment.available",s.getInt("available")),lw);
        }
        if(s.hasUUID("offer")){
            sy=line(x,sy,t("shipment.price",coins(s.getLong("fee"))),lw);
            sy=line(x,sy,t("shipment.time",seconds(s.getLong("travelTicks"))),lw);
            var purchase=Button.builder(t("shipment.order"),b->send(KingdomActionPayload.of("SHIPMENT_ORDER",s.getUUID("offer")))).bounds(x,sy+2,lw,16).build();
            purchase.active=s.getBoolean("canOrder") && shipmentQuantity.equals(Integer.toString(s.getInt("quantity")));
            purchase.setTooltip(Tooltip.create(s.getBoolean("canOrder") ? t("shipment.apply_quantity") : shipmentReason(s.getString("reason"))));addRenderableWidget(purchase);
            shipmentQuantityBox.setResponder(value->{shipmentQuantity=value;purchase.active=s.getBoolean("canOrder") && value.equals(Integer.toString(s.getInt("quantity")));});sy+=22;
        }
        if(s.contains("reason"))wrapped(x,sy,shipmentReason(s.getString("reason")),lw,BAD,Math.max(1,(bottom()-sy)/11));
        var orders=s.getList("orders",Tag.TAG_COMPOUND);
        if(orders.isEmpty()){wrapped(rx,y,t(s.getBoolean("stateKnown") ? "shipment.no_order" : "shipment.order_unknown"),rw,MUTED,4);return;}
        int index=0;for(int n=0;n<orders.size();n++)if(orders.getCompound(n).getUUID("id").equals(selectedShipmentOrder))index=n;
        var order=orders.getCompound(index);selectedShipmentOrder=order.getUUID("id");var next=orders.getCompound((index+1)%orders.size());
        shipmentButton(rx,y,rw,t("shipment.to",order.getString("destination")),b->{selectedShipmentOrder=next.getUUID("id");confirmShipmentCancel=false;rebuildWidgets();},orders.size()>1,shipmentItem(order.getString("product")));int ry=y+20;
        ry=line(rx,ry,t("shipment.stage."+order.getString("stage")),rw,TITLE);
        if(!order.getString("blocked").equals("NONE"))ry=wrapped(rx,ry,t("shipment.blocked."+order.getString("blocked")),rw,BAD,2);
        ry=line(rx,ry,t("shipment.delivered",order.getInt("delivered"),order.getInt("quantity")),rw);
        ry=line(rx,ry,t("shipment.held_returned",order.getInt("held"),order.getInt("returned")),rw);
        if(order.getString("stage").equals("IN_TRANSIT"))ry=line(rx,ry,t("shipment.remaining",seconds(order.getLong("remainingTicks"))),rw);
        shipmentButton(rx,ry+2,rw,t(confirmShipmentCancel ? "shipment.confirm_cancel" : "shipment.cancel"),b->{
            if(confirmShipmentCancel){confirmShipmentCancel=false;send(KingdomActionPayload.of("SHIPMENT_CANCEL",order.getUUID("id")));}
            else{confirmShipmentCancel=true;rebuildWidgets();}
        },order.getBoolean("canCancel"),t(order.getBoolean("refundable") ? "shipment.refund_note" : "shipment.earned_fee_note"));ry+=21;
        if(order.getBoolean("canResume"))shipmentButton(rx,ry,rw,t("shipment.resume"),b->send(KingdomActionPayload.of("SHIPMENT_RESUME",order.getUUID("id"))),true,t("shipment.resume_note"));
    }
    private void shipmentVillage(int x,int y,int width,CompoundTag s,ListTag villages,boolean source){
        String key=source ? "source" : "target";UUID current=s.getUUID(key),other=s.getUUID(source ? "target" : "source");
        int index=0;for(int n=0;n<villages.size();n++)if(villages.getCompound(n).getUUID("id").equals(current))index=n;
        var village=villages.getCompound(index);CompoundTag next=village;
        for(int offset=1;offset<villages.size();offset++){var candidate=villages.getCompound((index+offset)%villages.size());if(source || !candidate.getUUID("id").equals(other)){next=candidate;break;}}
        UUID nextId=next.getUUID("id");Component label=t("shipment."+key,village.getString("name"));
        if(!village.getBoolean("loaded"))label=Component.empty().append(label).append(" · ").append(t("shipment.unavailable"));
        shipmentButton(x,y,width,label,b->send(KingdomActionPayload.of(source ? "SHIPMENT_SOURCE" : "SHIPMENT_TARGET",nextId)),!nextId.equals(current),Component.empty().append(label).append("\n").append(t(source ? "shipment.source_note" : "shipment.target_note")));
    }
    private static Component shipmentReason(String reason){return t("shipment.reason."+(reason.isEmpty() ? "SERVICE_UNAVAILABLE" : reason));}
    private static Component shipmentItem(String id){var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id));return item.<Component>map(i->Component.translatable(i.getDescriptionId())).orElseGet(()->Component.literal(id));}
    private static long seconds(long ticks){return ticks/20+(ticks%20==0 ? 0 : 1);}

    private void shipmentButton(int x,int y,int width,Component label,Consumer<Button> press,boolean active,Component hint){
        var button=Button.builder(label,press::accept).bounds(x,y,width,16).build();button.active=active;
        if(hint!=null)button.setTooltip(Tooltip.create(hint));addRenderableWidget(button);
    }

    // ---------------------------------------------------------------- tabs

    private void noKingdom() {
        int y = y0();
        text(x0(), y, t("none"), MUTED, contentWidth()); y += 14;
        y = nearbyVillage(x0(), y, contentWidth());
    }

    private void kingdomTab() {
        CompoundTag k = view.getCompound("kingdom");
        int x = x0(), y = y0(), half = contentWidth() / 2;
        y = line(x, y, t("capital", k.getString("capital")), half);
        y = line(x, y, t("culture", k.getString("culture")), half);
        if(k.contains("personality"))y=line(x,y,t("personality",t("personality."+k.getString("personality"))),half);
        if(k.getBoolean("personalityFallback"))y=line(x,y,t("personality_fallback"),half,MUTED);
        y = line(x, y, t("coordinates", k.getInt("x"), k.getInt("y"), k.getInt("z")), half);
        String ruler = k.getString("ruler");
        y = line(x, y, ruler.isEmpty() ? t("ruler_none") : t("ruler", ruler), half);
        y = line(x, y, t("villages", k.getList("members", Tag.TAG_COMPOUND).size()), half);
        y = line(x, y, warLine(), half);
        y = line(x, y, t("income", coins(k.getLong("income"))), half);
        if (!k.getBoolean("active")) y = line(x, y, t("inactive"), half, BAD);
        CompoundTag rights = view.getCompound("rights");
        y += 4;
        text(x, y, t("rights"), TITLE, half); y += 11;
        if (view.getBoolean("isRuler")) y = reason(x, y, rights, "decide", t("rights_ruler"), half);
        else y = wrapped(x, y, t("rights_viewer"), half, MUTED, 3);
        int lx = x + half + 6, lw = contentWidth() - half - 6;
        text(lx, y0(), t("members"), TITLE, lw);
        RowList members = list(lx, y0() + 12, lw, bottom() - 24 - (y0() + 12));
        for (Tag tag : k.getList("members", Tag.TAG_COMPOUND)) {
            CompoundTag m = (CompoundTag) tag;
            members.add(m.getBoolean("capital") ? t("member_capital", m.getString("name"), m.getInt("level"))
                    : t("member", m.getString("name"), m.getInt("level"), m.getLong("income")), TEXT, false, null);
        }
        kingdomSwitcher(bottom() - 18);
    }

    /** Previous / next kingdom and "my kingdom": only changes what is shown. */
    private void kingdomSwitcher(int y) {
        ListTag all = view.getList("kingdoms", Tag.TAG_COMPOUND);
        UUID focus = view.getUUID("focus");
        int index = 0;
        for (int i = 0; i < all.size(); i++) if (all.getCompound(i).getUUID("id").equals(focus)) index = i;
        int finalIndex = index;
        if (all.size() > 1) {
            button(x0(), y, 20, t("prev"), b -> view(all.getCompound((finalIndex - 1 + all.size()) % all.size()).getUUID("id")), true, null);
            button(x0() + 22, y, 20, t("next"), b -> view(all.getCompound((finalIndex + 1) % all.size()).getUUID("id")), true, null);
        }
        if (view.hasUUID("ruled") && !view.getUUID("ruled").equals(focus))
            button(x0() + 46, y, 100, t("my_kingdom"), b -> view(view.getUUID("ruled")), true, null);
    }

    private void militaryTab() {
        int x = x0(), y = y0(), w = contentWidth();
        boolean ruler = view.getBoolean("isRuler");
        CompoundTag rights = view.getCompound("rights");
        y = line(x, y, warLine(), w);
        if (view.contains("war")) {
            CompoundTag war = view.getCompound("war");
            y = line(x, y, t(war.getBoolean("capitalOpen") ? "capital_open" : "capital_closed", war.getString("defender"),
                    war.getInt("capitalHeld"), war.getInt("capitalRequired"), war.getInt("initial")), w);
            y = line(x, y, t("war_progress", war.getInt("capitalWins"), war.getInt("conquered"),
                    Math.max(0, view.getLong("day") - war.getLong("startDay")), Math.max(0, view.getLong("day") - war.getLong("idleSince"))), w);
        }
        y += 2;
        int listTop = y, listHeight = bottom() - 42 - listTop, half = w / 2;
        if (ruler) {
            text(x, listTop, t("targets"), TITLE, half); 
            RowList targets = list(x, listTop + 11, half - 4, listHeight - 11);
            CompoundTag selected = null;
            for (Tag tag : view.getList("targets", Tag.TAG_COMPOUND)) {
                CompoundTag e = (CompoundTag) tag;
                UUID id = e.getUUID("id");
                boolean isSelected = id.equals(selectedTarget);
                if (isSelected) selected = e;
                String owner = e.contains("owner") ? e.getString("owner") : "";
                MutableComponent row = owner.isEmpty() ? t("target_independent", e.getString("name")) : t("target_of", e.getString("name"), owner);
                if (e.contains("required")) row.append(" " + e.getInt("wins") + "/" + e.getInt("required"));
                if (e.getBoolean("capital")) row.append(" ").append(t("capital_mark"));
                targets.add(row, e.getBoolean("ready") ? GOOD : TEXT, isSelected, () -> { selectedTarget = id; rebuildWidgets(); });
            }
            if (targets.rows.isEmpty()) targets.add(t("no_targets"), MUTED, false, null);
            int rx = x + half + 2, rw = w - half - 2, ry = listTop;
            if (selected != null) {
                ry = line(rx, ry, Component.literal(selected.getString("name")), rw, TITLE);
                ry = line(rx, ry, t("target_detail", selected.getInt("defence"), selected.getInt("distance")), rw);
                if (selected.contains("required")) ry = line(rx, ry, t("target_progress", selected.getInt("wins"), selected.getInt("required")), rw);
                if (selected.getLong("cooldown") > 0) ry = line(rx, ry, t("cooldown", selected.getLong("cooldown")), rw, BAD);
                if (selected.contains("status")) ry = wrapped(rx, ry, json(selected.getString("status")), rw, BAD, 3);
                else ry = line(rx, ry, t("target_ready"), rw, GOOD);
            } else ry = line(rx, ry, t("select_target"), rw, MUTED);
            ry += 2;
            ry = sourceButton(rx, ry, rw);
            if (rights.contains("order")) {
                // A waiting order replaces the start button with "call off" and its status.
                CompoundTag o = rights.getCompound("order");
                button(rx, ry, rw, t("cancel_raid"), b -> send(KingdomActionPayload.of("CANCEL_RAID")), !o.getBoolean("npc"), null);
                ry += 20;
                ry = wrapped(rx, ry, t("order_line", o.getString("target"), o.contains("source") ? o.getString("source") : "-",
                        o.getLong("expiresIn") / 20, o.getString("waiting")), rw, MUTED, Math.max(1, (bottom() - 22 - ry) / 10));
            } else {
                button(rx, ry, rw, t("start_raid"), b -> send(KingdomActionPayload.of("RAID", selectedTarget, selectedSource)),
                        selected != null && selected.getBoolean("ready"), selected == null ? t("select_target") : null);
                ry += 20;
            }
        } else {
            y = line(x, y, t("ruler_only_raids"), w, MUTED);
            listTop = y;
        }
        int ly = ruler ? listTop + listHeight + 2 : listTop;
        for (Tag tag : view.getList("raids", Tag.TAG_COMPOUND)) {
            CompoundTag r = (CompoundTag) tag;
            if (ly > bottom() - 32) break;
            ly = line(x, ly, t(r.getBoolean("ours") ? "raid_ours" : "raid_theirs", r.getString("attacker"), r.getString("target"),
                    r.getInt("wins"), r.getInt("required"), r.getLong("ticks") / 20), w, r.getBoolean("ours") ? GOOD : BAD);
        }
        warControls(bottom() - 18);
    }

    private int sourceButton(int x, int y, int w) {
        ListTag sources = view.getList("sources", Tag.TAG_COMPOUND);
        Component label = t("source_auto");
        int index = -1;
        for (int i = 0; i < sources.size(); i++) if (sources.getCompound(i).getUUID("id").equals(selectedSource)) index = i;
        if (index >= 0) {
            CompoundTag s = sources.getCompound(index);
            label = t("source", s.getString("name"), s.getInt("strength"));
        } else selectedSource = null;
        int next = index + 1;
        button(x, y, w, label, b -> { selectedSource = next >= sources.size() ? null : sources.getCompound(next).getUUID("id"); rebuildWidgets(); },
                !sources.isEmpty(), t("source_hint"));
        return y + 20;
    }

    /** Declare or request war on a chosen kingdom, and make or offer peace. */
    private void warControls(int y) {
        boolean ruler = view.getBoolean("isRuler");
        CompoundTag rights = view.getCompound("rights");
        int x = x0(), w = contentWidth();
        if (view.contains("war")) {
            if (ruler) {
                CompoundTag war = view.getCompound("war");
                boolean attacking = war.getBoolean("attacking");
                button(x, y, 150, t(attacking ? "make_peace" : war.getBoolean("peaceOffered") ? "peace_offered" : "offer_peace"),
                        b -> send(KingdomActionPayload.of("PEACE")), rights.contains("decideOk") && (attacking || !war.getBoolean("peaceOffered")), denied(rights, "decide"));
            }
            return;
        }
        List<CompoundTag> others = new ArrayList<>();
        for (Tag tag : view.getList("kingdoms", Tag.TAG_COMPOUND)) {
            CompoundTag k = (CompoundTag) tag;
            if (!k.getUUID("id").equals(view.getUUID("focus")) && k.getBoolean("active")) others.add(k);
        }
        if (others.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < others.size(); i++) if (others.get(i).getUUID("id").equals(enemyKingdom)) index = i;
        CompoundTag enemy = others.get(index);
        enemyKingdom = enemy.getUUID("id");
        int next = (index + 1) % others.size();
        Component label = t("against", enemy.getString("name"), enemy.contains("relation") ? enemy.getInt("relation") : "?");
        button(x, y, w - 154, label, b -> { enemyKingdom = others.get(next).getUUID("id"); rebuildWidgets(); }, others.size() > 1, null);
        UUID target = enemyKingdom;
        Component truce = enemy.contains("truce") ? t("truce_until", enemy.getLong("truce")) : null;
        if (ruler) button(x + w - 150, y, 150, t("declare_war"), b -> send(KingdomActionPayload.of("DECLARE_WAR", target)),
                rights.contains("decideOk") && truce == null && !enemy.getBoolean("atWar"), truce != null ? truce : denied(rights, "decide"));
        else button(x + w - 150, y, 150, t("request_war"), b -> send(KingdomActionPayload.of("REQUEST_WAR", target)),
                rights.contains("requestOk") && truce == null && !enemy.getBoolean("atWar"), truce != null ? truce : denied(rights, "request"));
    }

    private void diplomacyTab() {
        int x = x0(), y = y0(), w = contentWidth(), half = w * 11 / 20;
        boolean ruler = view.getBoolean("isRuler");
        text(x, y, t("known_kingdoms"), TITLE, half);
        RowList kingdoms = list(x, y + 11, half - 4, bottom() - 24 - (y + 11));
        for (Tag tag : view.getList("kingdoms", Tag.TAG_COMPOUND)) {
            CompoundTag k = (CompoundTag) tag;
            UUID id = k.getUUID("id");
            boolean self = id.equals(view.getUUID("focus"));
            MutableComponent row = Component.literal(k.getString("name"));
            if (self) row.append(" ").append(t("this_kingdom"));
            else {
                row.append(" · ").append(k.contains("relation") ? t("relation", k.getInt("relation")) : t("relation_unknown"));
                if (k.contains("war")) row.append(" · ").append(t(k.getString("war").equals("attacking") ? "state_attacking" : "state_defending"));
                else if (k.contains("truce")) row.append(" · ").append(t("truce_until", k.getLong("truce")));
                else if (k.getBoolean("atWar")) row.append(" · ").append(t("state_elsewhere"));
            }
            int color = self ? MUTED : k.contains("war") ? BAD : k.contains("relation") && k.getInt("relation") >= 50 ? GOOD : TEXT;
            kingdoms.add(row, color, id.equals(selectedKingdom), () -> { selectedKingdom = id; rebuildWidgets(); });
        }
        button(x, bottom() - 18, 110, t("view_kingdom"), b -> view(selectedKingdom), selectedKingdom != null, t("select_kingdom"));
        int rx = x + half + 2, rw = w - half - 2, ry = y;
        if (view.contains("war") && view.getCompound("war").getBoolean("peaceOffered")) {
            CompoundTag war = view.getCompound("war");
            ry = line(rx, ry, t("peace_was_offered", war.getString("defender")), rw, GOOD);
            if (ruler && war.getBoolean("attacking")) { button(rx, ry, rw, t("accept_peace"), b -> send(KingdomActionPayload.of("PEACE")), true, null); ry += 20; }
        }
        if (ruler) {
            text(rx, ry, t("applications"), TITLE, rw); ry += 11;
            RowList requests = list(rx, ry, rw, 44);
            for (Tag tag : view.getList("requests", Tag.TAG_COMPOUND)) {
                CompoundTag r = (CompoundTag) tag;
                UUID village = r.getUUID("village");
                requests.add(r.getBoolean("npc") ? t("application_npc", r.getString("name")) : t("application_player", r.getString("name"), r.getString("from")),
                        TEXT, village.equals(selectedRequest), () -> { selectedRequest = village; rebuildWidgets(); });
            }
            if (requests.rows.isEmpty()) requests.add(t("no_applications"), MUTED, false, null);
            ry += 46;
            UUID request = selectedRequest;
            button(rx, ry, rw / 2 - 1, t("accept"), b -> send(KingdomActionPayload.of("ACCEPT", request)), request != null, t("select_application"));
            button(rx + rw / 2 + 1, ry, rw / 2 - 1, t("refuse"), b -> send(KingdomActionPayload.of("REFUSE", request)), request != null, t("select_application"));
            ry += 22;
        }
        nearbyVillage(rx, ry, rw);
    }

    /** The independent village the player stands in: join a kingdom or found one from an own developed village. */
    private int nearbyVillage(int x, int y, int w) {
        CompoundTag v = view.getCompound("nearby");
        if (!v.contains("id")) return wrapped(x, y, t("no_nearby_village"), w, MUTED, 3);
        String owner = v.getString("owner");
        y = wrapped(x, y, owner.isEmpty() ? t("nearby_npc", v.getString("name"), v.getInt("level")) : t("nearby_owned", v.getString("name"), v.getInt("level"), owner), w, TEXT, 2);
        ListTag kingdoms = v.getList("kingdoms", Tag.TAG_COMPOUND);
        if ((owner.isEmpty() || v.getBoolean("own")) && !kingdoms.isEmpty()) {
            int index = 0;
            for (int i = 0; i < kingdoms.size(); i++) if (kingdoms.getCompound(i).getUUID("id").equals(joinKingdom)) index = i;
            CompoundTag k = kingdoms.getCompound(index);
            joinKingdom = k.getUUID("id");
            int next = (index + 1) % kingdoms.size();
            button(x, y, w, t("join_choice", k.getString("name"), k.getInt("relation")), b -> { joinKingdom = kingdoms.getCompound(next).getUUID("id"); rebuildWidgets(); },
                    kingdoms.size() > 1, null);
            y += 20;
            UUID chosen = joinKingdom;
            button(x, y, w, t("join"), b -> send(KingdomActionPayload.of("JOIN", chosen)), true, t("join_hint"));
            y += 20;
        }
        if (v.getBoolean("canFound")) {
            nameBox = new EditBox(font, x, y, w - 84, 16, t("kingdom_name"));
            nameBox.setMaxLength(64); nameBox.setValue(kingdomName); nameBox.setHint(t("kingdom_name"));
            addRenderableWidget(nameBox);
            button(x + w - 82, y, 82, t("found"), b -> send(new KingdomActionPayload("FOUND", Optional.empty(), Optional.empty(), 0, nameBox.getValue().strip())), true, null);
            y += 20;
        }
        return y;
    }

    private void economyTab() {
        CompoundTag k = view.getCompound("kingdom");
        CompoundTag rights = view.getCompound("rights");
        int x = x0(), y = y0(), w = contentWidth(), half = w / 2;
        y = line(x, y, t("total_income", coins(k.getLong("income"))), w);
        if (!k.contains("coins")) y = line(x, y, t("storage_hidden"), w, MUTED);
        else if (!k.getBoolean("storageLoaded")) y = line(x, y, t("storage_unloaded"), w, BAD);
        else y = line(x, y, t("storage", coins(k.getLong("coins")), k.getInt("gold"), k.getInt("silver"), k.getInt("bronze")), w);
        y = line(x, y, t("owed", coins(k.getLong("owed"))), w, k.getLong("owed") > 0 ? BAD : MUTED);
        y += 2;
        text(x, y, t("village_incomes"), TITLE, half);
        RowList incomes = list(x, y + 11, half - 4, bottom() - (y + 11));
        for (Tag tag : k.getList("members", Tag.TAG_COMPOUND)) {
            CompoundTag m = (CompoundTag) tag;
            incomes.add(m.getBoolean("capital") ? t("income_capital", m.getString("name")) : t("income_row", m.getString("name"), m.getInt("level"), m.getLong("income")),
                    TEXT, false, null);
        }
        int rx = x + half + 2, rw = w - half - 2, ry = y;
        text(rx, ry, t("withdraw_title"), TITLE, rw); ry += 12;
        boolean allowed = rights.contains("withdrawOk");
        amountBox = new EditBox(font, rx, ry, rw - 46, 16, t("amount"));
        amountBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,9}"));
        amountBox.setValue(amount); amountBox.setHint(t("amount"));
        addRenderableWidget(amountBox);
        long all = k.getLong("coins");
        button(rx + rw - 44, ry, 44, t("all"), b -> { amountBox.setValue(Long.toString(all)); }, allowed && all > 0, null);
        ry += 20;
        // Preset amounts, never above the stock the server reported.
        long[] presets = {1, 10, 64, 640, 4096};
        int pw = (rw - 4 * 2) / presets.length;
        for (int i = 0; i < presets.length; i++) {
            long preset = presets[i];
            button(rx + i * (pw + 2), ry, pw, Component.literal(coins(preset)), b -> amountBox.setValue(Long.toString(preset)), allowed && preset <= all, null);
        }
        ry += 20;
        button(rx, ry, rw, t("withdraw"), b -> {
            String value = amountBox.getValue();
            long n = value.isEmpty() ? 0 : Long.parseLong(value);
            amount = value;
            send(new KingdomActionPayload("WITHDRAW", Optional.empty(), Optional.empty(), n, ""));
        }, allowed && all > 0, allowed ? t("storage_empty") : denied(rights, "withdraw"));
        ry += 22;
        if (!allowed) wrapped(rx, ry, denied(rights, "withdraw"), rw, BAD, 4);
        else wrapped(rx, ry, t("withdraw_note"), rw, MUTED, 4);
    }

    private void rulershipTab() {
        CompoundTag k = view.getCompound("kingdom");
        CompoundTag rights = view.getCompound("rights");
        int x = x0(), y = y0(), w = contentWidth();
        String ruler = k.getString("ruler");
        y = line(x, y, ruler.isEmpty() ? t("ruler_none") : t("ruler", ruler), w);
        y = line(x, y, t("reputation", k.getString("capital"), rights.getInt("reputation")), w, TITLE);
        y = line(x, y, t("needs_claim", rights.getInt("claimNeeds")), w);
        y = line(x, y, t("needs_decision", rights.getInt("decisionNeeds")), w);
        y = line(x, y, t("needs_request", rights.getInt("requestNeeds")), w);
        y += 4;
        y = reason(x, y, rights, "claim", t("claim_possible"), w);
        if (view.getBoolean("isRuler")) y = reason(x, y, rights, "decide", t("decisions_possible"), w);
        y = reason(x, y, rights, "request", t("request_possible"), w);
        int by = bottom() - 18;
        button(x, by, 150, t("claim"), b -> send(KingdomActionPayload.of("CLAIM")), rights.contains("claimOk"), denied(rights, "claim"));
        if (view.getBoolean("isRuler"))
            button(x + 154, by, 150, t(confirmAbdicate ? "abdicate_confirm" : "abdicate"), b -> {
                if (confirmAbdicate) { confirmAbdicate = false; send(KingdomActionPayload.of("ABDICATE")); } else { confirmAbdicate = true; rebuildWidgets(); }
            }, true, t("abdicate_hint"));
    }

    // ---------------------------------------------------------------- pieces

    private Component warLine() {
        if (!view.contains("war")) return t("war_none");
        CompoundTag war = view.getCompound("war");
        return war.getBoolean("attacking") ? t("war_attacking", war.getString("defender")) : t("war_defending", war.getString("attacker"));
    }
    /** "ok" text when the server reported the right, else the server's reason in red. */
    private int reason(int x, int y, CompoundTag rights, String key, Component ok, int w) {
        if (rights.contains(key + "Ok")) return wrapped(x, y, ok, w, GOOD, 2);
        return wrapped(x, y, denied(rights, key), w, BAD, 2);
    }
    private Component denied(CompoundTag rights, String key) {
        if (!rights.contains(key)) return null;
        String value = rights.getString(key);
        return value.startsWith("{") || value.startsWith("\"") ? json(value) : Component.literal(value);
    }
    private Component json(String value) {
        try { return Component.Serializer.fromJson(value, minecraft.level.registryAccess()); }
        catch (RuntimeException broken) { return Component.literal(value); }
    }
    private static MutableComponent t(String key, Object... args) { return Component.translatable("millenairekingdoms.gui." + key, args); }
    private static String coins(long deniers) {
        long gold = deniers / 4096, rest = deniers % 4096, silver = rest / 64, bronze = rest % 64;
        StringBuilder b = new StringBuilder();
        if (gold > 0) b.append(gold).append("o ");
        if (silver > 0) b.append(silver).append("a ");
        if (bronze > 0 || b.isEmpty()) b.append(bronze).append("d");
        return b.toString().strip();
    }
    private void view(UUID kingdom) { if (kingdom != null) send(KingdomActionPayload.of("VIEW", kingdom)); }
    private void send(KingdomActionPayload request) { KingdomClient.send(request); }

    private int line(int x, int y, Component text, int w) { return line(x, y, text, w, TEXT); }
    private int line(int x, int y, Component text, int w, int color) { text(x, y, text, color, w); return y + 11; }
    private int wrapped(int x, int y, Component text, int w, int color, int maxLines) {
        if (text == null) return y;
        var parts = font.split(text, w);
        for (int i = 0; i < parts.size() && i < maxLines; i++) {
            var part = parts.get(i);
            StringBuilder plain = new StringBuilder();
            part.accept((index, style, codePoint) -> { plain.appendCodePoint(codePoint); return true; });
            text(x, y, Component.literal(plain.toString()), color, w);
            y += 10;
        }
        return y + 1;
    }
    private void text(int x, int y, Component text, int color, int width) { texts.add(new Text(x, y, text, color, width)); }
    private RowList list(int x, int y, int w, int h) {
        RowList l = new RowList(x, y, w, Math.max(12, h));
        l.scroll = scrolls.getOrDefault(tab + ":" + lists.size(), 0);
        lists.add(l); return l;
    }
    private void button(int x, int y, int w, Component label, Consumer<Button> press, boolean active, Component whyNot) {
        Button b = Button.builder(label, press::accept).bounds(x, y, w, 16).build();
        b.active = active;
        if (!active && whyNot != null) b.setTooltip(Tooltip.create(whyNot));
        addRenderableWidget(b);
    }

    // ---------------------------------------------------------------- rendering and input

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.blit(PARCHMENT, left, top, panelWidth, panelHeight, 0, 0, 256, 186, 256, 256);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        CompoundTag k = view.getCompound("kingdom");
        Component heading = view.contains("focus") ? Component.literal(k.getString("name")) : title;
        g.drawString(font, font.plainSubstrByWidth(heading.getString(), panelWidth - 24), left + 12, top + 9, TITLE, false);
        g.fill(left + 12, top + 40, left + panelWidth - 12, top + 41, LINE);
        for (Text text : texts) g.drawString(font, font.plainSubstrByWidth(text.text.getString(), text.width), text.x, text.y, text.color, false);
        for (RowList list : lists) list.render(g, font, mouseX, mouseY);
        if (view.contains("message")) {
            // The server's answer to the last request, on up to two lines left of the refresh button.
            Component message = json(view.getString("message"));
            int color = message.getStyle().getColor() == null ? TEXT : 0xFF000000 | message.getStyle().getColor().getValue();
            var lines = font.split(message, panelWidth - 96);
            int y = top + panelHeight - (lines.size() > 1 ? 24 : 18);
            for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), left + 14, y + i * 10, color, false);
        }
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        for (RowList list : lists) if (list.click(mouseX, mouseY)) return true;
        return false;
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (RowList list : lists) if (list.scroll(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    @Override public boolean isPauseScreen() { return false; }

    /** A small scrollable list of clickable rows drawn on the parchment. */
    static final class RowList {
        record Row(Component text, int color, boolean selected, Runnable click) {}
        final int x, y, w, h, rowHeight = 11;
        final List<Row> rows = new ArrayList<>();
        int scroll;
        RowList(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }
        void add(Component text, int color, boolean selected, Runnable click) { rows.add(new Row(text, color, selected, click)); }
        int visible() { return h / rowHeight; }
        void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
            g.fill(x, y, x + w, y + h, 0x18000000);
            g.renderOutline(x, y, w, h, LINE);
            scroll = Math.max(0, Math.min(scroll, rows.size() - visible()));
            for (int i = 0; i < visible() && scroll + i < rows.size(); i++) {
                Row row = rows.get(scroll + i);
                int ry = y + 1 + i * rowHeight;
                boolean hover = row.click != null && mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowHeight;
                if (row.selected) g.fill(x + 1, ry, x + w - 1, ry + rowHeight, 0x55A07850);
                else if (hover) g.fill(x + 1, ry, x + w - 1, ry + rowHeight, 0x30A07850);
                g.drawString(font, font.plainSubstrByWidth(row.text.getString(), w - 8), x + 3, ry + 2, row.color, false);
            }
            if (rows.size() > visible()) {
                int bar = Math.max(8, h * visible() / rows.size());
                int by = y + (h - bar) * scroll / Math.max(1, rows.size() - visible());
                g.fill(x + w - 3, by, x + w - 1, by + bar, LINE);
            }
        }
        boolean click(double mouseX, double mouseY) {
            if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) return false;
            int index = scroll + (int) ((mouseY - y - 1) / rowHeight);
            if (index >= 0 && index < rows.size() && rows.get(index).click != null) { rows.get(index).click.run(); return true; }
            return true;
        }
        boolean scroll(double mouseX, double mouseY, double delta) {
            if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) return false;
            scroll = Math.max(0, Math.min(rows.size() - visible(), scroll - (int) Math.signum(delta)));
            return true;
        }
    }
}
