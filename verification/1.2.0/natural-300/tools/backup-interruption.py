from pathlib import Path
import tarfile,hashlib,json,fcntl
p=Path('/workspace/millenairekingdoms/run/natural-300-world-03');out=Path('/workspace/royal-decree/natural-300/checkpoints');out.mkdir(exist_ok=True)
archive=out/'world-03-before-resume.tar.gz';manifest=out/'world-03-before-resume.json';assert not archive.exists()and not manifest.exists()
def sha(f):
 h=hashlib.sha256()
 while b:=f.read(1024*1024):h.update(b)
 return h.hexdigest()
with(p/'world/session.lock').open('rb+')as lock:
 fcntl.lockf(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 files={}
 for f in p.rglob('*'):
  assert not f.is_symlink()
  if f.is_file():
   with f.open('rb')as stream:files[str(f.relative_to(p.parent))]={'bytes':f.stat().st_size,'sha256':sha(stream)}
 with tarfile.open(archive,'w:gz',compresslevel=1)as t:t.add(p,arcname=p.name)
 seen=set()
 with tarfile.open(archive,'r:gz')as t:
  for m in t:
   if m.isfile():
    with t.extractfile(m)as stream:assert sha(stream)==files[m.name]['sha256']
    assert m.size==files[m.name]['bytes'];seen.add(m.name)
 assert seen==set(files)
 for rel,row in files.items():
  with(p.parent/rel).open('rb')as stream:assert sha(stream)==row['sha256']
 with archive.open('rb')as stream:digest=sha(stream)
 result={'source':str(p),'archive':str(archive),'archive_sha256':digest,'source_preserved_in_place':True,'all_files_byte_verified':True,'files':files}
 manifest.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'archive':str(archive),'bytes':archive.stat().st_size,'sha256':digest,'files':len(files),'original_preserved':True}),flush=True)
