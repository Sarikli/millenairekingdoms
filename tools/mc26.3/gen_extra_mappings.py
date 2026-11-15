# Millénaire Kingdoms ne classe (personnalité du royaume, rôle des bâtiments) que les 7 cultures de base : un royaume dont la
# capitale est d'une culture ajoutée (Reconquista, Đại Việt…) est « non spécialisé » et n'a donc ni stratégie NPC, ni
# investissements royaux, ni bâtiments militaires comptés dans sa force. Ce script lit les cultures réellement installées et
# écrit une table explicite (compat/ExtraCultureMappings.java), classée par mots-clés et relue à la main ; à relancer après
# l'ajout d'une culture, puis recompiler (build.py).
# Usage : python3 gen_extra_mappings.py <millenaire-custom> <fichier Java de sortie>
import os,sys,json,glob,re,collections
MC,OUT=sys.argv[1],sys.argv[2]
BASE={"norman","seljuk","byzantines","indian","japanese","mayan","inuits"}
OVR={"orcish/fortress_cook":"AGRICULTURAL","orcish/fortress_builders":"ARTISAN","orcish/fortress_slavetrader":"ARTISAN",
     "orcish/fortress_toolmaker":"ARTISAN","orcish/toolmaker":"ARTISAN","orcish/fortress_bowmaker":"ARTISAN",
     "toltec/tolteclargepeasanthouse":None,"spanish/merchant_post":"ARTISAN",
     "andalus/mazmorra":"MILITARY","ogres/ogre_forge":"ARTISAN","ogres/ogre_pit":"MILITARY","ogres/ogre_bonecircle":"RELIGIOUS","orcish/tribute_field":"AGRICULTURAL","orcish/tribute_levypen":"MILITARY","orcish/tribute_tower":"MILITARY","ogres/ogre_smokehouse":"AGRICULTURAL","romans/pistrinum":"AGRICULTURAL","romans/arcus":"CIVIC","romans/domus":"CIVIC","spanish/atalaya":"MILITARY","spanish/conuco":"AGRICULTURAL","reconquista/convento_merced":"RELIGIOUS","romans/venalicium":"ARTISAN","ghanaians/caravan_halt":"ARTISAN","orcish/slavepen":"ARTISAN","romans/ludus":"MILITARY","spanish/ingenio":"AGRICULTURAL","romans/amphitheatrum":"CIVIC","romans/thermae":"CIVIC","ogres/ogre_pen":"AGRICULTURAL","ogres/ogre_feast":"CIVIC","ogres/ogre_totem":"RELIGIOUS","ogres/ogre_watchrock":"MILITARY","ogres/ogre_lumbercamp":"ARTISAN","orcish/tribute_granary":"AGRICULTURAL","orcish/tribute_garden":"AGRICULTURAL","orcish/tribute_pasture":"AGRICULTURAL","orcish/tribute_square":"CIVIC","orcish/tribute_shrine":"RELIGIOUS","orcish/tribute_garrison":"MILITARY","orcish/tribute_smithy":"ARTISAN"}
VOVR={"ogres/ogre_kraal":"MILITARY","ogres/ogre_stronghold":"MILITARY","orcish/tributary":"AGRICULTURAL","spanish/gran_palenque":"MILITARY","romans/vicus":"AGRICULTURAL"}   # villages classés à la main
def kw(name,table):
    for cat,words in table:
        for w in words:
            if re.search(w,name): return cat
    return None
VT=[("GENERAL",[r"mothership",r"greatenclosure"]),("RELIGIOUS",[r"circle_of_ancients"]),("MILITARY",[r"milit",r"^mil_",r"fort",r"war",r"army",r"castle",r"garrison",r"citadel",r"hive",r"stronghold",r"warcamp",r"barrack"]),
    ("RELIGIOUS",[r"relig",r"eccl",r"temple",r"monast",r"abbey",r"abbat",r"^reli_",r"holy",r"pagoda",r"sacred",r"shrine",r"cathedral"]),
    ("AGRICULTURAL",[r"agri",r"farm",r"agricol",r"rural",r"hunt",r"fishing",r"pastoral",r"herd",r"nong",r"peasant",r"harvest",r"survival"]),
    ("ARTISAN",[r"trad",r"artisan",r"market",r"industr",r"merchant",r"craft",r"mining",r"mine",r"port",r"commerc"]),
    ("GENERAL",[r"capital",r"palace",r"grosbourg",r"bourg",r"royal",r"imperial",r"huangcheng",r"settlement",r"city",r"town",r"kingdom"])]
