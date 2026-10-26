from pathlib import Path
import hashlib,json,zipfile,re,subprocess
p=Path('/workspace/millenairekingdoms');out=Path('/workspace/royal-decree/stabilization');source=json.loads((out/'rc9-source.json').read_text());jar=p/'build/libs/millenairekingdoms-1.2.0-rc.9.jar';sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest();rows=[]
for manifest in sorted((p/'releases').rglob('SHA256SUMS')):
 for line in manifest.read_text().splitlines():
  if not line.strip():continue
  digest,name=line.split(maxsplit=1);file=manifest.parent/name.strip().removeprefix('*');assert re.fullmatch('[a-f0-9]{64}',digest)and file.is_file()and sha(file)==digest,(str(manifest),name);rows.append({'file':str(file.relative_to(p)),'sha256':digest})
original=p/'millenaire-9.0.3.jar';assert sha(original)=='5882528027ac67e8759db3926ebef36abe2300029526582f80c7a988b583ec62';assert sha(jar)==source['jar_sha256']
with zipfile.ZipFile(jar)as z:
 names=z.namelist();assert not any(n.startswith('org/millenaire/')or n.endswith('GameTests.class')or '/atlas/geography/'in n or n.endswith('AtlasGeometry.class')or n.endswith('millenaire-9.0.3.jar')for n in names);locales=[n for n in names if re.fullmatch(r'assets/millenairekingdoms/lang/[a-z]{2}_[a-z]{2}.json',n)];assert len(locales)==26;metadata=z.read('META-INF/neoforge.mods.toml').decode();assert '1.2.0-rc.9'in metadata
 previous=p/'releases/1.2.0-rc8-alpha.4.dev5/millenairekingdoms-1.2.0-rc8-alpha.4.dev5.jar';primitive_classes=['RoyalConsolidationService','RoyalTreasuryImage','RoyalTreasuryPersistence','RoyalContainerReceipts','RoyalCoinPlan','RoyalPaymentService','RoyalEconomicReservations','RoyalTreasurySnapshot']
 with zipfile.ZipFile(previous)as old:
  same={c:z.read('com/millenairekingdoms/economy/'+c+'.class')==old.read('com/millenairekingdoms/economy/'+c+'.class')for c in primitive_classes};assert all(same.values())
 result={'version':'1.2.0-rc.9','source':source,'prior_artifacts_verified':len(rows),'prior_artifacts':rows,'original_millenaire_903_sha256':sha(original),'packaged_locales':len(locales),'no_bundled_native_classes_jar_tests_or_geography':True,'unchanged_financial_primitive_class_bytes_from_actual_dev5_process_cuts':same,'main_tracking_ref':subprocess.check_output(['git','rev-parse','refs/remotes/origin/main'],cwd=p,text=True).strip()};assert result['main_tracking_ref']=='52a6bc3a4ca4b717775dbd319bbe1df6f733ee0b';(out/'rc9-artifact-audit.json').write_text(json.dumps(result,indent=2)+'\n');print({k:v for k,v in result.items()if k not in ['source','prior_artifacts']})
