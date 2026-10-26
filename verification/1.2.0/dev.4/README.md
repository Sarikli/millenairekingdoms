# dev.4 player shipping gate

The raw logs contain exact required success lines in separate write/reload JVMs. Initial shipping16+16 and final
shipping17+17 passed; final adds changed local endpoint ownership and clean stale-stock rejection. Related existing
construction GUI14+14 and existing GUI7+7 passed because shared screen/action/protocol code changed. Normal builds
passed; final production artifact is hashed in results.json. No unrelated successful backend/crash package was repeated.

Real original native storage reopens source56/target4/held40/capital119 without repair; the saved full actor/route/product/
quantity/price/duration intent replays through the actual action handler with an empty new-JVM offer cache. Other cases
cover conservative stock/native activity, exact real fee/refund/cancellation, full destination/retry, unavailable source,
unloaded destination metadata, stale endpoint/price/duration/selection/expiry/clock, actor/tenure, forged requests,
unknown-format preservation, shared throttling, bounded codec and EN/TR labels. Test-only fixtures supply coins/goods,
active/loaded villages and a controlled clock for the full-destination case; they do not prove graphical/online use.

Earlier original real-timed event1+1 and separate runtime budget1+1, actual paid service49+49, native cargo cuts30 JVMs
and joint fee/domain cuts9 JVMs are retained in their own directories, not rerun or relabelled as fresh dev.4 results.
No DISPLAY/Wayland/Xvfb or established authenticated client session was available. The owner checklist remains unchecked.
