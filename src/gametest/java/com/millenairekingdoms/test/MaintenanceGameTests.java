package com.millenairekingdoms.test;

import static com.millenairekingdoms.test.AutonomySupport.*;
import com.millenairekingdoms.autonomy.AutonomyService;
import com.millenairekingdoms.autonomy.RaidOrderService;
import com.millenairekingdoms.conquest.ConquestService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@PrefixGameTestTemplate(false)
public final class MaintenanceGameTests {
    private static final String REVIEW = "millenairekingdoms_autonomy_tests";

    @GameTest(templateNamespace=REVIEW, template="empty", timeoutTicks=200)
    public static void expiredReadyOrderMustNotLaunch(GameTestHelper h) throws Exception {
        var a=village(h,1,1,"Expired source"); var t=village(h,5,5,"Expired target");
        try {
            var l=h.getLevel(); var d=KingdomSavedData.get(l); var k=kingdom(d,a); raider(a);
            h.assertTrue(l.getGameTime()>1 && l.isPositionEntityTicking(t.getCenter()),"Precondition: time and ticking target");
            var order=d.addOrder(k.id(),id(t),id(a),null,0,0,1);
            RaidOrderService.process(l);
            boolean started=d.raid(id(t))!=null;
            System.out.println("REVIEW_EXPIRED_ORDER now="+l.getGameTime()+" expiry="+order.expiresGameTime()+" started="+started);
            if(started) raidManager("endRaid",a,t,l);
            h.assertTrue(!started,"Expired order must not start a real raid");
            h.succeed();
        } finally {remove(h,a,t);}
    }

    @GameTest(templateNamespace=REVIEW, template="empty", timeoutTicks=200)
    public static void transferringVictoryMustApplyRaidTension(GameTestHelper h) throws Exception {
        var a=village(h,1,1,"Tension attacker"); var b=village(h,5,5,"Tension defender"); var t=village(h,1,5,"Transferred member");
        try {
            var l=h.getLevel(); var d=KingdomSavedData.get(l); var ka=kingdom(d,a); var kb=kingdom(d,b);
            d.attach(kb.id(),id(t)); raider(a); d.declareWar(ka.id(),kb.id(),l.getGameTime());
            for(int i=0;i<2;i++) {
                if(a.getVillagerRecords().values().stream().allMatch(r->r.isKilled()))raider(a);
                relation(a,b,0);
                ConquestService.start(l,id(a),id(t),null);
                raidManager("applyLooting",a,t,l); raidManager("endRaid",a,t,l);
                System.out.println("REVIEW_CONQUEST_TENSION victory="+(i+1)+" relation="+a.getRelation(b.getId())+" transferred="+ka.id().equals(d.owner(id(t))));
                h.assertTrue(a.getRelation(b.getId())==-10,"Each conquest raid must apply tension, including the transferring victory");
            }
            h.succeed();
        } finally {remove(h,a,b,t);}
    }

    @GameTest(templateNamespace=REVIEW, template="empty", timeoutTicks=200)
    public static void abdicationMustNotStrandNpcApplications(GameTestHelper h) throws Exception {
        var a=village(h,1,1,"Abdication capital"); var v=village(h,5,5,"Stranded applicant");
        try {
            var l=h.getLevel(); var d=new KingdomSavedData(); var k=kingdom(d,a); var ruler=UUID.randomUUID();
            d.claimRule(k.id(),ruler,"Ruler",l.getGameTime()); relation(a,v,100);
            h.assertTrue(AutonomyService.decideJoin(l,d,v,yes())==AutonomyService.JoinDecision.APPLIED,"NPC applies to ruled kingdom");
            d.abdicate(k.id(),ruler);
            var decision=AutonomyService.decideJoin(l,d,v,yes());
            System.out.println("REVIEW_STRANDED_JOIN npc="+d.npcGoverned(k.id())+" pending="+(d.joinRequest(id(v))!=null)+" decision="+decision);
            h.assertTrue(d.owner(id(v))!=null || d.joinRequest(id(v))==null,"After abdication NPC applications must be processed or released");
            h.succeed();
        } finally {remove(h,a,v);}
    }

