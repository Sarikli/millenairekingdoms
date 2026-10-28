package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.autonomy.RoyalStrategyStore.*;
import com.millenairekingdoms.construction.*;
import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.politics.PoliticsService;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.millenaire.building.BuildingInstance;

/** Persistent unpaid development and independently recoverable logistics. Domain services alone own physical effects. */
public final class RoyalStrategyService {
    private final ServerLevel level;
    private final RoyalStrategyStore store;
    private record Need(UUID target, ResourceLocation selection, int weight, String reason) {}

    public RoyalStrategyService(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Strategic decisions require server thread");
        this.level = level;
        this.store = new RoyalStrategyStore(level);
    }

    public Plan inspect(UUID kingdom) { return store.read(kingdom); }
    public Portfolio portfolio(UUID kingdom) { return store.portfolio(kingdom); }

    public RoyalNpcEconomyService.Result step(UUID kingdom, long day, RandomSource random) {
        var data = KingdomSavedData.get(level);
        var k = data.kingdom(kingdom);
        if (!RoyalNpcConfig.budgets().enabled() || k == null || k.fallen() || !k.active() || !data.npcGoverned(kingdom)) {
            for (var lane : Lane.values()) invalidate(lane, store.read(kingdom, lane), "NPC government no longer authorizes new economic work");
            return null;
        }
        var capital = PoliticsService.village(level, k.capital());
        if (capital == null || capital.getTownhall() == null || capital.getOwnerUUID() != null
                || !KingdomPersonality.forCapital(level, k).specialized()) return null;

        var project = reconcile(Lane.DEVELOPMENT, store.read(kingdom, Lane.DEVELOPMENT), day);
        if (replaceable(project, day)) project = planDevelopment(data, k, project, day, random);
        if (project != null && project.status() == Status.CONDITIONAL && !authorized(data, k, project)) {
            invalidate(Lane.DEVELOPMENT, project, "Capital, native type or village membership changed before payment");
            project = store.read(kingdom, Lane.DEVELOPMENT);
        }

        var logistics = reconcile(Lane.LOGISTICS, store.read(kingdom, Lane.LOGISTICS), day);
        if (replaceable(logistics, day)) {
            var pending = data.members(kingdom).stream().sorted().map(id -> PoliticsService.village(level, id))
                    .filter(v -> v != null && v.getOwnerUUID() == null && v.getPendingProject() != null).findFirst().orElse(null);
            if (pending != null) {
                var next = new Plan(kingdom, k.capital(), capital.getVillageTypeId(), dimension(), UUID.randomUUID(), pending.getId().uuid(),
                        pending.getPendingProject().planSetId(), true, day, day + RoyalNpcConfig.cadence().attemptDays(), tick(),
                        fingerprint(data, k, pending.getId().uuid()), Status.CONDITIONAL,
                        "WAITING_SUPPLY_OBSERVATION: registered native project; shortage and goods must be measured when loaded");
                store.write(Lane.LOGISTICS, logistics, next);logistics = next;
            }
        }
        // No physical balance or cargo is inferred while the capital is cold.
        if (!capital.isActive() || !level.isLoaded(capital.getCenter())) {
            waiting(Lane.DEVELOPMENT, project, day, "WAITING_ACTIVATION: unpaid project retained; treasury/materials/builders unknown");
            waiting(Lane.LOGISTICS, logistics, day, "WAITING_ACTIVATION: registered supply need; physical shortage remains unknown");
            return null;
        }
        var supply = project != null && project.status() == Status.CONDITIONAL
                ? new RoyalNpcEconomyService.ProjectNeed(project.id(), project.target(), project.selection()) : null;
        var service = RoyalNpcEconomyService.automatic(level);
        var preview = service.inspect(kingdom, day, supply);

        // A linked construction never holds the logistics lane hostage.
        if (logistics != null && logistics.status() == Status.CONDITIONAL && !authorized(data, k, logistics)) {
            invalidate(Lane.LOGISTICS, logistics, "Supply target or capital authority changed before payment");
            logistics = store.read(kingdom, Lane.LOGISTICS);
        }
        if (replaceable(logistics, day) || logistics.status() == Status.CONDITIONAL && day >= logistics.nextDay() && unperformed(logistics)) {
            var candidates = preview.candidates().stream().filter(p -> p.choice().kind() == RoyalNpcDecisionStore.Kind.SHIPMENT).toList();
            var selected = chooseProposal(candidates, random);
            if (selected != null) {
                var choice = selected.choice();
                var next = new Plan(kingdom, k.capital(), capital.getVillageTypeId(), dimension(), UUID.randomUUID(), choice.target(),
                        choice.selection(), true, day, day + RoyalNpcConfig.cadence().attemptDays(), tick(),
                        fingerprint(data, k, choice.target()), Status.CONDITIONAL, RoyalStrategyStore.bound(selected.reason()));
                store.write(Lane.LOGISTICS, logistics, next);
                logistics = next;
            }
        }
        if (logistics != null && logistics.status() == Status.CONDITIONAL) {
            var selected = logistics;
            if (preview.candidates().stream().anyMatch(p -> shipmentMatches(selected, p))) {
                return issue(Lane.LOGISTICS, logistics, day, random, p -> shipmentMatches(selected, p), supply);
            }
            waiting(Lane.LOGISTICS, logistics, day, "WAITING_SUPPLY: " + preview.reason());
        }

        if (project == null || project.status() != Status.CONDITIONAL) return null;
        var selected = project;
        var village = PoliticsService.village(level, project.target());
        if (village == null) return null;
        if (RoyalConstructionCatalog.naturalWorkPending(village)) {
            waiting(Lane.DEVELOPMENT, project, day, "WAITING_NATIVE_PROJECT: existing original work keeps its slot; supplies remain independent");
            return null;
        }
        var options = RoyalConstructionCatalog.forNpc(level, kingdom, project.target()).options();
        if (options.stream().noneMatch(o -> o.planSet().equals(selected.selection()))) {
            invalidate(Lane.DEVELOPMENT, project, "Original growth eligibility changed; unpaid work retired without a debit");
            return null;
        }
        var choice = options.stream().filter(o -> o.planSet().equals(selected.selection())).findFirst().orElseThrow();
        var role = RoyalInvestmentPolicy.role(choice.planSet());
        int count = (int)village.getBuildings().stream().filter(b -> b != village.getTownhall()
                && b.getStatus() == BuildingInstance.Status.COMPLETE && RoyalInvestmentPolicy.role(b.getPlanSetId()) == role).count();
        var context = RoyalInvestmentPolicy.context(level, data, k, selected.target());
        int desired = role == RoyalInvestmentPolicy.Role.MILITARY && context.war()
                ? Math.max(1, Math.min(4, (village.getVillagerRecords().size() + 7) / 8)) : 1;
        if (count >= desired) {
            invalidate(Lane.DEVELOPMENT, project, "Original village development already satisfies this functional need; no commission fee");return null;
        }
        if (preview.candidates().stream().anyMatch(p -> constructionMatches(selected, p)))
            return issue(Lane.DEVELOPMENT, project, day, random, p -> constructionMatches(selected, p), supply);
        waiting(Lane.DEVELOPMENT, project, day, "WAITING_RESOURCES: " + preview.reason());
        return null;
    }

