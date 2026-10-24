# Strict shipment ledger gate

Fresh world royal-shipment-ledger-01 / verifyRoyalShipments: **All 9 required tests passed** in each write/reload JVM;
normal offline build passed. Raw compressed log, uncompressed log hash and artifact hash are retained.
These are real atomic filesystem/ledger tests with synthetic operation acknowledgements: they do NOT prove physical
cargo collection/delivery, paid logistics, native surplus, abrupt process safety or live timed shipments.

Covers immutable intent/source reservation/terminal history, unacknowledged collection not spendable, durable dispatch
time and no premature arrival, pending credit allocation retained through cancellation, partial delivered/returned
accounting, proved-unperformed operation tombstones, position/identity/operation collisions, eight unknown/corrupt file
cases preserving bytes, real file lock, deep immutable images, authority-retirement cancellation provenance including
quarantine, and separate-JVM unresolved destination allocation recovery (40 collected/15 delivered/25 returned/0 escrow
as synthetic ledger inputs). Physical proof must precede every future live acknowledgement.
