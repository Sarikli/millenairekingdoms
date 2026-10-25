from pathlib import Path
import subprocess,json,re,sys,hashlib,shutil,tarfile
p=Path('/workspace/millenairekingdoms');out=Path('/workspace/royal-decree');result=[]
for arg in sys.argv[1:]:
 suite,task,count,run=arg.split(':');case={'suite':suite,'run':run,'stages':[]};result.append(case)
 for phase in ['write','reopen']:
  log=out/(run+'-'+phase+'.log');cmd=['python','/workspace/cloud-setup/millenaire-gradle.py','--offline','--init-script','scripts/isolated-tests.gradle','-Dkingdoms.testSuite='+suite,'-Dkingdoms.testRun='+run,'run'+task+'GameTestServer'+('Reload' if phase=='reopen' else '')]
  if phase=='reopen':cmd+=['-x','run'+task+'GameTestServer']
  print('RUN',suite,phase,flush=True)
  with log.open('wb') as s:code=subprocess.call(cmd,cwd=p,stdout=s,stderr=subprocess.STDOUT)
  data=log.read_text(errors='replace');lines=re.findall(r'All (\d+) required tests passed',data);jars=list((p/'build/libs').glob('*.jar'));jar=max(jars,key=lambda f:f.stat().st_mtime_ns)
  attempt=out/'i18n-ocean'/'attempts'/run/phase;attempt.mkdir(parents=True,exist_ok=True);shutil.copyfile(jar,attempt/jar.name)
  with tarfile.open(attempt/'source-snapshot.tar.gz','w:gz') as archive:
   for relative in ['src','build.gradle','gradle.properties','scripts/isolated-tests.gradle','scripts/check-localization.py']:
    source=p/relative
    if source.exists():archive.add(source,arcname=relative)
  row={'phase':phase,'exit':code,'passes':lines,'build':'BUILD SUCCESSFUL' in data,'log':str(log),'sha256':hashlib.sha256(log.read_bytes()).hexdigest(),'jar':jar.name,'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()};case['stages'].append(row);(out/(run+'-results.json')).write_text(json.dumps(result,indent=2)+'\n');print(row,flush=True)
  if code or lines!=[count] or not row['build']:raise SystemExit('GATE FAILED; no dependent reopen')
