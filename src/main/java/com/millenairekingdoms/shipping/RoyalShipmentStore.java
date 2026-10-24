package com.millenairekingdoms.shipping;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Own durable shipment accounting. A stage or an acknowledgement here is NOT physical inventory/payment proof.
 * Only the future server coordinator may acknowledge operations after native contents/proof read-back. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class RoyalShipmentStore {
    private static final int FORMAT=1,MAX_SHIPMENTS=10_000,MAX_OPERATIONS=512;
    private static final long MAX_BYTES=16L*1024*1024;
    public enum Stage { PREPARING, COLLECTING, IN_TRANSIT, AWAITING_DELIVERY, RETURNING, DELIVERED, CANCELLED, RECOVERY_REQUIRED }
    public enum Role { COLLECT, DELIVER, RETURN }
    public enum Outcome { PREPARED, APPLIED, CANCELLED }
    public enum Blocked { NONE, UNLOADED, FULL, STOCK, AUTHORITY, MEMBERSHIP, STORAGE, PAYMENT, PHYSICAL_PROOF }

    public record Endpoint(UUID village,ResourceLocation type,UUID townhall,BlockPos center,UUID localOwner) {
        public Endpoint { Objects.requireNonNull(village);Objects.requireNonNull(type);Objects.requireNonNull(townhall);center=Objects.requireNonNull(center).immutable();if(!BlockPos.of(center.asLong()).equals(center))throw new IllegalArgumentException("Shipment center is outside native packed coordinate range"); }
    }
    public record Intent(UUID id,String dimension,UUID kingdom,UUID capital,UUID actor,long rulerSince,Endpoint source,Endpoint destination,
                         ResourceLocation product,int quantity,long fee,long issued,long travelTicks) {
        public Intent {
            Objects.requireNonNull(id);Objects.requireNonNull(kingdom);Objects.requireNonNull(capital);Objects.requireNonNull(source);Objects.requireNonNull(destination);Objects.requireNonNull(product);
            if(dimension==null || ResourceLocation.tryParse(dimension)==null || source.village.equals(destination.village) || source.townhall.equals(destination.townhall)
                    || quantity<1 || quantity>65536 || fee<1 || issued<0 || travelTicks<1 || rulerSince<0 || actor==null && rulerSince!=0)
                throw new IllegalArgumentException("Invalid immutable royal shipment intent");
            dimension=ResourceLocation.parse(dimension).toString();Math.addExact(issued,travelTicks);
        }
    }
    /** Native full-slot payload is immutable. Its interpretation/value must additionally be checked with server registries. */
    public record Operation(UUID id,Role role,UUID container,BlockPos position,int quantity,CompoundTag image,Outcome outcome) {
        public Operation {
            Objects.requireNonNull(id);Objects.requireNonNull(role);Objects.requireNonNull(container);Objects.requireNonNull(outcome);position=Objects.requireNonNull(position).immutable();
            if(!BlockPos.of(position.asLong()).equals(position))throw new IllegalArgumentException("Cargo position is outside native packed coordinate range");
            if(quantity<1 || quantity>65536)throw new IllegalArgumentException("Invalid physical shipment quantity");
            image=Objects.requireNonNull(image).copy();keys(image,Set.of("format","state","before","after"));
            if(!image.contains("format",Tag.TAG_INT) || image.getInt("format")!=1 || !image.contains("state",Tag.TAG_COMPOUND))throw new IllegalArgumentException("Unsupported physical cargo image");
            for(var key:List.of("before","after"))if(!image.contains(key,Tag.TAG_LIST) || !(image.get(key) instanceof ListTag slots)
                    || slots.size()!=27 || slots.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Incomplete physical cargo slot image");
        }
        @Override public CompoundTag image(){return image.copy();}
        public boolean applied(){return outcome==Outcome.APPLIED;}
        public boolean pending(){return outcome==Outcome.PREPARED;}
    }
    public record Entry(Intent intent,Stage stage,Blocked blocked,String diagnosis,long due,boolean cancellationRequested,List<Operation> operations) {
        public Entry {
            Objects.requireNonNull(intent);Objects.requireNonNull(stage);Objects.requireNonNull(blocked);Objects.requireNonNull(diagnosis);operations=List.copyOf(operations);
            if(diagnosis.length()>512 || due< -1 || operations.size()>MAX_OPERATIONS)throw new IllegalArgumentException("Invalid shipment lifecycle extent");
            var ids=new HashSet<UUID>();for(var op:operations)if(!ids.add(op.id))throw new IllegalArgumentException("Duplicate shipment operation identity");
            int collected=sum(operations,Role.COLLECT,true),planned=sum(operations,Role.COLLECT,false);
            int delivered=sum(operations,Role.DELIVER,true),returned=sum(operations,Role.RETURN,true);
            int allocated=Math.addExact(sum(operations,Role.DELIVER,false),sum(operations,Role.RETURN,false));
            if(planned>intent.quantity || allocated>collected)throw new IllegalArgumentException("Shipment allocates cargo that was never acknowledged collected");
            if(stage==Stage.PREPARING && (!operations.isEmpty() || due!=-1) || stage==Stage.COLLECTING && due!=-1)
                throw new IllegalArgumentException("Invalid pre-dispatch shipment accounting");
            if(cancellationRequested && stage!=Stage.RETURNING && stage!=Stage.CANCELLED && stage!=Stage.RECOVERY_REQUIRED
                    || !cancellationRequested && (stage==Stage.RETURNING || stage==Stage.CANCELLED))throw new IllegalArgumentException("Invalid durable shipment cancellation provenance");
            boolean dispatched=stage==Stage.IN_TRANSIT || stage==Stage.AWAITING_DELIVERY || stage==Stage.DELIVERED;
            if(dispatched && (due<0 || collected!=intent.quantity || planned!=collected))throw new IllegalArgumentException("Transit requires complete acknowledged collection and due time");
            if(due>=0 && (collected!=intent.quantity || due<Math.addExact(intent.issued,intent.travelTicks)))throw new IllegalArgumentException("Invalid retained dispatch time or incomplete accepted cargo");
            if(stage==Stage.DELIVERED && (delivered!=intent.quantity || returned!=0) || RoyalShipmentStore.terminal(stage) && (collected!=delivered+returned || operations.stream().anyMatch(Operation::pending)))
                throw new IllegalArgumentException("Terminal shipment must account for every collected item and resolve every physical operation");
            if((stage==Stage.COLLECTING || stage==Stage.PREPARING || stage==Stage.IN_TRANSIT) && operations.stream().anyMatch(o->o.role!=Role.COLLECT))throw new IllegalArgumentException("Premature shipment credit allocation");
        }
        public int collected(){return sum(operations,Role.COLLECT,true);}
        public int delivered(){return sum(operations,Role.DELIVER,true);}
        public int returned(){return sum(operations,Role.RETURN,true);}
        public int escrow(){return collected()-delivered()-returned();}
        public int allocatable(){return collected()-sum(operations,Role.DELIVER,false)-sum(operations,Role.RETURN,false);}
        public boolean terminal(){return RoyalShipmentStore.terminal(stage);}
        public boolean dispatched(){return due>=0;}
    }

    private final Path directory,file;
    public RoyalShipmentStore(Path directory) {
        this.directory=directory.toAbsolutePath().normalize();file=this.directory.resolve("shipments.dat");
        try {
            var created=new ArrayList<Path>();for(Path p=this.directory;!Files.exists(p);p=p.getParent())created.add(p);
            Files.createDirectories(this.directory);for(Path p:created)sync(p);if(!created.isEmpty())sync(created.getLast().getParent());
        }catch(IOException e){throw failure(e);}
    }
    public synchronized Entry reserve(Intent intent){return locked(entries->{
        var old=entries.get(intent.id);if(old!=null){if(!old.intent.equals(intent))throw new IllegalArgumentException("Shipment UUID belongs to another immutable intent");return old;}
        if(entries.size()>=MAX_SHIPMENTS)throw new IllegalStateException("Shipment history is full; preserve tombstones for audited archival");
        for(var entry:entries.values())if(!entry.terminal() && sameSource(entry.intent,intent))throw new IllegalStateException("Sending village already has an active royal shipment: "+entry.intent.id);
        return put(entries,new Entry(intent,Stage.PREPARING,Blocked.NONE,"",-1,false,List.of()));
    });}
    public synchronized Entry load(UUID id){return locked(entries->entries.get(id));}
    public synchronized List<Entry> entries(){return locked(entries->List.copyOf(entries.values()));}
    public synchronized Entry collecting(UUID id){return update(id,old->{
        if(old.stage==Stage.COLLECTING)return old;require(old.stage==Stage.PREPARING,"Only a prepared, actually paid shipment may collect cargo");
        return changed(old,Stage.COLLECTING,Blocked.NONE,"",old.due,old.operations);
    });}
    public synchronized Entry prepare(UUID id,Operation operation){return locked(entries->{
        var old=requireEntry(entries,id);
        for(var existing:old.operations)if(existing.id.equals(operation.id)){
            if(!sameOperation(existing,operation))throw new IllegalArgumentException("Operation UUID is already bound to another physical mutation");return old;
        }
        require(operation.pending(),"New operation cannot start as acknowledged");
        require(old.stage==switch(operation.role){case COLLECT->Stage.COLLECTING;case DELIVER->Stage.AWAITING_DELIVERY;case RETURN->Stage.RETURNING;},"Operation role does not match shipment lifecycle");
        for(var entry:entries.values())for(var other:entry.operations) {
            require(!other.id.equals(operation.id),"Physical operation UUID is already used by another shipment");
            if(other.pending() && entry.intent.dimension.equals(old.intent.dimension) && (other.position.equals(operation.position) || other.container.equals(operation.container)))
                throw new IllegalStateException("Container has unresolved royal cargo operation "+other.id);
        }
        var operations=new ArrayList<>(old.operations);operations.add(operation);
        return put(entries,changed(old,old.stage,Blocked.NONE,"",old.due,operations));
    });}
    public synchronized Entry acknowledge(UUID id,UUID operation){return update(id,old->{
        require(old.stage!=Stage.RECOVERY_REQUIRED,"Quarantined shipment requires explicit reviewed recovery");
        var operations=new ArrayList<>(old.operations);for(int i=0;i<operations.size();i++){
            var op=operations.get(i);if(!op.id.equals(operation))continue;if(op.applied())return old;require(op.pending(),"Cancelled physical operation cannot become applied");
            operations.set(i,new Operation(op.id,op.role,op.container,op.position,op.quantity,op.image,Outcome.APPLIED));return changed(old,old.stage,Blocked.NONE,"",old.due,operations);
        }throw new IllegalArgumentException("Unknown physical shipment operation");
    });}
    /** Requires native proof that a prepared mutation never applied; caller must verify exact original BEFORE. */
    public synchronized Entry cancelOperation(UUID id,UUID operation){return update(id,old->{
        require(old.stage==Stage.RETURNING,"Only cancellation may release an unapplied physical allocation");
        var operations=new ArrayList<>(old.operations);for(int i=0;i<operations.size();i++){
            var op=operations.get(i);if(!op.id.equals(operation))continue;if(op.outcome==Outcome.CANCELLED)return old;
            require(op.pending(),"Applied cargo cannot be cancelled out of the accounting history");
            operations.set(i,new Operation(op.id,op.role,op.container,op.position,op.quantity,op.image,Outcome.CANCELLED));return changed(old,old.stage,Blocked.NONE,"",old.due,operations);
        }throw new IllegalArgumentException("Unknown physical shipment operation");
    });}
    public synchronized Entry dispatch(UUID id,long gameTime){return update(id,old->{
        if(old.stage==Stage.IN_TRANSIT)return old;
        require(old.stage==Stage.COLLECTING && old.collected()==old.intent.quantity && old.operations.stream().noneMatch(Operation::pending),"Dispatch requires full acknowledged collection");
        require(gameTime>=old.intent.issued,"Dispatch clock precedes accepted quote");
        return changed(old,Stage.IN_TRANSIT,Blocked.NONE,"",Math.addExact(gameTime,old.intent.travelTicks),old.operations);
    });}
    public synchronized Entry awaitDelivery(UUID id,long gameTime){return update(id,old->{
        if(old.stage==Stage.AWAITING_DELIVERY)return old;require(old.stage==Stage.IN_TRANSIT && gameTime>=old.due,"Shipment has not reached its durable arrival time");
        return changed(old,Stage.AWAITING_DELIVERY,Blocked.NONE,"",old.due,old.operations);
    });}
    /** Cancellation is persisted before resolving pending mutations/refunds. Pending credits stay reserved. */
    public synchronized Entry returning(UUID id){return update(id,old->{
        if(old.stage==Stage.RETURNING || old.terminal())return old;require(old.stage!=Stage.RECOVERY_REQUIRED,"Quarantined cargo cannot be reset by cancellation");
        return changed(old,Stage.RETURNING,Blocked.NONE,"",old.due,old.operations);
    });}
    /** Persist old mandate retirement before political publication, including same-tick abdicate/reclaim. */
    public synchronized int retire(java.util.function.Predicate<Intent> invalid){return locked(entries->{
        int count=0;for(var item:entries.entrySet()){
            var old=item.getValue();if(old.terminal() || old.cancellationRequested || !invalid.test(old.intent))continue;
            var next=new Entry(old.intent,old.stage==Stage.RECOVERY_REQUIRED ? old.stage : Stage.RETURNING,Blocked.AUTHORITY,
                    "Original shipment mandate retired",old.due,true,old.operations);item.setValue(next);count++;
        }if(count>0){validate(entries);write(entries);}return count;
    });}
    public synchronized Entry finish(UUID id){return update(id,old->{
        if(old.terminal())return old;require(old.stage==Stage.RETURNING || old.stage==Stage.AWAITING_DELIVERY,"Only resolved returns or delivered shipments may finish");
        return changed(old,old.stage==Stage.RETURNING ? Stage.CANCELLED : Stage.DELIVERED,Blocked.NONE,"",old.due,old.operations);
    });}
    public synchronized Entry block(UUID id,Blocked blocked,String diagnosis){return update(id,old->{
        require(!old.terminal() && old.stage!=Stage.RECOVERY_REQUIRED,"Cannot rewrite terminal or quarantined shipment");return changed(old,old.stage,blocked,diagnosis,old.due,old.operations);
    });}
    public synchronized Entry quarantine(UUID id,String diagnosis){return update(id,old->{
        if(old.stage==Stage.RECOVERY_REQUIRED)return old;require(!old.terminal(),"Terminal shipment cannot reopen");return changed(old,Stage.RECOVERY_REQUIRED,Blocked.PHYSICAL_PROOF,diagnosis,old.due,old.operations);
    });}
    /** For financial/withdrawal coordination: only pending physical operations block their exact containers. */
    public synchronized boolean hasPendingContainer(String dimension,BlockPos position){return locked(entries->entries.values().stream()
            .filter(e->e.intent.dimension.equals(dimension)).flatMap(e->e.operations.stream()).anyMatch(o->o.pending() && o.position.equals(position)));}

    private Entry update(UUID id,Function<Entry,Entry> operation){return locked(entries->{var old=requireEntry(entries,id);var next=operation.apply(old);return old.equals(next) ? old : put(entries,next);});}
    private Entry put(Map<UUID,Entry> entries,Entry entry){entries.put(entry.intent.id,entry);validate(entries);write(entries);return entry;}
    private static Entry changed(Entry old,Stage stage,Blocked blocked,String diagnosis,long due,List<Operation> operations){return new Entry(old.intent,stage,blocked,diagnosis,due,old.cancellationRequested || stage==Stage.RETURNING || stage==Stage.CANCELLED,operations);}
    private static Entry requireEntry(Map<UUID,Entry> entries,UUID id){var entry=entries.get(id);if(entry==null)throw new IllegalArgumentException("Unknown royal shipment");return entry;}
    private static boolean sameOperation(Operation a,Operation b){return a.id.equals(b.id) && a.role==b.role && a.container.equals(b.container) && a.position.equals(b.position) && a.quantity==b.quantity && a.image.equals(b.image);}
    private static int sum(List<Operation> operations,Role role,boolean appliedOnly){int total=0;for(var op:operations)if(op.role==role && op.outcome!=Outcome.CANCELLED && (!appliedOnly || op.applied()))total=Math.addExact(total,op.quantity);return total;}
    private static boolean terminal(Stage stage){return stage==Stage.DELIVERED || stage==Stage.CANCELLED;}
    private static boolean sameSource(Intent a,Intent b){return a.dimension.equals(b.dimension) && a.source.village.equals(b.source.village);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}

    private static void validate(Map<UUID,Entry> entries){
        var ids=new HashSet<UUID>();var sources=new HashSet<String>();var positions=new HashSet<String>();var containers=new HashSet<String>();
        for(var entry:entries.values()){
            if(!entry.terminal() && !sources.add(entry.intent.dimension+"/"+entry.intent.source.village))throw new IllegalStateException("Duplicate active shipment source");
            for(var op:entry.operations){
                if(!ids.add(op.id))throw new IllegalStateException("Duplicate physical operation UUID in shipment history");
                if(op.pending() && (!positions.add(entry.intent.dimension+"/"+op.position.asLong()) || !containers.add(entry.intent.dimension+"/"+op.container)))throw new IllegalStateException("Overlapping unresolved physical shipment operations");
            }
        }
    }
    private Map<UUID,Entry> read()throws IOException{
        var result=new LinkedHashMap<UUID,Entry>();if(!Files.exists(file))return result;
        var tag=NbtIo.readCompressed(file,NbtAccounter.create(MAX_BYTES));keys(tag,Set.of("format","shipments"));
        require(tag.contains("format",Tag.TAG_INT) && tag.getInt("format")==FORMAT,"Unsupported shipment format; original file preserved");
        var list=list(tag,"shipments");require(list.size()<=MAX_SHIPMENTS,"Shipment history exceeds supported limit");
        for(Tag raw:list){var item=(CompoundTag)raw;keys(item,Set.of("intent","stage","blocked","diagnosis","due","cancellationRequested","operations"));
            require(item.contains("intent",Tag.TAG_COMPOUND) && item.contains("due",Tag.TAG_LONG) && item.contains("cancellationRequested",Tag.TAG_BYTE) && (item.getByte("cancellationRequested")==0 || item.getByte("cancellationRequested")==1),"Incomplete shipment lifecycle");
            var operations=new ArrayList<Operation>();for(Tag opRaw:list(item,"operations")){
                var op=(CompoundTag)opRaw;keys(op,Set.of("id","role","container","position","quantity","image","outcome"));
                require(op.hasUUID("id") && op.hasUUID("container") && op.contains("position",Tag.TAG_LONG) && op.contains("quantity",Tag.TAG_INT) && op.contains("image",Tag.TAG_COMPOUND)
                       ,"Incomplete physical shipment operation");
                operations.add(new Operation(op.getUUID("id"),Role.valueOf(string(op,"role")),op.getUUID("container"),BlockPos.of(op.getLong("position")),op.getInt("quantity"),op.getCompound("image"),Outcome.valueOf(string(op,"outcome"))));
            }
            var entry=new Entry(loadIntent(item.getCompound("intent")),Stage.valueOf(string(item,"stage")),Blocked.valueOf(string(item,"blocked")),string(item,"diagnosis"),item.getLong("due"),item.getBoolean("cancellationRequested"),operations);
            require(result.putIfAbsent(entry.intent.id,entry)==null,"Duplicate shipment identity");
        }validate(result);return result;
    }
    private void write(Map<UUID,Entry> entries){
        var tag=new CompoundTag();tag.putInt("format",FORMAT);var shipments=new ListTag();
        for(var entry:entries.values()){
            var item=new CompoundTag();item.put("intent",saveIntent(entry.intent));item.putString("stage",entry.stage.name());item.putString("blocked",entry.blocked.name());item.putString("diagnosis",entry.diagnosis);item.putLong("due",entry.due);item.putBoolean("cancellationRequested",entry.cancellationRequested);
            var operations=new ListTag();for(var op:entry.operations){var raw=new CompoundTag();raw.putUUID("id",op.id);raw.putString("role",op.role.name());raw.putUUID("container",op.container);raw.putLong("position",op.position.asLong());raw.putInt("quantity",op.quantity);raw.put("image",op.image.copy());raw.putString("outcome",op.outcome.name());operations.add(raw);}item.put("operations",operations);shipments.add(item);
        }tag.put("shipments",shipments);
        try{var temporary=Files.createTempFile(directory,".shipments-",".tmp");try{
            NbtIo.writeCompressed(tag,temporary);try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}NbtIo.readCompressed(temporary,NbtAccounter.create(MAX_BYTES));
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync(directory);
        }finally{Files.deleteIfExists(temporary);}}catch(IOException e){throw failure(e);}
    }
    public static CompoundTag saveIntent(Intent intent){
        var tag=new CompoundTag();tag.putUUID("id",intent.id);tag.putString("dimension",intent.dimension);tag.putUUID("kingdom",intent.kingdom);tag.putUUID("capital",intent.capital);if(intent.actor!=null)tag.putUUID("actor",intent.actor);tag.putLong("rulerSince",intent.rulerSince);
        tag.put("source",saveEndpoint(intent.source));tag.put("destination",saveEndpoint(intent.destination));tag.putString("product",intent.product.toString());tag.putInt("quantity",intent.quantity);tag.putLong("fee",intent.fee);tag.putLong("issued",intent.issued);tag.putLong("travelTicks",intent.travelTicks);return tag;
    }
    private static Intent loadIntent(CompoundTag tag){
        keys(tag,Set.of("id","dimension","kingdom","capital","actor","rulerSince","source","destination","product","quantity","fee","issued","travelTicks"));
        for(var key:List.of("id","kingdom","capital"))require(tag.hasUUID(key),"Incomplete shipment identity: "+key);
        for(var key:List.of("rulerSince","fee","issued","travelTicks"))require(tag.contains(key,Tag.TAG_LONG),"Incomplete shipment amount/time: "+key);
        require((!tag.contains("actor") || tag.hasUUID("actor")) && tag.contains("quantity",Tag.TAG_INT) && tag.contains("source",Tag.TAG_COMPOUND) && tag.contains("destination",Tag.TAG_COMPOUND),"Incomplete shipment mandate/endpoints");
        return new Intent(tag.getUUID("id"),string(tag,"dimension"),tag.getUUID("kingdom"),tag.getUUID("capital"),tag.hasUUID("actor") ? tag.getUUID("actor") : null,tag.getLong("rulerSince"),loadEndpoint(tag.getCompound("source")),loadEndpoint(tag.getCompound("destination")),ResourceLocation.parse(string(tag,"product")),tag.getInt("quantity"),tag.getLong("fee"),tag.getLong("issued"),tag.getLong("travelTicks"));
    }
    private static CompoundTag saveEndpoint(Endpoint endpoint){var tag=new CompoundTag();tag.putUUID("village",endpoint.village);tag.putString("type",endpoint.type.toString());tag.putUUID("townhall",endpoint.townhall);tag.putLong("center",endpoint.center.asLong());if(endpoint.localOwner!=null)tag.putUUID("localOwner",endpoint.localOwner);return tag;}
    private static Endpoint loadEndpoint(CompoundTag tag){keys(tag,Set.of("village","type","townhall","center","localOwner"));require(tag.hasUUID("village") && tag.hasUUID("townhall") && tag.contains("center",Tag.TAG_LONG) && (!tag.contains("localOwner") || tag.hasUUID("localOwner")),"Incomplete shipment endpoint");return new Endpoint(tag.getUUID("village"),ResourceLocation.parse(string(tag,"type")),tag.getUUID("townhall"),BlockPos.of(tag.getLong("center")),tag.hasUUID("localOwner") ? tag.getUUID("localOwner") : null);}
    private static String string(CompoundTag tag,String key){require(tag.contains(key,Tag.TAG_STRING),"Missing shipment string: "+key);return tag.getString(key);}
    private static ListTag list(CompoundTag tag,String key){require(tag.contains(key,Tag.TAG_LIST),"Missing shipment list: "+key);var list=(ListTag)tag.get(key);require(list.isEmpty() || list.getElementType()==Tag.TAG_COMPOUND,"Invalid shipment list: "+key);return list;}
    private static void keys(CompoundTag tag,Set<String> keys){require(keys.containsAll(tag.getAllKeys()),"Unknown shipment data; original file preserved");}
    private static void sync(Path path)throws IOException{try(var channel=FileChannel.open(path,StandardOpenOption.READ)){channel.force(true);}}
    private <T>T locked(Function<Map<UUID,Entry>,T> operation){
        try(var channel=FileChannel.open(directory.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            require(lock!=null,"Royal shipment journal is busy; do not mutate physical inventory");return operation.apply(read());
        }catch(OverlappingFileLockException e){throw new IllegalStateException("Royal shipment journal is busy; do not mutate physical inventory",e);}catch(IOException e){throw failure(e);}
    }
    private static IllegalStateException failure(IOException cause){return new IllegalStateException("Cannot safely access royal shipment journal; preserve coins and cargo",cause);}
}
