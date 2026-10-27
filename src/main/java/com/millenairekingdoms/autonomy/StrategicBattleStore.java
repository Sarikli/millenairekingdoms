package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

/** Immutable roll binding plus a tiny monotone lifecycle. The existing political registry owns conquest receipts. */
public final class StrategicBattleStore {
    public enum Status { ROLL_COMMITTED, RESOLVED, ABORTED }
    public record Operation(KingdomSavedData.RaidOrder order,long targetRevision,MilitarySnapshot attack,MilitarySnapshot defense,
                            OpposedDice.Settings settings,OpposedDice.Roll roll,long day,long tick,HistoryEvent narrative,Status status,String reason){
        public Operation{
            Objects.requireNonNull(order);Objects.requireNonNull(attack);Objects.requireNonNull(defense);Objects.requireNonNull(settings);Objects.requireNonNull(roll);Objects.requireNonNull(narrative);Objects.requireNonNull(status);
            if(!order.byNpc() || order.source()==null || targetRevision<0 || day<0 || tick<=0 || tick<order.dueGameTime() || tick>=order.expiresGameTime() || order.issuedGameTime()<0 || order.dueGameTime()<order.issuedGameTime() || order.expiresGameTime()<=order.dueGameTime()
                    || !attack.village().equals(order.source()) || !defense.village().equals(order.target()) || !order.kingdom().equals(attack.owner()) || Objects.equals(defense.owner(),attack.owner()) || (order.warId()==null)!=(defense.owner()==null)
                    || !OpposedDice.compare(attack.attack(),defense.defense(),roll.attackDie(),roll.defenseDie(),settings).equals(roll)
                    || narrative.kind()!=HistoryEvent.Kind.STRATEGIC_BATTLE || !narrative.source().equals(order.id().toString()) || narrative.day()!=day || narrative.gameTime()!=tick
                    || !narrative.id().equals(HistoryEvent.identity(narrative.dimension(),HistoryEvent.Kind.STRATEGIC_BATTLE,order.id().toString())) || reason==null || reason.length()>512)throw new IllegalArgumentException("Invalid committed strategic operation");
        }
        public UUID id(){return order.id();}
        public KingdomSavedData.Campaign campaign(){return new KingdomSavedData.Campaign(order.kingdom(),order.target(),targetRevision,order.warId());}
        public Operation status(Status next,String why){if(status!=Status.ROLL_COMMITTED && next!=status)throw new IllegalStateException("Strategic outcome is already terminal");return new Operation(order,targetRevision,attack,defense,settings,roll,day,tick,narrative,next,why.substring(0,Math.min(512,why.length())));}
    }
    private final Path directory;
    private final String dimension;
    public StrategicBattleStore(ServerLevel level){this(HistoryHooks.directory(level).resolve("strategic-military"),level.dimension().location().toString());}
    public StrategicBattleStore(Path directory,String dimension){this.directory=directory;this.dimension=dimension;}
    private Path file(UUID id,boolean done){return directory.resolve(done?"done":"pending").resolve(id+".dat");}
    public boolean owns(UUID id){return Files.exists(file(id,false)) || Files.exists(file(id,true));}
    public Operation read(UUID id){var p=file(id,false);var d=file(id,true);if(Files.exists(p) && Files.exists(d))throw new IllegalStateException("Duplicate strategic operation files; preserve both");var chosen=Files.exists(p)?p:d;if(!Files.exists(chosen))return null;var op=decode(HistoryFiles.read(chosen));if(!op.id().equals(id) || !op.narrative().dimension().equals(dimension))throw new IllegalArgumentException("Strategic file identity/dimension changed");return op;}
    public List<UUID> pending(){
        var folder=directory.resolve("pending");if(!Files.exists(folder))return List.of();
        try(var files=Files.list(folder)){var paths=files.filter(p->p.getFileName().toString().endsWith(".dat")).limit(129).toList();if(paths.size()>128)throw new IllegalStateException("Pending strategic operation budget reached; preserve existing records");var ids=new ArrayList<UUID>();for(var p:paths)ids.add(UUID.fromString(p.getFileName().toString().replaceFirst("\\.dat$","")));ids.sort(UUID::compareTo);return List.copyOf(ids);}catch(IOException e){throw new IllegalStateException("Strategic operation directory unavailable",e);}
    }
    public Operation commit(Operation op){var old=read(op.id());if(old!=null){if(!old.order().equals(op.order()))throw new IllegalArgumentException("Strategic identity reused for another order");return old;}if(pending().size()>=128)throw new IllegalStateException("Too many pending strategic operations");if(op.status()!=Status.ROLL_COMMITTED || !dimension.equals(op.narrative().dimension()))throw new IllegalArgumentException("Invalid new strategic operation");HistoryFiles.write(file(op.id(),false),encode(op));return op;}
    public void finish(Operation expected,Status status,String reason){
        if(status==Status.ROLL_COMMITTED || !Objects.equals(read(expected.id()),expected))throw new IllegalStateException("Strategic lifecycle changed");var next=expected.status(status,reason);HistoryFiles.write(file(next.id(),false),encode(next));archive(next.id());
    }
    public void archive(UUID id){var op=read(id);if(op==null || op.status()==Status.ROLL_COMMITTED)return;var source=file(id,false);var target=file(id,true);if(!Files.exists(source))return;
        try{Files.createDirectories(target.getParent());Files.move(source,target,StandardCopyOption.ATOMIC_MOVE);sync(source.getParent());sync(target.getParent());sync(directory);}catch(IOException e){throw new IllegalStateException("Strategic outcome remains durable; archive move deferred",e);}
    }
    private static void sync(Path directory)throws IOException{try(var ch=FileChannel.open(directory,StandardOpenOption.READ)){ch.force(true);}}
    public static CompoundTag encode(Operation op){
        var t=new CompoundTag();t.putInt("format",op.order().warId()==null?2:1);var o=new CompoundTag();o.putUUID("id",op.id());o.putUUID("kingdom",op.order().kingdom());o.putUUID("source",op.order().source());o.putUUID("target",op.order().target());if(op.order().warId()!=null)o.putUUID("war",op.order().warId());o.putLong("issued",op.order().issuedGameTime());o.putLong("due",op.order().dueGameTime());o.putLong("expires",op.order().expiresGameTime());t.put("order",o);
        t.putLong("targetRevision",op.targetRevision());t.put("attack",op.attack().save());t.put("defense",op.defense().save());t.putDouble("scale",op.settings().scale());t.putInt("cap",op.settings().maximumModifier());t.putInt("home",op.settings().homeAdvantage());t.putInt("attackDie",op.roll().attackDie());t.putInt("defenseDie",op.roll().defenseDie());t.putLong("day",op.day());t.putLong("tick",op.tick());t.put("narrative",op.narrative().save());t.putString("status",op.status().name());t.putString("reason",op.reason());return t;
    }
    public static Operation decode(CompoundTag t){
        HistoryFiles.keys(t,Set.of("format","order","targetRevision","attack","defense","scale","cap","home","attackDie","defenseDie","day","tick","narrative","status","reason"));
        int format=t.getInt("format");if(format!=1 && format!=2)throw new IllegalArgumentException("Unsupported strategic operation format; original preserved");for(var k:List.of("format","cap","home","attackDie","defenseDie"))require(t,k,Tag.TAG_INT);for(var k:List.of("targetRevision","day","tick"))require(t,k,Tag.TAG_LONG);for(var k:List.of("order","attack","defense","narrative"))require(t,k,Tag.TAG_COMPOUND);for(var k:List.of("status","reason"))require(t,k,Tag.TAG_STRING);require(t,"scale",Tag.TAG_DOUBLE);
        var o=t.getCompound("order");var keys=new HashSet<>(Set.of("id","kingdom","source","target","issued","due","expires"));if(format==1)keys.add("war");HistoryFiles.keys(o,keys);for(var k:List.of("id","kingdom","source","target"))if(!o.hasUUID(k))throw new IllegalArgumentException("Invalid strategic order UUID");if(format==1 && !o.hasUUID("war"))throw new IllegalArgumentException("Invalid strategic war UUID");for(var k:List.of("issued","due","expires"))require(o,k,Tag.TAG_LONG);
        var order=new KingdomSavedData.RaidOrder(o.getUUID("id"),o.getUUID("kingdom"),o.getUUID("target"),o.getUUID("source"),format==1?o.getUUID("war"):null,null,o.getLong("issued"),o.getLong("due"),o.getLong("expires"));var attack=MilitarySnapshot.read(t.getCompound("attack"));var defense=MilitarySnapshot.read(t.getCompound("defense"));var settings=new OpposedDice.Settings(t.getDouble("scale"),t.getInt("cap"),t.getInt("home"));return new Operation(order,t.getLong("targetRevision"),attack,defense,settings,OpposedDice.compare(attack.attack(),defense.defense(),t.getInt("attackDie"),t.getInt("defenseDie"),settings),t.getLong("day"),t.getLong("tick"),HistoryEvent.load(t.getCompound("narrative")),Status.valueOf(t.getString("status")),t.getString("reason"));
    }
    private static void require(CompoundTag t,String key,int type){if(!t.contains(key,type))throw new IllegalArgumentException("Invalid strategic operation field: "+key);}
}
