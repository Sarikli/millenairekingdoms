package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.autonomy.StrategicBattleStore.*;
import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import com.mojang.logging.LogUtils;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.VillageSavedData;

/** Owns only never-dispatched unattended orders. Native combat and all physical entities/inventories remain native. */
public final class StrategicBattleService {
    private static final Map<ServerLevel,Session> SESSIONS=new WeakHashMap<>();
    private static final class Session{UUID cursor,schedulingCursor;long retryAfter,budgetTick=Long.MIN_VALUE,lastWarning=Long.MIN_VALUE;long lastNanos;final Set<UUID> known=new HashSet<>(),selected=new HashSet<>();}
    private StrategicBattleService(){}
    public static boolean owns(ServerLevel level,UUID id){return new StrategicBattleStore(level).owns(id);}
    public static void clear(net.minecraft.server.MinecraftServer server){SESSIONS.keySet().removeIf(l->l.getServer()==server);}
    public static long lastNanos(ServerLevel level){var s=SESSIONS.get(level);return s==null?0:s.lastNanos;}
    public static void onTick(LevelTickEvent.Post event){if(event.getLevel() instanceof ServerLevel level && level.getGameTime()%50==0)recover(level,2);}
    public static int recover(ServerLevel level,int limit){
        thread(level);var session=SESSIONS.computeIfAbsent(level,l->new Session());if(level.getGameTime()<session.retryAfter)return 0;var store=new StrategicBattleStore(level);long start=System.nanoTime();int count=0;
        try{var pending=checkedPending(level,session,store);if(pending.isEmpty())return 0;int offset=0;if(session.cursor!=null){while(offset<pending.size() && pending.get(offset).compareTo(session.cursor)<=0)offset++;offset%=pending.size();}
            for(int n=0;n<Math.min(limit,pending.size());n++){var id=pending.get((offset+n)%pending.size());session.cursor=id;var operation=store.read(id);if(operation.status()==Status.ROLL_COMMITTED)resume(level,store,operation);else store.archive(id);count++;}
        }catch(RuntimeException failure){warn(level,session,failure);}finally{session.lastNanos=System.nanoTime()-start;}return count;
    }
    /** Null means use the established native physical path. A durable strategic roll always keeps its mode. */
    public static RaidOrderService.Attempt attempt(ServerLevel level,KingdomSavedData data,KingdomSavedData.RaidOrder order){
        thread(level);var store=new StrategicBattleStore(level);var session=SESSIONS.computeIfAbsent(level,l->new Session());
        try{var existing=store.read(order.id());if(existing!=null){resume(level,store,existing);return new RaidOrderService.Attempt(RaidOrderService.Status.RESOLVED,"Existing committed strategic operation",null);}}
        catch(RuntimeException failure){warn(level,session,failure);return RaidOrderService.Attempt.waiting("Existing strategic operation awaits durable recovery: "+failure.getMessage());}
        if(!order.byNpc() || order.source()==null || !AutonomyConfig.current().autonomy() || !StrategicCombatConfig.ENABLED.get())return null;
        if(!order.equals(data.order(order.id())))return RaidOrderService.Attempt.dropped("the order was withdrawn or replaced");
        if(level.getGameTime()>=order.expiresGameTime())return RaidOrderService.Attempt.dropped("the order expired");
        var source=PoliticsService.village(level,order.source());var target=PoliticsService.village(level,order.target());if(source==null || target==null)return null;
        if(level.isLoaded(source.getCenter()) || level.isLoaded(target.getCenter()))return null;
        if(level.getGameTime()<session.retryAfter)return RaidOrderService.Attempt.waiting("Strategic operation data unavailable; bounded retry, original evidence preserved");
        try{
            if(!selected(level,data,store,session,order.id()))return RaidOrderService.Attempt.waiting("Bounded round-robin strategic inspection budget; next pass");
            var problem=problem(level,data,order,false);if(problem!=null)return RaidOrderService.Attempt.waiting(problem);
            var a=MilitarySnapshot.capture(level,source);var d=MilitarySnapshot.capture(level,target);if(a.attack()<=0 || d.defense()<=0)return RaidOrderService.Attempt.waiting("Verified positive native attacker and defender capabilities are unavailable");
            var attackingUnits=new HashSet<UUID>();a.raiders().forEach(u->attackingUnits.add(u.id()));if(d.defenders().stream().anyMatch(u->attackingUnits.contains(u.id())))return RaidOrderService.Attempt.waiting("The same native unit appears on both sides; military metadata requires repair");
            // Existing native target strength eligibility is stricter than a lucky roll and is never bypassed.
            if(d.defense()>=2*a.attack())return RaidOrderService.Attempt.waiting("Native target is too strong for this available military force");
            acknowledgePolitical(level,data);
            var settings=StrategicCombatConfig.settings();var roll=OpposedDice.roll(a.attack(),d.defense(),level.getRandom(),settings);
            long tick=Math.max(1,level.getGameTime()),day=Math.max(0,level.getDayTime())/24000;var victor=roll.attackerWon()?a.owner():d.owner();var loser=roll.attackerWon()?d.owner():a.owner();
            var facts=new HashMap<>(Map.of("mode","STRATEGIC","attackingStrength",Long.toString(a.attack()),"defendingStrength",Long.toString(d.defense()),"attackerDie",Integer.toString(roll.attackDie()),"defenderDie",Integer.toString(roll.defenseDie()),"modifier",Integer.toString(roll.relativeModifier())));
            List<HistoryEvent.Realm> realms;if(d.owner()==null){facts.put("independentTarget","true");realms=List.of(HistoryHooks.realm(level,a.owner()));}else realms=List.of(HistoryHooks.realm(level,victor),HistoryHooks.realm(level,loser));
            var narrative=HistoryEvent.create(HistoryEvent.Kind.STRATEGIC_BATTLE,day,tick,level.dimension().location().toString(),order.id().toString(),realms,List.of(HistoryHooks.place(level,order.source()),HistoryHooks.place(level,order.target())),null,"","UNATTENDED_OPPOSED_DICE",roll.attackerWon()?"ATTACKER_VICTORY":"DEFENDED",facts);
            var operation=new Operation(order,data.revision(order.target()),a,d,settings,roll,day,tick,narrative,Status.ROLL_COMMITTED,"");store.commit(operation);session.known.add(order.id());resume(level,store,operation);
            return new RaidOrderService.Attempt(RaidOrderService.Status.RESOLVED,"Unattended opposed-dice military operation",null);
        }catch(RuntimeException failure){warn(level,session,failure);return RaidOrderService.Attempt.waiting("Strategic resolution deferred: "+failure.getMessage());}
    }
    /** Replay only the exact committed roll; never draw random numbers here or start native NPC activity. */
    public static void resume(ServerLevel level,StrategicBattleStore store,Operation op){
        thread(level);if(op.status()!=Status.ROLL_COMMITTED){store.archive(op.id());return;}var data=KingdomSavedData.get(level);var receipt=data.receipt(op.id());var raid=data.raid(op.order().target());
        if(receipt==null){
            if(raid==null){var current=data.order(op.id());String reason=current==null || !current.equals(op.order())?"Original military order no longer exists or changed":problem(level,data,current,true);
                if(reason!=null || !op.campaign().equals(data.campaign(op.order().source(),op.order().target()))){abort(level,store,op,reason==null?"Political campaign changed after the roll":reason);return;}
                raid=data.beginIdentified(op.order().source(),op.order().target(),op.tick(),null,op.id(),op.id());
            }
            if(!raid.id().equals(op.id()) || !raid.campaign().equals(op.campaign())){abort(level,store,op,"Another military operation owns the current raid");return;}
            var source=com.millenairekingdoms.politics.PoliticsService.village(level,op.order().source());var target=com.millenairekingdoms.politics.PoliticsService.village(level,op.order().target());
            if(source==null || target==null || !data.npcGoverned(op.attack().owner()) || op.defense().owner()!=null && !data.npcGoverned(op.defense().owner()) || source.getOwnerUUID()!=null || target.getOwnerUUID()!=null || Military.busy(source) || Military.busy(target)){
                abort(level,store,op,"Current native authority/physical raid invalidated the uncommitted political result");return;
            }
            if(op.roll().attackerWon())data.recordVictory(raid);
            data.finish(data.raid(op.order().target()),KingdomSavedData.Outcome.LOST);receipt=data.receipt(op.id());
        }else if(raid!=null && raid.id().equals(op.id()))data.finish(raid,KingdomSavedData.Outcome.LOST);
        if(receipt==null || !receipt.attacker().equals(op.order().source()) || !receipt.target().equals(op.order().target()) || !Objects.equals(receipt.campaign(),op.campaign()))throw new IllegalStateException("Strategic result lacks the exact political receipt");
        acknowledgePolitical(level,data);
        if(receipt.outcome()!=KingdomSavedData.Outcome.WON && receipt.outcome()!=KingdomSavedData.Outcome.LOST){store.finish(op,Status.ABORTED,"Political context invalidated the military result; no strategic success published");return;}
        HistoryHooks.store(level).append(op.narrative());store.finish(op,Status.RESOLVED,receipt.outcome()+"; exact acknowledged political receipt; no physical NPC losses or loot implied");
        LogUtils.getLogger().info("[Kingdoms] STRATEGIC_BATTLE operation={} strength={}/{} dice={}/{} modifier={} attackerWon={} political={} transferred={}",op.id(),op.attack().attack(),op.defense().defense(),op.roll().attackDie(),op.roll().defenseDie(),op.roll().relativeModifier(),op.roll().attackerWon(),receipt.outcome(),receipt.transferred());
    }
    private static void abort(ServerLevel level,StrategicBattleStore store,Operation op,String reason){var data=KingdomSavedData.get(level);data.removeOrder(op.id());var raid=data.raid(op.order().target());if(raid!=null && raid.id().equals(op.id()))data.finish(raid,KingdomSavedData.Outcome.ABORTED);acknowledgePolitical(level,data);store.finish(op,Status.ABORTED,reason);}
    private static String problem(ServerLevel level,KingdomSavedData data,KingdomSavedData.RaidOrder order,boolean committed){
        // A durable roll already started inside its original window. A separately saved world clock may lag it.
        if(order==null || !order.byNpc() || !order.equals(data.order(order.id())) || !committed && (level.getGameTime()<order.dueGameTime() || level.getGameTime()>=order.expiresGameTime()))return "Military order is absent, premature, expired or replaced";
        var source=PoliticsService.village(level,order.source());var target=PoliticsService.village(level,order.target());var defender=data.owner(order.target());
        if(source==null || target==null || !order.kingdom().equals(data.owner(order.source())) || !data.npcGoverned(order.kingdom()) || defender!=null && !data.npcGoverned(defender))return "Current NPC military authority or native settlement is unavailable";
        try{var campaign=data.startable(order.source(),order.target());if(!Objects.equals(campaign.warId(),order.warId()))return "Legitimate active war changed";}catch(IllegalArgumentException invalid){return invalid.getMessage();}
        if(order.warId()==null){
            if(defender!=null)return "Independent target joined a kingdom before execution";
            var capital=PoliticsService.village(level,data.kingdom(order.kingdom()).capital());Integer relation=capital==null?null:capital.getRelations().get(target.getId());long radius=AutonomyService.joinRadius();
            if(relation==null || relation>AutonomyConfig.current().warThreshold() || capital.getCenter().distSqr(target.getCenter())>radius*radius)return "Independent target is no longer a nearby hostile village";
        }
        if(RaidOrderService.cooldownLeft(data,order.target(),AutonomyConfig.current())>0)return "Existing military cooldown has not expired";
        if(data.activeConquests(order.kingdom())>0 || defender!=null && (data.activeConquests(defender)>0 || data.orderOf(defender)!=null))return "A kingdom has an incompatible military commitment";
        if(source.getTownhall()==null || target.getTownhall()==null || source.getOwnerUUID()!=null || target.getOwnerUUID()!=null)return "Native capital/local government eligibility changed";
        var a=ModCultures.getVillageType(source.getVillageTypeId());var b=ModCultures.getVillageType(target.getVillageTypeId());if(a==null || b==null || a.playerControlled() || b.playerControlled() || a.loneBuilding() || b.loneBuilding())return "Unsupported native village type";
        if(!level.getWorldBorder().isWithinBounds(source.getCenter()) || !level.getWorldBorder().isWithinBounds(target.getCenter()) || target.getId().equals(source.getParentVillageId()) || source.getId().equals(target.getParentVillageId()))return "Native location/parent-child military restriction";
        if(Military.busy(source) || Military.busy(target))return "Native physical raid/preparation already owns these forces";
        for(var village:VillageSavedData.get(level).getVillageManager().getAllVillages())if(source.getId().equals(village.getRaidTarget()) || target.getId().equals(village.getRaidTarget()))return "Another native physical operation targets these forces";
        return Military.problem(source,target);
    }
    /** Ordinary SavedData worker plus actual-file readback: queued or in-memory state cannot acknowledge a victory. */
    public static void acknowledgePolitical(ServerLevel level,KingdomSavedData data){
        var file=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data/"+KingdomSavedData.NAME+".dat");
        if(Files.exists(file))KingdomSavedData.load(readPolitical(file),level.registryAccess());var expected=data.save(new CompoundTag(),level.registryAccess());data.setDirty();level.getDataStorage().save();IOUtilities.waitUntilIOWorkerComplete();var actual=readPolitical(file);KingdomSavedData.load(actual,level.registryAccess());if(!expected.equals(actual))throw new IllegalStateException("Political military result has not reached the saved authoritative snapshot");
    }
    private static CompoundTag readPolitical(Path file){try{var root=NbtIo.readCompressed(file,NbtAccounter.create(64L*1024*1024));if(!root.contains("data",Tag.TAG_COMPOUND) || root.contains("DataVersion") && (!root.contains("DataVersion",Tag.TAG_INT) || root.getInt("DataVersion")>net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion()))throw new IllegalArgumentException("Invalid/future political container; original preserved");return root.getCompound("data");}catch(java.io.IOException e){throw new IllegalStateException("Cannot acknowledge political military persistence",e);}}
    private static boolean selected(ServerLevel level,KingdomSavedData data,StrategicBattleStore store,Session session,UUID id){
        long now=level.getGameTime();
        if(session.budgetTick!=now){
            checkedPending(level,session,store);session.budgetTick=now;session.selected.clear();
            var candidates=data.orders().stream().filter(o->o.byNpc() && o.source()!=null && now>=o.dueGameTime() && now<o.expiresGameTime() && !session.known.contains(o.id()))
                    .filter(o->{var a=PoliticsService.village(level,o.source());var d=PoliticsService.village(level,o.target());return a!=null && d!=null && !level.isLoaded(a.getCenter()) && !level.isLoaded(d.getCenter());})
                    .sorted(Comparator.comparing(KingdomSavedData.RaidOrder::id)).toList();
            if(!candidates.isEmpty()){int start=0;if(session.schedulingCursor!=null){while(start<candidates.size() && candidates.get(start).id().compareTo(session.schedulingCursor)<=0)start++;start%=candidates.size();}
                for(int n=0;n<Math.min(2,candidates.size());n++){var next=candidates.get((start+n)%candidates.size()).id();session.selected.add(next);session.schedulingCursor=next;}
            }
        }
        return session.selected.contains(id);
    }
    private static List<UUID> checkedPending(ServerLevel level,Session session,StrategicBattleStore store){var ids=store.pending();session.known.retainAll(ids);for(var id:ids)if(!session.known.contains(id)){store.read(id);session.known.add(id);}return ids;}
    private static void warn(ServerLevel level,Session s,RuntimeException failure){long now=level.getGameTime();s.retryAfter=now+1200;if(s.lastWarning==Long.MIN_VALUE || now-s.lastWarning>=1200){s.lastWarning=now;LogUtils.getLogger().warn("[Kingdoms] Strategic military operation deferred; originals retained: {}",failure.getMessage());}}
    private static void thread(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("Strategic military decisions require server thread");}
}
