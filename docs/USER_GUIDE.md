# Millénaire Kingdoms 1.1.0 — User guide (English)

Minecraft 1.21.1 · NeoForge 21.1.259 · Millénaire 9.0.2 · Java 21

Millénaire Kingdoms adds a political layer on top of Millénaire's villages: kingdoms, capitals, rulers, war and peace,
conquest through real Millénaire raids, daily tax income, NPC kingdoms that take their own decisions, and independent
villages that found their own kingdom as they develop. Millénaire's
village simulation (villagers, buildings, cultures, village ownership, trade, its own raids) is not changed.

## 1. Installation

### Single player
1. Install NeoForge 21.1.259 for Minecraft 1.21.1.
2. Put the original **Millénaire 9.0.2** JAR and `millenairekingdoms-1.1.0.jar` into `mods`.
3. **Remove every older Millénaire Kingdoms JAR (0.x, 1.0.x).** Two versions cannot be loaded together.
4. **Back up the world folder** before opening an existing world (see "Save format" below).

### Dedicated server
1. Install NeoForge 21.1.259 on the server, put the same two JARs into `mods`, remove the old Kingdoms JAR.
2. Every player's client needs the same two JARs too (the kingdom screen is drawn on the client; the mod is required on both sides).
3. Settings live per world in `world/serverconfig/millenairekingdoms-server.toml` (created on first start).
4. Operator commands (`/kingdoms ...`) need OP level 2. Player commands (`/kingdom ...`) are open to everyone; every rule is checked on the server.

## 2. The kingdom screen

Open it with the **K** key, `/kingdom` or `/kingdom gui`. Rebind the key under Options → Controls → Key Binds,
category **Millénaire Kingdoms**, **Open Kingdom Management**. It does nothing while chat, the inventory or another
screen is open, and it goes through the same server checks as `/kingdom gui`. Right-clicking a capital's NPC leader still opens Millénaire's own chief
screen; the chat summary that appears with it has an **[Open the kingdom screen]** button. The screen uses Millénaire's
parchment and follows the game language (English or Turkish).

The screen only shows what the server sent. Every button sends a request, and the server checks all rules again
(ruler, reputation, distance, war, military readiness, treasury). Hovering a greyed-out button shows why it is
disabled. Players who do not rule may look at everything but cannot decide.

| Tab | Contents |
|---|---|
| **My Kingdom** | Name, capital name and culture, capital coordinates, ruler, member villages (level and income), war status, daily income, your political rights. `<` `>` show other kingdoms. |
| **Military** | War details, the capital condition (60%), conquest progress (0/2, 1/2, 2/2), the target villages by name with status and cooldown, which village sends its soldiers (or "strongest ready village"), **Start conquest raid**, waiting order and running raids, declare war / ask the NPC leader for war, make or offer peace. |
| **Diplomacy** | Known kingdoms, relation scores, war and truce state, peace offers, accepting or refusing join applications, bringing the independent village you stand in into a kingdom, founding a kingdom from your own developed village. |
| **Treasury** | Total daily income, income per village, the real deniers in the capital town hall storage (gold/silver/bronze), income waiting for storage space, **withdrawing coins**. |
| **Rulership** | Your reputation at the capital, the requirements, claiming the rule, abdicating, and every blocked right with its reason. |

## 3. Reputation and rulership

- A kingdom has at most one player ruler; a player rules at most one kingdom.
- **Claim the rule:** stand within 64 blocks of the capital centre, with Millénaire combined reputation (village +
  culture) of **at least 32768** at the capital, while the kingdom has no ruler.
- **Political decisions** (war, peace, conquest orders, applications, treasury): the ruler needs **at least 8192**
  reputation at the capital. Below that the ruler keeps the title; decisions wait until it is regained.
- **Kingdoms without a ruler** are governed by their NPC leader. A player with at least 8192 reputation may ask that
  leader at the capital to declare war on another kingdom; the leader agrees when the capitals' relation is **−30 or worse**.
- Rule is political only: Millénaire's village ownership (ownerUUID), local rights and chest locks never change.

## 4. Diplomacy and conquest rules

- A kingdom's relation is Millénaire's own relation between the two capital villages (−100 … 100).
- **Peaceful joining:** an independent village may join when its relation with the capital is **at least +50**.
  A player's village joins only by its owner's wish; joining a ruled kingdom waits for the ruler.
- **Founding:** your own Millénaire village with a town hall at level 3 or more becomes the capital of a new kingdom you rule.
- **Conquest only through real Millénaire raids.** Millénaire starts, runs and decides the raid; conquest raids take no loot.
  A failed raid gives no progress.
  - Independent village: no war needed, **1 successful raid**.
  - Normal village of a rival kingdom: an **active war** declared by the attacker, **2 successful raids** in that war.
  - Capital: opens once the attacker holds **60%** (rounded up) of the villages the defender had when the war began,
    then **2 successful raids**. When the capital falls the defending kingdom collapses and its villages pass to the victor.
