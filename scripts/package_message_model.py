"""Package the same exported message classifier that is bundled in the APK."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    assets = root / 'app/src/main/assets/tinybert'
    metadata = json.loads((assets / 'metadata.json').read_text())
    report_path = root / 'model' / metadata['training_report']
    report = json.loads(report_path.read_text())
    model_file = metadata.get('model_file', 'model.int8.onnx')
    digest = hashlib.sha256((assets / model_file).read_bytes()).hexdigest()
    if not report['accepted'] or digest != metadata['model_sha256'] or digest != report['model_sha256']:
        raise SystemExit('Model and accepted evaluation report do not match')
    if metadata['task'] != 'binary_gate' or metadata['labels'] != ['CHAT', 'SCHEDULE']:
        raise SystemExit('Unexpected message classification contract')
    files = {
        model_file: assets / model_file,
        'metadata.json': assets / 'metadata.json',
        'vocab.txt': assets / 'vocab.txt',
        'MODEL_CARD.md': root / 'model/MODEL_CARD.md',
        'training_report.json': report_path,
        'TinyBERT-CODE-LICENSE': root / 'model/TinyBERT-CODE-LICENSE',
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    checksums = []
    with ZipFile(args.output, 'w', ZIP_DEFLATED) as archive:
        for name, path in files.items():
            content = path.read_bytes()
            archive.writestr(name, content)
            checksums.append(f'{hashlib.sha256(content).hexdigest()}  {name}\n')
        archive.writestr('SHA256SUMS.txt', ''.join(checksums))
    print(f'{args.output.name}: {args.output.stat().st_size} bytes, model SHA-256 {digest}')


if __name__ == '__main__':
    main()
