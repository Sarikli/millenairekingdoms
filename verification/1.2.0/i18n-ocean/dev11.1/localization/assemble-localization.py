"""Assemble offline drafts + explicitly AI-authored editorial overrides. No model/API or world access."""
from pathlib import Path
import ast
import hashlib
import importlib.util
import json
import re
import zipfile

PROJECT = Path('/workspace/millenairekingdoms')
SCRATCH = Path('/workspace/royal-decree/i18n-ocean')
LANG = PROJECT / 'src/main/resources/assets/millenairekingdoms/lang'
english = json.loads((LANG / 'en_us.json').read_text())
manifest = json.loads((LANG.parent / 'locale-manifest.json').read_text())
overrides = json.loads((SCRATCH / 'narrative-editorial-overrides.json').read_text())
with zipfile.ZipFile(PROJECT / 'releases/1.2.0-rc8-alpha.dev8/millenairekingdoms-1.2.0-rc8-alpha.dev8.jar') as baseline:
    protected = {locale: json.loads(baseline.read('assets/millenairekingdoms/lang/'+locale+'.json')) for locale in ['en_us', 'tr_tr']}
spec = importlib.util.spec_from_file_location('audit', PROJECT / 'scripts/check-localization.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)

# Reproduce only the local translator's deterministic input policy; importing its model/runtime is unnecessary.
environment = {'re': re, 'hashlib': hashlib}
tree = ast.parse((SCRATCH / 'translate-local.py').read_text())
for node in tree.body:
    if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id in {'slot','contexts','POLICY'} for target in node.targets):
        exec(compile(ast.Module(body=[node],type_ignores=[]),'<translation policy>','exec'), environment)
    elif isinstance(node, ast.FunctionDef) and node.name in {'indexed','translation_input','source_hash'}:
        exec(compile(ast.Module(body=[node],type_ignores=[]),'<translation policy>','exec'), environment)

report = {'keys_per_locale':len(english),'locales':{},'native_speaker_review':False,
          'quality':'New translations are offline machine-assisted/AI-authored drafts. Coverage/format/source checks do not certify linguistic quality.',
          'legacy_en_tr_values_protected_per_locale':459,'editorial_narrative_bodies':24*20,
          'external_project_text_translation_egress':False}
prepared = {}
for locale in manifest['locales']:
    if locale == 'en_us':
        values = dict(english)
        editorial = {}
    else:
        values = json.loads((SCRATCH / (locale+'-local-draft.json')).read_text())
        hashes = json.loads((SCRATCH / (locale+'-local-source-hashes.json')).read_text())
        editorial = dict(overrides['locales'].get(locale, {}))
        editorial.update(overrides['interface_overrides'].get(locale, {}))
        values.update(editorial)
        neutral={key:source for key,source in english.items() if source in {"x","y","z"}}
        values.update(neutral)
        assert values.keys() == english.keys(), (locale, 'key set differs', set(english)-set(values), set(values)-set(english))
        for key, source in english.items():
            if key in protected.get(locale, {}):
                assert values[key] == protected[locale][key], (locale, key, 'legacy value changed')
            elif key not in editorial:
                assert hashes.get(key) == environment['source_hash'](key,source), (locale,key,'stale/missing local translation input hash')
            # Preserve component-joining boundaries, without rewriting a single protected legacy value.
            if key not in protected.get(locale, {}):
                leading=re.match(r'^\s*',source)[0];trailing=re.search(r'\s*$',source)[0]
                values[key]=leading+values[key].strip()+trailing
    assert all(values.get(key)==value for key,value in protected.get(locale,{}).items()), (locale,'legacy EN/TR drift')
    for key, source in english.items():
        value=values[key]
        assert value.strip() and audit.slots(source)==audit.slots(value), (locale,key,'format/argument mismatch')
        assert not re.search(r'Z\s*X\s*\d[\d\s]*Z\s*X', value, re.I), (locale,key,'unrestored token')
    raw=(json.dumps({key:values[key] for key in english},ensure_ascii=False,indent=2)+'\n').encode()
    prepared[locale]=raw
    flags=set()
    for directory in [SCRATCH,SCRATCH/'stats-before-final-delta']:
        file=directory/(locale+'-local-stats-v3.json')
        if file.exists():flags.update(json.loads(file.read_text()).get('protected_segment_keys',[]))
    previous=SCRATCH/'local-translation-stats-v2.json'
    if previous.exists():flags.update(json.loads(previous.read_text()).get(locale,{}).get('protected_segment_keys',[]))
    # Historical field name includes both whole-message retries and protected-fragment repair, not only fragmentation.
    report['locales'][locale]={'keys':len(values),'resource_sha256':hashlib.sha256(raw).hexdigest(),
        'ai_editorial_keys':sorted(editorial),'legacy_protected_keys':len(protected.get(locale,{})),
        'protected_argument_repair_review_keys':sorted(flags-set(editorial)),
        'language_neutral_axis_keys':[key for key,source in english.items() if source in {'x','y','z'}],
        'linguistic_approval':False if locale!='en_us' else 'Existing source; legacy narrative equality is independently tested'}

assert len(prepared)==26
# No partial publication if any locale fails source/format/legacy validation.
for locale, raw in prepared.items():
    temporary=LANG/(locale+'.json.tmp');temporary.write_bytes(raw);temporary.replace(LANG/(locale+'.json'))
(SCRATCH/'assembled-localization-provenance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'locales':len(prepared),'keys_per_locale':len(english),'values':len(prepared)*len(english),
                  'legacy_en_tr_values_per_locale':459,'ai_editorial_bodies':480,'native_speaker_approval':False}))
