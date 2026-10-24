package com.millenairekingdoms.command;

import com.millenairekingdoms.construction.RoyalRecoveryService;
import com.millenairekingdoms.construction.RoyalRecoveryStore;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;

/** Explicit OP review; every resolution is bound to an immutable persisted reason and decision UUID. */
public final class RoyalRecoveryCommands {
    private RoyalRecoveryCommands() {}
    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("royal_recovery").requires(s->s.hasPermission(2))
                .then(Commands.literal("list").executes(c->guarded(c,()->{
                    var entries=service(c).entries(c.getSource()); reply(c,"Recovery decisions in this dimension: "+entries.size());
                    for(var e:entries) reply(c,describe(e));
                })))
                .then(Commands.literal("inspect_order").then(Commands.argument("order",UuidArgument.uuid()).executes(c->guarded(c,()->{
                    var e=service(c).inspectOrder(c.getSource(),UuidArgument.getUuid(c,"order"));
                    reply(c,"Order "+e.intent().id()+" | "+e.stage()+" / "+e.blocked()+" | village="+e.intent().village()
                            +" | plan="+e.intent().plan()+" | fee="+e.intent().fee()+" | building="+e.building()
                            +" | retiredFrom="+e.cancellationFrom()+" | recoveryDecision="+e.recoveryDecision());
                }))))
                .then(Commands.literal("adopt_pending").then(Commands.argument("order",UuidArgument.uuid())
                        .then(Commands.argument("reason",StringArgumentType.greedyString()).executes(c->authorize(c,RoyalRecoveryStore.Mode.ADOPT_PENDING)))))
                .then(Commands.literal("adopt_building").then(Commands.argument("order",UuidArgument.uuid())
                        .then(Commands.argument("building",UuidArgument.uuid()).then(Commands.argument("reason",StringArgumentType.greedyString())
                                .executes(c->authorize(c,RoyalRecoveryStore.Mode.ADOPT_BUILDING))))))
                .then(Commands.literal("forfeit_fee").then(Commands.argument("order",UuidArgument.uuid())
                        .then(Commands.argument("reason",StringArgumentType.greedyString()).executes(c->authorize(c,RoyalRecoveryStore.Mode.FORFEIT_FEE)))))
                .then(Commands.literal("resume").then(Commands.argument("decision",UuidArgument.uuid()).executes(c->guarded(c,()->
                        reply(c,describe(service(c).resume(c.getSource(),UuidArgument.getUuid(c,"decision"))))))))
                .then(Commands.literal("abandon").then(Commands.argument("decision",UuidArgument.uuid())
                        .then(Commands.argument("reason",StringArgumentType.greedyString()).executes(c->guarded(c,()->reply(c,describe(service(c).abandon(
                                c.getSource(),UuidArgument.getUuid(c,"decision"),StringArgumentType.getString(c,"reason")))))))));
    }
    private static int authorize(CommandContext<CommandSourceStack> c,RoyalRecoveryStore.Mode mode) {
        UUID decision=UUID.randomUUID();
        return guarded(c,()->{
            var service=service(c); var e=service.authorize(c.getSource(),decision,UuidArgument.getUuid(c,"order"),mode,
                    mode==RoyalRecoveryStore.Mode.ADOPT_BUILDING ? UuidArgument.getUuid(c,"building") : null,StringArgumentType.getString(c,"reason"));
            reply(c,"Persisted recovery decision "+decision+"; fee retained, original work preserved. Resume this ID if interrupted.");
            reply(c,describe(service.resume(c.getSource(),e.intent().id())));
        },decision);
    }
    private static RoyalRecoveryService service(CommandContext<CommandSourceStack> c) {return new RoyalRecoveryService(c.getSource().getLevel());}
    private static String describe(RoyalRecoveryStore.Entry e) {
        return e.intent().id()+" | "+e.state()+" | "+e.intent().mode()+" | order="+e.intent().order().id()+" | building="+e.intent().building()
                +" | operator="+e.intent().source()+"/"+e.intent().operator()+" | reason="+e.intent().reason()
                +(e.result()==null ? "" : " | result="+e.result().source()+": "+e.result().reason());
    }
    private static int guarded(CommandContext<CommandSourceStack> c,Action action) {return guarded(c,action,null);}
    private static int guarded(CommandContext<CommandSourceStack> c,Action action,UUID decision) {
        try {action.run();return 1;}
        catch(Exception failure) {
            LogUtils.getLogger().warn("[Kingdoms] Operator recovery command failed; decision={}; retained durable state",decision,failure);
            c.getSource().sendFailure(Component.literal("Recovery paused: "+failure.getMessage()+". Inspect /kingdoms royal_recovery list; durable changes may already exist."
                    +(decision==null ? "" : " Decision ID: "+decision))); return 0;
        }
    }
    private static void reply(CommandContext<CommandSourceStack> c,String text) {c.getSource().sendSuccess(()->Component.literal(text),false);}
    @FunctionalInterface private interface Action {void run() throws Exception;}
}
