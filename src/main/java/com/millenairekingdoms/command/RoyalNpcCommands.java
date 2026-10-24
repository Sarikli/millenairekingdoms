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
                    reply(c,"NPC economics | masterAutonomy="+AutonomyConfig.current().autonomy()+" | enabled="+budget.enabled()+" | reserve="+budget.reserve()+" | maxFeePermille="+budget.sharePermille()+" | actualFunds="+preview.funds()+" | "+preview.reason());
                    reply(c,"Justified candidates="+preview.candidates().size()+" | runtimeLast="+RoyalNpcRuntime.last(level));
                    preview.candidates().stream().sorted(Comparator.comparingInt(RoyalNpcEconomyService.Proposal::weight).reversed()).limit(8).forEach(p->reply(c,p.choice().kind()+" | weight="+p.weight()+" | fee="+p.choice().fee()+" | "+p.reason()));
                }))))
                .then(Commands.literal("status").then(PoliticalArguments.kingdomArgument("kingdom").executes(c->guarded(c,()->{
                    var level=c.getSource().getLevel();var id=PoliticalArguments.kingdom(c,"kingdom");var path=RoyalNpcDecisionStore.directory(level);var dimension=level.dimension().location().toString();
                    if(!Files.exists(path.resolve("decisions.dat"))){reply(c,"No persisted NPC economic attempts.");return;}
                    var entries=new RoyalNpcDecisionStore(path).entries().stream().filter(d->d.dimension().equals(dimension) && d.kingdom().equals(id))
                            .sorted(Comparator.comparingLong(RoyalNpcDecisionStore.Decision::day).reversed().thenComparing(RoyalNpcDecisionStore.Decision::id)).limit(12).toList();
                    reply(c,"Recent NPC attempts="+entries.size()+"; outcomes describe initial requests, not completed buildings/deliveries.");
                    for(var d:entries)reply(c,d.id()+" | day="+d.day()+" next="+d.nextDay()+" | "+d.choice().kind()+"/"+d.outcome()+" | "+d.reason());
                }))));
    }
    private interface Read {void run();}
    private static int guarded(CommandContext<CommandSourceStack> c,Read read){try{read.run();return 1;}catch(RuntimeException unavailable){c.getSource().sendFailure(Component.literal("NPC economic data unavailable; original records preserved: "+unavailable.getMessage()));return 0;}}
    private static void reply(CommandContext<CommandSourceStack> c,String text){c.getSource().sendSuccess(()->Component.literal(text),false);}
}
