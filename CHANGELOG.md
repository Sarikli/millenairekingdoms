# Changelog

## 1.1.1 — 2026-10-22 (local maintenance build)

### Fixed

- Reject expired or withdrawn military orders before any Millénaire raid can start.
- Apply conquest raid tension using the original war parties even when the victory already transferred the village.
- Retire waiting military orders atomically when rulership changes, including immediate abdication/reclaim by the same player. Active battles continue normally.
- Release unresolved applications when a kingdom returns to NPC rule. Legacy orphan applications are released on the next daily step, rather than discarded during save loading.
- Resolve village targets and operator kingdom targets consistently by exact name or UUID. Ambiguous village names require a UUID; authority checks remain server-side.
- Build the JAR with the repository's MIT license metadata.

### Verification and maintenance

- Keep all 113 existing GameTests and add 12 maintenance tests covering authority transitions, stale orders, diplomacy, command permissions and old-save integrity.
- Fix the natural-capital test's assumption that the persistent income calendar always starts at day zero.
- Pass all seven suites: 125 tests in the write pass and 125 in separate-JVM restart passes, using the final 1.1.1 JAR.
- Keep save format 6, existing migrations, original UUIDs, real Millénaire integration and existing gameplay rules.
- Document confirmed findings, remaining risks and test limitations in [the maintenance report](TEST_RAPORU_1_1_1.md).

No stable version tag or GitHub Release has been published by this maintenance task.

## 1.1.0

The existing development-based NPC kingdom founding release is preserved. See [its original report](TEST_REPORT_1_1_0.md).
