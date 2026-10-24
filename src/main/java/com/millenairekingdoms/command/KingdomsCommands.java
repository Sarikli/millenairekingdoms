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
                                () -> reply(c, describeWar(data(c), data(c).peace(UuidArgument.getUuid(c, "war"))))))))
                        .then(Commands.literal("list").executes(c -> guarded(c, () -> {
                            var data = data(c); var wars = data.wars(); reply(c, "Wars in this dimension: " + wars.size());
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
                            reply(c, "Relation between " + a.name() + " and " + b.name() + " set to " + ca.getRelation(cb.getId()));
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
                    reply(c, k.name() + " | npc=" + data.npcGoverned(k.id()) + " | " + (war == null ? "at peace" : attacking ? "attacking" : "defending")
                            + " | order=" + (data.orderOf(k.id()) != null) + " | raids=" + data.activeConquests(k.id()) + " | targets=" + targets.size());
                    for (UUID t : targets) {
                        var tv = com.millenairekingdoms.politics.PoliticsService.village(level, t);
                        if (tv == null) { reply(c, "  " + t + " unknown"); continue; }
                        reply(c, "  target " + com.millenairekingdoms.politics.PoliticsService.name(tv) + " | cooldown "
                                + com.millenairekingdoms.autonomy.RaidOrderService.cooldownLeft(data, t, s) + " | involved " + data.involves(t)
                                + " | busy " + com.millenairekingdoms.autonomy.Military.busy(tv) + " | defence " + tv.getVillageDefendingStrength());
                        for (UUID m : data.members(k.id())) {
                            var mv = com.millenairekingdoms.politics.PoliticsService.village(level, m);
                            if (mv == null) continue;
                            String problem = com.millenairekingdoms.autonomy.Military.problem(mv, tv);
                            reply(c, "    from " + com.millenairekingdoms.politics.PoliticsService.name(mv) + ": " + (problem == null ? "ready" : problem));
                        }
                    }
                }))))
                        .then(Commands.literal("status").executes(c -> guarded(c, () -> {
                    var data = data(c); var level = c.getSource().getLevel();
                    var s = com.millenairekingdoms.autonomy.AutonomyConfig.current();
                    reply(c, "NPC autonomy " + (s.autonomy() ? "on" : "off") + " | joining " + s.joining() + " | campaigns " + s.campaigns()
                            + " | " + s.frequency() + " | day " + data.day() + " | next decision day " + data.nextDecisionDay()
                            + " | war threshold " + s.warThreshold() + " | cooldown " + s.raidCooldownDays() + " | truce " + s.truceDays());
                    data.orders().forEach(o -> reply(c, "order " + o.id() + " | kingdom " + data.kingdom(o.kingdom()).name() + " -> " + o.target()
                            + (o.source() == null ? "" : " from " + o.source()) + (o.warId() == null ? " | independent" : " | war " + o.warId())
                            + " | " + (o.byNpc() ? "NPC" : "ruler") + " | due in " + Math.max(0, o.dueGameTime() - level.getGameTime())
                            + " | expires in " + Math.max(0, o.expiresGameTime() - level.getGameTime())
                            + (com.millenairekingdoms.autonomy.RaidOrderService.waitingReason(o.id()) == null ? ""
                            : " | waiting: " + com.millenairekingdoms.autonomy.RaidOrderService.waitingReason(o.id()))));
                    data.truces().forEach((p, until) -> reply(c, "truce " + data.kingdom(p.low()).name() + " / " + data.kingdom(p.high()).name() + " until day " + until));
                    data.wars().stream().filter(w -> w.status() == KingdomSavedData.WarStatus.ACTIVE).forEach(w -> {
                        var clock = data.warClock(w.id());
                        reply(c, "war " + data.kingdom(w.attackerKingdom()).name() + " -> " + data.kingdom(w.defenderKingdom()).name()
                                + " | since day " + (clock == null ? "?" : clock.startDay()) + " | last progress day " + (clock == null ? "?" : clock.lastProgressDay())
                                + " | villages taken " + data.conquered(w.id()));
                    });
                }))))
                .then(Commands.literal("founding").then(Commands.literal("status").executes(c -> guarded(c, () -> {
                    // Operator diagnosis, read only: the founding rule for every village Millénaire knows in this dimension.
                    var data = data(c); var level = c.getSource().getLevel();
                    var s = com.millenairekingdoms.capital.FoundingConfig.current();
                    reply(c, "Kingdom founding " + (s.enabled() ? "on" : "off") + " | minimum level " + s.minimumLevel() + " | every "
                            + s.intervalDays() + " days | " + s.baseChancePercent() + "% +" + s.increasePerLevel() + " per level | distance "
                            + s.minCapitalDistance() + " | day " + data.day() + " | next evaluation day "
                            + com.millenairekingdoms.capital.DynamicFounding.nextDueDay(data.day(), s.intervalDays()));
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
                    reply(c, "Villages by rule: " + counts);
                    shown.forEach(line -> reply(c, "  " + line));
                }))))
                .then(Commands.literal("ruler")
                        .then(Commands.literal("clear").then(kingdomArgument().executes(c -> guarded(c, () -> {
                            var removed = data(c).clearRuler(PoliticalArguments.kingdom(c, "kingdom"));
                            reply(c, removed == null ? "This kingdom has no player ruler." : "Ruler removed: " + removed.player() + " (" + removed.lastKnownName() + ")");
                        })))))
                .then(Commands.literal("create").then(villageArgument("capital")
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> guarded(c, () -> {
                            UUID capital = villageId(c, "capital"); knownVillage(c.getSource(), capital);
                            var kingdom = data(c).create(StringArgumentType.getString(c, "name"), capital);
                            reply(c, "Kingdom created: " + kingdom.id() + " | " + kingdom.name() + " | capital=" + capital);
                        })))))
                .then(Commands.literal("attach").then(kingdomArgument().then(villageArgument("village").executes(c -> guarded(c, () -> {
                    UUID village = villageId(c, "village"); knownVillage(c.getSource(), village);
                    data(c).attach(PoliticalArguments.kingdom(c, "kingdom"), village); reply(c, "Village attached: " + village);
                })))))
                .then(Commands.literal("active").then(kingdomArgument().then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(c -> guarded(c, () -> {
                            data(c).setActive(PoliticalArguments.kingdom(c, "kingdom"), BoolArgumentType.getBool(c, "enabled"));
                            reply(c, "Kingdom active=" + BoolArgumentType.getBool(c, "enabled"));
                        })))))
                .then(Commands.literal("village").then(villageArgument("village").executes(c -> guarded(c, () -> {
                    UUID village = villageId(c, "village"); knownVillage(c.getSource(), village);
                    var data = data(c); UUID owner = data.owner(village);
                    reply(c, village + " | kingdom=" + (owner == null ? "unassigned" : owner + " (" + data.kingdom(owner).name() + ")")
                            + " | capital=" + data.isCapital(village) + " | revision=" + data.revision(village));
                }))))
                .then(Commands.literal("list").executes(c -> guarded(c, () -> {
                    var data = data(c); reply(c, "Kingdoms in " + c.getSource().getLevel().dimension().location() + ": " + data.kingdoms().size());
                    data.kingdoms().stream().sorted(Comparator.comparing(KingdomSavedData.Kingdom::name))
                            .forEach(k -> reply(c, describe(data, k)));
                })))
                .then(Commands.literal("info").then(kingdomArgument().executes(c -> guarded(c, () -> {
                    var data = data(c); var kingdom = data.kingdom(PoliticalArguments.kingdom(c, "kingdom"));
                    if (kingdom == null) throw new IllegalArgumentException("Unknown kingdom.");
                    reply(c, describe(data, kingdom));
                    reply(c, com.millenairekingdoms.personality.KingdomPersonality.describe(com.millenairekingdoms.personality.KingdomPersonality.forCapital(c.getSource().getLevel(),kingdom)));
                    data.members(kingdom.id()).stream().sorted().forEach(v -> reply(c, "  " + v + (data.isCapital(v) ? " (capital)" : "")));
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
            reply(c, "Conquest raid started: " + raid.id() + " | attacking kingdom=" + campaign.attackerKingdom()
                    + (campaign.warId() == null ? " | independent village, 1 victory required" : " | war=" + campaign.warId()
                    + (data(c).isCapital(campaign.target()) ? " | CAPITAL" : "")
                    + " | " + data(c).wins(campaign) + "/" + KingdomSavedData.REQUIRED_WINS + " before this raid")
                    + ". Operator test: preparation and relation checks skipped; loot disabled for this raid.");
        });
    }
    private static int status(CommandContext<CommandSourceStack> c) {
        return guarded(c, () -> {
            UUID target = villageId(c, "target"); var data = data(c);
            var active = data.raid(target); var last = data.lastResult(target);
            reply(c, target + " | political owner=" + data.owner(target) + " | capital=" + data.isCapital(target)
                    + " | active=" + (active == null ? "none" : active.id()) + " | last=" + (last == null ? "none" : last.outcome())
                    + " | legacy archived wins=" + data.legacyWins(target) + " (no political credit)");
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
        reply(context, "Villages in " + source.getLevel().dimension().location() + " (" + villages.size() + "); page " + page + ". IDs are plain UUIDs:");
        villages.stream().skip(offset).limit(10).forEach(v -> reply(context,
                v.getId().uuid() + " | " + ConquestService.name(v) + " | " + v.getCenter().toShortString()));
        return 1;
    }
}
