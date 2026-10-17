package com.millenairekingdoms.command;

import com.millenairekingdoms.autonomy.RaidOrderService;
import com.millenairekingdoms.conquest.ConquestService;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.TreasuryService;
import com.millenairekingdoms.gui.KingdomScreens;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Player commands (permission 0). They act only for the executing player's real UUID; every reputation, distance,
 * ruler and war rule is re-checked in PoliticsService, so typing a command gives nothing a chat button would not. */
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
                    reply(p, "You now rule " + kingdomOf(p).name() + ". The NPC leader keeps running the village.");
                })))
                .then(Commands.literal("abdicate").executes(c -> run(c, p -> reply(p, "You gave up the rule of " + PoliticsService.abdicate(p).name() + "."))))
                .then(Commands.literal("war")
                        .then(Commands.literal("request").then(target().executes(c -> run(c, p -> {
                            var war = PoliticsService.requestWar(p, StringArgumentType.getString(c, "target"));
                            reply(p, "The leader agreed: " + name(p, war.attackerKingdom()) + " declared war on " + name(p, war.defenderKingdom()) + ".");
                        }))))
                        .then(Commands.literal("declare").then(target().executes(c -> run(c, p -> {
                            var war = PoliticsService.declareWar(p, StringArgumentType.getString(c, "target"));
                            reply(p, name(p, war.attackerKingdom()) + " declared war on " + name(p, war.defenderKingdom()) + ".");
                        }))))
                        .then(Commands.literal("peace").executes(c -> run(c, p -> reply(p,
                                PoliticsService.peace(p) == PoliticsService.PeaceResult.MADE ? "Peace made." : "Peace offered; the attacking ruler must accept.")))))
                .then(Commands.literal("join").then(target().executes(c -> run(c, p -> reply(p,
                        PoliticsService.join(p, StringArgumentType.getString(c, "target")) == PoliticsService.JoinResult.JOINED
                                ? "The village joined the kingdom." : "Application sent; the kingdom's ruler decides.")))))
                .then(Commands.literal("accept").then(Commands.argument("village", UuidArgument.uuid()).executes(c -> run(c, p -> reply(p,
                        ConquestService.name(PoliticsService.accept(p, UuidArgument.getUuid(c, "village"))) + " joined your kingdom.")))))
                .then(Commands.literal("refuse").then(Commands.argument("village", UuidArgument.uuid()).executes(c -> run(c, p -> {
                    PoliticsService.refuse(p, UuidArgument.getUuid(c, "village")); reply(p, "Application refused.");
                }))))
                .then(Commands.literal("found").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> run(c, p -> reply(p,
                        "You founded and rule " + PoliticsService.found(p, StringArgumentType.getString(c, "name")).name() + ".")))))
                .then(Commands.literal("raid")
                        .then(Commands.literal("status").executes(c -> run(c, KingdomCommands::raidStatus)))
                        .then(Commands.literal("cancel").executes(c -> run(c, p -> {
                            RaidOrderService.cancel(p); reply(p, "The waiting conquest raid was called off.");
                        })))
                        .then(Commands.argument("target", UuidArgument.uuid()).suggests(KingdomCommands::targets)
                                .executes(c -> run(c, p -> raid(p, UuidArgument.getUuid(c, "target"), null)))
                                .then(Commands.argument("source", UuidArgument.uuid()).suggests(KingdomCommands::sources)
                                        .executes(c -> run(c, p -> raid(p, UuidArgument.getUuid(c, "target"), UuidArgument.getUuid(c, "source")))))))
                .then(Commands.literal("treasury")
                        .executes(c -> run(c, p -> reply(p, TreasuryService.describe(p))))
                        .then(Commands.literal("withdraw").then(Commands.argument("deniers", IntegerArgumentType.integer(1)).executes(c -> run(c, p -> reply(p,
                                "Withdrew " + EconomyService.format(TreasuryService.withdraw(p, IntegerArgumentType.getInteger(c, "deniers")))
                                        + " from the capital's town hall storage."))))))
                .then(Commands.literal("gui").executes(c -> run(c, KingdomScreens::open)))
                .executes(c -> run(c, KingdomScreens::open)));
    }

    private static void raid(ServerPlayer p, java.util.UUID target, java.util.UUID source) {
        var attempt = RaidOrderService.order(p, target, source);
        reply(p, attempt.status() == RaidOrderService.Status.STARTED ? "Conquest raid started."
                : "Conquest raid ordered; it starts when possible (" + attempt.reason() + "). It is called off after two days.");
    }
    private static void raidStatus(ServerPlayer p) {
        var data = KingdomSavedData.get(p.serverLevel());
        var kingdom = PoliticsService.ruledKingdom(p);
        var order = data.orderOf(kingdom.id());
        if (order != null) reply(p, "Waiting order against " + villageName(p, order.target()) + (RaidOrderService.waitingReason(order.id()) == null ? ""
                : ": " + RaidOrderService.waitingReason(order.id())));
        data.activeRaids().stream().filter(r -> r.campaign() != null && kingdom.id().equals(r.campaign().attackerKingdom())).forEach(r ->
                reply(p, "Raid under way: " + villageName(p, r.attacker()) + " -> " + villageName(p, r.target()) + " | "
                        + data.wins(r.campaign()) + "/" + KingdomSavedData.requiredWins(r.campaign())));
        if (order == null && data.activeConquests(kingdom.id()) == 0) reply(p, "No conquest raid under way.");
    }
    private static String villageName(ServerPlayer p, java.util.UUID id) {
        var v = PoliticsService.village(p.serverLevel(), id); return v == null ? id.toString() : PoliticsService.name(v);
    }
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> targets(
            CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        var data = KingdomSavedData.get(context.getSource().getLevel());
        var player = context.getSource().getPlayer();
        var kingdom = player == null ? null : data.ruledBy(player.getUUID());
        return SharedSuggestionProvider.suggest(org.millenaire.village.VillageSavedData.get(context.getSource().getLevel()).getVillageManager()
                .getAllVillages().stream().map(v -> v.getId().uuid()).filter(id -> kingdom == null || !kingdom.equals(data.owner(id)))
                .map(java.util.UUID::toString), builder);
    }
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> sources(
            CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        var data = KingdomSavedData.get(context.getSource().getLevel());
        var player = context.getSource().getPlayer();
        var kingdom = player == null ? null : data.ruledBy(player.getUUID());
        return SharedSuggestionProvider.suggest(kingdom == null ? java.util.stream.Stream.empty()
                : data.members(kingdom).stream().map(java.util.UUID::toString), builder);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> target() {
        return Commands.argument("target", StringArgumentType.greedyString()).suggests((context, builder) -> SharedSuggestionProvider.suggest(
                KingdomSavedData.get(context.getSource().getLevel()).kingdoms().stream().filter(k -> !k.fallen()).map(KingdomSavedData.Kingdom::name), builder));
    }
    interface Action { void run(ServerPlayer player); }
    private static int run(CommandContext<CommandSourceStack> c, Action action) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        try { action.run(player); return 1; }
        catch (IllegalArgumentException refused) { c.getSource().sendFailure(com.millenairekingdoms.util.KingdomException.of(refused)); return 0; }
    }
    private static KingdomSavedData.Kingdom kingdomOf(ServerPlayer p) {
        var data = KingdomSavedData.get(p.serverLevel()); return data.kingdom(data.ruledBy(p.getUUID()));
    }
    private static String name(ServerPlayer p, java.util.UUID kingdom) { return KingdomSavedData.get(p.serverLevel()).kingdom(kingdom).name(); }
    private static void reply(ServerPlayer p, String text) { p.sendSystemMessage(Component.literal("[Kingdoms] " + text)); }
}
