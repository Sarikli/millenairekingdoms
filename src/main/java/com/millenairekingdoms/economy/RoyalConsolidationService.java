package com.millenairekingdoms.economy;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.economy.RoyalPaymentJournal.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/** Conserving server maintenance, never income/spending. One independently persisted chest changes per operation. */
public final class RoyalConsolidationService {
    public enum Status { NO_CHANGE, COMMITTED, DEFERRED, RECOVERY_REQUIRED }
    public record Result(UUID id,Status status,long beforeValue,long afterValue,String reason){}
    private final ServerLevel level;
    private final RoyalTreasuryPersistence persistence;
    public RoyalConsolidationService(ServerLevel level){this(level,new RoyalTreasuryPersistence());}
    RoyalConsolidationService(ServerLevel level,RoyalTreasuryPersistence persistence){this.level=level;this.persistence=persistence;}
    public static Path directory(ServerLevel level){return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-consolidation");}
    private RoyalPaymentJournal journal(){return new RoyalPaymentJournal(directory(level));}
    public List<Entry> current(){return current(level);}
    static List<Entry> current(ServerLevel level){
        var root=directory(level);if(!Files.exists(root))return List.of();
        try(var files=Files.list(root)){if(files.filter(p->p.getFileName().toString().endsWith(".dat")).limit(257).count()>256)throw new IllegalStateException("Consolidation active index exceeds its bounded recovery budget; originals preserved");}catch(IOException e){throw new IllegalStateException("Consolidation index unavailable",e);}
        var entries=new RoyalPaymentJournal(root).entries();entries.forEach(RoyalConsolidationService::intent);return entries;
    }
    public Entry inspect(UUID id){var live=directory(level).resolve(id+".dat");var archived=directory(level).resolve("archive").resolve(id+".dat");if(Files.exists(live) && Files.exists(archived))throw new IllegalStateException("Duplicate consolidation journal identity");var file=Files.exists(live)?live:archived;if(!Files.exists(file))return null;var entry=new RoyalPaymentJournal(file.getParent()).load(id);intent(entry);return entry;}
    public Result consolidate(UUID kingdom){
        thread();var data=KingdomSavedData.get(level);var k=data.kingdom(kingdom);if(k==null || !k.active() || k.fallen())return new Result(null,Status.DEFERRED,0,0,"Current active capital is unavailable");
        return RoyalPaymentService.withCapitalLock(level,k.capital(),()->{
            for(var entry:current())if(entry.intent().getString("dimension").equals(dimension()) && entry.intent().getUUID("capital").equals(k.capital()) && entry.state()!=State.COMMITTED && entry.state()!=State.CANCELLED)return settle(entry);
            var quote=RoyalTreasurySnapshot.forMaintenance(level,kingdom);guard(quote,null);var slots=quote.slots();
            for(int start=0;start<slots.size();start+=27){
                var before=slots.subList(start,start+27);RoyalCoinPlan.Plan part;try{part=RoyalCoinPlan.consolidate(before);}catch(IllegalArgumentException noRoom){continue;}if(!useful(before,part.after()))continue;
                var after=new ArrayList<ItemStack>(slots);var replacement=part.after();for(int n=0;n<27;n++)after.set(start+n,replacement.get(n));long value=RoyalCoinPlan.spendableValue(slots);
                var plan=new RoyalCoinPlan.Plan(slots,after,value,value);UUID id=UUID.randomUUID();var request=new CompoundTag();request.putInt("schema",1);request.putString("kind","CONSOLIDATION");request.putString("dimension",dimension());request.putUUID("kingdom",kingdom);request.putUUID("capital",k.capital());request.putUUID("reference",id);request.putLong("position",quote.positions().get(start/27).asLong());request.putLong("amount",0);request.putLong("value",value);
                var image=RoyalTreasuryImage.create(level,quote,plan,id,request,false);image.requireConservation();persistence.flush(level,quote.positions());quote.requireUnchanged();persistence.verify(level,image,false);
                var recovery=new CompoundTag();recovery.put("image",image.save(level));var entry=journal().prepare(id,request,recovery);return settle(entry);
            }
            return new Result(null,Status.NO_CHANGE,quote.spendableValue(),quote.spendableValue(),"No useful denomination conversion or stack merge");
        });
    }
    /** Existing acknowledged work may finish even when new maintenance is disabled; never issue another conversion. */
    public Result recover(UUID id){thread();var entry=inspect(id);if(entry==null)throw new IllegalArgumentException("Unknown consolidation operation");var request=intent(entry);if(!request.getString("dimension").equals(dimension()))throw new IllegalArgumentException("Consolidation belongs to another dimension");return RoyalPaymentService.withCapitalLock(level,request.getUUID("capital"),()->settle(entry));}
    private Result settle(Entry entry){
        var request=intent(entry);if(!request.getString("dimension").equals(dimension()))throw new IllegalArgumentException("Consolidation dimension changed");var image=image(entry);
        if(entry.state()==State.RECOVERY_REQUIRED)return result(entry,Status.RECOVERY_REQUIRED,"Unreceipted/incompatible physical evidence requires review; no inventory mutation");
        if(entry.state()==State.COMMITTED || entry.state()==State.CANCELLED){archive(entry);return result(entry,entry.state()==State.COMMITTED?Status.COMMITTED:Status.NO_CHANGE,"Existing terminal conservation receipt");}
        if(entry.state()==State.PAID){var done=journal().transition(entry.id(),State.PAID,State.COMMITTED,entry.recovery());archive(done);return result(done,Status.COMMITTED,"Previously acknowledged physical conservation; no new inventory access");}
        var quote=RoyalTreasurySnapshot.forMaintenance(level,request.getUUID("kingdom"));if(!quote.capital().equals(request.getUUID("capital")))throw new IllegalStateException("Original consolidation capital changed; retain evidence");guard(quote,entry.id());
        List<ItemStack> after;
        try{after=image.reconcile(level,quote);}catch(IllegalStateException incompatible){var recovery=entry.recovery();recovery.putString("diagnostic",bound(incompatible.getMessage()));var quarantined=journal().transition(entry.id(),State.PREPARED,State.RECOVERY_REQUIRED,recovery);return result(quarantined,Status.RECOVERY_REQUIRED,incompatible.getMessage());}
        // A single live BE receives slot changes and its own receipt without a server-thread yield.
        quote.applyConservingSingle(after);image.record(level);persistence.flush(level,quote.positions());var current=RoyalTreasurySnapshot.forMaintenance(level,request.getUUID("kingdom"));image.requireAfter(level,current,after);persistence.verifyApplied(level,image,after);
        var acknowledged=journal().transition(entry.id(),State.PREPARED,State.PAID,entry.recovery());var done=journal().transition(entry.id(),State.PAID,State.COMMITTED,acknowledged.recovery());
        try{image.requireStorage(level,current);image.cleanup(level);persistence.flush(level,current.positions());}catch(RuntimeException cleanup){com.mojang.logging.LogUtils.getLogger().warn("[Kingdoms] Consolidation {} conserved value; physical proof cleanup retained: {}",entry.id(),cleanup.getMessage());}
        archive(done);return result(done,Status.COMMITTED,"Exact physical value conserved and verified from saved chunks");
    }
    private void guard(RoyalTreasurySnapshot quote,UUID own){
        quote.requireUnchanged();var root=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-payments");
        if(Files.exists(root))for(var entry:new RoyalPaymentJournal(root).entries()){
            if(entry.state()==State.COMMITTED || entry.state()==State.CANCELLED || entry.state()==State.REFUNDED)continue;var request=entry.intent();if(!request.contains("dimension",Tag.TAG_STRING) || !request.hasUUID("capital"))throw new IllegalStateException("Unknown unresolved payment authority; consolidation deferred");
            if(request.getString("dimension").equals(dimension()) && request.getUUID("capital").equals(quote.capital()))throw new IllegalStateException("Capital has active payment/refund/recovery reservation "+entry.id()+"/"+entry.state());
        }
        RoyalEconomicReservations.requireTreasuryAvailable(level,quote.positions(),own);
    }
    static void requireAvailable(ServerLevel level,List<BlockPos> positions,UUID own){
        var requested=new HashSet<>(positions);for(var entry:current(level)){
            if(entry.id().equals(own) || entry.state()==State.COMMITTED || entry.state()==State.CANCELLED)continue;var request=intent(entry);if(!request.getString("dimension").equals(level.dimension().location().toString()))continue;
            var image=image(level,entry);if(image.chests().stream().anyMatch(c->requested.contains(c.position())))throw new IllegalStateException("Physical container has unresolved consolidation "+entry.id()+"/"+entry.state());
        }
    }
    private RoyalTreasuryImage image(Entry entry){return image(level,entry);}
    private static RoyalTreasuryImage image(ServerLevel level,Entry entry){
        var recovery=entry.recovery();if(!Set.of("image","diagnostic").containsAll(recovery.getAllKeys()) || !recovery.contains("image",Tag.TAG_COMPOUND) || recovery.contains("diagnostic") && !recovery.contains("diagnostic",Tag.TAG_STRING))throw new IllegalStateException("Unsupported consolidation recovery image");var image=RoyalTreasuryImage.read(level,recovery.getCompound("image"));image.requireBinding(entry.id(),intent(entry),false);image.requireConservation();
        if(image.value(false)!=entry.intent().getLong("value") || image.value(true)!=image.value(false) || image.changedPosition().asLong()!=entry.intent().getLong("position"))throw new IllegalStateException("Consolidation historical value/container binding changed");return image;
    }
    private static CompoundTag intent(Entry entry){
        var t=entry.intent();if(!t.getAllKeys().equals(Set.of("schema","kind","dimension","kingdom","capital","reference","position","amount","value")) || !t.contains("schema",Tag.TAG_INT) || t.getInt("schema")!=1 || !t.getString("kind").equals("CONSOLIDATION") || !t.contains("dimension",Tag.TAG_STRING) || net.minecraft.resources.ResourceLocation.tryParse(t.getString("dimension"))==null || !t.hasUUID("kingdom") || !t.hasUUID("capital") || !t.hasUUID("reference") || !t.getUUID("reference").equals(entry.id()) || !t.contains("position",Tag.TAG_LONG) || !t.contains("amount",Tag.TAG_LONG) || t.getLong("amount")!=0 || !t.contains("value",Tag.TAG_LONG) || t.getLong("value")<0 || !Set.of(State.PREPARED,State.PAID,State.COMMITTED,State.CANCELLED,State.RECOVERY_REQUIRED).contains(entry.state()))throw new IllegalStateException("Unsupported consolidation intent; original journal preserved");return t;
    }
    private void archive(Entry entry){
        var source=directory(level).resolve(entry.id()+".dat");if(!Files.exists(source))return;var target=directory(level).resolve("archive").resolve(source.getFileName());
        if(Files.exists(target))throw new IllegalStateException("Consolidation archive identity collision; preserve both originals");
        try{Files.createDirectories(target.getParent());Files.move(source,target,StandardCopyOption.ATOMIC_MOVE);sync(directory(level));sync(target.getParent());}catch(IOException e){throw new IllegalStateException("Conservation acknowledged; journal archival deferred",e);}
    }
    private Result result(Entry entry,Status status,String reason){long value=entry.intent().getLong("value");return new Result(entry.id(),status,value,value,bound(reason));}
    private static boolean useful(List<ItemStack> before,List<ItemStack> after){long countBefore=before.stream().filter(s->RoyalCoinPlan.spendableValue(List.of(s))>0).mapToLong(ItemStack::getCount).sum(),countAfter=after.stream().filter(s->RoyalCoinPlan.spendableValue(List.of(s))>0).mapToLong(ItemStack::getCount).sum();return countAfter<countBefore || after.stream().filter(s->RoyalCoinPlan.spendableValue(List.of(s))>0).count()<before.stream().filter(s->RoyalCoinPlan.spendableValue(List.of(s))>0).count();}
    private static void sync(Path dir)throws IOException{try(var ch=FileChannel.open(dir,StandardOpenOption.READ)){ch.force(true);}}
    private String dimension(){return level.dimension().location().toString();}
    private static String bound(String text){return text.substring(0,Math.min(512,text.length()));}
    private void thread(){if(!level.getServer().isSameThread())throw new IllegalStateException("Consolidation requires the server thread");}
}
