#!/usr/bin/env python3
"""Inspect a built QQ integration APK and its release verification reports."""
from pathlib import Path
import hashlib,json,os,re,subprocess,sys,xml.etree.ElementTree as ET,zipfile

root=Path(__file__).resolve().parents[1]
apk=Path(sys.argv[1]) if len(sys.argv)>1 else root/'app/build/outputs/apk/release/app-release.apk'
sdk=Path(os.environ['ANDROID_HOME']);tools=sdk/'build-tools/35.0.0'
expected='041f228046082db2aaf03367fc29417b0620aadc45a4c5c05d42a08c579f0fea'
cert=subprocess.check_output(['java','-cp',str(tools/'lib/apksigner.jar'),'com.android.apksigner.ApkSignerTool','verify','--verbose','--print-certs',str(apk)],text=True)
assert re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)',cert)==[expected]
subprocess.run([str(tools/'zipalign'),'-c','-P','16','4',str(apk)],check=True,capture_output=True)
badging=subprocess.check_output([str(tools/'aapt2'),'dump','badging',str(apk)],text=True)
assert "name='cn.yiji.app'" in badging and "versionName='2.2.0'" in badging and "versionCode='11'" in badging
assert 'application-debuggable' not in badging and "minSdkVersion:'26'" in badging and "targetSdkVersion:'35'" in badging
from loguru import logger
logger.remove()
from androguard.core.apk import APK
from androguard.core.dex import DEX
package=APK(str(apk));manifest=package.get_android_manifest_xml();ns='{http://schemas.android.com/apk/res/android}'
app=manifest.find('application');assert app.get(ns+'debuggable','false')=='false'
service=next(e for e in app.findall('service') if e.get(ns+'name','').endswith('.qq.QqCaptureService'))
assert service.get(ns+'process','').endswith(':qq_capture')
assert service.get(ns+'exported')=='false' and service.get(ns+'stopWithTask')=='false'
fgs=service.get(ns+'foregroundServiceType','');assert fgs=='specialUse' or int(fgs,0)&0x40000000
receiver=next(e for e in app.findall('receiver') if e.get(ns+'name','').endswith('.qq.QqIngressReceiver'));assert receiver.get(ns+'exported')=='false'
activity=next(e for e in app.findall('activity') if e.get(ns+'name','').endswith('.LoginChallengeActivity'));assert activity.get(ns+'exported')=='false'
with zipfile.ZipFile(apk) as z:
    names=z.namelist();libs=[n for n in names if n.startswith('lib/') and n.endswith('.so')]
    assert {n.split('/')[1] for n in libs}=={'arm64-v8a'}
    assert not any(n.rsplit('/',1)[-1] in ('libfekit.so','libmmkv.so','libQSec.so') for n in names)
    assert 'assets/QQ_LICENSE' in names and 'assets/QQ_THIRD_PARTY_NOTICES.md' in names
    bridge=DEX(z.read('assets/native_bridge.dex'))
    assert set(bridge.get_classes_names())=={'Ldev/yiji/qqlocalprobe/bridge/NativeBridge;','Ldev/yiji/qqlocalprobe/bridge/ProbeChannel;'}
    classes=set();unsupported=[]
    for name in names:
        if re.fullmatch(r'classes\d*\.dex',name):
            dex=DEX(z.read(name));classes.update(dex.get_classes_names())
            for method in dex.get_methods():
                if method.get_class_name()=='Ljava/util/List;' and method.get_name() in ('removeFirst','removeLast','getFirst','getLast','reversed'):
                    unsupported.append(method.get_name())
    assert not any(c.startswith('Lcom/tencent/') for c in classes)
    assert not unsupported,unsupported
    for name in ['ProbeRunner','NativeBootstrap','DiagnosticClassLoader','HostEnvironment','LocalNativeSigner','MessageCodec']:
        assert 'Ldev/yiji/qqlocalprobe/'+name+';' in classes,name
reports=list((root/'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'));assert reports
suites=[ET.parse(p).getroot() for p in reports]
tests=sum(int(s.attrib['tests']) for s in suites)
failed=sum(int(s.attrib.get('failures',0))+int(s.attrib.get('errors',0)) for s in suites)
skipped=sum(int(s.attrib.get('skipped',0)) for s in suites)
assert tests>0 and failed==0 and skipped==0,(tests,failed,skipped)
lint=ET.parse(root/'app/build/reports/lint-results-release.xml').getroot()
assert not any(i.attrib['severity'] in ('Error','Fatal') for i in lint.findall('issue'))
print(json.dumps({'apk':str(apk),'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
    'certificateSha256':expected,'packageName':'cn.yiji.app','versionName':'2.2.0','versionCode':11,
    'minSdk':26,'targetSdk':35,'abi':'arm64-v8a','debuggable':False,'zipalign16KiB':True,
    'qqServiceProcess':service.get(ns+'process'),'qqServiceStopWithTask':False,'qqComponentsExported':False,
    'bundledTencentCode':False,'nativeLibraries':libs,'applicationClassCount':len(classes),
    'tests':tests,'failures':failed,'skipped':skipped,'lintErrors':0,
    'lintWarnings':sum(i.attrib['severity']=='Warning' for i in lint.findall('issue')),
    'limitations':['No real QQ account login in this environment','Screen-off longevity and power usage require target-device verification']},ensure_ascii=False,indent=2))
