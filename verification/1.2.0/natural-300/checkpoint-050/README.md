# Natural-world observation: day50 checkpoint, incomplete

51 daily snapshots cover0–50 actual Minecraft days (1,200,000 ticks), from the unchanged baseline22200 to1222200. Original JSONL bytes are retained verbatim through that clock. The current JVM's stdout prefix ends at its actual day50 marker. This is a partial checkpoint; the same live world continues toward300 days without a checkpoint stop.

Four diplomatic incorporations, one war and one strategic battle are recorded. At world day49 / relative day48.075, Sainte-Marguerite les-mines declared war on Saga at relation−93. The ensuing unattended conflict used native strengths60/96, dice17/9 and modifier−4. It produced an actual attacker victory, not a completed conquest: the retained political save contains the exact WON receipt, transferred=false and campaign wins1/2. Numazu remains in Saga. No royal order or shipment domain record is present at this checkpoint.

The native saved-file sample was captured read-only after the daily flush. Its original receipt explicitly disclaims atomicity across separate saved-data files. The decoded military receipt is corroborating readback; the original NBT files remain in the archive.

Analysis files are generated only from the filtered day50 dataset. Pre-round samples begin at world day6, before the daily drift/border step; they describe eligibility at that moment, not actual random draws. Strategy samples describe conditional metadata intentions, not paid/finished work. Native building counts include walls and sub-buildings; resident counts include persistent records rather than simultaneously loaded living NPCs.

There is no300-day completion marker or final separate-JVM reopen claim here. The released product JAR remains SHA-256 b7ae9fc2f208588962bac8739021b4e4169322443d475b65c672791a53a3e8dd.
