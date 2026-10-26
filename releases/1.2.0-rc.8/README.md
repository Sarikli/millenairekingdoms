# 1.2.0-rc.8 — upgrades, product picker and capital production

- **Rulership → Construction:** upgrade completed non-centre buildings when their original next level is eligible. Default administrative fees128 →192 →256 deniers (base64 × target display level), real materials still required. Existing building UUID/target/quote persist; the unpaid waiting queue and single village slot are retained. Genuine original NPCs perform the upgrade.
- **Shipping:** click the product name for a searchable list with actual item icons and localized names; choose a row, or Back/Esc without changing selection. Stock protection/transport rules stay intact.
- **Treasury:** active capitals produce4 deniers per completed centre level/day, added to existing member income through the existing daily ledger/real coins. Same-day restart cannot mint again; full/unloaded storage leaves owed income waiting.

Türkçe: Merkez dışındaki uygun binalar128/192/256 denier artan destek bedeliyle yükseltilebilir; malzeme ayrıca gerekir. Sevkiyat ürününe tıklayınca resimli/aranabilir liste açılır. Başkent, tamamlanmış merkez seviyesi başına günde4 denier üretir; bağlı köy gelirine eklenir.

[Rules/balance/compatibility](../../docs/1.2.0_RC8_UPGRADES_AND_INCOME.md) · [exact raw validation/failures/limits](../../verification/1.2.0/rc.8-upgrades/README.md). Nine related paired packages, genuine two native upgrades/restart, daily production/restart, publication cuts and normal production-JAR save/reopen are recorded.

Own addon only. Matching client/server **protocol4**, original Millénaire9.0.3 / MC1.21.1 / NeoForge21.1.259 / Java21. Construction format5 reads1–4 lazily; recovery audit2 reads1. Back up the whole world before upgrading; earlier candidates cannot read the new formats, so restore a matching whole-world backup to downgrade. Never delete transaction files. Prior candidates preserved; no stable release/tag/merge.
