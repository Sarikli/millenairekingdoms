# 1.2.0-dev.1 / Millénaire 9.0.3 gate

Validated on the development branch, source checkpoint `829c498` (progress-only commits follow).
JDK 21.0.13+9, Gradle 8.14.3, NeoForge 21.1.259, Minecraft 1.21.1, pinned original Millénaire 9.0.3.

All seven suites passed in fresh isolated worlds, each shared across its write/separate-JVM reload pair:
**125 required tests + 125 required tests**. Counts below are the actual GameTest lines, not Gradle status.
Compressed raw text logs and their uncompressed SHA-256 values are retained alongside this report.
Original log timestamps are UTC; no credentials or third-party dependency JARs are included.

Normal build passed. Production-JAR dedicated server (no test mod) opened on loopback in an isolated flat test world,
executed `kingdoms list`, `kingdoms founding status`, `save-all flush`, and stopped cleanly. A separate JVM reopened
that world and repeated the checks. Both logs contain Done, Kingdoms 1.2.0-dev.1, Millénaire 9.0.3, command output,
Saved the game, and all dimensions saved. This empty-world smoke test is not a manual graphical/gameplay test.

The initial launch with stale [1.1.1] test metadata ran zero tests despite Gradle success. That metadata was corrected
before the final seven-suite run. Those initial startup failures are not counted as passes.
The inherited Yggdrasil direct-connection warning and Millénaire structure/path warnings remain visible in raw logs;
they did not prevent the required tests. Graphical client/player login is unverified.
Political save format remains 6; authentic existing migration/save fixtures ran unchanged. New 1.2 gameplay is not implemented yet.

### verifyWars
[16:43:20] [Server thread/INFO] [minecraft/GameTestServer]: All 30 required tests passed :)
[16:43:38] [Server thread/INFO] [minecraft/GameTestServer]: All 30 required tests passed :)

### verifyConquest
[16:44:04] [Server thread/INFO] [minecraft/GameTestServer]: All 25 required tests passed :)
[16:44:21] [Server thread/INFO] [minecraft/GameTestServer]: All 25 required tests passed :)

### verifyStage5
[16:44:45] [Server thread/INFO] [minecraft/GameTestServer]: All 24 required tests passed :)
[16:45:02] [Server thread/INFO] [minecraft/GameTestServer]: All 24 required tests passed :)

### verifyCapitals
[16:47:17] [Server thread/INFO] [minecraft/GameTestServer]: All 5 required tests passed :)
[16:48:00] [Server thread/INFO] [minecraft/GameTestServer]: All 5 required tests passed :)

### verifyAutonomy
[16:48:25] [Server thread/INFO] [minecraft/GameTestServer]: All 22 required tests passed :)
[16:48:41] [Server thread/INFO] [minecraft/GameTestServer]: All 22 required tests passed :)

### verifyGui
[16:49:03] [Server thread/INFO] [minecraft/GameTestServer]: All 7 required tests passed :)
[16:49:20] [Server thread/INFO] [minecraft/GameTestServer]: All 7 required tests passed :)

### verifyFounding
[16:49:50] [Server thread/INFO] [minecraft/GameTestServer]: All 12 required tests passed :)
[16:50:10] [Server thread/INFO] [minecraft/GameTestServer]: All 12 required tests passed :)
