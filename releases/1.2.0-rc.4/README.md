# 1.2.0-rc.4: narrative personality names / kişilik adları

The kingdom screen expresses the realm's temperament using these matching English/Turkish names:

| Köy kökeni / Origin | Türkçe | English |
|---|---|---|
| Askerî / Military | Savaşçı | Warlike |
| Dinî / Religious | Gelenekçi | Traditionalist |
| Tarımsal / Agricultural | Barışçıl | Peaceful |
| Zanaatkâr / Artisan | Üretken | Industrious |
| Saray / Genel / Palace / General | Pragmatik | Pragmatic |

The existing behavior and saved identities remain unchanged. Peaceful/Barışçıl expresses a lower tendency toward war, not absolute pacifism. [Naming rationale and native-origin mapping](../../docs/1.2.0_PERSONALITIES.md). Construction and Shipping remain under Rulership; the RC.2 reserve breakdown remains available.

Own addon only, matching client/server builds with original Millénaire9.0.3 / Minecraft1.21.1 / NeoForge21.1.259 / Java21. The dependency and test classes are separate. SHA256SUMS pins the artifact; previous candidates remain preserved.

[Verification](../../verification/1.2.0/rc.4-personality-names/README.md): build,314 matching EN/TR keys and equivalent format arguments, all200 production classes identical to RC.3, actual production-JAR normal save/stop/reopen in two JVMs. No GameTest package was repeated for this text change.
