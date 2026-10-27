"""Exact1.2.1 production JAR: fresh world and COPY of the genuine1.2.0 natural300-day save; no observer/test mod."""
from pathlib import Path
import hashlib,json,subprocess,time,re,shutil,sys,signal,os
sys.path.insert(0,'/workspace/royal-decree/stabilization')
from read_nbt import read
p=Path('/workspace/millenairekingdoms');out=Path('/workspace/royal-decree/1.2.1');prior=p/'run/natural-300-world-03';jar=p/'build/libs/millenairekingdoms-1.2.1.jar';rows=[]
def sha(f):return hashlib.sha256(f.read_bytes()).hexdigest()
def manifest(root):return {str(f.relative_to(root)):sha(f)for f in root.rglob('*')if f.is_file()}
def props(file):return sorted(x.strip()for x in file.read_text().splitlines()if x.strip()and not x.startswith(('#','!')))
expected='28043afee58e95a3d653ac20dc0110b6a47dae67624755ee0c7f6c8f0253c0f5'
assert sha(jar)==expected
original=manifest(prior/'world');political=read(prior/'world/data/millenairekingdoms_kingdoms.dat')['data'];strategic={k:v for k,v in original.items()if '/strategic-military/'in k and k.endswith('.dat')}
(out/'production-original-world-manifest.json').write_text(json.dumps(original,indent=2)+'\n')
for kind in ['new','released120-natural300-copy']:
 directory=p/('run/patch121-production-'+kind+'-01');assert not directory.exists();directory.mkdir()
 for name in ['eula.txt','server.properties']:shutil.copyfile(prior/name,directory/name)
 if(prior/'config').exists():shutil.copytree(prior/'config',directory/'config')
 if kind!='new':shutil.copytree(prior/'world',directory/'world')
 before_props=props(directory/'server.properties');before_eula=sha(directory/'eula.txt')
 for phase in ['write','reopen']:
  log=out/('production-'+kind+'-'+phase+'.log');print('START',kind,phase,flush=True)
  with log.open('w')as stream:
   process=subprocess.Popen(['python','/workspace/cloud-setup/millenaire-gradle.py','--offline','--init-script','/workspace/royal-decree/rc-smoke.gradle','-Dkingdoms.smokeDirectory='+str(directory),'runServerJar'],cwd=p,stdin=subprocess.PIPE,stdout=stream,stderr=subprocess.STDOUT,text=True,start_new_session=True)
   deadline=time.monotonic()+600;ready=False
   while process.poll()is None and time.monotonic()<deadline:
    if re.search(r'Done \([^)]+\)!',log.read_text(errors='replace')):
     ready=True;process.stdin.write('kingdoms list\nsave-all flush\nstop\n');process.stdin.flush();break
    time.sleep(0.5)
   if not ready:os.killpg(process.pid,signal.SIGTERM);process.wait(timeout=30);raise SystemExit('Production startup failed; retained '+str(log))
   try:code=process.wait(timeout=180)
   except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGTERM);process.wait(timeout=30);raise SystemExit('Production shutdown failed; retained '+str(log))
  text=log.read_text(errors='replace');copy_preserved=True;ops_preserved=True
  if kind!='new':
   copy_preserved=read(directory/'world/data/millenairekingdoms_kingdoms.dat')['data']==political
   ops_preserved=all(sha(directory/'world'/name)==value for name,value in strategic.items())
  row=dict(kind=kind,phase=phase,exit_code=code,ready=ready,saved='Saved the game'in text,stopped='Stopping server'in text,build_successful='BUILD SUCCESSFUL'in text,version_initialized='Millénaire Kingdoms 1.2.1 initialized'in text,native_verified='dependency verified: millenaire 9.0.3'in text,test_or_observer_loaded=any(x in text for x in ['millenairekingdoms_tests','kingdoms-natural-observer','natural_observation']),jar_sha256=expected,staged_sha256=sha(directory/'mods/millenairekingdoms.jar'),log_sha256=sha(log),properties_unchanged=props(directory/'server.properties')==before_props,eula_unchanged=sha(directory/'eula.txt')==before_eula,entire_legacy_political_object_preserved=copy_preserved,legacy_strategic_files_checked=len(strategic)if kind!='new'else 0,legacy_strategic_bytes_preserved=ops_preserved,world=str(directory),authentication='Unverified; inherited local offline fixture; no graphical/client login claim')
  row['passed']=code==0 and all(row[k]for k in ['ready','saved','stopped','build_successful','version_initialized','native_verified','properties_unchanged','eula_unchanged','entire_legacy_political_object_preserved','legacy_strategic_bytes_preserved'])and not row['test_or_observer_loaded']and row['staged_sha256']==expected
  rows.append(row);(out/'production-results.json').write_text(json.dumps(rows,indent=2)+'\n');print(json.dumps(row),flush=True)
  if not row['passed']:raise SystemExit('Production lifecycle gate failed; retained exact evidence')
assert manifest(prior/'world')==original,'Original world changed'
(out/'production-original-preserved.json').write_text(json.dumps({'files':len(original),'source':str(prior/'world'),'entire_file_manifest_unchanged':True,'strategic_files':len(strategic)},indent=2)+'\n')
