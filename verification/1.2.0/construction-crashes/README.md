# Actual abrupt construction domain boundaries

Eight isolated worlds, three JVMs each (24 total). Every preparation and recovery JVM logged **All 1 required tests
passed** (16 normal runs). Every interruption JVM actually called Runtime.halt(70), exited 70 without Minecraft shutdown
or success lines, and made Gradle fail as expected. Halted JVMs are not passing GameTests. Normal offline build passed.
The real RoyalConstructionService, actual real-coin coordinator, physical read-back and original village serializer run;
package-private injected persistence and actual OS file locks stop the selected boundary. No production crash switch.

| Boundary | Disk coins before recovery | Result | Final coins |
|---|---:|---|---:|
| Intent persisted, before debit intent | 128 | Original request safely queued/committed | 64 |
| Debit persisted, before payment/order acknowledgement | 64 | Same original expense reconciled/queued | 64 |
| Before original pending save | 64 | Uncertain publication quarantined, no reissue/refund | 64 |
| After original pending save, before order acceptance | 64 | Original pending preserved, publication quarantined | 64 |
| QUEUED persisted, before receipt commit | 64 | Existing accepted receipt committed, no charge | 64 |
| Cancellation persisted, before refund | 64 | Cancelled/refunded once | 128 |
| Physical refund saved, before REFUNDED | 128 | Same refund acknowledged, no second credit | 128 |
| REFUNDED saved, before terminal order | 128 | Tracking closes, no second refund | 128 |

Uncertain publication recovery is preservation, not a claim of successful fulfillment. The original pending record is
absent before its save and present after it; startup keeps those exact independent states while retaining PAID/reservation.
Recovery never repairs original plan/inventory metadata, creates NPCs, places buildings or manually fabricates domain stages.
The cancellation probes use an actual request paused at PAID by changing availability after physical persistence; actual
service cancellation then persists its origin before financial work. Duplicate recovery cannot charge/refund again.

Command: python scripts/verify-construction-crashes.py -- python /workspace/cloud-setup/millenaire-gradle.py --offline
The earlier four payment-only crash probes were not rerun. The focused construction suite remains separately 53+53.
These domain probes use inventory/pending fixtures, not actual building-block interruption. Genuine NPC task/material/
structure persistence previously passed graceful write/reload; physical building-block abrupt-save ordering remains a
separate next investigation. Power loss/other filesystems and audited third-state resolution are not claimed.
