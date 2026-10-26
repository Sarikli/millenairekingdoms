from pathlib import Path
import subprocess,hashlib,json,re
p=Path('/workspace/millenairekingdoms');out=Path('/workspace/royal-decree/stabilization');base=out.parent;spec=json.loads((out/'final-matrix-spec.json').read_text());jar=p/'build/libs/millenairekingdoms-1.2.0-rc.9.jar'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def source_hash():
 d=hashlib.sha256();files=[]
 for relative in ['src','build.gradle','gradle.properties','scripts/verify-regression.py','scripts/isolated-tests.gradle','scripts/check-localization.py']:
  root=p/relative;files.extend(root.rglob('*')if root.is_dir()else[root])
 for f in sorted(f for f in files if f.is_file()):d.update(str(f.relative_to(p)).encode()+b'\0'+f.read_bytes())
 return d.hexdigest()
result=json.loads((out/'rc9-matrix-results.json').read_text());assert len(result['results'])==11 and all(c['passed']for c in result['results']);assert sha(jar)==result['jar_sha256'];frozen=source_hash();revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=p,text=True).strip();result['test_source_revisions']=[{'source_commit':result['source_commit'],'source_sha256':result['source_sha256'],'completed_suites':11},{'source_commit':revision,'source_sha256':frozen,'reason':'Only long-test observer callbacks changed after the RoyalLive reopen ended without a terminal result; production JAR byte-identical. Completed pairs retained.'}];result['interrupted_attempts']=[{'suite':'verifyRoyalLive','run':'stabilization-rc9-royallive-01','write_result':'All 3 required tests passed; exit0/BUILD SUCCESSFUL','reopen_result':'Interrupted without an all-required line or terminal build result. Unverified, never a passed pair.','reopen_log':str(base/'stabilization-rc9-royallive-01-reopen.log')}]
for row in spec:
 if row['suite']=='verifyRoyalLive':row['run']='stabilization-rc9-royallive-02'
(out/'final-matrix-spec.json').write_text(json.dumps(spec,indent=2)+'\n')
for n,row in enumerate(spec[len(result['results']):],len(result['results'])+1):
 assert source_hash()==frozen and sha(jar)==result['jar_sha256'],'Candidate source/artifact changed; preserve evidence and stop'
 print('START_FINAL',n,len(spec),row['suite'],row['run'],flush=True);arg=f"{row['suite']}:{row['task_stem']}:{row['count']}:{row['run']}"
 with(out/'rc9-matrix-detailed.log').open('ab')as log:code=subprocess.call(['python','/workspace/royal-decree/ocean-loading/verify.py',arg],cwd=p,stdout=log,stderr=subprocess.STDOUT)
 report=base/(row['run']+'-results.json');case=json.loads(report.read_text())[-1]if report.exists()else {'suite':row['suite'],'run':row['run'],'stages':[]};case.update(expected_per_pass=row['count'],runner_exit=code,source_frozen=source_hash()==frozen,test_source_commit=revision,test_source_sha256=frozen);case['passed']=code==0 and case['source_frozen']and len(case['stages'])==2 and all(r['exit']==0 and r['passes']==[str(row['count'])]and r['build']and r['jar_sha256']==result['jar_sha256']for r in case['stages']);result['results'].append(case);result['complete']=len(result['results'])==len(spec)and all(c['passed']for c in result['results']);(out/'rc9-matrix-results.json').write_text(json.dumps(result,indent=2)+'\n')
 if not case['passed']:print('FAILED_FINAL',row['suite'],str(report),flush=True);raise SystemExit(1)
 print('PASSED_FINAL',n,len(spec),row['suite'],f"write/reopen: All {row['count']} required tests passed",flush=True)
print('FINAL_MATRIX_COMPLETE',len(spec),'packages',2*len(spec),'JVMs','required_per_pass',result['required_tests_each_complete_pass'],'jar',result['jar_sha256'],flush=True)
