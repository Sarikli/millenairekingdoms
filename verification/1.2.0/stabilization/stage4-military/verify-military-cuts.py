from pathlib import Path
import subprocess,json,re,hashlib,shutil,tarfile
p=Path('/workspace/millenairekingdoms');out=Path('/workspace/royal-decree/stabilization');results=[]
for cut in ['after-roll','after-begin','after-victory','after-political-ack']:
 run='stabilization-military-cut-'+cut+'-01';case={'cut':cut,'run':run,'stages':[]};results.append(case)
 for phase in ['prepare','cut','read']:
  log=out/(run+'-'+phase+'.log');cmd=['python','/workspace/cloud-setup/millenaire-gradle.py','--offline','--init-script','scripts/isolated-tests.gradle','-Dkingdoms.testSuite=verifyStrategicCrash','-Dkingdoms.testRun='+run,'-Dkingdoms.strategicCrashPhase='+phase,'-Dkingdoms.strategicCut='+cut,'runStrategicCrashGameTestServer']
  print('RUN',cut,phase,flush=True)
  with log.open('wb') as f:code=subprocess.call(cmd,cwd=p,stdout=f,stderr=subprocess.STDOUT)
  data=log.read_text(errors='replace');passes=re.findall(r'All (\d+) required tests passed',data);jar=p/'build/libs/millenairekingdoms-1.2.0-rc8-alpha.4.dev4.jar';row={'phase':phase,'exit':code,'passes':passes,'build':'BUILD SUCCESSFUL' in data,'expected_halt':'EXPECTED_STRATEGIC_PROCESS_HALT cut='+cut in data and 'exit value 70' in data,'log':str(log),'sha256':hashlib.sha256(log.read_bytes()).hexdigest(),'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()};case['stages'].append(row)
  attempt=out/'cut-attempts'/run/phase;attempt.mkdir(parents=True,exist_ok=True);shutil.copyfile(jar,attempt/jar.name)
  with tarfile.open(attempt/'source-snapshot.tar.gz','w:gz') as archive:
   for name in ['src','build.gradle','gradle.properties','scripts/isolated-tests.gradle']:archive.add(p/name,arcname=name)
  (out/'military-cuts-results.json').write_text(json.dumps(results,indent=2)+'\n');print(row,flush=True)
  if phase=='cut':
   if code==0 or passes or not row['expected_halt']:raise SystemExit('Unexpected cut failure; no recovery gate')
  elif code or passes!=['1'] or not row['build']:raise SystemExit('Required military cut gate failed; no dependent phase')
