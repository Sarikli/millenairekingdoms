package com.millenairekingdoms.economy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Strict separate NPC attempt/cooldown and first-fee authorization history. It is not money, goods or domain proof.
 * A durable attempt precedes issue; unissued attempts are never blindly replayed after a crash. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class RoyalNpcDecisionStore {
    private static final int FORMAT=1,MAX_DECISIONS=10_000;
    private static final long MAX_BYTES=16L*1024*1024;
    public enum Kind { NONE, CONSTRUCTION, SHIPMENT }
    public enum Outcome { ISSUING, ACCEPTED, DEFERRED, FAILED, SKIPPED }
    public record Budget(UUID capital,ResourceLocation capitalType,long reserve,int sharePermille){
        public Budget{Objects.requireNonNull(capital);if(reserve<0 || sharePermille<1 || sharePermille>1000)throw new IllegalArgumentException("Invalid NPC fee budget");}
    }
    public record Choice(Kind kind,UUID source,UUID target,ResourceLocation selection,int quantity,long fee,long travelTicks,UUID need){
        public Choice{
            Objects.requireNonNull(kind);
            if(kind==Kind.NONE){if(source!=null || target!=null || selection!=null || quantity!=0 || fee!=0 || travelTicks!=0 || need!=null)throw new IllegalArgumentException("Non-action attempt cannot authorize a fee");}
            else{
                Objects.requireNonNull(source);Objects.requireNonNull(selection);if(fee<1)throw new IllegalArgumentException("NPC action needs a positive exact fee");
                if(kind==Kind.CONSTRUCTION && (target!=null || quantity!=0 || travelTicks!=0 || need!=null)
                        || kind==Kind.SHIPMENT && (target==null || source.equals(target) || quantity<1 || quantity>65536 || travelTicks<1 || need==null))throw new IllegalArgumentException("Invalid NPC construction/shipment selection");
            }
        }
        public static Choice none(){return new Choice(Kind.NONE,null,null,null,0,0,0,null);}
    }
    public record Decision(UUID id,String dimension,UUID kingdom,long day,long nextDay,Budget budget,Choice choice,Outcome outcome,String reason){
        public Decision{
            Objects.requireNonNull(id);Objects.requireNonNull(kingdom);Objects.requireNonNull(budget);Objects.requireNonNull(choice);Objects.requireNonNull(outcome);Objects.requireNonNull(reason);
            if(dimension==null || ResourceLocation.tryParse(dimension)==null || day<0 || nextDay<=day || reason.length()>512 || choice.kind()!=Kind.NONE && budget.capitalType()==null)
                throw new IllegalArgumentException("Invalid bound NPC decision");
            dimension=ResourceLocation.parse(dimension).toString();
            if(choice.kind()==Kind.NONE && outcome!=Outcome.SKIPPED && outcome!=Outcome.DEFERRED || choice.kind()!=Kind.NONE && outcome==Outcome.SKIPPED)throw new IllegalArgumentException("Invalid NPC decision outcome");
        }
        Decision result(Outcome result,String explanation){return new Decision(id,dimension,kingdom,day,nextDay,budget,choice,result,explanation);}
    }
    private final Path directory,file;
    public RoyalNpcDecisionStore(Path directory){
        this.directory=directory.toAbsolutePath().normalize();file=this.directory.resolve("decisions.dat");
        try{Files.createDirectories(this.directory);}catch(IOException failure){throw failure("NPC decision directory is unavailable",failure);}
    }
    public static Path directory(ServerLevel level){return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-npc-decisions");}
    public synchronized List<Decision> entries(){return locked(entries->List.copyOf(entries.values()));}
    public synchronized Decision load(UUID id){return locked(entries->entries.get(id));}
    public synchronized long nextDay(String dimension,UUID kingdom){return locked(entries->entries.values().stream().filter(d->d.dimension().equals(dimension) && d.kingdom().equals(kingdom)).mapToLong(Decision::nextDay).max().orElse(0));}
    public synchronized Decision begin(Decision decision){return locked(entries->{
        if(entries.containsKey(decision.id()))throw new IllegalArgumentException("NPC attempt UUID already exists; never replay an unissued proposal");
        if(entries.size()>=MAX_DECISIONS)throw new IllegalStateException("NPC decision history reached its supported bound; preserve it for review");
        if(entries.values().stream().anyMatch(d->d.dimension().equals(decision.dimension()) && d.kingdom().equals(decision.kingdom()) && d.nextDay()>decision.day()))
            throw new IllegalArgumentException("NPC decision cooldown has not expired");
        if(decision.choice().kind()!=Kind.NONE && decision.outcome()!=Outcome.ISSUING)throw new IllegalArgumentException("New NPC action must persist ISSUING before domain issue");
        entries.put(decision.id(),decision);write(entries);return decision;
    });}
    public synchronized Decision finish(UUID id,Outcome outcome,String reason){return locked(entries->{
        var current=entries.get(id);if(current==null)throw new IllegalArgumentException("Unknown NPC decision");var next=current.result(outcome,reason);
        if(current.equals(next))return current;
        if(current.outcome()!=Outcome.ISSUING || outcome==Outcome.ISSUING)throw new IllegalArgumentException("NPC attempt result is already retained");
        entries.put(id,next);write(entries);return next;
    });}
    private <T>T locked(Function<LinkedHashMap<UUID,Decision>,T> action){
        try(var channel=FileChannel.open(directory.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IllegalStateException("NPC decision history is already being written");return action.apply(read());
        }catch(IOException|java.nio.channels.OverlappingFileLockException failure){throw failure("NPC decision history is unavailable",failure);}
    }
    private LinkedHashMap<UUID,Decision> read(){
        try{
            var entries=new LinkedHashMap<UUID,Decision>();if(!Files.exists(file))return entries;
            if(Files.size(file)>MAX_BYTES)throw new IllegalStateException("NPC decision history is too large; original preserved");
            var root=NbtIo.readCompressed(file,NbtAccounter.create(MAX_BYTES));keys(root,Set.of("format","decisions"));
            if(!root.contains("format",Tag.TAG_INT) || root.getInt("format")!=FORMAT || !root.contains("decisions",Tag.TAG_LIST)
                    || !(root.get("decisions") instanceof ListTag list) || !list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND || list.size()>MAX_DECISIONS)
                throw new IllegalStateException("Unsupported NPC decision data; original preserved");
            for(var raw:list){var decision=decode((CompoundTag)raw);if(entries.put(decision.id(),decision)!=null)throw new IllegalStateException("Duplicate NPC decision identity; original preserved");}
            var latest=new LinkedHashMap<String,Long>();
            for(var decision:entries.values().stream().sorted(Comparator.comparingLong(Decision::day)).toList()){
                String key=decision.dimension()+"/"+decision.kingdom();if(latest.getOrDefault(key,0L)>decision.day())throw new IllegalStateException("Overlapping NPC cooldown history; original preserved");latest.put(key,decision.nextDay());
            }
            return entries;
        }catch(IOException|IllegalArgumentException failure){throw failure("Unreadable NPC decision history; original preserved",failure);}
    }
    private void write(Map<UUID,Decision> entries){
        var root=new CompoundTag();root.putInt("format",FORMAT);var list=new ListTag();for(var decision:entries.values())list.add(encode(decision));root.put("decisions",list);
        try{
            var temporary=Files.createTempFile(directory,".decisions-",".tmp");
            try{
                NbtIo.writeCompressed(root,temporary);if(Files.size(temporary)>MAX_BYTES)throw new IOException("NPC decision data exceeds its supported bound");
                try(var data=FileChannel.open(temporary,StandardOpenOption.WRITE)){data.force(true);}Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                try(var dir=FileChannel.open(directory,StandardOpenOption.READ)){dir.force(true);}
            }finally{Files.deleteIfExists(temporary);}
        }catch(IOException failure){throw failure("NPC decision acknowledgement failed; inspect durable history before retrying",failure);}
    }
    private static CompoundTag encode(Decision decision){
        var tag=new CompoundTag();tag.putUUID("id",decision.id());tag.putString("dimension",decision.dimension());tag.putUUID("kingdom",decision.kingdom());tag.putLong("day",decision.day());tag.putLong("nextDay",decision.nextDay());
        var budget=new CompoundTag();budget.putUUID("capital",decision.budget().capital());if(decision.budget().capitalType()!=null)budget.putString("capitalType",decision.budget().capitalType().toString());budget.putLong("reserve",decision.budget().reserve());budget.putInt("sharePermille",decision.budget().sharePermille());tag.put("budget",budget);
        var c=decision.choice();var choice=new CompoundTag();choice.putString("kind",c.kind().name());if(c.source()!=null)choice.putUUID("source",c.source());if(c.target()!=null)choice.putUUID("target",c.target());if(c.selection()!=null)choice.putString("selection",c.selection().toString());
        choice.putInt("quantity",c.quantity());choice.putLong("fee",c.fee());choice.putLong("travelTicks",c.travelTicks());if(c.need()!=null)choice.putUUID("need",c.need());tag.put("choice",choice);tag.putString("outcome",decision.outcome().name());tag.putString("reason",decision.reason());return tag;
    }
    private static Decision decode(CompoundTag tag){
        keys(tag,Set.of("id","dimension","kingdom","day","nextDay","budget","choice","outcome","reason"));required(tag,Set.of("id","dimension","kingdom","day","nextDay","budget","choice","outcome","reason"));
        require(tag,"budget",Tag.TAG_COMPOUND);require(tag,"choice",Tag.TAG_COMPOUND);var b=tag.getCompound("budget");keys(b,Set.of("capital","capitalType","reserve","sharePermille"));required(b,Set.of("capital","reserve","sharePermille"));
        var budget=new Budget(uuid(b,"capital"),optionalLocation(b,"capitalType"),number(b,"reserve"),integer(b,"sharePermille"));var c=tag.getCompound("choice");keys(c,Set.of("kind","source","target","selection","quantity","fee","travelTicks","need"));required(c,Set.of("kind","quantity","fee","travelTicks"));
        var choice=new Choice(Kind.valueOf(string(c,"kind")),optionalUuid(c,"source"),optionalUuid(c,"target"),optionalLocation(c,"selection"),integer(c,"quantity"),number(c,"fee"),number(c,"travelTicks"),optionalUuid(c,"need"));
        return new Decision(uuid(tag,"id"),string(tag,"dimension"),uuid(tag,"kingdom"),number(tag,"day"),number(tag,"nextDay"),budget,choice,Outcome.valueOf(string(tag,"outcome")),string(tag,"reason"));
    }
    private static void require(CompoundTag tag,String key,int type){if(!tag.contains(key,type))throw new IllegalArgumentException("Wrong NPC decision field type: "+key);}
    private static String string(CompoundTag tag,String key){require(tag,key,Tag.TAG_STRING);return tag.getString(key);}
    private static int integer(CompoundTag tag,String key){require(tag,key,Tag.TAG_INT);return tag.getInt(key);}
    private static long number(CompoundTag tag,String key){require(tag,key,Tag.TAG_LONG);return tag.getLong(key);}
    private static UUID uuid(CompoundTag tag,String key){if(!tag.hasUUID(key))throw new IllegalArgumentException("Invalid NPC UUID field: "+key);return tag.getUUID(key);}
    private static UUID optionalUuid(CompoundTag tag,String key){return tag.contains(key) ? uuid(tag,key) : null;}
    private static ResourceLocation optionalLocation(CompoundTag tag,String key){return tag.contains(key) ? ResourceLocation.parse(string(tag,key)) : null;}
    private static void keys(CompoundTag tag,Set<String> allowed){if(!allowed.containsAll(tag.getAllKeys()))throw new IllegalArgumentException("Unknown NPC decision fields; original preserved");}
    private static void required(CompoundTag tag,Set<String> required){if(!tag.getAllKeys().containsAll(required))throw new IllegalArgumentException("Missing NPC decision fields; original preserved");}
    private static IllegalStateException failure(String message,Exception cause){return new IllegalStateException(message,cause);}
}
