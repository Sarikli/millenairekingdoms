# Millénaire Kingdoms 1.1.0: technical and test report

Developed independent NPC villages found their own kingdoms. Starting point: `v1.0.1` (c8565a9; the `v1.0.0` tag
already existed at 35a7f8f). Minecraft 1.21.1 · NeoForge 21.1.259 · Millénaire 9.0.2 (JAR unchanged) · Java 21.

Output: `millenairekingdoms-1.1.0.jar`, SHA-256
`046b3474bfce848f38ec23d7ba0337073ddd179d849293d7fcd935ed5df3eb9e`. Every automated test and the live server test
were run with this exact JAR.

Test logs, live server logs and screenshots referred to below under `verification/1.1.0/` are kept locally and are
not part of this repository.

## 1. Technical analysis

| Topic | Finding (Millénaire 9.0.2 / 1.0.1 code) |
|---|---|
| Building level | `BuildingInstance.getLevel()` is the 0-based plan level. When Millénaire starts an upgrade (`startUpgrade`) it **writes the new level immediately** and sets the status to `UPGRADING`; once the work is done the status becomes `COMPLETE`. Only the `COMPLETE` status therefore counts as a completed level. |
| Central building | `Village.getTownhall()` returns the first building when there is no town hall plan set, so the mod also checks that the plan set's `isTownHall()`, the variant and the stored plan are the real plan for that level. |
| Natural origin | Millénaire's village record does not store whether a village was generated naturally or created by command. The rule is applied through the village type: types with a natural generation weight (`weight > 0`) that are not player-controlled and not single buildings, and villages with no owner (`ownerUUID`) and no parent village (hamlets). |
| Unloaded villages | The town hall's plan set, variant, level and status live in Millénaire's persistent village record, so they can be read without the village being loaded. No chunks are loaded. A village with an inconsistent record (`INVALID_RECORD`) or an unfinished building (`NOT_COMPLETE`) is skipped for that period and evaluated again in the next one. |
| Calendar | `KingdomDay` checks every second and runs a new kingdom day once when it is booked in the ledger (`ledger.day`). If the day goes backwards nothing runs until the booked day is passed; a large jump counts as a single day. |
| Level caps | Vary widely by culture and village type (see section 7). |

## 2. Implementation (smallest safe change)

New files: `capital/DynamicFounding.java` (rules and evaluation) and `capital/FoundingConfig.java` (the
`dynamicFounding` settings section). Changed: `KingdomDay.run` (one call at the end), `CapitalConfig` (registers the
section), `KingdomsCommands` (read-only `/kingdoms founding status`), `NaturalCapitals.chain` (public for tests only).
No new mixin, economy, diplomacy or war service, and no new screen.

- **Chance:** `clamp(baseChancePercent + (level − minimumTownhallLevel) × chanceIncreasePerLevel, 0, 100)`, and 0%
  below the minimum level. With the defaults: level 3 → 5%, 4 → 10%, 5 → 15%, 7 → 25%, 10 → 40%, 17 → 75%, 22+ → 100%.
- **Period:** with the newly booked day `day` and the previously booked day `previous`, an evaluation runs when
  `floor(day / n) > floor(previous / n)` (n = `checkIntervalDays`, default 7). Days 7, 14, 21, … start a period. The
  booked day is written earlier in the same `KingdomDay` step, so a restart never repeats a period; if the clock is set
  back there are no rolls until the booked day is passed; skipping 970 days with `/time add` is one evaluation.
- **Persistent data:** none needed. The period is derived from the booked ledger day, and founding itself is a single
  `KingdomSavedData.create` snapshot. **The save format stays at 6** with no migration: 1.0.x worlds open as they are,
  and 1.0.1 can open a 1.1.0 save.
- **Evaluation:** villages are scanned in UUID order and every eligible village draws one sample. A kingdom founded
  during an evaluation already counts for the 1,500-block rule for the villages after it. A failed `create` (for
  example a name clash) leaves no record.
- **Rules (in order):** disabled → non-natural type → player village → hamlet → member or capital of a kingdom →
  former capital of any kingdom (fallen ones included) → busy (under a conquest raid, the target of a raid order, or
  has a pending join application) → invalid record → not `COMPLETE` → level too low → closer than 1,500 blocks → roll.
