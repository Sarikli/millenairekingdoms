package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.autonomy.RoyalStrategyStore.*;
import com.millenairekingdoms.construction.*;
import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.politics.PoliticsService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.millenaire.building.BuildingInstance;

/** Metadata governance continues unloaded. Only the existing paid domain services may execute a revalidated proposal. */
public final class RoyalStrategyService {
    private final ServerLevel level;
    private final RoyalStrategyStore store;
    private record Need(UUID target,ResourceLocation selection,boolean logistics,int weight,String reason){}
    public RoyalStrategyService(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("Strategic decisions require server thread");this.level=level;store=new RoyalStrategyStore(level);}
    public Plan inspect(UUID kingdom){return store.read(kingdom);}
    public RoyalNpcEconomyService.Result step(UUID kingdom,long day,RandomSource random){
        var data=KingdomSavedData.get(level);var k=data.kingdom(kingdom);var old=store.read(kingdom);
        if(!RoyalNpcConfig.budgets().enabled() || k==null || k.fallen() || !k.active() || !data.npcGoverned(kingdom)){
            invalidate(old,"Current NPC government no longer authorizes this conditional intention");return null;
        }
        var capital=PoliticsService.village(level,k.capital());
        if(capital==null || capital.getTownhall()==null || capital.getOwnerUUID()!=null || !KingdomPersonality.forCapital(level,k).specialized()){
            invalidate(old,"Registered autonomous native capital/type unavailable; physical facts unknown");return null;
        }
        // Already-issued domain IDs are never issued again. Their own runtimes own acceptance/completion/recovery.
        if(old!=null && (old.status()==Status.ISSUING || old.status()==Status.LINKED)){
            var decision=decisions().load(old.id());
            if(decision!=null && old.status()==Status.ISSUING){var linked=old.status(Status.LINKED,"Existing economic authorization "+decision.id()+"; domain runtime owns completion/recovery");store.write(old,linked);old=linked;}
            if(old.status()==Status.ISSUING || day<old.nextDay() || !domainTerminal(old))return null;
        }
        if(old!=null && old.status()!=Status.INVALIDATED && (!old.capital().equals(k.capital()) || !old.capitalType().equals(capital.getVillageTypeId()) || old.target()!=null && !kingdom.equals(data.owner(old.target())))){
            invalidate(old,"Capital/type/membership changed before execution; conditional plan cancelled without payment");return null;
        }
        Plan plan=old;
        if(plan==null || day>=plan.nextDay() || plan.status()==Status.INVALIDATED){
            var needs=needs(data,k,day);int total=needs.stream().mapToInt(Need::weight).sum();Need selected=null;
            if(total>0){int roll=random.nextInt(total);for(var need:needs){roll-=need.weight();if(roll<0){selected=need;break;}}}
            var same=selected!=null && old!=null && old.status()==Status.CONDITIONAL && selected.target().equals(old.target()) && Objects.equals(selected.selection(),old.selection()) && selected.logistics()==old.logistics();
            var fingerprint=fingerprint(data,k,selected==null?null:selected.target());String reason=selected==null?"No legal metadata development gap; native queues/eligibility retained. Physical treasury, materials and builders are UNKNOWN":selected.reason();
            plan=new Plan(kingdom,k.capital(),capital.getVillageTypeId(),level.dimension().location().toString(),selected==null?null:same?old.id():UUID.randomUUID(),selected==null?null:selected.target(),selected==null?null:selected.selection(),selected!=null && selected.logistics(),day,Math.addExact(day,RoyalNpcConfig.cadence().attemptDays()),Math.max(0,level.getGameTime()),fingerprint,selected==null?Status.IDLE:Status.CONDITIONAL,RoyalStrategyStore.bound(reason));
            store.write(old,plan);
        }
        if(plan.status()!=Status.CONDITIONAL)return null;
        if(!plan.fingerprint().equals(fingerprint(data,k,plan.target()))){invalidate(plan,"Native project/development/war metadata changed; conditional need must be reconsidered before execution");return null;}
        if(!capital.isActive() || !level.isLoaded(capital.getCenter()))return null;
        var service=RoyalNpcEconomyService.automatic(level);var preview=service.inspect(kingdom,day);var pending=plan;
        if(preview.candidates().stream().noneMatch(p->matches(pending,p)))return null;
        // Persist ownership of the exact once-only domain ID before entering any financial path.
        var issuing=plan.status(Status.ISSUING,"Conditional proposal ready for current domain validation; no completed construction claim");store.write(plan,issuing);
        var result=service.evaluateConditional(kingdom,day,random,issuing.id(),p->matches(issuing,p));
        if(result.attempt()!=null)store.write(issuing,issuing.status(Status.LINKED,"Bound economic authorization "+result.attempt()+"; "+result.reason()));
        else store.write(issuing,issuing.status(Status.CONDITIONAL,"Current physical/financial validation deferred: "+result.reason()));
        return result;
    }
    private boolean matches(Plan plan,RoyalNpcEconomyService.Proposal proposal){var c=proposal.choice();return plan.logistics()?c.kind()==RoyalNpcDecisionStore.Kind.SHIPMENT && plan.target().equals(c.target()):c.kind()==RoyalNpcDecisionStore.Kind.CONSTRUCTION && plan.target().equals(c.source()) && plan.selection().equals(c.selection());}
    private List<Need> needs(KingdomSavedData data,KingdomSavedData.Kingdom k,long day){
        var all=data.members(k.id()).stream().sorted().toList();int limit=Math.min(all.size(),RoyalNpcConfig.cadence().maximumVillages());var out=new ArrayList<Need>();
        for(int n=0;n<limit;n++){
            var id=all.get((int)((day*limit+n)%all.size()));var v=PoliticsService.village(level,id);if(v==null || v.getOwnerUUID()!=null || v.getTownhall()==null)continue;
            var pending=v.getPendingProject();
            if(pending!=null){if(com.millenairekingdoms.shipping.RoyalShipmentConfig.TRACKING.get())out.add(new Need(id,pending.planSetId(),true,100,"NATIVE_REGISTERED_METADATA: pending "+pending.planSetId()+". Evaluate logistical support when stocks load; no shortage, cargo or fee is assumed"));continue;}
            if(!RoyalConstructionConfig.TRACKING.get() || RoyalConstructionCatalog.naturalWorkPending(v))continue;
            var counts=new EnumMap<RoyalInvestmentPolicy.Role,Integer>(RoyalInvestmentPolicy.Role.class);for(var b:v.getBuildings())if(b!=v.getTownhall() && b.getStatus()==BuildingInstance.Status.COMPLETE)counts.merge(RoyalInvestmentPolicy.role(b.getPlanSetId()),1,Integer::sum);
            var context=RoyalInvestmentPolicy.context(level,data,k,id);
            for(var option:RoyalConstructionCatalog.forNpc(level,k.id(),id).options().stream().limit(128).toList()){
                var role=RoyalInvestmentPolicy.role(option.planSet());if(role==RoyalInvestmentPolicy.Role.UNCLASSIFIED)continue;
                int desired=role==RoyalInvestmentPolicy.Role.MILITARY && context.war()?Math.max(1,Math.min(4,(v.getVillagerRecords().size()+7)/8)):1;
                if(counts.getOrDefault(role,0)>=desired)continue;
                if(role==RoyalInvestmentPolicy.Role.CIVIC && (!id.equals(k.capital()) || data.members(k.id()).size()<3 || EconomyService.centerLevel(v)<2))continue;
                int weight=RoyalInvestmentPolicy.weight(context,role,RoyalNpcConfig.weights());
                if(weight>0)out.add(new Need(id,option.planSet(),false,weight,"NATIVE_REGISTERED_METADATA: missing "+role+" coverage, conditional "+option.planSet()+". Treasury/materials/builders UNKNOWN; current native eligibility and reserves must be revalidated"));
            }
        }
        var capital=PoliticsService.village(level,k.capital());
        if(capital!=null && capital.isActive() && level.isLoaded(capital.getCenter())){
            var ready=RoyalNpcEconomyService.automatic(level).inspect(k.id(),day).candidates();
            if(!ready.isEmpty())return ready.stream().map(p->{var c=p.choice();boolean logistics=c.kind()==RoyalNpcDecisionStore.Kind.SHIPMENT;var target=logistics?c.target():c.source();var v=PoliticsService.village(level,target);
                return new Need(target,logistics?v.getPendingProject().planSetId():c.selection(),logistics,p.weight(),"CURRENT_DOMAIN_PREVIEW: "+p.reason()+"; authorization and physical execution still require revalidation");}).toList();
        }
        return out.stream().anyMatch(Need::logistics)?out.stream().filter(Need::logistics).toList():out;
    }
    private String fingerprint(KingdomSavedData data,KingdomSavedData.Kingdom k,UUID target){var war=data.activeWar(k.id());var s=new StringBuilder().append(k.capital()).append('/').append(war==null?"PEACE":war.id());if(target!=null){var v=PoliticsService.village(level,target);s.append('/').append(target).append('/').append(data.owner(target));if(v!=null)s.append('/').append(v.getVillageTypeId()).append('/').append(v.getPendingProject()).append('/').append(v.getBuildings().stream().map(b->b.getId()+":"+b.getPlanSetId()+":"+b.getLevel()+":"+b.getStatus()).sorted().toList());}return UUID.nameUUIDFromBytes(s.toString().getBytes(StandardCharsets.UTF_8)).toString();}
    private boolean domainTerminal(Plan plan){
        var d=decisions().load(plan.id());if(d==null)return false;
        if(d.choice().kind()==RoyalNpcDecisionStore.Kind.CONSTRUCTION){var path=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction");if(!Files.exists(path.resolve("orders.dat")))return false;var entry=new RoyalConstructionStore(path).load(plan.id());return entry!=null && entry.terminal();}
        var path=com.millenairekingdoms.shipping.RoyalShipmentService.directory(level);if(!Files.exists(path.resolve("shipments.dat")))return false;var entry=new com.millenairekingdoms.shipping.RoyalShipmentStore(path).load(plan.id());return entry!=null && entry.terminal();
    }
    private RoyalNpcDecisionStore decisions(){return new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(level));}
    private void invalidate(Plan old,String reason){if(old!=null && old.status()!=Status.INVALIDATED && old.status()!=Status.ISSUING && old.status()!=Status.LINKED)store.write(old,old.status(Status.INVALIDATED,reason));}
}
