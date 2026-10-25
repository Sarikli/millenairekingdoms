# Regression in progress — verified intermediate checkpoint

Completed fresh pairs so far: Wars `All 30 required tests passed`, Conquest `All 25 required tests passed`, Stage5 `All 26 required tests passed`, Capitals `All 5 required tests passed`, EACH write and separate-JVM same-world reopen exit0/BUILD SUCCESSFUL. Exact logs/hash metadata are in checkpoint-results.json. Same validated dev7.2 artifact SHA8c1856c85669a679fc261dd617628c243d7f82f4afdee0052f0c2323806bcfae, production bytes unchanged.

Four legacy/detached schema assertions now explicitly check compatibility6 (max7 requires a real live history nonce). Capital income test now checks real RC.8 capital production plus member income and all coin denominations, not pre-RC.8 zero-capital/bronze-only assumptions. All failed attempts remain under attempts and had no dependent reopen. No production bug fix was needed for these old assertions.

The separate [400-day model](simulation/README.md) has four successful independent JVMs, each literal `All 1 required tests passed`. Remaining current regression packages and final production-JAR packaging/lifecycle are still pending at this checkpoint. No graphical/authenticated claim.
