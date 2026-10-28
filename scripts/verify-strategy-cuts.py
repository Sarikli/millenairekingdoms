#!/usr/bin/env python3
"""Verify durable unpaid/authorized strategy boundaries with actual halt70 and independent recovery JVMs."""
import argparse, datetime, hashlib, json, os, re, signal, subprocess, uuid
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('runner',nargs=argparse.REMAINDER)
args=parser.parse_args()
root=Path(__file__).resolve().parent.parent
runner=args.runner[1:] if args.runner[:1]==['--'] else args.runner
runner=runner or ['bash',str(root/'gradlew')]
run='strategy-cuts-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]
out=root/'build/verification'/run;out.mkdir(parents=True)
version=re.search(r'^mod_version=(.+)$',(root/'gradle.properties').read_text(),re.M).group(1)
jar=root/'build/libs'/('millenairekingdoms-'+version+'.jar')
rows=[]
print('Evidence directory:',out,flush=True)
for cut in ['before-decision','after-decision']:
 case=dict(cut=cut,stages=[]);rows.append(case)
 for phase in ['prepare','cut','read']:
  log=out/(cut+'-'+phase+'.log')
  cmd=[*runner,'--init-script',str(root/'scripts/isolated-tests.gradle'),'-Dkingdoms.testSuite=verifyStrategyEconomyCrash','-Dkingdoms.testRun='+run+'-'+cut,'-Dkingdoms.strategyEconomyPhase='+phase,'-Dkingdoms.strategyEconomyCut='+cut,'runStrategyEconomyCrashGameTestServer']
  print('START',cut,phase,flush=True)
  with log.open('w')as stream:
   process=subprocess.Popen(cmd,cwd=root,stdout=stream,stderr=subprocess.STDOUT,start_new_session=(os.name=='posix'))
   try: code=process.wait(timeout=900)
   except subprocess.TimeoutExpired:
    if os.name=='posix':os.killpg(process.pid,signal.SIGTERM)
    else:process.terminate()
    try:process.wait(timeout=30)
    except subprocess.TimeoutExpired:
     if os.name=='posix':os.killpg(process.pid,signal.SIGKILL)
     else:process.kill()
     process.wait()
    raise SystemExit('Strategy process-cut timeout; original evidence retained')
  content=log.read_text(errors='replace');counts=[int(v)for v in re.findall(r'All (\d+) required tests passed',content)]
  halted='EXPECTED_STRATEGY_ECONOMY_PROCESS_HALT cut='+cut in content and 'exit value 70' in content
  launches=re.findall(r'ISOLATED_TEST suite=\S+ task=(\S+) world=(.+)',content)
  sha=hashlib.sha256(jar.read_bytes()).hexdigest();image_matches=len(launches)==1 and hashlib.sha256((Path(launches[0][1])/'mods/millenairekingdoms.jar').read_bytes()).hexdigest()==sha
  passed=image_matches and (code!=0 and counts==[] and halted if phase=='cut' else code==0 and counts==[1] and 'BUILD SUCCESSFUL' in content)
  row=dict(phase=phase,exit_code=code,required_pass_counts=counts,expected_halt=halted,jar_sha256=sha,image_matches=image_matches,log_sha256=hashlib.sha256(log.read_bytes()).hexdigest(),passed=passed)
  case['stages'].append(row);(out/'results.json').write_text(json.dumps(rows,indent=2)+'\n');print(json.dumps(dict(cut=cut,**row)),flush=True)
  if not passed:raise SystemExit('Actual strategy cut/recovery gate failed; dependent phases not run')
