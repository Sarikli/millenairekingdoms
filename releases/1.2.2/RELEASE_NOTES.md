# Millénaire Kingdoms 1.2.2 — By Royal Decree

Minecraft 1.21.1 · NeoForge 21.1.259 · Java 21 · Millénaire 9.0.3

- NPC kingdoms keep persistent unpaid building plans that wait behind native village work and claim the next free native construction slot through the pre-growth handoff.
- Planned projects can request partial supply shipments; construction and logistics use independent order IDs and existing physical payment/cargo protocols without starving ready construction.
- Bounded temporary economic activation lets previously generated villages work without nearby players (max 1024 tickets, no new terrain, military priority, released before shutdown), and renews the empty-dimension grace timer while a valid lease is active.
- Assigned construction materials are kept out of native shop deliveries while an NPC builder carries project resources.
- Construction save readback is bounded to the selected native village instead of decoding the entire world village file, preventing 64 MiB NBT allocation failures in large worlds while preserving existing quarantined order recovery without a second fee.
- Fixed locale-sensitive sub-building path lowercasing in `NaturalCapitals` (`Locale.ROOT`).
- New-world NPC budget defaults: 64 deniers retained and 50% maximum first fee (existing world configurations remain unchanged). Read-only OP command: `/kingdoms economy explain <kingdom>`.

Use matching 1.2.2 clients and servers (network protocol 11). Back up your world before upgrading.
