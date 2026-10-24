# Real abrupt JVM cuts after native receipt correction

Seven fresh worlds/21 actual JVMs: each prepare/recover logged **All 1 required tests passed**; each middle JVM
used Runtime.halt(70), exited unsuccessfully and logged NO GameTest pass or graceful shutdown. Raw compressed logs
and uncompressed hashes are retained. Before/partial/after were not repeated when the runner stopped before a fourth
JVM launch on a temporary, unrelated shipment-draft compile error; that failed invocation is separately preserved.

| Boundary | Capital on disk before recovery | Capital after recovery | Meaning |
|---|---:|---:|---|
| before |128|64|Proof and changed items were not saved|
| partial |96|64|Only one independently saved chest had its Items+proof|
| after |64|64|Both saved debits precede PAID acknowledgement|
| refund |128|128|Saved refund precedes REFUNDED acknowledgement|
| slots-before-proof |128|64|Test-only native ChestBlockEntity.setItem hook halts immediately after a real slot write, before coordinator proof assignment; unsaved BE mutation is lost|
| debit-activity |128|128|After a saved64 debit, known original BuildingInventory deposits32 into each physical chest restore old before images; replay keeps that additional64 input|
| refund-activity |64|64|After a saved64 refund, original inventory withdraws64 bronze into a separately saved real recipient chest; recipient64 +capital64 stays128 after recovery|

The hook is only in a test subclass, no production switch/Mixin/dependency edit. Actual save/read-back/recovery still
uses native Items+NeoForge data serialization. Native API activity is real; no claim that a merchant AI goal ran.
This establishes these process-crash boundaries, not host power loss, arbitrary external edits or shipment escrow.
