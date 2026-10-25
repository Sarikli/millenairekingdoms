from pathlib import Path
import sys,json,hashlib,gzip,shutil,subprocess
p=Path('/workspace/millenairekingdoms');scratch=Path('/workspace/royal-decree');version,resultsfile=sys.argv[1:3];stage='alpha3' if version=='1.2.0-rc8-alpha.3' else version.removeprefix('1.2.0-rc8-alpha.3.');out=p/'verification/1.2.0/ocean-loading'/stage;out.mkdir(parents=True,exist_ok=True);release=p/'releases'/version;release.mkdir(exist_ok=True);jar=p/'build/libs'/('millenairekingdoms-'+version+'.jar')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
cases=json.loads((scratch/resultsfile).read_text())
for case in cases:
 assert len(case['stages'])==2
 for row in case['stages']:
  assert row['exit']==0 and row['build'] and len(row['passes'])==1 and row['jar_sha256']==sha(jar)
  log=Path(row['log']);assert sha(log)==row['sha256'];target=out/(log.name+'.gz');target.write_bytes(gzip.compress(log.read_bytes(),mtime=0));row.update(archive_log=target.relative_to(p).as_posix(),archive_sha256=sha(target))
target=release/jar.name
if target.exists():assert sha(target)==sha(jar)
else:shutil.copyfile(jar,target)
meta=dict(version=version,bytes=jar.stat().st_size,sha256=sha(jar),source_parent=subprocess.check_output(['git','rev-parse','HEAD'],cwd=p,text=True).strip(),baseline_alpha2_sha256='4101f637a2a3a65f2e8b73c2f2606b30a4e8293a4f3bd72d725a564f1a9c6b94',ocean_scanning='disabled')
(release/'SHA256SUMS').write_text(sha(target)+'  '+target.name+'\n');(release/'artifact.json').write_text(json.dumps(meta,indent=2)+'\n');(out/'results.json').write_text(json.dumps(dict(artifact=meta,pairs=cases),indent=2)+'\n')
(release/'README.md').write_text('# '+version+' — Ocean surveying disabled\n\nOcean scanning, geography requests/cache access and the client ocean layer are disabled. The political atlas and26-language/chronicle features remain. Old geographic files and all prior artifacts are preserved. Same Draft PR3, no stable release or main merge. [Full report](../../docs/1.2.0_OCEAN_DISABLED.md).\n')
table='| Package | Write | Separate-JVM same-world reopen |\n|---|---|---|\n'
for case in cases:table+='| '+case['suite']+' | '+' | '.join('`All '+row['passes'][0]+' required tests passed`' for row in case['stages'])+' |\n'
(out/'README.md').write_text('# '+version+' disabled-behavior evidence\n\nEach package uses a distinct fresh world and successful write before reopening the SAME world in a separate JVM, exit0/BUILD SUCCESSFUL and the same JAR SHA. Active-survey tests were superseded by the explicit user cancellation; their old artifacts/evidence remain preserved.\n\n'+table+'\nSHA256 `'+sha(target)+'`. Actual graphics/input/scaling/authentication remain manual.\n')
files=[*p.glob('src/**/*'),p/'gradle.properties',p/'build.gradle'];(out/'source-sha256.json').write_text(json.dumps({f.relative_to(p).as_posix():sha(f) for f in files if f.is_file()},indent=2)+'\n');print(json.dumps(meta));print(table)
