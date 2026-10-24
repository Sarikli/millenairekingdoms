# Royal coin planner checkpoint (Phase 2 in progress)

Millénaire 9.0.3 / NeoForge 21.1.259 / JDK 21.0.13+9. Full `verifyRoyalEconomy` package, isolated world
`run/regression/royal-coins-2/verifyRoyalEconomy`, write then separate-JVM reload.

- `All 8 required tests passed` in write.
- `All 8 required tests passed` in reload.
- `ROYAL_COINS_RESTART_WRITE_PASS` and `ROYAL_COINS_RESTART_READ_PASS` confirm real chest coin persistence.
- Raw uncompressed log SHA-256: `8b621d63300fca3ed8d3dc4cb501a39a9627255291adba6ec52b1899dfa3b8a8`.

Tests cover exact change from gold, mixed denominations/value, protected goods, invalid/insufficient amounts,
full change/refund storage, custom-component coin preservation, immutable plan snapshots and a real chest across restart.
An initial launch ran zero tests because the new class was not registered with the explicit test event; registration was
fixed before the final run. No zero-test launch is counted as passing.

This is a side-effect-free planner, NOT an authorized/durable treasury payment service. The chest mutation here is an
integration-test fixture. Duplicate payment/refund prevention, real request authorization, unloaded/multi-chest snapshots,
transaction journaling, interruption recovery, fees, orders and UI remain unimplemented. Do not label dev.2 complete.
