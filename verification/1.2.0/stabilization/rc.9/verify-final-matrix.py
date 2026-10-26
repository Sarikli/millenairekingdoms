from pathlib import Path
import subprocess,hashlib,json,re,sys
p=Path('/workspace/millenairekingdoms');out=Path('/workspace/royal-decree/stabilization');base=out.parent;spec=json.loads((out/'final-matrix-spec.json').read_text());jar=p/'build/libs/millenairekingdoms-1.2.0-rc.9.jar'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def source_hash():
 digest=hashlib.sha256();roots=['src','build.gradle','gradle.properties','scripts/verify-regression.py','scripts/isolated-tests.gradle','scripts/check-localization.py'];files=[]
 for relative in roots:
  root=p/relative;files.extend(root.rglob('*')if root.is_dir()else[root])
 for file in sorted(f for f in files if f.is_file()):digest.update(str(file.relative_to(p)).encode()+b'\0'+file.read_bytes())
 return digest.hexdigest()
assert 'BUILD SUCCESSFUL' in (out/'rc9-build.log').read_text();source_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=p,text=True).strip();artifact_hash=sha(jar);frozen=source_hash();result={'version':'1.2.0-rc.9','source_commit':source_commit,'source_sha256':frozen,'jar_sha256':artifact_hash,'expected_suites':len(spec),'expected_jvms':2*len(spec),'required_tests_each_complete_pass':sum(row['count']for row in spec),'results':[]};(out/'rc9-source.json').write_text(json.dumps({k:v for k,v in result.items()if k!='results'},indent=2)+'\n')
for n,row in enumerate(spec,1):
 assert source_hash()==frozen and sha(jar)==artifact_hash,'Candidate source/JAR changed; preserve completed evidence and stop'
 print('START_FINAL',n,len(spec),row['suite'],row['run'],flush=True)
 arg=f"{row['suite']}:{row['task_stem']}:{row['count']}:{row['run']}"
 with (out/'rc9-matrix-detailed.log').open('ab')as output:code=subprocess.call(['python','/workspace/royal-decree/ocean-loading/verify.py',arg],cwd=p,stdout=output,stderr=subprocess.STDOUT)
 report=base/(row['run']+'-results.json');case=json.loads(report.read_text())[-1]if report.exists()else {'suite':row['suite'],'run':row['run'],'stages':[]};case['expected_per_pass']=row['count'];case['runner_exit']=code;case['source_frozen']=source_hash()==frozen;case['passed']=code==0 and case['source_frozen'] and len(case['stages'])==2 and all(stage['exit']==0 and stage['passes']==[str(row['count'])]and stage['build']and stage['jar_sha256']==artifact_hash for stage in case['stages']);result['results'].append(case);result['complete']=len(result['results'])==len(spec)and all(c['passed']for c in result['results']);(out/'rc9-matrix-results.json').write_text(json.dumps(result,indent=2)+'\n')
 if not case['passed']:print('FAILED_FINAL',row['suite'],str(report),flush=True);raise SystemExit(1)
 print('PASSED_FINAL',n,len(spec),row['suite'],f"write/reopen: All {row['count']} required tests passed",flush=True)
print('FINAL_MATRIX_COMPLETE',len(spec),'packages',2*len(spec),'JVMs','required_per_pass',result['required_tests_each_complete_pass'],'jar',artifact_hash,flush=True)
