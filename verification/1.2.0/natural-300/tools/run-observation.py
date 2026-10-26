from pathlib import Path
import subprocess,time,json,hashlib,shutil,re

p=Path('/workspace/millenairekingdoms');out=Path('/workspace/royal-decree/natural-300');capture=out/'attempt-03';capture.mkdir(exist_ok=True);world=p/'run/natural-300-world-03'
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
final=p/'releases/1.2.0-final/millenairekingdoms-1.2.0.jar';native=p/'millenaire-9.0.3.jar';observer=out/'kingdoms-natural-observer.jar'
assert sha(final)=='b7ae9fc2f208588962bac8739021b4e4169322443d475b65c672791a53a3e8dd'
assert sha(native)=='5882528027ac67e8759db3926ebef36abe2300029526582f80c7a988b583ec62'
assert not world.exists(),'Preserve earlier attempts instead of reusing them'
world.mkdir();(world/'mods').mkdir();shutil.copyfile(final,world/'mods/millenairekingdoms.jar');shutil.copyfile(observer,world/'mods/kingdoms-natural-observer.jar')
shutil.copyfile(p/'run/release-120-production-new-01/eula.txt',world/'eula.txt')
(world/'server.properties').write_text('''level-name=world
level-seed=20261027300
level-type=minecraft:normal
generate-structures=true
difficulty=normal
gamemode=survival
online-mode=false
enforce-secure-profile=false
server-ip=127.0.0.1
server-port=25589
view-distance=5
simulation-distance=5
max-tick-time=-1
allow-flight=true
''')
provenance={'seed':20261027300,'world':str(world),'production_jar_sha256':sha(final),'native_jar_sha256':sha(native),'observer_jar_sha256':sha(observer),'git_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=p,text=True).strip(),'gameplay_changes':'None. Default addon/native config. No injected relations, money, materials, buildings, villages, kings, victories or Ethos. Native mock connection is used for passive exploration/chunk presence. Before baseline, normal terrain chunks in each 17x17 exploration window are generated explicitly because an embedded connection lacks a graphical client chunk view. Original ChunkEvent.Load and VillageSpawnQueue alone select/place villages and capitals. Temporary test-only exploration region tickets keep the normal view resident until the original spawn queue processes it; all observer tickets are removed before the measurement baseline. During the 300days, only original native village activation manages village chunks.','clock':'Vanilla tick sprint; every game tick and dayTime increment is checked. 300days=7200000 actual ticks after baseline.','production_classes_modified':False,'stages':[]}
(capture/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
for phase in ['write','reopen']:
 log=capture/(phase+'.log');print('OBSERVATION_START',phase,flush=True)
 command=['python','/workspace/cloud-setup/millenaire-gradle.py','--offline','--init-script',str(out/'server.gradle'),'-Dkingdoms.observation.mode='+phase,'runServerJar']
 with log.open('w')as f:proc=subprocess.Popen(command,cwd=p,stdout=f,stderr=subprocess.STDOUT,stdin=subprocess.PIPE,text=True)
 (capture/'process.json').write_text(json.dumps({'runner_phase':phase,'pid':proc.pid,'log':str(log),'world':str(world)})+'\n')
 seen=0;deadline=time.monotonic()+(43200 if phase=='write'else 600)
 while proc.poll()is None and time.monotonic()<deadline:
  lines=log.read_text(errors='replace').splitlines()
  for line in lines[seen:]:
   if 'NATURAL_300_'in line:print(line,flush=True)
  seen=len(lines);time.sleep(2)
 if proc.poll()is None:
  proc.stdin.write('stop\n');proc.stdin.flush();proc.wait(timeout=180);raise SystemExit('Observation timed out; logs/world retained')
 code=proc.returncode;text=log.read_text(errors='replace');observations=world/'world/natural-observation'
 assert code==0 and 'BUILD SUCCESSFUL'in text and 'Millénaire Kingdoms 1.2.0 initialized'in text and 'millenairekingdoms_tests'not in text,(phase,code)
 assert not (observations/'failure.json').exists(),(observations/'failure.json').read_text()if(observations/'failure.json').exists()else''
 assert sha(world/'mods/millenairekingdoms.jar')==sha(final)
 if phase=='write':
  result=json.loads((observations/'final.json').read_text());assert result['observationComplete']and result['consecutiveTicks']==7200000
  assert result['gameTime']-result['baseTick']==7200000 and result['dayTime']-result['baseDayTime']==7200000
  baseline=json.loads((observations/'baseline.json').read_text());assert len(baseline['kingdoms'])>=2 and baseline['observerExplorationTickets']==0 and result['observerExplorationTickets']==0
  assert text.count('Natural capital founded:')>=2
 else:result=json.loads((observations/'reopen.json').read_text());assert result['sameWorldSeparateJvm']and result['kingdomMembershipAndCapitalsPreserved']
 row={'phase':phase,'exit_code':code,'build_successful':True,'log_sha256':sha(log),'production_jar_sha256':sha(final),'result':result};provenance['stages'].append(row);(capture/'results.json').write_text(json.dumps(provenance,indent=2)+'\n')
 print('OBSERVATION_STAGE_COMPLETE',phase,flush=True)
assert sha(final)==provenance['production_jar_sha256']and sha(native)==provenance['native_jar_sha256']
print('OBSERVATION_AND_REOPEN_COMPLETE',flush=True)
