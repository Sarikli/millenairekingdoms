package com.millenairekingdoms.gui;

import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.shipping.RoyalShipmentConfig;
import com.millenairekingdoms.shipping.RoyalShipmentService;
import com.millenairekingdoms.shipping.RoyalShipmentService.Quote;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import com.millenairekingdoms.shipping.RoyalShipmentStore.Entry;
import com.millenairekingdoms.shipping.RoyalShipmentStore.Stage;
import com.millenairekingdoms.shipping.RoyalVillageStock;
import com.millenairekingdoms.util.KingdomException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Bounded server-owned selection and expiring offers. Reading a screen never advances shipments or loads chunks. */
public final class RoyalShipmentView {
    private static final long OFFER_TICKS=1200;
    private static final int MAX_PRODUCTS=128,MAX_HISTORY=12;
    private static final Map<ServerPlayer,Session> SESSIONS=new WeakHashMap<>();
    private record Offer(UUID id,Quote quote,long issued) {}
    private static final class Session {
        String dimension;
        UUID kingdom,capital,source,target;
        long tenure;
        ResourceLocation product;
        int quantity=40;
        Offer offer;
    }
    private RoyalShipmentView() {}
    public static void forget(UUID player){SESSIONS.keySet().removeIf(p->p.getUUID().equals(player));}

    public static void selectVillage(ServerPlayer player,UUID village,boolean source){
        var k=PoliticsService.ruledKingdom(player);var data=KingdomSavedData.get(player.serverLevel());
        if(village==null || !k.id().equals(data.owner(village)))throw declined();
        var state=session(player,k);
        if(source){if(!village.equals(state.source)){state.source=village;state.product=null;state.offer=null;}}
        else if(!village.equals(state.target)){state.target=village;state.offer=null;}
        normalize(state,data);
    }
    public static void selectProduct(ServerPlayer player,ResourceLocation product){
        var state=session(player,PoliticsService.ruledKingdom(player));
        if(RoyalVillageStock.forPlayer(player,state.source).products().stream().limit(MAX_PRODUCTS)
                .noneMatch(i->BuiltInRegistries.ITEM.getKey(i).equals(product)))throw declined();
        if(!product.equals(state.product)){state.product=product;state.offer=null;}
    }
    public static void quantity(ServerPlayer player,long quantity){
        var state=session(player,PoliticsService.ruledKingdom(player));
        if(quantity<1 || quantity>RoyalShipmentConfig.current().maximumQuantity())throw declined();
        if(quantity!=state.quantity){state.quantity=(int)quantity;state.offer=null;}
    }

