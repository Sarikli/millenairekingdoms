package com.millenairekingdoms.shipping;

import com.millenairekingdoms.economy.RoyalCargoContainers;
import com.millenairekingdoms.economy.RoyalEconomyConfig;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentService.Purpose;
import com.millenairekingdoms.economy.RoyalPrices;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.shipping.RoyalShipmentStore.*;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.millenaire.culture.ModCultures;

/** Paid timed royal logistics. Existing native villages/storage/trade stay autonomous; no physical caravan or chunk
 * forcing. Each step performs at most one cargo operation or one lifecycle boundary, preserving exact bound receipts. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class RoyalShipmentService {
    public record Quote(UUID kingdom,UUID capital,UUID actor,long rulerSince,Endpoint source,Endpoint destination,
                        ResourceLocation product,int quantity,int available,long distance,long fee,long travelTicks) {}
    private static final Map<MinecraftServer,Set<UUID>> BUSY=new WeakHashMap<>();
    private final ServerLevel level;
    private final RoyalShipmentStore orders;
    private final RoyalPaymentService payments;
    private final RoyalTreasuryPersistence persistence;
    private static final class Deferred extends IllegalStateException {
        final Blocked blocked;Deferred(Blocked blocked,String reason){super(reason);this.blocked=blocked;}
    }
    public RoyalShipmentService(ServerLevel level){this(level,new RoyalTreasuryPersistence(),new RoyalPaymentService(level));}
    // Genuine filesystem/process-cut tests inject persistence, never production crash flags.
    RoyalShipmentService(ServerLevel level,RoyalTreasuryPersistence persistence,RoyalPaymentService payments){
        this.level=level;this.persistence=persistence;this.payments=payments;this.orders=new RoyalShipmentStore(directory(level));
    }
    public static Path directory(ServerLevel level){return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-shipments");}
    public Quote quoteNpc(UUID kingdom,UUID from,UUID to,ResourceLocation product,int quantity){thread();return quote(null,kingdom,from,to,product,quantity);}
    public Quote quotePlayer(ServerPlayer player,UUID from,UUID to,ResourceLocation product,int quantity){thread();dimension(player);return quote(player,PoliticsService.ruledKingdom(player).id(),from,to,product,quantity);}
    /** Read-only price/time preview also reports insufficient surplus; actual requests still require the full quantity. */
    public Quote previewPlayer(ServerPlayer player,UUID from,UUID to,ResourceLocation product,int quantity){thread();dimension(player);return quote(player,PoliticsService.ruledKingdom(player).id(),from,to,product,quantity,false);}
    public Entry requestNpc(UUID kingdom,UUID from,UUID to,ResourceLocation product,int quantity,UUID id){return request(null,kingdom,from,to,product,quantity,id,null,null);}
    public Entry requestQuotedNpc(UUID kingdom,UUID from,UUID to,ResourceLocation product,int quantity,UUID id,long fee,long travelTicks){return request(null,kingdom,from,to,product,quantity,id,fee,travelTicks);}
    public Entry requestPlayer(ServerPlayer player,UUID from,UUID to,ResourceLocation product,int quantity,UUID id){thread();dimension(player);return request(player,PoliticsService.ruledKingdom(player).id(),from,to,product,quantity,id,null,null);}
    public Entry requestQuotedPlayer(ServerPlayer player,UUID from,UUID to,ResourceLocation product,int quantity,UUID id,long fee,long travelTicks){thread();dimension(player);return request(player,PoliticsService.ruledKingdom(player).id(),from,to,product,quantity,id,fee,travelTicks);}
    public Entry resumePlayer(ServerPlayer player,UUID id){thread();dimension(player);bind(require(id),player,PoliticsService.ruledKingdom(player).id());return exclusive(id,()->advance(id,player));}
    public Entry resumeNpc(UUID kingdom,UUID id){thread();bind(require(id),null,kingdom);return exclusive(id,()->advance(id,null));}
    public Entry cancelPlayer(ServerPlayer player,UUID id){thread();dimension(player);bind(require(id),player,PoliticsService.ruledKingdom(player).id());return cancel(id);}
    public Entry cancelNpc(UUID kingdom,UUID id){thread();bind(require(id),null,kingdom);return cancel(id);}
    /** Existing work may continue offline under its original UUID/tenure/reputation; a NEW player fee waits for them. */
    public Entry observe(UUID id){thread();return exclusive(id,()->advance(id,null));}

    private Quote quote(ServerPlayer player,UUID kingdom,UUID from,UUID to,ResourceLocation product,int quantity){
        return quote(player,kingdom,from,to,product,quantity,true);
    }
    private Quote quote(ServerPlayer player,UUID kingdom,UUID from,UUID to,ResourceLocation product,int quantity,boolean requireStock){
        if(from.equals(to) || quantity<1 || quantity>RoyalShipmentConfig.current().maximumQuantity())throw new IllegalArgumentException("Choose distinct member villages and a supported positive quantity");
        var stock=player==null ? RoyalVillageStock.forNpc(level,kingdom,from) : RoyalVillageStock.forPlayer(player,from);
        var item=item(product);var available=stock.availability(item);if(requireStock && available.available()<quantity)throw new Deferred(Blocked.STOCK,"Source has only "+available.available()+" safely transferable units");
        // Target identity is saved metadata. It need not be loaded/active until a physical delivery is attempted.
        var target=endpoint(kingdom,to,player==null);var source=stock.endpoint();var data=KingdomSavedData.get(level);var k=data.kingdom(kingdom);
        long distance=(long)Math.ceil(Math.hypot((double)source.center().getX()-target.center().getX(),(double)source.center().getZ()-target.center().getZ()));
        stock.requireUnchanged();return new Quote(kingdom,k.capital(),player==null ? null : player.getUUID(),player==null ? 0 : data.ruler(kingdom).sinceGameTime(),source,target,product,quantity,available.available(),distance,
                RoyalPrices.shipment(RoyalEconomyConfig.current(),quantity,distance),RoyalShipmentConfig.transit().duration(distance));
    }
    private Endpoint endpoint(UUID kingdom,UUID villageId,boolean npc){
        var data=KingdomSavedData.get(level);var k=data.kingdom(kingdom);
        if(k==null || !k.active() || k.fallen() || !kingdom.equals(data.owner(villageId)))throw new IllegalArgumentException("Shipment villages must belong to the same active kingdom");
        var village=PoliticsService.village(level,villageId);var type=village==null ? null : ModCultures.getVillageType(village.getVillageTypeId());
        if(type==null || type.loneBuilding() || village.getTownhall()==null)throw new Deferred(Blocked.STORAGE,"Original destination village/townhall metadata is unavailable");
        if(npc && (type.playerControlled() || village.getOwnerUUID()!=null))throw new IllegalArgumentException("NPC shipping cannot command locally player-controlled villages");
        return new Endpoint(villageId,village.getVillageTypeId(),village.getTownhall().getId().uuid(),village.getCenter(),village.getOwnerUUID());
    }
    private Entry request(ServerPlayer player,UUID kingdom,UUID from,UUID to,ResourceLocation product,int quantity,UUID id,Long fee,Long duration){
        thread();return exclusive(id,()->{
            var old=orders.load(id);if(old!=null){bind(old,player,kingdom);
                if(!old.intent().source().village().equals(from) || !old.intent().destination().village().equals(to) || !old.intent().product().equals(product) || old.intent().quantity()!=quantity)throw new IllegalArgumentException("Shipment UUID belongs to another route/product/quantity");
                return advance(id,player);
            }
            var quote=quote(player,kingdom,from,to,product,quantity);
            if(fee!=null && (fee!=quote.fee || duration==null || duration!=quote.travelTicks))throw new IllegalArgumentException("Shipment price/duration changed; refresh the server offer before ordering");
            var intent=new Intent(id,level.dimension().location().toString(),kingdom,quote.capital,quote.actor,quote.rulerSince,quote.source,quote.destination,product,quantity,quote.fee,Math.max(0,level.getGameTime()),quote.travelTicks);
            orders.reserve(intent);return advance(id,player);
        });
    }
    private Entry cancel(UUID id){return exclusive(id,()->{
        var entry=require(id);if(entry.terminal() || entry.stage()==Stage.RECOVERY_REQUIRED)return entry;
        if(entry.stage()==Stage.AWAITING_DELIVERY && entry.delivered()==entry.intent().quantity() && entry.operations().stream().noneMatch(Operation::pending))return finishDelivery(entry);
        orders.returning(id);return advance(id,null);
    });}
    private Entry advance(UUID id,ServerPlayer explicitActor){
        var entry=require(id);if(entry.terminal() || entry.stage()==Stage.RECOVERY_REQUIRED)return entry;
        try{
            if(!entry.cancellationRequested() && !political(entry.intent())){orders.retire(i->i.id().equals(id));entry=require(id);}
            var pending=entry.operations().stream().filter(Operation::pending).findFirst();
            if(pending.isPresent()){
                var operation=pending.get();
                if(!entry.cancellationRequested() && (operation.role()==Role.COLLECT || operation.role()==Role.DELIVER)){
                    var paid=receipt(entry);var expected=operation.role()==Role.COLLECT ? State.PAID : State.COMMITTED;
                    if(paid==null || paid.state()!=expected)return orders.quarantine(id,"Pending physical cargo lacks its actual original fee receipt");
                }
                return settle(entry,operation);
            }
            if(entry.cancellationRequested())return cancellation(entry);
            return switch(entry.stage()){
                case PREPARING->prepareFee(entry,explicitActor);
                case COLLECTING->collectOrDispatch(entry);
                case IN_TRANSIT->transit(entry);
                case AWAITING_DELIVERY->entry.delivered()==entry.intent().quantity() ? finishDelivery(entry) : credit(entry,Role.DELIVER);
                default->entry;
            };
        }catch(BadReceipt corrupt){return orders.quarantine(id,diagnosis(corrupt));}
        catch(Deferred waiting){var current=require(id);return current.terminal() || current.stage()==Stage.RECOVERY_REQUIRED ? current : orders.block(id,waiting.blocked,diagnosis(waiting));}
    }
    private Entry prepareFee(Entry entry,ServerPlayer explicitActor){
        var intent=entry.intent();RoyalVillageStock source;
        try{source=stock(intent,intent.source(),false);}catch(IllegalArgumentException changed){orders.returning(intent.id());return cancellation(require(intent.id()));}
        if(source.availability(item(intent.product())).available()<intent.quantity())throw new Deferred(Blocked.STOCK,"Current native source needs leave insufficient surplus");
        source.requireUnchanged();var receipt=receipt(entry);
        if(receipt!=null && receipt.state()!=State.PREPARED && receipt.state()!=State.PAID)return orders.quarantine(intent.id(),"Pre-dispatch fee is already terminal or ambiguous");
        if(receipt==null || receipt.state()==State.PREPARED){
            ServerPlayer actor=explicitActor;
            if(intent.actor()!=null){if(actor==null)actor=level.getServer().getPlayerList().getPlayer(intent.actor());if(actor==null || actor.serverLevel()!=level)throw new Deferred(Blocked.PAYMENT,"Original player must be online before a new or unacknowledged fee can be charged");}
            try{receipt=actor==null ? payments.payNpc(intent.kingdom(),intent.id(),Purpose.SHIPMENT_FEE,intent.id(),intent.fee()) : payments.payPlayer(actor,intent.id(),Purpose.SHIPMENT_FEE,intent.id(),intent.fee());}
            catch(RuntimeException failure){var current=require(intent.id());if(current.cancellationRequested() || current.terminal())return current;return orders.block(intent.id(),Blocked.PAYMENT,diagnosis(failure));}
        }
        var current=require(intent.id());if(current.cancellationRequested())return cancellation(current);
        if(receipt.state()!=State.PAID)return orders.quarantine(intent.id(),"Shipment fee has not been acknowledged PAID");return orders.collecting(intent.id());
    }
    private Entry collectOrDispatch(Entry entry){
        var paid=receipt(entry);if(paid==null || paid.state()!=State.PAID)return orders.quarantine(entry.intent().id(),"Source collection lacks its actual original PAID transport fee");
        return entry.collected()==entry.intent().quantity() ? orders.dispatch(entry.intent().id(),Math.max(level.getGameTime(),entry.intent().issued())) : collection(entry);
    }
    private Entry collection(Entry entry){
        var intent=entry.intent();var source=stock(intent,intent.source(),false);int remaining=intent.quantity()-entry.collected();
        if(source.availability(item(intent.product())).available()<remaining)throw new Deferred(Blocked.STOCK,"Current native reserves leave insufficient remaining dispatch surplus");
        var slots=source.slots();var positions=source.positions();var product=new ItemStack(item(intent.product()));
        for(int half=0;half<positions.size();half++){
            int count=0;for(int slot=0;slot<27;slot++){var stack=slots.get(half*27+slot);if(ItemStack.isSameItemSameComponents(stack,product))count+=stack.getCount();}
            if(count>0)return allocate(entry,Role.COLLECT,source,positions.get(half),Math.min(count,remaining));
        }throw new Deferred(Blocked.STOCK,"No exact ordinary source cargo remains");
    }
    private Entry transit(Entry entry){
        var receipt=receipt(entry);if(receipt==null || receipt.state()!=State.PAID && receipt.state()!=State.COMMITTED)return orders.quarantine(entry.intent().id(),"Dispatched shipment lacks its original paid logistics receipt");
        if(receipt.state()==State.PAID){commit(entry);return orders.block(entry.intent().id(),Blocked.NONE,"");}
        if(level.getGameTime()<entry.due())return orders.block(entry.intent().id(),Blocked.NONE,"");return orders.awaitDelivery(entry.intent().id(),level.getGameTime());
    }
    private Entry credit(Entry entry,Role role){
        if(role==Role.DELIVER){var paid=receipt(entry);if(paid==null || paid.state()!=State.COMMITTED)return orders.quarantine(entry.intent().id(),"New delivery lacks the actual committed transport fee");}
        var intent=entry.intent();var endpoint=role==Role.RETURN ? intent.source() : intent.destination();
        RoyalVillageStock destination;
        try{destination=stock(intent,endpoint,role==Role.RETURN);}
        catch(IllegalArgumentException changed){
            if(role==Role.RETURN)throw new Deferred(Blocked.MEMBERSHIP,"Original source identity/ownership no longer permits return; cargo remains held");
            throw changed;
        }
        var product=new ItemStack(item(intent.product()));var slots=destination.slots();var positions=destination.positions();
        for(int half=0;half<positions.size();half++){
            int capacity=0;for(int slot=0;slot<27;slot++){var stack=slots.get(half*27+slot);
                if(stack.isEmpty())capacity+=Math.min(64,product.getMaxStackSize());else if(ItemStack.isSameItemSameComponents(stack,product))capacity+=Math.min(64,stack.getMaxStackSize())-stack.getCount();
            }
            if(capacity>0)return allocate(entry,role,destination,positions.get(half),Math.min(capacity,entry.allocatable()));
        }throw new Deferred(Blocked.FULL,"Original receiving town hall has no cargo capacity; remaining goods stay held");
    }
    private Entry allocate(Entry entry,Role role,RoyalVillageStock snapshot,BlockPos position,int amount){
        if(amount<1)throw new Deferred(Blocked.PHYSICAL_PROOF,"Cargo is reserved by an unresolved physical operation");
        var operation=RoyalCargoContainers.plan(level,entry.intent(),role,position,amount,persistence);snapshot.requireUnchanged();
        orders.prepare(entry.intent().id(),operation);return settle(require(entry.intent().id()),operation);
    }
    private Entry settle(Entry entry,Operation operation){
        try{return RoyalCargoContainers.settle(level,orders,entry.intent().id(),operation.id(),persistence,()->{
            var current=require(entry.intent().id());if(current.cancellationRequested() && operation.role()!=Role.RETURN)return;
            if(operation.role()!=Role.RETURN && !political(current.intent())){orders.returning(current.intent().id());return;}
            var endpoint=operation.role()==Role.DELIVER ? current.intent().destination() : current.intent().source();RoyalVillageStock view;
            try{view=stock(current.intent(),endpoint,operation.role()==Role.RETURN);}
            catch(IllegalArgumentException lost){
                if(operation.role()!=Role.RETURN){orders.returning(current.intent().id());return;}
                throw new Deferred(Blocked.MEMBERSHIP,"Original source identity/ownership no longer permits return");
            }
            if(!view.positions().contains(operation.position()))throw new Deferred(Blocked.STORAGE,"Original prepared cargo container is no longer declared by its town hall");
            if(operation.role()==Role.COLLECT && view.availability(item(current.intent().product())).available()<operation.quantity())throw new Deferred(Blocked.STOCK,"Original source now needs its reserved goods; new removal waits");
            view.requireUnchanged();
        });}finally{invalidate(entry.intent().source());invalidate(entry.intent().destination());}
    }
    private Entry cancellation(Entry entry){
        if(entry.escrow()>0)return credit(entry,Role.RETURN);
        var intent=entry.intent();var receipt=receipt(entry);
        if(receipt==null){
            // PREPARING was retained through retirement, so an absent receipt here is genuinely uncharged.
            if(entry.stage()!=Stage.PREPARING || entry.collected()!=0 || !entry.operations().isEmpty())return orders.quarantine(intent.id(),"Missing fee receipt after possible paid collection; never assume an unpaid cancellation");
            return orders.finish(intent.id());
        }
        if(entry.dispatched()){
            if(receipt.state()!=State.PAID && receipt.state()!=State.COMMITTED)return orders.quarantine(intent.id(),"Accepted dispatch has an invalid logistics fee state");commit(entry);
        }else{
            RoyalPaymentService.Receipt refunded;
            try{refunded=payments.settleRetiredShipment(intent.id(),intent.kingdom(),intent.capital(),intent.actor(),intent.fee(),true);}
            catch(IllegalArgumentException | IllegalStateException unavailable){throw new Deferred(Blocked.PAYMENT,diagnosis(unavailable));}
            if(refunded==null || refunded.state()!=State.REFUNDED)return orders.quarantine(intent.id(),"Pre-dispatch transport fee cannot be safely refunded");
        }
        return orders.finish(intent.id());
    }
    private Entry finishDelivery(Entry entry){
        var receipt=receipt(entry);if(receipt==null || receipt.state()!=State.COMMITTED)return orders.quarantine(entry.intent().id(),"Completed delivery lacks its committed original transport fee");return orders.finish(entry.intent().id());
    }
    private void commit(Entry entry){
        var intent=entry.intent();var committed=payments.acknowledgeShipment(intent.id(),intent.kingdom(),intent.capital(),intent.actor(),intent.fee());
        if(committed==null || committed.state()!=State.COMMITTED)throw new BadReceipt("Original shipment fee could not be committed");
    }
    private static final class BadReceipt extends IllegalStateException {BadReceipt(String message){super(message);}}
    private RoyalPaymentService.Receipt receipt(Entry entry){
        var intent=entry.intent();try{return payments.inspectShipment(intent.id(),intent.kingdom(),intent.capital(),intent.actor(),intent.fee());}
        catch(IllegalArgumentException mismatch){throw new BadReceipt("Shipment original fee purpose/reference/actor/capital/amount binding differs");}
    }
    private RoyalVillageStock stock(Intent intent,Endpoint endpoint,boolean returning){
        var village=PoliticsService.village(level,endpoint.village());
        if(village==null || village.getTownhall()==null)throw new Deferred(Blocked.STORAGE,"Original village/town hall records are unavailable");
        if(!village.isActive() || !level.isLoaded(village.getCenter()))throw new Deferred(Blocked.UNLOADED,"Original village is inactive/unloaded; never force chunks");
        try{return returning ? RoyalVillageStock.forReturn(level,intent) : RoyalVillageStock.forIssued(level,intent,endpoint);}
        catch(IllegalStateException unavailable){throw new Deferred(Blocked.STORAGE,diagnosis(unavailable));}
    }
    private boolean political(Intent intent){
        var data=KingdomSavedData.get(level);var k=data.kingdom(intent.kingdom());var ruler=data.ruler(intent.kingdom());
        if(k==null || !k.active() || k.fallen() || !intent.capital().equals(k.capital()) || !intent.kingdom().equals(data.owner(intent.source().village()))
                || !intent.kingdom().equals(data.owner(intent.destination().village())) || !data.mayDecide(intent.kingdom(),intent.actor())
                || (intent.actor()==null ? ruler!=null : ruler==null || !intent.actor().equals(ruler.player()) || intent.rulerSince()!=ruler.sinceGameTime()))return false;
        if(intent.actor()!=null){try{if(PoliticsService.reputation(level,k,intent.actor())<PoliticsService.DECISION_REPUTATION)return false;}catch(IllegalArgumentException missing){throw new Deferred(Blocked.AUTHORITY,"Original capital reputation is unavailable");}}
        try{return intent.source().equals(endpoint(intent.kingdom(),intent.source().village(),intent.actor()==null)) && intent.destination().equals(endpoint(intent.kingdom(),intent.destination().village(),intent.actor()==null));}
        catch(IllegalArgumentException changed){return false;}
    }
    private void bind(Entry entry,ServerPlayer player,UUID kingdom){
        var intent=entry.intent();var data=KingdomSavedData.get(level);var k=data.kingdom(kingdom);var ruler=data.ruler(kingdom);
        if(!intent.dimension().equals(level.dimension().location().toString()) || !intent.kingdom().equals(kingdom) || k==null || !k.capital().equals(intent.capital())
                || !Objects.equals(intent.actor(),player==null ? null : player.getUUID()) || !data.mayDecide(kingdom,intent.actor())
                || (player!=null && (ruler==null || ruler.sinceGameTime()!=intent.rulerSince() || !PoliticsService.ruledKingdom(player).id().equals(kingdom))))throw new IllegalArgumentException("Shipment belongs to another original ruler/capital/tenure/dimension");
    }
    private void invalidate(Endpoint endpoint){var village=PoliticsService.village(level,endpoint.village());if(village!=null && village.getTownhall()!=null && village.getTownhall().getInventory()!=null)village.getTownhall().getInventory().invalidateCache();}
    private Entry require(UUID id){var entry=orders.load(id);if(entry==null)throw new IllegalArgumentException("Unknown royal shipment");if(!entry.intent().dimension().equals(level.dimension().location().toString()))throw new IllegalArgumentException("Shipment belongs to another dimension");return entry;}
    private static Item item(ResourceLocation id){return BuiltInRegistries.ITEM.getOptional(id).orElseThrow(()->new IllegalArgumentException("Unknown shipment product"));}
    private <T>T exclusive(UUID id,java.util.function.Supplier<T> action){thread();var busy=BUSY.computeIfAbsent(level.getServer(),key->new HashSet<>());if(!busy.add(id))throw new IllegalStateException("Shipment is already being processed");try{return action.get();}finally{busy.remove(id);}}
    public static void clearRuntime(MinecraftServer server){BUSY.remove(server);}
    private static String diagnosis(Exception failure){var message=failure.getMessage();return message==null ? failure.getClass().getSimpleName() : message.substring(0,Math.min(512,message.length()));}
    private void dimension(ServerPlayer player){if(player.serverLevel()!=level)throw new IllegalArgumentException("Ruler changed dimension");}
    private void thread(){if(!level.getServer().isSameThread())throw new IllegalStateException("Royal shipments require the server thread");}
}
