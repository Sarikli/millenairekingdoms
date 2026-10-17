package com.millenairekingdoms.test;

import static com.millenairekingdoms.test.Stage5Support.*;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.WarStatus;
import com.millenairekingdoms.politics.ChiefInteraction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.culture.ModCultures;
import org.millenaire.entity.ModEntities;

/** Stage 5 work package 1: player rulers, war and peace decisions, NPC war requests and the chief interaction,
 * all through the real /kingdom commands at permission 0 against real Millénaire villages and reputation. */
@PrefixGameTestTemplate(false)
public final class PoliticsGameTests {

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void claimNeedsReputationDistanceAndOneRuler(GameTestHelper h) throws Exception {
        var c = village(h, 1, 1, "Claim capital"); var d = village(h, 5, 5, "Other capital");
        try {
            var data = KingdomSavedData.get(h.getLevel()); var k = kingdom(h, c); var l = kingdom(h, d);
            var alice = player(h, "Alice", c); var bob = player(h, "Bob", c);
            reputation(h, c, alice.getUUID(), 32767);
            h.assertTrue(run(alice, "kingdom claim") == 0 && data.ruler(k.id()) == null, "Below 32768 the claim is refused");
            reputation(h, c, alice.getUUID(), 32768);
            moveTo(alice, c, 65);
            h.assertTrue(run(alice, "kingdom claim") == 0 && data.ruledBy(alice.getUUID()) == null, "More than 64 blocks from the capital is refused");
            moveTo(alice, c, 63);
            var owner = c.getOwnerUUID(); var relations = c.getRelations(); var reputation = c.getReputation().get(alice.getUUID());
            h.assertTrue(run(alice, "kingdom claim") == 1 && alice.getUUID().equals(data.ruler(k.id()).player()), "At 32768 within 64 blocks the claim succeeds");
            h.assertTrue(java.util.Objects.equals(owner, c.getOwnerUUID()) && c.getOwnerUUID() == null && relations.equals(c.getRelations())
                    && reputation == c.getReputation().get(alice.getUUID()), "Ruling changes no Millénaire ownership, relation or reputation");
            reputation(h, c, bob.getUUID(), 100000);
            h.assertTrue(run(bob, "kingdom claim") == 0 && alice.getUUID().equals(data.ruler(k.id()).player()), "A second player cannot take a ruled kingdom");
            moveTo(alice, d, 0); reputation(h, d, alice.getUUID(), 100000);
            h.assertTrue(run(alice, "kingdom claim") == 0 && data.ruler(l.id()) == null, "A ruler cannot claim a second kingdom");
            h.assertTrue(run(alice, "kingdom abdicate") == 1 && data.ruler(k.id()) == null, "Abdication frees the kingdom");
            h.assertTrue(run(bob, "kingdom claim") == 1 && bob.getUUID().equals(data.ruler(k.id()).player()), "Then the next eligible player may claim");
            h.assertTrue(run(alice, "kingdom abdicate") == 0, "Only a ruler can abdicate");
            h.succeed();
        } finally { remove(h, c, d); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void reputationLossBlocksDecisionsButKeepsRule(GameTestHelper h) throws Exception {
        var c = village(h, 1, 1, "Ruled capital"); var d = village(h, 5, 5, "Target capital");
        try {
            var data = KingdomSavedData.get(h.getLevel()); var k = kingdom(h, c); var l = kingdom(h, d);
            var alice = player(h, "Alice", c);
            reputation(h, c, alice.getUUID(), 40000); run(alice, "kingdom claim");
            reputation(h, c, alice.getUUID(), 8191);
            h.assertTrue(run(alice, "kingdom war declare " + l.id()) == 0 && data.activeWar(k.id()) == null, "Below 8192 decisions are blocked");
            h.assertTrue(alice.getUUID().equals(data.ruler(k.id()).player()), "The ruler record is kept while reputation is low");
            reputation(h, c, alice.getUUID(), 8192);
            moveTo(alice, c, 500); // A ruler decides from anywhere; only the claim needs the capital.
            h.assertTrue(run(alice, "kingdom war declare " + l.name()) == 1, "Regained reputation restores decisions (target by name)");
            var war = data.activeWar(k.id());
            h.assertTrue(war != null && war.attackerKingdom().equals(k.id()) && war.defenderKingdom().equals(l.id()), "Ruler declared war on L");
            data.peace(war.id());
            h.succeed();
        } finally { remove(h, c, d); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void rulersMakeWarAndPeaceWithoutUnilateralDefenderPeace(GameTestHelper h) throws Exception {
        var c = village(h, 1, 1, "Attacker capital"); var d = village(h, 5, 5, "Defender capital"); var e = village(h, 1, 5, "NPC capital");
        try {
            var data = KingdomSavedData.get(h.getLevel()); var k = kingdom(h, c); var l = kingdom(h, d); var m = kingdom(h, e);
            var alice = player(h, "Alice", c); var bob = player(h, "Bob", d);
            reputation(h, c, alice.getUUID(), 40000); reputation(h, d, bob.getUUID(), 40000);
            run(alice, "kingdom claim"); run(bob, "kingdom claim");
            h.assertTrue(run(alice, "kingdom war declare " + l.id()) == 1, "Ruler A declares war");
            var war = data.activeWar(k.id());
            h.assertTrue(run(bob, "kingdom war peace") == 1 && data.war(war.id()).status() == WarStatus.ACTIVE && data.peaceOffered(war.id()),
                    "The defending ruler can only offer peace; the war continues");
            h.assertTrue(run(alice, "kingdom war peace") == 1 && data.war(war.id()).status() == WarStatus.PEACE
                    && data.war(war.id()).endReason().equals(KingdomSavedData.RULER_PEACE_REASON) && !data.peaceOffered(war.id()),
                    "The attacking ruler accepts (or simply makes) peace");
            // An NPC-governed attacker decides on a defending ruler's offer from Millénaire relations.
            relation(e, c, -40);
            var npcWar = data.declareWar(m.id(), k.id(), h.getLevel().getGameTime());
            h.assertTrue(run(alice, "kingdom war peace") == 0 && data.war(npcWar.id()).status() == WarStatus.ACTIVE, "NPC attacker refuses at -40");
            relation(e, c, -29);
            h.assertTrue(run(alice, "kingdom war peace") == 1 && data.war(npcWar.id()).status() == WarStatus.PEACE, "NPC attacker accepts above -30");
            h.assertTrue(run(alice, "kingdom war peace") == 0, "No war, no peace");
            h.succeed();
        } finally { remove(h, c, d, e); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void npcLeaderGrantsWarRequestsOnlyOnBadRelations(GameTestHelper h) throws Exception {
        var c = village(h, 1, 1, "Petitioned capital"); var d = village(h, 5, 5, "Hated capital");
        try {
            var data = KingdomSavedData.get(h.getLevel()); var k = kingdom(h, c); var l = kingdom(h, d);
            var carol = player(h, "Carol", c);
            reputation(h, c, carol.getUUID(), 8191); relation(c, d, -30);
            h.assertTrue(run(carol, "kingdom war request " + l.id()) == 0 && data.activeWar(k.id()) == null, "Below 8192 the leader does not listen");
            reputation(h, c, carol.getUUID(), 8192); relation(c, d, -29);
            h.assertTrue(run(carol, "kingdom war request " + l.id()) == 0 && data.activeWar(k.id()) == null, "Relations -29 are not hostile enough");
            moveTo(carol, c, 70); relation(c, d, -30);
            h.assertTrue(run(carol, "kingdom war request " + l.id()) == 0, "The request must be made at the capital");
            moveTo(carol, c, 0);
            int relationBefore = c.getRelation(d.getId());
            h.assertTrue(run(carol, "kingdom war request " + l.id()) == 1, "At 8192 and relations -30 the NPC leader declares war");
            var war = data.activeWar(k.id());
            h.assertTrue(war.attackerKingdom().equals(k.id()) && war.defenderKingdom().equals(l.id()) && data.ruler(k.id()) == null
                    && c.getRelation(d.getId()) == relationBefore, "War of the NPC-governed kingdom; Millénaire relations untouched");
            data.peace(war.id());
            var dave = player(h, "Dave", c); reputation(h, c, dave.getUUID(), 40000); run(dave, "kingdom claim");
            h.assertTrue(run(carol, "kingdom war request " + l.id()) == 0 && data.activeWar(k.id()) == null,
                    "A ruled kingdom's NPC leader cannot be asked: the ruler decides");
            h.succeed();
        } finally { remove(h, c, d); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void chiefRightClickKeepsMillenaireScreenAndOffersChatOptions(GameTestHelper h) throws Exception {
        var c = village(h, 1, 1, "Chief capital"); var d = village(h, 5, 5, "Enemy capital"); var e = village(h, 1, 5, "Member village");
        try {
            var data = KingdomSavedData.get(h.getLevel()); var k = kingdom(h, c); var l = kingdom(h, d);
            var chiefType = ModCultures.getAllVillagerTypes().values().stream().filter(t -> t.hasTag("chief")).findFirst().orElseThrow();
            var chief = ModEntities.MILL_VILLAGER.get().create(h.getLevel());
            chief.setVillageId(c.getId()); chief.setVillagerTypeId(chiefType.id()); chief.syncTypeDerivedFlags(chiefType);
            chief.moveTo(c.getCenter().getX() + 0.5, c.getCenter().getY() + 1, c.getCenter().getZ() + 0.5);
            h.assertTrue(ChiefInteraction.isChief(chief), "Fixture is a real Millénaire chief");
            var erin = player(h, "Erin", c);
            reputation(h, c, erin.getUUID(), 40000); relation(c, d, -50);
            h.assertTrue(CommonHooks.onInteractEntity(erin, chief, InteractionHand.MAIN_HAND) == null,
                    "The interaction event is not cancelled, so Millénaire's chief screen still opens");
            h.assertTrue(hasButton(erin.messages, "/kingdom claim") && hasButton(erin.messages, "/kingdom war request " + l.id())
                    && erin.text().contains(k.name()), "Chat overview with clickable claim and war request options");
            var result = erin.interactOn(chief, InteractionHand.MAIN_HAND);
            h.assertTrue(result.consumesAction(), "Millénaire's own mobInteract still handles the click: " + result);
            int before = erin.messages.size();
            CommonHooks.onInteractEntity(erin, chief, InteractionHand.OFF_HAND);
            h.assertTrue(erin.messages.size() == before, "Off-hand events do not repeat the overview");
            data.attach(k.id(), e.getId().uuid()); chief.setVillageId(e.getId());
            var frank = player(h, "Frank", e);
            CommonHooks.onInteractEntity(frank, chief, InteractionHand.MAIN_HAND);
            h.assertTrue(frank.messages.isEmpty(), "A member village's chief shows no kingdom politics");
            h.succeed();
        } finally { remove(h, c, d, e); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void playerCommandsAreOpenAndOperatorCommandsStay(GameTestHelper h) throws Exception {
        var c = village(h, 1, 1, "Op capital"); var d = village(h, 5, 5, "Op target");
        try {
            var data = KingdomSavedData.get(h.getLevel()); var k = kingdom(h, c); var l = kingdom(h, d);
            var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
            var console = h.getLevel().getServer().createCommandSourceStack().withLevel(h.getLevel());
            h.assertTrue(dispatcher.getRoot().getChild("kingdom").canUse(console.withPermission(0))
                    && !dispatcher.getRoot().getChild("kingdoms").canUse(console.withPermission(0)), "/kingdom is permission 0, /kingdoms stays operator-only");
            var gina = player(h, "Gina", c); reputation(h, c, gina.getUUID(), 40000);
            h.assertTrue(run(gina, "kingdoms list") == -1, "A normal player cannot use operator commands");
            h.assertTrue(run(gina, "kingdom info") == 1 && gina.text().contains(k.name()), "/kingdom info works for a player");
            run(gina, "kingdom claim"); run(gina, "kingdom war declare " + l.id());
            var war = data.activeWar(k.id());
            h.assertTrue(dispatcher.execute("kingdoms war peace " + war.id(), console) == 1 && data.war(war.id()).status() == WarStatus.PEACE,
                    "Operator peace still works on a ruler's war");
            h.assertTrue(dispatcher.execute("kingdoms ruler clear " + k.id(), console) == 1 && data.ruler(k.id()) == null, "Operator ruler clear still works");
            h.succeed();
        } finally { remove(h, c, d); }
    }
}
