# Bounded native construction readback

Candidate1.2.2-dev.6, based on8b18734. Frozen JAR SHA-256:
`0d17715ea023bdbdc6e9ba2ff9f9d8d73faaabba5c66051b20ac2c0bf53c868f`.

A gameplay report showed a ready construction decision failing with `Tried to read NBT tag that was too big` at the64MiB allocation budget. The plan was already LINKED to a real domain record, subsequently quarantined as RECOVERY_REQUIRED/AMBIGUOUS_ENGINE_STATE. That outstanding liability correctly prevented a second major project. The report is not an unpaid-target readiness failure. Private gameplay identities and logs are not included here.

RoyalVillagePersistence previously decoded the complete native village file before and after saving a single project's acknowledgement. The new NativeVillageReadback uses the same open file for a projected UUID/header pass and a selected-village pass. It retains native version checks, duplicate identity rejection, exact disk/live pending/building comparisons and the original worker barrier. Only relevant pending/building fields are retained. There is no native file rewrite in the reader, changed save format, chunk load, new receipt, project fabrication or automatic adoption of ambiguous ownership.

Limits remain explicit:64MiB retained NBT accounting per pass,100000 indexed villages,512MiB expanded stream per pass and the native parser depth limit. Skipped payload still consumes a bounded decompression budget; both passes consume the full GZIP/NBT stream. The synthetic64MiB unrelated payload readback measured262510604/262582961ns in the two JVMs. This measures a controlled pressure fixture, not general gameplay latency or a naturally generated80-day world.

The negative image has all283 production class bytes identical to frozen dev5; metadata/test package changes alone reproduce the failure. The new case places a controlled large opaque extension on a different genuine serialized native village. It proves the exact target can be acknowledged without allocating the unrelated payload, rewriting those bytes or changing the live project. Existing future/corrupt/stale-file rejection tests remain in the construction package.

| Suite | Exact required line in both clean write and separate-JVM reopen |
| --- | --- |
| verifyRoyalConstruction | All 56 required tests passed |
| verifyRoyalRecovery | All 17 required tests passed |
| verifyEconomicQueue | All 11 required tests passed |

All pairs exit0 and stage the exact same frozen image. The new recovery case starts from an existing quarantined64-denier paid order and adopts only the exact existing pending project through OP authorization. It retains the same order ID, original pending object,64 actual remaining deniers and committed receipt; replay does not debit again. Existing recovery tests also preserve authorization across JVMs and complete a genuine233-block original NPC building with full physical match after reopen.

Both actual cut gates immediately before/after native village persistence pass. All four prepare/recovery JVMs log `All 1 required tests passed` and exit0. Each interrupt JVM halts70 without a success line; the Gradle wrapper reports exit1 and the explicit nonzero JVM exit70. Recovery preserves the actual64-denier balance, original paid identity and quarantine of uncertain publication. See process-cut-results.json and the six complete compressed logs. Halted JVMs are expected interruptions, not passing tests.

After installation, an already quarantined gameplay order still needs explicit OP review through the existing `royal_recovery inspect_order`, `adopt_pending` or `adopt_building` commands. Adoption refuses mismatching pending work, unknown buildings or missing/ambiguous receipts. No reset, fee forfeiture, second charge or guessed publication is part of this fix.

A separate internal probe reproduced a different unpaid-target policy starvation when another member is ready. That is not this report's LINKED/NBT failure, and no fix for that separate policy issue is claimed in this focused image. Its private failed evidence remains preserved. Long day-based acceptance remains manual; no new day-by-day observation, graphical client or online-login success is claimed.
