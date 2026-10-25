# Changelog

## 1.2.0-rc.8 — 2026-10-25 (upgrades, product picker, capital production)

- Add player royal upgrades for original eligible completed non-centre buildings, binding exact UUID/next level/quote. Charge construction base × target display level:128/192/256 with64 base, material costs separate. Retain unpaid queue, one village slot, authority/fee/recovery safeguards and original NPC work. Order5 lazily reads1–4, audit2 reads1; network4 prevents old clients misreading the new upgrade offers.
- Replace shipment product cycling with a searchable icon/name list and Back/Esc return; retain server stock/authorization/transport rules.
- Add capital production4 deniers per completed centre level/day through the existing daily ledger/native coins, including capitals without members; preserve once-per-day/restart/unavailable-storage behavior and existing member rates.
- Nine related paired packages logged their exact All28/3/26/7/55/16/10/18/9 lines per JVM, with real original building→two upgrades for64+128+192,100% final physical match and actual capital coin/day restart. Two publication process cuts and ordinary production-JAR save/reopen also verified. Initial plan-only fixture selection failure is retained; corrected only the test, kept the successful unchanged-binary GUI pair. [Exact evidence/scope](verification/1.2.0/rc.8-upgrades/README.md). Graphics/auth remain unverified; no unrelated full matrix/400-day repeat.

## 1.2.0-rc.7 — 2026-10-25 (construction waiting queue)

- Let players queue one unpaid royal mandate behind ongoing ordinary native construction/upgrade. Preserve the current native project/task; revalidate current online ruler, original loaded target/treasury, eligibility and real funds before automatic single payment at the saved quote. Persist waiting across restart, cancel without fees/native deletion, retain one active royal slot per village.
- Fix the observed native20-tick/royal100-tick handoff race with a pinned noncancelling per-village pre-growth hook. Extend the native-building observation baseline atomically only before first publication so preceding natural work cannot fulfill the royal order. Format4 lazily reads1–3 and preserves all earlier liabilities/recovery identities. Earlier candidates cannot read4; downgrade requires a matching whole-world backup.
- Final paired JVMs each logged `All 2 required tests passed` (live), `All 20 required tests passed` (GUI), `All 55 required tests passed` (construction), `All 16 required tests passed` (recovery) and `All 10 required tests passed` (sites). Actual original villagers finish the prior1048-step upgrade then a distinct royal building at100% physical match, one64 fee. Two relevant abrupt publication cuts retain conservative recovery; normal production-JAR save/stop/reopen and packed EN/TR320-key checks passed. [Raw evidence/failed attempts/scope](verification/1.2.0/rc.7-construction-queue/README.md). Graphical/online checks remain unverified; unrelated successful suites/simulation were not repeated.

## 1.2.0-rc.6 — 2026-10-25 (Ethos / Karakter heading)

- Rename the kingdom screen heading to **Ethos** in English and **Karakter** in Turkish. Keep all five temperament names and the existing translation key/format argument.
- Build and packed translation checks passed; all200 production classes match RC.5. Only the two heading values and version metadata differ. No GameTest package was repeated for this text change; graphical label fit remains unverified. RC.5 timing defaults/configuration and previous evidence remain intact.

## 1.2.0-rc.5 — 2026-10-24 (natural capitals and decision timing)

- Set new natural-capital chance to10% and both natural/development-founded capital spacing defaults to1000 blocks. Joining/shared political evaluations now default to1 day. Halve other decision waits with rounding down: founding7→3, raid cooldown3→1, truce10→5, peace age12→6/idle6→3/long-war36→18 days.
- Halve NPC raid preparation6000→3000 ticks, order lifetime48000→24000 and queued-order checks100→50. NPC economic checks1200→600 ticks and attempt/project/village/route waits3/6/12/6→1/3/6/3 days. Retain all other probabilities, relation/level/reputation thresholds, personality weights, real payments/stock protections and actual cargo travel.
- Existing active TOML values and already persisted due dates/truces/order lifetimes are retained. A [partial gameplay settings list](releases/1.2.0-rc.5/serverconfig-changes.toml) supports updating existing installations without replacing unrelated configuration. Full [timing table and compatibility details](docs/1.2.0_RC5_TIMINGS.md).

- Focused paired GameTests logged exact All5/22/12/12/1/1 in each write/separate-JVM reopen, plus normal build and actual production-JAR save/stop/reopen. Generated normal config changed exactly the12 preset keys. An obsolete3-day assertion failed once, was corrected only in the fixture and passed in a fresh world; failure retained. [Exact evidence/scope](verification/1.2.0/rc.5-timings/README.md). Graphics/online remain unverified; unrelated successful suites were not repeated.

## 1.2.0-rc.4 — 2026-10-24 (narrative personality names)

