package com.millenairekingdoms.kingdom;

import com.millenairekingdoms.conquest.ConquestSavedData;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Server-thread-only political registry and conquest ledger, one file per dimension.
 * A transaction constructs and validates an immutable snapshot before publishing it.
 * Membership, progress and the victory receipt therefore cannot be saved separately.
 */
public final class KingdomSavedData extends SavedData {
    public static final String NAME = "millenairekingdoms_kingdoms";
    /** Victories needed against another kingdom's normal village during one war. */
    public static final int REQUIRED_WINS = 2;
    /** Victories needed against a village without a kingdom; no war is involved. */
    public static final int INDEPENDENT_WINS = 1;
    /** Format 3: a null campaign warId now means an independent-village conquest.
     * Format 1/2 campaigns all had a null warId and needed two wins; they load as STAGE3_WAR.
     * Format 4: adds player rulers; older files simply have none.
     * Format 5: adds the stage-5 ledger (daily marker, undelivered income, peace offers, join requests); older files start empty.
     * Format 6: adds the stage-6.2 "autonomy" compound (next political decision day, truces, last conquest raid day per
     * target, war clocks, pending raid orders) and allows NPC village join applications without a player.
     * Older files start with no truces or orders; every ACTIVE war gets a clock starting on the stored ledger day. */
    /** Format7 adds only a causal history commit UUID; all history records remain in their separate archive.
     * Format1–6 remain readable. Without a new live historical transition, compatible saves still write format6. */
    public static final int FORMAT = 7;
    /** Marks pre-4.2A two-win campaigns. Never produced by campaign(), so their progress stays historical. */
    public static final UUID STAGE3_WAR = new UUID(0L, 0L);

    /** Share of a war's initial defender members (capital excluded) the attacker must hold before the capital opens. */
    public static final int CAPITAL_PERCENT = 60;
    public static final String PEACE_REASON = "OPERATOR_PEACE", CONQUERED_REASON = "CAPITAL_CONQUERED";
    /** Peace decided by a player ruler (or accepted by an NPC leader from a ruler's offer). */
    public static final String RULER_PEACE_REASON = "RULER_PEACE";
    /** Peace decided by an NPC-governed attacking kingdom (stage 6.2). */
    public static final String NPC_PEACE_REASON = "NPC_PEACE";
    private static final Set<String> PEACE_REASONS = Set.of(PEACE_REASON, RULER_PEACE_REASON, NPC_PEACE_REASON);
    /** Conquest raid days are kept this long for cooldowns (the configurable cooldown is at most 60 days). */
    public static final int RAID_DAY_MEMORY = 60;

    /** fallenInWar is null for a living kingdom. A fallen kingdom keeps its id, name and former
     * capital only as history: it is inactive, has no members and can never be reactivated. */
    public record Kingdom(UUID id, String name, UUID capital, boolean active, UUID fallenInWar) {
        public boolean fallen() { return fallenInWar != null; }
    }
    public enum WarStatus { ACTIVE, PEACE, CONQUERED }
    public record War(UUID id, UUID attackerKingdom, UUID defenderKingdom, long startGameTime,
                      WarStatus status, Set<UUID> initialDefenderMembers, String endReason) {
        public War { initialDefenderMembers = Set.copyOf(initialDefenderMembers); }
    }
    /** warId is the ACTIVE war for a kingdom-owned target, null for an independent target,
     * or STAGE3_WAR for archived pre-4.2A progress. Each war and each target membership
     * revision starts a separate campaign, so old progress never carries over. */
    public record Campaign(UUID attackerKingdom, UUID target, long targetRevision, UUID warId) {}
    public record Raid(UUID id, UUID attacker, UUID target, long startGameTime,
                       UUID initiator, Campaign campaign, boolean victoryRecorded) {
        Raid won() { return new Raid(id, attacker, target, startGameTime, initiator, campaign, true); }
    }
    public enum Outcome { WON, LOST, ABORTED, LEGACY_WON, INVALIDATED }
    public record Result(UUID raidId, UUID attacker, UUID target, Campaign campaign, Outcome outcome, boolean transferred) {}
    /** A kingdom's political ruler, identified only by the player's UUID; the name is for display while offline.
     * Independent of Millénaire's village ownerUUID: a ruler gains no local village rights. */
    public record Ruler(UUID player, String lastKnownName, long sinceGameTime) {}
    public static final int MAX_RULER_NAME = 64;
    /** A pending application to join a ruled kingdom; only that kingdom's ruler can accept it. player is the village owner
     * who applied, or null (with an empty name) when an unowned NPC village applied on its own (stage 6.2). */
    public record JoinRequest(UUID kingdom, UUID player, String playerName, long gameTime) {
        public boolean fromNpcVillage() { return player == null; }
    }

    /** Stage 6.2: an unordered pair of kingdoms (a truce binds both). */
    public record Pair(UUID low, UUID high) {
        public static Pair of(UUID a, UUID b) { return a.compareTo(b) <= 0 ? new Pair(a, b) : new Pair(b, a); }
        public boolean has(UUID kingdom) { return low.equals(kingdom) || high.equals(kingdom); }
    }
    /** Kingdom-calendar days (ledger day) of an ACTIVE war: when it began and when the attacker last won a conquest raid in it. */
    public record WarClock(long startDay, long lastProgressDay) {}
    /** A conquest raid ordered by a player ruler (issuer) or decided by an NPC-governed kingdom (issuer null), not started yet.
     * source null: a ready member village is chosen when the raid starts. warId null: the target is an independent village.
     * Times are game ticks, like Millénaire's own raid timers; an order still waiting at expiresGameTime is dropped. */
    public record RaidOrder(UUID id, UUID kingdom, UUID target, UUID source, UUID warId, UUID issuer,
                            long issuedGameTime, long dueGameTime, long expiresGameTime) {
        public boolean byNpc() { return issuer == null; }
    }

