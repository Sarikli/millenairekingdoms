package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.server.level.ServerLevel;

/** Read-only operator facts. A missing/unknown physical balance is never reported as verified zero. */
public final class RoyalNpcDiagnostics {
    private RoyalNpcDiagnostics(){}
    public record State(String government,boolean capitalRegistered,boolean capitalLoaded,boolean capitalActive,
                        long lastEvaluationDay,long nextEvaluationDay,String physicalInformation,String executionReason,RoyalStrategyStore.Plan conditionalStrategy){}
    public static State inspect(ServerLevel level,UUID id,long day,RoyalNpcEconomyService.Preview preview){
        var data=KingdomSavedData.get(level);var kingdom=data.kingdom(id);var capital=kingdom==null?null:PoliticsService.village(level,kingdom.capital());
        var path=RoyalNpcDecisionStore.directory(level);var last=Files.exists(path.resolve("decisions.dat"))?new RoyalNpcDecisionStore(path).entries().stream()
                .filter(d->d.kingdom().equals(id) && d.dimension().equals(level.dimension().location().toString()))
                .max(Comparator.comparingLong(RoyalNpcDecisionStore.Decision::day)):Optional.<RoyalNpcDecisionStore.Decision>empty();
        boolean loaded=capital!=null && level.isLoaded(capital.getCenter());
        var strategy=new RoyalStrategyStore(level).read(id);
        return new State(kingdom==null?"UNKNOWN":data.npcGoverned(id)?"NPC":"PLAYER",capital!=null,loaded,capital!=null && capital.isActive(),
                Math.max(last.map(RoyalNpcDecisionStore.Decision::day).orElse(-1L),strategy==null?-1:strategy.evaluatedDay()),Math.max(last.map(RoyalNpcDecisionStore.Decision::nextDay).orElse(day),strategy==null?day:strategy.nextDay()),
                !loaded?"UNKNOWN: treasury/materials/builders unavailable; registered political/type metadata only":"REQUIRES CURRENT DOMAIN VALIDATION: loaded does not authorize funds/materials/builders",preview.reason(),strategy);
    }
}
