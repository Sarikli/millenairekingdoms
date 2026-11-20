package com.millenairekingdoms.command;

import com.millenairekingdoms.autonomy.RaidOrderService;
import com.millenairekingdoms.conquest.ConquestService;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.TreasuryService;
import com.millenairekingdoms.gui.KingdomScreens;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Player commands (permission 0). They act only for the executing player's real UUID; every reputation, distance,
 * ruler and war rule is re-checked in PoliticsService, so typing a command gives nothing a chat button would not.
 */
public final class KingdomCommands {
    private KingdomCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdom")
                .then(Commands.literal("info").executes(c -> run(c, p -> {
                    PoliticsService.sendVillageOverview(p);
                    PoliticsService.sendOverview(p, PoliticsService.nearbyCapital(p));
                })))
                .then(Commands.literal("claim").executes(c -> run(c, p -> {
                    PoliticsService.claim(p);
                    reply(p, com.millenairekingdoms.localization.PublicText.of("You now rule %s. The NPC leader keeps running the village.", kingdomOf(p).name()));
                })))
                .then(Commands.literal("abdicate").executes(c -> run(c, p -> reply(p, com.millenairekingdoms.localization.PublicText.of("You gave up the rule of %s.", PoliticsService.abdicate(p).name())))))
                .then(Commands.literal("war")
                        .then(Commands.literal("request").then(target().executes(c -> run(c, p -> {
                            var war = PoliticsService.requestWar(p, StringArgumentType.getString(c, "target"));
                            reply(p, com.millenairekingdoms.localization.PublicText.of("The leader agreed: %s declared war on %s.", name(p, war.attackerKingdom()), name(p, war.defenderKingdom())));
                        }))))
                        .then(Commands.literal("declare").then(target().executes(c -> run(c, p -> {
                            var war = PoliticsService.declareWar(p, StringArgumentType.getString(c, "target"));
                            reply(p, com.millenairekingdoms.localization.PublicText.of("%s declared war on %s.", name(p, war.attackerKingdom()), name(p, war.defenderKingdom())));
                        }))))
                        .then(Commands.literal("peace").executes(c -> run(c, p -> reply(p,
                                PoliticsService.peace(p) == PoliticsService.PeaceResult.MADE ? com.millenairekingdoms.localization.PublicText.of("Peace made.") : com.millenairekingdoms.localization.PublicText.of("Peace offered; the attacking ruler must accept."))))))
                .then(Commands.literal("join").then(target().executes(c -> run(c, p -> reply(p,
                        PoliticsService.join(p, StringArgumentType.getString(c, "target")) == PoliticsService.JoinResult.JOINED ? com.millenairekingdoms.localization.PublicText.of("The village joined the kingdom.") : com.millenairekingdoms.localization.PublicText.of("Application sent; the kingdom's ruler decides."))))))
                .then(Commands.literal("accept").then(PoliticalArguments.villageArgument("village").executes(c -> run(c, p -> reply(p,
                        com.millenairekingdoms.localization.PublicText.of("%s joined your kingdom.", ConquestService.name(PoliticsService.accept(p, PoliticalArguments.village(c, "village")))))))))
                .then(Commands.literal("refuse").then(PoliticalArguments.villageArgument("village").executes(c -> run(c, p -> {
                    PoliticsService.refuse(p, PoliticalArguments.village(c, "village")); reply(p, com.millenairekingdoms.localization.PublicText.of("Application refused."));
                }))))
                .then(Commands.literal("found").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> run(c, p -> reply(p,
                        com.millenairekingdoms.localization.PublicText.of("You founded and rule %s.", PoliticsService.found(p, StringArgumentType.getString(c, "name")).name()))))))
                .then(Commands.literal("raid")
                        .then(Commands.literal("status").executes(c -> run(c, KingdomCommands::raidStatus)))
                        .then(Commands.literal("cancel").executes(c -> run(c, p -> {
                            RaidOrderService.cancel(p); reply(p, com.millenairekingdoms.localization.PublicText.of("The waiting conquest raid was called off."));
                        })))
                        .then(PoliticalArguments.villageArgument("target")
                                .executes(c -> run(c, p -> raid(p, PoliticalArguments.village(c, "target"), null)))
                                .then(PoliticalArguments.villageArgument("source")
                                        .executes(c -> run(c, p -> raid(p, PoliticalArguments.village(c, "target"), PoliticalArguments.village(c, "source")))))))
                .then(Commands.literal("treasury")
                        .executes(c -> run(c, p -> reply(p, TreasuryService.describeComponent(p))))
                        .then(Commands.literal("withdraw").then(Commands.argument("deniers", IntegerArgumentType.integer(1)).executes(c -> run(c, p -> reply(p,
                                com.millenairekingdoms.localization.PublicText.of("Withdrew %s from the capital's town hall storage.", EconomyService.format(TreasuryService.withdraw(p, IntegerArgumentType.getInteger(c, "deniers"))))))))))
                .then(Commands.literal("road")
                        .then(Commands.literal("build")
                                .then(PoliticalArguments.villageArgument("villageA")
                                        .then(PoliticalArguments.villageArgument("villageB")
                                                .executes(c -> run(c, p -> roadBuild(p,
                                                        PoliticalArguments.village(c, "villageA"),
                                                        PoliticalArguments.village(c, "villageB")))))))
                        .then(Commands.literal("cancel").executes(c -> run(c, KingdomCommands::roadCancel)))
                        .then(Commands.literal("status").executes(c -> run(c, KingdomCommands::roadStatus))))
                .then(Commands.literal("gui").executes(c -> run(c, KingdomScreens::open)))
                .executes(c -> run(c, KingdomScreens::open)));
    }

    private static void raid(ServerPlayer p, java.util.UUID target, java.util.UUID source) {
        var attempt = RaidOrderService.order(p, target, source);
        reply(p,
                attempt.status() == RaidOrderService.Status.STARTED
                        ? com.millenairekingdoms.localization.PublicText.of("Conquest raid started.")
                        : com.millenairekingdoms.localization.PublicText.of(
                                  "Conquest raid ordered; it starts when possible (%s). It is called off after one day.",
                                  com.millenairekingdoms.localization.PublicText.error(attempt.reason())));
    }
    private static void raidStatus(ServerPlayer p) {
        var data = KingdomSavedData.get(p.serverLevel());
        var kingdom = PoliticsService.ruledKingdom(p);
        var order = data.orderOf(kingdom.id());
        if (order != null)
            reply(p,
                    com.millenairekingdoms.localization.PublicText.of("Waiting order against %s%s",
                            villageName(p, order.target()),
                            (RaidOrderService.waitingReason(order.id()) == null
                                            ? ""
                                            : Component.literal(": ").append(
                                                      com.millenairekingdoms.localization.PublicText.error(
                                                              RaidOrderService.waitingReason(order.id()))))));
        data.activeRaids()
                .stream()
                .filter(r -> r.campaign() != null && kingdom.id().equals(r.campaign().attackerKingdom()))
                .forEach(r
                        -> reply(p,
                                com.millenairekingdoms.localization.PublicText.of("Raid under way: %s -> %s | %s/%s",
                                        villageName(p, r.attacker()), villageName(p, r.target()),
                                        data.wins(r.campaign()), KingdomSavedData.requiredWins(r.campaign()))));
        if (order == null && data.activeConquests(kingdom.id()) == 0)
            reply(p, com.millenairekingdoms.localization.PublicText.of("No conquest raid under way."));
    }

    private static void roadBuild(ServerPlayer p, java.util.UUID villageA, java.util.UUID villageB) {
        var seg = com.millenairekingdoms.roads.RoadService.orderPlayerRoad(p, villageA, villageB);
        reply(p, Component.translatable("millenairekingdoms.commands.road.started", villageName(p, seg.villageA()),
                villageName(p, seg.villageB()), seg.totalBlocks(), seg.stipendPaid()));
    }

    private static void roadCancel(ServerPlayer p) {
        var seg = com.millenairekingdoms.roads.RoadService.cancelPlayerRoad(p);
        reply(p, Component.translatable("millenairekingdoms.commands.road.cancelled", villageName(p, seg.villageA()),
                villageName(p, seg.villageB())));
    }

    private static void roadStatus(ServerPlayer p) {
        var level = p.serverLevel();
        var data = KingdomSavedData.get(level);
        var kingdom = PoliticsService.ruledKingdom(p);
        var roadData = com.millenairekingdoms.roads.RoadSavedData.get(level);
        var active = roadData.activeSegment(kingdom.id());
        if (active != null) {
            reply(p, Component.translatable("millenairekingdoms.commands.road.status_active",
                    villageName(p, active.villageA()), villageName(p, active.villageB()),
                    active.completedBlocks(), active.totalBlocks(),
                    Component.translatable("millenairekingdoms.gui.roads.status." + active.status().name())));
        } else {
            reply(p, Component.translatable("millenairekingdoms.commands.road.status_none"));
        }
        var connected = roadData.connectedToCapital(data, kingdom.id());
        long completedCount = roadData.segmentsForKingdom(kingdom.id())
                                      .stream()
                                      .filter(com.millenairekingdoms.roads.RoadSavedData.RoadSegment::isCompleted)
                                      .count();
        reply(p, Component.translatable("millenairekingdoms.commands.road.network_summary",
                connected.size(), completedCount));
    }
    private static String villageName(ServerPlayer p, java.util.UUID id) {
        var v = PoliticsService.village(p.serverLevel(), id);
        return v == null ? id.toString() : PoliticsService.name(v);
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> target() {
        return Commands.argument("target", StringArgumentType.greedyString())
                .suggests(
                        (context, builder)
                                -> SharedSuggestionProvider.suggest(KingdomSavedData.get(context.getSource().getLevel())
                                                                            .kingdoms()
                                                                            .stream()
                                                                            .filter(k -> !k.fallen())
                                                                            .map(KingdomSavedData.Kingdom::name),
                                        builder));
    }
    interface Action {
        void run(ServerPlayer player);
    }
    private static int run(CommandContext<CommandSourceStack> c, Action action) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        try {
            action.run(player);
            return 1;
        } catch (IllegalArgumentException refused) {
            c.getSource().sendFailure(com.millenairekingdoms.util.KingdomException.of(refused));
            return 0;
        }
    }
    private static KingdomSavedData.Kingdom kingdomOf(ServerPlayer p) {
        var data = KingdomSavedData.get(p.serverLevel());
        return data.kingdom(data.ruledBy(p.getUUID()));
    }
    private static String name(ServerPlayer p, java.util.UUID kingdom) {
        return KingdomSavedData.get(p.serverLevel()).kingdom(kingdom).name();
    }
    private static void reply(ServerPlayer p, String text) {
        com.millenairekingdoms.localization.PublicText.send(p, Component.literal("[Kingdoms] " + text));
    }
    private static void reply(ServerPlayer p, Component text) {
        com.millenairekingdoms.localization.PublicText.send(p, Component.literal("[Kingdoms] ").append(text));
    }
}
