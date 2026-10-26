from pathlib import Path
import subprocess,zipfile,hashlib,json
p=Path('/workspace/millenairekingdoms');o=Path('/workspace/royal-decree/natural-300');v=p/'verification/1.2.0/natural-300'
cp=(p/'../royal-decree/i18n-ocean/geography/scratch-classpath.txt').read_text().strip()+':'+str(p/'millenaire-9.0.3.jar')+':'+str(p/'releases/1.2.0-final/millenairekingdoms-1.2.0.jar')
classes=o/'observer-classes';classes.mkdir(exist_ok=True)
r=subprocess.run(['/workspace/cloud-setup/jdk-21.0.13+9/bin/javac','--release','21','-proc:none','-sourcepath','','-implicit:none','-cp',cp,'-d',str(classes)]+[str(f)for f in (v/'observer-src').rglob('*.java')],capture_output=True,text=True)
if (o/'compile.log').exists():
 prior=o/('compile-attempt-'+str(len(list(o.glob('compile-attempt-*')))+1)+'.log');(o/'compile.log').rename(prior)
(o/'compile.log').write_text(r.stdout+r.stderr);print('\n'.join((r.stdout+r.stderr).splitlines()[-40:]));assert r.returncode==0
jar=o/'kingdoms-natural-observer.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED)as z:
 for root in [classes,v/'observer-resources']:
  for f in root.rglob('*'):
   if f.is_file():z.write(f,str(f.relative_to(root)))
print(json.dumps({'jar':str(jar),'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'bytes':jar.stat().st_size}))
