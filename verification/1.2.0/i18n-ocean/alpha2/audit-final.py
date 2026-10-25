from pathlib import Path
import json, hashlib, zipfile, re, subprocess
p = Path('/workspace/millenairekingdoms')
out = Path('/workspace/royal-decree/i18n-ocean')
def digest(data): return hashlib.sha256(data).hexdigest()
def entries(version):
    path = p / ('releases/' + version + '/millenairekingdoms-' + version + '.jar')
    if not path.exists(): path = p / ('build/libs/millenairekingdoms-' + version + '.jar')
    with zipfile.ZipFile(path) as z:
        return path, {n: z.read(n) for n in z.namelist() if not n.endswith('/')}
final, files = entries('1.2.0-rc8-alpha.2')
dev12, previous = entries('1.2.0-rc8-alpha.dev12')
metadata = {'META-INF/MANIFEST.MF', 'META-INF/neoforge.mods.toml'}
assert files.keys() == previous.keys()
changed = sorted(n for n in files if files[n] != previous[n])
assert set(changed) == metadata, changed
assert b'version="1.2.0-rc8-alpha.2"' in files['META-INF/neoforge.mods.toml']
for n in files:
    assert not n.startswith(('org/millenaire/', 'com/millenairekingdoms/test/', 'data/minecraft/worldgen/', 'data/millenairekingdoms_geography_tests/')), n
    assert not any(term in n for term in ['geography_tests', 'gametest', 'en-tr-goldens', 'model.bin']), n
    assert not n.endswith('.jar'), n
dev11, older = entries('1.2.0-rc8-alpha.dev11.1')
assert files.keys() == older.keys()
production_changes = sorted(n for n in files if n not in metadata and files[n] != older[n])
assert {
    'com/millenairekingdoms/atlas/geography/GeographyService.class',
    'com/millenairekingdoms/atlas/geography/GeographyService$Session.class',
    'com/millenairekingdoms/atlas/geography/GeographyStore.class',
}.issubset(production_changes), production_changes
assert all(n == 'com/millenairekingdoms/atlas/geography/GeographyStore.class'
           or re.fullmatch(r'com/millenairekingdoms/atlas/geography/GeographyService(?:\$[^/]+)?\.class', n)
           for n in production_changes), production_changes
locales = json.loads(files['assets/millenairekingdoms/locale-manifest.json'])['locales']
assert sorted(re.fullmatch(r'assets/millenairekingdoms/lang/([^/]+)\.json', n)[1]
              for n in files if re.fullmatch(r'assets/millenairekingdoms/lang/([^/]+)\.json', n)) == locales
baseline, original = entries('1.2.0-rc8-alpha')
old_classes = {n for n in original if n.endswith('.class')}
same = sorted(n for n in old_classes if files.get(n) == original[n])
different = sorted(n for n in old_classes if n in files and files[n] != original[n])
removed = sorted(old_classes - files.keys())
row = dict(version='1.2.0-rc8-alpha.2', jar=str(final.relative_to(p)), bytes=final.stat().st_size,
    sha256=digest(final.read_bytes()), source_parent=subprocess.check_output(['git','rev-parse','HEAD'],cwd=p,text=True).strip(),
    dev12_metadata_only_changes=changed, dev11_1_nonmetadata_changes=production_changes,
    exact_dev12_content_entries=len(files)-len(metadata), locales=locales,
    forbidden_test_native_worldgen_model_entries=0,
    first_alpha_identical_classes=same, first_alpha_changed_classes=different, first_alpha_removed_classes=removed,
    entries_sha256={n:digest(data) for n,data in sorted(files.items())})
(out / 'alpha2-artifact-audit.json').write_text(json.dumps(row,indent=2)+'\n')
print(json.dumps({k:v for k,v in row.items() if k not in ['entries_sha256','first_alpha_identical_classes','first_alpha_changed_classes']}))
