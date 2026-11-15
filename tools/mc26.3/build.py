# Build of Millénaire Kingdoms for Minecraft 26.3 without Gradle (the way this port was built and tested).
# Usage: python3 tools/mc26.3/build.py <dir with the NeoForge 26.3 / Minecraft 26.3 jars> <millenaire-9.1.0+mc26.3.jar> [javac]
# Writes build/millenairekingdoms-<version>.jar (version read from gradle.properties).
import os, sys, glob, shutil, zipfile, subprocess, json, re
ROOT=os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CPDIR,MILL=sys.argv[1],sys.argv[2]; JAVAC=sys.argv[3] if len(sys.argv)>3 else "javac"
props=dict(l.split("=",1) for l in open(ROOT+"/gradle.properties",encoding="utf-8").read().splitlines() if "=" in l and not l.startswith("#"))
VER=props["mod_version"].strip()
OUT=ROOT+"/build/classes-mc26.3"; shutil.rmtree(OUT,ignore_errors=True); os.makedirs(OUT)
CP=os.pathsep.join(sorted(glob.glob(CPDIR+"/*.jar"))+[MILL])
srcs=glob.glob(ROOT+"/src/main/java/**/*.java",recursive=True)
r=subprocess.run([JAVAC,"-nowarn","-proc:none","-encoding","UTF-8","--release","25","-d",OUT,"-cp",CP]+srcs,capture_output=True,text=True)
if r.returncode: print(r.stderr[-4000:]); raise SystemExit("compilation failed")
toml=open(ROOT+"/src/main/templates/META-INF/neoforge.mods.toml",encoding="utf-8").read()
toml=re.sub(r"\$\{(\w+)\}",lambda m:props.get(m.group(1),m.group(0)).strip(),toml).replace("\\u00e9","é")
jar=ROOT+f"/build/millenairekingdoms-{VER}.jar"
z=zipfile.ZipFile(jar,"w",zipfile.ZIP_DEFLATED); z.writestr("META-INF/MANIFEST.MF","Manifest-Version: 1.0\r\n\r\n"); z.writestr("META-INF/neoforge.mods.toml",toml)
z.write(ROOT+"/LICENSE","LICENSE")
RES=ROOT+"/src/main/resources"
for root,ds,fs in os.walk(RES):
    for f in sorted(fs): p=os.path.join(root,f); z.write(p,os.path.relpath(p,RES).replace(os.sep,"/"))
for root,ds,fs in os.walk(OUT):
    for f in sorted(fs): p=os.path.join(root,f); z.write(p,os.path.relpath(p,OUT).replace(os.sep,"/"))
z.close(); print("jar:",jar,os.path.getsize(jar),"bytes")
