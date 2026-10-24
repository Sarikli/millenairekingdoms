# Durable construction cancellations

Fresh `royal-cancellation-03` world: **All 30 required tests passed** in each of two separate JVMs; normal build passed.
The prior 19 tests remain. Eleven added tests cover actual unpaid/paid/PREPARED cancellation, payment-before-order
acknowledgement, accepted nonrefundable patronage, preserved original pending work, full-inventory deferred refund/retry,
conflicting payment purposes, ambiguous publication, v1 migration of every stage/terminal tombstone, and separate-JVM
resumption of both accepted and unaccepted cancellation decisions. No fake NPC or completed structure is used.

Format 2 retains cancellationFrom before financial work. A legacy v1 unresolved cancellation has unknown origin and
requires explicit recovery; reading does not rewrite the file. QUEUING cancellation also preserves the reservation and
receipt with AMBIGUOUS_ENGINE_STATE, rather than guessing ownership from a matching natural plan or minting a refund.

Trial 01 passed 27+27 before the final three cases were added. Trial 02 ran 30 tests and failed the full-treasury case
because the test caught IllegalStateException while the planner's expected no-space exception is IllegalArgumentException.
That test catch was corrected. Trial 03 is the final 30+30 evidence above. Unrelated suites were not rerun.
Actual autonomous builder completion, authority cleanup, domain abrupt crashes and GUI/scheduler integration remain.
