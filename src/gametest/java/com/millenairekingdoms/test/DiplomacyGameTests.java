package com.millenairekingdoms.test;

import static com.millenairekingdoms.test.Stage5Support.*;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Outcome;
import com.millenairekingdoms.politics.DiplomacyService;
import com.millenairekingdoms.politics.KingdomDay;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingInstance.Status;
import org.millenaire.village.Village;

/** Stage 5 work package 3: daily relation drift on Millénaire's capital relations, peaceful joining of independent
 * NPC and player villages, founding a kingdom from a developed own village, and local rights surviving conquest. */
@PrefixGameTestTemplate(false)
public final class DiplomacyGameTests {

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void relationsDriftRarelySmallAndBoundedWithoutWar(GameTestHelper h) throws Exception {
        var a = village(h, 1, 1, "Drift A"); var b = village(h, 5, 1, "Drift B"); var c = village(h, 1, 5, "Drift C"); var e = village(h, 5, 5, "Drift E");
        try {
            var d = new KingdomSavedData();
            var ka = d.create("Drift A " + UUID.randomUUID(), EconomyGameTests.id(a)); var kb = d.create("Drift B " + UUID.randomUUID(), EconomyGameTests.id(b));
            var kc = d.create("Drift C " + UUID.randomUUID(), EconomyGameTests.id(c)); var ke = d.create("Drift E " + UUID.randomUUID(), EconomyGameTests.id(e));
            relation(a, b, 50); relation(a, c, -50); relation(b, c, 0); relation(a, e, 80); relation(b, e, -20); relation(c, e, 10);
            var war = d.declareWar(kb.id(), ke.id(), 1);
            var pairs = new Village[][]{{a, b}, {a, c}, {b, c}, {a, e}, {b, e}, {c, e}};
            int[] previous = new int[pairs.length]; int changes = 0; boolean up = false, down = false;
            for (int i = 0; i < pairs.length; i++) previous[i] = pairs[i][0].getRelation(pairs[i][1].getId());
            RandomSource random = RandomSource.create(42);
            for (int day = 0; day < 400; day++) {
                DiplomacyService.drift(h.getLevel(), d, random);
                for (int i = 0; i < pairs.length; i++) {
                    int now = pairs[i][0].getRelation(pairs[i][1].getId());
                    h.assertTrue(now == pairs[i][1].getRelation(pairs[i][0].getId()), "Relations stay symmetric");
                    h.assertTrue(Math.abs(now - previous[i]) <= 1, "At most one point per pair per day");
                    h.assertTrue(now >= -100 && now <= 100 && (Math.abs(now) <= 50 || Math.abs(now) <= Math.abs(previous[i])),
                            "Drift never pushes past ±50 (Millénaire raids only below -90)");
                    if (i == 4) h.assertTrue(now <= previous[i], "Kingdoms at war only drift apart");
                    if (now != previous[i]) { changes++; if (i == 2) { up |= now > previous[i]; down |= now < previous[i]; } }
                    previous[i] = now;
                }
            }
            h.assertTrue(changes > 100 && changes < 1200, "About one change per pair in four days: " + changes);
            h.assertTrue(up && down, "Peaceful relations move both ways");
            h.assertTrue(a.getRelation(e.getId()) <= 80, "A relation beyond 50 only moves inward");
            h.assertTrue(d.wars().size() == 1 && d.activeWar(ka.id()) == null && d.activeWar(kc.id()) == null && d.war(war.id()) != null,
                    "Relation changes alone start no war");
            h.succeed();
        } finally { remove(h, a, b, c, e); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void dailyPoliticsRunOncePerDay(GameTestHelper h) throws Exception {
        var a = village(h, 1, 1, "Daily A"); var b = village(h, 5, 5, "Daily B");
        try {
            var d = new KingdomSavedData(); d.create("Daily A " + UUID.randomUUID(), EconomyGameTests.id(a)); d.create("Daily B " + UUID.randomUUID(), EconomyGameTests.id(b));
            relation(a, b, 10); d.startDay(3, Map.of());
            int moved = 0;
            for (long day = 4; day < 60; day++) {
                int before = a.getRelation(b.getId());
                h.assertTrue(KingdomDay.run(h.getLevel(), d, day), "A new day runs");
                int after = a.getRelation(b.getId());
                h.assertTrue(!KingdomDay.run(h.getLevel(), d, day) && a.getRelation(b.getId()) == after, "Repeating a day changes nothing");
                h.assertTrue(Math.abs(after - before) <= 1, "Small change");
                if (after != before) moved++;
            }
            h.assertTrue(moved > 0 && moved < 56, "Changes are occasional, not daily: " + moved);
            h.succeed();
        } finally { remove(h, a, b); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void ownVillageJoinsNpcKingdomOnRelationAndReputation(GameTestHelper h) throws Exception {
        var capital = village(h, 1, 1, "NPC join capital"); var own = village(h, 5, 5, "Owned village"); var second = village(h, 3, 1, "Second capital");
        try {
            var data = KingdomSavedData.get(h.getLevel()); var k = kingdom(h, capital);
            var olga = player(h, "Olga", own); var pete = player(h, "Pete", own);
            own.setOwner(olga.getUUID(), "Olga");
            reputation(h, capital, olga.getUUID(), 8192); reputation(h, capital, pete.getUUID(), 100000);
            relation(capital, own, 100);
            h.assertTrue(run(pete, "kingdom join " + k.id()) == 0 && data.owner(EconomyGameTests.id(own)) == null,
                    "Another player cannot attach a village that is not theirs");
            relation(capital, own, 49);
            h.assertTrue(run(olga, "kingdom join " + k.id()) == 0 && data.owner(EconomyGameTests.id(own)) == null, "Relation 49 is not good enough");
            relation(capital, own, 50); reputation(h, capital, olga.getUUID(), 8191);
            h.assertTrue(run(olga, "kingdom join " + k.id()) == 0, "The NPC leader needs reputation 8192 from the owner");
            reputation(h, capital, olga.getUUID(), 8192);
            h.assertTrue(run(olga, "kingdom join " + k.name()) == 1 && k.id().equals(data.owner(EconomyGameTests.id(own))), "Owner joins at 50 and 8192");
            h.assertTrue(olga.getUUID().equals(own.getOwnerUUID()) && own.getParentVillageId() == null && "Owned village".equals(own.getVillageName()),
                    "Joining keeps Millénaire's owner, parent and name");
            var l = kingdom(h, second);
            h.assertTrue(run(olga, "kingdom join " + l.id()) == 0 && k.id().equals(data.owner(EconomyGameTests.id(own))),
                    "A member village cannot join a second kingdom");
            h.succeed();
        } finally { remove(h, capital, own, second); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void ruledKingdomDecidesApplications(GameTestHelper h) throws Exception {
        var capital = village(h, 1, 1, "Ruled join capital"); var own = village(h, 5, 5, "Applicant village"); var other = village(h, 5, 1, "Refused village");
        try {
            var data = KingdomSavedData.get(h.getLevel()); var k = kingdom(h, capital);
            var rex = player(h, "Rex", capital); reputation(h, capital, rex.getUUID(), 40000); run(rex, "kingdom claim");
            var olga = player(h, "Olga", own); own.setOwner(olga.getUUID(), "Olga"); other.setOwner(olga.getUUID(), "Olga");
            relation(capital, own, -60); // The ruler, not the relation, decides an owner's application.
            h.assertTrue(run(olga, "kingdom join " + k.id()) == 1 && data.owner(EconomyGameTests.id(own)) == null
                    && data.joinRequest(EconomyGameTests.id(own)) != null, "An owner's application waits for the ruler");
            var mallory = player(h, "Mallory", capital); reputation(h, capital, mallory.getUUID(), 100000);
            h.assertTrue(run(mallory, "kingdom accept " + EconomyGameTests.id(own)) == 0 && data.owner(EconomyGameTests.id(own)) == null,
                    "Only the ruler accepts");
            h.assertTrue(run(rex, "kingdom info") == 1 && hasButton(rex.messages, "/kingdom accept " + EconomyGameTests.id(own)), "The ruler sees the application");
            h.assertTrue(run(rex, "kingdom accept " + EconomyGameTests.id(own)) == 1 && k.id().equals(data.owner(EconomyGameTests.id(own)))
                    && data.joinRequest(EconomyGameTests.id(own)) == null && olga.getUUID().equals(own.getOwnerUUID()), "Accepted; owner unchanged");
            moveTo(olga, other, 0);
            run(olga, "kingdom join " + k.id());
            h.assertTrue(run(rex, "kingdom refuse " + EconomyGameTests.id(other)) == 1 && data.joinRequest(EconomyGameTests.id(other)) == null
                    && data.owner(EconomyGameTests.id(other)) == null, "Refused application leaves the village independent");
            h.succeed();
        } finally { remove(h, capital, own, other); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void npcVillagesJoinOnlyWithGoodRelations(GameTestHelper h) throws Exception {
        var npcCapital = village(h, 1, 1, "NPC kingdom capital"); var ruledCapital = village(h, 5, 1, "Ruled kingdom capital");
        var first = village(h, 1, 5, "NPC hamlet one"); var second = village(h, 5, 5, "NPC hamlet two");
        try {
            var data = KingdomSavedData.get(h.getLevel()); var kn = kingdom(h, npcCapital); var kr = kingdom(h, ruledCapital);
            var rex = player(h, "Rex", ruledCapital); reputation(h, ruledCapital, rex.getUUID(), 40000); run(rex, "kingdom claim");
            var dan = player(h, "Dan", first); reputation(h, npcCapital, dan.getUUID(), 8192); reputation(h, ruledCapital, dan.getUUID(), 100000);
            relation(npcCapital, first, 49);
            h.assertTrue(run(dan, "kingdom join " + kn.id()) == 0, "NPC leader and village need relation 50");
            relation(npcCapital, first, 50);
            h.assertTrue(run(dan, "kingdom join " + kn.id()) == 1 && kn.id().equals(data.owner(EconomyGameTests.id(first))), "A diplomat brings in an NPC village at 50");
            h.assertTrue(run(dan, "kingdom join " + kr.id()) == 0, "Not twice");
            moveTo(dan, second, 0); moveTo(rex, second, 0); relation(ruledCapital, second, 50);
            h.assertTrue(run(dan, "kingdom join " + kr.id()) == 0 && data.joinRequest(EconomyGameTests.id(second)) == null,
                    "In a ruled kingdom only the ruler can bring in a village nobody owns");
            relation(ruledCapital, second, 49);
            h.assertTrue(run(rex, "kingdom join " + kr.id()) == 0, "The village itself refuses below 50");
            relation(ruledCapital, second, 50);
            h.assertTrue(run(rex, "kingdom join " + kr.id()) == 1 && kr.id().equals(data.owner(EconomyGameTests.id(second)))
                    && second.getOwnerUUID() == null, "The ruler's decision plus the village's consent");
            h.succeed();
        } finally { remove(h, npcCapital, ruledCapital, first, second); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void developedOwnVillageFoundsAKingdomRuledByItsOwner(GameTestHelper h) throws Exception {
        var small = village(h, 1, 1, "Small town", 1, Status.COMPLETE); var big = village(h, 5, 5, "Big town", 2, Status.COMPLETE);
        var npc = village(h, 1, 5, "NPC town", 6, Status.COMPLETE);
        try {
            var data = KingdomSavedData.get(h.getLevel());
            var olga = player(h, "Olga", small); small.setOwner(olga.getUUID(), "Olga"); big.setOwner(olga.getUUID(), "Olga");
            h.assertTrue(run(olga, "kingdom found Olga's Small Realm") == 0 && data.owner(EconomyGameTests.id(small)) == null, "Town hall level 2 is too small");
            var pete = player(h, "Pete", big);
            h.assertTrue(run(pete, "kingdom found Pete's Realm") == 0 && data.owner(EconomyGameTests.id(big)) == null, "Only the owner may found from it");
            moveTo(pete, npc, 0);
            h.assertTrue(run(pete, "kingdom found NPC Realm") == 0 && data.owner(EconomyGameTests.id(npc)) == null, "An NPC village cannot be founded upon");
            moveTo(olga, big, 0);
            String name = "Olga's Realm " + UUID.randomUUID();
            h.assertTrue(run(olga, "kingdom found " + name) == 1, "Level 3 town hall founds a kingdom");
            UUID k = data.owner(EconomyGameTests.id(big));
            h.assertTrue(k != null && data.isCapital(EconomyGameTests.id(big)) && data.kingdom(k).name().equals(name)
                    && olga.getUUID().equals(data.ruler(k).player()) && olga.getUUID().equals(big.getOwnerUUID()), "Founder rules; Millénaire owner kept");
            moveTo(olga, small, 0); small.getTownhall().startUpgrade(small.getTownhall().getPlanId(), 3); small.getTownhall().markComplete();
            h.assertTrue(run(olga, "kingdom found Second " + UUID.randomUUID()) == 0, "A ruler cannot found a second kingdom");
            h.succeed();
        } finally { remove(h, small, big, npc); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void conquestKeepsTheOwnersLocalRights(GameTestHelper h) throws Exception {
        var a = village(h, 1, 1, "Conqueror capital"); var b = village(h, 5, 5, "Owned capital", 2, Status.COMPLETE);
        var own = village(h, 5, 1, "Owned member");
        try {
            var data = KingdomSavedData.get(h.getLevel()); var ka = kingdom(h, a);
            var olga = player(h, "Olga", b); b.setOwner(olga.getUUID(), "Olga"); own.setOwner(olga.getUUID(), "Olga");
            reputation(h, b, olga.getUUID(), 20000); // Millénaire gives a village's founder 20000 reputation there.
            h.assertTrue(run(olga, "kingdom found Olga's Doomed Realm " + UUID.randomUUID()) == 1, "Founded");
            UUID kb = data.owner(EconomyGameTests.id(b));
            moveTo(olga, own, 0); run(olga, "kingdom join " + kb);
            h.assertTrue(kb.equals(data.owner(EconomyGameTests.id(own))), "Ruler brought her own village in");
            var war = data.declareWar(ka.id(), kb, 2);
            win(data, EconomyGameTests.id(a), EconomyGameTests.id(own)); win(data, EconomyGameTests.id(a), EconomyGameTests.id(own));
            win(data, EconomyGameTests.id(a), EconomyGameTests.id(b)); win(data, EconomyGameTests.id(a), EconomyGameTests.id(b));
            h.assertTrue(data.kingdom(kb).fallen() && ka.id().equals(data.owner(EconomyGameTests.id(own))) && ka.id().equals(data.owner(EconomyGameTests.id(b)))
                    && data.ruler(kb) == null && data.war(war.id()).status() == KingdomSavedData.WarStatus.CONQUERED, "Both villages conquered; her rule ended");
            h.assertTrue(own.isControlledBy(olga.getUUID()) && b.isControlledBy(olga.getUUID()) && "Olga".equals(b.getOwnerName()),
                    "Millénaire local control (owner UUID) is untouched by conquest");
            h.succeed();
        } finally { remove(h, a, b, own); }
    }

    private static void win(KingdomSavedData d, UUID attacker, UUID target) {
        var raid = d.begin(attacker, target, 1, null); d.recordVictory(raid); d.finish(raid, Outcome.LOST);
    }
}
