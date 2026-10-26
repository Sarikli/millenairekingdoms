# 1.2.0-rc.10 — Chronicle prose revision

[JAR](millenairekingdoms-1.2.0-rc.10.jar) · [SHA256SUMS](SHA256SUMS) · [Actual EN/TR examples](../../docs/1.2.0_NARRATIVE_EXAMPLES.md) · [Editorial scope and verification](../../docs/1.2.0_NARRATIVE_REWRITE.md)

Repeated chronicler introductions and Ethos closing sentences are removed. all 14 English/Turkish event kinds receive concise bodies/headings and three actual variants per form. Occasional word-level choices carry tone within the event. All 26 locales lose the old global boilerplate; other languages retain their localized bodies pending a separate editorial pass.

Saved historical facts remain unchanged, including previous events displayed through the new renderer. This candidate retains all staged 1.2.0 gameplay changes, including village-only atlas pages, developed natural capitals and the 50-unit shipment ceiling. The earlier 1.2.0 JAR is preserved separately.

Source commit `b4c234679ebae452dfbac5f16ec3187043a1f23b`. JAR SHA-256 `07a596e2d156b73fe70ad39f231d71366ce1ad32c0947f144a61415ccbdcbb0f`; 1,939,394 bytes. Java 21 / Minecraft 1.21.1 / NeoForge 21.1.259 / separate original Millénaire 9.0.3; matching protocol 10.

Final identical-JAR verification: History38+38 and Localization8+8, each with its exact required-test pass line, exit0 and BUILD SUCCESSFUL. Two normal production-JAR startup/save/stop/reopen JVMs passed without a test mod. Only ChronicleNarrator.class changes among production classes; the other 270 remain identical to staged 1.2.0. Graphical font/input/layout, authenticated login and native-language approval remain separate manual checks.

Same Draft PR3; no main merge, stable tag or GitHub Release.
