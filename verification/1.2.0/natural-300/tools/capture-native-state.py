"""Read-only saved-state samples after daily flush; never rewrites world data."""
from pathlib import Path
import argparse,time,json,hashlib,gzip,io,struct,fcntl,os,shutil

parser=argparse.ArgumentParser();parser.add_argument('--world',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);a=parser.parse_args()
root=a.world/'world';obs=root/'natural-observation';out=a.output;out.mkdir(parents=True,exist_ok=True)
def clocks(raw):
 s=io.BytesIO(gzip.decompress(raw))
 def n(fmt):return struct.unpack('>'+fmt,s.read(struct.calcsize('>'+fmt)))[0]
 def string():return s.read(n('H')).decode('utf-8',errors='replace')
 def value(t):
  if t in(1,2,3,4,5,6):return n({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t])
  if t==7:return s.read(n('i'))
  if t==8:return string()
  if t==9:
   kind=n('B');return[value(kind)for _ in range(n('i'))]
  if t==10:
   d={}
   while True:
    kind=n('B')
    if not kind:return d
    key=string();d[key]=value(kind)
  if t in(11,12):return[n('i'if t==11 else'q')for _ in range(n('i'))]
  raise ValueError(t)
 t=n('B');string();d=value(t)['Data'];return d['Time'],d['DayTime']
with(out/'capture.lock').open('w')as lock:
 fcntl.lockf(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);(out/'process.json').write_text(json.dumps({'pid':os.getpid(),'read_only':True})+'\n')
 deadline=time.monotonic()+43200
 while time.monotonic()<deadline:
  try:
   raw=(obs/'latest.json').read_bytes();latest=json.loads(raw);day=latest['relativeDay'];dest=out/f'day-{day:03}'
   if not dest.exists():
    level=(root/'level.dat').read_bytes();game,daytime=clocks(level)
    verification_tick=day==300 and(obs/'reopen.json').exists()and game==latest['gameTime']+1
    if game==latest['gameTime']or verification_tick:
     files={'level.dat':level,'observer-snapshot.json':raw}
     for name in['millenaire_villages.dat','millenairekingdoms_kingdoms.dat']:
      files['data/'+name]=(root/'data'/name).read_bytes()
     # Individual files are complete reads, but this is explicitly not an atomic
     # multi-store snapshot; normal domain writers can run after the daily save.
     temp=out/f'.day-{day:03}-{os.getpid()}';temp.mkdir(exist_ok=False)
     for name,data in files.items():f=temp/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(data)
     receipt={'day':day,'captured_unix_time':time.time(),'observer_game_time':latest['gameTime'],'saved_world_game_time':game,'saved_world_day_time':daytime,'after_verification_tick':verification_tick,'read_only':True,'atomic_across_stores':False,'files':{name:{'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}for name,data in files.items()}}
     (temp/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');temp.rename(dest);print('NATIVE_STATE_SAMPLED',day,game,flush=True)
   if(obs/'final.json').exists()and(obs/'reopen.json').exists():break
   if(obs/'failure.json').exists():print('Observer failure retained; sampler stopping',flush=True);break
  except(json.JSONDecodeError,FileNotFoundError):pass
  time.sleep(2)
