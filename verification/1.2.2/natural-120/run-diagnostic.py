#!/usr/bin/env python3
"""Observe a disposable copy of a completed natural world, then reopen it in a separate JVM."""
import argparse, atexit, hashlib, json, re, shutil, subprocess, time, tomllib, zipfile
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--world',type=Path,required=True)
p.add_argument('--label',required=True)
p.add_argument('--ticks',type=int,default=240000)
p.add_argument('--jar',type=Path,required=True)
p.add_argument('runner',nargs=argparse.REMAINDER)
a=p.parse_args();root=Path(__file__).resolve().parents[3];world=a.world.resolve()
assert re.fullmatch(r'[A-Za-z0-9-]+',a.label) and 1000<=a.ticks<=2880000
assert 'diagnostic-copy' in world.name and (world/'world/natural-observation/final.json').is_file(),'Use an independent copy, never the original completed observation'
runner=a.runner[1:]if a.runner[:1]==['--']else a.runner
assert runner,'Explicit existing Gradle runner required'
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
init=Path(__file__).with_name('observer.gradle');out=root/'build/verification'/('economic-diagnostic-'+a.label)
assert not out.exists();out.mkdir(parents=True)
result={'label':a.label,'world':str(world),'ticks':a.ticks,'original_observation':'completed dev2 natural world; disposable copy continued, not fresh generation','source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'production_sources':{str(x.relative_to(root)):sha(x)for x in (root/'src/main').rglob('*')if x.is_file()},'stages':[],'status':'BUILDING_OBSERVER'}
def save(): (out/'results.json').write_text(json.dumps(result,indent=2)+'\n')
def mark_incomplete():
 if result['status']!='COMPLETE_AND_REOPEN':result['status']='INCOMPLETE';save()
atexit.register(mark_incomplete)
save()
with (out/'build.log').open('w')as log:code=subprocess.call([*runner,'--init-script',str(init),'economicObserverJar'],cwd=root,stdout=log,stderr=subprocess.STDOUT)
assert code==0,'Observer compilation failed'
observer=root/'build/economic-observer/kingdoms-economy-observer.jar';jar=a.jar.resolve()
with zipfile.ZipFile(jar)as source:version=tomllib.loads(source.read('META-INF/neoforge.mods.toml').decode())['mods'][0]['version']
with zipfile.ZipFile(observer)as source:pin=tomllib.loads(source.read('META-INF/neoforge.mods.toml').decode())['dependencies']['kingdoms_natural_observer'][0]['versionRange']
assert pin=='['+version+']','Observer must match the exact candidate version before launch'
shutil.copyfile(jar,world/'mods/millenairekingdoms.jar');shutil.copyfile(observer,world/'mods/kingdoms-economy-observer.jar')
shutil.copyfile(jar,out/jar.name);shutil.copyfile(observer,out/observer.name)
result.update(jar_sha256=sha(jar),observer_sha256=sha(observer));save()
observations=world/'world'/('economy-diagnostics-'+a.label);assert not observations.exists()
for phase in ['diagnose','diagnose-reopen']:
 result['status']=phase.upper();save();print('DIAGNOSTIC_START',phase,a.label,flush=True)
 log=out/(phase+'.log');cmd=[*runner,'--init-script',str(init),'-Dkingdoms.observation.world='+str(world),'-Dkingdoms.observation.mode='+phase,'-Dkingdoms.observation.label='+a.label,'-Dkingdoms.observation.ticks='+str(a.ticks),'runServerJar']
 with log.open('w')as stream:
  proc=subprocess.Popen(cmd,cwd=root,stdout=stream,stderr=subprocess.STDOUT,stdin=subprocess.PIPE,text=True,start_new_session=True);result['active_process']=proc.pid;save();deadline=time.monotonic()+21600;stopping=False;seen=0
  while proc.poll()is None:
   lines=log.read_text(errors='replace').splitlines()
   for line in lines[seen:]:
    if 'ECONOMY_120_DIAGNOSTIC'in line:print(line,flush=True)
   seen=len(lines)
   if not stopping and (time.monotonic()>deadline or shutil.disk_usage(root).free<268435456 or (observations/'stop.request').exists()):
    result['status']='STOPPING_INCOMPLETE';save();proc.stdin.write('stop\n');proc.stdin.flush();stopping=True
   time.sleep(2)
  code=proc.returncode
 content=log.read_text(errors='replace');result['stages'].append({'phase':phase,'exit_code':code,'log_sha256':sha(log)});save()
 assert code==0 and 'BUILD SUCCESSFUL'in content and not stopping,(phase,code)
 assert not(observations/'failure.json').exists(),'Read retained failure receipt'
 assert sha(world/'mods/millenairekingdoms.jar')==result['jar_sha256']
 if phase=='diagnose':
  actual=json.loads((observations/'final.json').read_text());assert actual['observedTicks']==a.ticks and actual['gameTime']-actual['baseTick']==a.ticks and actual['dayTime']-actual['baseDayTime']==a.ticks
  result['final_counts']={k:len(actual.get(k,[]))for k in ['construction','shipments','payments','economicDecisions']};save()
 else:assert json.loads((observations/'reopen.json').read_text())['economicRecordsPreserved']
result['status']='COMPLETE_AND_REOPEN';result['outputs_sha256']={x.name:sha(x)for x in observations.iterdir()if x.is_file()};save();print('DIAGNOSTIC_COMPLETE_AND_REOPEN',result['final_counts'],flush=True)
