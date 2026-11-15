package com.millenairekingdoms.compat;

import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Portage 26.3 : classement des cultures ajoutées à Millénaire (packs 1.12 convertis, Normans CivCraft, orcs), généré
 * par gen_extra_mappings.py à partir des cultures installées. Complète les tables d'origine sans jamais les modifier.
 * 66 types de villages, 595 bâtiments.
 */
public final class ExtraCultureMappings {
    private ExtraCultureMappings() {}

    /** Types de villages -> nom d'une constante de KingdomPersonality.Category. */
    public static void villageTypes(Map<Identifier, String> m) {
        put(m, "AGRICULTURAL", "andalus/andalus_agricultural_village", "civnormans/agricole", "civnormans/survivalvillage", "german/agricole", "ghanaians/agricultural", "huari/agriculture");
        put(m, "AGRICULTURAL", "orcish/tributary", "polish/agricultural", "reconquista/reconquista_agricultural_village", "romans/vicus", "song/agriculturalvillage", "spanish/farming_village");
        put(m, "AGRICULTURAL", "teuton/farm_village", "toltec/agriculture", "viet/agriculturalvillage", "viking/agriculturalvillage");
        put(m, "ARTISAN", "andalus/andalus_trading_village", "civnormans/artisans", "german/artisans", "ghanaians/industrial", "reconquista/reconquista_trading_village", "romans/tradingvillage");
        put(m, "ARTISAN", "song/tradingvillage", "teuton/trade_village", "viet/tradingvillage", "viking/commercialtown");
        put(m, "GENERAL", "aliens/mothership", "german/grosbourg", "huari/imperial", "orcish/settlement", "song/huangchengcun", "spanish/colonial_village");
        put(m, "GENERAL", "spanish/colonialvillagetest", "viet/huangchengcun", "viking/town", "zimbabwean/palace", "zimbabwean/zgreatenclosure");
        put(m, "MILITARY", "andalus/andalus_military_village", "civnormans/militaire", "german/militaire", "ghanaians/military", "huari/military", "ogres/ogre_kraal");
        put(m, "MILITARY", "ogres/ogre_stronghold", "orcish/outpost", "polish/military", "reconquista/reconquista_military_village", "romans/militaryvillage", "song/militaryvillage");
        put(m, "MILITARY", "spanish/gran_palenque", "teuton/mil_village", "toltec/military", "viet/militaryvillage", "zimbabwean/military");
        put(m, "RELIGIOUS", "andalus/andalus_religious_village", "civnormans/ecclesiastique", "german/ecclesiastique", "ghanaians/religious", "huari/religious", "orcish/circle_of_ancients");
        put(m, "RELIGIOUS", "reconquista/reconquista_religious_village", "romans/religiousvillage", "song/religiousvillage", "teuton/reli_village", "toltec/religious", "viet/religiousvillage");
    }

