package com.millenairekingdoms.test;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Outcome;
import com.millenairekingdoms.politics.KingdomDay;
import java.util.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Maintenance save compatibility uses isolated SavedData names: no player's world or global test registry is replaced. */
@PrefixGameTestTemplate(false)
public final class MaintenanceDataGameTests {
    private static final String NS="millenairekingdoms_war_tests";
    private static final String FIXTURE_STORE="maintenance_111_real_110";
    private static final String LEDGER_STORE="maintenance_111_ledger";
    private static UUID id(int n) { return new UUID(0x1110000000004000L,0x8000000000000000L+n); }
    private static boolean read() { return AutonomySupport.read(); }
    private static CompoundTag save(KingdomSavedData d,GameTestHelper h) {return d.save(new CompoundTag(),h.getLevel().registryAccess());}

    @GameTest(templateNamespace=NS,template="empty")
    public static void actual110SaveSurvivesSeparateJvmUnchanged(GameTestHelper h) throws Exception {
        CompoundTag original;
        try(var in=MaintenanceDataGameTests.class.getResourceAsStream("/review-1.1.0-kingdoms.dat")) {
            if(in==null)throw new IllegalStateException("Missing actual 1.1.0 fixture");
            original=NbtIo.readCompressed(in,NbtAccounter.unlimitedHeap()).getCompound("data");
        }
        h.assertTrue(original.getInt("format")==6 && !original.getList("wars",Tag.TAG_COMPOUND).isEmpty()
                && !original.getList("receipts",Tag.TAG_COMPOUND).isEmpty(),"Authentic 1.1.0 war/conquest save");
        var storage=h.getLevel().getDataStorage();
        KingdomSavedData d;
        if(read()) d=storage.get(KingdomSavedData.factory(),FIXTURE_STORE);
        else {
            d=KingdomSavedData.load(original.copy(),h.getLevel().registryAccess());
            storage.set(FIXTURE_STORE,d); d.setDirty();
        }
        h.assertTrue(d!=null && canonical(original).equals(canonical(save(d,h))),"Every 1.1.0 field survives save/restart unchanged");
        h.assertTrue(KingdomSavedData.FORMAT==7,"Current maximum7 preserves the authentic format6 fixture without eager history migration");
        System.out.println("MAINTENANCE_110_"+(read()?"RESTART_READ_PASS":"WRITE_PASS"));
        h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty")
    public static void fullLedgerSurvivesSeparateJvm(GameTestHelper h) {
        var storage=h.getLevel().getDataStorage();
        KingdomSavedData d;
        if(read()) d=storage.get(KingdomSavedData.factory(),LEDGER_STORE);
        else {
            d=new KingdomSavedData();
            var a=d.create("Ledger A111",id(1)); var b=d.create("Ledger B111",id(2)); var c=d.create("Ledger C111",id(3));
            d.attach(b.id(),id(4)); d.startDay(42,Map.of(a.id(),100L,b.id(),77L));
            d.claimRule(c.id(),id(99),"Ruler111",1);
            var old=d.declareWar(a.id(),b.id(),2); d.peace(old.id(),KingdomSavedData.RULER_PEACE_REASON,10);
            var war=d.declareWar(a.id(),b.id(),3);
            var raid=d.begin(id(1),id(4),4,null); d.recordVictory(raid); d.finish(raid,Outcome.LOST);
            d.offerPeace(war.id()); d.requestJoin(id(5),c.id(),null,"",5);
            d.addOrder(c.id(),id(6),id(3),id(99),6,Long.MAX_VALUE/4,Long.MAX_VALUE/2);
            d.scheduleDecision(70);
            storage.set(LEDGER_STORE,d); d.setDirty();
        }
        h.assertTrue(d!=null,"Ledger exists after process restart");
        UUID a=d.owner(id(1)),b=d.owner(id(2)),c=d.owner(id(3));
        var war=d.activeWar(a);
        h.assertTrue(d.members(b).contains(id(4)) && d.ruler(c).player().equals(id(99)),"Membership and ruler retained");
        h.assertTrue(d.day()==42 && d.nextDecisionDay()==70 && d.owed(a)==100 && d.owed(b)==77,"Tax debts and founding/political calendar retained");
        h.assertTrue(d.truceUntil(a,b)==52 && d.peaceOffered(war.id()) && d.warClock(war.id()).startDay()==42,"War, truce and peace offer retained");
        h.assertTrue(d.orderOf(c).issuer().equals(id(99)) && d.joinRequest(id(5)).kingdom().equals(c),"Valid military mandate and application retained");
        h.assertTrue(d.wins(d.campaign(id(1),id(4)))==1 && d.lastResult(id(4)).outcome()==Outcome.WON,"Conquest and receipt retained");
        h.assertTrue(!d.startDay(42,Map.of(a,100L)) && d.owed(a)==100,"Restart cannot book the same income twice");
        System.out.println("MAINTENANCE_LEDGER_"+(read()?"RESTART_READ_PASS":"WRITE_PASS"));h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty")
    public static void oldOrphanApplicationsAreReleasedOnlyDuringLifecycle(GameTestHelper h) {
        var d=new KingdomSavedData();var a=d.create("Orphan NPC",id(10));var b=d.create("Ruled",id(11));
        d.claimRule(b.id(),id(98),"StillRuler",1);d.requestJoin(id(12),a.id(),null,"",2);d.requestJoin(id(13),b.id(),null,"",2);
        d.startDay(0,Map.of()); var before=save(d,h);
        var loaded=KingdomSavedData.load(before,h.getLevel().registryAccess());
        h.assertTrue(canonical(before).equals(canonical(save(loaded,h))),"Reading existing format-6 data never silently discards requests");
        KingdomDay.run(h.getLevel(),loaded,1,AutonomySupport.ON.with(false,false,false),
                new com.millenairekingdoms.capital.FoundingConfig.Settings(false,3,7,5,5,1500),AutonomySupport.no());
        h.assertTrue(loaded.joinRequest(id(12))==null && loaded.joinRequest(id(13))!=null,"Day lifecycle releases only requests without a deciding ruler");
        h.assertTrue(loaded.releaseUnruledJoinRequests()==0,"Release is idempotent");h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty")
    public static void authorityChangesRetireOrdersAtomicallyButKeepValidApplications(GameTestHelper h) {
        var d=new KingdomSavedData();var k=d.create("Authority transitions",id(20));
        var npc=d.addOrder(k.id(),id(21),null,null,1,1,1000);
        d.requestJoin(id(22),k.id(),id(97),"Owner",2);
        d.claimRule(k.id(),id(96),"FirstRuler",3);
        h.assertTrue(d.order(npc.id())==null && d.joinRequest(id(22))!=null,"New ruler receives applications but no NPC raid mandate");
        var player=d.addOrder(k.id(),id(21),null,id(96),4,4,1000);
        var before=save(d,h);
        AutonomySupport.rejected(h,()->d.claimRule(k.id(),id(95),"SecondRuler",5));
        h.assertTrue(before.equals(save(d,h)),"Competing claim changes no state");
        var other=d.create("Other realm",id(23));
        AutonomySupport.rejected(h,()->d.claimRule(other.id(),id(96),"FirstRuler",6));
        d.clearRuler(k.id()); d.claimRule(k.id(),id(95),"SecondRuler",7);
        var loaded=KingdomSavedData.load(save(d,h),h.getLevel().registryAccess());
        h.assertTrue(loaded.order(player.id())==null && loaded.joinRequest(id(22))==null && loaded.ruler(k.id()).player().equals(id(95)),
                "Order and orphan application cannot survive operator removal or reload");h.succeed();
    }

    private static Tag canonical(Tag tag) {
        if(tag instanceof CompoundTag c){var out=new CompoundTag();for(String k:c.getAllKeys())out.put(k,canonical(c.get(k)));return out;}
        if(tag instanceof ListTag l){var entries=new ArrayList<Tag>();l.forEach(t->entries.add(canonical(t)));entries.sort(Comparator.comparing(Tag::toString));var out=new ListTag();out.addAll(entries);return out;}
        return tag.copy();
    }
}
