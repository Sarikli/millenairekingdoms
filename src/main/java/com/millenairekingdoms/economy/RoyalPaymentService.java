package com.millenairekingdoms.economy;

import com.millenairekingdoms.economy.RoyalPaymentJournal.Entry;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Server-owned real-coin payments. Consumers must only fulfill PAID receipts for the bound purpose/reference.
 * PAID reserves the capital until explicit fulfillment (commit) or cancellation (refund). A commit is nonrefundable.
 * No player payload may select the NPC entry point. No gameplay consumer is wired until its own durable lifecycle exists. */
public final class RoyalPaymentService {
    public enum Purpose { CONSTRUCTION, SHIPMENT_FEE, ADMINISTRATIVE }
    public record Receipt(UUID id, State state, long amount, Purpose purpose, UUID reference) {}
    private static final Map<MinecraftServer,Set<UUID>> BUSY=new WeakHashMap<>();
    private final ServerLevel level;
    private final RoyalPaymentJournal journal;
    private final RoyalTreasuryPersistence persistence;

    public RoyalPaymentService(ServerLevel level) {
        this(level,level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-payments"),new RoyalTreasuryPersistence());
    }
    // Package visibility permits genuine interrupted-I/O fixtures without a production crash switch.
    RoyalPaymentService(ServerLevel level,Path directory,RoyalTreasuryPersistence persistence) {
        this.level=level; this.journal=new RoyalPaymentJournal(directory); this.persistence=persistence;
    }

    public Receipt payNpc(UUID kingdom,UUID id,Purpose purpose,UUID reference,long amount) {
        return pay(()->RoyalTreasurySnapshot.forNpc(level,kingdom),null,id,purpose,reference,amount);
    }
    public Receipt payPlayer(ServerPlayer player,UUID id,Purpose purpose,UUID reference,long amount) {
        requireDimension(player);
        return pay(()->RoyalTreasurySnapshot.forPlayer(player),player.getUUID(),id,purpose,reference,amount);
    }
    public Receipt commitNpc(UUID kingdom,UUID id) { return finish(()->RoyalTreasurySnapshot.forNpc(level,kingdom),null,id,false); }
    public Receipt commitPlayer(ServerPlayer player,UUID id) {
        requireDimension(player); return finish(()->RoyalTreasurySnapshot.forPlayer(player),player.getUUID(),id,false);
    }
    public Receipt refundNpc(UUID kingdom,UUID id) { return finish(()->RoyalTreasurySnapshot.forNpc(level,kingdom),null,id,true); }
    public Receipt refundPlayer(ServerPlayer player,UUID id) {
        requireDimension(player); return finish(()->RoyalTreasurySnapshot.forPlayer(player),player.getUUID(),id,true);
    }

    /** Authorized receipt inspection needs no physical chunk access; it never reconciles or mutates payment. */
    public Receipt inspectNpc(UUID kingdom,UUID id) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Royal payments require the server thread");
        if(!KingdomSavedData.get(level).mayDecide(kingdom,null)) throw new IllegalArgumentException("NPC agency no longer governs this kingdom");
        return inspect(kingdom,null,id);
    }
    public Receipt inspectPlayer(ServerPlayer player,UUID id) {
        requireDimension(player);
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Royal payments require the server thread");
        return inspect(PoliticsService.ruledKingdom(player).id(),player.getUUID(),id);
    }
    private Receipt inspect(UUID kingdom,UUID actor,UUID id) {
        var entry=journal.load(id); if(entry==null) return null; var request=Request.read(id,entry.intent());
        if(!request.dimension.equals(level.dimension().location().toString()) || !request.kingdom.equals(kingdom)
                || !request.capital.equals(KingdomSavedData.get(level).kingdom(kingdom).capital()) || !Objects.equals(request.actor,actor)) {
            throw new IllegalArgumentException("Receipt does not belong to this dimension, capital and ruler");
        }
        return receipt(request,entry);
    }

