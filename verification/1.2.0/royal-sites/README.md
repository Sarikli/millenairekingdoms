# Original unavailable-site retry evidence

- `baseline-failed.log.gz`: actual original placement failed, native cooldown expired, old paid order stayed NO_SPACE
  with an empty pending slot and balance64. One required GameTest FAILED; this is not a passing run.
- `unavailable-reason-failed.log.gz`: first expanded 10-test write run FAILED one required test because inactive-village
  diagnostics erased the known placement-failure evidence. No reload was launched after this failed write.
- `sites-10-pair.log.gz`: final fresh royal-sites-final-03, both separate JVMs logged **All 10 required tests passed**,
  plus normal build. Genuine native location rejection/retry, native placement, authority/agency and original I/O failures.

Hashes/results: results.json. Full behavior/limitations: docs/1.2.0_CONSTRUCTION.md. Only actual original growth chooses
the location and creates the task. The test changes ordinary terrain to make a later location legal; it never sets an
explicit commissioned site, places a royal template, advances a task or claims fake resident completion. Native cooldown
tests use controlled clocks. Original temporary cooldown is verified absent after reload. Known saved failure retries;
uncertain publication is quarantined before engine ticks with the exact original pending and previously paid fee.
The first one-test fixed pair is superseded by the final 10+10. Initial compile diagnostics are not GameTest passes.

Related service/monitor regression royal-site-regression-01 also logged **All 53 required tests passed** in each
separate construction JVM, plus normal build. Raw log: verification/1.2.0/royal-sites/construction-53-pair.log.gz.
Unrelated completed packages were not rerun.