    /** Stage 5 bookkeeping. day: the last Minecraft day (dayTime / 24000) whose daily income and diplomacy ran, -1 before
     * the first. owed: income in deniers already earned but not yet placed in the capital's town hall storage (full or
     * unloaded chests); never counted twice. peaceOffers: ACTIVE wars whose defending ruler offered peace.
     * Stage 6.2 (format 6): nextDecisionDay of NPC politics (-1 until scheduled), truces (pair -> first day war is allowed
     * again), raidDays (target -> day its last conquest raid ended), warClocks (one per ACTIVE war) and pending raid orders.
     * The State constructor drops entries that no longer apply (ended wars, fallen kingdoms, villages that joined,
     * expired truces, orders whose target or war changed) and starts a clock for every new ACTIVE war. */
    private record Ledger(long day, Map<UUID, Long> owed, Set<UUID> peaceOffers, Map<UUID, JoinRequest> joinRequests,
                          long nextDecisionDay, Map<Pair, Long> truces, Map<UUID, Long> raidDays,
                          Map<UUID, WarClock> warClocks, Map<UUID, RaidOrder> orders) {
        static final Ledger EMPTY = new Ledger(-1, Map.of(), Set.of(), Map.of(), -1, Map.of(), Map.of(), Map.of(), Map.of());
        Ledger {
            owed = Map.copyOf(owed); peaceOffers = Set.copyOf(peaceOffers); joinRequests = Map.copyOf(joinRequests);
            truces = Map.copyOf(truces); raidDays = Map.copyOf(raidDays); warClocks = Map.copyOf(warClocks); orders = Map.copyOf(orders);
        }
        Ledger withDay(long d, Map<UUID, Long> o) { return new Ledger(d, o, peaceOffers, joinRequests, nextDecisionDay, truces, raidDays, warClocks, orders); }
        Ledger withOwed(Map<UUID, Long> o) { return withDay(day, o); }
        Ledger withOffers(Set<UUID> p) { return new Ledger(day, owed, p, joinRequests, nextDecisionDay, truces, raidDays, warClocks, orders); }
        Ledger withJoins(Map<UUID, JoinRequest> j) { return new Ledger(day, owed, peaceOffers, j, nextDecisionDay, truces, raidDays, warClocks, orders); }
        Ledger withDecision(long n) { return new Ledger(day, owed, peaceOffers, joinRequests, n, truces, raidDays, warClocks, orders); }
        Ledger withTruces(Map<Pair, Long> t) { return new Ledger(day, owed, peaceOffers, joinRequests, nextDecisionDay, t, raidDays, warClocks, orders); }
        Ledger withRaidDays(Map<UUID, Long> r) { return new Ledger(day, owed, peaceOffers, joinRequests, nextDecisionDay, truces, r, warClocks, orders); }
        Ledger withClocks(Map<UUID, WarClock> c) { return new Ledger(day, owed, peaceOffers, joinRequests, nextDecisionDay, truces, raidDays, c, orders); }
        Ledger withOrders(Map<UUID, RaidOrder> o) { return new Ledger(day, owed, peaceOffers, joinRequests, nextDecisionDay, truces, raidDays, warClocks, o); }
        /** Today on the kingdom calendar for new clocks, truces and cooldowns (0 before the first day ran). */
        long today() { return Math.max(day, 0); }
        Ledger pruned(Map<UUID, Kingdom> kingdoms, Map<UUID, UUID> membership, Map<UUID, War> wars) {
            check(day >= -1 && nextDecisionDay >= -1, "Invalid ledger day.");
            java.util.function.Predicate<UUID> living = k -> kingdoms.containsKey(k) && !kingdoms.get(k).fallen();
            Map<UUID, Long> o = new HashMap<>();
            owed.forEach((k, v) -> { check(v >= 0, "Negative undelivered income."); if (v > 0 && living.test(k)) o.put(k, v); });
            Set<UUID> offers = new HashSet<>();
            peaceOffers.forEach(w -> { if (wars.containsKey(w) && wars.get(w).status == WarStatus.ACTIVE) offers.add(w); });
            Map<UUID, JoinRequest> joins = new HashMap<>();
            joinRequests.forEach((v, r) -> {
                check(r.playerName != null && r.gameTime >= 0 && (r.player == null ? r.playerName.isEmpty()
                        : !r.playerName.isBlank() && r.playerName.length() <= MAX_RULER_NAME), "Invalid join request.");
                if (living.test(r.kingdom) && !membership.containsKey(v)) joins.put(v, r);
            });
            Map<Pair, Long> t = new HashMap<>();
            truces.forEach((pair, until) -> {
                check(pair.low != null && pair.high != null && pair.low.compareTo(pair.high) < 0 && until >= 0, "Invalid truce.");
                if (living.test(pair.low) && living.test(pair.high) && until > day) t.put(pair, until);
            });
            Map<UUID, Long> r = new HashMap<>();
            raidDays.forEach((target, d) -> { check(target != null && d >= 0, "Invalid raid day."); if (day < 0 || day - d <= RAID_DAY_MEMORY) r.put(target, d); });
            Map<UUID, WarClock> c = new HashMap<>();
            wars.forEach((id, w) -> {
                if (w.status != WarStatus.ACTIVE) return;
                WarClock clock = warClocks.get(id);
                if (clock != null) check(clock.startDay >= 0 && clock.lastProgressDay >= clock.startDay, "Invalid war clock.");
                c.put(id, clock != null ? clock : new WarClock(today(), today()));
            });
            Map<UUID, RaidOrder> orders = new HashMap<>();
            Set<UUID> orderingKingdoms = new HashSet<>(), targets = new HashSet<>();
            this.orders.forEach((id, x) -> {
                check(id.equals(x.id) && x.kingdom != null && x.target != null && !x.target.equals(x.source) && x.issuedGameTime >= 0
                        && x.dueGameTime >= x.issuedGameTime && x.expiresGameTime >= x.dueGameTime, "Invalid raid order.");
                UUID owner = membership.get(x.target);
                War war = x.warId == null ? null : wars.get(x.warId);
                boolean valid = living.test(x.kingdom) && kingdoms.get(x.kingdom).active && !x.kingdom.equals(owner)
                        && (x.source == null || x.kingdom.equals(membership.get(x.source)))
                        && (x.warId == null ? owner == null : war != null && war.status == WarStatus.ACTIVE
                        && war.attackerKingdom.equals(x.kingdom) && war.defenderKingdom.equals(owner));
                if (!valid) return;
                check(orderingKingdoms.add(x.kingdom) && targets.add(x.target), "Only one raid order per kingdom and per target.");
                orders.put(id, x);
            });
            return new Ledger(day, o, offers, joins, nextDecisionDay, t, r, c, orders);
        }
    }

    /** rulers is keyed by kingdom, so a kingdom can never have two rulers at once. */
    private record State(Map<UUID, Kingdom> kingdoms, Map<UUID, UUID> membership, Map<UUID, Long> revisions,
                         Map<Campaign, Integer> progress, Map<UUID, Raid> active,
                         Map<UUID, Result> receipts, Map<UUID, UUID> last, Map<UUID, War> wars, Map<UUID, Ruler> rulers,
                         Ledger ledger) {
        State {
            kingdoms = Map.copyOf(kingdoms); membership = Map.copyOf(membership); revisions = Map.copyOf(revisions);
            progress = Map.copyOf(progress); active = Map.copyOf(active); receipts = Map.copyOf(receipts); last = Map.copyOf(last);
            wars = Map.copyOf(wars); rulers = Map.copyOf(rulers);
            validate(kingdoms, membership, revisions, progress, active, receipts, last);
            validateWars(kingdoms, wars);
            validateCampaignWars(wars, progress, active, receipts);
            validateRulers(kingdoms, rulers);
            ledger = ledger.pruned(kingdoms, membership, wars);
        }
    }
    private State state = new State(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Ledger.EMPTY);
    private CompoundTag legacyArchive = new CompoundTag();
    // Only get(level) binds a live registry. Detached readers/tests never publish economic side effects.
    private ServerLevel royalRuntimeLevel;
    private UUID historyCommit;

