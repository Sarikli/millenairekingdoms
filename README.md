# Millénaire Kingdoms

A medieval kingdom layer for [Millénaire](https://millenaire.org) villages: NPC kingdoms with their own capitals,
player rulers, diplomacy, war and peace, conquest through **real Millénaire raids**, daily taxes paid into a capital
treasury, and independent villages that grow into kingdoms of their own. Everything is managed from an in-game
kingdom screen, so no commands need to be memorised.

Millénaire's own village simulation (villagers, buildings, cultures, village ownership, trade and its own raids) is
left untouched, and the original Millénaire JAR is never modified.

| | |
|---|---|
| Mod version | 1.1.0 |
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.259 |
| Required dependency | Millénaire 9.0.2 |
| Java | 21 |
| Mod ID | `millenairekingdoms` |
| Languages | English, Turkish |

## Features

- **Kingdom screen.** Press **K** (rebindable under Controls → Key Binds → Millénaire Kingdoms) or run `/kingdom`.
  Five tabs: My Kingdom, Military, Diplomacy, Treasury and Rulership. Greyed-out buttons explain why they are disabled,
  and every action is checked again on the server.
- **Player rulership.** Claim an unruled kingdom at its capital with a combined Millénaire reputation of 32768 or more.
  Political decisions need 8192. A kingdom has at most one ruler, and a player rules at most one kingdom.
- **Conquest.** Villages change hands only through real Millénaire raids, without looting. An independent village falls
  after one victory and a rival kingdom's village after two victories during an active war. A capital can be attacked
  once the attacker holds 60% of the villages the defender had when the war began; two victories there end that kingdom.
- **Raid orders.** Rulers pick the target and the village that sends the soldiers. Military readiness, a three-day
  cooldown per target and one running raid at a time apply. NPC kingdoms use the same rules.
- **NPC kingdoms decide for themselves.** Every three days a kingdom without a player ruler weighs accepting villages,
  declaring war on a hostile neighbour (relation −70 or worse), sending conquest raids and making peace (after day 12,
  followed by a ten-day truce). A kingdom with a player ruler is never driven by this automation.
- **Diplomacy.** Kingdom relations follow Millénaire's relation between the two capitals. Border villages and raids
  create tension, peace treaties improve relations, and independent villages may join a kingdom on their own once their
  relation with its capital reaches +50.
- **Income and treasury.** Each member village produces its town hall level × 4 deniers per day. Real coins go into the
  capital town hall's storage, and the ruler can withdraw them from the kingdom screen while at the capital.
- **Natural capitals.** About 2% of newly generated natural villages, at least 1,500 blocks from any living capital,
  start as NPC capitals with a fully upgraded town hall.
- **Kingdoms founded by development.** An independent NPC village that completes town hall level 3 through
  Millénaire's normal construction gets a chance to found its own kingdom every seven in-game days. The chance starts
  at 5% and rises by 5 points per further completed level, up to 100%. The village itself is not changed in any way.

All chances, distances and intervals can be adjusted per world.

## Installation

1. Install NeoForge 21.1.259 for Minecraft 1.21.1.
2. Put the original **Millénaire 9.0.2** JAR and **`millenairekingdoms-1.1.0.jar`** (from the
   [Releases](../../releases) page) into your `mods` folder.
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

## Save format

Kingdom data is stored per dimension in `data/millenairekingdoms_kingdoms.dat`, currently using save format 6.

- Worlds from older versions (formats 1 to 5) load without losing data and are upgraded to format 6 on the next save.
- The upgrade cannot be undone: versions before 1.0.0 cannot open a format 6 file.
- A damaged file, or one written by a newer version, is never overwritten with empty data.

## Building from source

Millénaire is not included in this repository. Download **Millénaire 9.0.2** yourself and place it in the project root
as `millenaire-9.0.2.jar`. The build verifies its SHA-256 checksum:

```text
8665fcb513dc17eed08fee4b30dae988f45157f72d332ab795611b3761cbc92f
```

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

## Documentation

- [User guide (English)](docs/USER_GUIDE.md)
- [Kullanım rehberi (Türkçe)](docs/KULLANIM_REHBERI.md)
- [Test report for 1.1.0](TEST_REPORT_1_1_0.md)

## License

Released under the [MIT License](LICENSE). Millénaire is a separate mod by its own authors; it is not covered by this
license and is not distributed with this project.
