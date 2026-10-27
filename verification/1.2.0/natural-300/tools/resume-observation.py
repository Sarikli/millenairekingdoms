"""Durable long-running observation; consumes the existing saved world without editing clocks."""
from pathlib import Path
import subprocess,time,json,hashlib,shutil,os,tomllib
p=Path('/workspace/millenairekingdoms');out=Path('/workspace/royal-decree/natural-300');capture=out/'attempt-05';capture.mkdir(exist_ok=True);world=p/'run/natural-300-world-03';obs=world/'world/natural-observation'
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
product='b7ae9fc2f208588962bac8739021b4e4169322443d475b65c672791a53a3e8dd';native='5882528027ac67e8759db3926ebef36abe2300029526582f80c7a988b583ec62'
assert sha(world/'mods/millenairekingdoms.jar')==product and sha(p/'millenaire-9.0.3.jar')==native
assert(obs/'baseline.json').exists()and not(obs/'final.json').exists()
assert(out/'checkpoints/world-03-before-resume.json').exists()
for name,record in json.loads((out/'attempt-03/gameplay-configs.json').read_text()).items():assert tomllib.loads((world/'config'/name).read_text())==record['values'],name
if(obs/'paused.json').exists():
 prior=json.loads((obs/'paused.json').read_text());(obs/'paused.json').rename(obs/('paused-'+str(prior['gameTime'])+'.json'))
shutil.copyfile(out/'kingdoms-natural-observer.jar',world/'mods/kingdoms-natural-observer.jar')
provenance={'runner_pid':os.getpid(),'mode':'same-world resume from recorded saved clock; no clock mutation','world':str(world),'production_jar_sha256':product,'native_jar_sha256':native,'observer_sha256':sha(world/'mods/kingdoms-natural-observer.jar'),'stages':[]}
(capture/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
for phase in ['resume','reopen']:
 log=capture/(phase+'.log');print('OBSERVATION_START',phase,flush=True)
 command=['python','/workspace/cloud-setup/millenaire-gradle.py','--offline','--init-script',str(out/'server.gradle'),'-Dkingdoms.observation.mode='+phase,'runServerJar']
 with log.open('w')as f:proc=subprocess.Popen(command,cwd=p,stdout=f,stderr=subprocess.STDOUT,stdin=subprocess.DEVNULL,start_new_session=True,close_fds=True)
 (capture/'process.json').write_text(json.dumps({'runner_pid':os.getpid(),'runner_phase':phase,'pid':proc.pid,'log':str(log),'world':str(world),'detached_session':True})+'\n')
 seen=0;deadline=time.monotonic()+(43200 if phase=='resume'else 600)
 while proc.poll()is None and time.monotonic()<deadline:
  lines=log.read_text(errors='replace').splitlines()
  for line in lines[seen:]:
   if 'NATURAL_300_'in line:print(line,flush=True)
  seen=len(lines)
  (capture/'heartbeat.json').write_text(json.dumps({'runner_pid':os.getpid(),'child_pid':proc.pid,'phase':phase,'unix_time':time.time(),'latest_recorded_day':json.loads((obs/'latest.json').read_text())['relativeDay']})+'\n');time.sleep(5)
 if proc.poll()is None:raise SystemExit('Timeout; world, running child and evidence preserved for explicit diagnosis')
 code=proc.returncode;text=log.read_text(errors='replace')
 if code==0 and(obs/'paused.json').exists()and not(obs/'final.json').exists():print('OBSERVATION_PAUSED_WITH_SAVED_WORLD',flush=True);raise SystemExit(0)
 assert code==0 and 'BUILD SUCCESSFUL'in text and 'Millénaire Kingdoms 1.2.0 initialized'in text and 'millenairekingdoms_tests'not in text,(phase,code)
 assert not(obs/'failure.json').exists(),(obs/'failure.json').read_text()if(obs/'failure.json').exists()else''
 assert sha(world/'mods/millenairekingdoms.jar')==product
 if phase=='resume':
  result=json.loads((obs/'final.json').read_text());baseline=json.loads((obs/'baseline.json').read_text());assert result['observationComplete']and result['consecutiveTicks']==7200000
  assert result['gameTime']-baseline['baseTick']==result['dayTime']-baseline['baseDayTime']==7200000
  assert result['observerExplorationTickets']==0
 else:result=json.loads((obs/'reopen.json').read_text());assert result['sameWorldSeparateJvm']and result['kingdomMembershipAndCapitalsPreserved']
 provenance['stages'].append({'phase':phase,'exit_code':code,'build_successful':True,'log_sha256':sha(log),'result':result});(capture/'results.json').write_text(json.dumps(provenance,indent=2)+'\n');print('OBSERVATION_STAGE_COMPLETE',phase,flush=True)
print('OBSERVATION_AND_REOPEN_COMPLETE',flush=True)
