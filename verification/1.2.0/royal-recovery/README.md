# Audited operator recovery evidence

`recovery-15-pair.log.gz`: raw Gradle/Minecraft output from a fresh isolated world `royal-recovery-01`.
Both write and separate JVM reload explicitly logged **All 15 required tests passed**. Normal build passed.
`construction-53-pair.log.gz`: related schema/runtime regression in fresh `royal-recovery-schema-01`,
**All 53 required tests passed** in both JVMs, plus build. No unrelated completed package was rerun.

Production JAR and original dependency hashes are in `results.json`; the original dependency is never redistributed.

Coverage and limitations: `docs/1.2.0_RECOVERY.md`. Real original building adoption at native task 0/2447, genuine
partial construction saved at 2197/2447, separate JVM completed 2447/2447, carried spruce 14 -> 0, template 233/233.
The fixture creates only the baseline village with the original spawner; no fake commissioned structure/builder,
task progress/status edit or speculative inventory repair. Original SavedData/file-lock failures are real boundaries.
Yggdrasil public-key requests are refused; online authentication and graphical gameplay are not passing claims.