    private RoyalNpcEconomyService.Result issue(Lane lane, Plan plan, long day, RandomSource random,
            java.util.function.Predicate<RoyalNpcEconomyService.Proposal> filter, RoyalNpcEconomyService.ProjectNeed supply) {
        var issuing = plan.status(Status.ISSUING, "Current native/budget validation ready; preserve this domain ID before payment");
        store.write(lane, plan, issuing);
        var result = RoyalNpcEconomyService.automatic(level).evaluateConditional(plan.kingdom(), day, random, plan.id(), filter, supply);
        var next = result.attempt() == null ? issuing.status(Status.CONDITIONAL, "Current physical validation deferred: " + result.reason())
                : issuing.status(Status.LINKED, "Bound domain authorization " + result.attempt() + "; " + result.reason());
        store.write(lane, issuing, next);
        return result;
    }

    private Plan reconcile(Lane lane, Plan plan, long day) {
        if (plan == null || plan.status() != Status.ISSUING && plan.status() != Status.LINKED) return plan;
        var decision = decisions().load(plan.id());
        if (decision != null && (!decision.kingdom().equals(plan.kingdom()) || !decision.dimension().equals(plan.dimension())
                || !decision.budget().capital().equals(plan.capital()))) throw new IllegalStateException("Original economic binding changed; retain evidence");
        if (decision != null && plan.status() == Status.ISSUING) {
            var linked = plan.status(Status.LINKED, "Recovered original economic authorization; domain runtime retains ownership");
            store.write(lane, plan, linked);plan = linked;
        }
        if (day >= Math.max(plan.nextDay(), decision == null ? plan.nextDay() : decision.nextDay()) && unperformed(plan)) {
            var retired = plan.status(Status.INVALIDATED, "No payment, construction or shipment record exists after cooldown; retire without replay");
            store.write(lane, plan, retired);return retired;
        }
        return plan;
    }

