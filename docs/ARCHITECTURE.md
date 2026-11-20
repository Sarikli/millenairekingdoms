# Current architecture and safe modification guide

Applies to 1.2.4, based on original Millénaire 9.1.1 / Minecraft 1.21.1 / NeoForge 21.1.259 / Java 21.

## Authority and boundaries

| State | Owner | Readers / execution boundary |
| --- | --- | --- |
| Original villages, residents, buildings, native projects and inventories | Millénaire VillageSavedData/native objects and chunks | Adapters read the original 9.1.1 API (`SiteHunterMixin`, `VillageSpawnQueueMixin`, `BuildTaskCreditMixin`); native builders/combat retain physical work. |
| Kingdom identity, membership/revisions, rulers, wars, campaign wins, income day ledger | KingdomSavedData / Codec | Publishes validated immutable state replacements (`autoResolve` per `RaidOrder`). Retirement and causal history hooks belong to this boundary. |
| Political decisions | KingdomDay / AutonomyService / DiplomacyService / Military | Registered native metadata supports unloaded settlements; probabilities never bypass permissions/readiness. |
| Physical money | RoyalTreasurySnapshot/Image/Persistence plus RoyalPaymentService/Journal | Quotes require loaded actual storage/current authority; durable operation/container receipts govern recovery. Owed income is not spendable coins. |
| Construction mandate | RoyalConstructionService/Store/Monitor/Runtime | Authorization/payment/queue identity precede native work; physical completion is acknowledged separately. |
| Cargo and delivery | RoyalShipmentService/Store/Runtime/Persistence plus VirtualShipmentCargo | Fee, goods escrow, per-container movement, virtual unloaded buffers and cancellation proofs retain original identities. |
| NPC economic intention | RoyalStrategyService/Store and RoyalNpcRuntime | Conditional metadata plans, not promised resources or completed construction. Loaded current physical validation precedes paid execution. |
| Military operation | RaidOrderService, ConquestService, StrategicBattleService/Store | One original order owns either native dispatch (with optional By Player / Automatic fallback) or a committed strategic roll. Political receipts alone grant campaign credit. |
| Royal Roads | RoadSavedData / RoadService / RoadPlanner / RoadPlacer / RoadRuntime | Chunk-free A* + Catmull-Rom planning and reusable river bridges; physical realization runs incrementally on naturally loaded chunks with a proximity-only builder NPC (`no chunk forcing`). |
| Hybrid marches | MarchSavedData / MarchService / MarchRuntime | Caravans and bannered armies advance in the background and materialize only near players (`88`/`96` block hysteresis); banditry and wartime ambushes settle through `RoyalShipmentService.ambush` and `MarchService.defeat`. |
| Unloaded virtual economy | VirtualEconomySavedData / VirtualEconomyService / VirtualEconomyRuntime / VirtualConstructionBoosts | Bounded round-robin simulation of `abstracted_production` and `requiredGoods` into a virtual Town Hall buffer, plus unloaded construction credit handed off one-way on chunk load. |
| Historical facts | HistoryHooks/Recovery/Store | Causal UUIDs and immutable names/facts; no retroactive invented founding dates. Presentation (`ChronicleNarrator`) never changes stored facts. |
| Discovery and atlas presentation | HistoryVisibility, AtlasViews/Colors | Authorized native settlements and durable colors; no ocean scanner/geometry worker. Old cache bytes are not reclaimed. |
| UI/network | KingdomActions/Screens, payload codecs, client package (`KingdomRoyalTabs`) | Bound requests and main-thread server revalidation (Protocol `12`). Client snapshots/prices are not authority. |

```mermaid
flowchart LR
  UI[Client requests] --> Actions[Server action validation]
  Tick[NeoForge server events] --> Politics[Daily political decisions]
  Tick --> NPC[Conditional NPC planning]
  Tick --> Virtual[Unloaded virtual economy]
  Actions --> Domain[Construction / shipment / road / treasury services]
  NPC --> Domain
  Politics --> Registry[Political registry]
  Politics --> Orders[Military orders]
  Domain --> Roads[Royal Roads realization]
  Domain --> Marches[Hybrid caravans and armies]
  Orders --> Marches
  Marches --> Native[Loaded native combat]
  Orders --> Strategic[Unloaded strategic roll journal]
  Virtual --> Native
  Native --> Registry
  Strategic --> Registry
  Domain --> Receipts[Physical operation receipts and disk acknowledgment]
  Registry --> History[Immutable causal history]
  History --> Views[Authorized history / atlas views]
```

These are responsibility boundaries, not a claim of an acyclic implementation. The baseline has a broad explicit package-reference cycle. In particular, political publication knows retirement/history hooks, financial protocols share exclusion guards, and server GUI adapters call domain services. Extract narrow ports/read models later without introducing another authoritative database or changing publication order.

## Event ordering and work budgets

