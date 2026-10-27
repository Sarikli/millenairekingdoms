# Millénaire Kingdoms

> **1.2.1 — By Royal Decree.** [Release](https://github.com/Sarikli/millenairekingdoms/releases/tag/1.2.1) · [JAR, checksum and bilingual notes](releases/1.2.1/) · [engineering review](docs/1.2.1_ENGINEERING_REVIEW.md).
> Unattended strategic conflicts now include hostile independent villages. Focused maintenance retains real order blockers, quietly defers cold-capital treasury work and improves contributor checks. [Current architecture](docs/ARCHITECTURE.md) · [contributing](CONTRIBUTING.md) · [1.2.1 validation](docs/1.2.1_PROGRESS.md). The released 1.2.0 artifact, earlier verification and original PR #3 remain preserved. The maintenance work is reviewed in [Draft PR #4](https://github.com/Sarikli/millenairekingdoms/pull/4); this task does not merge `main`.

A medieval kingdom layer for [Millénaire](https://millenaire.org) villages: NPC kingdoms with their own capitals,
player rulers, diplomacy, war and peace, conquest through native raids and persistent unattended strategic engagements, daily taxes paid into a capital
treasury, and independent villages that grow into kingdoms of their own. Everything is managed from an in-game
kingdom screen, so no commands need to be memorised.

Original village ownership, inventories, builders and physical combat remain authoritative in Millénaire.
Addon generation hooks use original building plans to complete natural capitals; the original dependency JAR is never modified.

| | |
|---|---|
| Stable version | 1.2.1 — By Royal Decree |
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.259 |
| Required dependency | Millénaire 9.0.3 |
| Java | 21 |
| Mod ID | `millenairekingdoms` |
| Languages | All26 original Millénaire9.0.3 locales; new translations are AI/machine-assisted drafts pending native review |

See the [bilingual release notes](releases/1.2.1/RELEASE_NOTES.md), [changelog](CHANGELOG.md) and [audit findings and limits](docs/1.2.1_ENGINEERING_REVIEW.md). The [immutable 1.2.0 release](releases/1.2.0-final/) remains available.

## Features

- **Kingdom screen.** Press **K** (rebindable under Controls → Key Binds → Millénaire Kingdoms) or run `/kingdom`.
  Five main tabs: My Kingdom, Military, Diplomacy, Treasury and Rulership. Open Construction and Shipping using the two buttons inside Rulership; click Rulership or press Esc to return. Greyed-out buttons explain why they are disabled,
  and every action is checked again on the server.
- **Chronicles and political atlas.** Open History/Tarihçe at the kingdom screen's bottom-left. Read kingdom/world events, search fallen archives, filter dates/types and locate known historical places on a separate full-screen political atlas. All settlements of one kingdom share a persistent color/name; native village icons, capital crowns, zoom/pan and information connect to village/kingdom history. Ocean scanning and territorial polygon rendering are removed. Old founding dates stay unknown; original NPC work and real financial/cargo rules remain. See [usage, privacy and verified scope](docs/1.2.0_RC8_ALPHA_REPORT.md).
- **Player rulership.** Claim an unruled kingdom at its capital with a combined Millénaire reputation of 32768 or more.
  Political decisions need 8192. A kingdom has at most one ruler, and a player rules at most one kingdom.
- **Conquest.** Active regions use original native raids; genuinely unloaded NPC regions, including hostile independent targets, can resolve a persistent opposed-dice engagement using actual native military records. Existing political conquest requirements remain, without invented physical deaths or loot. An independent village falls
  after one victory and a rival kingdom's village after two victories during an active war. A capital can be attacked
  once the attacker holds 60% of the villages the defender had when the war began; two victories there end that kingdom.
- **Raid orders.** Rulers pick the target and the village that sends the soldiers. Military readiness, a one-day
  cooldown per target and one running raid at a time apply. NPC kingdoms use the same rules.
- **NPC kingdoms decide for themselves.** Every day a kingdom without a player ruler weighs accepting villages,
  declaring war on a hostile neighbour (relation −70 or worse), sending conquest raids and making peace (after day 6,
  followed by a five-day truce). A kingdom with a player ruler is never driven by this automation.
- **Diplomacy.** Kingdom relations follow Millénaire's relation between the two capitals. Border villages and raids
  create tension, peace treaties improve relations, and independent villages may join a kingdom on their own once their
  relation with its capital reaches +50.
- **Income and treasury.** Each member village produces its town hall level × 4 deniers per day. Real coins go into the
  capital town hall's storage, and the ruler can withdraw them from the kingdom screen while at the capital.
- **Conserving treasury maintenance.** Useful bounded maintenance converts64bronze→1silver→(64silver)1gold in one verified physical chest, conserving exact value and preserving payment/cargo recovery. Unavailable storage is deferred.
- **Natural capitals.** About 10% of newly generated natural villages, at least 1,000 blocks from any living capital,
  start as NPC capitals with all generated buildings at their final native levels.
- **Kingdoms founded by development.** An independent NPC village that completes town hall level 3 through
  Millénaire's normal construction gets a chance to found its own kingdom every three in-game days. The chance starts
  at 5% and rises by 5 points per further completed level, up to 100%. The village itself is not changed in any way.

- **Royal commissions.** Pay real capital coins for one culturally eligible autonomous-village construction request. The original village chooses the site and its own builders use real materials; saved progress and explicit cancellation/recovery preserve natural projects.
- **Royal shipments.** Pay normal logistics costs to transfer actual surplus with at most50 protected units per product between member villages over server-tick travel time. Full/unloaded destinations retain held cargo; normal Millénaire trade stays unrestricted.
- **Capital ethos.** Exact native capital types give a Warlike, Peaceful, Traditionalist, Industrious or Pragmatic ethos (Karakter in Turkish). Bounded NPC decisions use the same paid services, actual needs/reserves and persistent cooldowns. War increases military preference for every type; player rule excludes new NPC economics.

[RC.5 balance changes and existing-world settings](docs/1.2.0_RC5_TIMINGS.md) describe the new defaults. Existing per-world configuration values remain in effect until edited. Configurable chances, distances and intervals can be adjusted per world. See the bilingual [1.2.0 player/operator guide](docs/1.2.0_USER_GUIDE.md), [save/downgrade policy](docs/1.2.0_SAVE_COMPATIBILITY.md), and [separate unverified graphical/online checks](docs/1.2.0_MANUAL_TESTS.md).

## Installation

Download **[millenairekingdoms-1.2.1.jar](https://github.com/Sarikli/millenairekingdoms/releases/download/1.2.1/millenairekingdoms-1.2.1.jar)** and install it alongside the original **Millénaire 9.0.3** with the versions above. Use the same addon/dependency versions on the server and all clients, removing older addon copies. See the [current player guide](docs/1.2.0_USER_GUIDE.md). Keep a whole-world pre-upgrade backup. Network protocol 11 requires matching 1.2.1 clients and servers. Existing native/political/financial formats remain supported; new independent strategic records use format 2 and cannot be downgraded to 1.2.0.

### Historical 1.1.1 installation


1. Install NeoForge 21.1.259 for Minecraft 1.21.1.
2. Put the original **Millénaire 9.0.2** JAR and **[millenairekingdoms-1.1.1.jar](releases/1.1.1/millenairekingdoms-1.1.1.jar)** (local maintenance build) into your `mods` folder.
3. Remove any older Millénaire Kingdoms JAR. Two versions cannot be loaded together.
4. **Back up your world before opening an existing save.** See [Save format](#save-format) below.

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

Every key and its default value is listed in the [user guide](docs/USER_GUIDE.md#7-settings-millenairekingdoms-servertoml).

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
/kingdoms test conquest | status | villages    (testing only, skips military readiness)
```

Village targets and operator kingdom targets accept exact names or UUIDs. Quote names containing spaces
(e.g. `/kingdom raid "Target village" "Source village"`); duplicate village names require a UUID.

## Save format

Kingdom data is stored per dimension in `data/millenairekingdoms_kingdoms.dat`, currently using save format 7. Financial, construction, shipment and history stores are versioned separately; see the [current compatibility policy](docs/1.2.0_SAVE_COMPATIBILITY.md).

- Political registry formats 1–6 remain readable and migrate lazily; format 7 retains causal publication identity.
- Downgrading requires a matching whole-world backup. Older addons do not understand all new records or pending paid work.
- A damaged file, or one written by a newer version, is never overwritten with empty data.
- Historical 1.1.1 used format 6. Changing a ruler cancels waiting orders
  from the old tenure; active raids finish normally. Unresolved applications to kingdoms returning to NPC rule
  are withdrawn so villages can apply again. Legacy orphan applications are released on the next kingdom day.

## Building from source

Millénaire is not included in this repository. Download the pinned original **Millénaire 9.0.3** JAR from the official site with:

```bash
bash scripts/setup-millenaire.sh
```

The script and build verify SHA-256 `5882528027ac67e8759db3926ebef36abe2300029526582f80c7a988b583ec62`.
The dependency is stored locally as ignored `millenaire-9.0.3.jar`; never redistribute it with this addon.

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

The mod is covered by NeoForge GameTests. Each task runs a write pass and then a restart pass that reopens the same
world in a separate JVM to check that the saved data survives.

| Task | Covers |
|---|---|
| `verifyWars` | War, peace, the 60% capital rule, ruler records |
| `verifyConquest` | Conquest raids, looting rules, cleanup, save migrations |
| `verifyStage5` | Rulership, income, diplomacy |
| `verifyCapitals` | Natural capitals (3 GB of heap recommended) |
| `verifyAutonomy` | NPC joining, war, campaigns, peace, truces, a 300-day calendar simulation |
| `verifyGui` | Server side of the kingdom screen and the treasury |
| `verifyFounding` | Kingdoms founded by development: chances, eligibility, distance, calendar, balance |

```bash
./scripts/run-dev.sh verifyFounding
```

Gradle can report `BUILD SUCCESSFUL` even when the test server crashes before loading the mod. Treat a run as passed
only when the log contains `All N required tests passed`.

1.1.1 verification: **125/125 tests in the write pass and 125/125 in separate-JVM restart passes**.
Each suite uses its own temporary world; its write/restart pair shares that world. This includes the existing
113 tests and 12 additional maintenance tests. Logs are in `verification/1.1.1/final/`.

## Documentation

- [User guide (English)](docs/USER_GUIDE.md)
- [Kullanım rehberi (Türkçe)](docs/KULLANIM_REHBERI.md)
- [1.1.1 bakım ve test raporu (Türkçe)](TEST_RAPORU_1_1_1.md)
- [Test report for 1.1.0](TEST_REPORT_1_1_0.md)

## License

Released under the [MIT License](LICENSE). Millénaire is a separate mod by its own authors; it is not covered by this
license and is not distributed with this project.