    public static CompoundTag build(ServerPlayer player,UUID focused){
        var out=new CompoundTag();var level=player.serverLevel();var data=KingdomSavedData.get(level);
        if(!focused.equals(data.ruledBy(player.getUUID()))){forget(player.getUUID());out.putString("reason","RULER_ONLY");return out;}
        try{
            var k=PoliticsService.ruledKingdom(player);var state=session(player,k);var villages=new ListTag();
            for(var id:data.members(k.id()).stream().sorted(Comparator.comparing(UUID::toString)).toList()){
                var village=PoliticsService.village(level,id);if(village==null)continue;
                var v=new CompoundTag();v.putUUID("id",id);v.putString("name",PoliticsService.name(village));
                v.putBoolean("loaded",village.isActive() && level.isLoaded(village.getCenter()));villages.add(v);
            }
            out.put("villages",villages);out.putUUID("source",state.source);if(state.target!=null)out.putUUID("target",state.target);
            out.putInt("quantity",state.quantity);out.putInt("maximumQuantity",RoyalShipmentConfig.current().maximumQuantity());
            var path=RoyalShipmentService.directory(level);
            var history=Files.exists(path.resolve("shipments.dat")) ? new RoyalShipmentStore(path).entries() : List.<Entry>of();
            out.putBoolean("stateKnown",true);
            var selected=history.stream().filter(e->e.intent().dimension().equals(state.dimension) && e.intent().kingdom().equals(k.id()) && e.intent().source().village().equals(state.source))
                    .sorted(Comparator.comparing(Entry::terminal).thenComparing(Comparator.comparingLong((Entry e)->e.intent().issued()).reversed()).thenComparing(e->e.intent().id())).toList();
            var orders=new ListTag();for(var entry:selected.stream().limit(MAX_HISTORY).toList()){
                var i=entry.intent();var order=new CompoundTag();order.putUUID("id",i.id());order.putString("product",i.product().toString());
                order.putString("destination",name(player,i.destination().village()));order.putString("stage",entry.cancellationRequested() && !entry.terminal() && entry.stage()!=Stage.RECOVERY_REQUIRED ? "CANCELLING" : entry.stage().name());
                order.putString("blocked",entry.blocked().name());order.putInt("quantity",i.quantity());order.putInt("collected",entry.collected());order.putInt("delivered",entry.delivered());order.putInt("returned",entry.returned());order.putInt("held",entry.escrow());
                order.putLong("remainingTicks",entry.due()<0 ? i.travelTicks() : Math.max(0,entry.due()-level.getGameTime()));order.putLong("fee",i.fee());
                boolean own=player.getUUID().equals(i.actor()) && k.capital().equals(i.capital()) && state.tenure==i.rulerSince();
                order.putBoolean("canCancel",own && !entry.terminal() && !entry.cancellationRequested() && entry.stage()!=Stage.RECOVERY_REQUIRED);
                order.putBoolean("canResume",own && !entry.terminal() && entry.stage()!=Stage.RECOVERY_REQUIRED);
                order.putBoolean("refundable",!entry.dispatched());orders.add(order);
            }
            out.put("orders",orders);
            long funds=0;boolean treasury=false;
            try{funds=RoyalTreasurySnapshot.forPlayer(player).spendableValue();treasury=true;}catch(IllegalArgumentException|IllegalStateException unavailable){/* No chunk forcing. */}
            out.putBoolean("treasuryAvailable",treasury);if(treasury)out.putLong("spendable",funds);
            if(state.target==null){state.offer=null;out.putString("reason","NEED_TWO_VILLAGES");return out;}
            RoyalVillageStock stock;
            try{stock=RoyalVillageStock.forPlayer(player,state.source);}catch(IllegalStateException unavailable){state.offer=null;out.putString("reason","SOURCE_UNAVAILABLE");return out;}
            var products=stock.products().stream().limit(MAX_PRODUCTS).toList();
            if(products.stream().noneMatch(i->BuiltInRegistries.ITEM.getKey(i).equals(state.product))){state.product=products.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(products.getFirst());state.offer=null;}
            var choices=new ListTag();for(var item:products){var tag=new CompoundTag();tag.putString("id",BuiltInRegistries.ITEM.getKey(item).toString());choices.add(tag);}out.put("products",choices);
            if(state.product==null){state.offer=null;out.putString("reason","NO_GOODS");return out;}
            out.putString("product",state.product.toString());var availability=stock.availability(BuiltInRegistries.ITEM.get(state.product));
            out.putInt("physical",availability.physical());out.putInt("available",availability.available());out.putInt("retained",availability.retained());
            var quote=new RoyalShipmentService(level).previewPlayer(player,state.source,state.target,state.product,state.quantity);long now=level.getGameTime();
            if(state.offer==null || expired(state.offer,now) || !sameTerms(state.offer.quote,quote) || history.stream().anyMatch(e->e.intent().id().equals(state.offer.id)))
                state.offer=new Offer(UUID.randomUUID(),quote,now);
            out.putUUID("offer",state.offer.id);out.putLong("fee",quote.fee());out.putLong("travelTicks",quote.travelTicks());out.putLong("distance",quote.distance());
            String reason=selected.stream().anyMatch(e->!e.terminal()) ? "ORDER_PENDING" : availability.available()<state.quantity ? "INSUFFICIENT_STOCK"
                    : !treasury ? "TREASURY_UNAVAILABLE" : funds<quote.fee() ? "INSUFFICIENT_FUNDS" : "";
            out.putBoolean("canOrder",reason.isEmpty());if(!reason.isEmpty())out.putString("reason",reason);
        }catch(KingdomException authority){forget(player.getUUID());out=new CompoundTag();out.putString("reason","AUTHORITY");}
        catch(RuntimeException unavailable){
            var state=SESSIONS.get(player);if(state!=null)state.offer=null;
            out.remove("offer");out.putBoolean("canOrder",false);out.putString("reason","SERVICE_UNAVAILABLE");
        }
        return out;
    }

