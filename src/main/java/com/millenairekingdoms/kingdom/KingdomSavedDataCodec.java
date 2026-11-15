package com.millenairekingdoms.kingdom;

import static com.millenairekingdoms.kingdom.KingdomSavedData.CONQUERED_REASON;
import static com.millenairekingdoms.kingdom.KingdomSavedData.FORMAT;
import static com.millenairekingdoms.kingdom.KingdomSavedData.MAX_RULER_NAME;
import static com.millenairekingdoms.kingdom.KingdomSavedData.PEACE_REASONS;
import static com.millenairekingdoms.kingdom.KingdomSavedData.STAGE3_WAR;
import static com.millenairekingdoms.kingdom.KingdomSavedData.check;
import static com.millenairekingdoms.kingdom.KingdomSavedData.requiredWins;

import com.millenairekingdoms.conquest.ConquestSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Campaign;
import com.millenairekingdoms.kingdom.KingdomSavedData.JoinRequest;
import com.millenairekingdoms.kingdom.KingdomSavedData.Kingdom;
import com.millenairekingdoms.kingdom.KingdomSavedData.Ledger;
import com.millenairekingdoms.kingdom.KingdomSavedData.Outcome;
import com.millenairekingdoms.kingdom.KingdomSavedData.Pair;
import com.millenairekingdoms.kingdom.KingdomSavedData.Raid;
import com.millenairekingdoms.kingdom.KingdomSavedData.RaidOrder;
import com.millenairekingdoms.kingdom.KingdomSavedData.Result;
import com.millenairekingdoms.kingdom.KingdomSavedData.Ruler;
import com.millenairekingdoms.kingdom.KingdomSavedData.State;
import com.millenairekingdoms.kingdom.KingdomSavedData.War;
import com.millenairekingdoms.kingdom.KingdomSavedData.WarClock;
import com.millenairekingdoms.kingdom.KingdomSavedData.WarStatus;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * NBT serialization, legacy format migration, and snapshot invariant validation for {@link KingdomSavedData}.
 */
final class KingdomSavedDataCodec {
    private KingdomSavedDataCodec() {}

    static KingdomSavedData fromLegacy(CompoundTag legacy, HolderLookup.Provider registries) {
        var old = ConquestSavedData.load(legacy, registries);
        var data = new KingdomSavedData();
        data.legacyArchive = legacy.copy();
        var active = new HashMap<UUID, Raid>();
        var receipts = new HashMap<UUID, Result>();
        for (var r : old.activeRaids()) {
            var raid = new Raid(
                    r.id(), r.attacker(), r.target(), r.startGameTime(), r.initiator(), null, r.victoryRecorded());
            active.put(raid.target(), raid);
            if (raid.victoryRecorded())
                receipts.put(raid.id(),
                        new Result(raid.id(), raid.attacker(), raid.target(), null, Outcome.LEGACY_WON, false));
        }
        data.publish(new State(
                Map.of(), Map.of(), Map.of(), Map.of(), active, receipts, Map.of(), Map.of(), Map.of(), Ledger.EMPTY));
        return data;
    }

