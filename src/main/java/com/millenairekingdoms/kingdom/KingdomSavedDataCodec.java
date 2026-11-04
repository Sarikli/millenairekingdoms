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
            tag.putUUID("historyCommit", data.historyCommit);
        tag.put("legacyArchive", data.legacyArchive.copy());
        ListTag kingdoms = new ListTag();
        state.kingdoms().values().forEach(k -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("id", k.id());
            e.putString("name", k.name());
            e.putUUID("capital", k.capital());
            e.putBoolean("active", k.active());
            if (k.fallen())
                e.putUUID("fallenInWar", k.fallenInWar());
            kingdoms.add(e);
        });
        tag.put("kingdoms", kingdoms);
        ListTag membership = new ListTag();
        state.membership().forEach((v, k) -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("village", v);
            e.putUUID("kingdom", k);
            membership.add(e);
        });
        tag.put("membership", membership);
        ListTag revisions = new ListTag();
        state.revisions().forEach((v, r) -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("village", v);
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
                e.putUUID("initiator", r.initiator());
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
            e.putUUID("target", t);
            e.putUUID("id", id);
            last.add(e);
        });
        tag.put("last", last);
        ListTag wars = new ListTag();
        state.wars().values().forEach(w -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("id", w.id());
            e.putUUID("attackerKingdom", w.attackerKingdom());
            e.putUUID("defenderKingdom", w.defenderKingdom());
            e.putLong("startGameTime", w.startGameTime());
            e.putString("status", w.status().name());
            e.putString("endReason", w.endReason());
            ListTag initial = new ListTag();
            w.initialDefenderMembers().forEach(v -> {
                CompoundTag member = new CompoundTag();
                member.putUUID("village", v);
                initial.add(member);
            });
            e.put("initialDefenderMembers", initial);
            wars.add(e);
        });
        tag.put("wars", wars);
        ListTag rulers = new ListTag();
        state.rulers().forEach((k, r) -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("kingdom", k);
            e.putUUID("player", r.player());
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
            e.putUUID("kingdom", k);
            e.putLong("deniers", v);
            owed.add(e);
        });
        ledger.put("owed", owed);
        ListTag offers = new ListTag();
        state.ledger().peaceOffers().forEach(w -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("war", w);
            offers.add(e);
        });
        ledger.put("peaceOffers", offers);
        ListTag joins = new ListTag();
        state.ledger().joinRequests().forEach((v, r) -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("village", v);
            e.putUUID("kingdom", r.kingdom());
            if (r.player() != null)
                e.putUUID("player", r.player());
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
            e.putUUID("a", p.low());
            e.putUUID("b", p.high());
            e.putLong("untilDay", until);
            truces.add(e);
        });
        autonomy.put("truces", truces);
        ListTag raidDays = new ListTag();
        state.ledger().raidDays().forEach((t, d) -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("target", t);
            e.putLong("day", d);
            raidDays.add(e);
        });
        autonomy.put("raidDays", raidDays);
        ListTag clocks = new ListTag();
        state.ledger().warClocks().forEach((w, c) -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("war", w);
            e.putLong("startDay", c.startDay());
            e.putLong("lastProgressDay", c.lastProgressDay());
            clocks.add(e);
        });
        autonomy.put("warClocks", clocks);
        ListTag orders = new ListTag();
        state.ledger().orders().values().forEach(o -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("id", o.id());
            e.putUUID("kingdom", o.kingdom());
            e.putUUID("target", o.target());
            if (o.source() != null)
                e.putUUID("source", o.source());
            if (o.warId() != null)
                e.putUUID("war", o.warId());
            if (o.issuer() != null)
                e.putUUID("issuer", o.issuer());
            e.putLong("issuedGameTime", o.issuedGameTime());
            e.putLong("dueGameTime", o.dueGameTime());
            e.putLong("expiresGameTime", o.expiresGameTime());
            orders.add(e);
        });
        autonomy.put("orders", orders);
        tag.put("autonomy", autonomy);
        return tag;
    }

    static KingdomSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        int format = tag.getInt("format");
        check(format >= 1 && format <= FORMAT, "Unsupported kingdom data format.");
        check(format >= 7 ? tag.hasUUID("historyCommit") : !tag.contains("historyCommit"),
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
            var k = new Kingdom(e.getUUID("id"), e.getString("name"), e.getUUID("capital"), e.getBoolean("active"),
                    e.hasUUID("fallenInWar") ? e.getUUID("fallenInWar") : null);
            unique(kingdoms, k.id(), k);
        }
        for (Tag v : list(tag, "membership")) {
            CompoundTag e = (CompoundTag) v;
            unique(membership, e.getUUID("village"), e.getUUID("kingdom"));
        }
        for (Tag v : list(tag, "revisions")) {
            CompoundTag e = (CompoundTag) v;
            unique(revisions, e.getUUID("village"), e.getLong("revision"));
        }
        for (Tag v : list(tag, "progress")) {
            CompoundTag e = (CompoundTag) v;
            unique(progress, readCampaign(e, stage3), e.getInt("wins"));
        }
        for (Tag v : list(tag, "active")) {
            CompoundTag e = (CompoundTag) v;
            var r = new Raid(e.getUUID("id"), e.getUUID("attacker"), e.getUUID("target"), e.getLong("startGameTime"),
                    e.hasUUID("initiator") ? e.getUUID("initiator") : null, context(e, stage3),
                    e.getBoolean("victoryRecorded"));
            unique(active, r.target(), r);
        }
        for (Tag v : list(tag, "receipts")) {
            CompoundTag e = (CompoundTag) v;
            var r = new Result(e.getUUID("id"), e.getUUID("attacker"), e.getUUID("target"), context(e, stage3),
                    Outcome.valueOf(e.getString("outcome")), e.getBoolean("transferred"));
            unique(receipts, r.raidId(), r);
        }
        for (Tag v : list(tag, "last")) {
            CompoundTag e = (CompoundTag) v;
            unique(last, e.getUUID("target"), e.getUUID("id"));
        }
        var data = new KingdomSavedData();
        var wars = new HashMap<UUID, War>();
        if (format >= 2)
            for (Tag v : list(tag, "wars")) {
                CompoundTag e = (CompoundTag) v;
                check(e.contains("startGameTime", Tag.TAG_LONG) && e.contains("endReason", Tag.TAG_STRING),
                        "Incomplete war data.");
                Set<UUID> initial = new HashSet<>();
                for (Tag member : list(e, "initialDefenderMembers"))
                    check(initial.add(((CompoundTag) member).getUUID("village")), "Duplicate war snapshot member.");
                var war = new War(e.getUUID("id"), e.getUUID("attackerKingdom"), e.getUUID("defenderKingdom"),
                        e.getLong("startGameTime"), WarStatus.valueOf(e.getString("status")), initial,
                        e.getString("endReason"));
                unique(wars, war.id(), war);
            }
        var rulers = new HashMap<UUID, Ruler>();
        if (format >= 4)
            for (Tag v : list(tag, "rulers")) {
                CompoundTag e = (CompoundTag) v;
                check(e.hasUUID("kingdom") && e.hasUUID("player") && e.contains("lastKnownName", Tag.TAG_STRING)
                                && e.contains("sinceGameTime", Tag.TAG_LONG),
                        "Incomplete ruler data.");
                unique(rulers, e.getUUID("kingdom"),
                        new Ruler(e.getUUID("player"), e.getString("lastKnownName"), e.getLong("sinceGameTime")));
            }
        check(format >= 5 || !tag.contains("ledger"), "Unexpected ledger in legacy kingdom data.");
        Ledger ledger = Ledger.EMPTY;
        if (format >= 5) {
            check(tag.contains("ledger", Tag.TAG_COMPOUND), "Missing kingdom ledger.");
            CompoundTag l = tag.getCompound("ledger");
            check(l.contains("day", Tag.TAG_LONG), "Incomplete ledger.");
            var owed = new HashMap<UUID, Long>();
            for (Tag v : list(l, "owed")) {
                CompoundTag e = (CompoundTag) v;
                check(e.hasUUID("kingdom") && e.contains("deniers", Tag.TAG_LONG) && e.getLong("deniers") > 0,
                        "Invalid undelivered income.");
                unique(owed, e.getUUID("kingdom"), e.getLong("deniers"));
            }
            var offers = new HashSet<UUID>();
            for (Tag v : list(l, "peaceOffers"))
                check(offers.add(((CompoundTag) v).getUUID("war")), "Duplicate peace offer.");
            var joins = new HashMap<UUID, JoinRequest>();
            for (Tag v : list(l, "joinRequests")) {
                CompoundTag e = (CompoundTag) v;
                // Format 6 allows an NPC village's own application, which has no player.
                check(e.hasUUID("village") && e.hasUUID("kingdom") && (format >= 6 || e.hasUUID("player"))
                                && e.contains("playerName", Tag.TAG_STRING) && e.contains("gameTime", Tag.TAG_LONG),
                        "Incomplete join request.");
                unique(joins, e.getUUID("village"),
                        new JoinRequest(e.getUUID("kingdom"), e.hasUUID("player") ? e.getUUID("player") : null,
                                e.getString("playerName"), e.getLong("gameTime")));
            }
            ledger = new Ledger(l.getLong("day"), owed, offers, joins, -1, Map.of(), Map.of(), Map.of(), Map.of());
        }
        check(format >= 6 || !tag.contains("autonomy"), "Unexpected autonomy data in legacy kingdom data.");
        if (format >= 6) {
            check(tag.contains("autonomy", Tag.TAG_COMPOUND), "Missing kingdom autonomy data.");
            CompoundTag a = tag.getCompound("autonomy");
            check(a.contains("nextDecisionDay", Tag.TAG_LONG), "Incomplete autonomy data.");
            var truces = new HashMap<Pair, Long>();
            for (Tag v : list(a, "truces")) {
                CompoundTag e = (CompoundTag) v;
                check(e.hasUUID("a") && e.hasUUID("b") && e.contains("untilDay", Tag.TAG_LONG), "Incomplete truce.");
                Pair pair = Pair.of(e.getUUID("a"), e.getUUID("b"));
                check(pair.low().equals(e.getUUID("a")), "Truce pair is not in canonical order.");
                unique(truces, pair, e.getLong("untilDay"));
            }
            var raidDays = new HashMap<UUID, Long>();
            for (Tag v : list(a, "raidDays")) {
                CompoundTag e = (CompoundTag) v;
                check(e.hasUUID("target") && e.contains("day", Tag.TAG_LONG), "Incomplete raid day.");
                unique(raidDays, e.getUUID("target"), e.getLong("day"));
            }
            var clocks = new HashMap<UUID, WarClock>();
            for (Tag v : list(a, "warClocks")) {
                CompoundTag e = (CompoundTag) v;
                check(e.hasUUID("war") && e.contains("startDay", Tag.TAG_LONG)
                                && e.contains("lastProgressDay", Tag.TAG_LONG),
                        "Incomplete war clock.");
                unique(clocks, e.getUUID("war"), new WarClock(e.getLong("startDay"), e.getLong("lastProgressDay")));
            }
            var orders = new HashMap<UUID, RaidOrder>();
            for (Tag v : list(a, "orders")) {
                CompoundTag e = (CompoundTag) v;
                check(e.hasUUID("id") && e.hasUUID("kingdom") && e.hasUUID("target")
                                && e.contains("issuedGameTime", Tag.TAG_LONG) && e.contains("dueGameTime", Tag.TAG_LONG)
                                && e.contains("expiresGameTime", Tag.TAG_LONG),
                        "Incomplete raid order.");
                var o = new RaidOrder(e.getUUID("id"), e.getUUID("kingdom"), e.getUUID("target"),
                        e.hasUUID("source") ? e.getUUID("source") : null, e.hasUUID("war") ? e.getUUID("war") : null,
                        e.hasUUID("issuer") ? e.getUUID("issuer") : null, e.getLong("issuedGameTime"),
                        e.getLong("dueGameTime"), e.getLong("expiresGameTime"));
                unique(orders, o.id(), o);
            }
            ledger = ledger.withDecision(a.getLong("nextDecisionDay"))
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
        data.historyCommit = format >= 7 ? tag.getUUID("historyCommit") : null;
        if (format < 6)
            data.setDirty();
        data.legacyArchive = tag.getCompound("legacyArchive").copy();
        if (!data.legacyArchive.isEmpty())
            ConquestSavedData.load(data.legacyArchive, registries);
        return data;
    }

    private static CompoundTag identity(UUID id, UUID attacker, UUID target, Campaign context) {
        CompoundTag e = new CompoundTag();
        e.putUUID("id", id);
        e.putUUID("attacker", attacker);
        e.putUUID("target", target);
        if (context != null)
            e.put("campaign", writeCampaign(context));
        return e;
    }

    private static CompoundTag writeCampaign(Campaign c) {
        CompoundTag e = new CompoundTag();
        e.putUUID("attackerKingdom", c.attackerKingdom());
        e.putUUID("target", c.target());
        e.putLong("targetRevision", c.targetRevision());
        if (c.warId() != null)
            e.putUUID("warId", c.warId());
        return e;
    }

    private static Campaign readCampaign(CompoundTag e, boolean stage3) {
        check(!stage3 || !e.hasUUID("warId"), "Unexpected war context in pre-4.2A campaign.");
        return new Campaign(e.getUUID("attackerKingdom"), e.getUUID("target"), e.getLong("targetRevision"),
                e.hasUUID("warId") ? e.getUUID("warId")
                        : stage3   ? STAGE3_WAR
                                   : null);
    }

    private static Campaign context(CompoundTag e, boolean stage3) {
        return e.contains("campaign", Tag.TAG_COMPOUND) ? readCampaign(e.getCompound("campaign"), stage3) : null;
    }

    private static ListTag list(CompoundTag tag, String key) {
        check(tag.contains(key, Tag.TAG_LIST), "Missing kingdom list: " + key);
        ListTag list = (ListTag) tag.get(key);
        check(list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND, "Invalid kingdom list: " + key);
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
