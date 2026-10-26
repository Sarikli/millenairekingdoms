package com.millenairekingdoms.command;

import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.autonomy.RoyalNpcConfig;
import com.millenairekingdoms.autonomy.RoyalNpcEconomyService;
import com.millenairekingdoms.autonomy.RoyalNpcRuntime;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.Files;
import java.util.Comparator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** OP2 readonly economic explanation/history. No force-issue/replay/reset command exists. */
public final class RoyalNpcCommands {
    private RoyalNpcCommands(){}
    public static LiteralArgumentBuilder<CommandSourceStack> tree(){
        return Commands.literal("economy").requires(s->s.hasPermission(2))
                .then(Commands.literal("explain").then(PoliticalArguments.kingdomArgument("kingdom").executes(c->guarded(c,()->{
                    var level=c.getSource().getLevel();var id=PoliticalArguments.kingdom(c,"kingdom");long day=Math.max(0,level.getDayTime())/EconomyService.DAY_TICKS;
                    var preview=RoyalNpcEconomyService.automatic(level).inspect(id,day);var budget=RoyalNpcConfig.budgets();
                    reply(c,com.millenairekingdoms.localization.PublicText.of("NPC economics | masterAutonomy=%s | enabled=%s | reserve=%s | maxFeePermille=%s | actualFunds=%s | %s", AutonomyConfig.current().autonomy(), budget.enabled(), budget.reserve(), budget.sharePermille(), preview.funds()<0?"UNKNOWN":Long.toString(preview.funds()), preview.reason()));
                    reply(c,com.millenairekingdoms.localization.PublicText.of("Justified candidates=%s | runtimeLast=%s", preview.candidates().size(), RoyalNpcRuntime.last(level)));
                    reply(c,com.millenairekingdoms.localization.PublicText.of("%s",com.millenairekingdoms.autonomy.RoyalNpcDiagnostics.inspect(level,id,day,preview)));
                    preview.candidates().stream().sorted(Comparator.comparingInt(RoyalNpcEconomyService.Proposal::weight).reversed()).limit(8).forEach(p->reply(c,com.millenairekingdoms.localization.PublicText.of("%s | weight=%s | fee=%s | %s", p.choice().kind(), p.weight(), p.choice().fee(), p.reason())));
                }))))
                .then(Commands.literal("status").then(PoliticalArguments.kingdomArgument("kingdom").executes(c->guarded(c,()->{
                    var level=c.getSource().getLevel();var id=PoliticalArguments.kingdom(c,"kingdom");var path=RoyalNpcDecisionStore.directory(level);var dimension=level.dimension().location().toString();
                    if(!Files.exists(path.resolve("decisions.dat"))){reply(c,com.millenairekingdoms.localization.PublicText.of("No persisted NPC economic attempts."));return;}
                    var entries=new RoyalNpcDecisionStore(path).entries().stream().filter(d->d.dimension().equals(dimension) && d.kingdom().equals(id))
                            .sorted(Comparator.comparingLong(RoyalNpcDecisionStore.Decision::day).reversed().thenComparing(RoyalNpcDecisionStore.Decision::id)).limit(12).toList();
                    reply(c,com.millenairekingdoms.localization.PublicText.of("Recent NPC attempts=%s; outcomes describe initial requests, not completed buildings/deliveries.", entries.size()));
                    for(var d:entries)reply(c,com.millenairekingdoms.localization.PublicText.of("%s | day=%s next=%s | %s/%s | %s", d.id(), d.day(), d.nextDay(), d.choice().kind(), d.outcome(), d.reason()));
                }))));
    }
    private interface Read {void run();}
    private static int guarded(CommandContext<CommandSourceStack> c,Read read){try{read.run();return 1;}catch(RuntimeException unavailable){c.getSource().sendFailure(com.millenairekingdoms.localization.PublicText.of("NPC economic data unavailable; original records preserved: %s", unavailable.getMessage()));return 0;}}
    private static void reply(CommandContext<CommandSourceStack> c,String text){c.getSource().sendSuccess(()->Component.literal(text),false);}
    private static void reply(CommandContext<CommandSourceStack> c,Component text){c.getSource().sendSuccess(()->text,false);}
}
