"""Restore the pinned deployment graph from source-controlled compressed parts."""
import gzip
import hashlib
import json
from pathlib import Path

root=Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets/tinybert'
metadata=json.loads((assets/'metadata.json').read_text())
manifest=json.loads((root/'model/deployment_manifest.json').read_text())
target=assets/metadata.get('model_file','model.int8.onnx')
expected=metadata['model_sha256']
if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest()!=expected:
    data=gzip.decompress(b''.join((root/name).read_bytes() for name in manifest['parts']))
    if hashlib.sha256(data).hexdigest()!=expected:
        raise SystemExit('Deployment parts do not match the evaluated model')
    target.write_bytes(data)
print('Deployment model ready:',expected)