- Name the kingdom's temperament in English and Turkish: Military → Warlike/Savaşçı, Religious → Traditionalist/Gelenekçi, Agricultural → Peaceful/Barışçıl, Artisan → Industrious/Üretken, Palace/General → Pragmatic/Pragmatik.
- Keep the existing capital-origin identities, policy weights and persisted data. The five displayed names in each language and version metadata are the only changed production payloads; all200 Java classes match RC.3. English/Turkish retain314 matching keys and equivalent indexed/implicit format arguments. Build passed; no new or repeated GameTest result is claimed for this text change.
- Rulership navigation and stock diagnostics remain available. Graphical label fit and authenticated login remain separate manual checks; the user's1047/0 stock report remains open.

## 1.2.0-rc.3 — 2026-10-24 (rulership navigation)

- Reduce the top navigation to five main tabs. Open Construction and Shipping with two buttons inside Rulership; the Rulership tab or Esc returns from either page. Reopening starts at the parent page.
- Keep the royal forms intact, make rulership information scrollable, and retain the fixed claim/abdication controls and existing confirmation/authority checks. Add the English/Turkish return hint; retain RC.2 stock diagnostics.
- Gradle build passed. The focused existing GUI suite logged **All 7 required tests passed** in both write and separate-JVM reopen; the actual RC.3 production JAR also completed ordinary startup/save/stop/reopen without a test mod. Server/view/payment/cargo/domain payloads are byte-identical to RC.2. No unrelated successful suite was repeated.
- Graphical layout and online authentication remain unverified. The user's1047/0 shipment-stock report remains open; this UI change does not alter stock protection.

## 1.2.0-rc.2 — 2026-10-24 (stock diagnostic follow-up)

- Show actual and kept shipment stock, plus a product-hover breakdown of native target, construction, residents, configured minimum and Millénaire protection. Insufficient-stock messages show requested/sendable/kept amounts in English and Turkish.
- Add a genuine original Norman religious storage regression:1047 cobblestone protects1024 and permits23; actual collected23/remaining1024 survive separate JVM, then cancellation returns all goods/one fee exactly. A controlled2048 minimum explains a genuine zero with no construction; it is not assumed to be the user's configuration.
- Focused shipment GUI passed18+18, existing GUI7+7/build; actual RC2 normal production-JAR startup/save/stop/reopen passed. Stock/payment/cargo/domain bytecode is unchanged; no reserve is bypassed or world format changed.
- **The reported user's1047/0 root cause remains open.** This follow-up exposes the values needed to diagnose the actual world; the controlled test does not reproduce its zero. Graphical tooltip layout and online authentication remain unverified. See [investigation](docs/1.2.0_RC2_STOCK_DIAGNOSTICS.md).


## 1.2.0-rc.1 — 2026-10-24 (release candidate)

- Target the verified original Millénaire 9.0.3 with Minecraft 1.21.1, NeoForge 21.1.259 and Java 21; keep the dependency separate.
- Spend real capital coins through durable, bound native-container receipts. Preserve denomination change, tax debts, refunds and later legitimate inventory activity; quarantine uncertain/future evidence.
- Commission culturally eligible autonomous village construction. The original engine selects sites and its genuine NPC builders consume real materials; loaded physical block/entity persistence precedes completion acknowledgement. Add explicit cancellation and audited OP recovery.
- Ship protected real surplus between member villages for a distance/quantity fee and server-tick travel time. Preserve native project/household stocks, exact held cargo, full/unloaded destinations and governance retirement without restricting ordinary trade.
- Add Construction and Shipping to the existing kingdom screen with server-owned offers, replay protection, saved history/status and English/Turkish translations (seven tabs, protocol3).
- Derive five personalities from exact native capital village types. Add bounded, need-based NPC investments/logistics through shared paid services, actual reserves/share limits, persistent cooldowns, war-time military preference and existing eligible campaign weighting. Player rule excludes new NPC economic decisions.
- Preserve political format6 and original1.1.0/representative original1.1.1 serializer fields across restart; document strict new stores and unsupported full downgrade.
- Retain actual failed/passing process-crash evidence, original builder/material/block gates and400-day/four-JVM conservation/performance measurements. The final [technical report](docs/1.2.0_RELEASE_REPORT.md) records exact regression results and RC checksum.
- Graphical client layout and authenticated online login remain separately unverified in the manual checklist. This is a review candidate; no stable release, tag or merge is performed.

Türkçe: Gerçek para ile özerk inşaat siparişi ve korunan stoktan süreli sevkiyat, iki dilli arayüz ve başkent türüne bağlı NPC ekonomi kararları eklendi. Vergi modeli ve normal Millénaire ticareti korundu. Gerçek envanter, makbuz, yeniden açma ve özgün NPC inşaat kanıtları raporda bulunur; görsel/çevrimiçi kontroller ayrı ve henüz doğrulanmamıştır.

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
