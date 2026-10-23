# Abrupt JVM payment recovery

Four fresh isolated worlds, each opened by three separate JVMs:

| Boundary | Preparation | Interrupted JVM | Recovery | Actual disk coins before recovery | Final coins |
| --- | --- | --- | --- | --- | --- |
| Before debit chunk writes | All 1 required tests passed | halt(70), expected failure | All 1 required tests passed | 128 | 64 |
| One of two chunks written | All 1 required tests passed | halt(70), expected failure | All 1 required tests passed | 96 | 64 |
| Debit saved before PAID | All 1 required tests passed | halt(70), expected failure | All 1 required tests passed | 64 | 64 |
| Refund saved before REFUNDED | All 1 required tests passed | halt(70), expected failure | All 1 required tests passed | 128 | 128 |

The interrupted JVM executes `Runtime.halt(70)` inside the physical persistence boundary, bypassing Minecraft save/stop,
shutdown hooks and finally blocks. Its Gradle launcher fails as expected and has **no GameTest pass line**. It is not
counted as a passing test run. Preparation and recovery require their own explicit `All 1 required tests passed` lines.
Original plan metadata and political data were persisted before interruption; recovery does not repair them. Coins,
physical chest identities and complete journal images are read from the previous processes' actual files. Recovery
also retries the same ID to assert no second debit/refund. The cases were run in two harness invocations so the already
verified before-write case was not repeated. result.json preserves the exact world IDs and stage results.

Reproduce all boundaries in fresh worlds:

```sh
python scripts/verify-payment-crashes.py -- python /workspace/cloud-setup/millenaire-gradle.py --offline
```

The default command is `bash gradlew --offline`; the cloud helper above selects this instance's full JDK/truststore.
These are process-termination tests on Linux, not physical power-loss tests or proof of atomic cross-file commits.
