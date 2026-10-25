package com.millenairekingdoms.politics;

import com.millenairekingdoms.conquest.ConquestService;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Kingdom;
import com.millenairekingdoms.kingdom.KingdomSavedData.War;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.millenairekingdoms.util.KingdomException;
import org.millenaire.ReputationConstants;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageRelations;
import org.millenaire.village.VillageSavedData;


/** Player politics. Every rule is checked here on the server; chat buttons only run these same commands.
 * Reads Millénaire reputation and relations, never writes them, and never touches village ownership. */
public final class PoliticsService {
    /** Millénaire's "friend of the village": request a war from an NPC leader, and the floor for a ruler's decisions. */
    public static final int WAR_REQUEST_REPUTATION = ReputationConstants.FRIEND_OF_THE_VILLAGE;
    public static final int DECISION_REPUTATION = ReputationConstants.FRIEND_OF_THE_VILLAGE;
    /** Millénaire's "one of us": take over a kingdom's rule. */
    public static final int CLAIM_REPUTATION = ReputationConstants.ONE_OF_US;
    public static final double CAPITAL_REACH = 64.0;
    /** An NPC leader grants a war request against a capital it regards as BAD (-30) or worse, and an NPC attacker accepts
     * a defending ruler's peace offer only once relations are better than that. */
    public static final int HOSTILE_RELATION = VillageRelations.BAD;
    private static final String TAG = "[Kingdoms] ";

    private PoliticsService() {}