BR=[("MILITARY",[r"armou?r",r"barrack",r"guard",r"watch",r"tower",r"fort",r"soldier",r"archer",r"milit",r"garrison",r"merc_",r"mercenar",r"warrior",r"weaponsmith",r"armory",r"knight",r"training",r"bastion",r"keep$"]),
    ("RELIGIOUS",[r"presbyter",r"bishop",r"capilla",r"church",r"chapel",r"mosque",r"temple",r"shrine",r"pagoda",r"abbey",r"abbaye",r"monast",r"priest",r"imam",r"synagog",r"altar",r"cathedral",r"shaman",r"totem",r"relig",r"prayer",r"tomb",r"monk",r"cross",r"kanis",r"madrasa",r"pyramid",r"sacrific",r"holy",r"witch"]),
    ("AGRICULTURAL",[r"farm",r"field",r"paddy",r"orchard",r"vineyard",r"fish",r"pasture",r"cattle",r"sheep",r"chicken",r"pig",r"cow",r"mill",r"bak",r"kitchen",r"apiar",r"bee",r"plantation",r"garden",r"granar",r"hunt",r"rice",r"wheat",r"millet",r"maize",r"corn",r"olive",r"date",r"grape",r"melon",r"silkworm",r"tea",r"butcher",r"cook",r"food",r"restaurant",r"herb",r"stable",r"llama",r"alpaca",r"pumpkin",r"potato",r"veg",r"carrot",r"barn",r"forag",r"storehouse"]),
    ("ARTISAN",[r"guesthouse",r"claypit",r"thatch",r"forge",r"smith",r"lumber",r"wood",r"grove",r"quarry",r"mine",r"market",r"bazaar",r"inn$",r"inn_",r"tavern",r"weav",r"kiln",r"brick",r"carpent",r"mason",r"glass",r"potter",r"ceram",r"dye",r"tann",r"workshop",r"craft",r"alchem",r"paint",r"sculpt",r"stonecraft",r"merchant",r"trade",r"silk",r"paper",r"calligraph",r"brew",r"winer",r"sake",r"souk",r"shop",r"steel",r"iron",r"gunpowder",r"jewel",r"loom",r"textile",r"candle",r"copper",r"gold",r"cotton",r"saw",r"mint",r"bank",r"guild",r"build",r"architect",r"fletcher",r"bow",r"leather",r"scribe",r"bookbind",r"spinn",r"ale",r"beer"]),
    ("CIVIC",[r"archive",r"well",r"fountain",r"bath",r"hamam",r"library",r"school",r"residence",r"hospital",r"infirm",r"park",r"plaza",r"square",r"garden_",r"court",r"theat",r"stage",r"meeting",r"public",r"cistern",r"aqueduct",r"statue",r"monument"])]
vt={}; br={}; skip=collections.Counter(); unk_v=[]; unk_b=[]
dirs=glob.glob(MC+"/*_converted/cultures/*")+[d for d in glob.glob(MC+"/*/cultures/*") if not d.split("/")[-3].endswith("_converted") and glob.glob(d+"/villages/*.json")]
for d in sorted(set(dirs)):
    c=os.path.basename(d)
    if c in BASE: continue
    for f in sorted(glob.glob(d+"/villages/*.json")):
        j=json.load(open(f,encoding="utf-8")); n=os.path.basename(f)[:-5]
        if j.get("player_controlled") or j.get("lone_building") or j.get("weight",1)<=0: skip["village (joueur/isolé/poids 0)"]+=1; continue
        k=VOVR.get(f"{c}/{n}") or kw(n,VT) or kw(str(j.get("name","")).lower(),VT)
        if not k:
            cen=[e["plan"].split("/")[-1] for e in j.get("layout",[]) if e.get("role")=="centre"]
            k=kw(" ".join(cen),VT)
        if k: vt[f"{c}/{n}"]=k
        else: unk_v.append(f"{c}/{n}")
    for f in sorted(glob.glob(d+"/buildings/**/*.json",recursive=True)):
        rel=os.path.relpath(f,d+"/buildings").replace(os.sep,"/"); cat=rel.split("/")[0]
        if cat in ("townhalls","townhall","walls","lone","player","nodes","pathnodes"): skip["bâtiment "+cat]+=1; continue
        j=json.load(open(f,encoding="utf-8")); bid=j.get("building_id",os.path.basename(f)[:-5])
        if "_a_" in bid or re.search(r"_[a-z]?\d+$",bid) and bid.split("_")[-1].isdigit(): skip["sous-bâtiment"]+=1; continue
        s=(bid+" "+" ".join(j.get("tags",[]))).lower()
        k=kw(bid.lower(),BR) or kw(s,BR)
        if f"{c}/{bid}" in OVR: k=OVR[f"{c}/{bid}"]
        if k: br[f"{c}/{bid}"]=k
        else: unk_b.append(f"{c}/{bid}")
def jmap(m,fn):
    by=collections.defaultdict(list)
    for k,v in sorted(m.items()): by[v].append(k)
    out=[]
    for cat,ids in sorted(by.items()):
        for i in range(0,len(ids),6): out.append(f'        {fn}(m, "{cat}", '+", ".join(f'"{x}"' for x in ids[i:i+6])+");")
    return "\n".join(out)
java=f'''package com.millenairekingdoms.compat;

import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Portage 26.3 : classement des cultures ajoutées à Millénaire (packs 1.12 convertis, Normans CivCraft, orcs), généré
 * par gen_extra_mappings.py à partir des cultures installées. Complète les tables d'origine sans jamais les modifier.
 * {len(vt)} types de villages, {len(br)} bâtiments.
 */
public final class ExtraCultureMappings {{
    private ExtraCultureMappings() {{}}

    /** Types de villages -> nom d'une constante de KingdomPersonality.Category. */
    public static void villageTypes(Map<Identifier, String> m) {{
{jmap(vt,"put")}
    }}

    /** Bâtiments (ensembles de plans) -> nom d'une constante de RoyalInvestmentPolicy.Role. */
    public static void buildings(Map<Identifier, String> m) {{
{jmap(br,"put")}
    }}

    private static void put(Map<Identifier, String> m, String category, String... paths) {{
        for (var p : paths) m.putIfAbsent(Identifier.fromNamespaceAndPath("millenaire", p), category);
    }}
}}
'''
open(OUT,"w",encoding="utf-8").write(java)
print("types de villages :",collections.Counter(vt.values()),"; non classés :",unk_v)
print("bâtiments :",collections.Counter(br.values()),"; non classés :",len(unk_b)); print("  ",unk_b); print("ignorés :",dict(skip))
