# Physical construction durability: reproduced failure and fix

Before-fix real-resident probe: preparation logged **All 1 required tests passed**. An actual resident then completed
2447/2447 steps and 100% live structure; JVM halted(70) immediately after original COMPLETE task metadata save, before
royal COMPLETED. Recovery **failed**: only **2/233** template blocks persisted and **14 consumed spruce logs reappeared**.
No pass is claimed for that recovery. Raw before-fix logs/result are retained.

The fix acknowledges already-loaded construction/task extents and original townhall/double-chest contents through normal
chunk serialization/write/flush and exact section/block-entity disk read-back. It also saves fully loaded resident root
entity sections through the pinned original 1.21.1 entity-storage path, flushes/reads exact entity NBT, and synchronizes
actual villager records before original completion metadata save. It never forces chunks, creates NPCs, places a block
or modifies a live task cursor/status. Detached cursor-zero NBT is only a read-only extent of the already-original task.
A structural guard uses the existing 90% natural-capital calibration; gross pre-existing damage becomes explicit recovery.
It is not a claim of every cell's semantic correctness across every cultural plan. Unloaded sections defer rather than
replacing partially loaded entity data. Native attempted terrain cells outside world height are not persistable world data.

Final fresh after-fix three-JVM probe: preparation/recovery each logged **All 1 required tests passed**. The actual halt70
JVM has no pass/shutdown lines and intentionally failed Gradle. Recovery read **233/233 blocks, 0 carried spruce**, then
closed royal tracking without repairing the save. Actual physical source/block/entity files now precede completion ack.
An authentic archived copy of the failed pre-fix world separately logged **All 1 required tests passed**: still 2/233,
still 14 logs, but order RECOVERY_REQUIRED/BUILDING_MISSING. No blocks/inventories/task statuses are silently repaired.
Fixture bytes are original generated-world files, not fabricated NBT or dependency JARs; its checksum/provenance manifest
is under src/gametest/resources/fixtures. Only the test JAR includes the fixture, never the production JAR.

Final relevant package: fresh royal-physical-final-01 write/reload each logged **All 53 required tests passed**, normal
build passed. Other successful packages and the prior eight domain/payment cuts were not rerun; these are separate
recorded gates. Power loss, other filesystems, all cultural templates and an atomic whole-world snapshot are not claimed.

Diagnostic trial 01 deferred completion on non-world terrain cells and was deliberately stopped (not a passing halt70).
Trial 02 already read 233/233 and 0 logs, but its test cleaned up village activation before its final tracking assertion.
That fixture ordering was corrected. The final passing evidence above is trial 03, not either diagnostic trial.

Reproduce:
python scripts/verify-building-crash.py -- python /workspace/cloud-setup/millenaire-gradle.py --offline
python scripts/verify-building-crash.py --guard-fixture -- python /workspace/cloud-setup/millenaire-gradle.py --offline
The guard command is one ordinary passing JVM; it does not perform a new halt.
