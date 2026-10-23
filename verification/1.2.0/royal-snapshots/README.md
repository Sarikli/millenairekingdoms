# Authorized treasury snapshot checkpoint — Phase 2 incomplete

Clean-world verifyRoyalEconomy run `royal-snapshots-1` with the pinned original Millénaire 9.0.3 and NeoForge 21.1.259.
Write and separate-JVM reload each reported **All 22 required tests passed**. This includes 8 coin tests, 7 journal tests
and 7 new snapshot tests. Uncompressed raw log SHA-256: `9aed25a92be8a22df890fecf7d5bd68b6c95dc5f044870a055c75be4a882732b`.

New tests use real registered money, ChestBlockEntity storage, Millénaire Village/BuildingInventory records and existing
kingdom/ruler rules. They check physical coins versus undelivered taxes; ruler/reputation changes and NPC exclusion;
unknown/inactive/fallen kingdoms; truly unloaded storage without loading/forcing chunks; both double-chest halves without
double counting; stale/replaced containers and defensive snapshots; off-server-thread refusal. Player identities use the
existing FakePlayer test helper. These fixtures are not proof of real villager construction or a live paid order.

RoyalTreasurySnapshot is read-only. No new physical debit/refund operation, container identity persisted across crashes,
or transaction-to-inventory coordinator exists yet. No world inventory is changed by the production snapshot/planner API.
The journal can persist its own lifecycle but does not establish cross-file atomicity. Dev.2 remains incomplete.
The prior 125+125 compatibility gate and these focused 22+22 tests were run separately; a full eight-suite rerun at this
latest commit is not claimed. Existing player-facing gameplay has not been connected to the new primitives.
