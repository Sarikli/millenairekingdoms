package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.personality.KingdomPersonality.Category;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Explicit audited native building functions, never culture/name substring guesses. These weights do not authorize
 * spending or invent a build: the later planner must first prove a real need and use the original eligibility catalog. */
public final class RoyalInvestmentPolicy {
    public enum Role { MILITARY, AGRICULTURAL, RELIGIOUS, ARTISAN, CIVIC, UNCLASSIFIED }
    public record Context(KingdomPersonality.Profile profile,boolean war,boolean threatened) {}
    private static final Map<ResourceLocation,Role> ROLES=roles();
    private RoyalInvestmentPolicy(){}
    public static Map<ResourceLocation,Role> buildings(){return ROLES;}
    public static Role role(ResourceLocation plan){return ROLES.getOrDefault(plan,Role.UNCLASSIFIED);}
    /** Real active war and original saved enemy village positions only; no chunk loads or political mutation. */
    public static Context context(ServerLevel level,KingdomSavedData data,KingdomSavedData.Kingdom kingdom,UUID village){
        if(!kingdom.id().equals(data.owner(village)))throw new IllegalArgumentException("Investment context requires a current member village");
        var war=data.activeWar(kingdom.id());var own=PoliticsService.village(level,village);boolean threatened=false;
        if(war!=null && own!=null){
            var enemy=war.attackerKingdom().equals(kingdom.id()) ? war.defenderKingdom() : war.attackerKingdom();long radius=AutonomyService.joinRadius();
            for(var member:data.members(enemy)){var other=PoliticsService.village(level,member);if(other!=null
                    && Math.hypot((double)own.getCenter().getX()-other.getCenter().getX(),(double)own.getCenter().getZ()-other.getCenter().getZ())<=radius){threatened=true;break;}}
        }
        return new Context(KingdomPersonality.forCapital(level,kingdom),war!=null,threatened);
    }
    public static int weight(Context context,Role role,RoyalNpcConfig.Weights settings){return weight(context.profile().category(),role,context.war(),context.threatened(),settings);}
    public static String describe(Context context){var s=RoyalNpcConfig.weights();return "base="+context.profile().category()+" | war="+context.war()+" | threatened="+context.threatened()
            +" | militaryWeight="+weight(context,Role.MILITARY,s)+" | agriculturalWeight="+weight(context,Role.AGRICULTURAL,s)+" | religiousWeight="+weight(context,Role.RELIGIOUS,s)
            +" | artisanWeight="+weight(context,Role.ARTISAN,s)+" | civicWeight="+weight(context,Role.CIVIC,s)+" | aggressionFactor="+s.aggression(context.profile().category());}
    public static int weight(Category base,Role role,boolean war,boolean threatened,RoyalNpcConfig.Weights settings){
        if(role==Role.UNCLASSIFIED)return 0;
        boolean preferred=switch(base){case MILITARY->role==Role.MILITARY;case AGRICULTURAL->role==Role.AGRICULTURAL;case RELIGIOUS->role==Role.RELIGIOUS;case ARTISAN->role==Role.ARTISAN;case GENERAL->role==Role.CIVIC;};
        return (preferred ? settings.preferred() : settings.ordinary())+(role==Role.MILITARY ? (war ? settings.warMilitary() : 0)+(threatened ? settings.borderMilitary() : 0) : 0);
    }
    public static double aggression(double existingChance,Category base,RoyalNpcConfig.Weights settings){
        if(!Double.isFinite(existingChance) || existingChance<0 || existingChance>1)throw new IllegalArgumentException("Invalid existing NPC chance");
        return Math.min(0.95,existingChance*settings.aggression(base));
    }
    private static Map<ResourceLocation,Role> roles(){
        var r=new LinkedHashMap<ResourceLocation,Role>();
        add(r,Role.MILITARY,"byzantines","watchtower");
        add(r,Role.MILITARY,"indian","armyforge","soldierhouse");
        add(r,Role.MILITARY,"inuits","armourerhouse","armoury");
        add(r,Role.MILITARY,"japanese","japanesearmyforge","japaneselookout","japanesesamuraih");
        add(r,Role.MILITARY,"mayan","mayanarmyforge","mayanguard","mayanwatchtower");
        add(r,Role.MILITARY,"norman","armoury","guardhouse","watchtower","largeguardhouse","guardroom");
        add(r,Role.MILITARY,"seljuk","armoury","barracks","barrackssmall","watchtower");
        add(r,Role.RELIGIOUS,"byzantines","chapel","presbytery");
        add(r,Role.RELIGIOUS,"indian","pandithouse","villagetemple");
        add(r,Role.RELIGIOUS,"inuits","animistic","offering","offering1","offering2","shaman");
        add(r,Role.RELIGIOUS,"japanese","japanesepagoda","japaneseshintoshrine","japanesezen");
        add(r,Role.RELIGIOUS,"mayan","mayanalter","mayancalendar","mayanshamangrounds","mayantemple");
        add(r,Role.RELIGIOUS,"norman","presbytery","abbaye","pilgrimhall","church");
        add(r,Role.RELIGIOUS,"seljuk","abdesthane","imamshouse","mosque","smallmosque");
        add(r,Role.AGRICULTURAL,"byzantines","carrotfield","vineyard","wheatfield","farm","fishfarm","sheepfarm","windmill");
        add(r,Role.AGRICULTURAL,"indian","paddy","spicegarden","sugarplantation");
        add(r,Role.AGRICULTURAL,"inuits","foodpreparer","foodprephouse","hunter","hunterex","lake","farmp","farmw");
        add(r,Role.AGRICULTURAL,"japanese","chickenfarm","japanesefarm","japanesefishery","japanesekitchen","japanesepaddy","squidfarm");
        add(r,Role.AGRICULTURAL,"mayan","mayanbakery","mayancacaofarm","mayancornfarm","mayansheepchickenfarm");
        add(r,Role.AGRICULTURAL,"norman","cattlefarm","chickenfarmnorm","farm","largeorchard","pigfarm","sheepchickenfarm","bakery","smallorchard");
        add(r,Role.AGRICULTURAL,"seljuk","cowfarm","fishery","kitchen","mill","olivefarm","pistachiotrees","sheepfarm","sugarcanefarm","wheatpaddy");
        add(r,Role.ARTISAN,"byzantines","acaciagrove","acaciagrovesmall","pinegrove","pinegrovesmall","architect","dressmaker","mason","mine","silkfarm","smelter","toolsmith","woodcutter","market","sandpit","workshop","inn","taverna");
        add(r,Role.ARTISAN,"indian","bazaar","brickkiln","cottonfield","craftsmanhouse","forge","grove","lumbermanhouse","painterhouse","quarry","scultorhouse","weaverhouse","inn","guesthouse");
        add(r,Role.ARTISAN,"inuits","carver","lumberman","market","sodder","grove","guesthouse");
        add(r,Role.ARTISAN,"japanese","japanesecrafter","japaneseforge","japanesegrove","japaneselmh","japanesemarket","japanesequarry","japaneseinn");
        add(r,Role.ARTISAN,"mayan","mayancrafter","mayangrove","mayanlumber","mayanmarket","mayanmining","mayanobsidiancrafter","mayansculptorhouse","mayaninn");
        add(r,Role.ARTISAN,"norman","alchemyworkshop","carpenterhouse","forge","glassblower","lumbermanhut","quarry","weaverhouse","grove","market","inn","largeinn","guesthouse","tavern");
        add(r,Role.ARTISAN,"seljuk","brick","cottonfarm","cottonfield","lumbermanshouse","market","mine_brick","stonecraftsman","toolsmith","weaver","inn");
        add(r,Role.CIVIC,"byzantines","archives");
        add(r,Role.CIVIC,"indian","archives");
        add(r,Role.CIVIC,"japanese","bath","japanesearchive","well");
        add(r,Role.CIVIC,"mayan","mayanballcourt","mayanlibrary");
        add(r,Role.CIVIC,"norman","archives","fountain","largefountain","well","residence");
        add(r,Role.CIVIC,"seljuk","bigwell","cesme","golgealti","hamambath","well");
        return Collections.unmodifiableMap(r);
    }
    private static void add(Map<ResourceLocation,Role> roles,Role role,String culture,String... names){
        for(var name:names)if(roles.put(ResourceLocation.fromNamespaceAndPath("millenaire",culture+"/"+name),role)!=null)throw new IllegalStateException("Duplicate native building function mapping");
    }
}
