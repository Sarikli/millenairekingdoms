#!/usr/bin/env python3
"""Fresh normal world, original natural generation,120 real zero-player days, then separate-JVM reopen."""
import argparse, datetime, hashlib, json, os, re, shutil, subprocess, time, uuid
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--seed',type=int,default=20261027300)
parser.add_argument('--eula-file',type=Path,required=True,help='Previously accepted local server EULA file to reuse in this test world')
parser.add_argument('runner',nargs=argparse.REMAINDER)
args=parser.parse_args()
root=Path(__file__).resolve().parents[3]
runner=args.runner[1:] if args.runner[:1]==['--'] else args.runner
runner=runner or ['bash',str(root/'gradlew'),'--no-daemon']
init=Path(__file__).with_name('observer.gradle')
run='economy-120-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]
world=root/'run'/run;capture=root/'build/verification'/run
assert not world.exists();world.mkdir();capture.mkdir(parents=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
assert re.search(r'^eula=true\s*$',args.eula_file.read_text(),re.M),'Existing accepted EULA is required'
shutil.copyfile(args.eula_file,world/'eula.txt')
provenance={'run':run,'seed':args.seed,'world':str(world),'capture':str(capture),'runner_pid':os.getpid(),'git_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'target_ticks':2880000,'status':'BUILDING_OBSERVER','stages':[],
 'conditions':'Fresh normal terrain. Original native spawn queue and natural-capital draw only. Test-only exploration tickets and passive embedded player are removed before measurement. No relations, Ethos, funds, items, villagers, buildings, orders or outcomes injected. Default political/economic rules; actual world config reported. Zero-player measurement uses only production economic leases/native services. Offline server fixture, not graphical/online-authentication proof.'}
result=capture/'results.json'
def save():result.write_text(json.dumps(provenance,indent=2)+'\n')
save();print('ECONOMY_120_CAPTURE',capture,flush=True)
with (capture/'build.log').open('w')as stream:
 code=subprocess.call([*runner,'--init-script',str(init),'build','economicObserverJar'],cwd=root,stdout=stream,stderr=subprocess.STDOUT)
assert code==0,'Observer build failed; see retained build.log'
version=re.search(r'^mod_version=(.+)$',(root/'gradle.properties').read_text(),re.M).group(1)
jar=root/'build/libs'/('millenairekingdoms-'+version+'.jar');native=root/'millenaire-9.0.3.jar';observer=root/'build/economic-observer/kingdoms-economy-observer.jar'
assert version.startswith('1.2.2-')
assert sha(native)=='5882528027ac67e8759db3926ebef36abe2300029526582f80c7a988b583ec62'
(world/'mods').mkdir();shutil.copyfile(jar,world/'mods/millenairekingdoms.jar');shutil.copyfile(observer,world/'mods/kingdoms-economy-observer.jar');shutil.copyfile(jar,capture/jar.name);shutil.copyfile(observer,capture/observer.name)
provenance.update(version=version,jar_sha256=sha(jar),native_sha256=sha(native),observer_sha256=sha(observer))
(world/'server.properties').write_text(f'''level-name=world
level-seed={args.seed}
level-type=minecraft:normal
generate-structures=true
difficulty=normal
gamemode=survival
online-mode=false
enforce-secure-profile=false
server-ip=127.0.0.1
server-port=25591
view-distance=10
simulation-distance=5
max-tick-time=-1
allow-flight=true
''')
for phase in ['write','reopen']:
 log=capture/(phase+'.log');command=[*runner,'--init-script',str(init),'-Dkingdoms.observation.world='+str(world),'-Dkingdoms.observation.mode='+phase,'runServerJar']
 provenance['status']=phase.upper();save();print('ECONOMY_120_START',phase,flush=True)
 with log.open('w')as stream:
  process=subprocess.Popen(command,cwd=root,stdin=subprocess.PIPE,text=True,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
  provenance['active_process']=process.pid;save();seen=0;deadline=time.monotonic()+(43200 if phase=='write'else 600);pause=False
  while process.poll()is None:
   lines=log.read_text(errors='replace').splitlines()
   for line in lines[seen:]:
    if 'ECONOMY_120_'in line:print(line,flush=True)
   seen=len(lines)
   if not pause and (time.monotonic()>deadline or shutil.disk_usage(root).free<268435456):
    pause=True;provenance['pause_reason']='Bounded wall-clock or disk-space limit';save()
    observations=world/'world/natural-observation'
    if phase=='write'and(observations/'baseline.json').exists():(observations/'pause.request').write_text(provenance['pause_reason'])
    else:process.stdin.write('stop\n');process.stdin.flush()
   time.sleep(2)
  code=process.returncode
 observations=world/'world/natural-observation';content=log.read_text(errors='replace')
 assert code==0 and 'BUILD SUCCESSFUL'in content and 'millenairekingdoms_tests'not in content,(phase,code)
 assert sha(world/'mods/millenairekingdoms.jar')==provenance['jar_sha256']
 assert not(observations/'failure.json').exists(),'Observer reports failure; original evidence preserved'
 if pause:
  provenance['status']='PAUSED_INCOMPLETE';save();raise SystemExit('Safe checkpoint retained;120-day completion is not claimed')
 actual=json.loads((observations/('final.json'if phase=='write'else'reopen.json')).read_text())
 if phase=='write':
  assert actual['observationComplete']and actual['consecutiveTicks']==2880000 and actual['gameTime']-actual['baseTick']==2880000 and actual['dayTime']-actual['baseDayTime']==2880000
  baseline=json.loads((observations/'baseline.json').read_text());assert len(baseline['kingdoms'])>=2 and baseline['players']==0 and actual['players']==0 and baseline['observerExplorationTickets']==0 and actual['observerExplorationTickets']==0
 else:assert actual['sameWorldSeparateJvm']and actual['kingdomMembershipAndCapitalsPreserved']and actual['economicRecordsPreserved']
 provenance['stages'].append({'phase':phase,'exit_code':code,'log_sha256':sha(log),'actual_result':actual});save()
provenance['status']='COMPLETE_120_AND_REOPEN';save();print('ECONOMY_120_AND_REOPEN_COMPLETE',flush=True)