- **New kingdom:** "Kingdom of <village>" (NaturalCapitals naming, unique, at most 64 characters), active, no ruler,
  `npcGoverned` and `mayDecide(k, null)` true, with the founding village as its only member. Millénaire data is not
  touched and the village is not upgraded.

### Order of joining and founding

`KingdomDay.run` uses a fixed order: income → relation drift and border tension → NPC decisions (peace, war,
**joining**, campaigns; every 3 days) → **founding** (every 7 days). When both fall on the same day, joining runs
first. Both steps re-check the decision on the server at the moment of writing: a village that joined is now a member
and gets `IN_KINGDOM` under the founding rule, and a newly founded capital is never a joining candidate (`joinTarget`
skips villages that have an owner kingdom). One random source is used per day (the world's random in game, a fixed or
seeded one in tests).

## 3. verifyFounding (new suite, 12 tests)

`./scripts/run-dev.sh verifyFounding` (a write pass plus a restart pass in a separate JVM). Rule tests use village
records that carry Millénaire's real plan set, variant and the real plan at that level; the growth test uses a real
Millénaire village. Randomness is always checked with a fixed sample, 0% or 100%.

| Test | Items covered |
|---|---|
| chanceTableFollowsCompletedLevels | 1–8 (table for 0…24, 40, 1000), 16–17 (0%/100% settings), 30 (natural capital 2% unchanged), default settings |
| evaluationPeriodsFollowTheBookedDay | 19, 21 (period starts, repeated or earlier day, large jump) |
| realTownHallLevelsAndStatuses | 1–7 every level 0…17 with the real town hall plans of the Byzantine military village; 9 (UPGRADING via `startUpgrade`, PLANNED, UNDER_CONSTRUCTION); 11 (wrong plan, nonexistent level, village without buildings); culture level table |
| onlyIndependentNaturalVillagesFound | 10 (player village, player-controlled type), 11 (member), 12 (capital, conquered former capital that looks independent in the record), 13 (hamlet, single building, non-natural type), village with a join application, 18 |
| distanceBoundaryAndConfiguredChance | 14 (rejected at 1,499 and diagonal 1,499.4), 15 (accepted at 1,500), 16–17 with NeoForge config values, distance effect of the new capital |
| olderSavesAndKeyCommandUnchanged | 27 (the format-6 save of the 1.0.1 live test world opens and saves byte for byte; a 0.4.0 save still migrates), 29 (`/kingdom gui` and `founding` command nodes) |
| oneRollPerPeriodWhateverTheClock | 19, 21 through `KingdomDay`: over 30 days rolls happen only on days 7/14/21/28, no roll on a repeated or earlier day, a 970-day jump is one roll, no roll while disabled, at 100% a single founding at the next period start |
| foundingSurvivesRestart | 20: founding in the write pass; the kingdom, calendar day and village record survive into the restart pass; the same day does not run again |
| realVillageGrowsAndFoundsWithoutAnyChange | Real Millénaire village (Byzantine military, 29 buildings, 16 villagers, 26 chests): levels 0/1/2 TOO_LOW; NOT_COMPLETE while upgrading to 3; level 3 COMPLETE after Millénaire's rush → 5%; sample 0.05 does not found, 0.0499 does; 22–23: all Millénaire record data, blocks, chest contents, villager entities, owner, culture, name and level identical |
| newKingdomJoinsNpcPoliticsEconomyAndScreen | 24–26: villages joining, daily income, army and border, NPC war declaration, a real conquest order, peace and truce, kingdom screen data, rulership claimed through reputation; 28 (founder is not a joining candidate) |
| joiningAndFoundingNeverCollide | 28: decision day and founding day on the same day; the candidate village joins first and does not found; the other village founds and is not a joining candidate |
| longTermFoundingBalance | Calendar-skipping balance simulation (section 6) |

Result: **12/12 in the write pass and 12/12 in the restart pass**, confirmed by the server's own
`All 12 required tests passed` line.

## 4. Regression (with the 1.1.0 JAR)

Seven suites in sequence, each with a write pass and a restart pass in a separate JVM; every log was checked for
"Millénaire Kingdoms 1.1.0 initialized" and the server's result line.

| Suite | Write | Restart |
|---|---|---|
| verifyFounding | 12/12 | 12/12 |
| verifyGui | 6/6 | 6/6 |
| verifyWars | 26/26 | 26/26 |
| verifyConquest | 25/25 | 25/25 |
| verifyStage5 | 24/24 | 24/24 |
| verifyAutonomy | 15/15 | 15/15 |
| verifyCapitals (3 GB) | 5/5 | 5/5 |

