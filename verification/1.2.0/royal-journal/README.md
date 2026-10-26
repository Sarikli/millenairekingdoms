# Royal journal primitive checkpoint — Phase 2 incomplete

Final clean-world `verifyRoyalEconomy` run: `royal-journal-3`, Millénaire 9.0.3 / NeoForge 21.1.259 / JDK 21.

- Write: `All 15 required tests passed`.
- Separate-JVM reload of the same world: `All 15 required tests passed`.
- Both real coin chest and prepared/paid journal record restart markers were checked.
- Uncompressed log SHA-256: `b97c4d24a789b703907b058d2674c9669cbf5378383067205e3f9d5786ac4188`.

The suite includes the earlier 8 coin tests plus 7 real-filesystem journal tests: exact replay without state reset,
conflicting intent, illegal lifecycle transitions, defensive snapshots, preservation of future/corrupt records,
prepared/paid records across JVMs, and real file-lock contention. The first contention fixture omitted CREATE when
opening its own lock file; that fixture was fixed before this final run. Its failed run is not counted as passing.

The journal uses bounded, versioned NBT, file force, atomic rename, directory force (including created ancestors),
and an exclusive filesystem lock. Unsupported durability primitives fail closed; no non-atomic fallback is used.
The verified filesystem is Linux. Other filesystem/platform durability support is unverified.

This validates only the journal's OWN records and the coin planner. No payment coordinator or live paid action
uses it yet. It does not establish atomicity with Minecraft chunk inventories, exactly-once charging/refunds,
request authorization, container replacement recovery, or a full durable transaction protocol. Those remain required
before dev.2 can be complete. Future code must not mark PAID merely because this journal permits a state transition.
