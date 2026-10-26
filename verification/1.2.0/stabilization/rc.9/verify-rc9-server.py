"""Exact production artifact/new and copied genuine alpha.3 worlds. No test mod/client/authentication claim."""
from pathlib import Path
import subprocess,time,re,json,hashlib,shutil
p=Path('/workspace/millenairekingdoms');out=Path('/workspace/royal-decree/stabilization');version='1.2.0-rc.9';jar=p/('build/libs/millenairekingdoms-'+version+'.jar');prior=p/'run/royal-rc8-alpha3-ocean-disabled-01';source=json.loads((out/'rc9-source.json').read_text());rows=[]
def sha(file):return hashlib.sha256(file.read_bytes()).hexdigest()
def properties(file):return sorted(line.strip()for line in file.read_text().splitlines()if line.strip()and not line.lstrip().startswith(('#','!')))
assert sha(jar)==source['jar_sha256'];legacy_source_hash=sha(prior/'world/level.dat')
for kind in ['new','legacy-alpha3-copy']:
 world=p/('run/stabilization-rc9-production-'+kind+'-01');assert not world.exists(),'Preserve existing attempt, choose a fresh run';world.mkdir()
 for name in ['eula.txt','server.properties']:shutil.copyfile(prior/name,world/name)
 if kind.startswith('legacy'):shutil.copytree(prior/'world',world/'world')
 before_properties=properties(world/'server.properties');before_eula=(world/'eula.txt').read_bytes();retired={str(f.relative_to(world)):sha(f)for f in (world/'world').rglob('*')if f.is_file()and any(part in ['geography','geography-cache']for part in f.parts)}if (world/'world').exists()else {}
 previous=None
 for phase in ['write','reopen']:
  log=out/('rc9-production-'+kind+'-'+phase+'.log');level=world/'world/level.dat';before=sha(level)if level.exists()else None
  assert before is None if kind=='new'and phase=='write'else before is not None
  if previous is not None:assert before==previous
  print('NORMAL_START',kind,phase,flush=True)
  with log.open('w')as output:
   process=subprocess.Popen(['python','/workspace/cloud-setup/millenaire-gradle.py','--offline','--init-script','/workspace/royal-decree/rc-smoke.gradle','-Dkingdoms.smokeDirectory='+str(world),'runServerJar'],cwd=p,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True)
   deadline=time.monotonic()+600;ready=False
   while process.poll()is None and time.monotonic()<deadline:
    if re.search(r'Done \([^)]+\)!',log.read_text(errors='replace')):
     ready=True;process.stdin.write('kingdoms list\nsave-all flush\nstop\n');process.stdin.flush();break
    time.sleep(1)
   if not ready:process.terminate();process.wait(timeout=30);raise SystemExit('Production startup failed: '+str(log))
   try:code=process.wait(timeout=180)
   except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=30);raise SystemExit('Production shutdown failed: '+str(log))
  text=log.read_text(errors='replace');staged=world/'mods/millenairekingdoms.jar';after=sha(level);row={'kind':kind,'phase':phase,'exit_code':code,'ready':ready,'saved':'Saved the game'in text,'stopped':'Stopping server'in text,'build_successful':'BUILD SUCCESSFUL'in text,'final_initialized':('Millénaire Kingdoms '+version+' initialized')in text,'dependency_verified':'dependency verified: millenaire 9.0.3'in text,'test_mod_loaded':'millenairekingdoms_tests'in text,'jar_sha256':sha(jar),'staged_jar_sha256':sha(staged),'log_sha256':sha(log),'level_before_sha256':before,'level_after_sha256':after,'world':str(world),'properties_semantically_unchanged':properties(world/'server.properties')==before_properties,'eula_unchanged':(world/'eula.txt').read_bytes()==before_eula,'legacy_original_unchanged':sha(prior/'world/level.dat')==legacy_source_hash,'retired_cache_files':len(retired),'retired_cache_bytes_unchanged':all((world/f).exists()and sha(world/f)==digest for f,digest in retired.items()),'authentication':'Unverified: existing127.0.0.1:25579 offline-mode production fixture; no graphical or authenticated client.'}
  assert code==0 and all(row[k]for k in ['ready','saved','stopped','build_successful','final_initialized','dependency_verified','properties_semantically_unchanged','eula_unchanged','legacy_original_unchanged','retired_cache_bytes_unchanged'])and not row['test_mod_loaded']and row['jar_sha256']==row['staged_jar_sha256']==source['jar_sha256'],row
  rows.append(row);previous=after;(out/'rc9-production-results.json').write_text(json.dumps({'source':source,'legacy_source_world':str(prior),'legacy_source_level_sha256':legacy_source_hash,'results':rows},indent=2)+'\n');print('NORMAL_PASSED',kind,phase,'exactJar=true','exit=0','testMod=false',flush=True)