    static CompoundTag save(KingdomSavedData data, CompoundTag tag) {
        State state = data.state;
        tag.putInt("format", data.historyCommit == null ? 6 : FORMAT);
        if (data.historyCommit != null)
            com.millenairekingdoms.compat.KNbt.putUUID(tag, "historyCommit", data.historyCommit);
        tag.put("legacyArchive", data.legacyArchive.copy());
        ListTag kingdoms = new ListTag();
        state.kingdoms().values().forEach(k -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "id", k.id());
            e.putString("name", k.name());
            com.millenairekingdoms.compat.KNbt.putUUID(e, "capital", k.capital());
            e.putBoolean("active", k.active());
            if (k.fallen())
                com.millenairekingdoms.compat.KNbt.putUUID(e, "fallenInWar", k.fallenInWar());
            kingdoms.add(e);
        });
        tag.put("kingdoms", kingdoms);
        ListTag membership = new ListTag();
        state.membership().forEach((v, k) -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "village", v);
            com.millenairekingdoms.compat.KNbt.putUUID(e, "kingdom", k);
            membership.add(e);
        });
        tag.put("membership", membership);
        ListTag revisions = new ListTag();
        state.revisions().forEach((v, r) -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "village", v);
            e.putLong("revision", r);
            revisions.add(e);
        });
        tag.put("revisions", revisions);
        ListTag progress = new ListTag();
        state.progress().forEach((c, w) -> {
            CompoundTag e = writeCampaign(c);
            e.putInt("wins", w);
            progress.add(e);
        });
        tag.put("progress", progress);
        ListTag active = new ListTag();
        state.active().values().forEach(r -> {
            CompoundTag e = identity(r.id(), r.attacker(), r.target(), r.campaign());
            e.putLong("startGameTime", r.startGameTime());
            if (r.initiator() != null)
                com.millenairekingdoms.compat.KNbt.putUUID(e, "initiator", r.initiator());
            e.putBoolean("victoryRecorded", r.victoryRecorded());
            active.add(e);
        });
        tag.put("active", active);
        ListTag receipts = new ListTag();
        state.receipts().values().forEach(r -> {
            CompoundTag e = identity(r.raidId(), r.attacker(), r.target(), r.campaign());
            e.putString("outcome", r.outcome().name());
            e.putBoolean("transferred", r.transferred());
            receipts.add(e);
        });
        tag.put("receipts", receipts);
        ListTag last = new ListTag();
        state.last().forEach((t, id) -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "target", t);
            com.millenairekingdoms.compat.KNbt.putUUID(e, "id", id);
            last.add(e);
        });
        tag.put("last", last);
        ListTag wars = new ListTag();
        state.wars().values().forEach(w -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "id", w.id());
            com.millenairekingdoms.compat.KNbt.putUUID(e, "attackerKingdom", w.attackerKingdom());
            com.millenairekingdoms.compat.KNbt.putUUID(e, "defenderKingdom", w.defenderKingdom());
            e.putLong("startGameTime", w.startGameTime());
            e.putString("status", w.status().name());
            e.putString("endReason", w.endReason());
            ListTag initial = new ListTag();
            w.initialDefenderMembers().forEach(v -> {
                CompoundTag member = new CompoundTag();
                com.millenairekingdoms.compat.KNbt.putUUID(member, "village", v);
                initial.add(member);
            });
            e.put("initialDefenderMembers", initial);
            wars.add(e);
        });
        tag.put("wars", wars);
        ListTag rulers = new ListTag();
        state.rulers().forEach((k, r) -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "kingdom", k);
            com.millenairekingdoms.compat.KNbt.putUUID(e, "player", r.player());
            e.putString("lastKnownName", r.lastKnownName());
            e.putLong("sinceGameTime", r.sinceGameTime());
            rulers.add(e);
        });
        tag.put("rulers", rulers);
        CompoundTag ledger = new CompoundTag();
        ledger.putLong("day", state.ledger().day());
        ListTag owed = new ListTag();
        state.ledger().owed().forEach((k, v) -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "kingdom", k);
            e.putLong("deniers", v);
            owed.add(e);
        });
        ledger.put("owed", owed);
        ListTag offers = new ListTag();
        state.ledger().peaceOffers().forEach(w -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "war", w);
            offers.add(e);
        });
        ledger.put("peaceOffers", offers);
        ListTag joins = new ListTag();
        state.ledger().joinRequests().forEach((v, r) -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "village", v);
            com.millenairekingdoms.compat.KNbt.putUUID(e, "kingdom", r.kingdom());
            if (r.player() != null)
                com.millenairekingdoms.compat.KNbt.putUUID(e, "player", r.player());
            e.putString("playerName", r.playerName());
            e.putLong("gameTime", r.gameTime());
            joins.add(e);
        });
        ledger.put("joinRequests", joins);
        tag.put("ledger", ledger);
        CompoundTag autonomy = new CompoundTag();
        autonomy.putLong("nextDecisionDay", state.ledger().nextDecisionDay());
        ListTag truces = new ListTag();
        state.ledger().truces().forEach((p, until) -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "a", p.low());
            com.millenairekingdoms.compat.KNbt.putUUID(e, "b", p.high());
            e.putLong("untilDay", until);
            truces.add(e);
        });
        autonomy.put("truces", truces);
        ListTag raidDays = new ListTag();
        state.ledger().raidDays().forEach((t, d) -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "target", t);
            e.putLong("day", d);
            raidDays.add(e);
        });
        autonomy.put("raidDays", raidDays);
        ListTag clocks = new ListTag();
        state.ledger().warClocks().forEach((w, c) -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "war", w);
            e.putLong("startDay", c.startDay());
            e.putLong("lastProgressDay", c.lastProgressDay());
            clocks.add(e);
        });
        autonomy.put("warClocks", clocks);
        ListTag orders = new ListTag();
        state.ledger().orders().values().forEach(o -> {
            CompoundTag e = new CompoundTag();
            com.millenairekingdoms.compat.KNbt.putUUID(e, "id", o.id());
            com.millenairekingdoms.compat.KNbt.putUUID(e, "kingdom", o.kingdom());
            com.millenairekingdoms.compat.KNbt.putUUID(e, "target", o.target());
            if (o.source() != null)
                com.millenairekingdoms.compat.KNbt.putUUID(e, "source", o.source());
            if (o.warId() != null)
                com.millenairekingdoms.compat.KNbt.putUUID(e, "war", o.warId());
            if (o.issuer() != null)
                com.millenairekingdoms.compat.KNbt.putUUID(e, "issuer", o.issuer());
            e.putLong("issuedGameTime", o.issuedGameTime());
            e.putLong("dueGameTime", o.dueGameTime());
            e.putLong("expiresGameTime", o.expiresGameTime());
            if (o.quotedCost() > 0)
                e.putLong("quotedCost", o.quotedCost());
            if (o.quotedStrength() > 0)
                e.putInt("quotedStrength", o.quotedStrength());
            orders.add(e);
        });
        autonomy.put("orders", orders);
        tag.put("autonomy", autonomy);
        return tag;
    }

    static KingdomSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        int format = tag.getIntOr("format", 0);
        check(format >= 1 && format <= FORMAT, "Unsupported kingdom data format.");
        check(format >= 7 ? com.millenairekingdoms.compat.KNbt.hasUUID(tag, "historyCommit") : !tag.contains("historyCommit"),
                "Invalid or disguised historical commit marker.");
        boolean stage3 = format < 3;
        // A format-1 file must not hide format-2 war records during migration.
        check(format != 1 || !tag.contains("wars"), "Unexpected wars in legacy kingdom data.");
        check(format >= 4 || !tag.contains("rulers"), "Unexpected rulers in legacy kingdom data.");
        var kingdoms = new HashMap<UUID, Kingdom>();
        var membership = new HashMap<UUID, UUID>();
        var revisions = new HashMap<UUID, Long>();
        var progress = new HashMap<Campaign, Integer>();
        var active = new HashMap<UUID, Raid>();
        var receipts = new HashMap<UUID, Result>();
        var last = new HashMap<UUID, UUID>();
        for (Tag v : list(tag, "kingdoms")) {
            CompoundTag e = (CompoundTag) v;
            var k = new Kingdom(com.millenairekingdoms.compat.KNbt.getUUID(e, "id"), e.getStringOr("name", ""), com.millenairekingdoms.compat.KNbt.getUUID(e, "capital"), e.getBooleanOr("active", false),
                    com.millenairekingdoms.compat.KNbt.hasUUID(e, "fallenInWar") ? com.millenairekingdoms.compat.KNbt.getUUID(e, "fallenInWar") : null);
            unique(kingdoms, k.id(), k);
        }
        for (Tag v : list(tag, "membership")) {
            CompoundTag e = (CompoundTag) v;
            unique(membership, com.millenairekingdoms.compat.KNbt.getUUID(e, "village"), com.millenairekingdoms.compat.KNbt.getUUID(e, "kingdom"));
        }
        for (Tag v : list(tag, "revisions")) {
            CompoundTag e = (CompoundTag) v;
            unique(revisions, com.millenairekingdoms.compat.KNbt.getUUID(e, "village"), e.getLongOr("revision", 0L));
        }
        for (Tag v : list(tag, "progress")) {
            CompoundTag e = (CompoundTag) v;
            unique(progress, readCampaign(e, stage3), e.getIntOr("wins", 0));
        }
        for (Tag v : list(tag, "active")) {
            CompoundTag e = (CompoundTag) v;
            var r = new Raid(com.millenairekingdoms.compat.KNbt.getUUID(e, "id"), com.millenairekingdoms.compat.KNbt.getUUID(e, "attacker"), com.millenairekingdoms.compat.KNbt.getUUID(e, "target"), e.getLongOr("startGameTime", 0L),
                    com.millenairekingdoms.compat.KNbt.hasUUID(e, "initiator") ? com.millenairekingdoms.compat.KNbt.getUUID(e, "initiator") : null, context(e, stage3),
                    e.getBooleanOr("victoryRecorded", false));
            unique(active, r.target(), r);
        }
        for (Tag v : list(tag, "receipts")) {
            CompoundTag e = (CompoundTag) v;
            var r = new Result(com.millenairekingdoms.compat.KNbt.getUUID(e, "id"), com.millenairekingdoms.compat.KNbt.getUUID(e, "attacker"), com.millenairekingdoms.compat.KNbt.getUUID(e, "target"), context(e, stage3),
                    Outcome.valueOf(e.getStringOr("outcome", "")), e.getBooleanOr("transferred", false));
            unique(receipts, r.raidId(), r);
        }
        for (Tag v : list(tag, "last")) {
            CompoundTag e = (CompoundTag) v;
            unique(last, com.millenairekingdoms.compat.KNbt.getUUID(e, "target"), com.millenairekingdoms.compat.KNbt.getUUID(e, "id"));
        }
        var data = new KingdomSavedData();
        var wars = new HashMap<UUID, War>();
        if (format >= 2)
            for (Tag v : list(tag, "wars")) {
                CompoundTag e = (CompoundTag) v;
                check(e.contains("startGameTime") && e.contains("endReason"),
                        "Incomplete war data.");
                Set<UUID> initial = new HashSet<>();
                for (Tag member : list(e, "initialDefenderMembers"))
                    check(initial.add(com.millenairekingdoms.compat.KNbt.getUUID((CompoundTag) member, "village")), "Duplicate war snapshot member.");
                var war = new War(com.millenairekingdoms.compat.KNbt.getUUID(e, "id"), com.millenairekingdoms.compat.KNbt.getUUID(e, "attackerKingdom"), com.millenairekingdoms.compat.KNbt.getUUID(e, "defenderKingdom"),
                        e.getLongOr("startGameTime", 0L), WarStatus.valueOf(e.getStringOr("status", "")), initial,
                        e.getStringOr("endReason", ""));
                unique(wars, war.id(), war);
            }
        var rulers = new HashMap<UUID, Ruler>();
        if (format >= 4)
            for (Tag v : list(tag, "rulers")) {
                CompoundTag e = (CompoundTag) v;
                check(com.millenairekingdoms.compat.KNbt.hasUUID(e, "kingdom") && com.millenairekingdoms.compat.KNbt.hasUUID(e, "player") && e.contains("lastKnownName")
                                && e.contains("sinceGameTime"),
                        "Incomplete ruler data.");
                unique(rulers, com.millenairekingdoms.compat.KNbt.getUUID(e, "kingdom"),
                        new Ruler(com.millenairekingdoms.compat.KNbt.getUUID(e, "player"), e.getStringOr("lastKnownName", ""), e.getLongOr("sinceGameTime", 0L)));
            }
        check(format >= 5 || !tag.contains("ledger"), "Unexpected ledger in legacy kingdom data.");
        Ledger ledger = Ledger.EMPTY;
        if (format >= 5) {
            check(tag.contains("ledger"), "Missing kingdom ledger.");
            CompoundTag l = tag.getCompoundOrEmpty("ledger");
            check(l.contains("day"), "Incomplete ledger.");
            var owed = new HashMap<UUID, Long>();
            for (Tag v : list(l, "owed")) {
                CompoundTag e = (CompoundTag) v;
                check(com.millenairekingdoms.compat.KNbt.hasUUID(e, "kingdom") && e.contains("deniers") && e.getLongOr("deniers", 0L) > 0,
                        "Invalid undelivered income.");
                unique(owed, com.millenairekingdoms.compat.KNbt.getUUID(e, "kingdom"), e.getLongOr("deniers", 0L));
            }
            var offers = new HashSet<UUID>();
            for (Tag v : list(l, "peaceOffers"))
                check(offers.add(com.millenairekingdoms.compat.KNbt.getUUID((CompoundTag) v, "war")), "Duplicate peace offer.");
            var joins = new HashMap<UUID, JoinRequest>();
            for (Tag v : list(l, "joinRequests")) {
                CompoundTag e = (CompoundTag) v;
                // Format 6 allows an NPC village's own application, which has no player.
                check(com.millenairekingdoms.compat.KNbt.hasUUID(e, "village") && com.millenairekingdoms.compat.KNbt.hasUUID(e, "kingdom") && (format >= 6 || com.millenairekingdoms.compat.KNbt.hasUUID(e, "player"))
                                && e.contains("playerName") && e.contains("gameTime"),
                        "Incomplete join request.");
                unique(joins, com.millenairekingdoms.compat.KNbt.getUUID(e, "village"),
                        new JoinRequest(com.millenairekingdoms.compat.KNbt.getUUID(e, "kingdom"), com.millenairekingdoms.compat.KNbt.hasUUID(e, "player") ? com.millenairekingdoms.compat.KNbt.getUUID(e, "player") : null,
                                e.getStringOr("playerName", ""), e.getLongOr("gameTime", 0L)));
            }
            ledger = new Ledger(l.getLongOr("day", 0L), owed, offers, joins, -1, Map.of(), Map.of(), Map.of(), Map.of());
        }
        check(format >= 6 || !tag.contains("autonomy"), "Unexpected autonomy data in legacy kingdom data.");
        if (format >= 6) {
            check(tag.contains("autonomy"), "Missing kingdom autonomy data.");
            CompoundTag a = tag.getCompoundOrEmpty("autonomy");
            check(a.contains("nextDecisionDay"), "Incomplete autonomy data.");
            var truces = new HashMap<Pair, Long>();
            for (Tag v : list(a, "truces")) {
                CompoundTag e = (CompoundTag) v;
                check(com.millenairekingdoms.compat.KNbt.hasUUID(e, "a") && com.millenairekingdoms.compat.KNbt.hasUUID(e, "b") && e.contains("untilDay"), "Incomplete truce.");
                Pair pair = Pair.of(com.millenairekingdoms.compat.KNbt.getUUID(e, "a"), com.millenairekingdoms.compat.KNbt.getUUID(e, "b"));
                check(pair.low().equals(com.millenairekingdoms.compat.KNbt.getUUID(e, "a")), "Truce pair is not in canonical order.");
                unique(truces, pair, e.getLongOr("untilDay", 0L));
            }
            var raidDays = new HashMap<UUID, Long>();
            for (Tag v : list(a, "raidDays")) {
                CompoundTag e = (CompoundTag) v;
                check(com.millenairekingdoms.compat.KNbt.hasUUID(e, "target") && e.contains("day"), "Incomplete raid day.");
                unique(raidDays, com.millenairekingdoms.compat.KNbt.getUUID(e, "target"), e.getLongOr("day", 0L));
            }
            var clocks = new HashMap<UUID, WarClock>();
            for (Tag v : list(a, "warClocks")) {
                CompoundTag e = (CompoundTag) v;
                check(com.millenairekingdoms.compat.KNbt.hasUUID(e, "war") && e.contains("startDay")
                                && e.contains("lastProgressDay"),
                        "Incomplete war clock.");
                unique(clocks, com.millenairekingdoms.compat.KNbt.getUUID(e, "war"), new WarClock(e.getLongOr("startDay", 0L), e.getLongOr("lastProgressDay", 0L)));
            }
            var orders = new HashMap<UUID, RaidOrder>();
            for (Tag v : list(a, "orders")) {
                CompoundTag e = (CompoundTag) v;
                check(com.millenairekingdoms.compat.KNbt.hasUUID(e, "id") && com.millenairekingdoms.compat.KNbt.hasUUID(e, "kingdom") && com.millenairekingdoms.compat.KNbt.hasUUID(e, "target")
                                && e.contains("issuedGameTime") && e.contains("dueGameTime")
                                && e.contains("expiresGameTime"),
                        "Incomplete raid order.");
                var o = new RaidOrder(com.millenairekingdoms.compat.KNbt.getUUID(e, "id"), com.millenairekingdoms.compat.KNbt.getUUID(e, "kingdom"), com.millenairekingdoms.compat.KNbt.getUUID(e, "target"),
                        com.millenairekingdoms.compat.KNbt.hasUUID(e, "source") ? com.millenairekingdoms.compat.KNbt.getUUID(e, "source") : null, com.millenairekingdoms.compat.KNbt.hasUUID(e, "war") ? com.millenairekingdoms.compat.KNbt.getUUID(e, "war") : null,
                        com.millenairekingdoms.compat.KNbt.hasUUID(e, "issuer") ? com.millenairekingdoms.compat.KNbt.getUUID(e, "issuer") : null, e.getLongOr("issuedGameTime", 0L),
                        e.getLongOr("dueGameTime", 0L), e.getLongOr("expiresGameTime", 0L),
                        e.contains("quotedCost") ? e.getLongOr("quotedCost", 0L) : 0L,
                        e.contains("quotedStrength") ? e.getIntOr("quotedStrength", 0) : 0);
                unique(orders, o.id(), o);
            }
            ledger = ledger.withDecision(a.getLongOr("nextDecisionDay", 0L))
                             .withTruces(truces)
                             .withRaidDays(raidDays)
                             .withClocks(clocks)
                             .withOrders(orders);
        }
        data.state = new State(kingdoms, membership, revisions, progress, active, receipts, last, wars, rulers, ledger);
        // Pruning is for live transitions only; a stored entry that no longer applies means damaged data.
        // Older formats had no war clocks: every ACTIVE war starts its clock on the stored ledger day.
        Ledger expected = format < 6 ? ledger.withClocks(data.state.ledger().warClocks()) : ledger;
        check(data.state.ledger().equals(expected), "Ledger refers to ended wars, fallen kingdoms or member villages.");
        data.historyCommit = format >= 7 ? com.millenairekingdoms.compat.KNbt.getUUID(tag, "historyCommit") : null;
        if (format < 6)
            data.setDirty();
        data.legacyArchive = tag.getCompoundOrEmpty("legacyArchive").copy();
        if (!data.legacyArchive.isEmpty())
            ConquestSavedData.load(data.legacyArchive, registries);
        return data;
    }

    private static CompoundTag identity(UUID id, UUID attacker, UUID target, Campaign context) {
        CompoundTag e = new CompoundTag();
        com.millenairekingdoms.compat.KNbt.putUUID(e, "id", id);
        com.millenairekingdoms.compat.KNbt.putUUID(e, "attacker", attacker);
        com.millenairekingdoms.compat.KNbt.putUUID(e, "target", target);
        if (context != null)
            e.put("campaign", writeCampaign(context));
        return e;
    }

    private static CompoundTag writeCampaign(Campaign c) {
        CompoundTag e = new CompoundTag();
        com.millenairekingdoms.compat.KNbt.putUUID(e, "attackerKingdom", c.attackerKingdom());
        com.millenairekingdoms.compat.KNbt.putUUID(e, "target", c.target());
        e.putLong("targetRevision", c.targetRevision());
        if (c.warId() != null)
            com.millenairekingdoms.compat.KNbt.putUUID(e, "warId", c.warId());
        return e;
    }

    private static Campaign readCampaign(CompoundTag e, boolean stage3) {
        check(!stage3 || !com.millenairekingdoms.compat.KNbt.hasUUID(e, "warId"), "Unexpected war context in pre-4.2A campaign.");
        return new Campaign(com.millenairekingdoms.compat.KNbt.getUUID(e, "attackerKingdom"), com.millenairekingdoms.compat.KNbt.getUUID(e, "target"), e.getLongOr("targetRevision", 0L),
                com.millenairekingdoms.compat.KNbt.hasUUID(e, "warId") ? com.millenairekingdoms.compat.KNbt.getUUID(e, "warId")
                        : stage3   ? STAGE3_WAR
                                   : null);
    }

    private static Campaign context(CompoundTag e, boolean stage3) {
        return e.contains("campaign") ? readCampaign(e.getCompoundOrEmpty("campaign"), stage3) : null;
    }

    private static ListTag list(CompoundTag tag, String key) {
        check(tag.contains(key), "Missing kingdom list: " + key);
        ListTag list = (ListTag) tag.get(key);
        check(list.isEmpty() || com.millenairekingdoms.compat.KNbt.elementType(list) == Tag.TAG_COMPOUND, "Invalid kingdom list: " + key);
        return list;
    }

    private static <K, V> void unique(Map<K, V> map, K key, V value) {
        check(map.putIfAbsent(key, value) == null, "Duplicate kingdom data key.");
    }

    /** A ruler belongs to an existing kingdom that has not fallen; one player rules at most one kingdom. */
    static void validateRulers(Map<UUID, Kingdom> kingdoms, Map<UUID, Ruler> rulers) {
        Set<UUID> players = new HashSet<>();
        rulers.forEach((k, r)
                               -> check(kingdoms.containsKey(k) && !kingdoms.get(k).fallen() && r.player() != null
                                               && r.lastKnownName() != null && !r.lastKnownName().isBlank()
                                               && r.lastKnownName().length() <= MAX_RULER_NAME && r.sinceGameTime() >= 0
                                               && players.add(r.player()),
                                       "Invalid kingdom ruler."));
    }

    static void validateWars(Map<UUID, Kingdom> kingdoms, Map<UUID, War> wars) {
        Set<UUID> participants = new HashSet<>();
        wars.forEach((id, w) -> {
            check(id.equals(w.id()) && !id.equals(STAGE3_WAR) && !w.attackerKingdom().equals(w.defenderKingdom())
                            && w.startGameTime() >= 0 && kingdoms.containsKey(w.attackerKingdom())
                            && kingdoms.containsKey(w.defenderKingdom()),
                    "Invalid war identity/participants.");
            check(!w.initialDefenderMembers().contains(kingdoms.get(w.defenderKingdom()).capital()),
                    "Capital cannot be a member of the initial war snapshot.");
            if (w.status() == WarStatus.ACTIVE) {
                check(w.endReason().isEmpty() && kingdoms.get(w.attackerKingdom()).active()
                                && kingdoms.get(w.defenderKingdom()).active(),
                        "Invalid active war state.");
                check(participants.add(w.attackerKingdom()) && participants.add(w.defenderKingdom()),
                        "Overlapping active wars.");
            } else if (w.status() == WarStatus.PEACE)
                check(PEACE_REASONS.contains(w.endReason()), "Invalid peace end reason.");
            else
                check(w.endReason().equals(CONQUERED_REASON)
                                && id.equals(kingdoms.get(w.defenderKingdom()).fallenInWar()),
                        "Conquered war must name the kingdom that fell in it.");
        });
        // A kingdom falls exactly once, in a recorded CONQUERED war where it was the defender.
        kingdoms.values().stream().filter(Kingdom::fallen).forEach(k -> {
            War w = wars.get(k.fallenInWar());
            check(!k.active() && w != null && w.status() == WarStatus.CONQUERED && w.defenderKingdom().equals(k.id()),
                    "Invalid fallen kingdom.");
        });
    }

    /**
     * A war-bound campaign must name a recorded war of the same attacking kingdom;
     * an unfinished war raid additionally requires that war not to have ended in peace.
     * Raids still running when a capital fell close normally and award nothing (INVALIDATED).
     */
    static void validateCampaignWars(
            Map<UUID, War> wars, Map<Campaign, Integer> progress, Map<UUID, Raid> active, Map<UUID, Result> receipts) {
        java.util.function.Consumer<Campaign> consistent = c -> {
            if (c == null || c.warId() == null || c.warId().equals(STAGE3_WAR))
                return;
            War war = wars.get(c.warId());
            check(war != null && war.attackerKingdom().equals(c.attackerKingdom()),
                    "Conquest progress does not match its war.");
        };
        progress.keySet().forEach(consistent);
        receipts.values().forEach(r -> consistent.accept(r.campaign()));
        active.values().forEach(r -> {
            consistent.accept(r.campaign());
            check(r.campaign() == null || r.campaign().warId() == null || r.campaign().warId().equals(STAGE3_WAR)
                            || wars.get(r.campaign().warId()).status() != WarStatus.PEACE,
                    "Active conquest raid belongs to an ended war.");
        });
    }

    static void validate(Map<UUID, Kingdom> kingdoms, Map<UUID, UUID> membership, Map<UUID, Long> revisions,
            Map<Campaign, Integer> progress, Map<UUID, Raid> active, Map<UUID, Result> receipts, Map<UUID, UUID> last) {
        Set<String> names = new HashSet<>();
        kingdoms.forEach((id, k)
                                 -> check(id.equals(k.id()) && !k.name().isBlank() && k.name().length() <= 64
                                                 && names.add(k.name().toLowerCase(java.util.Locale.ROOT))
                                                 && (k.fallen() || id.equals(membership.get(k.capital()))),
                                         "Invalid kingdom/capital membership."));
        membership.forEach((v, k)
                                   -> check(kingdoms.containsKey(k) && !kingdoms.get(k).fallen()
                                                   && revisions.getOrDefault(v, 0L) > 0,
                                           "Invalid membership/revision."));
        revisions.forEach((v, r) -> check(r > 0, "Invalid target revision."));
        progress.forEach((c, w)
                                 -> check(kingdoms.containsKey(c.attackerKingdom()) && c.targetRevision() >= 0
                                                 && c.targetRevision() <= revisions.getOrDefault(c.target(), 0L)
                                                 && w > 0 && w <= requiredWins(c),
                                         "Invalid conquest progress."));
        Set<UUID> involved = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        active.forEach((t, r) -> {
            check(t.equals(r.target()) && r.startGameTime() > 0 && involved.add(r.attacker())
                            && involved.add(r.target()) && ids.add(r.id()),
                    "Invalid/overlapping raid.");
            check(r.campaign() == null
                            || (r.target().equals(r.campaign().target())
                                    && kingdoms.containsKey(r.campaign().attackerKingdom())),
                    "Invalid raid campaign.");
            check(r.victoryRecorded() == receipts.containsKey(r.id()), "Raid result marker and receipt differ.");
            if (r.victoryRecorded()) {
                var receipt = receipts.get(r.id());
                check(receipt.target().equals(r.target()) && receipt.attacker().equals(r.attacker())
                                && java.util.Objects.equals(receipt.campaign(), r.campaign()),
                        "Raid receipt identity differs.");
            }
        });
        receipts.forEach((id, r) -> {
            check(id.equals(r.raidId()) && !r.attacker().equals(r.target()), "Invalid receipt identity.");
            check(r.campaign() == null
                            || (r.target().equals(r.campaign().target())
                                    && kingdoms.containsKey(r.campaign().attackerKingdom())),
                    "Invalid receipt campaign.");
            check(r.outcome() != Outcome.WON || (r.campaign() != null && progress.getOrDefault(r.campaign(), 0) > 0),
                    "Victory without progress.");
            check(!r.transferred()
                            || (r.outcome() == Outcome.WON
                                    && progress.getOrDefault(r.campaign(), 0) == requiredWins(r.campaign())),
                    "Transfer without completed campaign.");
        });
        last.forEach((t, id)
                             -> check(receipts.containsKey(id) && receipts.get(id).target().equals(t),
                                     "Invalid last-result index."));
    }
}