Total **113/113 in both passes**. In the shared test world, scheduled founding is turned off just like scheduled NPC
decisions; the tests call the same code directly with the settings enabled.

## 5. Live dedicated server test

A copy of the 1.0.1 live test world, with only the released JAR in `mods`. Commands were run from the server console.

| Step | Result |
|---|---|
| Startup | The server started without errors; the `dynamicFounding` section was added to the config file with comments. `/kingdoms founding status`: 23 villages, all 5 independent villages at level 0. |
| 1. Choosing a village | In this world every independent village is closer than 1,500 blocks to a capital. **Campigny le-fort** (Norman military, 1,180 blocks from Arbis) was chosen. For the 100% founding step, `minCapitalDistance = 1000` and `baseChancePercent = 100` were used for this test only; NeoForge reloaded the file while running. |
| 2–3. Growth | First level 0 COMPLETE. Millénaire's own rush construction (`/millenaire dev grow 1`): level 1 at step 2, level 2 at step 5. |
| 4. Level 2 | Day 266 evaluation at 100%: "23 villages, 0 rolled, 0 founded … 4 too low"; Campigny TOO_LOW, no founding. |
| 5. During an upgrade | **Could not be observed live.** Millénaire's natural construction started no project in 10 minutes (forced growth steps included); the village stayed on an "armoury" project waiting for resources. This state was verified in GameTest on a real village through Millénaire's own `startUpgrade` transition (level 3, UPGRADING → postponed). |
| 6. Level 3 COMPLETE | The next rush raised the town hall to level 3: the status command showed "eligible, 100.0% per evaluation". (It would be 5% with the default settings; verified in GameTest on a real village.) |
| 7. Founding | Day 273: "Kingdom founded by development: 'Kingdom of Campigny le-fort' … at completed level 3"; "1 rolled, 1 founded … [L3 1/1/0]". |
| 8. Village unchanged | Millénaire status output right before and after founding: the full list of 93 buildings (type, variant, level, status; 1,851 characters) identical, 23 villagers, town hall `fort_a_3` at level 3. |
| 9. Kingdom screen | The graphical client was not run (the maintainer had said key tests were no longer needed, and the machine had frozen earlier with server and client running together). The server-side screen data was verified in GameTest with the new kingdom. |
| 10. NPC services | `autonomy explain`: npc=true. On later decision days Englesqueville les-pâtures joined the new kingdom on its own (relation 100); the kingdom earned 4 deniers each day and the coins went into the capital's storage; after the relation was set to −90, Arbis declared war on the new kingdom (day 300). |
| 11. Restart | Clean shutdown and startup: 5 kingdoms, the Campigny kingdom with 2 villages, an active war, calendar day 300 and the village record (capital, revision 1) all preserved. |

## 6. Long-term balance

### Calendar-skipping simulations (building levels fixed, growth not simulated)

**A. Isolated villages** (GameTest; 7 levels × 4 villages × 20 seeds, 3,000 blocks apart, no kingdoms, 728 days = 104
evaluations, default 5% + 5):

| Level | Chance | Founded within 728 days | Mean day | Expected (7 / p) | Median |
|---|---|---|---|---|---|
| 3 | 5% | 78/80 | 153.0 | 140.0 | 98 |
| 4 | 10% | 80/80 | 73.7 | 70.0 | 49 |
| 5 | 15% | 80/80 | 40.3 | 46.7 | 28 |
| 7 | 25% | 80/80 | 28.6 | 28.0 | 21 |
| 10 | 40% | 80/80 | 16.5 | 17.5 | 14 |
| 13 | 55% | 80/80 | 12.4 | 12.7 | 14 |
| 17 | 75% | 80/80 | 9.3 | 9.3 | 7 |

The measured times match the geometric expectation (the 2 villages at level 3 that did not found within 728 days
explain the small deviation of the mean). The cumulative effect is large: a village at level 3 founds a kingdom within
a year (52 evaluations) with 93% probability. What limits this is not the chance but the 1,500-block rule and villages
joining existing kingdoms (B).