- Wars are directional: only the side that declared the war conquers (the defender does not counter-conquer).
- A kingdom fights at most one active war at a time.
- **Conquest order (ruler):** in the Military tab pick a target, optionally the village that sends its soldiers, and press
  **Start conquest raid**. Commands: `/kingdom raid <targetVillageUUID> [sourceVillageUUID]`, `/kingdom raid status`, `/kingdom raid cancel`.
- **Military readiness** (the same for players and NPCs; only the operator test command skips it):
  - the sending village's type must be one Millénaire lets raid;
  - it needs at least one living raider at home, not hired, with positive strength;
  - the target's defending strength must be below twice the sender's raiding strength (Millénaire's own target rule);
  - **3 days** must have passed since the last conquest raid on that target;
  - a kingdom has at most one waiting order or running conquest raid, and a target is raided by one raid at a time.
- **Unloaded villages:** a raid starts only while the target village is loaded (a player nearby). Otherwise the order
  waits and is called off after two days. The addon never fakes battles for unloaded villages or keeps chunks loaded.

## 5. Treasury and income

- Every Minecraft day each member village except the capital yields **town hall level × 4 deniers**.
- The income is put as real Millénaire coins into the capital's **existing town hall storage** (no new chest). When the
  storage is full or not loaded the income waits and is added once there is room; a day is never paid twice.
- **Withdrawing:** the ruler, within 64 blocks of the capital's town hall, takes any amount from the Treasury tab (or
  `/kingdom treasury withdraw <deniers>`). The coins are removed with Millénaire's own storage functions and handed over
  as real coin items. Only coins are taken; other town hall goods and Millénaire's chest locks are untouched.
  - Without free inventory space nothing is taken.
  - If the stored coins cannot pay the exact amount (e.g. 10 deniers when only gold coins are there) nothing happens and
    the payable amount is shown.
  - The same coins cannot be taken twice; requests are processed one after another.
  - `/kingdom treasury` shows the treasury.

## 6. NPC kingdoms: diplomacy, war and peace

Kingdoms without a ruler decide for themselves, by default **every 3 Minecraft days**, from persistent data
(Millénaire relations and villager records), so unloaded villages take part in politics. NPC logic never declares war,
makes peace, accepts villages or sends raids for a kingdom that has a player ruler.

- **Joining:** an independent, unowned village joins a nearby kingdom whose capital it regards at +50 or better, with a
  measured chance (distance and the kingdom's size matter). To a ruled kingdom the village applies; the ruler decides.
- **Declaring war:** possible when the capitals' relation is **−70 or worse**, villages of both kingdoms are neighbours within Millénaire's relation radius (2000 blocks by default), the kingdom is militarily ready, some village
  of the target can be raided, neither side is at war and no truce binds them. The stronger side is likelier, never certain.
- **Sources of political tension** (no arbitrary constant decline):
  - Millénaire's own nightly diplomacy: different cultures start at −30 and usually worsen, the same culture starts at +50 and improves.
  - Borders: the average Millénaire relation between the two kingdoms' villages that know each other pulls the capitals'
    relation by at most 2 points a day.
  - Raids: every Millénaire raid (normal or conquest) between villages of two kingdoms lowers their capitals' relation by 10.
  - A peace treaty improves it once by 20.
- **Campaigns:** an NPC attacker at war sends soldiers from its strongest ready village (the nearest among equals) against the defender's villages (the
  capital once it is open); an NPC kingdom at peace may attack an unowned independent village that its capital is hostile
  to. An NPC order prepares for a quarter day, then starts as a real Millénaire raid under the readiness rules above.
- **Peace:** after at least **12 days** of war an NPC attacker may make peace because of no military progress (6 days),
  no ready army, a few villages taken, recovered relations, or a long war (36 days). With a ruled attacker the NPC
  defender only offers peace. Every peace starts a **10-day truce** (for players and NPCs; operator commands excepted).
  Conquered villages keep their new allegiance.

## 6a. Kingdoms founded by development (1.1.0)