    /** Bâtiments (ensembles de plans) -> nom d'une constante de RoyalInvestmentPolicy.Role. */
    public static void buildings(Map<Identifier, String> m) {
        put(m, "AGRICULTURAL", "aliens/alientreefarm", "aliens/bakery", "aliens/cattlefarm", "aliens/farm", "aliens/pigfarm", "aliens/sheepchickenfarm");
        put(m, "AGRICULTURAL", "andalus/cottonfarm", "andalus/cowfarm", "andalus/datepalmgrove", "andalus/fishery", "andalus/mill", "andalus/olivefarm");
        put(m, "AGRICULTURAL", "andalus/sheepfarm", "andalus/sugarcanefarm", "andalus/wheatfield", "civnormans/bakery", "civnormans/cattlefarm", "civnormans/chickenfarmnorm");
        put(m, "AGRICULTURAL", "civnormans/createmillstone", "civnormans/farm", "civnormans/foragershut", "civnormans/kitchen", "civnormans/largeorchard", "civnormans/pigfarm");
        put(m, "AGRICULTURAL", "civnormans/sheepchickenfarm", "civnormans/smallorchard", "civnormans/storehouse", "civnormans/wheatpaddy", "german/bakery", "german/cattlefarm");
        put(m, "AGRICULTURAL", "german/chickenfarmnorm", "german/farm", "german/largeorchard", "german/pigfarm", "german/sheepchickenfarm", "ghanaians/calabash_farm");
        put(m, "AGRICULTURAL", "ghanaians/cotton_farm", "ghanaians/fish_pond", "ghanaians/millet_farm", "ghanaians/pasture", "ghanaians/potato_farm", "huari/huaribakery");
        put(m, "AGRICULTURAL", "huari/huarichickenfarm", "huari/huarifarm", "huari/huarifishery", "huari/huarisheepfarm", "huari/huariwheatterraces", "ogres/ogre_pen");
        put(m, "AGRICULTURAL", "ogres/ogre_smokehouse", "orcish/bestiary", "orcish/boneyard", "orcish/boneyard_circle", "orcish/boneyard_settlement", "orcish/burrow");
        put(m, "AGRICULTURAL", "orcish/cages", "orcish/dyefarmhut", "orcish/farmhut", "orcish/fortress_cook", "orcish/huntinghalls", "orcish/huntinghalls_dragonhunters1");
        put(m, "AGRICULTURAL", "orcish/huntinghalls_dragonhunters2", "orcish/lumberfarm", "orcish/nomadstent", "orcish/player_hive_farm", "orcish/player_huntinghalls", "orcish/tribute_field");
        put(m, "AGRICULTURAL", "orcish/tribute_garden", "orcish/tribute_granary", "orcish/tribute_pasture", "orcish/windmill", "polish/cattlefarm", "polish/chickenpigfarm");
        put(m, "AGRICULTURAL", "polish/farm", "polish/sheepfarmpl", "reconquista/cottonfarm", "reconquista/cowfarm", "reconquista/datepalmgrove", "reconquista/fishery");
        put(m, "AGRICULTURAL", "reconquista/mill", "reconquista/olivefarm", "reconquista/sheepfarm", "reconquista/sugarcanefarm", "reconquista/vineyard", "reconquista/wheatfield");
        put(m, "AGRICULTURAL", "romans/carrotfield", "romans/dressmaker", "romans/farm", "romans/fishfarm", "romans/olivetum", "romans/pistrinum");
        put(m, "AGRICULTURAL", "romans/sheepfarm", "romans/silkfarm", "romans/vineyard", "romans/wheatfield", "romans/windmill", "song/so_chickenfarm");
        put(m, "AGRICULTURAL", "song/so_fishery", "song/so_pig_farm", "song/so_restaurant", "song/so_rice_farm", "song/so_rice_paddy", "song/so_rice_paddy2");
        put(m, "AGRICULTURAL", "song/so_silkfarm", "song/so_spicegarden", "song/so_wheat_farm", "song/so_wheatpaddy", "spanish/avis", "spanish/barnyard");
        put(m, "AGRICULTURAL", "spanish/bovis", "spanish/carrotfarm", "spanish/conuco", "spanish/farm", "spanish/grapefarm", "spanish/ingenio");
        put(m, "AGRICULTURAL", "spanish/ovis", "spanish/porcus", "teuton/lat_chickenfarm", "teuton/lat_cowfarm", "teuton/lat_farm", "teuton/lat_fishery");
        put(m, "AGRICULTURAL", "teuton/lat_house", "teuton/lat_mill", "teuton/lat_pigfarm", "teuton/lat_sheepfarm", "teuton/lat_vegfarm", "teuton/lit_chickenfarm");
        put(m, "AGRICULTURAL", "teuton/lit_cowfarm", "teuton/lit_farm", "teuton/lit_fishery", "teuton/lit_house", "teuton/lit_mill", "teuton/lit_pigfarm");
        put(m, "AGRICULTURAL", "teuton/lit_sheepfarm", "teuton/lit_vegfarm", "teuton/ltg_chickenfarm", "teuton/ltg_cowfarm", "teuton/ltg_farm", "teuton/ltg_fishery");
        put(m, "AGRICULTURAL", "teuton/ltg_house", "teuton/ltg_mill", "teuton/ltg_pigfarm", "teuton/ltg_sheepfarm", "teuton/ltg_vegfarm", "teuton/pru_chickenfarm");
        put(m, "AGRICULTURAL", "teuton/pru_cowfarm", "teuton/pru_farm", "teuton/pru_fishery", "teuton/pru_mill", "teuton/pru_pigfarm", "teuton/pru_sheepfarm");
        put(m, "AGRICULTURAL", "teuton/pru_vegfarm", "toltec/elderhouse", "toltec/tolteccacaofarm", "toltec/toltecchickenfarm", "toltec/tolteccottonfield", "toltec/toltecfarm");
        put(m, "AGRICULTURAL", "viet/so_chickenfarm", "viet/so_fishery", "viet/so_pig_farm", "viet/so_restaurant", "viet/so_rice_farm", "viet/so_rice_paddy");
        put(m, "AGRICULTURAL", "viet/so_rice_paddy2", "viet/so_silkfarm", "viet/so_spicegarden", "viet/so_wheat_farm", "viet/so_wheatpaddy", "viking/vikingcattlefarm");
        put(m, "AGRICULTURAL", "viking/vikingfarm", "viking/vikingfishery", "viking/vikingsheepfarm", "viking/vikingwheatfarm", "zimbabwean/zkitchen", "zimbabwean/zkraal");
        put(m, "AGRICULTURAL", "zimbabwean/zlargehouse", "zimbabwean/zmaizefield", "zimbabwean/zsheepfarm", "zimbabwean/zwheatfield");
        put(m, "ARTISAN", "aliens/alchemyworkshop", "aliens/carpenterhouse", "aliens/forge", "aliens/guesthouse", "aliens/inn", "aliens/lumbermanhut");
        put(m, "ARTISAN", "aliens/market", "aliens/quarry", "aliens/tavern", "andalus/architect", "andalus/bazaar", "andalus/brickkiln");
        put(m, "ARTISAN", "andalus/grove", "andalus/inn", "andalus/painterhouse", "andalus/pistachiogrove", "andalus/quarry", "andalus/smalloakgrove");
        put(m, "ARTISAN", "andalus/stonecraftsman", "andalus/tannery", "andalus/tavern", "andalus/toolsmith", "andalus/weaver", "andalus/woodcutter");
        put(m, "ARTISAN", "civnormans/alchemyworkshop", "civnormans/candlemaker", "civnormans/carpenterhouse", "civnormans/forge", "civnormans/glassblower", "civnormans/grove");
        put(m, "ARTISAN", "civnormans/guesthouse", "civnormans/inn", "civnormans/kiln", "civnormans/lumberjackhut", "civnormans/lumbermanhut", "civnormans/market");
        put(m, "ARTISAN", "civnormans/potterhouse", "civnormans/quarry", "civnormans/tavern", "civnormans/weaverhouse", "civnormans/workshop", "german/alchemyworkshop");
        put(m, "ARTISAN", "german/carpenterhouse", "german/forge", "german/glassblower", "german/grove", "german/guesthouse", "german/inn");
        put(m, "ARTISAN", "german/lumbermanhut", "german/market", "german/quarry", "german/tavern", "german/weaverhouse", "ghanaians/caravan_halt");
        put(m, "ARTISAN", "ghanaians/fletcher", "ghanaians/forge", "ghanaians/gold_mine", "ghanaians/inn", "ghanaians/kiln", "ghanaians/lumberman");
        put(m, "ARTISAN", "ghanaians/market", "ghanaians/painter", "ghanaians/quarry", "ghanaians/sculptor", "huari/huariarmyforge", "huari/huaribricks");
        put(m, "ARTISAN", "huari/huaricrafter", "huari/huariforge", "huari/huarigrove", "huari/huariinn", "huari/huarilumberhouse", "huari/huarimarket");
        put(m, "ARTISAN", "huari/huariquarry", "huari/huariweaverhouse", "ogres/grove", "ogres/ogre_forge", "ogres/ogre_lumbercamp", "orcish/bowmaker");
        put(m, "ARTISAN", "orcish/brewcart", "orcish/builderstent", "orcish/fortress_bowmaker", "orcish/fortress_builders", "orcish/fortress_slavetrader", "orcish/fortress_toolmaker");
        put(m, "ARTISAN", "orcish/grove", "orcish/marketplace", "orcish/player_hive_lumber", "orcish/slavepen", "orcish/toolmaker", "orcish/toolsmith");
        put(m, "ARTISAN", "orcish/tribute_smithy", "orcish/warsmith", "polish/forge", "polish/glassblowerpolish", "polish/grovepolish", "polish/lumbermanhutpolish");
        put(m, "ARTISAN", "polish/meadbrewery", "polish/quarry", "polish/tavern", "reconquista/architect", "reconquista/bazaar", "reconquista/brickkiln");
        put(m, "ARTISAN", "reconquista/grove", "reconquista/inn", "reconquista/painterhouse", "reconquista/pistachiogrove", "reconquista/quarry", "reconquista/smalloakgrove");
        put(m, "ARTISAN", "reconquista/stonecraftsman", "reconquista/tannery", "reconquista/tavern", "reconquista/toolsmith", "reconquista/weaver", "reconquista/winery");
        put(m, "ARTISAN", "reconquista/woodcutter", "romans/acaciagrove", "romans/acaciagrovesmall", "romans/architect", "romans/inn", "romans/market");
        put(m, "ARTISAN", "romans/mason", "romans/mine", "romans/pinegrove", "romans/pinegrovesmall", "romans/smelter", "romans/taverna");
        put(m, "ARTISAN", "romans/toolsmith", "romans/venalicium", "romans/woodcutter", "romans/workshop", "song/alchemyworkshop", "song/so_acaciagrove");
        put(m, "ARTISAN", "song/so_armyforge", "song/so_brickkiln", "song/so_crafter", "song/so_fancygrove", "song/so_forge", "song/so_grove");
        put(m, "ARTISAN", "song/so_guesthouse", "song/so_gunpowder", "song/so_inn", "song/so_lumberman", "song/so_market", "song/so_paper_currency");
        put(m, "ARTISAN", "song/so_quarry", "song/so_scribe", "song/so_silkfactory", "song/so_steelworks", "song/so_tavern", "spanish/bow");
        put(m, "ARTISAN", "spanish/ceramicoven", "spanish/ceramisthouse", "spanish/forest", "spanish/forge", "spanish/inn", "spanish/market");
        put(m, "ARTISAN", "spanish/merchant_post", "spanish/mine", "spanish/tavern", "teuton/lat_buildhouse", "teuton/lat_dyer", "teuton/lat_glassmaker");
        put(m, "ARTISAN", "teuton/lat_grove", "teuton/lat_inn", "teuton/lat_market", "teuton/lat_mason", "teuton/lat_mine", "teuton/lat_smith");
        put(m, "ARTISAN", "teuton/lat_tavern", "teuton/lit_buildhouse", "teuton/lit_dyer", "teuton/lit_glassmaker", "teuton/lit_grove", "teuton/lit_inn");
        put(m, "ARTISAN", "teuton/lit_market", "teuton/lit_mason", "teuton/lit_mine", "teuton/lit_smith", "teuton/lit_tavern", "teuton/ltg_buildhouse");
        put(m, "ARTISAN", "teuton/ltg_dyer", "teuton/ltg_glassmaker", "teuton/ltg_grove", "teuton/ltg_inn", "teuton/ltg_market", "teuton/ltg_mason");
        put(m, "ARTISAN", "teuton/ltg_mine", "teuton/ltg_smith", "teuton/ltg_tavern", "teuton/pru_buildhouse", "teuton/pru_dyer", "teuton/pru_glassmaker");
        put(m, "ARTISAN", "teuton/pru_grove", "teuton/pru_inn", "teuton/pru_market", "teuton/pru_mason", "teuton/pru_mine", "teuton/pru_smith");
        put(m, "ARTISAN", "teuton/pru_tavern", "toltec/brickkiln", "toltec/painterhouse", "toltec/toltecarmyforge", "toltec/tolteccrafter", "toltec/tolteccraftsmanhouse");
        put(m, "ARTISAN", "toltec/toltecforge", "toltec/toltecgrove", "toltec/toltecguesthouse", "toltec/toltecinn", "toltec/tolteclumberhouse", "toltec/toltecmarket");
        put(m, "ARTISAN", "toltec/toltecobsidiancrafter", "toltec/toltecquarry", "toltec/toltecsculptorhouse", "toltec/weaverhouse", "viet/alchemyworkshop", "viet/so_acaciagrove");
        put(m, "ARTISAN", "viet/so_armyforge", "viet/so_brickkiln", "viet/so_crafter", "viet/so_fancygrove", "viet/so_forge", "viet/so_grove");
        put(m, "ARTISAN", "viet/so_guesthouse", "viet/so_gunpowder", "viet/so_inn", "viet/so_lumberman", "viet/so_market", "viet/so_paper_currency");
        put(m, "ARTISAN", "viet/so_quarry", "viet/so_scribe", "viet/so_silkfactory", "viet/so_steelworks", "viet/so_tavern", "viking/vikingforge");
        put(m, "ARTISAN", "viking/vikinggrove", "viking/vikinggrove2", "viking/vikinginn", "viking/vikinglumberman", "viking/vikingmarket", "viking/vikingquarry");
        put(m, "ARTISAN", "viking/vikingweaponforge", "viking/vikingworkshop", "zimbabwean/zclaypit", "zimbabwean/zgrove", "zimbabwean/zinn", "zimbabwean/zlumbermanh");
        put(m, "ARTISAN", "zimbabwean/zmarket", "zimbabwean/zquarry", "zimbabwean/zthatcher", "zimbabwean/ztoolsmith", "zimbabwean/zworkshop");
        put(m, "CIVIC", "aliens/archives", "andalus/archives", "andalus/fountain", "andalus/hamambath", "andalus/well", "civnormans/archives");
        put(m, "CIVIC", "civnormans/fountain", "civnormans/largefountain", "civnormans/meetingspot", "civnormans/well", "german/archives", "german/fountain");
        put(m, "CIVIC", "german/largefountain", "german/well", "ghanaians/archive", "ghanaians/well", "ogres/ogre_feast", "orcish/orcish_well");
        put(m, "CIVIC", "orcish/tribute_square", "reconquista/archives", "reconquista/fountain", "reconquista/hamambath", "reconquista/well", "romans/amphitheatrum");
        put(m, "CIVIC", "romans/archives", "romans/arcus", "romans/domus", "romans/thermae", "song/so_well", "spanish/archives");
        put(m, "CIVIC", "teuton/lat_hospital", "teuton/lat_park", "teuton/lat_well", "teuton/lit_hospital", "teuton/lit_park", "teuton/lit_well");
        put(m, "CIVIC", "teuton/ltg_hospital", "teuton/ltg_park", "teuton/ltg_well", "teuton/pru_hospital", "teuton/pru_park", "teuton/pru_well");
        put(m, "CIVIC", "toltec/toltecarchives", "toltec/toltecballcourt", "toltec/tolteclibrary", "viet/so_well", "viking/vikingwell");
        put(m, "MILITARY", "aliens/armoury", "aliens/guardhouse", "andalus/armoury", "andalus/barracks", "andalus/mazmorra", "andalus/watchtower");
        put(m, "MILITARY", "civnormans/armoury", "civnormans/guardhouse", "civnormans/watchtower", "german/armoury", "german/guardhouse", "german/watchtower");
        put(m, "MILITARY", "ghanaians/armory", "ghanaians/barracks", "ghanaians/barracks_a2_soldier_right", "ghanaians/guard_tower", "ghanaians/merc_hall", "huari/huariguard");
        put(m, "MILITARY", "ogres/ogre_pit", "ogres/ogre_watchrock", "orcish/armormaker", "orcish/armorsmith", "orcish/barracks", "orcish/boneyardwatchtower");
        put(m, "MILITARY", "orcish/boneyardwatchtower_circle", "orcish/boneyardwatchtower_settlement", "orcish/fortress", "orcish/fortress_arenatower1", "orcish/fortress_arenatower2", "orcish/fortress_barracks1");
        put(m, "MILITARY", "orcish/fortress_barracks2", "orcish/fortress_circle", "orcish/fortress_guardtower1", "orcish/fortress_guardtower2", "orcish/fortress_ogre", "orcish/fortress_warsmith");
        put(m, "MILITARY", "orcish/player_barracks", "orcish/tribute_garrison", "orcish/tribute_levypen", "orcish/tribute_tower", "orcish/watchtower", "polish/barracks");
        put(m, "MILITARY", "polish/watchtower", "reconquista/armoury", "reconquista/barracks", "reconquista/watchtower", "romans/ludus", "romans/watchtower");
        put(m, "MILITARY", "song/so_barracks", "song/so_guardtower", "song/so_guardtower_f", "song/so_tower", "spanish/atalaya", "spanish/barracks");
        put(m, "MILITARY", "spanish/guardhouse", "teuton/lat_barracks", "teuton/lit_barracks", "teuton/ltg_barracks", "teuton/pru_barracks", "toltec/toltecguard");
        put(m, "MILITARY", "toltec/toltecsoldierhouse", "viet/so_barracks", "viet/so_guardtower", "viet/so_guardtower_f", "viet/so_tower", "viking/merc_house");
        put(m, "MILITARY", "viking/vikingguard", "viking/vikingguardbed1", "viking/vikingguardbed2", "viking/vikingguardbed3", "viking/vikingwatchtower", "zimbabwean/zarmoury");
        put(m, "MILITARY", "zimbabwean/zbarracks");
        put(m, "RELIGIOUS", "aliens/church", "aliens/presbytery", "andalus/chapel", "andalus/femalemosque", "andalus/imamshouse", "andalus/madrasa");
        put(m, "RELIGIOUS", "andalus/mosque", "andalus/synagogue", "civnormans/chapel", "civnormans/church", "civnormans/presbytery", "german/church");
        put(m, "RELIGIOUS", "german/presbytery", "ghanaians/imam_house", "ghanaians/mosque", "huari/huaritemple", "ogres/ogre_bonecircle", "ogres/ogre_totem");
        put(m, "RELIGIOUS", "orcish/circle_shamans", "orcish/tribute_shrine", "polish/churchpolish", "polish/presbyterypolish", "reconquista/chapel", "reconquista/convento_merced");
        put(m, "RELIGIOUS", "reconquista/femalemosque", "reconquista/imamshouse", "reconquista/madrasa", "reconquista/mosque", "romans/chapel", "romans/presbytery");
        put(m, "RELIGIOUS", "song/so_pagoda", "song/so_shrine", "song/so_temple", "spanish/bishophouse", "spanish/capilla", "spanish/cathedral");
        put(m, "RELIGIOUS", "teuton/lat_church", "teuton/lit_church", "teuton/ltg_church", "teuton/pru_church", "toltec/toltectemple", "viet/so_pagoda");
        put(m, "RELIGIOUS", "viet/so_shrine", "viet/so_temple", "viking/vikingtemple", "zimbabwean/zwitchdoctorhut");
    }

    private static void put(Map<Identifier, String> m, String category, String... paths) {
        for (var p : paths) m.putIfAbsent(Identifier.fromNamespaceAndPath("millenaire", p), category);
    }
}
