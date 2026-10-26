from pathlib import Path
import json,sys,hashlib,gzip,shutil,subprocess
p=Path('/workspace/millenairekingdoms');s=Path('/workspace/royal-decree');version,resultsfile=sys.argv[1:3];stage=version.removeprefix('1.2.0-rc8-alpha.');out=p/'verification/1.2.0/i18n-ocean'/stage;out.mkdir(parents=True,exist_ok=True);release=p/'releases'/version;release.mkdir(exist_ok=True);jar=p/'build/libs'/('millenairekingdoms-'+version+'.jar')
def sha(f):return hashlib.sha256(f.read_bytes()).hexdigest()
cases=json.loads((s/resultsfile).read_text())
for c in cases:
 assert len(c['stages'])==2
 for row in c['stages']:
  assert row['exit']==0 and row['build'] and len(row['passes'])==1 and row['jar_sha256']==sha(jar);f=Path(row['log']);assert sha(f)==row['sha256'];target=out/(f.name+'.gz');target.write_bytes(gzip.compress(f.read_bytes(),mtime=0));row.update(archive_log=target.relative_to(p).as_posix(),archive_sha256=sha(target))
file=release/jar.name
if file.exists():assert sha(file)==sha(jar)
else:shutil.copyfile(jar,file)
meta={'version':version,'bytes':jar.stat().st_size,'sha256':sha(jar),'baseline_commit':'1fb9e5f04e19e0c9ed7c21bee171800f55e5d747','source_parent':subprocess.check_output(['git','rev-parse','HEAD'],cwd=p,text=True).strip()};(release/'SHA256SUMS').write_text(sha(file)+'  '+file.name+'\n');(release/'artifact.json').write_text(json.dumps(meta,indent=2)+'\n');(out/'results.json').write_text(json.dumps({'artifact':meta,'pairs':cases},indent=2)+'\n');(release/'README.md').write_text('# '+version+' experimental checkpoint\n\nInternationalization/ocean atlas development continues on the same Draft PR3. Original alpha/RC.8 and all validated artifacts remain preserved. See [current progress](../../docs/1.2.0_I18N_OCEAN_PROGRESS.md). New-language linguistic quality is not native-speaker approval. Actual graphics/RTL/input/scaling/authentication remain manual/unverified.\n')
table='| Package | Write | Separate-JVM same-world reopen |\n|---|---|---|\n'
for c in cases:table+='| '+c['suite']+' | '+' | '.join('`All '+r['passes'][0]+' required tests passed`' for r in c['stages'])+' |\n'
(out/'README.md').write_text('# '+version+' exact server evidence\n\nEach distinct fresh world gates exit0/BUILD SUCCESSFUL and its exact All-line before a separate JVM reopens the SAME world. Same JAR SHA throughout each pair. These are server/model checks, not actual graphical/RTL/font/mouse/scaling/authenticated-client proof.\n\n'+table+'\nSHA256 `'+sha(file)+'`.\n');files=[*p.glob('src/main/**/*.java'),*p.glob('src/main/resources/**/*'),*p.glob('src/gametest/**/*.java'),p/'gradle.properties',p/'build.gradle'];(out/'source-sha256.json').write_text(json.dumps({f.relative_to(p).as_posix():sha(f) for f in files if f.is_file()},indent=2)+'\n');print(json.dumps(meta));print(table)