MillenaireKingdoms is the composition root. KingdomDay checks every 20 ticks and books a new day once, then income/relations/NPC politics/founding/road daily progress run in their documented order. Raid orders and strategic recovery check every 50 ticks. Construction uses HIGH before native slot selection; shipment tracking uses LOWEST. NPC economic scheduling uses HIGH and configured round robin. Treasury maintenance uses LOWEST, configured new-work cadence and separate 200 tick recovery. VirtualEconomyRuntime, RoadRuntime, and MarchRuntime tick on `LevelTickEvent.Post` at `LOWEST` priority with bounded per-pass work and never force chunks in production.

Budgets limit inspected jobs; they do not make an individual saved-chunk acknowledgment cheap. Sorting/index scans precede some budgets. All world/entity/inventory access remains on the legitimate game thread. Do not move these objects into an asynchronous task. Any future async durability design needs immutable snapshots, explicit acknowledgments and stale-state revalidation.

## Physical and strategic military lifecycle

1. Daily policy or a player ruler authorizes one conquest order, retaining source/target/current campaign, `autoResolve` mode, and due/expiry ticks.
2. For targets at `128` blocks or farther, `MarchService` plans a continuous route along friendly roads and terrain and marches a physical/background bannered army to the target perimeter before launching the native raid.
3. Unloaded NPC raids and player orders marked **Automatic** (when the target is unloaded on arrival) can resolve through committed `OpposedDice` using current native military records; **By Player** orders wait for live loaded combat. Partially loaded participants do not trigger an artificial strategic substitute.
4. Exact opposed dice and native snapshots persist before political effects. The existing political campaign grants one receipt, with 1 victory for an independent village or 2 within an active kingdom war. Capital majority/collapse rules remain unchanged.
5. SavedData write/readback precedes a truthful Chronicle and terminal acknowledgment. Recovery reuses the exact roll/ID, including an already committed operation past its original dispatch window. New expired orders are rejected before scheduling; their last real blocker is retained in diagnostics.

Independent defenders have a village identity and no invented kingdom UUID. Single-realm player banditry records retain only the victim kingdom and player actor name (`actorId`/`actorName`). Their Chronicle contains the real realms/attacker and native source/target places; localization names the winning side correctly. Strategic operations never claim physical deaths, loot, equipment loss or newly spawned soldiers.

## Finance and cross-file durability

Payment intent, actual chunk inventory, native village metadata and operation journals do not share a filesystem transaction. PREPARED/PAID/COMMITTED and refund/recovery states are necessary evidence, not redundant status flags. RoyalTreasuryImage binds before/after slot images, container identity and operation receipts; readback verifies actual saved chunks. Later legitimate native inventory changes must survive recovery. Unknown/unreceipted images are quarantined rather than guessed.

RoyalEconomicReservations and the capital lock prevent overlapping new money/cargo/consolidation images. These checks are deliberately shared across protocols. Consolidation conserves value in one chest per operation, with its own immutable intent/archive. New maintenance skips a cold capital quietly; existing recovery continues separately, even with new maintenance disabled. Loaded partial/missing storage and real integrity failures retain diagnostics. Synchronous commit latency remains a measured limitation; no safety barrier was removed for a benchmark.

## Persistence and compatibility

| Format | 1.2.4 policy |
| --- | --- |
| Native 9.1.1 data | Original dependency and serializers unchanged |
| Political registry 7 / HistoryEvent 1 | Existing supported readers/identities retained (`autoResolve` optional with safe `false` default) |
| Road / March / VirtualEconomy SavedData 1 | Dedicated format-1 per-dimension stores (`millenairekingdoms_roads.dat`, `_marches.dat`, `_virtual_economy.dat`) |
| Strategic war operation 1 / independent operation 2 | Persisted records; strict fields and frozen dice retained |
| Payment 1 / construction order 5 / recovery audit 2 / shipment 1 | Unchanged financial/native protocols |
| AtlasColors 1 and existing discovery | Retained bytes/visibility rules |
| Network 12 | Matching 1.2.4 clients/servers required for raid mode toggle, Royal Roads actions, and synced road state |

Whole-world backups precede upgrades. Reading old supported data is tested; downgrading a world containing new independent military records to 1.2.0 is not supported. Future/corrupt files must remain byte-preserved and block unsafe replay. Do not delete old journals, pending evidence or incompatible fields to obtain a passing test.

## Tests and contributor workflow

[CONTRIBUTING](../CONTRIBUTING.md) gives actual build and selected isolated-pair commands. Domain tests deliberately control fixtures/rolls to test both outcomes and durable cuts. The original-native independent fixture separately spawns original villages/NPCs, removes its setup tickets and observes ordinary scheduler/default RNG decisions with zero players and real ticks. These are distinct from the preserved natural 300-day 1.2.0 observation and from graphical/online checks.

Keep benchmark scope explicit: cold 5/50/500-capital metadata scheduling is not 500 populated villages or live inventory TPS. Useful treasury conversion measures the actual synchronous native-chunk durability path. Exact source/image hashes, failed attempts, pass lines and reopen receipts belong in verification, with the final engineering review recording limitations and deferred 1.3 work.
