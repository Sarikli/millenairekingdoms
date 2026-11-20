# Millénaire Kingdoms

> **1.2.4 — By Royal Decree.** [Release](https://github.com/Sarikli/millenairekingdoms/releases/tag/1.2.4) · [changelog](CHANGELOG.md) · [current architecture](docs/ARCHITECTURE.md) · [contributing](CONTRIBUTING.md).
> Adds kingdom-wide Royal Roads with organic terrain shaping and reusable multi-arch river bridges, hybrid physical caravans and bannered marching armies (including individual player banditry), player-selectable By Player / Automatic raid mode, chunk-free virtual economy simulation for unloaded villages, and Millénaire 9.1.1 compatibility.

A medieval kingdom layer for [Millénaire](https://millenaire.org) villages: NPC kingdoms with their own capitals,
player rulers, diplomacy, war and peace, conquest through native raids and persistent unattended strategic engagements, daily taxes paid into a capital
treasury, royal highways, physical caravans and armies, and independent villages that grow into kingdoms of their own. Everything is managed from an in-game
kingdom screen, so no commands need to be memorised.

Original village ownership, inventories, builders and physical combat remain authoritative in Millénaire.
Addon generation hooks use original building plans to complete natural capitals; the original dependency JAR is never modified.

| | |
|---|---|
| Stable version | 1.2.4 — By Royal Decree |
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.259 |
| Required dependency | Millénaire 9.1.1 |
| Java | 21 |
| Mod ID | `millenairekingdoms` |
| Network protocol | 12 (matching 1.2.4 client and server required) |
| Languages | All 26 original Millénaire locales; new translations are AI/machine-assisted drafts pending native review |

See the [release notes](releases/1.2.4/RELEASE_NOTES.md), [changelog](CHANGELOG.md) and [architecture guide](docs/ARCHITECTURE.md).

## Features

- **Kingdom screen.** Press **K** (rebindable under Controls → Key Binds → Millénaire Kingdoms) or run `/kingdom`.
  Five main tabs: My Kingdom, Military, Diplomacy, Treasury and Rulership. Open **Construction**, **Shipping**, and **Roads** using the buttons inside Rulership; click Rulership or press Esc to return. Greyed-out buttons explain why they are disabled,
  and every action is checked again on the server.
- **Chronicles and political atlas.** Open History/Tarihçe at the kingdom screen's bottom-left. Read kingdom/world events, search fallen archives, filter dates/types and locate known historical places on a separate full-screen political atlas. All settlements of one kingdom share a persistent color/name; native village icons, capital crowns, zoom/pan and information connect to village/kingdom history. Ocean scanning and territorial polygon rendering are removed. Old founding dates stay unknown; original NPC work and real financial/cargo rules remain.
- **Player rulership.** Claim an unruled kingdom at its capital with a combined Millénaire reputation of 160000 or more.
  Requesting war from an NPC leader requires 32796 reputation; political decisions need 8192 (existing saved rulers below 160000 retain their titles). A kingdom has at most one ruler, and a player rules at most one kingdom.
- **Conquest & selectable raid mode.** Active regions use original native raids; unloaded NPC regions (or player orders set to **Automatic**) can resolve a persistent opposed-dice engagement using actual native military records. Player conquest orders default to **By Player** (waiting at the target for live combat) and can be toggled to **Automatic** in the Military tab. An independent village falls
  after one victory and a rival kingdom's village after two victories during an active war. A capital can be attacked
  once the attacker holds 60% of the villages the defender had when the war began; two victories there end that kingdom.
- **Raid orders & upfront expedition cost.** Rulers pick the target and the village that sends the soldiers. Military readiness, a one-day
  cooldown per target, one running raid at a time, and an upfront expedition fee `ceil(S * (1000 + L) / 10000)` deniers (based on source raiding strength `S` and horizontal distance `L`, reduced by 25% when source or target is road-connected) apply. Cancelling before departure refunds the fee; once departed, it is nonrefundable. NPC kingdoms use the same pricing while retaining an economic reserve `max(32, 2 * dailyIncome)` and selecting sources with `S / D >= 1.30`.
- **NPC kingdoms decide for themselves.** Every day a kingdom without a player ruler weighs accepting villages,
  declaring war on a hostile neighbour (relation −70 or worse), sending conquest raids and making peace (after day 6,
  followed by a five-day truce). A kingdom with a player ruler is never driven by this automation.
- **Diplomacy.** Kingdom relations follow Millénaire's relation between the two capitals. Border villages and raids
  create tension, peace treaties improve relations, and independent villages may join a kingdom on their own once their
  relation with its capital reaches +50.
- **Income and treasury.** Each member village produces its town hall level × 4 deniers per day (+2 deniers/day when connected to the capital road network). Real coins go into the
  capital town hall's storage, and the ruler can withdraw them from the kingdom screen while at the capital.
- **Conserving treasury maintenance.** Useful bounded maintenance converts 64 bronze → 1 silver → (64 silver) 1 gold in one verified physical chest, conserving exact value and preserving payment/cargo recovery. Unavailable storage is deferred.
- **Natural capitals.** About 10% of newly generated natural villages (via both `SiteHunter` in Millénaire 9.1.x and `VillageSpawnQueue`), at least 1,000 blocks from any living capital,
  start as NPC capitals with all generated buildings at their final native levels.
- **Kingdoms founded by development.** An independent NPC village that completes town hall level 3 through
  Millénaire's normal construction gets a chance to found its own kingdom every three in-game days. The chance starts
  at 5% and rises by 5 points per further completed level, up to 100%. The village itself is not changed in any way.
- **Royal commissions.** Pay real capital coins for one culturally eligible autonomous-village construction request. The original village chooses the site and its own builders use real materials; saved progress and explicit cancellation/recovery preserve natural projects.
- **Royal shipments & Royal Roads.** Link member villages into a kingdom highway network through **Rulership → Roads** (`/kingdom road build`). Chunk-free routing shapes smooth curved roads, graded causeways, milestones, and reusable single/multi-arch river bridges without forcing chunks or harming village structures/farms. Road-connected villages travel `38%` faster on Royal Shipments, earn `+2` deniers/day, and discount expedition fees by `25%`.
- **Physical caravans, marching armies & banditry.** Road-linked shipments appear near players as cultural merchants leading 1–3 chested pack mules; distant expeditions (`>= 128` blocks) march as bannered squads to the target perimeter before launching the native raid. Foreign and independent players can ambush non-allied caravans and armies for dropped cargo and denier fees (losing Millénaire reputation and recording Chronicles while own-kingdom parties, mobs, and environmental damage stay protected).
- **Virtual economy for unloaded villages.** Bounded background tracking mirrors daily building `abstracted_production` and resident `requiredGoods` into a virtual Town Hall buffer, accrues up to 75% build-time credit for unloaded projects with one-way hand-off on load, and supplies unloaded Royal Shipments without forcing chunks.
- **Capital ethos.** Exact native capital types give a Warlike, Peaceful, Traditionalist, Industrious or Pragmatic ethos. Bounded NPC decisions use the same paid services, actual needs/reserves and persistent cooldowns. War increases military preference for every type; player rule excludes new NPC economics.

Existing per-world configuration values remain in effect until edited. Configurable chances, distances and intervals can be adjusted per world. See the bilingual [player/operator guide](docs/1.2.0_USER_GUIDE.md) and [save/downgrade policy](docs/1.2.0_SAVE_COMPATIBILITY.md).

## Installation

Download **[millenairekingdoms-1.2.4.jar](https://github.com/Sarikli/millenairekingdoms/releases/download/1.2.4/millenairekingdoms-1.2.4.jar)** and install it alongside the original **Millénaire 9.1.1** with the versions above. Use the same addon/dependency versions on the server and all clients, removing older addon copies. See the [current player guide](docs/1.2.0_USER_GUIDE.md). Keep a whole-world pre-upgrade backup. Network protocol 12 requires matching 1.2.4 clients and servers. Existing native/political/financial formats remain supported; new road, march, and virtual economy stores use format 1 and are ignored/unreadable on older releases.

On a dedicated server, the same two JARs must be installed on the server and on every client, because the kingdom
screen is drawn on the client.

## Configuration

Settings are stored per world in `world/serverconfig/millenairekingdoms-server.toml`, which is created on first start.
The main sections are:

- `naturalCapitals`: whether natural capitals appear, their chance and the minimum distance between capitals.
- `npcAutonomy`: NPC decision making, war frequency (`RARE`, `BALANCED`, `FREQUENT`), the relation needed for war,
  raid cooldowns, truce length and automatic campaigns.
- `dynamicFounding`: the minimum town hall level, evaluation interval, base chance, chance per level and minimum
  distance for kingdoms founded by development.
- `royalEconomy`, `royalConstruction`, `royalShipments`, `royalNpcEconomy`, `treasuryMaintenance`, `strategicCombat`: fees, reserves, construction budgets, logistics durations, consolidation cadence, and dice settings.
- `royalRoads`: segment distance, deep ocean limit, daily material progress, frontier worker range, inactive completion duration, bridge width, and road bonuses.
- `royalMarches`: caravan/army spawning (`88`/`96` block hysteresis), army march speed, skirmish distance, and player banditry reputation penalties.
- `virtualEconomy`: unloaded village simulation cadence, catch-up cap, virtual Town Hall buffer cap, construction credit days/speed-up, and unloaded shipment support.

Base political keys and defaults are listed in the [user guide](docs/USER_GUIDE.md#7-settings-millenairekingdoms-servertoml).

## Commands

The kingdom screen covers everything a player needs. The commands below remain available as a fallback.

**Players**

```text
/kingdom                         open the kingdom screen (also /kingdom gui)
/kingdom info                    kingdom summary in chat
/kingdom claim | abdicate
/kingdom war declare <kingdom> | war request <kingdom> | war peace
/kingdom join <kingdom> | accept <village> | refuse <village> | found <name>
/kingdom raid <target> [source] | raid status | raid cancel
/kingdom road build <villageA> <villageB> | road status | road cancel
/kingdom treasury | treasury withdraw <deniers>
```

**Operators (permission level 2)**

```text
/kingdoms list | info | create | attach | active | village
/kingdoms ruler clear
/kingdoms war declare | peace | info | list
/kingdoms relation <kingdom> <kingdom> <value>
/kingdoms autonomy status | autonomy explain <kingdom>
/kingdoms founding status
/kingdoms economy explain <kingdom>
/kingdoms road status <kingdom>
/kingdoms recovery ...
/kingdoms test conquest | status | villages    (testing only, skips military readiness)
```

Village targets and operator kingdom targets accept exact names or UUIDs. Quote names containing spaces
(e.g. `/kingdom raid "Target village" "Source village"`); duplicate village names require a UUID.

## Save format

Kingdom data is stored per dimension in `data/millenairekingdoms_kingdoms.dat`, currently using save format 7. Road segments (`data/millenairekingdoms_roads.dat`), active marches (`data/millenairekingdoms_marches.dat`), and unloaded village buffers (`data/millenairekingdoms_virtual_economy.dat`) each use versioned format 1. Financial, construction, shipment and history stores are versioned separately; see the [current compatibility policy](docs/1.2.0_SAVE_COMPATIBILITY.md) and [architecture guide](docs/ARCHITECTURE.md).

- Political registry formats 1–6 remain readable and migrate lazily; format 7 retains causal publication identity (`autoResolve` defaults cleanly when absent).
- Downgrading requires a matching whole-world backup. Older addons do not understand all new records or pending paid work.
- A damaged file, or one written by a newer version, is never overwritten with empty data.

## Building from source

Millénaire is not included in this repository. Download the pinned original **Millénaire 9.1.1** JAR with:

```bash
bash scripts/setup-millenaire.sh
```

The script and build verify SHA-256 `f25350c1ba954f08258a6bd47373f37597a0c7b893e91fc8a85373ffa13df9c4`.
The dependency is stored locally as ignored `millenaire-9.1.1.jar`; never redistribute it with this addon.

Then build with the Gradle wrapper (Java 21 required):

```bash
./gradlew build
```

The mod JAR is written to `build/libs/`.

On Linux, NeoForge 1.21.1 cannot handle a project path that contains non-ASCII characters. `scripts/run-dev.sh` works
around this by running the task from an ASCII-only copy of the project:

```bash
./scripts/run-dev.sh runClient
```

## Tests

The mod is covered by NeoForge GameTests. Each verification suite runs a write pass and then a restart pass that reopens the same
world in a separate JVM (`scripts/verify-regression.py`) to verify persistence and runtime behavior.

| Task | Covers |
|---|---|
| `verifyWars` | War, peace, the 60% capital rule, ruler records |
| `verifyConquest` | Conquest raids, looting rules, cleanup, save migrations |
| `verifyStage5` | Rulership, income, diplomacy |
| `verifyCapitals` | Natural capitals |
| `verifyAutonomy` | NPC joining, war, campaigns, peace, truces, calendar simulation |
| `verifyGui`, `verifyRoyalGui`, `verifyRoyalShipmentGui` | Server GUI snapshots, actions, network wire codecs, localization |
| `verifyFounding` | Kingdoms founded by development |
| `verifyHistory` | Immutable Chronicle storage, recovery, and bilingual EN/TR narration |
| `verifyRoads` | Chunk-free curved road planning, reusable bridges, parapet/milestone guards, and road bonuses |
| `verifyMarches` | Hybrid caravans, player banditry, bannered marching armies, skirmishes, and restart safety |
| `verifyVirtualEconomy` | Unloaded production/upkeep virtual buffers, absent-chest handling, and build-time credit hand-off |

Run isolated verification suites or source/localization checks with:

```bash
python3 scripts/check-localization.py --jar build/libs/millenairekingdoms-1.2.4.jar
python3 scripts/check-project.py --jar build/libs/millenairekingdoms-1.2.4.jar
python3 scripts/verify-regression.py --suites verifyRoads,verifyMarches,verifyVirtualEconomy -- ./gradlew --no-daemon
```

Gradle can report `BUILD SUCCESSFUL` even when the test server crashes before loading the mod. `scripts/verify-regression.py` checks explicit pass counts across both JVMs.

## Documentation

- [Current architecture (English)](docs/ARCHITECTURE.md)
- [1.2.0 user guide (English / Türkçe)](docs/1.2.0_USER_GUIDE.md)
- [Save compatibility policy (English)](docs/1.2.0_SAVE_COMPATIBILITY.md)
- [1.1.0 user guide (English)](docs/USER_GUIDE.md)
- [Kullanım rehberi (Türkçe)](docs/KULLANIM_REHBERI.md)
- [Contributing guide](CONTRIBUTING.md)

## License

Released under the [MIT License](LICENSE). Millénaire is a separate mod by its own authors; it is not covered by this
license and is not distributed with this project.
