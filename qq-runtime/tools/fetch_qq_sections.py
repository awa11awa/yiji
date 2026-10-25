#!/usr/bin/env python3
"""Fetch selected ZIP entries by HTTP ranges from the pinned QQ specimen.

Internal analysis helper. Source URL and expected DEX hashes are from the
previous full specimen audit. No Tencent files are packaged with the app.
"""
import hashlib
import io
import json
from pathlib import Path
import urllib.request
import zipfile

URL = 'https://imtt2.dd.qq.com/sjy.00008/sjy.00001/16891/apk/5F654F93E51F0E20441A70A7347B6820.apk'
SIZE = 393030322
EXPECTED = {
    'classes.dex': '1fccdd43ce373425b75155fab737193858173f55699f28bea7be523c6876622f',
    'classes2.dex': '9f035a94596546cb6a827592b562c0487cfdbeb9ead1267f56c5143233795d0f',
}


class RangedArchive(io.RawIOBase):
    def __init__(self):
        self.position = 0

    def seekable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=0):
        self.position = offset if whence == 0 else self.position + offset if whence == 1 else SIZE + offset
        return self.position

    def read(self, count=-1):
        start = self.position
        end = SIZE - 1 if count < 0 else min(SIZE - 1, start + count - 1)
        if end < start:
            return b''
        request = urllib.request.Request(URL, headers={
            'Range': f'bytes={start}-{end}', 'User-Agent': 'Mozilla/5.0', 'Referer': 'https://im.qq.com/'})
        with urllib.request.urlopen(request, timeout=40) as response:
            if response.status != 206 or response.headers.get('Content-Range') != f'bytes {start}-{end}/{SIZE}':
                raise RuntimeError('Server did not return the requested specimen range')
            data = response.read()
        if len(data) != end - start + 1:
            raise RuntimeError('Incomplete range')
        self.position += len(data)
        return data


if __name__ == '__main__':
    folder = Path(__file__).resolve().parents[1] / 'cache' / 'qq-sections'
    folder.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(RangedArchive()) as archive:
        (folder / 'entry-names.json').write_text(json.dumps(archive.namelist(), ensure_ascii=False))
        for name in ['classes.dex', 'classes2.dex', 'lib/arm64-v8a/libmmkv.so', 'AndroidManifest.xml', 'resources.arsc']:
            destination = folder / name.replace('/', '_')
            data = archive.read(name)
            digest = hashlib.sha256(data).hexdigest()
            if name in EXPECTED and digest != EXPECTED[name]:
                raise RuntimeError('DEX differs from audited specimen: ' + name)
            destination.write_bytes(data)
            print(json.dumps({'entry': name, 'bytes': len(data), 'sha256': digest}), flush=True)
