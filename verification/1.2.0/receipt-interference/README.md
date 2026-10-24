# Confirmed refund interference duplication — currently failing

refund-baseline-failed.log.gz is an actual FAILED GameTest, not passing evidence. Fresh royal-refund-baseline-01,
runRoyalReceiptGameTestServer: one required test failed; no reload was launched.

Initial real capital128, actual debit64, actual refund saved back to128 but its journal acknowledgement deliberately
throws after native chunk writes. Original BuildingInventory.remove then moves one real silver coin to another physical
chest, preserving total128 (capital64 +recipient64). That legitimate native inventory operation happens to restore the
old refund before image. Retrying refund credits64 again: capital128 +recipient64 =192, journalREFUNDED. Duplicated64.

This is a real saved-inventory/native-API interference test with injected acknowledgement failure, not an actual JVM
halt, an online client or proof that a particular autonomous merchant goal ran. It confirms the recovery algorithm can
mistake matching quantities for an unapplied refund. Prior passing payment packages did not cover this coincidence.
The Draft update must not be called finished until this reproducible currency duplication is fixed and verified.

Planned fix: exact bound operation receipts saved together with slot changes in the same native chest NBT, acknowledged
read-back, immutable operation binding, replay preserving later legitimate contents, and safe receipt cleanup only after
durable journal acknowledgement. Legacy pending images without such evidence must not be guessed safe.