    public static Factory<KingdomSavedData> factory() {
        return new Factory<>(KingdomSavedData::new, KingdomSavedData::load, null);
    }
    public static KingdomSavedData get(ServerLevel level) {
        var storage = level.getDataStorage();
        var data = storage.get(factory(), NAME);
        if (data != null) { data.royalRuntimeLevel = level; return data; }
        var folder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data");
        // DimensionDataStorage converts parse/IO failures to null. Never overwrite a damaged or newer file with an empty registry.
        check(Files.notExists(folder.resolve(NAME + ".dat")), "Kingdom data could not be loaded; restore a valid backup before continuing.");
        var legacy = storage.get(ConquestSavedData.factory(), ConquestSavedData.NAME);
        check(legacy != null || Files.notExists(folder.resolve(ConquestSavedData.NAME + ".dat")), "Legacy conquest data could not be loaded; migration refused.");
        data = legacy == null ? new KingdomSavedData() : fromLegacy(legacy.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        data.setDirty();
        storage.set(NAME, data);
        data.royalRuntimeLevel = level;
        return data;
    }

    public Collection<Kingdom> kingdoms() { return state.kingdoms.values(); }
    public Kingdom kingdom(UUID id) { return state.kingdoms.get(id); }
    public UUID owner(UUID village) { return state.membership.get(village); }
    public Set<UUID> members(UUID kingdom) {
        Set<UUID> result = new HashSet<>();
        state.membership.forEach((v, k) -> { if (k.equals(kingdom)) result.add(v); });
        return Set.copyOf(result);
    }
    /** Only living kingdoms have capitals; a fallen kingdom's former capital is a normal village. */
    public boolean isCapital(UUID village) { return state.kingdoms.values().stream().anyMatch(k -> !k.fallen() && k.capital.equals(village)); }
    public static int requiredWins(Campaign campaign) { return campaign.warId == null ? INDEPENDENT_WINS : REQUIRED_WINS; }
    public int wins(Campaign campaign) { return campaign == null ? 0 : state.progress.getOrDefault(campaign, 0); }
    public Map<Campaign, Integer> progress(UUID target) {
        Map<Campaign, Integer> result = new HashMap<>();
        state.progress.forEach((key, wins) -> { if (key.target.equals(target)) result.put(key, wins); });
        return Map.copyOf(result);
    }
    public long revision(UUID village) { return state.revisions.getOrDefault(village, 0L); }
    public Raid raid(UUID target) { return state.active.get(target); }
    public Collection<Raid> activeRaids() { return state.active.values(); }
    public Result receipt(UUID raidId) { return state.receipts.get(raidId); }
    public Result lastResult(UUID target) { UUID id = state.last.get(target); return id == null ? null : state.receipts.get(id); }
    public boolean involves(UUID village) {
        return state.active.values().stream().anyMatch(r -> r.attacker.equals(village) || r.target.equals(village));
    }

    public Kingdom create(String name, UUID capital) {
        name = name.strip();
        check(!name.isEmpty() && name.length() <= 64 && name.codePoints().noneMatch(Character::isISOControl), "Kingdom name must contain 1-64 printable characters.");
        String finalName = name;
        check(state.kingdoms.values().stream().noneMatch(k -> k.name.equalsIgnoreCase(finalName)), "Kingdom name already exists in this dimension.");
        check(owner(capital) == null && !involves(capital), "Capital is already assigned or involved in a conquest.");
        Kingdom kingdom = new Kingdom(UUID.randomUUID(), name, capital, true, null);
        var kingdoms = new HashMap<>(state.kingdoms); kingdoms.put(kingdom.id, kingdom);
        var membership = new HashMap<>(state.membership); membership.put(capital, kingdom.id);
        var revisions = new HashMap<>(state.revisions); revisions.put(capital, Math.addExact(revision(capital), 1));
        publish(new State(kingdoms, membership, revisions, state.progress, state.active, state.receipts, state.last, state.wars, state.rulers, state.ledger));
        return kingdom;
    }

    /** Administrative attachment is only for unassigned villages, never a transfer. */
    public void attach(UUID kingdomId, UUID village) {
        Kingdom kingdom = kingdom(kingdomId);
        check(kingdom != null && kingdom.active, "Unknown or inactive kingdom.");
        check(!involves(village), "Village is involved in a conquest.");
        if (kingdomId.equals(owner(village))) return;
        check(owner(village) == null && !isCapital(village), "Village already belongs to another kingdom; attachment refused.");
        var membership = new HashMap<>(state.membership); membership.put(village, kingdomId);
        var revisions = new HashMap<>(state.revisions); revisions.put(village, Math.addExact(revision(village), 1));
        publish(new State(state.kingdoms, membership, revisions, state.progress, state.active, state.receipts, state.last, state.wars, state.rulers, state.ledger));
    }
    public void setActive(UUID kingdomId, boolean active) {
        Kingdom kingdom = kingdom(kingdomId);
        check(kingdom != null, "Unknown kingdom.");
        check(!kingdom.fallen(), "This kingdom fell when its capital was conquered and cannot be used again.");
        check(activeWar(kingdomId) == null, "Make peace before changing a kingdom involved in an active war.");
        check(state.active.values().stream().noneMatch(r -> kingdomId.equals(owner(r.attacker)) || kingdomId.equals(owner(r.target))),
                "Kingdom is involved in an active conquest.");
        var kingdoms = new HashMap<>(state.kingdoms);
        kingdoms.put(kingdomId, new Kingdom(kingdom.id, kingdom.name, kingdom.capital, active, null));
        publish(new State(kingdoms, state.membership, state.revisions, state.progress, state.active, state.receipts, state.last, state.wars, state.rulers, state.ledger));
    }

    public Collection<War> wars() { return state.wars.values(); }
    public War war(UUID id) { return state.wars.get(id); }
    public War activeWar(UUID kingdomId) {
        return state.wars.values().stream().filter(w -> w.status == WarStatus.ACTIVE
                && (w.attackerKingdom.equals(kingdomId) || w.defenderKingdom.equals(kingdomId))).findFirst().orElse(null);
    }
    /** Independent-village conquests have no war context and do not block war or peace. */
    private boolean hasActiveConquest(UUID kingdomId) {
        return state.active.values().stream().filter(r -> r.campaign == null || r.campaign.warId != null)
                .anyMatch(r -> kingdomId.equals(owner(r.attacker))
                || kingdomId.equals(owner(r.target)) || (r.campaign != null && kingdomId.equals(r.campaign.attackerKingdom)));
    }
    public War declareWar(UUID attackerKingdom, UUID defenderKingdom, long startGameTime) {
        check(!attackerKingdom.equals(defenderKingdom), "A kingdom cannot declare war on itself.");
        check(startGameTime >= 0, "War start time cannot be negative.");
        for (UUID id : Set.of(attackerKingdom, defenderKingdom)) {
            check(kingdom(id) != null && kingdom(id).active, "Both kingdoms must exist and be active.");
            check(activeWar(id) == null, "A kingdom can participate in only one active war.");
            check(!hasActiveConquest(id), "Wait for existing conquest raids to finish before declaring war.");
        }
        var initial = new HashSet<>(members(defenderKingdom));
        initial.remove(kingdom(defenderKingdom).capital);
        var war = new War(UUID.randomUUID(), attackerKingdom, defenderKingdom, startGameTime, WarStatus.ACTIVE, initial, "");
        var wars = new HashMap<>(state.wars); wars.put(war.id, war); publishWars(wars);
        return war;
    }
    /** Peace never cancels NPC combat. Refuse while an involved conquest is active;
     * the operator can retry once the existing raid has completed its normal cleanup. */
    public War peace(UUID warId) { return peace(warId, PEACE_REASON); }
    /** Peace with the server's configured truce (stage 6.2). */
    public War peace(UUID warId, String reason) { return peace(warId, reason, defaultTruceDays.getAsInt()); }
    /** Ends the war and, in the same snapshot, starts a truce of truceDays kingdom days (0 = none) between both sides.
     * Pending orders of the war disappear with it; conquered villages keep their new allegiance. */
    public War peace(UUID warId, String reason, int truceDays) {
        check(PEACE_REASONS.contains(reason), "Invalid peace reason.");
        check(truceDays >= 0, "Invalid truce length.");
        var war = war(warId);
        check(war != null, "Unknown war.");
        if (war.status == WarStatus.PEACE) return war;
        check(war.status == WarStatus.ACTIVE, "This war already ended with a conquered capital.");
        check(!hasActiveConquest(war.attackerKingdom) && !hasActiveConquest(war.defenderKingdom),
                "Conquest raids are still active; retry peace after their normal cleanup.");
        var ended = new War(war.id, war.attackerKingdom, war.defenderKingdom, war.startGameTime,
                WarStatus.PEACE, war.initialDefenderMembers, reason);
        var wars = new HashMap<>(state.wars); wars.put(warId, ended);
        var truces = new HashMap<>(state.ledger.truces);
        if (truceDays > 0) truces.merge(Pair.of(war.attackerKingdom, war.defenderKingdom), state.ledger.today() + truceDays, Math::max);
        publish(new State(state.kingdoms, state.membership, state.revisions, state.progress,
                state.active, state.receipts, state.last, wars, state.rulers, state.ledger.withTruces(truces)));
        return ended;
    }
    /** Supplies the configured truce length; KingdomSavedData itself never reads the server config. */
    public static java.util.function.IntSupplier defaultTruceDays = () -> 10;
    private void publishWars(Map<UUID, War> wars) {
        publish(new State(state.kingdoms, state.membership, state.revisions, state.progress,
                state.active, state.receipts, state.last, wars, state.rulers, state.ledger));
    }

    public Ruler ruler(UUID kingdomId) { return state.rulers.get(kingdomId); }
    /** The kingdom this player rules, or null. */
    public UUID ruledBy(UUID player) {
        return state.rulers.entrySet().stream().filter(e -> e.getValue().player.equals(player)).map(Map.Entry::getKey).findFirst().orElse(null);
    }
    /** A living kingdom without a player ruler is under NPC political control. */
    public boolean npcGoverned(UUID kingdomId) {
        Kingdom kingdom = kingdom(kingdomId);
        return kingdom != null && !kingdom.fallen() && ruler(kingdomId) == null;
    }
    /** Whether actor (a player, or null for NPC automation) may make war or peace decisions for this kingdom.
     * Only a guard for future callers: declareWar/peace and operator commands do not consult it. */
    public boolean mayDecide(UUID kingdomId, UUID actor) {
        Kingdom kingdom = kingdom(kingdomId); Ruler ruler = ruler(kingdomId);
        return kingdom != null && kingdom.active && !kingdom.fallen() && (ruler == null ? actor == null : ruler.player.equals(actor));
    }
    /** Trusted internal/test use only: reputation and distance checks belong to the 5.1B caller.
     * Re-claiming one's own kingdom is a no-op. */
    public Ruler claimRule(UUID kingdomId, UUID player, String name, long gameTime) {
        Kingdom kingdom = kingdom(kingdomId);
        check(kingdom != null && kingdom.active && !kingdom.fallen(), "Only an active, living kingdom can be ruled.");
        check(player != null && gameTime >= 0 && name != null && !name.isBlank() && name.strip().length() <= MAX_RULER_NAME,
                "Invalid ruler identity, name or time.");
        Ruler current = ruler(kingdomId);
        if (current != null && current.player.equals(player)) return current;
        check(current == null, "This kingdom already has a ruler.");
        check(ruledBy(player) == null, "A player can rule only one kingdom.");
        Ruler ruler = new Ruler(player, name.strip(), gameTime);
        var rulers = new HashMap<>(state.rulers); rulers.put(kingdomId, ruler); publishRulers(rulers);
        return ruler;
    }
    public void abdicate(UUID kingdomId, UUID player) {
        Ruler current = ruler(kingdomId);
        check(current != null && current.player.equals(player), "Only the current ruler can abdicate.");
        var rulers = new HashMap<>(state.rulers); rulers.remove(kingdomId); publishRulers(rulers);
    }
    /** Operator maintenance; returns the removed ruler, or null when the kingdom had none. */
    public Ruler clearRuler(UUID kingdomId) {
        check(kingdom(kingdomId) != null, "Unknown kingdom.");
        Ruler current = ruler(kingdomId);
        if (current == null) return null;
        var rulers = new HashMap<>(state.rulers); rulers.remove(kingdomId); publishRulers(rulers);
        return current;
    }
    private void publishRulers(Map<UUID, Ruler> rulers) {
        // A mandate belongs to a tenure, not just a player UUID. Retire waiting orders in the same transaction
        // as the authority change; even an immediate abdicate/reclaim cannot revive the old mandate.
        Set<UUID> changed = new HashSet<>(state.rulers.keySet());
        changed.addAll(rulers.keySet());
        changed.removeIf(k -> java.util.Objects.equals(state.rulers.get(k), rulers.get(k)));
        var orders = new HashMap<>(state.ledger.orders);
        orders.values().removeIf(o -> changed.contains(o.kingdom));
        var joins = new HashMap<>(state.ledger.joinRequests);
        // Returning to NPC rule withdraws unresolved applications; villages may apply/join again under current rules.
        // Taking over from another ruler does not silently accept or discard that kingdom's applications.
        joins.values().removeIf(r -> changed.contains(r.kingdom) && !rulers.containsKey(r.kingdom));
        publish(new State(state.kingdoms, state.membership, state.revisions, state.progress,
                state.active, state.receipts, state.last, state.wars, rulers, state.ledger.withOrders(orders).withJoins(joins)));
    }

    /** Repairs unresolved applications left by older versions after abdication. Run on the server's daily step,
     * never inside the NBT reader: loading an old world preserves its data until the normal lifecycle resumes. */
    public int releaseUnruledJoinRequests() {
        var joins = new HashMap<>(state.ledger.joinRequests);
        joins.values().removeIf(r -> npcGoverned(r.kingdom));
        int released = state.ledger.joinRequests.size() - joins.size();
        if (released > 0) publishLedger(state.ledger.withJoins(joins));
        return released;
    }

    /** A player's own village becomes the capital of a new kingdom ruled by that player, in one snapshot.
     * Ownership, development and distance checks belong to the caller. */
    public Kingdom found(String name, UUID capital, UUID player, String playerName, long gameTime) {
        check(ruledBy(player) == null, "A player can rule only one kingdom.");
        // Validate the complete composite on a detached registry before publishing any live/economic/history effect.
        var proposal = new KingdomSavedData(); proposal.state = state;
        Kingdom kingdom = proposal.create(name, capital);
        proposal.claimRule(kingdom.id, player, playerName, gameTime);
        publish(proposal.state);
        return kingdom;
    }

    /** The last Minecraft day whose daily income and diplomacy ran, or -1 before the first. */
    public long day() { return state.ledger.day; }
    public long owed(UUID kingdomId) { return state.ledger.owed.getOrDefault(kingdomId, 0L); }
    public Map<UUID, Long> owed() { return state.ledger.owed; }
    /** Records day once and adds each kingdom's income to its undelivered balance in the same snapshot.
     * Returns false, changing nothing, when this day (or a later one) was already started. */
    public boolean startDay(long day, Map<UUID, Long> income) {
        check(day >= 0, "Invalid day.");
        if (day <= state.ledger.day) return false;
        var owed = new HashMap<>(state.ledger.owed);
        income.forEach((k, amount) -> {
            check(kingdom(k) != null && kingdom(k).active && !kingdom(k).fallen() && amount >= 0, "Income only for active, living kingdoms.");
            if (amount > 0) owed.merge(k, amount, Math::addExact);
        });
        publishLedger(state.ledger.withDay(day, owed));
        return true;
    }
    /** Called after amount deniers were actually placed in the capital's storage. */
    public void delivered(UUID kingdomId, long amount) {
        check(amount > 0 && amount <= owed(kingdomId), "Delivered more income than owed.");
        var owed = new HashMap<>(state.ledger.owed); owed.put(kingdomId, owed(kingdomId) - amount);
        publishLedger(state.ledger.withOwed(owed));
    }

    public boolean peaceOffered(UUID warId) { return state.ledger.peaceOffers.contains(warId); }
    /** The defender's ruler offers peace; the offer ends with the war. Idempotent. */
    public void offerPeace(UUID warId) {
        War war = war(warId);
        check(war != null && war.status == WarStatus.ACTIVE, "Peace can only be offered in an active war.");
        if (peaceOffered(warId)) return;
        var offers = new HashSet<>(state.ledger.peaceOffers); offers.add(warId);
        publishLedger(state.ledger.withOffers(offers));
    }

    public JoinRequest joinRequest(UUID village) { return state.ledger.joinRequests.get(village); }
    public Map<UUID, JoinRequest> joinRequests() { return state.ledger.joinRequests; }
    /** Stores (or replaces) a village's application; it disappears once the village joins any kingdom or the kingdom falls. */
    public void requestJoin(UUID village, UUID kingdomId, UUID player, String playerName, long gameTime) {
        Kingdom kingdom = kingdom(kingdomId);
        check(kingdom != null && kingdom.active && !kingdom.fallen(), "Unknown or inactive kingdom.");
        check(owner(village) == null && !involves(village), "Only an independent village outside any conquest can apply.");
        var joins = new HashMap<>(state.ledger.joinRequests);
        joins.put(village, new JoinRequest(kingdomId, player, playerName == null ? "" : playerName.strip(), gameTime));
        publishLedger(state.ledger.withJoins(joins));
    }
    public JoinRequest cancelJoin(UUID village) {
        JoinRequest removed = joinRequest(village);
        if (removed == null) return null;
        var joins = new HashMap<>(state.ledger.joinRequests); joins.remove(village);
        publishLedger(state.ledger.withJoins(joins));
        return removed;
    }
    private void publishLedger(Ledger ledger) {
        publish(new State(state.kingdoms, state.membership, state.revisions, state.progress,
                state.active, state.receipts, state.last, state.wars, state.rulers, ledger));
    }

    /** Stage 6.2: the day NPC politics next run, or -1 before they are scheduled. */
    public long nextDecisionDay() { return state.ledger.nextDecisionDay; }
    public void scheduleDecision(long day) {
        check(day >= 0, "Invalid decision day.");
        publishLedger(state.ledger.withDecision(day));
    }
    /** The first kingdom day on which a and b may declare war on each other again, or -1 without a truce. */
    public long truceUntil(UUID a, UUID b) { return state.ledger.truces.getOrDefault(Pair.of(a, b), -1L); }
    public Map<Pair, Long> truces() { return state.ledger.truces; }
    /** The kingdom day the last conquest raid against target ended, or -1. */
    public long lastRaidDay(UUID target) { return state.ledger.raidDays.getOrDefault(target, -1L); }
    public WarClock warClock(UUID warId) { return state.ledger.warClocks.get(warId); }
    public Collection<RaidOrder> orders() { return state.ledger.orders.values(); }
    public RaidOrder order(UUID id) { return state.ledger.orders.get(id); }
    public RaidOrder orderOf(UUID kingdomId) {
        return state.ledger.orders.values().stream().filter(o -> o.kingdom.equals(kingdomId)).findFirst().orElse(null);
    }
    public RaidOrder orderAgainst(UUID target) {
        return state.ledger.orders.values().stream().filter(o -> o.target.equals(target)).findFirst().orElse(null);
    }
    /** Conquest raids of this kingdom running now (as attacker kingdom of the campaign or owner of the attacking village). */
    public long activeConquests(UUID kingdomId) {
        return state.active.values().stream().filter(r -> r.campaign != null && kingdomId.equals(r.campaign.attackerKingdom)).count();
    }
    /** Villages that changed allegiance through victories of this war. */
    public int conquered(UUID warId) {
        return (int) state.receipts.values().stream().filter(r -> r.transferred && r.campaign != null && warId.equals(r.campaign.warId)).count();
    }
    /** Records a pending conquest order after the political checks it carries; one order per kingdom and per target. */
    public RaidOrder addOrder(UUID kingdomId, UUID target, UUID source, UUID issuer, long gameTime, long dueGameTime, long expiresGameTime) {
        check(gameTime >= 0 && dueGameTime >= gameTime && expiresGameTime >= dueGameTime, "Invalid order times.");
        check(orderOf(kingdomId) == null, "This kingdom already has a conquest raid waiting to start.");
        check(orderAgainst(target) == null && !involves(target), "Another conquest raid is already prepared against this village.");
        check(source == null || kingdomId.equals(owner(source)), "The raiding village must belong to the kingdom.");
        Campaign context = campaignFor(kingdomId, target);
        check(wins(context) < requiredWins(context), "This conquest campaign is complete.");
        var order = new RaidOrder(UUID.randomUUID(), kingdomId, target, source, context.warId, issuer, gameTime, dueGameTime, expiresGameTime);
        var orders = new HashMap<>(state.ledger.orders); orders.put(order.id, order);
        publishLedger(state.ledger.withOrders(orders));
        check(state.ledger.orders.containsKey(order.id), "The order is no longer valid.");
        return order;
    }
    public RaidOrder removeOrder(UUID orderId) {
        RaidOrder removed = order(orderId);
        if (removed == null) return null;
        var orders = new HashMap<>(state.ledger.orders); orders.remove(orderId);
        publishLedger(state.ledger.withOrders(orders));
        return removed;
    }

    /** ceil(initial * 60%) in integer arithmetic; 0 initial members open the capital immediately. */
    public static int capitalRequirement(War war) {
        return (war.initialDefenderMembers.size() * CAPITAL_PERCENT + 99) / 100;
    }
    /** Derived, never stored: initial defender members the attacker holds right now. Later
     * defender attachments are not in the fixed snapshot; a lost village stops counting. */
    public int capitalHeld(War war) {
        return (int) war.initialDefenderMembers.stream().filter(v -> war.attackerKingdom.equals(owner(v))).count();
    }
    public boolean capitalOpen(War war) {
        return war.status == WarStatus.ACTIVE && capitalHeld(war) >= capitalRequirement(war);
    }
    /** Capital victories of this war only; progress from an earlier war is never counted. */
    public int capitalWins(War war) {
        UUID capital = kingdom(war.defenderKingdom).capital;
        return state.progress.entrySet().stream().filter(e -> e.getKey().target.equals(capital) && war.id.equals(e.getKey().warId))
                .mapToInt(Map.Entry::getValue).max().orElse(0);
    }

    /** Central conquest rule check, used when a raid starts and again when its victory is processed. */
    public Campaign campaign(UUID attacker, UUID target) {
        UUID kingdomId = owner(attacker);
        check(kingdomId != null && kingdom(kingdomId).active, "Attacker must belong to an active kingdom.");
        return campaignFor(kingdomId, target);
    }
    /** The same political rules for a kingdom, before a raiding village is chosen (orders, GUI, NPC decisions). */
    public Campaign campaignFor(UUID kingdomId, UUID target) {
        check(kingdomId != null && kingdom(kingdomId) != null && kingdom(kingdomId).active, "Attacker must belong to an active kingdom.");
        UUID defender = owner(target);
        check(!kingdomId.equals(defender), "Target already belongs to the attacking kingdom.");
        if (defender == null) return new Campaign(kingdomId, target, revision(target), null);
        check(kingdom(defender).active, "Target kingdom is inactive.");
        War war = activeWar(kingdomId);
        check(war != null && war.attackerKingdom.equals(kingdomId) && war.defenderKingdom.equals(defender),
                "Conquering another kingdom's village requires an ACTIVE war declared by the attacking kingdom against that kingdom.");
        if (isCapital(target)) check(capitalOpen(war), "Capital is protected: the attacking kingdom must hold " + capitalRequirement(war)
                + " of the " + war.initialDefenderMembers.size() + " villages the defender had when the war began (currently "
                + capitalHeld(war) + ").");
        return new Campaign(kingdomId, target, revision(target), war.id);
    }
    /** All political start checks; ConquestService calls this before any Millénaire side effect. */
    public Campaign startable(UUID attacker, UUID target) {
        check(!attacker.equals(target) && !involves(attacker) && !involves(target), "Invalid or overlapping conquest raid.");
        var context = campaign(attacker, target);
        check(wins(context) < requiredWins(context), "This conquest campaign is complete.");
        return context;
    }
    public Raid begin(UUID attacker, UUID target, long startGameTime, UUID initiator) { return begin(attacker, target, startGameTime, initiator, null); }
    /** Starts the raid record; an order it fulfils is removed in the same snapshot, so a restart can never start it twice. */
    public Raid begin(UUID attacker, UUID target, long startGameTime, UUID initiator, UUID orderId) {
        check(startGameTime > 0, "Invalid conquest raid start time.");
        var context = startable(attacker, target);
        RaidOrder order = orderId == null ? null : order(orderId);
        check(orderId == null || order != null && order.target.equals(target) && order.kingdom.equals(context.attackerKingdom)
                && java.util.Objects.equals(order.warId, context.warId), "The raid order changed; it cannot start this raid.");
        Raid raid = new Raid(UUID.randomUUID(), attacker, target, startGameTime, initiator, context, false);
        var active = new HashMap<>(state.active); active.put(target, raid);
        var orders = new HashMap<>(state.ledger.orders); if (orderId != null) orders.remove(orderId);
        publish(new State(state.kingdoms, state.membership, state.revisions, state.progress, active, state.receipts, state.last, state.wars, state.rulers,
                state.ledger.withOrders(orders)));
        return raid;
    }

    /** A receipt, counter increment and optional membership transfer are one state replacement.
     * Replayed callbacks keep suppressing loot but cannot publish another result. */
    public boolean recordVictory(Raid raid) {
        Raid current = state.active.get(raid.target);
        if (current == null || !current.equals(raid) || current.victoryRecorded || state.receipts.containsKey(raid.id)) return false;
        var kingdoms = new HashMap<>(state.kingdoms);
        var membership = new HashMap<>(state.membership);
        var revisions = new HashMap<>(state.revisions);
        var progress = new HashMap<>(state.progress);
        var wars = new HashMap<>(state.wars);
        var rulers = new HashMap<>(state.rulers);
        var owed = new HashMap<>(state.ledger.owed);
        var clocks = new HashMap<>(state.ledger.warClocks);
        Outcome outcome = Outcome.LEGACY_WON;
        boolean transferred = false;
        if (raid.campaign != null) {
            boolean valid;
            try { valid = raid.campaign.equals(campaign(raid.attacker, raid.target)); }
            catch (IllegalArgumentException invalid) { valid = false; }
            outcome = valid ? Outcome.WON : Outcome.INVALIDATED;
            if (valid) {
                int count = Math.min(requiredWins(raid.campaign), wins(raid.campaign) + 1);
                progress.put(raid.campaign, count);
                WarClock clock = raid.campaign.warId == null ? null : state.ledger.warClocks.get(raid.campaign.warId);
                if (clock != null) clocks.put(raid.campaign.warId, new WarClock(clock.startDay, Math.max(clock.lastProgressDay, state.ledger.today())));
                if (count == requiredWins(raid.campaign)) {
                    // A fallen capital takes every village the defender still holds, in this same snapshot.
                    boolean capital = isCapital(raid.target);
                    Set<UUID> moved = capital ? members(owner(raid.target)) : Set.of(raid.target);
                    if (capital) collapse(war(raid.campaign.warId), kingdoms, wars, rulers, owed);
                    for (UUID village : moved) {
                        membership.put(village, raid.campaign.attackerKingdom);
                        revisions.put(village, Math.addExact(revision(village), 1));
                    }
                    transferred = true;
                }
            }
        }
        var active = new HashMap<>(state.active); active.put(raid.target, current.won());
        var receipts = new HashMap<>(state.receipts);
        receipts.put(raid.id, new Result(raid.id, raid.attacker, raid.target, raid.campaign, outcome, transferred));
        var ledger = state.ledger.withOwed(owed).withClocks(clocks);
        publish(new State(kingdoms, membership, revisions, progress, active, receipts, state.last, wars, rulers, ledger));
        return true;
    }
    /** The defender ends politically and loses its ruler in the same snapshot; its undelivered income goes to the victor,
     * so the former capital is never a separate tax centre again. Millénaire's own village records are never touched. */
    private void collapse(War war, Map<UUID, Kingdom> kingdoms, Map<UUID, War> wars, Map<UUID, Ruler> rulers, Map<UUID, Long> owed) {
        Kingdom loser = kingdom(war.defenderKingdom);
        kingdoms.put(loser.id, new Kingdom(loser.id, loser.name, loser.capital, false, war.id));
        rulers.remove(loser.id);
        Long spoils = owed.remove(loser.id);
        if (spoils != null) owed.merge(war.attackerKingdom, spoils, Math::addExact);
        wars.put(war.id, new War(war.id, war.attackerKingdom, war.defenderKingdom, war.startGameTime,
                WarStatus.CONQUERED, war.initialDefenderMembers, CONQUERED_REASON));
    }
    public void finish(Raid raid, Outcome otherwise) {
        Raid current = state.active.get(raid.target);
        if (current == null || !current.id.equals(raid.id)) return;
        check(otherwise == Outcome.LOST || otherwise == Outcome.ABORTED, "Invalid fallback outcome.");
        var receipts = new HashMap<>(state.receipts);
        receipts.putIfAbsent(raid.id, new Result(raid.id, raid.attacker, raid.target, raid.campaign, otherwise, false));
        var last = new HashMap<>(state.last); last.put(raid.target, raid.id);
        var active = new HashMap<>(state.active); active.remove(raid.target);
        // Stage 6.2: the cooldown before the next conquest raid on this village starts when this one ends.
        var raidDays = new HashMap<>(state.ledger.raidDays);
        if (raid.campaign != null) raidDays.put(raid.target, state.ledger.today());
        publish(new State(state.kingdoms, state.membership, state.revisions, state.progress, active, receipts, last, state.wars, state.rulers,
                state.ledger.withRaidDays(raidDays)));
    }
    private void publish(State next) {
        if (royalRuntimeLevel != null && (!state.rulers.equals(next.rulers) || !state.membership.equals(next.membership) || !state.kingdoms.equals(next.kingdoms))) {
            com.millenairekingdoms.construction.RoyalConstructionGovernance.beforePoliticalChange(royalRuntimeLevel, intent -> {
                Kingdom kingdom = next.kingdoms.get(intent.kingdom()); Ruler ruler = next.rulers.get(intent.kingdom());
                return kingdom == null || !kingdom.active || kingdom.fallen() || !kingdom.capital.equals(intent.capital())
                        || !intent.kingdom().equals(next.membership.get(intent.village()))
                        || !java.util.Objects.equals(state.rulers.get(intent.kingdom()), ruler)
                        || (ruler == null ? intent.actor() != null : !ruler.player.equals(intent.actor()) || ruler.sinceGameTime != intent.rulerSince());
            });
            com.millenairekingdoms.shipping.RoyalShipmentGovernance.beforePoliticalChange(royalRuntimeLevel, intent -> {
                Kingdom kingdom=next.kingdoms.get(intent.kingdom());Ruler ruler=next.rulers.get(intent.kingdom());
                return kingdom==null || !kingdom.active || kingdom.fallen() || !kingdom.capital.equals(intent.capital())
                        || !intent.kingdom().equals(next.membership.get(intent.source().village())) || !intent.kingdom().equals(next.membership.get(intent.destination().village()))
                        || !java.util.Objects.equals(state.rulers.get(intent.kingdom()),ruler)
                        || (ruler==null ? intent.actor()!=null : !ruler.player.equals(intent.actor()) || ruler.sinceGameTime!=intent.rulerSince());
            });
        }
        java.nio.file.Path historyPending = null;
        if (royalRuntimeLevel != null && (!state.kingdoms.equals(next.kingdoms) || !state.membership.equals(next.membership)
                || !state.wars.equals(next.wars) || !state.rulers.equals(next.rulers))) {
            var detached = new KingdomSavedData(); detached.state = next; detached.historyCommit = UUID.randomUUID();
            historyPending = com.millenairekingdoms.history.HistoryHooks.stagePolitical(royalRuntimeLevel,
                    save(new CompoundTag(), royalRuntimeLevel.registryAccess()), detached.save(new CompoundTag(), royalRuntimeLevel.registryAccess()));
            // Even when history I/O is unavailable, a later transition cannot reuse an earlier pending marker.
            historyCommit = detached.historyCommit;
        }
        state = next; setDirty();
        if (royalRuntimeLevel != null) com.millenairekingdoms.history.HistoryHooks.afterPolitical(royalRuntimeLevel, this, historyPending);
    }

    /** Old target-only wins are archived, never credited to an arbitrary new kingdom.
     * In-flight old conquests retain no-loot semantics and cleanup, but award no politics. */
    public static KingdomSavedData fromLegacy(CompoundTag legacy, HolderLookup.Provider registries) {
        var old = ConquestSavedData.load(legacy, registries);
        var data = new KingdomSavedData();
        data.legacyArchive = legacy.copy();
        var active = new HashMap<UUID, Raid>();
        var receipts = new HashMap<UUID, Result>();
        for (var r : old.activeRaids()) {
            var raid = new Raid(r.id(), r.attacker(), r.target(), r.startGameTime(), r.initiator(), null, r.victoryRecorded());
            active.put(raid.target, raid);
            if (raid.victoryRecorded) receipts.put(raid.id, new Result(raid.id, raid.attacker, raid.target, null, Outcome.LEGACY_WON, false));
        }
        data.publish(new State(Map.of(), Map.of(), Map.of(), Map.of(), active, receipts, Map.of(), Map.of(), Map.of(), Ledger.EMPTY));
        return data;
    }
    public int legacyWins(UUID target) {
        for (Tag value : legacyArchive.getList("progress", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            if (entry.getUUID("target").equals(target)) return entry.getInt("wins");
        }
        return 0;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", historyCommit == null ? 6 : FORMAT);
        if (historyCommit != null) tag.putUUID("historyCommit", historyCommit);
        tag.put("legacyArchive", legacyArchive.copy());
        ListTag kingdoms = new ListTag();
        state.kingdoms.values().forEach(k -> {
            CompoundTag e = new CompoundTag(); e.putUUID("id", k.id); e.putString("name", k.name);
            e.putUUID("capital", k.capital); e.putBoolean("active", k.active);
            if (k.fallen()) e.putUUID("fallenInWar", k.fallenInWar);
            kingdoms.add(e);
        });
        tag.put("kingdoms", kingdoms);
        ListTag membership = new ListTag();
        state.membership.forEach((v, k) -> { CompoundTag e = new CompoundTag(); e.putUUID("village", v); e.putUUID("kingdom", k); membership.add(e); });
        tag.put("membership", membership);
        ListTag revisions = new ListTag();
        state.revisions.forEach((v, r) -> { CompoundTag e = new CompoundTag(); e.putUUID("village", v); e.putLong("revision", r); revisions.add(e); });
        tag.put("revisions", revisions);
        ListTag progress = new ListTag();
        state.progress.forEach((c, w) -> { CompoundTag e = writeCampaign(c); e.putInt("wins", w); progress.add(e); });
        tag.put("progress", progress);
        ListTag active = new ListTag();
        state.active.values().forEach(r -> {
            CompoundTag e = identity(r.id, r.attacker, r.target, r.campaign);
            e.putLong("startGameTime", r.startGameTime); if (r.initiator != null) e.putUUID("initiator", r.initiator);
            e.putBoolean("victoryRecorded", r.victoryRecorded); active.add(e);
        });
        tag.put("active", active);
        ListTag receipts = new ListTag();
        state.receipts.values().forEach(r -> {
            CompoundTag e = identity(r.raidId, r.attacker, r.target, r.campaign);
            e.putString("outcome", r.outcome.name()); e.putBoolean("transferred", r.transferred); receipts.add(e);
        });
        tag.put("receipts", receipts);
        ListTag last = new ListTag();
        state.last.forEach((t, id) -> { CompoundTag e = new CompoundTag(); e.putUUID("target", t); e.putUUID("id", id); last.add(e); });
        tag.put("last", last);
        ListTag wars = new ListTag();
        state.wars.values().forEach(w -> {
            CompoundTag e = new CompoundTag(); e.putUUID("id", w.id);
            e.putUUID("attackerKingdom", w.attackerKingdom); e.putUUID("defenderKingdom", w.defenderKingdom);
            e.putLong("startGameTime", w.startGameTime); e.putString("status", w.status.name()); e.putString("endReason", w.endReason);
            ListTag initial = new ListTag();
            w.initialDefenderMembers.forEach(v -> { CompoundTag member = new CompoundTag(); member.putUUID("village", v); initial.add(member); });
            e.put("initialDefenderMembers", initial); wars.add(e);
        });
        tag.put("wars", wars);
        ListTag rulers = new ListTag();
        state.rulers.forEach((k, r) -> {
            CompoundTag e = new CompoundTag(); e.putUUID("kingdom", k); e.putUUID("player", r.player);
            e.putString("lastKnownName", r.lastKnownName); e.putLong("sinceGameTime", r.sinceGameTime); rulers.add(e);
        });
        tag.put("rulers", rulers);
        CompoundTag ledger = new CompoundTag();
        ledger.putLong("day", state.ledger.day);
        ListTag owed = new ListTag();
        state.ledger.owed.forEach((k, v) -> { CompoundTag e = new CompoundTag(); e.putUUID("kingdom", k); e.putLong("deniers", v); owed.add(e); });
        ledger.put("owed", owed);
        ListTag offers = new ListTag();
        state.ledger.peaceOffers.forEach(w -> { CompoundTag e = new CompoundTag(); e.putUUID("war", w); offers.add(e); });
        ledger.put("peaceOffers", offers);
        ListTag joins = new ListTag();
        state.ledger.joinRequests.forEach((v, r) -> {
            CompoundTag e = new CompoundTag(); e.putUUID("village", v); e.putUUID("kingdom", r.kingdom);
            if (r.player != null) e.putUUID("player", r.player);
            e.putString("playerName", r.playerName); e.putLong("gameTime", r.gameTime); joins.add(e);
        });
        ledger.put("joinRequests", joins);
        tag.put("ledger", ledger);
        CompoundTag autonomy = new CompoundTag();
        autonomy.putLong("nextDecisionDay", state.ledger.nextDecisionDay);
        ListTag truces = new ListTag();
        state.ledger.truces.forEach((p, until) -> { CompoundTag e = new CompoundTag(); e.putUUID("a", p.low); e.putUUID("b", p.high); e.putLong("untilDay", until); truces.add(e); });
        autonomy.put("truces", truces);
        ListTag raidDays = new ListTag();
        state.ledger.raidDays.forEach((t, d) -> { CompoundTag e = new CompoundTag(); e.putUUID("target", t); e.putLong("day", d); raidDays.add(e); });
        autonomy.put("raidDays", raidDays);
        ListTag clocks = new ListTag();
        state.ledger.warClocks.forEach((w, c) -> { CompoundTag e = new CompoundTag(); e.putUUID("war", w); e.putLong("startDay", c.startDay);
            e.putLong("lastProgressDay", c.lastProgressDay); clocks.add(e); });
        autonomy.put("warClocks", clocks);
        ListTag orders = new ListTag();
        state.ledger.orders.values().forEach(o -> {
            CompoundTag e = new CompoundTag(); e.putUUID("id", o.id); e.putUUID("kingdom", o.kingdom); e.putUUID("target", o.target);
            if (o.source != null) e.putUUID("source", o.source);
            if (o.warId != null) e.putUUID("war", o.warId);
            if (o.issuer != null) e.putUUID("issuer", o.issuer);
            e.putLong("issuedGameTime", o.issuedGameTime); e.putLong("dueGameTime", o.dueGameTime); e.putLong("expiresGameTime", o.expiresGameTime);
            orders.add(e);
        });
        autonomy.put("orders", orders);
        tag.put("autonomy", autonomy);
        return tag;
    }
    public static KingdomSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        int format = tag.getInt("format");
        check(format >= 1 && format <= FORMAT, "Unsupported kingdom data format.");
        check(format >= 7 ? tag.hasUUID("historyCommit") : !tag.contains("historyCommit"), "Invalid or disguised historical commit marker.");
        boolean stage3 = format < 3;
        // A format-1 file must not hide format-2 war records during migration.
        check(format != 1 || !tag.contains("wars"), "Unexpected wars in legacy kingdom data.");
        check(format >= 4 || !tag.contains("rulers"), "Unexpected rulers in legacy kingdom data.");
        var kingdoms = new HashMap<UUID, Kingdom>(); var membership = new HashMap<UUID, UUID>(); var revisions = new HashMap<UUID, Long>();
        var progress = new HashMap<Campaign, Integer>(); var active = new HashMap<UUID, Raid>(); var receipts = new HashMap<UUID, Result>(); var last = new HashMap<UUID, UUID>();
        for (Tag v : list(tag, "kingdoms")) { CompoundTag e = (CompoundTag) v;
            var k = new Kingdom(e.getUUID("id"), e.getString("name"), e.getUUID("capital"), e.getBoolean("active"),
                    e.hasUUID("fallenInWar") ? e.getUUID("fallenInWar") : null); unique(kingdoms, k.id, k); }
        for (Tag v : list(tag, "membership")) { CompoundTag e = (CompoundTag) v; unique(membership, e.getUUID("village"), e.getUUID("kingdom")); }
        for (Tag v : list(tag, "revisions")) { CompoundTag e = (CompoundTag) v; unique(revisions, e.getUUID("village"), e.getLong("revision")); }
        for (Tag v : list(tag, "progress")) { CompoundTag e = (CompoundTag) v; unique(progress, readCampaign(e, stage3), e.getInt("wins")); }
        for (Tag v : list(tag, "active")) { CompoundTag e = (CompoundTag) v;
            var r = new Raid(e.getUUID("id"), e.getUUID("attacker"), e.getUUID("target"), e.getLong("startGameTime"),
                    e.hasUUID("initiator") ? e.getUUID("initiator") : null, context(e, stage3), e.getBoolean("victoryRecorded")); unique(active, r.target, r); }
        for (Tag v : list(tag, "receipts")) { CompoundTag e = (CompoundTag) v;
            var r = new Result(e.getUUID("id"), e.getUUID("attacker"), e.getUUID("target"), context(e, stage3), Outcome.valueOf(e.getString("outcome")), e.getBoolean("transferred")); unique(receipts, r.raidId, r); }
        for (Tag v : list(tag, "last")) { CompoundTag e = (CompoundTag) v; unique(last, e.getUUID("target"), e.getUUID("id")); }
        var data = new KingdomSavedData();
        var wars = new HashMap<UUID, War>();
        if (format >= 2) for (Tag v : list(tag, "wars")) {
            CompoundTag e = (CompoundTag) v;
            check(e.contains("startGameTime", Tag.TAG_LONG) && e.contains("endReason", Tag.TAG_STRING), "Incomplete war data.");
            Set<UUID> initial = new HashSet<>();
            for (Tag member : list(e, "initialDefenderMembers"))
                check(initial.add(((CompoundTag) member).getUUID("village")), "Duplicate war snapshot member.");
            var war = new War(e.getUUID("id"), e.getUUID("attackerKingdom"), e.getUUID("defenderKingdom"),
                    e.getLong("startGameTime"), WarStatus.valueOf(e.getString("status")), initial, e.getString("endReason"));
            unique(wars, war.id, war);
        }
        var rulers = new HashMap<UUID, Ruler>();
        if (format >= 4) for (Tag v : list(tag, "rulers")) {
            CompoundTag e = (CompoundTag) v;
            check(e.hasUUID("kingdom") && e.hasUUID("player") && e.contains("lastKnownName", Tag.TAG_STRING)
                    && e.contains("sinceGameTime", Tag.TAG_LONG), "Incomplete ruler data.");
            unique(rulers, e.getUUID("kingdom"), new Ruler(e.getUUID("player"), e.getString("lastKnownName"), e.getLong("sinceGameTime")));
        }
        check(format >= 5 || !tag.contains("ledger"), "Unexpected ledger in legacy kingdom data.");
        Ledger ledger = Ledger.EMPTY;
        if (format >= 5) {
            check(tag.contains("ledger", Tag.TAG_COMPOUND), "Missing kingdom ledger.");
            CompoundTag l = tag.getCompound("ledger");
            check(l.contains("day", Tag.TAG_LONG), "Incomplete ledger.");
            var owed = new HashMap<UUID, Long>();
            for (Tag v : list(l, "owed")) { CompoundTag e = (CompoundTag) v;
                check(e.hasUUID("kingdom") && e.contains("deniers", Tag.TAG_LONG) && e.getLong("deniers") > 0, "Invalid undelivered income.");
                unique(owed, e.getUUID("kingdom"), e.getLong("deniers")); }
            var offers = new HashSet<UUID>();
            for (Tag v : list(l, "peaceOffers")) check(offers.add(((CompoundTag) v).getUUID("war")), "Duplicate peace offer.");
            var joins = new HashMap<UUID, JoinRequest>();
            for (Tag v : list(l, "joinRequests")) { CompoundTag e = (CompoundTag) v;
                // Format 6 allows an NPC village's own application, which has no player.
                check(e.hasUUID("village") && e.hasUUID("kingdom") && (format >= 6 || e.hasUUID("player")) && e.contains("playerName", Tag.TAG_STRING)
                        && e.contains("gameTime", Tag.TAG_LONG), "Incomplete join request.");
                unique(joins, e.getUUID("village"), new JoinRequest(e.getUUID("kingdom"), e.hasUUID("player") ? e.getUUID("player") : null,
                        e.getString("playerName"), e.getLong("gameTime"))); }
            ledger = new Ledger(l.getLong("day"), owed, offers, joins, -1, Map.of(), Map.of(), Map.of(), Map.of());
        }
        check(format >= 6 || !tag.contains("autonomy"), "Unexpected autonomy data in legacy kingdom data.");
        if (format >= 6) {
            check(tag.contains("autonomy", Tag.TAG_COMPOUND), "Missing kingdom autonomy data.");
            CompoundTag a = tag.getCompound("autonomy");
            check(a.contains("nextDecisionDay", Tag.TAG_LONG), "Incomplete autonomy data.");
            var truces = new HashMap<Pair, Long>();
            for (Tag v : list(a, "truces")) { CompoundTag e = (CompoundTag) v;
                check(e.hasUUID("a") && e.hasUUID("b") && e.contains("untilDay", Tag.TAG_LONG), "Incomplete truce.");
                Pair pair = Pair.of(e.getUUID("a"), e.getUUID("b"));
                check(pair.low.equals(e.getUUID("a")), "Truce pair is not in canonical order.");
                unique(truces, pair, e.getLong("untilDay")); }
            var raidDays = new HashMap<UUID, Long>();
            for (Tag v : list(a, "raidDays")) { CompoundTag e = (CompoundTag) v;
                check(e.hasUUID("target") && e.contains("day", Tag.TAG_LONG), "Incomplete raid day.");
                unique(raidDays, e.getUUID("target"), e.getLong("day")); }
            var clocks = new HashMap<UUID, WarClock>();
            for (Tag v : list(a, "warClocks")) { CompoundTag e = (CompoundTag) v;
                check(e.hasUUID("war") && e.contains("startDay", Tag.TAG_LONG) && e.contains("lastProgressDay", Tag.TAG_LONG), "Incomplete war clock.");
                unique(clocks, e.getUUID("war"), new WarClock(e.getLong("startDay"), e.getLong("lastProgressDay"))); }
            var orders = new HashMap<UUID, RaidOrder>();
            for (Tag v : list(a, "orders")) { CompoundTag e = (CompoundTag) v;
                check(e.hasUUID("id") && e.hasUUID("kingdom") && e.hasUUID("target") && e.contains("issuedGameTime", Tag.TAG_LONG)
                        && e.contains("dueGameTime", Tag.TAG_LONG) && e.contains("expiresGameTime", Tag.TAG_LONG), "Incomplete raid order.");
                var o = new RaidOrder(e.getUUID("id"), e.getUUID("kingdom"), e.getUUID("target"), e.hasUUID("source") ? e.getUUID("source") : null,
                        e.hasUUID("war") ? e.getUUID("war") : null, e.hasUUID("issuer") ? e.getUUID("issuer") : null,
                        e.getLong("issuedGameTime"), e.getLong("dueGameTime"), e.getLong("expiresGameTime"));
                unique(orders, o.id, o); }
            ledger = ledger.withDecision(a.getLong("nextDecisionDay")).withTruces(truces).withRaidDays(raidDays).withClocks(clocks).withOrders(orders);
        }
        data.state = new State(kingdoms, membership, revisions, progress, active, receipts, last, wars, rulers, ledger);
        // Pruning is for live transitions only; a stored entry that no longer applies means damaged data.
        // Older formats had no war clocks: every ACTIVE war starts its clock on the stored ledger day.
        Ledger expected = format < 6 ? ledger.withClocks(data.state.ledger.warClocks) : ledger;
        check(data.state.ledger.equals(expected), "Ledger refers to ended wars, fallen kingdoms or member villages.");
        data.historyCommit = format >= 7 ? tag.getUUID("historyCommit") : null;
        if (format < 6) data.setDirty();
        data.legacyArchive = tag.getCompound("legacyArchive").copy();
        if (!data.legacyArchive.isEmpty()) ConquestSavedData.load(data.legacyArchive, registries);
        return data;
    }
    private static CompoundTag identity(UUID id, UUID attacker, UUID target, Campaign context) {
        CompoundTag e = new CompoundTag(); e.putUUID("id", id); e.putUUID("attacker", attacker); e.putUUID("target", target);
        if (context != null) e.put("campaign", writeCampaign(context)); return e;
    }
    private static CompoundTag writeCampaign(Campaign c) {
        CompoundTag e = new CompoundTag(); e.putUUID("attackerKingdom", c.attackerKingdom); e.putUUID("target", c.target);
        e.putLong("targetRevision", c.targetRevision); if (c.warId != null) e.putUUID("warId", c.warId); return e;
    }
    private static Campaign readCampaign(CompoundTag e, boolean stage3) {
        check(!stage3 || !e.hasUUID("warId"), "Unexpected war context in pre-4.2A campaign.");
        return new Campaign(e.getUUID("attackerKingdom"), e.getUUID("target"), e.getLong("targetRevision"),
                e.hasUUID("warId") ? e.getUUID("warId") : stage3 ? STAGE3_WAR : null);
    }
    private static Campaign context(CompoundTag e, boolean stage3) {
        return e.contains("campaign", Tag.TAG_COMPOUND) ? readCampaign(e.getCompound("campaign"), stage3) : null;
    }
    private static ListTag list(CompoundTag tag, String key) {
        check(tag.contains(key, Tag.TAG_LIST), "Missing kingdom list: " + key);
        ListTag list = (ListTag) tag.get(key);
        check(list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND, "Invalid kingdom list: " + key); return list;
    }
    private static <K, V> void unique(Map<K, V> map, K key, V value) { check(map.putIfAbsent(key, value) == null, "Duplicate kingdom data key."); }
    private static void check(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }

    /** A ruler belongs to an existing kingdom that has not fallen; one player rules at most one kingdom. */
    private static void validateRulers(Map<UUID, Kingdom> kingdoms, Map<UUID, Ruler> rulers) {
        Set<UUID> players = new HashSet<>();
        rulers.forEach((k, r) -> check(kingdoms.containsKey(k) && !kingdoms.get(k).fallen() && r.player != null && r.lastKnownName != null
                && !r.lastKnownName.isBlank() && r.lastKnownName.length() <= MAX_RULER_NAME && r.sinceGameTime >= 0
                && players.add(r.player), "Invalid kingdom ruler."));
    }

    private static void validateWars(Map<UUID, Kingdom> kingdoms, Map<UUID, War> wars) {
        Set<UUID> participants = new HashSet<>();
        wars.forEach((id, w) -> {
            check(id.equals(w.id) && !id.equals(STAGE3_WAR) && !w.attackerKingdom.equals(w.defenderKingdom) && w.startGameTime >= 0
                    && kingdoms.containsKey(w.attackerKingdom) && kingdoms.containsKey(w.defenderKingdom), "Invalid war identity/participants.");
            check(!w.initialDefenderMembers.contains(kingdoms.get(w.defenderKingdom).capital), "Capital cannot be a member of the initial war snapshot.");
            if (w.status == WarStatus.ACTIVE) {
                check(w.endReason.isEmpty() && kingdoms.get(w.attackerKingdom).active && kingdoms.get(w.defenderKingdom).active,
                        "Invalid active war state.");
                check(participants.add(w.attackerKingdom) && participants.add(w.defenderKingdom), "Overlapping active wars.");
            } else if (w.status == WarStatus.PEACE) check(PEACE_REASONS.contains(w.endReason), "Invalid peace end reason.");
            else check(w.endReason.equals(CONQUERED_REASON) && id.equals(kingdoms.get(w.defenderKingdom).fallenInWar),
                    "Conquered war must name the kingdom that fell in it.");
        });
        // A kingdom falls exactly once, in a recorded CONQUERED war where it was the defender.
        kingdoms.values().stream().filter(Kingdom::fallen).forEach(k -> {
            War w = wars.get(k.fallenInWar);
            check(!k.active && w != null && w.status == WarStatus.CONQUERED && w.defenderKingdom.equals(k.id), "Invalid fallen kingdom.");
        });
    }

    /** A war-bound campaign must name a recorded war of the same attacking kingdom;
     * an unfinished war raid additionally requires that war not to have ended in peace.
     * Raids still running when a capital fell close normally and award nothing (INVALIDATED). */
    private static void validateCampaignWars(Map<UUID, War> wars, Map<Campaign, Integer> progress,
                                             Map<UUID, Raid> active, Map<UUID, Result> receipts) {
        java.util.function.Consumer<Campaign> consistent = c -> {
            if (c == null || c.warId == null || c.warId.equals(STAGE3_WAR)) return;
            War war = wars.get(c.warId);
            check(war != null && war.attackerKingdom.equals(c.attackerKingdom), "Conquest progress does not match its war.");
        };
        progress.keySet().forEach(consistent);
        receipts.values().forEach(r -> consistent.accept(r.campaign));
        active.values().forEach(r -> {
            consistent.accept(r.campaign);
            check(r.campaign == null || r.campaign.warId == null || r.campaign.warId.equals(STAGE3_WAR)
                    || wars.get(r.campaign.warId).status != WarStatus.PEACE, "Active conquest raid belongs to an ended war.");
        });
    }

    private static void validate(Map<UUID, Kingdom> kingdoms, Map<UUID, UUID> membership, Map<UUID, Long> revisions,
                                 Map<Campaign, Integer> progress, Map<UUID, Raid> active, Map<UUID, Result> receipts, Map<UUID, UUID> last) {
        Set<String> names = new HashSet<>();
        kingdoms.forEach((id, k) -> check(id.equals(k.id) && !k.name.isBlank() && k.name.length() <= 64
                && names.add(k.name.toLowerCase(java.util.Locale.ROOT)) && (k.fallen() || id.equals(membership.get(k.capital))),
                "Invalid kingdom/capital membership."));
        membership.forEach((v, k) -> check(kingdoms.containsKey(k) && !kingdoms.get(k).fallen() && revisions.getOrDefault(v, 0L) > 0,
                "Invalid membership/revision."));
        revisions.forEach((v, r) -> check(r > 0, "Invalid target revision."));
        progress.forEach((c, w) -> check(kingdoms.containsKey(c.attackerKingdom) && c.targetRevision >= 0
                && c.targetRevision <= revisions.getOrDefault(c.target, 0L) && w > 0 && w <= requiredWins(c), "Invalid conquest progress."));
        Set<UUID> involved = new HashSet<>(); Set<UUID> ids = new HashSet<>();
        active.forEach((t, r) -> {
            check(t.equals(r.target) && r.startGameTime > 0 && involved.add(r.attacker) && involved.add(r.target) && ids.add(r.id), "Invalid/overlapping raid.");
            check(r.campaign == null || (r.target.equals(r.campaign.target) && kingdoms.containsKey(r.campaign.attackerKingdom)), "Invalid raid campaign.");
            check(r.victoryRecorded == receipts.containsKey(r.id), "Raid result marker and receipt differ.");
            if (r.victoryRecorded) { var receipt = receipts.get(r.id); check(receipt.target.equals(r.target) && receipt.attacker.equals(r.attacker)
                    && java.util.Objects.equals(receipt.campaign, r.campaign), "Raid receipt identity differs."); }
        });
        receipts.forEach((id, r) -> {
            check(id.equals(r.raidId) && !r.attacker.equals(r.target), "Invalid receipt identity.");
            check(r.campaign == null || (r.target.equals(r.campaign.target) && kingdoms.containsKey(r.campaign.attackerKingdom)), "Invalid receipt campaign.");
            check(r.outcome != Outcome.WON || (r.campaign != null && progress.getOrDefault(r.campaign, 0) > 0), "Victory without progress.");
            check(!r.transferred || (r.outcome == Outcome.WON && progress.getOrDefault(r.campaign, 0) == requiredWins(r.campaign)), "Transfer without completed campaign.");
        });
        last.forEach((t, id) -> check(receipts.containsKey(id) && receipts.get(id).target.equals(t), "Invalid last-result index."));
    }
}
