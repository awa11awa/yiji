#!/usr/bin/env python3
"""Package the verified source tree, excluding local signing/account/build data."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
report = json.loads((ROOT / 'validation.json').read_text())
version = report['version']
apk = ROOT / 'deliverables' / f'qq-local-probe-v{version}.apk'
assert hashlib.sha256(apk.read_bytes()).hexdigest() == report['sha256']
directories = ['src', 'src-kotlin', 'bridge', 'compile-stubs', 'res', 'tests', 'tools', 'evidence', 'third_party']
files = [ROOT / name for name in ['AndroidManifest.xml', 'build.py', 'README.md', 'LICENSE',
                                 'THIRD_PARTY_NOTICES.md', 'login-dependencies.json', 'validation.json']]
for name in directories:
    files.extend(p for p in (ROOT / name).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
output = ROOT / 'deliverables' / f'qq-local-probe-v{version}-source.zip'
manifest = {'version': version, 'apkSha256': report['sha256'], 'files': {}}
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(files):
        relative = path.relative_to(ROOT).as_posix()
        assert path.suffix not in ('.p12', '.jks', '.apk', '.so', '.dex', '.class', '.pyc')
        archive.write(path, 'qq-local-probe/' + relative)
        manifest['files'][relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    archive.writestr('qq-local-probe/source-manifest.json', json.dumps(manifest, indent=2) + '\n')
with zipfile.ZipFile(output) as archive:
    assert archive.testzip() is None
print(json.dumps({'source': str(output), 'bytes': output.stat().st_size,
                  'files': len(files), 'sha256': hashlib.sha256(output.read_bytes()).hexdigest()}, indent=2))
