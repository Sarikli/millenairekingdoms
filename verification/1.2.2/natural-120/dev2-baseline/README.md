# Frozen dev2 natural-world baseline

**Economy liveness failed:**120 actual zero-player days produced0 paid construction orders,0 shipments,0 payments and0 economic decisions. Unpaid intentions are not orders. Normal shutdown and same-world separate-JVM reopen succeeded, preserving original economic/political records.

See [result.json](result.json) for source/JAR hashes, exact counts, unchanged64/500 budget and raw evidence SHA-256 values. The `.gz` files contain the original generated baseline,121 daily samples, final snapshot, native generation/history and reopen receipt. These contain generated test settlement/NPC identifiers, no real player information or world assets. The original native dependency and actual world are not redistributed.

The fresh normal seed20261027300 used original native generation,10% natural capitals and1000-block spacing. A passive original embedded test player explored normal terrain during preparation only. The measured interval removed that player and all observer exploration tickets and observed2,880,000 successive ticks without clock skips. It had3 natural capitals and42 non-lone settlement records (including child villages); these are not42 independent villages. Politics recorded7 incorporations,10 village conquests and18 strategic battles, with no kingdom wars in this seed. These political outcomes do not establish economic acceptance.

The original completed world is preserved locally. A separately copied and SHA-verified world ran another24000 real ticks with the frozen dev2 JAR and a read-only diagnostic observer. Its `diagnostic-*` files show zero persistent force flags, an increasing empty-level timer and42 loaded NPC tracks frozen across at least1000 world ticks. For example, original native NPCa0a7dbde-7bf2-4ab0-86be-087da23050b1 stays at tickCount0 between elapsed4600 and6800 during a WORKING lease. NPC goals remain null. Physical treasuries had funds; this was not merely a fee shortage.

NeoForge21.1.259's ServerLevel.tick calls ForcedChunkManager.hasForcedChunks, which reads persistent vanilla/NeoForge forced-chunk records. Transient economic region tickets are absent from those records. After300 empty-world ticks it skips the entity/block-entity loop. Earlier controlled GameTests retained the framework's own permanent area flags, masking this failure. Dev3's regression removes those flags and measures actual original NPC tick advancement; its results belong in a separate checkpoint.

No graphical-client or authenticated-login result is inferred from these offline server runs.
