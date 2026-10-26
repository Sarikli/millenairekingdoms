# Native cargo actual abrupt-process recovery

Ten fresh worlds/30 JVMs. Every prepare/recover logged **All 1 required tests passed** (20 normal JVMs). Ten interrupt
JVMs deliberately halted70, failed Gradle, and had no GameTest pass or graceful shutdown/save lines. Full raw compressed
logs, individual uncompressed hashes and exact outcomes are retained. Original9.0.3 village/plan inventory metadata
rehydrates without repair. No fake NPC, task cursor/status or native JAR modification.

| Cut | Disk physical before recovery | Final physical | Final escrow |
|---|---:|---:|---:|
| before Items+proof save |100|60|40|
| collection saved before ledger ack |60|60|40|
| first independently saved source chunk |64|60|40|
| delivery saved before ledger ack |100|100|0|
| return saved before ledger ack |100|100|0|
| delivery then native40 withdrawal to recipient |100|100|0|
| actual slot write before proof assignment |100|60|40|
| collection ack before dispatch |60|60|40|
| delivery ack before terminal history |100|100|0|
| collection then native known40 deposit restoring BEFORE |100|100|40|

All but the last case account for the original100 goods. The final case additionally supplies40 known external input
through original BuildingInventory.add, so physical100 +escrow40 =140. The credit-activity case actually saves the40
withdrawn goods into a separate real chest, retaining total100. Every recovered operation is replayed without a new
physical delta. The slots-before-proof hook exists only in a test ChestBlockEntity subclass.

Cargo route/mandate/time/transport-fee inputs remain synthetic low-level fixtures. This validates the native per-container
and shipment-operation ledger crash boundary, NOT an authorized paid timed village service, live runtime/UI, host power
loss, arbitrary external editors/mods or whole-world atomicity. Actual fee/route/time domain integration follows.
