package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.autonomy.RoyalInvestmentPolicy.Role;
import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionConfig;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.RoyalNpcBudget;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore.*;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.shipping.RoyalShipmentService;
import com.millenairekingdoms.shipping.RoyalShipmentConfig;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import com.millenairekingdoms.shipping.RoyalVillageStock;
import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.AnywoodHelper;
import org.millenaire.config.MillenaireServerConfig;
import org.millenaire.culture.ModCultures;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.Village;
import org.slf4j.Logger;

/** Bounded need discovery and one shared paid action, never native growth/placement/currency simulation. All automatic
 * callers persist an attempt before issuing; existing domain runtimes own recovery, not replayed policy proposals. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class RoyalNpcEconomyService {
    private static final Logger LOGGER=LogUtils.getLogger();
    private static final ResourceLocation BUILD=ResourceLocation.parse("millenaire:build");
    public record Proposal(Choice choice,int weight,String reason) {}
    public record Preview(List<Proposal> candidates,long funds,String reason){public Preview{candidates=List.copyOf(candidates);}}
    public record Result(UUID attempt,Kind kind,Outcome outcome,String reason) {}
    private final ServerLevel level;
    private final RoyalNpcDecisionStore decisions;
    private final boolean automatic;
    public RoyalNpcEconomyService(ServerLevel level){this(level,false);}
    private RoyalNpcEconomyService(ServerLevel level,boolean automatic){this.level=level;this.automatic=automatic;this.decisions=new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(level));}
    /** Automatic discovery requires a live dependent tracker; explicit internal service users own their domain steps. */
    public static RoyalNpcEconomyService automatic(ServerLevel level){return new RoyalNpcEconomyService(level,true);}
    private boolean constructionEnabled(){return !automatic || RoyalConstructionConfig.TRACKING.get();}
    private boolean shippingEnabled(){return !automatic || RoyalShipmentConfig.TRACKING.get();}

    public Preview inspect(UUID kingdom,long day){
        thread();var data=KingdomSavedData.get(level);var k=data.kingdom(kingdom);var limits=RoyalNpcConfig.budgets();
        if(!constructionEnabled() && !shippingEnabled())return none("Both automatic economic domain trackers are paused");
        if(!limits.enabled())return none("New NPC economic decisions are disabled");
        if(k==null || !k.active() || k.fallen() || !data.npcGoverned(kingdom))return none("Current active NPC government is required; player kingdoms are excluded");
        var history=history(kingdom);long due=history.stream().mapToLong(Decision::nextDay).max().orElse(0);
        if(day<due)return none("Persistent economic cooldown until day "+due);
        return discover(data,k,day,history,limits,RoyalNpcConfig.cadence());
    }
    public Result evaluate(UUID kingdom,long day,RandomSource random){
        return evaluateConditional(kingdom,day,random,UUID.randomUUID(),proposal->true);
    }
    /** A strategic intention selects a conditional need, never bypasses current domain/budget/native discovery. */
    Result evaluateConditional(UUID kingdom,long day,RandomSource random,UUID attempt,java.util.function.Predicate<Proposal> eligible){
        thread();if(day<0)throw new IllegalArgumentException("Invalid NPC decision day");
        var existing=decisions.load(attempt);if(existing!=null){if(!existing.kingdom().equals(kingdom) || !existing.dimension().equals(level.dimension().location().toString()) || !eligible.test(new Proposal(existing.choice(),1,existing.reason())))throw new IllegalArgumentException("Conditional identity belongs to another economic authorization");return result(existing);}
        var data=KingdomSavedData.get(level);var k=data.kingdom(kingdom);var limits=RoyalNpcConfig.budgets();
        if(!constructionEnabled() && !shippingEnabled())return new Result(null,Kind.NONE,Outcome.SKIPPED,"Both automatic economic domain trackers are paused");
        if(!limits.enabled() || k==null || !k.active() || k.fallen() || !data.npcGoverned(kingdom))return new Result(null,Kind.NONE,Outcome.SKIPPED,"New decisions disabled or not an active NPC kingdom");
        var history=history(kingdom);if(history.stream().anyMatch(d->d.nextDay()>day))return new Result(null,Kind.NONE,Outcome.SKIPPED,"Persistent economic cooldown");
        var cadence=RoyalNpcConfig.cadence();var preview=discover(data,k,day,history,limits,cadence);var chosen=choose(preview.candidates().stream().filter(eligible).toList(),random);
        var capital=PoliticsService.village(level,k.capital());var choice=chosen==null ? Choice.none() : chosen.choice();
        var decision=new Decision(attempt,level.dimension().location().toString(),kingdom,day,Math.addExact(day,cadence.attemptDays()),
                new Budget(k.capital(),capital==null ? null : capital.getVillageTypeId(),limits.reserve(),limits.sharePermille()),choice,
                chosen==null ? Outcome.SKIPPED : Outcome.ISSUING,bounded(chosen==null ? preview.reason() : chosen.reason()));
        decisions.begin(decision); // Required durable boundary before either shared domain service can charge or reserve.
        if(chosen==null){log(decision);return result(decision);}
        Outcome outcome;String explanation;
        try{
            if(choice.kind()==Kind.CONSTRUCTION){
                var order=new RoyalConstructionService(level).requestQuotedNpc(kingdom,choice.source(),choice.selection(),attempt,choice.fee());
                outcome=order.stage()==RoyalConstructionStore.Stage.QUEUED || order.stage()==RoyalConstructionStore.Stage.IN_PROGRESS || order.stage()==RoyalConstructionStore.Stage.COMPLETED ? Outcome.ACCEPTED : Outcome.DEFERRED;
                explanation=chosen.reason()+"; shared construction="+order.stage()+"/"+order.blocked();
            }else{
                var shipment=new RoyalShipmentService(level).requestQuotedNpc(kingdom,choice.source(),choice.target(),choice.selection(),choice.quantity(),attempt,choice.fee(),choice.travelTicks());
                outcome=shipment.stage()==RoyalShipmentStore.Stage.COLLECTING || shipment.stage()==RoyalShipmentStore.Stage.IN_TRANSIT
                        || shipment.stage()==RoyalShipmentStore.Stage.AWAITING_DELIVERY || shipment.stage()==RoyalShipmentStore.Stage.DELIVERED ? Outcome.ACCEPTED : Outcome.DEFERRED;
                explanation=chosen.reason()+"; shared shipment="+shipment.stage()+"/"+shipment.blocked();
            }
        }catch(RuntimeException interrupted){outcome=Outcome.DEFERRED;explanation=chosen.reason()+"; domain step incomplete, inspect saved status: "+interrupted.getMessage();}
        // A failed acknowledgement retains ISSUING/cooldown. Never infer that no money/domain write happened.
        decision=decisions.finish(attempt,outcome,bounded(explanation));log(decision);return result(decision);
    }

    private Preview discover(KingdomSavedData data,KingdomSavedData.Kingdom k,long day,List<Decision> history,RoyalNpcConfig.BudgetLimits limits,RoyalNpcConfig.Cadence cadence){
        var profile=KingdomPersonality.forCapital(level,k);var capital=PoliticsService.village(level,k.capital());
        if(!profile.specialized() || capital==null || capital.getOwnerUUID()!=null)return none("Recognized original autonomous capital type/owner is required");
        if(!capital.isActive() || !level.isLoaded(capital.getCenter()))return none("Capital is inactive/unloaded; no chunks are forced");
        if(MillenaireServerConfig.SERVER.ignoreResourceCost.get())return none("Original resource costs are disabled; no NPC economic project is justified");
        RoyalTreasurySnapshot treasury;try{treasury=RoyalTreasurySnapshot.forNpc(level,k.id());}catch(IllegalArgumentException|IllegalStateException missing){return none("Actual capital treasury is unavailable: "+missing.getMessage());}
        long funds=treasury.spendableValue();var members=members(data,k.id(),profile.category(),day,cadence);var stocks=new HashMap<UUID,RoyalVillageStock>();
        var shipments=shipmentHistory().stream().filter(e->e.intent().dimension().equals(level.dimension().location().toString()) && e.intent().kingdom().equals(k.id()) && !e.terminal()).toList();
        var construction=constructionHistory().stream().filter(e->e.intent().dimension().equals(level.dimension().location().toString()) && e.intent().kingdom().equals(k.id()) && !e.terminal()).toList();
        var proposals=new ArrayList<Proposal>();var diagnostics=new java.util.LinkedHashMap<String,Integer>();String reason="No justified legal, resourced investment or safely supplied native shortage";
        if(shippingEnabled() && shipments.isEmpty())proposals.addAll(shortages(data,k,members,stocks,history,construction,day,funds,profile.category(),limits,cadence,diagnostics));
        else if(shippingEnabled())reason="Existing shipment/liability must settle before another NPC shipment";
        // Genuine existing native shortages take priority over optional new commissions. No player/natural queue mutation.
        if(!proposals.isEmpty()){treasury.requireUnchanged();return new Preview(proposals,funds,"Native pending resource shortage");}
        if(!constructionEnabled())return new Preview(List.of(),funds,"Automatic construction tracker is paused; "+reason);
        if(!construction.isEmpty())return new Preview(List.of(),funds,bounded("Existing royal construction/liability remains tracked; no second major project; "+diagnostics));
        if(recent(history,Kind.CONSTRUCTION,day,cadence.projectDays(),null))return new Preview(List.of(),funds,"Persistent kingdom project cooldown");
        for(var member:members){
            var village=PoliticsService.village(level,member);if(!usable(village)){note(diagnostics,"unavailable/local-player village");continue;}
            // Original9.0.3 canStartNewConstruction explicitly excludes wall segments from normal-building slots.
            // Preserve every ordinary task; an independently scheduled native wall must not disable royal economics.
            if(village.getPendingProject()!=null || village.getBuildings().stream().anyMatch(b->b.isBeingBuilt() && !b.isWallSegment())){note(diagnostics,"existing native project/task");continue;}
            if(!builder(village)){note(diagnostics,"no available loaded original builder");continue;}
            if(recent(history,Kind.CONSTRUCTION,day,cadence.villageDays(),member)){note(diagnostics,"village project cooldown");continue;}
            RoyalVillageStock stock;try{stock=stock(stocks,k.id(),member);}catch(IllegalArgumentException|IllegalStateException missing){note(diagnostics,"native storage unavailable");continue;}
            var context=RoyalInvestmentPolicy.context(level,data,k,member);var counts=new java.util.EnumMap<Role,Integer>(Role.class);
            for(var building:village.getBuildings())if(building!=village.getTownhall() && building.getStatus()==BuildingInstance.Status.COMPLETE)
                counts.merge(RoyalInvestmentPolicy.role(building.getPlanSetId()),1,Integer::sum);
            var catalog=RoyalConstructionCatalog.forNpc(level,k.id(),member);
            for(var option:catalog.options().stream().limit(128).toList()){
                var role=RoyalInvestmentPolicy.role(option.planSet());if(role==Role.UNCLASSIFIED)continue;
                if(!RoyalNpcBudget.allows(funds,option.patronageFee(),limits.reserve(),limits.sharePermille())){reason="Actual treasury cannot cover a justified fee while retaining reserve/share";continue;}
                int desired=role==Role.MILITARY && context.war() ? Math.max(1,Math.min(4,(living(village)+7)/8)) : 1;
                int gap=desired-counts.getOrDefault(role,0);if(gap<1)continue;
                if(role==Role.CIVIC && (!member.equals(k.capital()) || data.members(k.id()).size()<3 || EconomyService.centerLevel(village)<2
                        || funds/4<limits.reserve()))continue;
                try{if(!stock.missing(option.requiredMaterials()).isEmpty()){note(diagnostics,"native project materials missing");continue;}}
                catch(IllegalArgumentException|IllegalStateException unavailable){note(diagnostics,"native material evidence unavailable");continue;}
                int weight=Math.multiplyExact(RoyalInvestmentPolicy.weight(context,role,RoyalNpcConfig.weights()),gap*Math.max(1,4-Math.min(3,EconomyService.centerLevel(village))));
                if(weight>0)proposals.add(new Proposal(new Choice(Kind.CONSTRUCTION,member,null,option.planSet(),0,option.patronageFee(),0,null),weight,
                        "Missing "+role+" coverage "+gap+" in "+member+"; legal native "+option.planSet()+"; real builder/materials ready; "+RoyalInvestmentPolicy.describe(context)));
            }
            stock.requireUnchanged();
        }
        treasury.requireUnchanged();return new Preview(proposals,funds,bounded(reason+"; "+diagnostics));
    }

    private List<Proposal> shortages(KingdomSavedData data,KingdomSavedData.Kingdom k,List<UUID> members,Map<UUID,RoyalVillageStock> stocks,List<Decision> history,
                                     List<RoyalConstructionStore.Entry> construction,long day,long funds,KingdomPersonality.Category base,RoyalNpcConfig.BudgetLimits limits,RoyalNpcConfig.Cadence cadence,Map<String,Integer> diagnostics){
        var out=new ArrayList<Proposal>();var shipping=new RoyalShipmentService(level);int routeDays=base==KingdomPersonality.Category.ARTISAN ? Math.max(1,cadence.routeDays()/2) : cadence.routeDays();
        for(var targetId:members){
            var target=PoliticsService.village(level,targetId);if(!usable(target))continue;var pending=target.getPendingProject();if(pending==null)continue;
            var set=ModCultures.getBuildingPlanSet(pending.planSetId());var definition=set==null ? null : set.getLevel(pending.variant(),pending.level());
            if(definition==null || !set.culture().equals(target.getCultureId()))continue;
            RoyalVillageStock targetStock;try{targetStock=stock(stocks,k.id(),targetId);}catch(IllegalArgumentException|IllegalStateException unavailable){continue;}
            Map<ResourceLocation,Integer> missing;try{missing=targetStock.missing(definition.requiredResources());}catch(IllegalArgumentException|IllegalStateException unavailable){note(diagnostics,"native pending material evidence unavailable");continue;}
            if(missing.isEmpty())continue;note(diagnostics,"native pending material deficit");
            // A native pending fingerprint is diagnostic provenance, not a claim to own/mutate its queue identity.
            UUID need=construction.stream().filter(e->e.intent().actor()==null && e.intent().village().equals(targetId) && e.intent().planSet().equals(pending.planSetId()) && e.intent().variant().equals(pending.variant()))
                    .map(e->e.intent().id()).findFirst().orElseGet(()->UUID.nameUUIDFromBytes((targetId+"/"+pending.planSetId()+"/"+pending.variant()+"/"+pending.level()+"/"+pending.isUpgrade()+"/"+pending.buildingId()).getBytes(StandardCharsets.UTF_8)));
            var sources=members.stream().filter(id->!id.equals(targetId)).map(id->PoliticsService.village(level,id)).filter(this::usable)
                    .sorted(Comparator.comparingDouble(v->v.getCenter().distSqr(target.getCenter()))).toList();
            boolean chosen=false;
            for(var source:sources){
                var sourceId=source.getId().uuid();if(routeRecent(history,sourceId,targetId,day,routeDays)){note(diagnostics,"bidirectional route cooldown");continue;}
                if(Math.hypot((double)source.getCenter().getX()-target.getCenter().getX(),(double)source.getCenter().getZ()-target.getCenter().getZ())>cadence.maximumDistance())continue;
                RoyalVillageStock sourceStock;try{sourceStock=stock(stocks,k.id(),sourceId);}catch(IllegalArgumentException|IllegalStateException unavailable){continue;}
                for(var required:missing.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()){
                    var products=AnywoodHelper.isAnywood(required.getKey()) ? sourceStock.products().stream().filter(i->new ItemStack(i).is(AnywoodHelper.LOGS_TAG)).limit(64).toList()
                            : BuiltInRegistries.ITEM.getOptional(required.getKey()).stream().toList();
                    for(var product:products){
                        int available;try{available=sourceStock.availability(product).available();}catch(IllegalArgumentException unsupported){continue;}
                        int quantity=Math.min(Math.min(required.getValue(),available),Math.min(cadence.maximumCargo(),com.millenairekingdoms.shipping.RoyalShipmentConfig.current().maximumQuantity()));if(quantity<1){note(diagnostics,"source goods protected/no safe surplus");continue;}
                        var productId=BuiltInRegistries.ITEM.getKey(product);var quote=shipping.quoteNpc(k.id(),sourceId,targetId,productId,quantity);
                        if(!RoyalNpcBudget.allows(funds,quote.fee(),limits.reserve(),limits.sharePermille())){note(diagnostics,"transport fee exceeds reserve/share");continue;}
                        var context=RoyalInvestmentPolicy.context(level,data,k,targetId);int weight=Math.max(1,RoyalInvestmentPolicy.weight(context,RoyalInvestmentPolicy.role(pending.planSetId()),RoyalNpcConfig.weights()))*Math.min(64,required.getValue());
                        out.add(new Proposal(new Choice(Kind.SHIPMENT,sourceId,targetId,productId,quantity,quote.fee(),quote.travelTicks(),need),weight,
                                "Native pending "+pending.planSetId()+"/"+pending.variant()+" level"+pending.level()+" lacks "+required.getValue()+" "+required.getKey()+"; safe source "+sourceId+" offers "+available+"; send "+quantity+" to "+targetId+"; need="+need));
                        chosen=true;break;
                    }if(chosen)break;
                }if(chosen)break;
            }
            targetStock.requireUnchanged();
        }return out;
    }

    private List<UUID> members(KingdomSavedData data,UUID kingdom,KingdomPersonality.Category base,long day,RoyalNpcConfig.Cadence cadence){
        var all=data.members(kingdom).stream().sorted().toList();int limit=Math.min(32,cadence.maximumVillages()*(base==KingdomPersonality.Category.ARTISAN ? 2 : 1));if(all.size()<=limit)return all;
        int start=(int)((day/cadence.attemptDays()%all.size())*limit%all.size());var selected=new ArrayList<UUID>();for(int n=0;n<limit;n++)selected.add(all.get((start+n)%all.size()));return List.copyOf(selected);
    }
    private boolean usable(Village village){var type=village==null ? null : ModCultures.getVillageType(village.getVillageTypeId());return type!=null && !type.loneBuilding() && !type.playerControlled() && village.getOwnerUUID()==null
            && village.getTownhall()!=null && village.isActive() && level.isLoaded(village.getCenter());}
    private boolean builder(Village village){
        return village.getVillagerRecords().values().stream().anyMatch(record->{var type=ModCultures.getVillagerType(record.getVillagerTypeId());return !record.isKilled() && !record.isAwayRaiding() && !record.isRaidingVillage() && !record.isAwayHired()
                && type!=null && !type.isChild() && type.goals().contains(BUILD) && level.getEntity(record.getUuid()) instanceof MillVillager entity && entity.isAlive() && !entity.isNoAi();});
    }
    private static int living(Village village){return (int)village.getVillagerRecords().values().stream().filter(r->!r.isKilled()).count();}
    private RoyalVillageStock stock(Map<UUID,RoyalVillageStock> stocks,UUID kingdom,UUID village){return stocks.computeIfAbsent(village,key->RoyalVillageStock.forNpc(level,kingdom,key));}
    private List<Decision> history(UUID kingdom){return decisions.entries().stream().filter(d->d.dimension().equals(level.dimension().location().toString()) && d.kingdom().equals(kingdom)).toList();}
    private List<RoyalConstructionStore.Entry> constructionHistory(){var path=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction");return Files.exists(path.resolve("orders.dat")) ? new RoyalConstructionStore(path).entries() : List.of();}
    private List<RoyalShipmentStore.Entry> shipmentHistory(){var path=RoyalShipmentService.directory(level);return Files.exists(path.resolve("shipments.dat")) ? new RoyalShipmentStore(path).entries() : List.of();}
    private static boolean recent(List<Decision> history,Kind kind,long day,int days,UUID village){return history.stream().anyMatch(d->d.choice().kind()==kind && (village==null || village.equals(d.choice().source())) && day-d.day()<days);}
    private static boolean routeRecent(List<Decision> history,UUID a,UUID b,long day,int days){return history.stream().anyMatch(d->d.choice().kind()==Kind.SHIPMENT && day-d.day()<days
            && (a.equals(d.choice().source()) && b.equals(d.choice().target()) || b.equals(d.choice().source()) && a.equals(d.choice().target())));}
    private static Proposal choose(List<Proposal> candidates,RandomSource random){int total=0;for(var p:candidates)total=Math.addExact(total,p.weight());if(total==0)return null;int draw=random.nextInt(total);for(var p:candidates){draw-=p.weight();if(draw<0)return p;}throw new IllegalStateException("Invalid NPC candidate weights");}
    private static Preview none(String reason){return new Preview(List.of(),-1,bounded(reason));}
    private static void note(Map<String,Integer> diagnostics,String reason){diagnostics.merge(reason,1,Integer::sum);}
    private static Result result(Decision d){return new Result(d.id(),d.choice().kind(),d.outcome(),d.reason());}
    private static String bounded(String value){return value.substring(0,Math.min(512,value.length()));}
    private static void log(Decision d){LOGGER.info("[Kingdoms] NPC economy {} kingdom={} day={} kind={} outcome={} {}",d.id(),d.kingdom(),d.day(),d.choice().kind(),d.outcome(),d.reason());}
    private void thread(){if(!level.getServer().isSameThread())throw new IllegalStateException("NPC economic decisions require the server thread");}
}