**B. Dense region** (GameTest; 6 × 6 villages 700 blocks apart, levels cycling 0–15, 2 existing NPC kingdoms,
Millénaire's nightly diplomacy and NPC joining enabled, 364 days, 5 seeds):

| Seed | New kingdoms (by level) | Mean founding day | Distance rejections during evaluations | Joined another kingdom | Wars |
|---|---|---|---|---|---|
| 0 | 5 (7, 8, 8, 12, 15) | 12.6 | 352 | 29 | 0 |
| 1 | 5 (6, 8, 8, 10, 15) | 19.6 | 260 | 29 | 2 |
| 2 | 4 (6, 8, 10, 12) | 17.5 | 310 | 29 | 0 |
| 3 | 4 (6, 8, 10, 15) | 7.0 | 273 | 29 | 0 |
| 4 | 4 (3, 7, 10, 15) | 7.0 | 249 | 27 | 0 |

Only 4–5 of 36 villages founded a kingdom. High-level villages found in the first weeks, then the 1,500-block rule
closes the region and most villages join existing kingdoms. The number of kingdoms does not grow out of control, and a
distance of at least 1,500 blocks between all capitals was verified. Because a new kingdom starts with a single
village, its income is 0 (income comes only from member villages) and its army is only its own villagers, so the
economic and military balance does not shift suddenly.

**C. 52-week skip in the live world** (real server, default settings, day 300 → 664): 52 evaluations. The only eligible
village (level ≥ 3) was Fontenay villefranche (level 4 through rush); it was rejected for distance in 2 evaluations and
was then counted as busy because it was the target of a Strenos conquest order. Gortyn (level 1) joined Strenos; no new
kingdom was founded. During this time the newly founded Campigny kingdom fought 4 wars with Arbis and Strenos and made
peace 4 times.

### Real NPC growth

On the live server, town hall levels rose only through Millénaire's own rush construction (Millénaire's `grow`
command): Campigny 0 → 3 (12 rushes), Fontenay 0 → 4 (8 rushes), Gortyn 0 → 1 (only 1 level in 100 rushes; the rush
picked other buildings). Millénaire's natural construction over time did not progress during a 10-minute observation
because it was waiting for resources. The speed of natural growth depends on Millénaire (resources, villager work,
player proximity); the skipping simulations do not include it and measure development time only in terms of founding
chance.

### Performance

A founding evaluation scans every village in the records once (without loading chunks): in GameTest, a day with a
founding evaluation took 0.057 ms on average and a day without one 0.006 ms; in the dense region a whole day (politics
included) took 0.12–0.33 ms. On the live server, `/tick query` before and after the 52-week skip: 0.3 ms per tick on
average (P99 0.6 → 1.1 ms).

## 7. Town hall levels by culture

The rule is the same for every culture, but the highest reachable level depends on the village type (Millénaire 9.0.2
plan data, `FOUNDING_MATRIX` rows):

| Culture | Village type → highest level (chance at that level) |
|---|---|
| Byzantine | military 17 (75%), religious 13 (55%), trade 10 (40%) |
| Indian | fort 13 (55%), palace 12 (50%), farming 6 (20%) |
| Maya | religious 15 (65%), military 13 (55%), farming 11 (45%) |
| Norman | farming 10, religious 10 (40%), craft 9, military 9 (35%), bourg 7 (25%) |
| Japanese | military 7, political 7 (25%), farming 6 (20%), trade 4 (10%) |
| Inuit | community 6 (20%), craft/farming/religious 5 (15%), hunting 3 (5%) |
| Seljuk | military 5, religious 5 (15%), farming/craft/trade 4 (10%) |

Byzantine, Maya and Indian villages reach high levels and found quickly, while Seljuk and Inuit villages stay at low
chances; the Inuit hunting village only reaches 5%. This is the natural result of using the same rule for every
culture, as intended.

## 8. Known limitations (no open critical or high-severity issues)

- Millénaire does not record whether a village was generated naturally or by command, so a command-created village of
  a natural type can also become a candidate.
- The "during an upgrade" step and opening the kingdom screen on the client were not done in the live test (section
  5); both were verified in GameTest.
- No village in the live world was at a valid distance, so the 100% founding step used a test setting (1,000 blocks);
  the 1,500-block limit was verified in GameTest at 1,499 and 1,500 blocks.
- Cosmetic: the founding evaluation also writes a "0 villages" log line for dimensions without villages (Nether, End).
- The skipping simulations do not include Millénaire's building growth.
