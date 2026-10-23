# 1.1.0 save fixture

`review-1.1.0-kingdoms.dat` was copied from the isolated 1.1.0 review GameTest world,
`/tmp/kingdoms-review-_5f3y2_0/run/gametest/world/data/millenairekingdoms_kingdoms.dat`.
It was written by the original 1.1.0 production code, before the maintenance fixes.
It is a test world, not a player's save. SHA-256:

`ca036bd7dc2e862dd352f52aa218e75a56f4645b4f4a480a6f0ffc46ef7c19ec`

`MaintenanceDataGameTests.actual110SaveSurvivesSeparateJvmUnchanged` loads its
format-6 payload, writes it under a separate SavedData key and compares every
field after a second JVM loads the saved copy. Map-backed NBT lists are compared
in canonical order; gzip headers and Minecraft's outer save envelope are not
compared. The original fixture is never rewritten.

`fullLedgerSurvivesSeparateJvm` additionally covers a representative valid
format-6 record containing rulers, valid waiting orders, applications, income,
peace offers, truces and conquest progress. Existing suites cover formats 1–5,
corrupt/future data and the founding calendar. These fixtures and the test mod
are excluded from the production JAR.
