# Millénaire Kingdoms 1.2.4 — By Royal Decree

Minecraft 1.21.1 · NeoForge 21.1.259 · Java 21 · Millénaire 9.1.1

- Updated dependency target to unmodified **Millénaire 9.1.1** and added `SiteHunterMixin` so natural NPC capitals generate reliably alongside `VillageSpawnQueueMixin`.
- Added **Royal Roads** (Rulership → Roads and `/kingdom road build|cancel|status`, OP `/kingdoms road status`): chunk-free A* + smooth Catmull-Rom routing, existing-road/bridge reuse, cultural styles, graded slabs, cut-and-fill causeways, non-overlapping milestones, reusable single/multi-arch river bridges, proximity frontier builder NPC, `+2` deniers/day bonus, `-38%` shipment travel duration, and `-25%` expedition fee discount.
- Added **Hybrid Physical Marches & Caravans**: road-linked Royal Shipments materialize near players as cultural merchants leading chested pack mules; distant conquest expeditions (`>= 128` blocks) march as bannered squads to the target perimeter before launching native raids.
- Enabled wartime ambushes and individual player **banditry** against foreign/NPC caravans and armies (dropping seized cargo and fee coins while penalizing Millénaire reputation, triggering retaliation, and recording attacker-named chronicles; own-kingdom parties, mobs, and environmental damage stay protected).
- Added player-selectable conquest raid mode (**By Player** default / **Automatic** fallback on the Military tab).
- Added chunk-free **Virtual Economy** simulation for unloaded villages (`abstracted_production` / `requiredGoods` virtual Town Hall buffer, background construction credit via `BuildTaskCreditMixin`, and unloaded shipment collection/delivery).

Use matching 1.2.4 clients and servers (network protocol 12). Back up your world before upgrading.
