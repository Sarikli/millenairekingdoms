from pathlib import Path
import subprocess,json,re,hashlib,os,signal
p=Path('/workspace/millenairekingdoms');out=Path('/workspace/royal-decree/1.2.1');runner=['python','/workspace/cloud-setup/millenaire-gradle.py','--offline']
def execute(name,cmd,timeout):
 print('START',name,flush=True)
 with(out/(name+'.log')).open('w')as log:
  process=subprocess.Popen(cmd,cwd=p,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  try:code=process.wait(timeout=timeout)
  except subprocess.TimeoutExpired:
   os.killpg(process.pid,signal.SIGTERM)
   try:code=process.wait(timeout=30)
   except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);code=process.wait()
   raise SystemExit(name+' timed out; evidence retained')
 print('COMPLETE',name,'exit',code,flush=True)
 return code
code=execute('maintenance-ab-baseline-01',runner+['--init-script','scripts/isolated-tests.gradle','--init-script',str(out/'baseline-class-maintenance.gradle'),'-Dkingdoms.testSuite=verifyMaintenance','-Dkingdoms.testRun=patch121-maintenance-ab-01','-Dkingdoms.scaleCapitals=500','runMaintenanceGameTestServer'],600)
text=(out/'maintenance-ab-baseline-01.log').read_text();meta=json.loads((out/'maintenance-ab-metadata.json').read_text());class_match=re.search(r'MAINTENANCE_RUNTIME_CLASS sha256=(\w+)',text);counts=[int(n)for n in re.findall(r'All (\d+) required tests passed',text)];meta.update(exit_code=code,required_pass_counts=counts,runtime_class_observed=class_match[1]if class_match else None,measurement_valid=code==0 and counts==[1]and class_match is not None and class_match[1]==meta['expected_runtime_class_sha256'],metrics=re.findall(r'MAINTENANCE_METADATA_SCALE[^\n]+',text),unloaded_warning_count=text.count('Conserving treasury maintenance'))
(out/'maintenance-ab-baseline-01.json').write_text(json.dumps(meta,indent=2)+'\n');print('BENCHMARK_VALID',meta['measurement_valid'],flush=True)
for domain in ['independent','consolidation']:
 code=execute(domain+'-cuts-runner',['python','scripts/verify-process-cuts.py',domain,'--',*runner],7200)
 if code:raise SystemExit('Required '+domain+' process-cut gate failed')
code=execute('production-runner',['python',str(out/'verify-production.py')],3600)
if code:raise SystemExit('Required production lifecycle gate failed')
print('ALL_FINAL_GATES_COMPLETE',flush=True)
