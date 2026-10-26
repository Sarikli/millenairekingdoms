from pathlib import Path
import struct,zipfile,json,hashlib
p=Path(__file__).resolve().parents[4];out=p/'verification/1.2.0/rc8-alpha/dev7.3';out.mkdir(exist_ok=True)
def normalize(data):
 b=bytearray(data);i=8
 def u2():
  nonlocal i
  v=struct.unpack_from('>H',b,i)[0];i+=2;return v
 def u4():
  nonlocal i
  v=struct.unpack_from('>I',b,i)[0];i+=4;return v
 count=u2();utf={};n=1
 while n<count:
  tag=b[i];i+=1
  if tag==1:
   size=u2();utf[n]=bytes(b[i:i+size]).decode('utf-8',errors='replace');i+=size
  elif tag in (3,4,9,10,11,12,17,18):i+=4
  elif tag in (5,6):i+=8;n+=1
  elif tag in (7,8,16,19,20):i+=2
  elif tag==15:i+=3
  else:raise ValueError(tag)
  n+=1
 i+=6;interfaces=u2();i+=interfaces*2
 def attributes():
  nonlocal i
  for _ in range(u2()):
   name=utf[u2()];length=u4();end=i+length
   if name=='Code':
    i+=4;size=u4();i+=size;exceptions=u2();i+=exceptions*8;attributes()
   elif name=='LineNumberTable':
    for _ in range(u2()):i+=2;b[i:i+2]=b'\0\0';i+=2
   i=end
 for _ in range(2):
  for _ in range(u2()):i+=6;attributes()
 attributes();assert i==len(b);return bytes(b)
old=p/'releases/1.2.0-rc8-alpha.dev7.2/millenairekingdoms-1.2.0-rc8-alpha.dev7.2.jar';new=p/'releases/1.2.0-rc8-alpha.dev7.3/millenairekingdoms-1.2.0-rc8-alpha.dev7.3.jar';rows=[]
with zipfile.ZipFile(old) as a,zipfile.ZipFile(new) as b:
 for name in ['com/millenairekingdoms/client/KingdomScreen$RowList.class','com/millenairekingdoms/client/KingdomScreen$RowList$Row.class']:
  x=a.read(name);y=b.read(name);assert x!=y and normalize(x)==normalize(y);rows.append({'class':name,'old_sha256':hashlib.sha256(x).hexdigest(),'new_sha256':hashlib.sha256(y).hexdigest(),'only_LineNumberTable_line_values_differ':True,'normalized_sha256':hashlib.sha256(normalize(x)).hexdigest()})
(out/'nested-debug-only.json').write_text(json.dumps(rows,indent=2)+'\n');print(json.dumps(rows,indent=2))