    @GameTest(templateNamespace=REVIEW, template="empty", timeoutTicks=200)
    public static void relinquishedOrdersCannotBeRevivedBySameRuler(GameTestHelper h) throws Exception {
        var a=village(h,1,1,"Old ruler source"); var t=village(h,5,5,"Old ruler target");
        try {
            var d=KingdomSavedData.get(h.getLevel()); var k=kingdom(d,a); var ruler=UUID.randomUUID();
            d.claimRule(k.id(),ruler,"OldRuler",1);
            var order=d.addOrder(k.id(),id(t),id(a),ruler,1,1,10000);
            d.abdicate(k.id(),ruler); d.claimRule(k.id(),ruler,"OldRuler",2);
            h.assertTrue(d.order(order.id())==null,"Abdication must retire an old mandate even when the same player immediately reclaims");
            h.succeed();
        } finally {remove(h,a,t);}
    }

    @GameTest(templateNamespace=REVIEW, template="empty", timeoutTicks=200)
    public static void normalRaidTensionControl(GameTestHelper h) throws Exception {
        AutonomyGameTests.millenaireRaidsBetweenKingdomsCreateTension(h);
    }
    @GameTest(templateNamespace=REVIEW, template="empty", timeoutTicks=200)
    public static void endedWarAndDeletedVillageCannotLaunchWaitingOrders(GameTestHelper h) throws Exception {
        var a=village(h,1,1,"Cancelled source"); var b=village(h,5,5,"Cancelled enemy");var t=village(h,1,5,"Deleted target");
        try {
            var l=h.getLevel();var d=KingdomSavedData.get(l);var ka=kingdom(d,a);var kb=kingdom(d,b);raider(a);
            var war=d.declareWar(ka.id(),kb.id(),l.getGameTime());
            var order=d.addOrder(ka.id(),id(b),id(a),null,1,1,Long.MAX_VALUE/2);
            d.peace(war.id());
            h.assertTrue(RaidOrderService.attempt(l,d,order,ON).status()==RaidOrderService.Status.DROPPED
                    && !b.isUnderAttack(),"Peace removes the order before it can touch Millenaire");
            var deleted=d.addOrder(ka.id(),id(t),id(a),null,1,1,Long.MAX_VALUE/2);
            remove(h,t);RaidOrderService.process(l);
            h.assertTrue(d.order(deleted.id())==null && a.getRaidTarget()==null,"A deleted target drops its order safely");
            h.succeed();
        } finally {remove(h,a,b,t);}
    }

    @GameTest(templateNamespace=REVIEW, template="empty", timeoutTicks=200)
    public static void rulerChangeLeavesAlreadyRunningCombatIntact(GameTestHelper h) throws Exception {
        var a=village(h,1,1,"Continuing source");var t=village(h,5,5,"Continuing target");
        try {
            var l=h.getLevel();var d=KingdomSavedData.get(l);var k=kingdom(d,a);raider(a);
            var raid=ConquestService.start(l,id(a),id(t),null);var ruler=UUID.randomUUID();
            d.claimRule(k.id(),ruler,"NewRuler",l.getGameTime());d.abdicate(k.id(),ruler);
            h.assertTrue(d.raid(id(t)).id().equals(raid.id()) && t.isUnderAttack(),"Authority changes retire waiting mandates, not active combat");
            raidManager("applyLooting",a,t,l);raidManager("endRaid",a,t,l);
            h.assertTrue(k.id().equals(d.owner(id(t))) && d.lastResult(id(t)).transferred() && d.raid(id(t))==null,
                    "Existing Millenaire victory, transfer and cleanup still finish normally");h.succeed();
        } finally {remove(h,a,t);}
    }

}
