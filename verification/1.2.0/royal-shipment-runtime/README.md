# Bounded runtime and actual automatic paid shipping

Fresh verifyRoyalShipmentRuntime / royal-shipment-runtime-01 logged **All 1 required tests passed** in both JVMs,
normal build passed. Three genuinely paid member shipments prove one/two-job budget, UUID round-robin fairness,
read-only startup, known-index restore after deliberately unsupported data (no inventory/file mutation), and no
terminal-history replay as new work.

Fresh isolated verifyRoyalShipmentLive / royal-shipment-live-01 also logged **All 1 required tests passed** in both
JVMs and normal build passed. Actual registered LevelTick.Post LOWEST tracker uses production defaults100 ticks/2 jobs,
actual default3600-tick route and one9 fee. After initial request the probe ONLY reads the index, never calls
observe/resume/runtime.step. Test LOW-priority callback explicitly keeps original fixture villages loaded/active;
production never forces chunks. No NPC/caravan or commissioned building is fabricated or claimed.

Write: actual event source96->56, target4, held40, due3800, save/reopen original native metadata without repair.
Read: automatic event alone completes after due (now4000), source56/target44, escrow0, capital119, original fee
COMMITTED. No clock/config-duration override, new fee, hidden direct step or private stage mutation.

All other suites still disable scheduled shipping to isolate their fixtures. Unknown startup index pauses tracking
with bytes preserved; runtime failures log/bound retry. These are dedicated backend/event checks, not graphical
screen rendering, authenticated login, NPC policy or full1.2 completion. Player UI is next.
