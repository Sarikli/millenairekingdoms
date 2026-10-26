package com.millenairekingdoms.capital;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.Millenaire;
import org.millenaire.building.BuildingInstance;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.village.Village;
import org.millenaire.village.VillageGrowthManager;

/** Pinned native rush upgrade boundary. Never selects a new building placement or changes ordinary growth. */
final class NaturalCapitalGrowth {
    private record Methods(Method candidates,Method upgrade,Method building,Method rush) {}
    private static final class Pinned { static final Methods METHODS=resolve(); }
    private NaturalCapitalGrowth() {}

    static boolean upgradeOne(ServerLevel level,Village village) {
        var m=Pinned.METHODS;
        var type=ModCultures.getVillageType(village.getVillageTypeId());
        // Same skip-tier/placement settings as native rushOneProject; retain native upgrade prerequisites.
        for(var candidate:(List<?>)invoke(m.candidates,null,level.getGameTime(),village,type,false,true)) {
            if(!(boolean)invoke(m.upgrade,candidate))continue;
            var building=(BuildingInstance)invoke(m.building,candidate);
            if(building==null || NaturalCapitals.buildingSpaceProblem(level,village,building)!=null)continue;
            return (boolean)invoke(m.rush,null,level,village,candidate);
        }
        return false;
    }

    private static Methods resolve() {
        if(!"9.0.3".equals(Millenaire.getModVersion()))throw new IllegalStateException("Natural capital upgrades require audited Millenaire9.0.3");
        try {
            var candidate=Class.forName("org.millenaire.village.VillageGrowthManager$GrowthCandidate",false,VillageGrowthManager.class.getClassLoader());
            return new Methods(method(VillageGrowthManager.class,"getAllCandidates",long.class,Village.class,VillageType.class,boolean.class,boolean.class),
                    method(candidate,"isUpgrade"),method(candidate,"existingBuilding"),method(VillageGrowthManager.class,"executeRushUpgrade",ServerLevel.class,Village.class,candidate));
        } catch(ReflectiveOperationException e) {throw new IllegalStateException("Audited natural capital upgrade API unavailable",e);}
    }
    private static Method method(Class<?> type,String name,Class<?>... arguments)throws NoSuchMethodException {
        var method=type.getDeclaredMethod(name,arguments);method.setAccessible(true);return method;
    }
    private static Object invoke(Method method,Object receiver,Object... arguments) {
        try {return method.invoke(receiver,arguments);}
        catch(InvocationTargetException e) {if(e.getCause() instanceof RuntimeException failure)throw failure;throw new IllegalStateException("Native capital upgrade failed",e.getCause());}
        catch(ReflectiveOperationException e) {throw new IllegalStateException("Native capital upgrade inaccessible",e);}
    }
}
