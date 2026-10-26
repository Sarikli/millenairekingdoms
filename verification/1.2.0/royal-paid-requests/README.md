# Paid original pending requests

Fresh world; write/reload JVMs both logged **All 19 required tests passed**, plus normal build. Counts: prior 15 catalog/
index/SavedData tests and 4 real request integration tests. Actual chest coins pay patronage; original pending metadata
is acknowledged before the receipt commits. Retries preserve the variant/price/reference, including config changes and
separate-JVM reopening. Natural pending work, inactive villages and conflicting/new active requests do not take more coins.
A stale domain save leaves QUEUING+PAID until real read-back succeeds; it cannot become COMMITTED merely from live state.
Receipt inspection rejects changed agency and never rebinds an NPC payment to a new player ruler.

Reload fixtures use a different physical area from the previous JVM's retained fixture; no saved container is overwritten
as test setup. The first trial also passed 19+19; this final pair includes the explicit isolation/receipt guards.

This establishes internal paid queue acceptance, not completed NPC construction. No fake builder, instant placement or
finished-world building is created. Cancellation, governance cleanup, monitoring, actual builder/resource/placement work,
domain abrupt-crash probes and EN/TR GUI/scheduler integration remain required before exposing gameplay.
