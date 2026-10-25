#!/usr/bin/env python3
"""Rebuild Acidify's public source for Android (the JVM release targets Java 25)."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def patch_android_device(source):
    tlv = source / 'commonMain/org/ntqqrev/acidify/internal/tlv/AndroidTlv.kt'
    original = tlv.read_text()
    changes = [
        ('writeString("13", Prefix.UINT_16 or Prefix.LENGTH_ONLY) // os version',
         'writeString(android.os.Build.VERSION.RELEASE, Prefix.UINT_16 or Prefix.LENGTH_ONLY) // os version'),
        ('writeString("", Prefix.UINT_16 or Prefix.LENGTH_ONLY) // brand',
         'writeString(android.os.Build.BRAND, Prefix.UINT_16 or Prefix.LENGTH_ONLY) // brand'),
        ('writeInt(0) // sdk build time', 'writeInt(client.appInfo.sdkInfo.sdkBuildTime.toInt()) // sdk build time'),
        ('codeName = "REL"', 'codeName = android.os.Build.VERSION.CODENAME'),
        ('bootloader = "V816.0.6.0.TKHCNXM"', 'bootloader = android.os.Build.BOOTLOADER'),
        ('fingerprint = "Redmi/alioth/alioth:13/TKQ1.221114.001/V816.0.6.0.TKHCNXM:user/release-keys"',
         'fingerprint = android.os.Build.FINGERPRINT'),
        ('innerVersion = "V816.0.6.0.TKHCNXM"', 'innerVersion = android.os.Build.DISPLAY'),
    ]
    for before, after in changes:
        if before not in original:
            raise RuntimeError('Acidify Android TLV source changed: ' + before)
        original = original.replace(before, after)
    start = original.index('                procVersion = ')
    end = original.index('\n', start)
    original = original[:start] + '                procVersion = System.getProperty("os.version").orEmpty(),' + original[end:]
    tlv.write_text(original)


def prepare_sources():
    lock = json.loads((ROOT / 'login-dependencies.json').read_text())
    source = ROOT / 'third_party' / 'acidify'
    if source.is_dir():
        return source
    archive = ROOT / 'cache' / 'login-deps' / 'acidify-core-jvm-1.6.3-sources.jar'
    if not archive.exists():
        archive.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(lock['source']['url'], timeout=45) as response:
            archive.write_bytes(response.read())
    if hashlib.sha256(archive.read_bytes()).hexdigest() != lock['source']['sha256']:
        raise RuntimeError('Acidify source checksum mismatch')
    source.mkdir(parents=True)
    with zipfile.ZipFile(archive) as jar:
        for name in jar.namelist():
            if name.endswith('.kt'):
                path = source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(jar.read(name))
    # Handle SMS requested immediately after a password attempt, in addition to
    # the upstream paths where it follows a captcha or device-lock challenge.
    login = source / 'commonMain/org/ntqqrev/acidify/LoginOps.kt'
    original = login.read_text()
    anchor = '    if (result.state == 239u.toUByte()) { // Device lock via SMS code'
    sms = '''    if (result.state == 160u.toUByte()) { // Direct SMS challenge
        result.tlvPack[0x104u]?.let { sessionStore.state.tlv104 = it }
        result.tlvPack[0x174u]?.let { sessionStore.state.tlv174 = it }
        val (countryCode, phone, smsUrl) = result.readSmsInfo()
        result = handleSms(countryCode, phone, smsUrl)
    }

'''
    if anchor not in original:
        raise RuntimeError('Acidify Android login source changed')
    login.write_text(original.replace(anchor, sms + anchor))
    report = source / 'commonMain/org/ntqqrev/acidify/internal/service/system/AndroidInfoSync.kt'
    original = report.read_text()
    for before, after in [('devType = "alioth"', 'devType = android.os.Build.DEVICE'),
                          ('osVer = "13"', 'osVer = android.os.Build.VERSION.RELEASE'),
                          ('brand = "MIUI"', 'brand = android.os.Build.BRAND'),
                          ('vendorOsName = "V816"', 'vendorOsName = android.os.Build.DISPLAY')]:
        if before not in original:
            raise RuntimeError('Acidify Android device report source changed')
        original = original.replace(before, after)
    report.write_text(original)
    patch_android_device(source)
    (source / 'LICENSE').write_bytes((ROOT / 'LICENSE').read_bytes())
    return source


def rebuild(dependencies, android):
    source = prepare_sources()
    sources = sorted(source.rglob('*.kt'))
    compiler = dependencies['compiler']
    plugin = next(p for p in compiler if 'kotlin-serialization-compiler-plugin-embeddable' in p.name)
    runtime = [p for p in dependencies['runtime'] if p.name != 'acidify-core-jvm-1.6.3.jar']
    xmlutil = next(p for p in runtime if '/xmlutil/core-jvm/' in str(p))
    patched_xml = ROOT / 'cache/xmlutil-android.jar'
    patch_source = ROOT / 'tools/BackportXmlutil.java'
    patch_digest = hashlib.sha256(xmlutil.read_bytes() + patch_source.read_bytes()).hexdigest()
    patch_stamp = patched_xml.with_suffix('.sha256')
    if not patched_xml.exists() or not patch_stamp.exists() or patch_stamp.read_text() != patch_digest:
        patch_classes = ROOT / 'cache/backport-classes'
        patch_classes.mkdir(parents=True, exist_ok=True)
        classpath = os.pathsep.join(map(str, compiler))
        subprocess.run(['java', 'com.sun.tools.javac.Main', '--release', '8', '-cp', classpath,
                        '-d', str(patch_classes), str(patch_source)], check=True)
        subprocess.run(['java', '-cp', str(patch_classes) + os.pathsep + classpath, 'BackportXmlutil',
                        str(xmlutil), str(patched_xml)], check=True)
        patch_stamp.write_text(patch_digest)
    runtime = [patched_xml if p == xmlutil else p for p in runtime]
    digest = hashlib.sha256()
    digest.update(Path(__file__).read_bytes())
    for path in [*sources, *compiler, *runtime]:
        digest.update(path.read_bytes())
    version = digest.hexdigest()
    jar = ROOT / 'cache' / 'acidify-android.jar'
    stamp = jar.with_suffix('.sha256')
    if not jar.exists() or not stamp.exists() or stamp.read_text() != version:
        # Only the device report needs Android types. Keep the Java boot API at
        # release 8 so Android-35 List.removeLast cannot shadow Kotlin's API-26
        # compatible collection extensions inside the shared protocol source.
        platform = ROOT / 'cache' / 'android-build-platform.jar'
        with zipfile.ZipFile(android) as sdk, zipfile.ZipFile(platform, 'w') as out:
            for name in sdk.namelist():
                if name == 'android/os/Build.class' or name.startswith('android/os/Build$'):
                    out.writestr(name, sdk.read(name))
        common = [p for p in sources if '/commonMain/' in str(p)]
        command = ['java', '-Xmx2g', '-cp', os.pathsep.join(map(str, compiler)),
                   'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '1.8',
                   '-Xjdk-release=8',
                   '-Xmulti-platform', '-Xexpect-actual-classes', '-Xcommon-sources=' + ','.join(map(str, common)),
                   '-Xconsistent-data-class-copy-visibility', '-Xplugin=' + str(plugin),
                   '-opt-in=kotlin.js.ExperimentalJsExport', '-opt-in=kotlin.js.ExperimentalJsStatic',
                   '-opt-in=kotlinx.serialization.ExperimentalSerializationApi',
                   '-classpath', os.pathsep.join(map(str, [platform, *runtime])), '-d', str(jar), *map(str, sources)]
        subprocess.run(command, check=True, cwd=ROOT)
        stamp.write_text(version)
    return [*runtime, jar]
