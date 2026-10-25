from pathlib import Path
import sys,os,subprocess,zipfile,hashlib,json,urllib.request,shutil
root=Path(__file__).resolve().parent
sys.path.insert(0,str(root/'tools'))
from login_dependencies import ensure
from android_protocol import rebuild
sdk=Path(sys.argv[1]);android=sdk/'platforms/android-35/android.jar'
rebuild(ensure(),android)
cache=root/'cache';out=cache/'bridge-build';out.mkdir(exist_ok=True)
classes=out/'classes';classes.mkdir(exist_ok=True)
subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-encoding','UTF-8','-cp',str(android),'-d',str(classes),*map(str,sorted((root/'compile-stubs').rglob('*.java'))),*map(str,sorted((root/'bridge').rglob('*.java')))],check=True)
jar=out/'bridge.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(classes.rglob('*.class')):
  if 'dev/yiji/qqlocalprobe/bridge/' in p.as_posix():z.write(p,p.relative_to(classes).as_posix())
spec=json.loads((root/'tools/r8-version.json').read_text());r8=cache/'r8'/('r8-'+spec['version']+'.jar')
if not r8.exists():
 r8.parent.mkdir(parents=True,exist_ok=True)
 with urllib.request.urlopen(spec['url'],timeout=90) as r:r8.write_bytes(r.read())
assert hashlib.sha256(r8.read_bytes()).hexdigest()==spec['sha256']
dex=out/'dex';dex.mkdir(exist_ok=True)
subprocess.run(['java','-cp',str(r8),'com.android.tools.r8.D8','--release','--min-api','26','--lib',str(android),'--classpath',str(classes),'--output',str(dex),str(jar)],check=True)
assets=root/'generated-assets';assets.mkdir(exist_ok=True)
shutil.copy2(dex/'classes.dex',assets/'native_bridge.dex')
for name in ['LICENSE','THIRD_PARTY_NOTICES.md']:shutil.copy2(root/name,assets/('QQ_'+name))
shutil.copytree(root/'third_party/licenses',assets/'qq-licenses',dirs_exist_ok=True)