    private boolean replaceable(Plan plan, long day) {
        return plan == null || plan.status() == Status.INVALIDATED
                || plan.status() == Status.IDLE && day >= plan.nextDay()
                || plan.status() == Status.LINKED && day >= plan.nextDay() && domainTerminal(plan);
    }

    private Plan planDevelopment(KingdomSavedData data, KingdomSavedData.Kingdom k, Plan old, long day, RandomSource random) {
        var candidates = developmentNeeds(data, k, day);
        Need selected = null;int total = candidates.stream().mapToInt(Need::weight).sum();
        if (total > 0) {int draw = random.nextInt(total);for (var need : candidates) {draw -= need.weight();if (draw < 0) {selected = need;break;}}}
        var capital = PoliticsService.village(level, k.capital());
        var next = new Plan(k.id(), k.capital(), capital.getVillageTypeId(), dimension(), selected == null ? null : UUID.randomUUID(),
                selected == null ? null : selected.target(), selected == null ? null : selected.selection(), false,
                day, day + RoyalNpcConfig.cadence().attemptDays(), tick(), fingerprint(data, k, selected == null ? null : selected.target()),
                selected == null ? Status.IDLE : Status.CONDITIONAL, selected == null ? "No currently legal development gap; no payment authorized" : selected.reason());
        store.write(Lane.DEVELOPMENT, old, next);return next;
    }

    private List<Need> developmentNeeds(KingdomSavedData data, KingdomSavedData.Kingdom k, long day) {
        var all = data.members(k.id()).stream().sorted().toList();
        int limit = Math.min(all.size(), RoyalNpcConfig.cadence().maximumVillages());var out = new ArrayList<Need>();
        for (int n = 0; n < limit; n++) {
            var id = all.get((int)((day * limit + n) % all.size()));var village = PoliticsService.village(level, id);
            if (village == null || village.getOwnerUUID() != null || village.getTownhall() == null) continue;
            if (!RoyalConstructionConfig.TRACKING.get()) continue;
            var counts = new EnumMap<RoyalInvestmentPolicy.Role, Integer>(RoyalInvestmentPolicy.Role.class);
            for (var building : village.getBuildings()) if (building != village.getTownhall() && building.getStatus() == BuildingInstance.Status.COMPLETE)
                counts.merge(RoyalInvestmentPolicy.role(building.getPlanSetId()), 1, Integer::sum);
            var context = RoyalInvestmentPolicy.context(level, data, k, id);
            for (var option : RoyalConstructionCatalog.forNpc(level, k.id(), id).options().stream().limit(128).toList()) {
                var role = RoyalInvestmentPolicy.role(option.planSet());if (role == RoyalInvestmentPolicy.Role.UNCLASSIFIED) continue;
                int desired = role == RoyalInvestmentPolicy.Role.MILITARY && context.war() ? Math.max(1, Math.min(4, (village.getVillagerRecords().size() + 7) / 8)) : 1;
                int gap = desired - counts.getOrDefault(role, 0);if (gap < 1) continue;
                if (role == RoyalInvestmentPolicy.Role.CIVIC && (!id.equals(k.capital()) || all.size() < 3 || EconomyService.centerLevel(village) < 2)) continue;
                int weight = RoyalInvestmentPolicy.weight(context, role, RoyalNpcConfig.weights()) * gap * Math.max(1, 4 - Math.min(3, EconomyService.centerLevel(village)));
                out.add(new Need(id, option.planSet(), weight, "UNPAID_DEVELOPMENT: missing " + role + "; materials and builder revalidate before the construction fee"));
            }
        }
        // Prefer a currently executable investment to an unavailable metadata-only alternative.
        var capital = PoliticsService.village(level, k.capital());
        if (capital.isActive() && level.isLoaded(capital.getCenter())) {
            var ready = RoyalNpcEconomyService.automatic(level).inspect(k.id(), day).candidates().stream()
                    .filter(p -> p.choice().kind() == RoyalNpcDecisionStore.Kind.CONSTRUCTION).toList();
            if (!ready.isEmpty()) return ready.stream().map(p -> new Need(p.choice().source(), p.choice().selection(), p.weight(), p.reason())).toList();
        }
        return out;
    }