    public static Entry order(ServerPlayer player,UUID offerId){
        var k=PoliticsService.ruledKingdom(player);var level=player.serverLevel();var path=RoyalShipmentService.directory(level);
        var old=Files.exists(path.resolve("shipments.dat")) ? new RoyalShipmentStore(path).load(offerId) : null;
        var service=new RoyalShipmentService(level);
        if(old!=null){var i=old.intent();return service.requestPlayer(player,i.source().village(),i.destination().village(),i.product(),i.quantity(),offerId);}
        var state=session(player,k);var offer=state.offer;
        if(offer==null || !offer.id.equals(offerId) || expired(offer,level.getGameTime()))throw expired();
        var q=offer.quote;var fresh=service.previewPlayer(player,q.source().village(),q.destination().village(),q.product(),q.quantity());
        if(fresh.available()<q.quantity())throw declined();
        if(!sameTerms(q,fresh))throw expired();
        return service.requestQuotedPlayer(player,q.source().village(),q.destination().village(),q.product(),q.quantity(),offer.id,q.fee(),q.travelTicks());
    }
    private static boolean sameTerms(Quote a,Quote b){
        return a.kingdom().equals(b.kingdom()) && a.capital().equals(b.capital()) && a.actor().equals(b.actor()) && a.rulerSince()==b.rulerSince()
                && a.source().equals(b.source()) && a.destination().equals(b.destination()) && a.product().equals(b.product()) && a.quantity()==b.quantity()
                && a.distance()==b.distance() && a.fee()==b.fee() && a.travelTicks()==b.travelTicks();
    }
    private static boolean expired(Offer offer,long now){return now<offer.issued || now-offer.issued>OFFER_TICKS;}
    private static Session session(ServerPlayer player,KingdomSavedData.Kingdom k){
        var level=player.serverLevel();var data=KingdomSavedData.get(level);var ruler=data.ruler(k.id());var state=SESSIONS.computeIfAbsent(player,p->new Session());
        String dimension=level.dimension().location().toString();
        if(!dimension.equals(state.dimension) || !k.id().equals(state.kingdom) || !k.capital().equals(state.capital) || state.tenure!=ruler.sinceGameTime()){
            state.dimension=dimension;state.kingdom=k.id();state.capital=k.capital();state.tenure=ruler.sinceGameTime();state.source=k.capital();state.target=null;state.product=null;state.offer=null;
        }
        if(state.quantity>RoyalShipmentConfig.current().maximumQuantity()){state.quantity=RoyalShipmentConfig.current().maximumQuantity();state.offer=null;}
        normalize(state,data);return state;
    }
    private static void normalize(Session state,KingdomSavedData data){
        if(!state.kingdom.equals(data.owner(state.source))){state.source=state.capital;state.product=null;state.offer=null;}
        if(state.target==null || state.target.equals(state.source) || !state.kingdom.equals(data.owner(state.target))){
            state.target=data.members(state.kingdom).stream().filter(id->!id.equals(state.source)).sorted(Comparator.comparing(UUID::toString)).findFirst().orElse(null);state.offer=null;
        }
    }
    private static String name(ServerPlayer player,UUID id){var v=PoliticsService.village(player.serverLevel(),id);return v==null ? "?" : PoliticsService.name(v);}
    private static KingdomException declined(){return new KingdomException("millenairekingdoms.gui.msg.shipment_declined","This shipment selection is no longer available. Refresh and choose again.");}
    private static KingdomException expired(){return new KingdomException("millenairekingdoms.gui.msg.shipment_offer_expired","This shipment offer expired or changed. Refresh before ordering.");}
}
