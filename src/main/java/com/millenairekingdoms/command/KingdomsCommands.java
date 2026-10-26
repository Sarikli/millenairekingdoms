package com.millenairekingdoms.command;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.conquest.ConquestService;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

public final class KingdomsCommands {
    private KingdomsCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdoms").requires(source -> source.hasPermission(2))
                .then(RoyalRecoveryCommands.tree())
                .then(RoyalNpcCommands.tree())
                .then(Commands.literal("war")
                        .then(Commands.literal("declare").then(kingdomArgument("attackerKingdom")
                                .then(kingdomArgument("defenderKingdom").executes(c -> guarded(c, () -> {
                                    var war = data(c).declareWar(PoliticalArguments.kingdom(c, "attackerKingdom"), PoliticalArguments.kingdom(c, "defenderKingdom"),
                                            c.getSource().getLevel().getGameTime());
                                    reply(c, describeWar(data(c), war));
                                })))))
                        .then(Commands.literal("info").then(warArgument().executes(c -> guarded(c, () -> {
                            var war = data(c).war(UuidArgument.getUuid(c, "war"));
                            if (war == null) throw new IllegalArgumentException("Unknown war.");
                            reply(c, describeWar(data(c), war));
                        }))))
                        .then(Commands.literal("peace").then(warArgument().executes(c -> guarded(c,
                                () -> reply(c, com.millenairekingdoms.localization.PublicText.of("%s", describeWar(data(c), data(c).peace(UuidArgument.getUuid(c, "war")))))))))
                        .then(Commands.literal("list").executes(c -> guarded(c, () -> {
                            var data = data(c); var wars = data.wars(); reply(c, com.millenairekingdoms.localization.PublicText.of("Wars in this dimension: %s", wars.size()));
                            wars.stream().sorted(Comparator.comparing(w -> w.id().toString())).forEach(w -> reply(c, describeWar(data, w)));
                        }))))
                .then(Commands.literal("relation").then(kingdomArgument("first").then(kingdomArgument("second")
                        .then(Commands.argument("value", IntegerArgumentType.integer(-100, 100)).executes(c -> guarded(c, () -> {
                            var data = data(c); var a = data.kingdom(PoliticalArguments.kingdom(c, "first")); var b = data.kingdom(PoliticalArguments.kingdom(c, "second"));
                            if (a == null || b == null || a.id().equals(b.id())) throw new IllegalArgumentException("Two different kingdoms are needed.");
                            var ca = com.millenairekingdoms.politics.PoliticsService.capital(c.getSource().getLevel(), a);
                            var cb = com.millenairekingdoms.politics.PoliticsService.capital(c.getSource().getLevel(), b);
                            // Maintenance and testing: sets Millénaire's own relation between the two capitals, as its chief screen would show it.
                            ca.adjustRelationSymmetric(c.getSource().getLevel(), cb.getId(), IntegerArgumentType.getInteger(c, "value"), true);
                            VillageSavedData.get(c.getSource().getLevel()).setDirty();
                            reply(c, com.millenairekingdoms.localization.PublicText.of("Relation between %s and %s set to %s", a.name(), b.name(), ca.getRelation(cb.getId())));
                        }))))))
                .then(Commands.literal("autonomy").then(Commands.literal("explain").then(kingdomArgument().executes(c -> guarded(c, () -> {
                    // Operator diagnosis: what an NPC campaign of this kingdom would target now, and why each pair is (not) ready.
                    var data = data(c); var level = c.getSource().getLevel(); var k = data.kingdom(PoliticalArguments.kingdom(c, "kingdom"));
                    if (k == null) throw new IllegalArgumentException("Unknown kingdom.");
                    var s = com.millenairekingdoms.autonomy.AutonomyConfig.current();
                    var war = data.activeWar(k.id());
                    boolean attacking = war != null && war.attackerKingdom().equals(k.id());
                    reply(c, com.millenairekingdoms.autonomy.RoyalInvestmentPolicy.describe(com.millenairekingdoms.autonomy.RoyalInvestmentPolicy.context(level,data,k,k.capital())));
                    var targets = attacking ? com.millenairekingdoms.autonomy.AutonomyService.warTargets(data, war)
                            : com.millenairekingdoms.autonomy.AutonomyService.hostileIndependents(level, data, k, s);
                    reply(c, com.millenairekingdoms.localization.PublicText.of("%s | npc=%s | %s | order=%s | raids=%s | targets=%s", k.name(), data.npcGoverned(k.id()), (war == null ? com.millenairekingdoms.localization.PublicText.of("at peace") : attacking ? com.millenairekingdoms.localization.PublicText.of("attacking") : com.millenairekingdoms.localization.PublicText.of("defending")), (data.orderOf(k.id()) != null), data.activeConquests(k.id()), targets.size()));
                    for (UUID t : targets) {
                        var tv = com.millenairekingdoms.politics.PoliticsService.village(level, t);
                        if (tv == null) { reply(c, com.millenairekingdoms.localization.PublicText.of("  %s unknown", t)); continue; }
                        reply(c, com.millenairekingdoms.localization.PublicText.of("  target %s | cooldown %s | involved %s | busy %s | defence %s", com.millenairekingdoms.politics.PoliticsService.name(tv), com.millenairekingdoms.autonomy.RaidOrderService.cooldownLeft(data, t, s), data.involves(t), com.millenairekingdoms.autonomy.Military.busy(tv), tv.getVillageDefendingStrength()));
                        for (UUID m : data.members(k.id())) {
                            var mv = com.millenairekingdoms.politics.PoliticsService.village(level, m);
                            if (mv == null) continue;
                            String problem = com.millenairekingdoms.autonomy.Military.problem(mv, tv);
                            reply(c, com.millenairekingdoms.localization.PublicText.of("    from %s: %s", com.millenairekingdoms.politics.PoliticsService.name(mv), (problem == null ? com.millenairekingdoms.localization.PublicText.of("ready") : problem)));
                        }
                    }
                }))))
                        .then(Commands.literal("status").executes(c -> guarded(c, () -> {
                    var data = data(c); var level = c.getSource().getLevel();
                    var s = com.millenairekingdoms.autonomy.AutonomyConfig.current();
                    reply(c, com.millenairekingdoms.localization.PublicText.of("NPC autonomy %s | joining %s | campaigns %s | %s | day %s | next decision day %s | war threshold %s | cooldown %s | truce %s", (s.autonomy() ? com.millenairekingdoms.localization.PublicText.of("on") : com.millenairekingdoms.localization.PublicText.of("off")), s.joining(), s.campaigns(), s.frequency(), data.day(), data.nextDecisionDay(), s.warThreshold(), s.raidCooldownDays(), s.truceDays()));
                    data.orders().forEach(o -> reply(c, com.millenairekingdoms.localization.PublicText.of("order %s | kingdom %s -> %s%s%s | %s | due in %s | expires in %s%s", o.id(), data.kingdom(o.kingdom()).name(), o.target(), (o.source() == null ? "" : com.millenairekingdoms.localization.PublicText.of(" from %s", o.source())), (o.warId() == null ? com.millenairekingdoms.localization.PublicText.of(" | independent") : com.millenairekingdoms.localization.PublicText.of(" | war %s", o.warId())), (o.byNpc() ? com.millenairekingdoms.localization.PublicText.of("NPC") : com.millenairekingdoms.localization.PublicText.of("ruler")), Math.max(0, o.dueGameTime() - level.getGameTime()), Math.max(0, o.expiresGameTime() - level.getGameTime()), (com.millenairekingdoms.autonomy.RaidOrderService.waitingReason(o.id()) == null ? "" : com.millenairekingdoms.localization.PublicText.of(" | waiting: %s", com.millenairekingdoms.autonomy.RaidOrderService.waitingReason(o.id()))))));
                    data.truces().forEach((p, until) -> reply(c, com.millenairekingdoms.localization.PublicText.of("truce %s / %s until day %s", data.kingdom(p.low()).name(), data.kingdom(p.high()).name(), until)));
                    data.wars().stream().filter(w -> w.status() == KingdomSavedData.WarStatus.ACTIVE).forEach(w -> {
                        var clock = data.warClock(w.id());
                        reply(c, com.millenairekingdoms.localization.PublicText.of("war %s -> %s | since day %s | last progress day %s | villages taken %s", data.kingdom(w.attackerKingdom()).name(), data.kingdom(w.defenderKingdom()).name(), (clock == null ? "?" : clock.startDay()), (clock == null ? "?" : clock.lastProgressDay()), data.conquered(w.id())));
                    });
                }))))
                .then(Commands.literal("founding").then(Commands.literal("status").executes(c -> guarded(c, () -> {
                    // Operator diagnosis, read only: the founding rule for every village Millénaire knows in this dimension.
                    var data = data(c); var level = c.getSource().getLevel();
                    var s = com.millenairekingdoms.capital.FoundingConfig.current();
                    reply(c, com.millenairekingdoms.localization.PublicText.of("Kingdom founding %s | minimum level %s | every %s days | %s%% +%s per level | distance %s | day %s | next evaluation day %s", (s.enabled() ? com.millenairekingdoms.localization.PublicText.of("on") : com.millenairekingdoms.localization.PublicText.of("off")), s.minimumLevel(), s.intervalDays(), s.baseChancePercent(), s.increasePerLevel(), s.minCapitalDistance(), data.day(), com.millenairekingdoms.capital.DynamicFounding.nextDueDay(data.day(), s.intervalDays())));
                    var counts = new java.util.TreeMap<com.millenairekingdoms.capital.DynamicFounding.Outcome, Integer>();
                    var shown = new java.util.ArrayList<String>();
                    VillageSavedData.get(level).getVillageManager().getAllVillages().stream()
                            .sorted(Comparator.comparing(v -> v.getId().uuid().toString())).forEach(v -> {
                                var r = com.millenairekingdoms.capital.DynamicFounding.check(level, data, v, s);
                                counts.merge(r.outcome(), 1, Integer::sum);
                                if (r.level() >= 0) shown.add(v.getId().uuid() + " | " + ConquestService.name(v) + " | level " + r.level() + " "
                                        + v.getTownhall().getStatus() + " | " + (r.outcome() == com.millenairekingdoms.capital.DynamicFounding.Outcome.NOT_CHOSEN
                                        ? "eligible, " + r.chance() + "% per evaluation" : r.outcome().toString()));
                            });
                    reply(c, com.millenairekingdoms.localization.PublicText.of("Villages by rule: %s", counts));
                    shown.forEach(line -> reply(c, "  " + line));
                }))))
                .then(Commands.literal("ruler")
                        .then(Commands.literal("clear").then(kingdomArgument().executes(c -> guarded(c, () -> {
                            var removed = data(c).clearRuler(PoliticalArguments.kingdom(c, "kingdom"));
                            reply(c, removed == null ? com.millenairekingdoms.localization.PublicText.of("This kingdom has no player ruler.") : com.millenairekingdoms.localization.PublicText.of("Ruler removed: %s (%s)", removed.player(), removed.lastKnownName()));
                        })))))
                .then(Commands.literal("create").then(villageArgument("capital")
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> guarded(c, () -> {
                            UUID capital = villageId(c, "capital"); knownVillage(c.getSource(), capital);
                            var kingdom = data(c).create(StringArgumentType.getString(c, "name"), capital);
                            reply(c, com.millenairekingdoms.localization.PublicText.of("Kingdom created: %s | %s | capital=%s", kingdom.id(), kingdom.name(), capital));
                        })))))
                .then(Commands.literal("attach").then(kingdomArgument().then(villageArgument("village").executes(c -> guarded(c, () -> {
                    UUID village = villageId(c, "village"); knownVillage(c.getSource(), village);
                    data(c).attach(PoliticalArguments.kingdom(c, "kingdom"), village); reply(c, com.millenairekingdoms.localization.PublicText.of("Village attached: %s", village));
                })))))
                .then(Commands.literal("active").then(kingdomArgument().then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(c -> guarded(c, () -> {
                            data(c).setActive(PoliticalArguments.kingdom(c, "kingdom"), BoolArgumentType.getBool(c, "enabled"));
                            reply(c, com.millenairekingdoms.localization.PublicText.of("Kingdom active=%s", BoolArgumentType.getBool(c, "enabled")));
                        })))))
                .then(Commands.literal("village").then(villageArgument("village").executes(c -> guarded(c, () -> {
                    UUID village = villageId(c, "village"); knownVillage(c.getSource(), village);
                    var data = data(c); UUID owner = data.owner(village);
                    reply(c, com.millenairekingdoms.localization.PublicText.of("%s | kingdom=%s | capital=%s | revision=%s", village, (owner == null ? com.millenairekingdoms.localization.PublicText.of("unassigned") : owner + " (" + data.kingdom(owner).name() + ")"), data.isCapital(village), data.revision(village)));
                }))))
                .then(Commands.literal("list").executes(c -> guarded(c, () -> {
                    var data = data(c); reply(c, com.millenairekingdoms.localization.PublicText.of("Kingdoms in %s: %s", c.getSource().getLevel().dimension().location(), data.kingdoms().size()));
                    data.kingdoms().stream().sorted(Comparator.comparing(KingdomSavedData.Kingdom::name))
                            .forEach(k -> reply(c, describe(data, k)));
                })))
                .then(Commands.literal("info").then(kingdomArgument().executes(c -> guarded(c, () -> {
                    var data = data(c); var kingdom = data.kingdom(PoliticalArguments.kingdom(c, "kingdom"));
                    if (kingdom == null) throw new IllegalArgumentException("Unknown kingdom.");
                    reply(c, describe(data, kingdom));
                    reply(c, com.millenairekingdoms.personality.KingdomPersonality.describe(com.millenairekingdoms.personality.KingdomPersonality.forCapital(c.getSource().getLevel(),kingdom)));
                    data.members(kingdom.id()).stream().sorted().forEach(v -> reply(c, com.millenairekingdoms.localization.PublicText.of("  %s%s", v, (data.isCapital(v) ? com.millenairekingdoms.localization.PublicText.of(" (capital)") : ""))));
                }))))
                .then(Commands.literal("test")
                        .then(Commands.literal("conquest").then(villageArgument("attacker").then(villageArgument("target").executes(KingdomsCommands::conquest))))
                        .then(Commands.literal("status").then(villageArgument("target").executes(KingdomsCommands::status)))
                        .then(Commands.literal("villages").executes(c -> villages(c, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(c -> villages(c, IntegerArgumentType.getInteger(c, "page")))))));
    }
    public static String describeWar(KingdomSavedData data, KingdomSavedData.War w) {
        return "War " + w.id() + " | " + w.attackerKingdom() + " -> " + w.defenderKingdom()
                + " | status=" + w.status() + " | startGameTime=" + w.startGameTime()
                + " | initial defender members=" + w.initialDefenderMembers().size()
                + " | capital requires=" + KingdomSavedData.capitalRequirement(w) + " | held=" + data.capitalHeld(w)
                + " | capital open=" + data.capitalOpen(w)
                + " | capital " + data.kingdom(w.defenderKingdom()).capital() + " progress=" + data.capitalWins(w) + "/" + KingdomSavedData.REQUIRED_WINS
                + " | endReason=" + w.endReason();
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, UUID> warArgument() {
        return Commands.argument("war", UuidArgument.uuid()).suggests((context, builder) -> SharedSuggestionProvider.suggest(
                KingdomSavedData.get(context.getSource().getLevel()).wars().stream().map(w -> w.id().toString()), builder));
    }
    private static String describe(KingdomSavedData data, KingdomSavedData.Kingdom k) {
        var ruler = data.ruler(k.id());
        return k.id() + " | " + k.name() + " | active=" + k.active() + " | capital=" + k.capital() + " | villages=" + data.members(k.id()).size()
                + " | ruler=" + (ruler == null ? "none" : ruler.player() + " (" + ruler.lastKnownName() + ")")
                + (k.fallen() ? " | FALLEN in war " + k.fallenInWar() + " (former capital and villages now belong to "
                + data.war(k.fallenInWar()).attackerKingdom() + ")" : "");
    }
    private static KingdomSavedData data(CommandContext<CommandSourceStack> c) {
        ConquestService.reconcile(c.getSource().getLevel());
        return KingdomSavedData.get(c.getSource().getLevel());
    }
    private static UUID villageId(CommandContext<CommandSourceStack> c, String key) { return PoliticalArguments.village(c, key); }
    private static void reply(CommandContext<CommandSourceStack> c, String text) { c.getSource().sendSuccess(() -> Component.literal(text), false); }
    private static void reply(CommandContext<CommandSourceStack> c, Component text) { c.getSource().sendSuccess(() -> text, false); }
    private static int guarded(CommandContext<CommandSourceStack> c, Runnable action) {
        try { action.run(); return 1; }
        catch (IllegalArgumentException e) { c.getSource().sendFailure(com.millenairekingdoms.util.KingdomException.of(e)); return 0; }
    }
    private static Village knownVillage(CommandSourceStack source, UUID id) {
        Village village = VillageSavedData.get(source.getLevel()).getVillageManager().getVillage(new VillageId(id));
        if (village == null) throw new IllegalArgumentException("Unknown village UUID in this dimension.");
        var type = ModCultures.getVillageType(village.getVillageTypeId());
        if (type == null || type.loneBuilding() || village.getTownhall() == null)
            throw new IllegalArgumentException("A known village with an existing town hall is required.");
        return village;
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> villageArgument(String name) {
        return PoliticalArguments.villageArgument(name);
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> kingdomArgument() {
        return kingdomArgument("kingdom");
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> kingdomArgument(String name) {
        return PoliticalArguments.kingdomArgument(name);
    }
    private static int conquest(CommandContext<CommandSourceStack> c) {
        return guarded(c, () -> {
            var source = c.getSource(); var player = source.getPlayer();
            var raid = ConquestService.start(source.getLevel(), villageId(c, "attacker"), villageId(c, "target"), player == null ? null : player.getUUID());
            var campaign = raid.campaign();
            reply(c, com.millenairekingdoms.localization.PublicText.of("Conquest raid started: %s | attacking kingdom=%s%s. Operator test: preparation and relation checks skipped; loot disabled for this raid.", raid.id(), campaign.attackerKingdom(), (campaign.warId() == null ? com.millenairekingdoms.localization.PublicText.of(" | independent village, 1 victory required") : com.millenairekingdoms.localization.PublicText.of(" | war=%s%s | %s/%s before this raid", campaign.warId(), (data(c).isCapital(campaign.target()) ? com.millenairekingdoms.localization.PublicText.of(" | CAPITAL") : ""), data(c).wins(campaign), KingdomSavedData.REQUIRED_WINS))));
        });
    }
    private static int status(CommandContext<CommandSourceStack> c) {
        return guarded(c, () -> {
            UUID target = villageId(c, "target"); var data = data(c);
            var active = data.raid(target); var last = data.lastResult(target);
            reply(c, com.millenairekingdoms.localization.PublicText.of("%s | political owner=%s | capital=%s | active=%s | last=%s | legacy archived wins=%s (no political credit)", target, data.owner(target), data.isCapital(target), (active == null ? com.millenairekingdoms.localization.PublicText.of("none") : active.id()), (last == null ? com.millenairekingdoms.localization.PublicText.of("none") : last.outcome()), data.legacyWins(target)));
            data.progress(target).entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().attackerKingdom().toString()))
                    .forEach(e -> reply(c, describeCampaign(data, e.getKey(), e.getValue())));
        });
    }
    private static String describeCampaign(KingdomSavedData data, KingdomSavedData.Campaign campaign, int wins) {
        UUID warId = campaign.warId();
        boolean stage3 = KingdomSavedData.STAGE3_WAR.equals(warId);
        int required = KingdomSavedData.requiredWins(campaign);
        String note = wins == required && !stage3 ? " (completed)"
                : campaign.targetRevision() != data.revision(campaign.target()) ? " (historical; target changed allegiance)"
                : stage3 ? " (historical; pre-war progress, no credit)"
                : warId != null && data.war(warId).status() != KingdomSavedData.WarStatus.ACTIVE ? " (historical; war ended)"
                : data.kingdom(campaign.attackerKingdom()).fallen() ? " (historical; attacking kingdom fell)" : "";
        return "campaign kingdom=" + campaign.attackerKingdom() + " | revision=" + campaign.targetRevision()
                + " | " + (warId == null ? "independent" : stage3 ? "war=none (pre-4.2A)" : "war=" + warId)
                + " | " + wins + "/" + required + note;
    }
    private static int villages(CommandContext<CommandSourceStack> context, int page) {
        var source = context.getSource();
        var villages = VillageSavedData.get(source.getLevel()).getVillageManager().getAllVillages().stream()
                .sorted(Comparator.comparing(v -> v.getId().uuid().toString())).toList();
        long offset = ((long) page - 1) * 10;
        reply(context, com.millenairekingdoms.localization.PublicText.of("Villages in %s (%s); page %s. IDs are plain UUIDs:", source.getLevel().dimension().location(), villages.size(), page));
        villages.stream().skip(offset).limit(10).forEach(v -> reply(context,
                v.getId().uuid() + " | " + ConquestService.name(v) + " | " + v.getCenter().toShortString()));
        return 1;
    }
}
