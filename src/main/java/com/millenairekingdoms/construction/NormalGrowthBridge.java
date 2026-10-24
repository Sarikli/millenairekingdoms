package com.millenairekingdoms.construction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.Millenaire;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.culture.VillageType;
import org.millenaire.village.Village;
import org.millenaire.village.VillageGrowthManager;

/** Narrow read-only boundary to the pinned 9.0.3 eligibility logic. No copied tier rules, rush calls or engine mutation.
 * Missing/private signature changes fail closed instead of offering invented construction candidates. */
final class NormalGrowthBridge {
    record Candidate(BuildingPlanSet set,BuildingPlanSet.LevelDef level,String variant,boolean upgrade) {}
    private record Methods(Method candidates,Method set,Method level,Method variant,Method upgrade,Method conditions,Method resources) {}
    private static final class Pinned { static final Methods METHODS=resolve(); }
    private NormalGrowthBridge() {}

    static List<Candidate> candidates(ServerLevel level,Village village,VillageType type) {
        var methods=Pinned.METHODS; var result=new ArrayList<Candidate>();
        for(Object raw:(List<?>)invoke(methods.candidates,null,level,village,type)) {
            var candidate=new Candidate((BuildingPlanSet)invoke(methods.set,raw),(BuildingPlanSet.LevelDef)invoke(methods.level,raw),
                    (String)invoke(methods.variant,raw),(boolean)invoke(methods.upgrade,raw));
            // Player-building candidates do not run this check in getAllCandidates; keep the new-building gate too.
            if(!candidate.upgrade && (boolean)invoke(methods.conditions,null,village,candidate.level)) result.add(candidate);
        }
        return List.copyOf(result);
    }
    static boolean conditions(Village village,BuildingPlanSet.LevelDef level) {
        return (boolean)invoke(Pinned.METHODS.conditions,null,village,level);
    }
    static boolean resources(ServerLevel level,Village village,BuildingPlanSet.LevelDef definition) {
        return (boolean)invoke(Pinned.METHODS.resources,null,level,village,definition);
    }

    private static Methods resolve() {
        if(!"9.0.3".equals(Millenaire.getModVersion())) throw new IllegalStateException("Royal construction eligibility requires the audited Millenaire 9.0.3 API");
        try {
            Class<?> record=Class.forName("org.millenaire.village.VillageGrowthManager$GrowthCandidate",false,VillageGrowthManager.class.getClassLoader());
            return new Methods(method(VillageGrowthManager.class,"getAllCandidates",ServerLevel.class,Village.class,VillageType.class),
                    method(record,"planSet"),method(record,"levelDef"),method(record,"variant"),method(record,"isUpgrade"),
                    method(VillageGrowthManager.class,"checkNewBuildingConditions",Village.class,BuildingPlanSet.LevelDef.class),
                    method(VillageGrowthManager.class,"hasResourcesForProject",ServerLevel.class,Village.class,BuildingPlanSet.LevelDef.class));
        } catch(ReflectiveOperationException e) { throw new IllegalStateException("Audited normal-growth API is unavailable; no royal building can be offered",e); }
    }
    private static Method method(Class<?> owner,String name,Class<?>... parameters) throws NoSuchMethodException {
        var method=owner.getDeclaredMethod(name,parameters); method.setAccessible(true); return method;
    }
    private static Object invoke(Method method,Object target,Object... parameters) {
        try { return method.invoke(target,parameters); }
        catch(InvocationTargetException e) {
            if(e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Normal-growth eligibility failed; no royal building can be offered",e.getCause());
        } catch(ReflectiveOperationException e) { throw new IllegalStateException("Cannot inspect audited normal-growth candidates",e); }
    }
}