    public static Village village(ServerLevel level, UUID id) {
        return VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(id));
    }
    public static Village capital(ServerLevel level, Kingdom kingdom) {
        Village capital = village(level, kingdom.capital());
        check(capital != null, "capital_unknown", "The capital village of %s is not loaded in Millénaire's records.", kingdom.name());
        return capital;
    }
    /** Millénaire's combined (village + culture) reputation of a player at the kingdom's capital, as its chief screen shows it. */
    public static int reputation(ServerLevel level, Kingdom kingdom, UUID player) {
        return capital(level, kingdom).getCombinedReputation(level, player);
    }
    /** The relation of a's capital towards b's capital in Millénaire's own -100..100 relation table. */
    public static int relation(ServerLevel level, Kingdom a, Kingdom b) {
        return capital(level, a).getRelation(new VillageId(b.capital()));
    }
    /** Same distance rule as Millénaire's village interaction: within 64 blocks of the centre block. */
    public static boolean near(ServerPlayer player, BlockPos center) {
        return player.distanceToSqr(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5) <= CAPITAL_REACH * CAPITAL_REACH;
    }
    /** The living kingdom whose capital centre is nearest the player within reach, or null. */
    public static Kingdom nearbyCapital(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        return data(level).kingdoms().stream().filter(k -> !k.fallen()).filter(k -> {
            Village v = village(level, k.capital()); return v != null && near(player, v.getCenter());
        }).min(Comparator.comparingDouble(k -> village(level, k.capital()).getCenter().distToCenterSqr(player.position()))).orElse(null);
    }
    public static Kingdom resolveKingdom(KingdomSavedData data, String text) {
        String key = text.strip();
        try { Kingdom byId = data.kingdom(UUID.fromString(key)); if (byId != null) return byId; }
        catch (IllegalArgumentException notUuid) { /* fall back to the name */ }
        return data.kingdoms().stream().filter(k -> !k.fallen() && k.name().equalsIgnoreCase(key)).findFirst()
                .orElseThrow(() -> new KingdomException("millenairekingdoms.politics.unknown_kingdom", "Unknown kingdom: %s", key));
    }

    public static KingdomSavedData.Ruler claim(ServerPlayer player) {
        ServerLevel level = player.serverLevel(); var data = data(level);
        Kingdom kingdom = nearbyCapital(player);
        check(kingdom != null, "claim_near", "Stand within 64 blocks of a kingdom's capital centre to claim its rule.");
        int reputation = reputation(level, kingdom, player.getUUID());
        check(reputation >= CLAIM_REPUTATION, "claim_reputation", "Claiming %s needs reputation %s at its capital; yours is %s.",
                kingdom.name(), CLAIM_REPUTATION, reputation);
        return data.claimRule(kingdom.id(), player.getUUID(), player.getGameProfile().getName(), level.getGameTime());
    }

    public static Kingdom abdicate(ServerPlayer player) {
        var data = data(player.serverLevel());
        UUID kingdomId = data.ruledBy(player.getUUID());
        check(kingdomId != null, "no_kingdom", "You do not rule a kingdom.");
        data.abdicate(kingdomId, player.getUUID());
        return data.kingdom(kingdomId);
    }

    /** A non-ruling player asks the NPC leader of the nearby capital to declare war. The leader decides from Millénaire's relations. */
    public static War requestWar(ServerPlayer player, String targetText) {
        ServerLevel level = player.serverLevel(); var data = data(level);
        Kingdom kingdom = nearbyCapital(player);
        check(kingdom != null, "petition_near", "Stand within 64 blocks of a capital to petition its leader.");
        check(kingdom.active(), "inactive", "%s is inactive.", kingdom.name());
        var ruler = data.ruler(kingdom.id());
        check(data.npcGoverned(kingdom.id()), "ruler_decides", "War decisions of %s belong to its ruler %s.",
                kingdom.name(), ruler == null ? "" : ruler.lastKnownName());
        Kingdom target = resolveKingdom(data, targetText);
        check(!target.id().equals(kingdom.id()), "war_self", "A kingdom cannot declare war on itself.");
        check(!target.fallen() && target.active(), "not_active", "%s is not an active kingdom.", target.name());
        int reputation = reputation(level, kingdom, player.getUUID());
        check(reputation >= WAR_REQUEST_REPUTATION, "friends_only", "The leader of %s only listens to friends of the village (reputation %s); yours is %s.",
                kingdom.name(), WAR_REQUEST_REPUTATION, reputation);
        int relation = relation(level, kingdom, target);
        check(relation <= HOSTILE_RELATION, "not_hostile", "The leader of %s refuses: relations with %s are %s; war needs %s or worse.",
                kingdom.name(), target.name(), relation, HOSTILE_RELATION);
        checkTruce(data, kingdom, target);
        War war = data.declareWar(kingdom.id(), target.id(), level.getGameTime());
        notifyRuler(level, target.id(), com.millenairekingdoms.localization.PublicText.of("%s%s declared war on your kingdom.", TAG, kingdom.name()).withStyle(ChatFormatting.RED));
        return war;
    }

    public static War declareWar(ServerPlayer player, String targetText) {
        ServerLevel level = player.serverLevel(); var data = data(level);
        Kingdom kingdom = ruledKingdom(player);
        Kingdom target = resolveKingdom(data, targetText);
        check(!target.fallen(), "fallen", "%s has fallen.", target.name());
        checkTruce(data, kingdom, target);
        War war = data.declareWar(kingdom.id(), target.id(), level.getGameTime());
        notifyRuler(level, target.id(), com.millenairekingdoms.localization.PublicText.of("%s%s declared war on your kingdom.", TAG, kingdom.name()).withStyle(ChatFormatting.RED));
        return war;
    }

    /** Stage 6.2: a peace binds both kingdoms for the configured truce; operator commands are not bound. */
    static void checkTruce(KingdomSavedData data, Kingdom a, Kingdom b) {
        long until = data.truceUntil(a.id(), b.id());
        com.millenairekingdoms.util.KingdomException.check(until < 0, "millenairekingdoms.war.truce",
                "%s and %s are bound by a truce until day %s.", a.name(), b.name(), until);
    }

    public enum PeaceResult { MADE, OFFERED }
    /** The attacker's ruler can always end its own war. The defender's ruler cannot: an NPC attacker accepts once relations
     * are better than BAD, a ruled attacker receives an offer that its ruler accepts with the same command. */
    public static PeaceResult peace(ServerPlayer player) {
        ServerLevel level = player.serverLevel(); var data = data(level);
        Kingdom kingdom = ruledKingdom(player);
        War war = data.activeWar(kingdom.id());
        check(war != null, "not_at_war", "%s is not at war.", kingdom.name());
        Kingdom attacker = data.kingdom(war.attackerKingdom()), defender = data.kingdom(war.defenderKingdom());
        if (attacker.id().equals(kingdom.id())) {
            data.peace(war.id(), KingdomSavedData.RULER_PEACE_REASON);
            DiplomacyService.treaty(level, data, war);
            notifyRuler(level, defender.id(), com.millenairekingdoms.localization.PublicText.of("%s%s made peace with your kingdom.", TAG, attacker.name()).withStyle(ChatFormatting.GREEN));
            return PeaceResult.MADE;
        }
        if (data.npcGoverned(attacker.id())) {
            int relation = relation(level, attacker, defender);
            check(relation > HOSTILE_RELATION, "peace_refused", "The leader of %s refuses peace while relations are %s; they must be better than %s.",
                    attacker.name(), relation, HOSTILE_RELATION);
            data.peace(war.id(), KingdomSavedData.RULER_PEACE_REASON);
            DiplomacyService.treaty(level, data, war);
            return PeaceResult.MADE;
        }
        data.offerPeace(war.id());
        notifyRuler(level, attacker.id(), com.millenairekingdoms.localization.PublicText.of("%s%s offers peace. ", TAG, defender.name()).withStyle(ChatFormatting.GOLD)
                .append(button(com.millenairekingdoms.localization.PublicText.of("[Accept peace]"), "/kingdom war peace", com.millenairekingdoms.localization.PublicText.of("End the war with %s", defender.name()))));
        return PeaceResult.OFFERED;
    }

    /** Villages join only with good relations (Millénaire GOOD, 50) between the kingdom's capital and the village. */
    public static final int JOIN_RELATION = VillageRelations.GOOD;
    /** A player's own village can found a kingdom once its town hall stands at level 3 (two completed upgrades). */
    public static final int FOUND_LEVEL = 3;
    public enum JoinResult { JOINED, REQUESTED }

    /** Brings the independent village the player stands in (within 64 blocks of its centre) into a kingdom, peacefully.
     * Village side: an owned village needs its owner; an NPC village consents at relation 50 with the capital.
     * Kingdom side: a ruler decides (an owner's application waits for the ruler); an NPC leader needs reputation 8192
     * and relation 50. Millénaire's culture, buildings, owner and local chief stay exactly as they are. */
    public static JoinResult join(ServerPlayer player, String kingdomText) {
        ServerLevel level = player.serverLevel(); var data = data(level);
        Village village = nearbyIndependentVillage(player);
        check(village != null, "join_near", "Stand within 64 blocks of the centre of the independent village that should join.");
        UUID villageId = village.getId().uuid();
        Kingdom kingdom = resolveKingdom(data, kingdomText);
        check(!kingdom.fallen() && kingdom.active(), "not_active", "%s is not an active kingdom.", kingdom.name());
        Village capital = capital(level, kingdom);
        int relation = capital.getRelation(village.getId());
        boolean owned = village.getOwnerUUID() != null;
        if (owned) check(village.isControlledBy(player.getUUID()), "join_owner_only", "Only %s, the owner of %s, can bring it into a kingdom.",
                village.getOwnerName(), name(village));
        var ruler = data.ruler(kingdom.id());
        if (ruler != null && !ruler.player().equals(player.getUUID())) {
            check(owned, "join_ruler_only", "Only the ruler of %s can bring in a village that is not yours.", kingdom.name());
            data.requestJoin(villageId, kingdom.id(), player.getUUID(), player.getGameProfile().getName(), level.getGameTime());
            notifyRuler(level, kingdom.id(), com.millenairekingdoms.localization.PublicText.of("%s%s asks to bring %s into %s. ", TAG, player.getGameProfile().getName(), name(village), kingdom.name()).withStyle(ChatFormatting.GOLD)
                    .append(button(com.millenairekingdoms.localization.PublicText.of("[Accept]"), "/kingdom accept " + villageId, com.millenairekingdoms.localization.PublicText.of("Attach %s", name(village)))).append(" ")
                    .append(button(com.millenairekingdoms.localization.PublicText.of("[Refuse]"), "/kingdom refuse " + villageId, com.millenairekingdoms.localization.PublicText.of("Refuse the application"))));
            return JoinResult.REQUESTED;
        }
        if (ruler != null) check(ruledKingdom(player).id().equals(kingdom.id()), "not_ruler_of", "You do not rule %s.", kingdom.name());
        else {
            int reputation = capital.getCombinedReputation(level, player.getUUID());
            check(reputation >= WAR_REQUEST_REPUTATION, "friends_only", "The leader of %s only listens to friends of the village (reputation %s); yours is %s.",
                    kingdom.name(), WAR_REQUEST_REPUTATION, reputation);
        }
        if (!owned || ruler == null) check(relation >= JOIN_RELATION, owned ? "join_relation_leader" : "join_relation_elders",
                (owned ? "The leader of %s" : "The elders of %s") + " refuse: the relation between %s and the capital is %s; joining needs %s.",
                owned ? kingdom.name() : name(village), name(village), relation, JOIN_RELATION);
        data.attach(kingdom.id(), villageId);
        return JoinResult.JOINED;
    }

    /** The ruler accepts an owner's application; ownership and the kingdom's state are checked again now. */
    public static Village accept(ServerPlayer player, UUID villageId) {
        ServerLevel level = player.serverLevel(); var data = data(level);
        Kingdom kingdom = ruledKingdom(player);
        var request = data.joinRequest(villageId);
        check(request != null && request.kingdom().equals(kingdom.id()), "no_application", "No application from that village to %s.", kingdom.name());
        Village village = village(level, villageId);
        // An NPC village's own application (stage 6.2) needs it to stay unowned and on good terms with the capital.
        boolean valid = village != null && (request.fromNpcVillage()
                ? village.getOwnerUUID() == null && capital(level, kingdom).getRelation(village.getId()) >= JOIN_RELATION
                : village.isControlledBy(request.player()));
        if (!valid) {
            data.cancelJoin(villageId);
            throw new KingdomException("millenairekingdoms.politics.application_withdrawn", "The village is gone, changed hands or no longer wants to join; the application was withdrawn.");
        }
        data.attach(kingdom.id(), villageId);
        var applicant = request.fromNpcVillage() ? null : level.getServer().getPlayerList().getPlayer(request.player());
        if (applicant != null) applicant.sendSystemMessage(com.millenairekingdoms.localization.PublicText.of("%s%s joined %s.", TAG, name(village), kingdom.name()).withStyle(ChatFormatting.GREEN));
        return village;
    }

    public static void refuse(ServerPlayer player, UUID villageId) {
        var data = data(player.serverLevel());
        Kingdom kingdom = ruledKingdom(player);
        var request = data.joinRequest(villageId);
        check(request != null && request.kingdom().equals(kingdom.id()), "no_application", "No application from that village to %s.", kingdom.name());
        data.cancelJoin(villageId);
    }

    /** A sufficiently developed independent village owned by the player becomes the capital of a new kingdom that the
     * player rules. Development is Millénaire's own town hall level (see EconomyService.centerLevel). */
    public static Kingdom found(ServerPlayer player, String name) {
        ServerLevel level = player.serverLevel(); var data = data(level);
        Village village = nearbyIndependentVillage(player);
        check(village != null, "found_near", "Stand within 64 blocks of the centre of your own independent village.");
        check(village.isControlledBy(player.getUUID()), "found_own", "Only your own Millénaire village can become a capital.");
        int townhall = com.millenairekingdoms.economy.EconomyService.centerLevel(village);
        check(townhall >= FOUND_LEVEL, "found_level", "%s's town hall is at level %s; a capital needs level %s.", name(village), townhall, FOUND_LEVEL);
        return data.found(name, village.getId().uuid(), player.getUUID(), player.getGameProfile().getName(), level.getGameTime());
    }

    /** The nearest known, non-lone village with a town hall within reach that belongs to no kingdom, or null. */
    public static Village nearbyIndependentVillage(ServerPlayer player) {
        ServerLevel level = player.serverLevel(); var data = KingdomSavedData.get(level);
        return VillageSavedData.get(level).getVillageManager().getAllVillages().stream()
                .filter(v -> near(player, v.getCenter()) && data.owner(v.getId().uuid()) == null && proper(v))
                .min(Comparator.comparingDouble(v -> v.getCenter().distToCenterSqr(player.position()))).orElse(null);
    }
    private static boolean proper(Village village) {
        var type = org.millenaire.culture.ModCultures.getVillageType(village.getVillageTypeId());
        return type != null && !type.loneBuilding() && village.getTownhall() != null;
    }
    public static String name(Village village) { return ConquestService.name(village); }

    /** The kingdom the player rules, if the player may decide for it now (active, living, reputation at least 8192).
     * A ruler below the threshold keeps the title; only decisions wait until the reputation is regained. */
    public static Kingdom ruledKingdom(ServerPlayer player) {
        ServerLevel level = player.serverLevel(); var data = data(level);
        UUID id = data.ruledBy(player.getUUID());
        check(id != null, "ruler_only", "Only a kingdom's ruler can do this.");
        Kingdom kingdom = data.kingdom(id);
        check(data.mayDecide(id, player.getUUID()), "inactive_decide", "%s is inactive; its ruler cannot decide now.", kingdom.name());
        int reputation = reputation(level, kingdom, player.getUUID());
        check(reputation >= DECISION_REPUTATION, "decision_reputation", "Your reputation at %s's capital is %s; political decisions need %s. You remain ruler.",
                kingdom.name(), reputation, DECISION_REPUTATION);
        return kingdom;
    }

    /** The overview shown by /kingdom info and when a capital's leader is right-clicked. */
    public static void sendOverview(ServerPlayer player, Kingdom focus) {
        ServerLevel level = player.serverLevel(); var data = data(level);
        UUID ruled = data.ruledBy(player.getUUID());
        if (focus == null && ruled != null) focus = data.kingdom(ruled);
        if (focus == null) {
            player.sendSystemMessage(com.millenairekingdoms.localization.PublicText.of("%sNo capital within 64 blocks and you rule no kingdom.", TAG).withStyle(ChatFormatting.GRAY));
            return;
        }
        var ruler = data.ruler(focus.id());
        boolean isRuler = ruler != null && ruler.player().equals(player.getUUID());
        Village capital = village(level, focus.capital());
        int reputation = capital == null ? 0 : capital.getCombinedReputation(level, player.getUUID());
        player.sendSystemMessage(Component.literal(TAG + focus.name()).withStyle(ChatFormatting.GOLD)
                .append(com.millenairekingdoms.localization.PublicText.of(" | ruler: %s | villages: %s%s", (ruler == null ? com.millenairekingdoms.localization.PublicText.of("none (the NPC leader governs)") : ruler.lastKnownName()), data.members(focus.id()).size(), (focus.active() ? "" : com.millenairekingdoms.localization.PublicText.of(" | INACTIVE"))).withStyle(ChatFormatting.WHITE)));
        player.sendSystemMessage(com.millenairekingdoms.localization.PublicText.of("Your reputation here: %s (war request %s, claim %s%s)", reputation, WAR_REQUEST_REPUTATION, CLAIM_REPUTATION, (isRuler ? com.millenairekingdoms.localization.PublicText.of(", decisions %s", DECISION_REPUTATION) : "")));
        long owed = data.owed(focus.id());
        player.sendSystemMessage(com.millenairekingdoms.localization.PublicText.of("Daily income: %s into the capital's town hall storage%s", EconomyService.format(EconomyService.dailyIncome(level, data, focus.id())), (owed > 0 ? com.millenairekingdoms.localization.PublicText.of(" | waiting for space or a loaded capital: %s", EconomyService.format(owed)) : "")));
        War war = data.activeWar(focus.id());
        if (war != null) {
            boolean attacking = war.attackerKingdom().equals(focus.id());
            Kingdom other = data.kingdom(attacking ? war.defenderKingdom() : war.attackerKingdom());
            MutableComponent line = com.millenairekingdoms.localization.PublicText.of("At war %s%s%s ", (attacking ? com.millenairekingdoms.localization.PublicText.of("against ") : com.millenairekingdoms.localization.PublicText.of("defending against ")), other.name(), (data.peaceOffered(war.id()) ? com.millenairekingdoms.localization.PublicText.of(" (peace offered)") : "")).withStyle(ChatFormatting.RED);
            if (isRuler) line.append(button(attacking ? com.millenairekingdoms.localization.PublicText.of("[Make peace]") : com.millenairekingdoms.localization.PublicText.of("[Offer peace]"), "/kingdom war peace",
                    attacking ? com.millenairekingdoms.localization.PublicText.of("End this war") : com.millenairekingdoms.localization.PublicText.of("Ask %s for peace", other.name())));
            player.sendSystemMessage(line);
        }
        if (ruler == null && ruled == null && focus.active()) {
            player.sendSystemMessage(reputation >= CLAIM_REPUTATION
                    ? com.millenairekingdoms.localization.PublicText.of("You may take over the rule. ").append(button(com.millenairekingdoms.localization.PublicText.of("[Claim rule]"), "/kingdom claim", com.millenairekingdoms.localization.PublicText.of("Become ruler of %s", focus.name())))
                    : com.millenairekingdoms.localization.PublicText.of("Claiming the rule needs reputation %s.", CLAIM_REPUTATION).withStyle(ChatFormatting.GRAY));
        }
        Kingdom finalFocus = focus;
        data.kingdoms().stream().filter(k -> !k.fallen() && !k.id().equals(finalFocus.id())).sorted(Comparator.comparing(Kingdom::name)).forEach(k -> {
            Village other = village(level, k.capital());
            Integer relation = capital == null || other == null ? null : capital.getRelation(other.getId());
            MutableComponent line = com.millenairekingdoms.localization.PublicText.of("  %s: relation %s ", k.name(), (relation == null ? "?" : relation));
            if (war == null && finalFocus.active() && k.active() && relation != null && data.truceUntil(finalFocus.id(), k.id()) < 0) {
                if (isRuler) line.append(button(com.millenairekingdoms.localization.PublicText.of("[Declare war]"), "/kingdom war declare " + k.id(), com.millenairekingdoms.localization.PublicText.of("Declare war on %s", k.name())));
                else if (ruler == null && reputation >= WAR_REQUEST_REPUTATION && relation <= HOSTILE_RELATION)
                    line.append(button(com.millenairekingdoms.localization.PublicText.of("[Request war]"), "/kingdom war request " + k.id(), com.millenairekingdoms.localization.PublicText.of("Ask the leader to declare war on %s", k.name())));
            }
            player.sendSystemMessage(line);
        });
        if (isRuler) {
            data.joinRequests().entrySet().stream().filter(e -> e.getValue().kingdom().equals(finalFocus.id())).forEach(e -> {
                Village applicant = village(level, e.getKey());
                player.sendSystemMessage(com.millenairekingdoms.localization.PublicText.of("Application: %s%s ", (applicant == null ? e.getKey() : name(applicant)), (e.getValue().fromNpcVillage() ? com.millenairekingdoms.localization.PublicText.of(" (the village itself)") : com.millenairekingdoms.localization.PublicText.of(" from %s", e.getValue().playerName()))).append(button(com.millenairekingdoms.localization.PublicText.of("[Accept]"), "/kingdom accept " + e.getKey(), com.millenairekingdoms.localization.PublicText.of("Attach the village")))
                        .append(" ").append(button(com.millenairekingdoms.localization.PublicText.of("[Refuse]"), "/kingdom refuse " + e.getKey(), com.millenairekingdoms.localization.PublicText.of("Refuse the application"))));
            });
            player.sendSystemMessage(button(com.millenairekingdoms.localization.PublicText.of("[Abdicate]"), "/kingdom abdicate", com.millenairekingdoms.localization.PublicText.of("Give up the rule of %s", focus.name())));
        }
        player.sendSystemMessage(button(com.millenairekingdoms.localization.PublicText.of("[Open the kingdom screen]"), "/kingdom gui", com.millenairekingdoms.localization.PublicText.of("All kingdom matters in one window")));
    }

    /** /kingdom info near an independent village: what it would take to join a kingdom or, for an owner, to found one. */
    public static void sendVillageOverview(ServerPlayer player) {
        ServerLevel level = player.serverLevel(); var data = data(level);
        Village village = nearbyIndependentVillage(player);
        if (village == null) return;
        boolean own = village.isControlledBy(player.getUUID());
        int townhall = com.millenairekingdoms.economy.EconomyService.centerLevel(village);
        MutableComponent line = com.millenairekingdoms.localization.PublicText.of("%s%s (independent, town hall level %s%s) ", TAG, name(village), townhall, (village.getOwnerUUID() == null ? "" : com.millenairekingdoms.localization.PublicText.of(", owner %s", village.getOwnerName()))).withStyle(ChatFormatting.GOLD);
        if (own && townhall >= FOUND_LEVEL && data.ruledBy(player.getUUID()) == null)
            line.append(com.millenairekingdoms.localization.PublicText.of("[Found a kingdom]").withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/kingdom found "))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, com.millenairekingdoms.localization.PublicText.of("Type the new kingdom's name")))));
        player.sendSystemMessage(line);
        if (village.getOwnerUUID() != null && !own) return;
        data.kingdoms().stream().filter(k -> !k.fallen() && k.active()).sorted(Comparator.comparing(Kingdom::name)).forEach(k -> {
            Village capital = village(level, k.capital());
            if (capital == null) return;
            player.sendSystemMessage(com.millenairekingdoms.localization.PublicText.of("  %s: relation with its capital %s ", k.name(), capital.getRelation(village.getId()))
                    .append(button(com.millenairekingdoms.localization.PublicText.of("[Join]"), "/kingdom join " + k.id(), com.millenairekingdoms.localization.PublicText.of("Ask to bring this village into %s", k.name()))));
        });
    }

    public static MutableComponent button(String label,String command,String hover){return button(com.millenairekingdoms.localization.PublicText.of(label),command,com.millenairekingdoms.localization.PublicText.of(hover));}
    public static MutableComponent button(Component label,String command,Component hover){
        return label.copy().withStyle(style->style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,hover)));
    }
    /** Messages the kingdom's ruler if online; a null kingdom does nothing. */
    public static void notifyRuler(ServerLevel level, UUID kingdomId, Component message) {
        if (kingdomId == null) return;
        var ruler = data(level).ruler(kingdomId);
        if (ruler == null) return;
        var player = level.getServer().getPlayerList().getPlayer(ruler.player());
        if (player != null) player.sendSystemMessage(message);
    }
    static KingdomSavedData data(ServerLevel level) {
        ConquestService.reconcile(level);
        return KingdomSavedData.get(level);
    }
    static void check(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    /** A refusal with a translatable message (key under millenairekingdoms.politics.) and its English text. */
    static void check(boolean condition, String key, String fallback, Object... args) {
        if (!condition) throw new KingdomException("millenairekingdoms.politics." + key, fallback, args);
    }
}
