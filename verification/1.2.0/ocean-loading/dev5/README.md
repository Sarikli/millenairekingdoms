# 1.2.0-rc8-alpha.3.dev5 disabled-behavior evidence

Each package uses a distinct fresh world and successful write before reopening the SAME world in a separate JVM, exit0/BUILD SUCCESSFUL and the same JAR SHA. Active-survey tests were superseded by the explicit user cancellation; their old artifacts/evidence remain preserved.

| Package | Write | Separate-JVM same-world reopen |
|---|---|---|
| verifyGeography | `All 4 required tests passed` | `All 4 required tests passed` |
| verifyAtlas | `All 19 required tests passed` | `All 19 required tests passed` |
| verifyHistory | `All 36 required tests passed` | `All 36 required tests passed` |

SHA256 `3149ed28e55a2899f3e2d27ba41f04ceb9857e58430879d8a44597ab6d8ff5de`. Actual graphics/input/scaling/authentication remain manual.