An ordinary NPC village spawns normally and grows through Millénaire's own construction. Once its town hall has
**completed level 3** (Millénaire's 0-based building level; the first construction is level 0) the village may found
its own kingdom.

- **When:** **every 7 Minecraft days**, one roll for each village that is eligible on that day. Nothing is rolled
  daily or per tick. A restart never repeats a period, setting the clock back adds no attempt, and a large jump such as
  `/time add` counts as one evaluation.
- **Chance:** 5% at level 3, +5 points for each further completed level, at most 100%. Examples: level 4 10%, 5 15%,
  7 25%, 10 40%, 17 75%. The rule is the same for every culture, but the highest town hall level differs by culture
  and village type (e.g. Byzantine military village 17, Norman farming village 10, Inuit hunting village 3, most
  Seljuk villages 4–5). A village type whose town hall never reaches level 3 cannot found a kingdom this way.
- **Who:** independent villages (neither capital nor member of any kingdom) of a type Millénaire generates naturally
  and not owned by a player. Player villages and player-controlled types, hamlets, lone buildings, kingdom members, and
  current or former capitals never found. A village in a conquest raid, targeted by a pending raid order, or applying to
  join a kingdom skips that period. Villages of another kingdom never declare independence.
- **Completed town hall:** a town hall still upgrading (Millénaire status UPGRADING) does not count; the village waits
  for the next period.
- **Distance:** at least **1,500 blocks** from every living capital. A kingdom founded earlier in the same evaluation
  counts for the next villages.
- **What changes:** only the political record. The village becomes the capital and only member of a new NPC kingdom
  named "Kingdom of <village>", active and without a ruler. Buildings, construction projects, villagers, the village
  leader, Millénaire's village owner, culture, village name, inventories, economy and reputation are unchanged; the
  village is not upgraded.
- **Afterwards:** the new kingdom takes part in the existing NPC politics (accepting villages, war, conquest through real
  raids, peace and truces, daily income), shows in the kingdom screen, and a player with enough reputation can claim
  its rule.
- **Natural capitals:** the 2% natural-capital system of world generation is unchanged. Natural capitals are already
  capitals, so they never found a second kingdom.
- **Joining and founding on the same day:** NPC decisions (village joining) run first, founding second. A village that
  joined a kingdom that day cannot found; a newly founded capital is never a joining candidate.
- **Operators:** `/kingdoms founding status` lists every village's state under the rule, its level and chance, and the
  next evaluation day (read only).

## 7. Settings (`millenairekingdoms-server.toml`)

| Section / key | Default | Meaning |
|---|---|---|
| `naturalCapitals.enabled` | `true` | New natural villages may rarely become NPC capitals |
| `naturalCapitals.chancePercent` | `2.0` | Chance per eligible new natural village (%) |
| `naturalCapitals.minCapitalDistance` | `1500` | Minimum distance between capitals (blocks) |
| `npcAutonomy.enabled` | `true` | NPC kingdoms decide for themselves |
| `npcAutonomy.diplomaticJoining` | `true` | Independent villages join on their own |
| `npcAutonomy.warFrequency` | `BALANCED` | `RARE`, `BALANCED`, `FREQUENT` |
| `npcAutonomy.decisionIntervalDays` | `3` | Days between political evaluations |
| `npcAutonomy.warRelationThreshold` | `-70` | Capitals' relation at or below which an NPC may declare war |
| `npcAutonomy.raidCooldownDays` | `3` | Days between two conquest raids on the same village |
| `npcAutonomy.truceDays` | `10` | Truce days after a peace |
| `npcAutonomy.automaticCampaigns` | `true` | NPC kingdoms send their own conquest raids |
| `dynamicFounding.enabled` | `true` | Developed independent villages may found their own kingdom |
| `dynamicFounding.minimumTownhallLevel` | `3` | Lowest completed town hall level (0-based) that may found |
| `dynamicFounding.checkIntervalDays` | `7` | Minecraft days between two founding evaluations |
| `dynamicFounding.baseChancePercent` | `5.0` | Chance at the minimum level (%) |
| `dynamicFounding.chanceIncreasePerLevel` | `5.0` | Points added per further completed level (capped at 100%) |
| `dynamicFounding.minCapitalDistance` | `1500` | Minimum distance between the founder and every living capital (blocks) |

Chance formula: `clamp(baseChancePercent + (level − minimumTownhallLevel) × chanceIncreasePerLevel, 0, 100)`; 0% below
the minimum level.

## 8. Save format and upgrading

- Kingdom data lives per dimension in `data/millenairekingdoms_kingdoms.dat`. 1.0.0 and 1.1.0 write **format 6**.
  1.1.0 adds no stored field (the founding period follows from the stored ledger day); 1.0.x worlds open as they are.
- Formats 1–5 (0.1.0 … 0.5.0) load and are upgraded to format 6 on the next save. Every kingdom, membership, ruler, war,
  conquest progress and ledger entry is kept; only an empty NPC autonomy section is added, and each active war gets a
  clock starting on the stored ledger day.
- **There is no way back:** 0.x versions cannot open a format-6 file. Back up the world before upgrading.
- A damaged or newer data file is never overwritten with an empty registry; the server asks you to restore a backup.

## 9. Player commands (fallback for the screen)

```text
/kingdom                 opens the kingdom screen (or /kingdom gui)
/kingdom info            summary in chat
/kingdom claim | abdicate
/kingdom war declare <kingdom> | war request <kingdom> | war peace
/kingdom join <kingdom> | accept <villageUUID> | refuse <villageUUID> | found <name>
/kingdom raid <targetUUID> [sourceUUID] | raid status | raid cancel
/kingdom treasury | treasury withdraw <deniers>
```

Operator commands: `/kingdoms list`, `info`, `create`, `attach`, `active`, `village`, `ruler clear`,
`war declare|peace|info|list`, `relation <kingdom> <kingdom> <value>` (maintenance/testing), `autonomy status`, `founding status`,
`test conquest|status|villages` (testing only: skips military readiness).
