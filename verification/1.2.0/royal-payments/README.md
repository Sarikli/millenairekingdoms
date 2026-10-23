# Royal payment coordinator checkpoint

Fresh isolated world: `royal-payment-05/verifyRoyalEconomy`.
Both log lines are required: **All 41 required tests passed** (write, then separate reload JVM).
Normal offline build also passed. See result.json for the dependency/development JAR hashes.

The three restart cases keep original 9.0.3 building-plan metadata and real saved chests/journal files.
Faults here are injected exceptions/save ordering, not an abrupt process kill or power-loss guarantee.
Initial split-package launcher failure, an overlong fixture name, and invalid fixture plan metadata were
corrected before this final clean-world pair; no failed attempt is counted as a passing pair.

Reproduce with a NEW run ID:

```sh
python /workspace/cloud-setup/millenaire-gradle.py --offline --init-script scripts/isolated-tests.gradle -Dkingdoms.testSuite=verifyRoyalEconomy -Dkingdoms.testRun=NEW-RUN verifyRoyalEconomy
```