    /** Trusted order-recovery endpoint, never a payload action or a source of new spending. The caller must already
     * have a durable CANCELLING authority-retirement decision. Reconcile only a fully matching original receipt.
     * Refunds stay in the same still-owned original physical capital, even when the original player is offline.
     * Accepted patronage commits without touching inventories; no old ruler is impersonated as an NPC. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public Receipt settleRetiredConstruction(UUID id,UUID kingdom,UUID capital,UUID actor,long amount,boolean refund) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Royal recovery requires the server thread");
        var entry=journal.load(id); if(entry==null) return null;
        var request=new Request(id,level.dimension().location().toString(),kingdom,capital,actor,Purpose.CONSTRUCTION,id,amount);
        if(!entry.intent().equals(request.save())) throw new IllegalArgumentException("Retired order does not match the original construction payment");
        if(refund) {
            if(entry.state()==State.REFUNDED) return receipt(request,entry); // Acknowledged terminal replay never needs a new inventory access.
            if(entry.state()!=State.PREPARED && entry.state()!=State.PAID && entry.state()!=State.REFUND_PREPARED && entry.state()!=State.REFUNDED) return receipt(request,entry);
            return finish(()->RoyalTreasurySnapshot.forRecovery(level,kingdom,capital),actor,id,true);
        }
        return acknowledgeConstruction(id,kingdom,capital,actor,amount);
    }

    /** Internal domain inspection: a caller must load the immutable order and validate its authority/stage separately.
     * Full original intent binding is checked, including offline player UUID. No inventories or new spending. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public Receipt inspectConstruction(UUID id,UUID kingdom,UUID capital,UUID actor,long amount) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Royal recovery requires the server thread");
        var entry=journal.load(id); if(entry==null) return null;
        var request=new Request(id,level.dimension().location().toString(),kingdom,capital,actor,Purpose.CONSTRUCTION,id,amount);
        if(!entry.intent().equals(request.save())) throw new IllegalArgumentException("Order does not match the original construction payment");
        return receipt(request,entry);
    }

    /** Close only an existing PAID receipt after the domain has acknowledged original queue/building acceptance.
     * This never creates/reconciles a debit, requires no chunks and does not grant an offline actor NPC agency. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public Receipt acknowledgeConstruction(UUID id,UUID kingdom,UUID capital,UUID actor,long amount) {
        var receipt=inspectConstruction(id,kingdom,capital,actor,amount);
        if(receipt==null) return null;
        return exclusive(capital,()->{
            var entry=journal.load(id); var request=Request.read(id,entry.intent());
            if(entry.state()!=State.PAID) return receipt(request,entry);
            guard(request);
            return receipt(request,journal.transition(id,State.PAID,State.COMMITTED,entry.recovery()));
        });
    }

    private Receipt pay(Supplier<RoyalTreasurySnapshot> source,UUID actor,UUID id,Purpose purpose,UUID reference,long amount) {
        var quote=source.get(); var request=new Request(id,level.dimension().location().toString(),quote.kingdom(),quote.capital(),actor,purpose,reference,amount);
        return exclusive(quote.capital(),()->{
            Entry entry=journal.load(id);
            if(entry!=null) {
                if(!entry.intent().equals(request.save())) throw new IllegalArgumentException("Payment ID belongs to another kingdom, actor, purpose, reference or amount");
                if(terminal(entry.state())) return receipt(request,entry);
                guard(request);
                return settle(source,request,entry);
            }
            guard(request);
            quote.requireUnchanged();
            RoyalEconomicReservations.requireTreasuryAvailable(level,quote.positions());
            var plan=RoyalCoinPlan.debitSeparate(quote.slots(),27,amount);
            var image=RoyalTreasuryImage.create(level,quote,plan,request.id,request.save(),false);
            // Identities and complete original contents must exist on disk before any financial intent or mutation.
            persistence.flush(level,quote.positions()); quote.requireUnchanged(); persistence.verify(level,image,false);
            var recovery=new CompoundTag(); recovery.put("debit",image.save(level));
            entry=journal.prepare(id,request.save(),recovery);
            return settle(source,request,entry);
        });
    }

    private Receipt finish(Supplier<RoyalTreasurySnapshot> source,UUID actor,UUID id,boolean refund) {
        var quote=source.get();
        return exclusive(quote.capital(),()->{
            Entry entry=journal.load(id);
            if(entry==null) throw new IllegalArgumentException("Unknown royal payment");
            var request=Request.read(id,entry.intent());
            if(!request.dimension.equals(level.dimension().location().toString()) || !request.kingdom.equals(quote.kingdom())
                    || !request.capital.equals(quote.capital()) || !Objects.equals(request.actor,actor)) {
                throw new IllegalArgumentException("Payment does not belong to this capital and ruler");
            }
            if((refund && entry.state()==State.REFUNDED) || (!refund && entry.state()==State.COMMITTED)) return receipt(request,entry);
            guard(request);
            if(refund) {
                // Cancellation after an interrupted debit first reconciles that exact debit, then refunds it.
                // If interrupted between PAID and REFUND_PREPARED, retry this cancellation; never fulfill the domain action.
                if(entry.state()==State.PREPARED) {
                    settle(source,request,entry);
                    entry=journal.load(id);
                    if(entry.state()==State.RECOVERY_REQUIRED) return receipt(request,entry);
                }
                if(entry.state()==State.REFUNDED) return receipt(request,entry);
                if(entry.state()==State.REFUND_PREPARED) return settle(source,request,entry);
                if(entry.state()!=State.PAID) throw new IllegalStateException("Only an unfulfilled PAID payment can be refunded: "+entry.state());
                var recovery=entry.recovery(); var debit=image(recovery,"debit",request,false);
                var refundQuote=source.get();
                try { debit.requireStorage(level,refundQuote); }
                catch(IllegalStateException e) { return quarantine(request,entry,e); }
                // Refunds add the original amount to CURRENT stock, preserving later legitimate changes.
                RoyalEconomicReservations.requireTreasuryAvailable(level,refundQuote.positions());
                var plan=debit.refundPlan(refundQuote);
                var image=RoyalTreasuryImage.create(level,refundQuote,plan,request.id,request.save(),true);
                persistence.flush(level,refundQuote.positions()); refundQuote.requireUnchanged(); persistence.verify(level,image,false);
                recovery.put("refund",image.save(level));
                entry=journal.transition(id,State.PAID,State.REFUND_PREPARED,recovery);
                return settle(source,request,entry);
            }
            if(entry.state()==State.COMMITTED) return receipt(request,entry);
            if(entry.state()!=State.PAID) throw new IllegalStateException("Only a PAID payment can be committed: "+entry.state());
            quote.requireUnchanged();
            return receipt(request,journal.transition(id,State.PAID,State.COMMITTED,entry.recovery()));
        });
    }

    private Receipt settle(Supplier<RoyalTreasurySnapshot> source,Request request,Entry entry) {
        if(entry.state()!=State.PREPARED && entry.state()!=State.REFUND_PREPARED) return receipt(request,entry);
        boolean refund=entry.state()==State.REFUND_PREPARED;
        var image=image(entry.recovery(),refund ? "refund" : "debit",request,refund);
        var quote=source.get();
        if(!request.capital.equals(quote.capital())) throw new IllegalStateException("Capital changed; retain payment for explicit recovery");
        java.util.List<net.minecraft.world.item.ItemStack> after;
        try { after=image.reconcile(level,quote); }
        catch(IllegalStateException e) { return quarantine(request,entry,e); }
        // Revalidation inside apply precedes the first slot write. The server thread does not yield during mutation.
        var before=quote.slots();boolean changes=false;
        for(int slot=0;slot<before.size();slot++)if(!net.minecraft.world.item.ItemStack.matches(before.get(slot),after.get(slot))){changes=true;break;}
        // Already applied proof reconciliation has no new delta and may release a prior financial reservation.
        if(changes)RoyalEconomicReservations.requireTreasuryAvailable(level,quote.positions());
        quote.apply(after);
        // No server-thread yield or native save occurs between the slot writes and these same-BE proofs.
        image.record(level);
        persistence.flush(level,quote.positions());
        var current=source.get(); image.requireAfter(level,current,after);
        // This must follow acknowledged chunk writes AND a flush, not a queued/pending in-memory IOWorker read.
        persistence.verifyApplied(level,image,after);
        var acknowledged=journal.transition(request.id,entry.state(),refund ? State.REFUNDED : State.PAID,entry.recovery());
        // A cleanup failure cannot undo an acknowledged financial result. Its proof remains safe to retain on disk.
        try {
            image.requireStorage(level,current); image.cleanup(level); persistence.flush(level,current.positions());
        } catch(RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("Acknowledged royal payment {} retained physical receipt cleanup: {}",request.id,failure.toString());
        }
        return receipt(request,acknowledged);
    }

    private RoyalTreasuryImage image(CompoundTag recovery,String key,Request request,boolean refund) {
        if(!recovery.contains(key,Tag.TAG_COMPOUND)) throw new IllegalStateException("Missing treasury recovery image; preserve journal");
        var image=RoyalTreasuryImage.read(level,recovery.getCompound(key));
        image.requireBinding(request.id,request.save(),refund);
        image.requireDirection(refund);
        long delta=Math.subtractExact(image.value(true),image.value(false));
        if(delta!=(refund ? request.amount : -request.amount)) throw new IllegalStateException("Treasury image value does not match payment; preserve journal");
        return image;
    }

    private Receipt quarantine(Request request,Entry entry,IllegalStateException cause) {
        var recovery=entry.recovery(); recovery.putString("diagnosis",cause.getMessage());
        return receipt(request,journal.transition(request.id,entry.state(),State.RECOVERY_REQUIRED,recovery));
    }

    private void guard(Request request) {
        for(Entry other:journal.entries()) {
            if(other.id().equals(request.id) || other.state()==State.COMMITTED || other.state()==State.REFUNDED || other.state()==State.CANCELLED) continue;
            var pending=Request.read(other.id(),other.intent());
            if(pending.dimension.equals(request.dimension) && pending.capital.equals(request.capital)) {
                throw new IllegalStateException("Capital has unresolved payment "+other.id()+" ("+other.state()+"); settle it before another expense");
            }
        }
    }

    private <T> T exclusive(UUID capital,Supplier<T> action) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Royal payments require the server thread");
        Set<UUID> busy=BUSY.computeIfAbsent(level.getServer(),key->new HashSet<>());
        if(!busy.add(capital)) throw new IllegalStateException("Capital payment is already in progress");
        try { return action.get(); } finally { busy.remove(capital); }
    }
    private void requireDimension(ServerPlayer player) {
        if(player.serverLevel()!=level) throw new IllegalArgumentException("Ruler is in another dimension");
    }
    private static Receipt receipt(Request request,Entry entry) {
        return new Receipt(request.id,entry.state(),request.amount,request.purpose,request.reference);
    }
    private static boolean terminal(State state) { return state==State.COMMITTED || state==State.REFUNDED || state==State.CANCELLED; }

    private record Request(UUID id,String dimension,UUID kingdom,UUID capital,UUID actor,Purpose purpose,UUID reference,long amount) {
        Request {
            Objects.requireNonNull(id); Objects.requireNonNull(dimension); Objects.requireNonNull(kingdom); Objects.requireNonNull(capital);
            Objects.requireNonNull(purpose); Objects.requireNonNull(reference);
            if(amount<=0) throw new IllegalArgumentException("Royal payment amount must be positive");
        }
        CompoundTag save() {
            var tag=new CompoundTag(); tag.putInt("format",1); tag.putString("dimension",dimension); tag.putUUID("kingdom",kingdom); tag.putUUID("capital",capital);
            tag.putString("agency",actor==null ? "NPC" : "PLAYER"); if(actor!=null) tag.putUUID("actor",actor);
            tag.putString("purpose",purpose.name()); tag.putUUID("reference",reference); tag.putLong("amount",amount); return tag;
        }
        static Request read(UUID id,CompoundTag tag) {
            if(tag.getInt("format")!=1 || tag.getString("dimension").isBlank() || !tag.hasUUID("kingdom") || !tag.hasUUID("capital")
                    || !tag.hasUUID("reference") || !tag.contains("amount",Tag.TAG_LONG)
                    || !(tag.getString("agency").equals("NPC") || tag.getString("agency").equals("PLAYER"))
                    || (tag.getString("agency").equals("PLAYER") && !tag.hasUUID("actor"))) {
                throw new IllegalStateException("Unsupported/incomplete royal payment intent; preserve journal");
            }
            return new Request(id,tag.getString("dimension"),tag.getUUID("kingdom"),tag.getUUID("capital"),
                    tag.getString("agency").equals("PLAYER") ? tag.getUUID("actor") : null,Purpose.valueOf(tag.getString("purpose")),tag.getUUID("reference"),tag.getLong("amount"));
        }
    }
}
