package com.millenairekingdoms.test;

import static com.millenairekingdoms.test.AutonomySupport.*;

import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.autonomy.AutonomyService;
import com.millenairekingdoms.autonomy.AutonomyService.JoinDecision;
import com.millenairekingdoms.autonomy.AutonomyService.PeaceDecision;
import com.millenairekingdoms.autonomy.Military;
import com.millenairekingdoms.autonomy.RaidOrderService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Outcome;
import com.millenairekingdoms.kingdom.KingdomSavedData.WarStatus;
import com.millenairekingdoms.politics.DiplomacyService;
import com.millenairekingdoms.politics.KingdomDay;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.village.Village;

/** Stage 6.2 packages A-E on real Millénaire villages and records: NPC joining, war decisions and their tension
 * sources, conquest orders turned into real Millénaire raids, deferral and revalidation, peace and truces.
 * Probabilities are tested at both ends with a fixed random value, never left to chance. */
@PrefixGameTestTemplate(false)
public final class AutonomyGameTests {

    // ---------------------------------------------------------------- A: joining

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void npcVillageJoinsNearbyNpcKingdomOnGoodRelations(GameTestHelper h) throws Exception {
        var capital = village(h, 1, 1, "Join capital"); var v = village(h, 5, 5, "Join village"); var second = village(h, 1, 5, "Join rival");
        try {
            var d = new KingdomSavedData(); var k = kingdom(d, capital); var l = kingdom(d, second);
            var level = h.getLevel();
            relation(capital, v, 49); relation(second, v, 20);
            h.assertTrue(AutonomyService.decideJoin(level, d, v, yes()) == JoinDecision.NONE && d.owner(id(v)) == null, "1. Relation 49 is not enough");
            relation(capital, v, 50);
            double chance = AutonomyService.joinChance(level, d, k, v);
            h.assertTrue(chance > 0 && chance < 1, "Joining is a measured chance, never certain: " + chance);
            h.assertTrue(AutonomyService.decideJoin(level, d, v, no()) == JoinDecision.NONE && d.owner(id(v)) == null, "A failed roll changes nothing");
            var before = millenaire(h);
            var culture = v.getCultureId(); var name = v.getVillageName(); var townhall = v.getTownhall().getId();
            h.assertTrue(AutonomyService.decideJoin(level, d, v, yes()) == JoinDecision.JOINED && k.id().equals(d.owner(id(v))), "1. Joins at 50");
            h.assertTrue(before.equals(millenaire(h)) && v.getOwnerUUID() == null && culture.equals(v.getCultureId()) && name.equals(v.getVillageName())
                    && townhall.equals(v.getTownhall().getId()), "20. Joining changes no Millénaire data (owner, culture, buildings, chief)");
            relation(second, v, 100);
            h.assertTrue(AutonomyService.joinTarget(level, d, v) == null && AutonomyService.decideJoin(level, d, v, yes()) == JoinDecision.NONE
                    && k.id().equals(d.owner(id(v))), "A member village never joins a second kingdom");
            // Proximity: a village beyond Millénaire's relation radius is never a candidate, and distance lowers the chance.
            var near = village(h, 3, 3, "Join near"); var far = farVillage(h, "Join far");
            relation(capital, near, 60); relation(capital, far, 60);
            h.assertTrue(AutonomyService.joinTarget(level, d, far) == null && k.equals(AutonomyService.joinTarget(level, d, near))
                    && AutonomyService.joinChance(level, d, k, far) < AutonomyService.joinChance(level, d, k, near), "Proximity counts");
            remove(h, near, far);
            h.succeed();
        } finally { remove(h, capital, v, second); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void playerVillagesAndRuledKingdomsDecideThemselves(GameTestHelper h) throws Exception {
        var capital = village(h, 1, 1, "Ruled capital A6"); var owned = village(h, 5, 5, "Owned A6"); var npc = village(h, 1, 5, "Applicant A6");
        var enemy = village(h, 5, 1, "Hated A6");
        try {
            var level = h.getLevel(); var d = KingdomSavedData.get(level);
            var k = kingdom(d, capital); var e = kingdom(d, enemy);
            var rex = Stage5Support.player(h, "Rex", capital);
            Stage5Support.reputation(h, capital, rex.getUUID(), 40000);
            h.assertTrue(Stage5Support.run(rex, "kingdom claim") == 1, "Rex rules the kingdom");
            owned.setOwner(UUID.randomUUID(), "Olga"); relation(capital, owned, 100); relation(capital, npc, 80);
            h.assertTrue(AutonomyService.decideJoin(level, d, owned, yes()) == JoinDecision.NONE && d.owner(id(owned)) == null,
                    "2. A player's village never joins without its owner");
            h.assertTrue(AutonomyService.decideJoin(level, d, npc, yes()) == JoinDecision.APPLIED && d.owner(id(npc)) == null
                    && d.joinRequest(id(npc)).fromNpcVillage(), "3. A ruled kingdom does not expand on its own: the village applies");
            relation(capital, npc, 40);
            h.assertTrue(Stage5Support.run(rex, "kingdom accept " + id(npc)) == 0 && d.joinRequest(id(npc)) == null && d.owner(id(npc)) == null,
                    "An application whose village no longer wants to join is withdrawn");
            relation(capital, npc, 80);
            AutonomyService.decideJoin(level, d, npc, yes());
            h.assertTrue(Stage5Support.run(rex, "kingdom accept " + id(npc)) == 1 && k.id().equals(d.owner(id(npc))) && npc.getOwnerUUID() == null,
                    "The ruler accepts the village's own application");
            relation(capital, enemy, -100); raider(capital);
            h.assertTrue(AutonomyService.decideWar(level, d, k, Math.max(0, d.day()), yes(), ON) == null && d.activeWar(k.id()) == null,
                    "3. No NPC war on behalf of a ruled kingdom");
            h.assertTrue(AutonomyService.decideCampaign(level, d, k, Math.max(0, d.day()), yes(), ON) == null && d.orderOf(k.id()) == null,
                    "3. No NPC raid on behalf of a ruled kingdom");
            d.clearRuler(k.id());
            h.succeed();
        } finally { remove(h, capital, owned, npc, enemy); }
    }

    // ---------------------------------------------------------------- B: war decisions and tension

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void npcDeclaresWarOnlyWhenHostileReadyAndFree(GameTestHelper h) throws Exception {
        var a = village(h, 1, 1, "War A6"); var b = village(h, 5, 5, "War B6"); var c = village(h, 1, 5, "War C6");
        try {
            var level = h.getLevel(); var d = new KingdomSavedData();
            var ka = kingdom(d, a); var kb = kingdom(d, b); var kc = kingdom(d, c);
            var raider = raider(a);
            relation(a, b, -69); relation(a, c, 0); relation(b, c, 0);
            h.assertTrue(AutonomyService.warTarget(level, d, ka, 0, ON) == null, "4. -69 is above the threshold");
            relation(a, b, -70);
            h.assertTrue(kb.equals(AutonomyService.warTarget(level, d, ka, 0, ON)), "4. -70 makes the kingdom a candidate");
            double chance = AutonomyService.declareChance(level, d, ka, kb, ON);
            h.assertTrue(chance > 0 && chance < 1, "Declaration is probabilistic: " + chance);
            h.assertTrue(AutonomyService.decideWar(level, d, ka, 0, no(), ON) == null && d.activeWar(ka.id()) == null, "A failed roll declares nothing");
            raider.setKilled(true);
            h.assertTrue(AutonomyService.decideWar(level, d, ka, 0, yes(), ON) == null, "7. Without a living raider there is no war");
            raider.setKilled(false);
            var war = AutonomyService.decideWar(level, d, ka, 0, yes(), ON);
            h.assertTrue(war != null && war.attackerKingdom().equals(ka.id()) && war.defenderKingdom().equals(kb.id()), "4. NPC kingdom declares war");
            relation(a, c, -90); relation(b, c, -90); raider(c);
            h.assertTrue(AutonomyService.decideWar(level, d, ka, 0, yes(), ON) == null && AutonomyService.decideWar(level, d, kc, 0, yes(), ON) == null,
                    "One active war per kingdom, attacker and defender alike");
            d.peace(war.id(), KingdomSavedData.NPC_PEACE_REASON, 10);
            h.assertTrue(d.truceUntil(ka.id(), kb.id()) == 10, "6. Peace starts a ten-day truce");
            relation(a, c, 0); relation(b, c, 0);
            h.assertTrue(AutonomyService.warTarget(level, d, ka, 9, ON) == null, "6. No war during the truce");
            d.startDay(10, Map.of());
            h.assertTrue(d.truceUntil(ka.id(), kb.id()) == -1 && kb.equals(AutonomyService.warTarget(level, d, ka, 10, ON)), "6. The truce ends on day 10");
            // A stronger kingdom is likelier to declare, never certain.
            for (int i = 0; i < 6; i++) raider(a);
            double strong = AutonomyService.declareChance(level, d, ka, kb, ON);
            defender(b); defender(b); defender(b);
            double weaker = AutonomyService.declareChance(level, d, ka, kb, ON);
            h.assertTrue(strong >= chance && weaker <= strong && strong < 1, "Strength helps, within bounds: " + chance + " " + strong + " " + weaker);
            var far = farVillage(h, "War far"); var kf = kingdom(d, far); relation(a, far, -100); raider(far);
            h.assertTrue(!AutonomyService.neighbours(level, d, ka, kf) && AutonomyService.neighbours(level, d, ka, kb),
                    "Only kingdoms within Millénaire's relation radius are war neighbours");
            remove(h, far);
            // Operator commands are unaffected by truces and autonomy.
            var again = d.declareWar(kb.id(), ka.id(), 5);
            h.assertTrue(again.status() == WarStatus.ACTIVE, "Operators can still declare any war");
            h.succeed();
        } finally { remove(h, a, b, c); }
    }

    /** 5. Millénaire's own nightly diplomacy is unbounded and biased away from neutral (villages of different cultures start
     * at -30 and tend down, of the same culture at +50 and tend up); the addon's ±50 only bounded its own drift. */
    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void warThresholdIsReachedNaturally(GameTestHelper h) throws Exception {
        var level = h.getLevel(); var types = raidingTypes();
        h.assertTrue(types.size() >= 2, "Two cultures with raiding villages exist");
        int pairs = 40, reached = 0, sameReached = 0;
        var made = new java.util.ArrayList<Village>();
        try {
            for (int i = 0; i < pairs; i++) {
                var x = village(h, 1, 1, "Drift x" + i, types.get(0)); var y = village(h, 2, 2, "Drift y" + i, types.get(1));
                var s1 = village(h, 3, 3, "Same x" + i, types.get(0)); var s2 = village(h, 4, 4, "Same y" + i, types.get(0));
                made.add(x); made.add(y); made.add(s1); made.add(s2);
                relation(x, y, -30); relation(s1, s2, 50); // Millénaire's initial relations (VillageSpawner.initializeRelations).
                boolean low = false, sameLow = false;
                for (int night = 0; night < 300; night++) {
                    for (Village v : new Village[]{x, y, s1, s2}) org.millenaire.village.VillageDiplomacyHelper.performNightlyDiplomacyDrift(level, v);
                    low |= x.getRelation(y.getId()) <= -70; sameLow |= s1.getRelation(s2.getId()) <= -70;
                }
                if (low) reached++;
                if (sameLow) sameReached++;
            }
            h.assertTrue(reached >= 10, "Different cultures reach -70 through Millénaire's own diplomacy: " + reached + "/" + pairs);
            // The addon's border tension: two kingdoms whose capitals are neutral but whose bordering villages are hostile.
            var d = new KingdomSavedData();
            var ca = village(h, 1, 1, "Border A", types.get(0)); var cb = village(h, 5, 5, "Border B", types.get(1));
            var ma = village(h, 1, 5, "Border member A", types.get(0)); var mb = village(h, 5, 1, "Border member B", types.get(1));
            made.add(ca); made.add(cb); made.add(ma); made.add(mb);
            var ka = kingdom(d, ca); var kb = kingdom(d, cb); d.attach(ka.id(), id(ma)); d.attach(kb.id(), id(mb));
            relation(ca, cb, 0); relation(ma, mb, -90); relation(ca, mb, -90); relation(cb, ma, -90); raider(ma);
            int days = 0;
            RandomSource random = RandomSource.create(7);
            while (ca.getRelation(cb.getId()) > -70 && days < 120) { DiplomacyService.border(level, d, random); days++; }
            h.assertTrue(ca.getRelation(cb.getId()) <= -70 && days > 20, "Border hostility pulls neutral capitals to -70 in " + days + " days, not at once");
            var war = AutonomyService.decideWar(level, d, ka, 0, yes(), ON);
            h.assertTrue(war != null, "Then the NPC kingdom may declare war on its own");
            System.out.println("AUTONOMY_DRIFT_PASS millenaire different-culture pairs reaching -70 in 300 nights: " + reached + "/" + pairs
                    + ", same-culture: " + sameReached + "/" + pairs + "; border tension 0 -> -70 in " + days + " days");
            h.succeed();
        } finally { made.forEach(v -> remove(h, v)); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void millenaireRaidsBetweenKingdomsCreateTension(GameTestHelper h) throws Exception {
        var ca = village(h, 1, 1, "Tension A"); var cb = village(h, 5, 5, "Tension B"); var m = village(h, 1, 5, "Tension member");
        try {
            var level = h.getLevel(); var d = KingdomSavedData.get(level);
            var ka = kingdom(d, ca); var kb = kingdom(d, cb); d.attach(kb.id(), id(m));
            relation(ca, cb, 0); raider(ca);
            // A normal Millénaire raid (no conquest, no war) from A's capital on B's member village.
            org.millenaire.combat.raid.RaidManager.planRaid(ca, m, level);
            org.millenaire.combat.raid.RaidManager.startRaidForced(ca, m, level);
            raidManager("endRaid", ca, m, level);
            h.assertTrue(ca.getRelation(cb.getId()) == -DiplomacyService.RAID_TENSION && cb.getRelation(ca.getId()) == -DiplomacyService.RAID_TENSION,
                    "A Millénaire raid between kingdoms strains their capitals' relation once");
            h.assertTrue(d.activeWar(ka.id()) == null && d.activeWar(kb.id()) == null && kb.id().equals(d.owner(id(m))), "No war or transfer from a normal raid");
            h.succeed();
        } finally { remove(h, ca, cb, m); }
    }

    // ---------------------------------------------------------------- C: real conquest raids from NPC decisions

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void npcCampaignWinsTwoRealRaidsWithCooldown(GameTestHelper h) throws Exception {
        var kc = village(h, 1, 1, "Campaign capital"); var src = village(h, 3, 1, "Campaign source");
        var tc = village(h, 5, 5, "Campaign enemy capital"); var target = village(h, 5, 1, "Campaign target");
        try {
            var level = h.getLevel(); var d = KingdomSavedData.get(level);
            var k = kingdom(d, kc); var t = kingdom(d, tc); d.attach(k.id(), id(src)); d.attach(t.id(), id(target));
            UUID owner = UUID.randomUUID(); target.setOwner(owner, "Local lord");
            raider(src);
            var war = d.declareWar(k.id(), t.id(), level.getGameTime());
            long day = Math.max(0, d.day());
            h.assertTrue(AutonomyService.warTargets(d, war).equals(java.util.List.of(id(target))), "11. Capital closed: only the member is a target");
            var order = AutonomyService.decideCampaign(level, d, k, day, yes(), ON);
            h.assertTrue(order != null && order.target().equals(id(target)) && order.source().equals(id(src)) && order.byNpc()
                    && order.dueGameTime() > level.getGameTime(), "NPC kingdom picks a ready source and prepares an order");
            h.assertTrue(AutonomyService.decideCampaign(level, d, k, day, yes(), ON) == null, "13. One order per kingdom");
            h.assertTrue(RaidOrderService.process(level) == 0 && d.order(order.id()) != null && src.getRaidTarget() == null, "Nothing leaves before preparation ends");
            var attempt = RaidOrderService.attempt(level, d, order, ON);
            h.assertTrue(attempt.status() == RaidOrderService.Status.STARTED && d.order(order.id()) == null && d.raid(id(target)) != null
                    && target.getId().equals(src.getRaidTarget()) && target.isUnderAttack(), "Order becomes a real Millénaire raid; order removed in the same snapshot");
            h.assertTrue(((java.util.Map<?, ?>) raidManager("applyLooting", src, target, level)).isEmpty(), "9/10. No loot in a conquest raid");
            raidManager("endRaid", src, target, level);
            h.assertTrue(d.wins(d.campaignFor(k.id(), id(target))) == 1 && t.id().equals(d.owner(id(target))), "10. 1/2 after the first win");
            h.assertTrue(d.lastRaidDay(id(target)) == day && RaidOrderService.cooldownLeft(d, id(target), ON) == 3, "13. Three-day cooldown recorded");
            h.assertTrue(AutonomyService.decideCampaign(level, d, k, day, yes(), ON) == null, "13. The cooldown blocks a second raid on the target");
            d.startDay(day + 3, Map.of());
            var second = AutonomyService.decideCampaign(level, d, k, day + 3, yes(), ON);
            h.assertTrue(second != null && RaidOrderService.attempt(level, d, second, ON).status() == RaidOrderService.Status.STARTED, "After 3 days the next raid starts");
            raidManager("applyLooting", src, target, level); raidManager("endRaid", src, target, level);
            h.assertTrue(k.id().equals(d.owner(id(target))) && owner.equals(target.getOwnerUUID()), "10/20. 2/2 transfers allegiance; Millénaire owner kept");
            h.assertTrue(d.warClock(war.id()).lastProgressDay() == day + 3, "The war clock records the progress");
            h.assertTrue(d.capitalOpen(d.war(war.id())) && AutonomyService.warTargets(d, d.war(war.id())).equals(java.util.List.of(id(tc))),
                    "11. Holding 1 of 1 opens the capital as the next target");
            d.startDay(day + 6, Map.of());
            var capitalOrder = AutonomyService.decideCampaign(level, d, k, day + 6, yes(), ON);
            h.assertTrue(capitalOrder != null && capitalOrder.target().equals(id(tc)), "The capital is targeted only now");
            for (int i = 0; i < 2; i++) {
                if (i == 1) { d.startDay(day + 9, Map.of()); capitalOrder = AutonomyService.decideCampaign(level, d, k, day + 9, yes(), ON); }
                h.assertTrue(RaidOrderService.attempt(level, d, capitalOrder, ON).status() == RaidOrderService.Status.STARTED, "Capital raid " + (i + 1));
                raidManager("applyLooting", src, tc, level); raidManager("endRaid", src, tc, level);
            }
            h.assertTrue(d.kingdom(t.id()).fallen() && d.war(war.id()).status() == WarStatus.CONQUERED && k.id().equals(d.owner(id(tc))),
                    "11. Capital fell to two real raids after the 60% rule");
            h.succeed();
        } finally { remove(h, kc, src, tc, target); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void failedRaidGivesNothingAndIndependentFallsToOne(GameTestHelper h) throws Exception {
        var kc = village(h, 1, 1, "Independent attacker"); var free = village(h, 5, 5, "Hostile free village"); var other = village(h, 1, 5, "Hostile second");
        try {
            var level = h.getLevel(); var d = KingdomSavedData.get(level);
            var k = kingdom(d, kc); raider(kc);
            relation(kc, free, -90); relation(kc, other, -90);
            var settings = ON;
            var order = AutonomyService.decideCampaign(level, d, k, Math.max(0, d.day()), yes(), settings);
            h.assertTrue(order != null && order.warId() == null, "At peace, a hostile independent village is a conquest target (no war)");
            UUID first = order.target(); Village target = first.equals(id(free)) ? free : other;
            h.assertTrue(RaidOrderService.attempt(level, d, order, settings).status() == RaidOrderService.Status.STARTED, "Real raid starts");
            target.getVillagerRecords().values().forEach(r -> r.setKilled(true));
            battleStatus(target, level);
            h.assertTrue(d.owner(first) == null && d.lastResult(first).outcome() == Outcome.LOST, "8. A failed raid transfers nothing");
            raider(kc); // Millénaire marked the fallen raider dead; a new one has grown up.
            Village next = target == free ? other : free;
            var second = AutonomyService.decideCampaign(level, d, k, Math.max(0, d.day()), yes(), settings);
            h.assertTrue(second != null && second.target().equals(id(next)), "The cooldown sends the next order elsewhere");
            h.assertTrue(RaidOrderService.attempt(level, d, second, settings).status() == RaidOrderService.Status.STARTED, "Second raid starts");
            raidManager("applyLooting", kc, next, level); raidManager("endRaid", kc, next, level);
            h.assertTrue(k.id().equals(d.owner(id(next))) && d.lastResult(id(next)).transferred(), "9. One real victory conquers an independent village");
            var owned = village(h, 3, 3, "Player free village"); owned.setOwner(UUID.randomUUID(), "Pat"); relation(kc, owned, -100);
            h.assertTrue(!AutonomyService.hostileIndependents(level, d, k, settings).contains(id(owned)), "NPC kingdoms never attack an independent player village");
            remove(h, owned);
            h.succeed();
        } finally { remove(h, kc, free, other); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void ordersWaitForUnloadedVillagesAndExpire(GameTestHelper h) throws Exception {
        var kc = village(h, 1, 1, "Waiting attacker"); var far = farVillage(h, "Far away village");
        try {
            var level = h.getLevel(); var d = KingdomSavedData.get(level);
            var k = kingdom(d, kc); raider(kc);
            var before = millenaire(h);
            long now = level.getGameTime();
            var order = d.addOrder(k.id(), id(far), null, null, now, now, now + 1);
            var attempt = RaidOrderService.attempt(level, d, order, ON);
            h.assertTrue(attempt.status() == RaidOrderService.Status.WAITING && attempt.reason().contains("not loaded") && kc.getRaidTarget() == null
                    && before.equals(millenaire(h)) && !level.getChunkSource().hasChunk(far.getCenter().getX() >> 4, far.getCenter().getZ() >> 4),
                    "An unloaded target defers the raid without any Millénaire change, fake battle or chunk ticket: " + attempt.reason());
            h.succeedWhen(() -> {
                RaidOrderService.process(level);
                h.assertTrue(d.order(order.id()) == null && d.raid(id(far)) == null, "An order that cannot start expires instead of retrying forever");
                remove(h, kc, far);
            });
        } catch (RuntimeException e) { remove(h, kc, far); throw e; }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void waitingOrdersAreRevalidated(GameTestHelper h) throws Exception {
        var a = village(h, 1, 1, "Valid A"); var b = village(h, 5, 5, "Valid B"); var free = village(h, 1, 5, "Valid free");
        var member = village(h, 5, 1, "Valid member"); var source = village(h, 3, 3, "Valid source");
        try {
            var d = new KingdomSavedData(); var ka = kingdom(d, a); var kb = kingdom(d, b);
            d.attach(kb.id(), id(member)); d.attach(ka.id(), id(source));
            var independent = d.addOrder(ka.id(), id(free), null, null, 1, 1, 100);
            d.attach(kb.id(), id(free));
            h.assertTrue(d.order(independent.id()) == null, "14. An independent target that joined another kingdom drops the order");
            var war = d.declareWar(ka.id(), kb.id(), 1);
            var warOrder = d.addOrder(ka.id(), id(member), id(source), null, 1, 1, 100);
            h.assertTrue(warOrder.warId().equals(war.id()), "Order bound to the war");
            rejected(h, () -> d.addOrder(ka.id(), id(b), null, null, 1, 1, 100)); // One order per kingdom.
            d.peace(war.id(), KingdomSavedData.NPC_PEACE_REASON, 0);
            h.assertTrue(d.order(warOrder.id()) == null, "14. Peace drops the war's orders");
            rejected(h, () -> d.addOrder(ka.id(), id(member), null, null, 1, 1, 100)); // No war any more: rival village refused.
            // The raiding village changes hands: the order that named it is dropped.
            var free2 = village(h, 3, 1, "Valid free two"); var l = village(h, 3, 5, "Valid L"); var kl = kingdom(d, l);
            var sourced = d.addOrder(ka.id(), id(free2), id(source), null, 1, 1, 100);
            d.declareWar(kl.id(), ka.id(), 3);
            for (int i = 0; i < 2; i++) { var raid = d.begin(id(l), id(source), 1, null); d.recordVictory(raid); d.finish(raid, Outcome.LOST); }
            h.assertTrue(kl.id().equals(d.owner(id(source))) && d.order(sourced.id()) == null, "14. A source that left the kingdom drops the order");
            remove(h, free2, l);
            h.succeed();
        } finally { remove(h, a, b, free, member, source); }
    }

    // ---------------------------------------------------------------- D: player conquest orders

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void rulerOrdersConquestRaidsWithRealChecks(GameTestHelper h) throws Exception {
        var kc = village(h, 1, 1, "Ruler capital D"); var free = village(h, 5, 5, "Free target D");
        var ec = village(h, 1, 5, "Enemy capital D"); var em = village(h, 5, 1, "Enemy member D"); var em2 = village(h, 3, 3, "Enemy member D2");
        try {
            var level = h.getLevel(); var d = KingdomSavedData.get(level);
            var k = kingdom(d, kc); var e = kingdom(d, ec); d.attach(e.id(), id(em)); d.attach(e.id(), id(em2));
            var ruler = Stage5Support.player(h, "Ruth", kc); Stage5Support.reputation(h, kc, ruler.getUUID(), 40000);
            Stage5Support.run(ruler, "kingdom claim");
            var raiderRecord = raider(kc);
            var stranger = Stage5Support.player(h, "Sam", kc); Stage5Support.reputation(h, kc, stranger.getUUID(), 100000);
            h.assertTrue(Stage5Support.run(stranger, "kingdom raid " + id(free)) == 0 && kc.getRaidTarget() == null, "16. A non-ruler cannot order raids");
            raiderRecord.setKilled(true);
            var before = millenaire(h);
            h.assertTrue(Stage5Support.run(ruler, "kingdom raid " + id(free)) == 0 && before.equals(millenaire(h)), "7. No ready raider: refused, no side effect");
            raiderRecord.setKilled(false);
            h.assertTrue(Stage5Support.run(ruler, "kingdom raid " + id(free)) == 1 && d.raid(id(free)) != null, "12. Independent village: no war needed");
            raidManager("applyLooting", kc, free, level); raidManager("endRaid", kc, free, level);
            h.assertTrue(k.id().equals(d.owner(id(free))), "9. One victory conquers it");
            before = millenaire(h);
            h.assertTrue(Stage5Support.run(ruler, "kingdom raid " + id(em)) == 0 && before.equals(millenaire(h)), "Rival member without war: refused, no side effect");
            h.assertTrue(Stage5Support.run(ruler, "kingdom war declare " + e.id()) == 1, "War declared");
            h.assertTrue(Stage5Support.run(ruler, "kingdom raid " + id(ec)) == 0, "11. Capital protected below 60%");
            h.assertTrue(Stage5Support.run(ruler, "kingdom raid " + id(em) + " " + id(em2)) == 0, "The source must be the ruler's own village");
            h.assertTrue(Stage5Support.run(ruler, "kingdom raid " + id(em)) == 1 && d.raid(id(em)) != null, "12. War target accepted");
            ruler.messages.clear();
            h.assertTrue(Stage5Support.run(ruler, "kingdom raid status") == 1 && ruler.text().contains("Raid under way") && ruler.text().contains("0/2"),
                    "The ruler sees the raid and its progress: " + ruler.text());
            h.assertTrue(Stage5Support.run(ruler, "kingdom raid " + id(em2)) == 0, "13. One raid at a time per kingdom");
            raidManager("applyLooting", kc, em, level); raidManager("endRaid", kc, em, level);
            h.assertTrue(d.wins(d.campaignFor(k.id(), id(em))) == 1, "1/2");
            h.assertTrue(Stage5Support.run(ruler, "kingdom raid " + id(em)) == 0 && RaidOrderService.cooldownLeft(d, id(em), ON) > 0, "13. Cooldown on the same target");
            h.assertTrue(Stage5Support.run(ruler, "kingdom raid " + id(em2)) == 1, "Another target is allowed at once");
            raidManager("applyLooting", kc, em2, level); raidManager("endRaid", kc, em2, level);
            d.peace(d.activeWar(k.id()).id());
            d.clearRuler(k.id());
            h.succeed();
        } finally { remove(h, kc, free, ec, em, em2); }
    }

    /** Audit H2/M2: a waiting order lapses with the authority that gave it. */
    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void ordersLapseWithTheAuthorityThatGaveThem(GameTestHelper h) throws Exception {
        var kc = village(h, 1, 1, "Authority capital"); var free = village(h, 5, 5, "Authority free"); var other = village(h, 1, 5, "Authority other");
        try {
            var level = h.getLevel(); var d = KingdomSavedData.get(level);
            var k = kingdom(d, kc); raider(kc);
            long now = level.getGameTime();
            var npcOrder = d.addOrder(k.id(), id(free), null, null, now, now, now + 1000);
            var heir = Stage5Support.player(h, "Heir", kc); Stage5Support.reputation(h, kc, heir.getUUID(), 40000);
            Stage5Support.run(heir, "kingdom claim");
            var before = millenaire(h);
            var a = RaidOrderService.attempt(level, d, npcOrder, ON);
            h.assertTrue(a.status() == RaidOrderService.Status.DROPPED && before.equals(millenaire(h)), "H2. An NPC order never starts once a ruler took over");
            d.removeOrder(npcOrder.id());
            var rulerOrder = d.addOrder(k.id(), id(free), null, heir.getUUID(), now, now, now + 1000);
            Stage5Support.reputation(h, kc, heir.getUUID(), 8191);
            h.assertTrue(RaidOrderService.attempt(level, d, rulerOrder, ON).status() == RaidOrderService.Status.WAITING, "M2. Low reputation: the order waits");
            Stage5Support.reputation(h, kc, heir.getUUID(), 40000);
            Stage5Support.run(heir, "kingdom abdicate");
            h.assertTrue(RaidOrderService.attempt(level, d, rulerOrder, ON).status() == RaidOrderService.Status.DROPPED && before.equals(millenaire(h)),
                    "H2. A ruler's order lapses when that ruler no longer rules");
            d.removeOrder(rulerOrder.id());
            h.succeed();
        } finally { remove(h, kc, free, other); }
    }

    // ---------------------------------------------------------------- E: peace and truces

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void npcPeaceAfterTwelveDaysKeepsConquests(GameTestHelper h) throws Exception {
        var a = village(h, 1, 1, "Peace A"); var b = village(h, 5, 5, "Peace B"); var m = village(h, 1, 5, "Peace member"); var r = village(h, 5, 1, "Peace ruled");
        try {
            var level = h.getLevel(); var d = new KingdomSavedData();
            var ka = kingdom(d, a); var kb = kingdom(d, b); var kr = kingdom(d, r); d.attach(kb.id(), id(m)); raider(a);
            d.startDay(20, Map.of());
            var war = d.declareWar(ka.id(), kb.id(), 1);
            h.assertTrue(d.warClock(war.id()).startDay() == 20, "The war clock starts on the kingdom day");
            for (int i = 0; i < 2; i++) { var raid = d.begin(id(a), id(m), 1, null); d.recordVictory(raid); d.finish(raid, Outcome.LOST); }
            h.assertTrue(ka.id().equals(d.owner(id(m))) && d.conquered(war.id()) == 1, "A village was taken");
            relation(a, b, -90);
            h.assertTrue(AutonomyService.decidePeace(level, d, war, 31, yes(), ON) == PeaceDecision.NONE, "14. No NPC peace before day 12 of the war");
            h.assertTrue(AutonomyService.peaceMotive(level, d, war, 32, ON) != null, "From day 12 a limited success is a reason");
            h.assertTrue(AutonomyService.decidePeace(level, d, war, 32, no(), ON) == PeaceDecision.NONE, "Peace is a chance, not automatic");
            int before = a.getRelation(b.getId());
            h.assertTrue(AutonomyService.decidePeace(level, d, war, 32, yes(), ON) == PeaceDecision.MADE, "14. NPC attacker makes peace");
            h.assertTrue(d.war(war.id()).status() == WarStatus.PEACE && d.war(war.id()).endReason().equals(KingdomSavedData.NPC_PEACE_REASON)
                    && ka.id().equals(d.owner(id(m))) && d.truceUntil(ka.id(), kb.id()) == 20 + 10, "Conquest kept; truce from the ledger day");
            h.assertTrue(a.getRelation(b.getId()) == before + DiplomacyService.PEACE_TREATY, "The treaty improves relations once");
            // A ruled attacker is never made to make peace: an NPC defender only offers it.
            d.startDay(31, Map.of());
            d.claimRule(kr.id(), UUID.randomUUID(), "Ruler", 1);
            var ruled = d.declareWar(kr.id(), kb.id(), 2);
            h.assertTrue(AutonomyService.decidePeace(level, d, ruled, 31 + 40, yes(), ON) == PeaceDecision.OFFERED
                    && d.war(ruled.id()).status() == WarStatus.ACTIVE && d.peaceOffered(ruled.id()), "3. Only an offer to the ruler");
            h.assertTrue(AutonomyService.decidePeace(level, d, ruled, 31 + 40, yes(), ON) == PeaceDecision.NONE, "The offer is made once");
            h.succeed();
        } finally { remove(h, a, b, m, r); }
    }

    /** Political evaluations run on stored days only, once per interval, never twice for a skipped stretch. */
    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void evaluationsAreSparseAndScheduled(GameTestHelper h) throws Exception {
        var a = village(h, 1, 1, "Schedule A");
        try {
            var d = new KingdomSavedData(); kingdom(d, a);
            d.startDay(0, Map.of());
            KingdomDay.run(h.getLevel(), d, 1, ON);
            h.assertTrue(d.nextDecisionDay() == 4, "First evaluation scheduled three days ahead");
            KingdomDay.run(h.getLevel(), d, 2, ON); KingdomDay.run(h.getLevel(), d, 3, ON);
            h.assertTrue(d.nextDecisionDay() == 4, "Not before");
            KingdomDay.run(h.getLevel(), d, 4, ON);
            h.assertTrue(d.nextDecisionDay() == 7, "Evaluated on day 4, next on day 7");
            KingdomDay.run(h.getLevel(), d, 40, ON);
            h.assertTrue(d.nextDecisionDay() == 43, "A skipped stretch evaluates once");
            var off = ON.with(false, true, true);
            KingdomDay.run(h.getLevel(), d, 50, off);
            h.assertTrue(d.nextDecisionDay() == 43, "Autonomy off: no evaluation");
            h.succeed();
        } finally { remove(h, a); }
    }

    // ---------------------------------------------------------------- calendar simulation (package I)

    /** 300 Minecraft days by calendar jump: Millénaire's own nightly diplomacy runs for every village each day, then the
     * addon's day (income, drift, border tension, decisions every 3 days). Real battles are NOT simulated: conquest raids are
     * off here (campaigns=false), because a victory may only come from a real Millénaire raid; the real-tick tests cover them.
     * Measures joins, wars, peaces, war lengths, time at war and the largest kingdom. */
    @GameTest(templateNamespace = NS, template = "empty", batch = "calendar", timeoutTicks = 2400)
    public static void politicalCalendarSimulation300Days(GameTestHelper h) throws Exception {
        var level = h.getLevel(); var types = raidingTypes();
        int cultures = Math.min(3, types.size());
        h.assertTrue(cultures >= 2, "At least two cultures");
        var settings = ON.with(true, true, false);
        var d = new KingdomSavedData();
        var villages = new java.util.ArrayList<Village>();
        var kingdoms = new java.util.ArrayList<KingdomSavedData.Kingdom>();
        int slot = 0;
        try {
            for (int k = 0; k < 6; k++) {
                var type = types.get(k % cultures);
                var capital = village(h, slot % 8, slot / 8, "Sim capital " + k, type); slot++;
                villages.add(capital);
                var kingdom = kingdom(d, capital); kingdoms.add(kingdom);
                for (int m = 0; m < 2; m++) {
                    var member = village(h, slot % 8, slot / 8, "Sim member " + k + "." + m, type); slot++;
                    villages.add(member); d.attach(kingdom.id(), id(member));
                }
            }
            for (int i = 0; i < 12; i++) { villages.add(village(h, slot % 8, slot / 8, "Sim free " + i, types.get(i % cultures))); slot++; }
            for (Village v : villages) {
                for (int r = 0; r < 2; r++) raider(v);
                defender(v);
            }
            // Millénaire's initial relations (VillageSpawner.initializeRelations): same culture +50, different -30.
            for (int i = 0; i < villages.size(); i++) for (int j = i + 1; j < villages.size(); j++) {
                Village a = villages.get(i), b = villages.get(j);
                relation(a, b, a.getCultureId().equals(b.getCultureId()) ? 50 : -30);
            }
            int startMembers = d.kingdoms().stream().mapToInt(k -> d.members(k.id()).size()).sum();
            d.startDay(0, Map.of());
            long nanos = 0; int maxActive = 0, warDays = 0, maxWar = 0, longestWar = 0, maxShare = 0, kingdomWarDays = 0;
            var started = new java.util.HashMap<UUID, Long>(); var ended = new java.util.HashSet<UUID>();
            var lengths = new java.util.ArrayList<Long>();
            for (long day = 1; day <= 300; day++) {
                for (Village v : villages) org.millenaire.village.VillageDiplomacyHelper.performNightlyDiplomacyDrift(level, v);
                long t0 = System.nanoTime();
                KingdomDay.run(level, d, day, settings);
                nanos += System.nanoTime() - t0;
                int active = 0;
                for (var w : d.wars()) {
                    if (w.status() == WarStatus.ACTIVE) { active++; started.putIfAbsent(w.id(), day); }
                    else if (started.containsKey(w.id()) && ended.add(w.id())) {
                        long len = day - started.get(w.id()); lengths.add(len); longestWar = (int) Math.max(longestWar, len);
                    }
                }
                maxActive = Math.max(maxActive, active); if (active > 0) warDays++; kingdomWarDays += 2 * active;
                for (var k : kingdoms) maxShare = Math.max(maxShare, d.members(k.id()).size());
            }
            long wars = d.wars().size(), peaces = d.wars().stream().filter(w -> w.status() == WarStatus.PEACE).count();
            long stillActive = d.wars().stream().filter(w -> w.status() == WarStatus.ACTIVE).count();
            int endMembers = d.kingdoms().stream().mapToInt(k -> d.members(k.id()).size()).sum();
            int joined = endMembers - startMembers;
            for (var w : d.wars()) if (w.status() == WarStatus.ACTIVE) longestWar = (int) Math.max(longestWar, 300 - started.get(w.id()));
            var attacks = new java.util.HashMap<UUID, Integer>();
            d.wars().forEach(w -> attacks.merge(w.attackerKingdom(), 1, Integer::sum));
            String summary = "kingdoms=" + kingdoms.size() + " villages=" + villages.size() + " joined=" + joined + " wars=" + wars + " peaces=" + peaces
                    + " active_at_end=" + stillActive + " longest_war_days=" + longestWar + " mean_war_days="
                    + (lengths.isEmpty() ? 0 : lengths.stream().mapToLong(Long::longValue).sum() / lengths.size())
                    + " days_with_war=" + warDays + "/300 kingdom_time_at_war=" + (100 * kingdomWarDays / (kingdoms.size() * 300)) + "% max_simultaneous_wars=" + maxActive + " largest_kingdom=" + maxShare + "/" + villages.size()
                    + " wars_declared_per_kingdom=" + attacks.values() + " fallen=0 (no simulated battles) avg_day_ms=" + String.format(java.util.Locale.ROOT, "%.3f", nanos / 300 / 1e6);
            System.out.println("AUTONOMY_SIM " + summary);
            h.assertTrue(wars >= 1, "NPC kingdoms are not passive: " + summary);
            h.assertTrue(joined >= 1, "Some independent villages joined on their own: " + summary);
            h.assertTrue(longestWar <= 60, "No war lasts for ever: " + summary);
            h.assertTrue(maxShare * 10 <= villages.size() * 6, "No kingdom swallows the world by diplomacy alone: " + summary);
            System.out.println("AUTONOMY_SIM_PASS");
            h.succeed();
        } finally { villages.forEach(v -> remove(h, v)); }
    }

    // ---------------------------------------------------------------- restart

    /** 15. Write round: an order, a truce, a cooldown, a war clock, an NPC application and a decision day.
     * Read round (separate JVM): all kept exactly; nothing ran twice. */
    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void autonomySurvivesServerRestart(GameTestHelper h) {
        var d = KingdomSavedData.get(h.getLevel());
        UUID ca = UUID.fromString("62000000-0000-4000-8000-000000000001"), cb = UUID.fromString("62000000-0000-4000-8000-000000000002");
        UUID cc = UUID.fromString("62000000-0000-4000-8000-000000000003"), free = UUID.fromString("62000000-0000-4000-8000-000000000004");
        UUID npc = UUID.fromString("62000000-0000-4000-8000-000000000005"), member = UUID.fromString("62000000-0000-4000-8000-000000000006");
        if (read()) {
            var ka = d.owner(ca); var kb = d.owner(cb); var kc = d.owner(cc);
            var war = d.activeWar(ka);
            var order = d.orderOf(kc);
            h.assertTrue(war != null && war.defenderKingdom().equals(kb) && d.warClock(war.id()).startDay() == 500, "War clock kept");
            h.assertTrue(order != null && order.target().equals(free) && order.byNpc() && order.dueGameTime() == Long.MAX_VALUE / 4, "Order kept, not started");
            h.assertTrue(d.raid(free) == null, "The order did not start a raid on restart");
            h.assertTrue(d.truceUntil(kb, kc) == 900, "Truce kept");
            h.assertTrue(d.lastRaidDay(member) == 500, "Cooldown kept");
            h.assertTrue(d.joinRequest(npc) != null && d.joinRequest(npc).fromNpcVillage(), "NPC application kept");
            h.assertTrue(d.nextDecisionDay() == 777, "Next decision day kept");
            System.out.println("AUTONOMY_RESTART_READ_PASS: war clock, order, truce, cooldown, NPC application and decision day retained");
        } else {
            if (d.owner(ca) == null) {
                d.startDay(500, Map.of());
                var ka = d.create("Restart A6", ca); var kb = d.create("Restart B6", cb); var kc = d.create("Restart C6", cc);
                d.attach(kb.id(), member);
                var first = d.declareWar(kb.id(), kc.id(), 1);
                d.peace(first.id(), KingdomSavedData.NPC_PEACE_REASON, 400);
                d.declareWar(ka.id(), kb.id(), 2);
                var raid = d.begin(ca, member, 3, null); d.finish(raid, Outcome.LOST);
                d.addOrder(kc.id(), free, null, null, 4, Long.MAX_VALUE / 4, Long.MAX_VALUE / 2);
                d.requestJoin(npc, kb.id(), null, "", 5);
                d.scheduleDecision(777);
            }
            d.setDirty();
            System.out.println("AUTONOMY_RESTART_WRITE: read assertions run in the next JVM");
        }
        h.succeed();
    }
}
