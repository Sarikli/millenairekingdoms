package com.millenairekingdoms.personality;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.culture.ModCultures;

/** Revision1 explicit original9.0.3 type mapping. The saved capital UUID/type are the durable source of identity;
 * no random roll, kingdom-name/culture heuristic, extra political schema or world mutation is needed on lookup. */
public final class KingdomPersonality {
    public static final int MAPPING_REVISION=1;
    public enum Category { MILITARY, AGRICULTURAL, RELIGIOUS, ARTISAN, GENERAL }
    public enum Status { MAPPED, MISSING_KINGDOM, MISSING_CAPITAL, UNKNOWN_TYPE, UNMAPPED_TYPE, CONTROLLED_TYPE, LONE_BUILDING }
    public record Profile(UUID capital,ResourceLocation villageType,Category category,Status status) {
        public boolean specialized(){return status==Status.MAPPED;}
    }
    private static final Map<ResourceLocation,Category> TYPES=mapping();
    private KingdomPersonality() {}

    public static Map<ResourceLocation,Category> types(){return TYPES;}
    /** Unknown/custom/controlled/lone types retain their records and use an explicit balanced fallback. */
    public static Profile forType(UUID capital,ResourceLocation type){
        var nativeType=type==null ? null : ModCultures.getVillageType(type);
        Status status=nativeType==null ? Status.UNKNOWN_TYPE : nativeType.loneBuilding() ? Status.LONE_BUILDING
                : nativeType.playerControlled() ? Status.CONTROLLED_TYPE : TYPES.containsKey(type) ? Status.MAPPED : Status.UNMAPPED_TYPE;
        return new Profile(capital,type,status==Status.MAPPED ? TYPES.get(type) : Category.GENERAL,status);
    }
    public static Profile forKingdom(ServerLevel level,UUID id){return forCapital(level,KingdomSavedData.get(level).kingdom(id));}
    /** Accepts a read-only political snapshot, including strict deserialized save copies for migration diagnostics. */
    public static Profile forCapital(ServerLevel level,KingdomSavedData.Kingdom kingdom){
        if(kingdom==null)return new Profile(null,null,Category.GENERAL,Status.MISSING_KINGDOM);
        var village=PoliticsService.village(level,kingdom.capital());
        return village==null ? new Profile(kingdom.capital(),null,Category.GENERAL,Status.MISSING_CAPITAL)
                : forType(kingdom.capital(),village.getVillageTypeId());
    }
    public static String describe(Profile profile){return "personality="+profile.category()+" | mapping="+MAPPING_REVISION+" | capital="+profile.capital()+" | type="+profile.villageType()+" | classification="+profile.status();}

    private static Map<ResourceLocation,Category> mapping(){
        var types=new LinkedHashMap<ResourceLocation,Category>();
        add(types,Category.MILITARY,"indian/fort","indian/fort_hamlet","norman/militaire","seljuk/military_village_seljuks","mayan/military","byzantines/militaryvillage","japanese/gunji");
        add(types,Category.AGRICULTURAL,"indian/agricole","indian/agricole_hamlet","norman/agricole","norman/hameau_agricole","seljuk/agriculture_village_seljuks","seljuk/agriculture_small_seljuks","mayan/agriculture","japanese/nogyo","inuits/huntingvillage","inuits/farmingvillage");
        add(types,Category.RELIGIOUS,"norman/ecclesiastique","norman/hameau_abbatiale","norman/notredame","seljuk/religious_village_seljuks","mayan/religious","byzantines/religiousvillage","inuits/religiousvillage");
        add(types,Category.ARTISAN,"norman/artisans","norman/hameau_artisans","seljuk/trading_village_seljuks","seljuk/artisans_small_seljuks","byzantines/tradingvillage","japanese/trading","inuits/artisanalvillage");
        add(types,Category.GENERAL,"indian/palace","indian/palace_autonomous","norman/bourg_autonome","norman/grosbourg","japanese/seiji","inuits/communityvillage");
        return Collections.unmodifiableMap(types);
    }
    private static void add(Map<ResourceLocation,Category> types,Category category,String... paths){
        for(var path:paths)if(types.put(ResourceLocation.fromNamespaceAndPath("millenaire",path),category)!=null)throw new IllegalStateException("Duplicate capital personality mapping");
    }
}
