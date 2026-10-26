# Original village SavedData acknowledgement

Fresh world; separate write/reload JVMs both logged **All 15 required tests passed**, plus normal build.
Counts: 6 original catalog, 6 atomic own order index, 3 original village SavedData barrier tests.

The new tests use the actual VillageSavedData serializer and NeoForge asynchronous worker, then read the original file.
They acknowledge exact pending metadata/removal/building metadata, reject a stale file even if a flush method returned,
compare building origin, and preserve future/corrupt/wrong-list-type original data before triggering a save. Removed
fixtures are explicitly dirtied so test metadata is not left in the saved registry. No original JAR/file-format rewrite.

This is metadata persistence evidence. It does not prove a live paid order, queue insertion by an order service, real
builders/material consumption, or completed NPC construction. Those integration gates remain unimplemented.
