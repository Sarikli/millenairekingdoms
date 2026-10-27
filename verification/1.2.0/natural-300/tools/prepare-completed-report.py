"""Prepare review artifacts only after real completion and same-world JVM reopen."""
from pathlib import Path
import argparse,subprocess,json,time,os,hashlib

parser=argparse.ArgumentParser();parser.add_argument('--world',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);a=parser.parse_args()
obs=a.world/'world/natural-observation';out=a.output;out.mkdir(parents=True,exist_ok=True);tools=Path(__file__).resolve().parent
deadline=time.monotonic()+172800
while time.monotonic()<deadline:
 if(obs/'failure.json').exists():raise SystemExit('Observer failure is preserved; no completed report generated')
 if(obs/'final.json').exists()and(obs/'reopen.json').exists():
  final=json.loads((obs/'final.json').read_text());reopen=json.loads((obs/'reopen.json').read_text());assert final['observationComplete']and final['consecutiveTicks']==7200000 and reopen['sameWorldSeparateJvm']and reopen['kingdomMembershipAndCapitalsPreserved']
  subprocess.run(['python',str(tools/'analyze-observation.py'),'--world',str(a.world),'--output',str(out)],stdout=(out/'analysis.log').open('w'),stderr=subprocess.STDOUT,check=True)
  environment=os.environ.copy();environment['MPLCONFIGDIR']='/tmp/kingdoms-natural300-mpl';environment['XDG_CACHE_HOME']='/tmp/kingdoms-natural300-cache'
  subprocess.run(['python',str(tools/'plot-observation.py'),'--world',str(a.world),'--output',str(out)],env=environment,stdout=(out/'plots.log').open('w'),stderr=subprocess.STDOUT,check=True)
  summary=json.loads((out/'summary.json').read_text());assert summary['observation_complete']and summary['separate_jvm_reopen_verified']and summary['daily_snapshots']==301
  (out/'ready-for-review.json').write_text(json.dumps({'actual_days':300,'actual_world_ticks':7200000,'daily_rows':301,'separate_jvm_reopen':True,'files':{f.name:hashlib.sha256(f.read_bytes()).hexdigest()for f in out.iterdir()if f.is_file()}},indent=2)+'\n');print('COMPLETED_OBSERVATION_REPORT_READY_FOR_REVIEW',flush=True);break
 time.sleep(10)
else:raise SystemExit('Completion not observed; no final report claimed')