    private boolean authorized(KingdomSavedData data, KingdomSavedData.Kingdom k, Plan plan) {
        var capital = PoliticsService.village(level, k.capital());var target = PoliticsService.village(level, plan.target());
        return plan.capital().equals(k.capital()) && plan.capitalType().equals(capital.getVillageTypeId())
                && k.id().equals(data.owner(plan.target())) && target != null && target.getOwnerUUID() == null;
    }
    private static boolean constructionMatches(Plan plan, RoyalNpcEconomyService.Proposal proposal) {
        var c = proposal.choice();return c.kind() == RoyalNpcDecisionStore.Kind.CONSTRUCTION && plan.target().equals(c.source()) && plan.selection().equals(c.selection());
    }
    private static boolean shipmentMatches(Plan plan, RoyalNpcEconomyService.Proposal proposal) {
        var c = proposal.choice();return c.kind() == RoyalNpcDecisionStore.Kind.SHIPMENT && plan.target().equals(c.target());
    }
    private static RoyalNpcEconomyService.Proposal chooseProposal(List<RoyalNpcEconomyService.Proposal> proposals, RandomSource random) {
        int total = proposals.stream().mapToInt(RoyalNpcEconomyService.Proposal::weight).sum();if (total == 0) return null;
        int draw = random.nextInt(total);for (var proposal : proposals) {draw -= proposal.weight();if (draw < 0) return proposal;}throw new IllegalStateException("Invalid economic weights");
    }
    private void waiting(Lane lane, Plan plan, long day, String reason) {
        if (plan == null || plan.status() != Status.CONDITIONAL) return;
        var bounded = RoyalStrategyStore.bound(reason);
        if (plan.reason().equals(bounded) && day == plan.evaluatedDay() && tick() - plan.metadataObservedTick() < 1200) return;
        var next = new Plan(plan.kingdom(),plan.capital(),plan.capitalType(),plan.dimension(),plan.id(),plan.target(),plan.selection(),plan.logistics(),
                Math.max(day,plan.evaluatedDay()),Math.max(day+1,plan.nextDay()),tick(),plan.fingerprint(),Status.CONDITIONAL,bounded);
        store.write(lane,plan,next);
    }
    private void invalidate(Lane lane, Plan plan, String reason) {
        if (plan != null && plan.status() != Status.INVALIDATED && plan.status() != Status.ISSUING && plan.status() != Status.LINKED)
            store.write(lane, plan, plan.status(Status.INVALIDATED, reason));
    }
    private String dimension() { return level.dimension().location().toString(); }
    private long tick() { return Math.max(0, level.getGameTime()); }
    private String fingerprint(KingdomSavedData data, KingdomSavedData.Kingdom k, UUID target) {
        return UUID.nameUUIDFromBytes((k.id() + "/" + k.capital() + "/" + target + "/" + (target == null ? 0 : data.revision(target))).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    private boolean unperformed(Plan plan){
        var root=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/millenairekingdoms");
        var construction=root.resolve("royal-construction");if(Files.exists(construction.resolve("orders.dat")) && new RoyalConstructionStore(construction).load(plan.id())!=null)return false;
        var shipments=com.millenairekingdoms.shipping.RoyalShipmentService.directory(level);if(Files.exists(shipments.resolve("shipments.dat")) && new com.millenairekingdoms.shipping.RoyalShipmentStore(shipments).load(plan.id())!=null)return false;
        var payments=root.resolve("royal-payments");return !Files.exists(payments) || new RoyalPaymentJournal(payments).load(plan.id())==null;
    }
    private boolean domainTerminal(Plan plan){
        var d=decisions().load(plan.id());if(d==null)return false;
        if(d.choice().kind()==RoyalNpcDecisionStore.Kind.CONSTRUCTION){var path=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction");if(!Files.exists(path.resolve("orders.dat")))return false;var entry=new RoyalConstructionStore(path).load(plan.id());return entry!=null && entry.terminal();}
        var path=com.millenairekingdoms.shipping.RoyalShipmentService.directory(level);if(!Files.exists(path.resolve("shipments.dat")))return false;var entry=new com.millenairekingdoms.shipping.RoyalShipmentStore(path).load(plan.id());return entry!=null && entry.terminal();
    }
    private RoyalNpcDecisionStore decisions(){return new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(level));}
}
