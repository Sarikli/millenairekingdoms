# Contributing

Use Java 21 and the Gradle 8.14.3 wrapper. Minecraft 1.21.1, NeoForge 21.1.259 and the original unmodified Millénaire 9.0.3 dependency are pinned in gradle.properties. Never upload the original dependency, a world save or player information in a pull request.

## Build and focused validation

From a clean checkout, obtain the checksum-pinned original dependency and build:

```sh
bash scripts/setup-millenaire.sh
./gradlew --no-daemon build compileGametestJava
python3 scripts/check-localization.py --jar build/libs/millenairekingdoms-1.2.1.jar
python3 scripts/check-project.py --jar build/libs/millenairekingdoms-1.2.1.jar
```

The setup helper downloads only the official pinned JAR and verifies its SHA-256. It does not redistribute native assets or rewrite the dependency. Offline builds work after Gradle artifacts have been cached. The ordinary test task has no JUnit sources; build success alone is not a gameplay test result.

Run only affected suites, sequentially, with isolated fresh worlds and a separate-JVM reload:

```sh
python3 scripts/verify-regression.py --suites verifyStrategicMilitary,verifyStrategicRestart -- ./gradlew --no-daemon
```

Financial changes additionally require the relevant payment, conservation, cargo, refund and actual process-cut gates. Native construction/military changes need original-native runtime evidence. Suites/worlds/logs are mapped in [the engineering review](docs/1.2.1_ENGINEERING_REVIEW.md) and build.gradle. Results are in build/verification; test worlds are in run/regression. Reopen runs follow successful writes to the same world. Do not count an expected process halt, an unrun reload, a source assertion or a rendered model as a passing gameplay/graphical test.

## Where to work

Read [the current architecture](docs/ARCHITECTURE.md) before changing persistence or authority. KingdomSavedData owns political state; native VillageSavedData owns original villages. Treasury and shipment inventories are physical, and unloaded storage cannot be fabricated. Existing journals and per-container receipts intentionally span separate persistence boundaries. NPC policies propose work; existing authorized domain services execute it. Client screens never own prices, permissions or membership.

Keep patches focused. Preserve original APIs, existing format readers, immutable operation IDs, durability/readback barriers, native queue ownership and attribution. Describe a concrete trigger, before/after behavior, exact tests and save/network compatibility. Avoid wholesale formatting or new dependencies without a demonstrated need. New source uses UTF-8, four Java spaces and LF; existing dense code is not an invitation to rewrite unrelated files. git diff --check is the minimum whitespace gate, not a full Java formatter.

## Reporting problems and reviewing evidence

Include addon/native/loader/Java versions, reproduction steps, whether chunks were loaded, and the actual expected/observed result. Redact private player information, credentials and server addresses. Preserve original failed logs and operation files locally; provide a minimal redacted sample rather than deleting recovery evidence. Graphical UI, online authentication and native-language quality require separate checks.

Historical releases and verification directories are evidence, not build inputs except the explicitly retained localization/legacy fixtures. Do not delete or rewrite them to reduce repository size. See the [1.2.x/1.3 roadmap and deferred architectural work](docs/1.2.1_ENGINEERING_REVIEW.md).

## Process interruption and scale checks

```sh
python3 scripts/verify-process-cuts.py independent -- ./gradlew --no-daemon
python3 scripts/verify-process-cuts.py consolidation -- ./gradlew --no-daemon
python3 scripts/verify-regression.py --suites verifyMaintenance -- ./gradlew --no-daemon
```

Each process-cut case uses fresh prepare, deliberate halt (exit 70), and recovery JVMs. Only prepare/recovery require the exact `All 1 required tests passed` line. The maintenance persistence fixture defaults to 50 cold metadata records. An optional 500-record write benchmark exists, but its original-native world reopen failed with a 3 GiB heap in chest linking/chunk lighting; it is retained as a failure, not a supported large-world throughput claim.
