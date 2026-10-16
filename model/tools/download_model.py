"""Download the pinned official Chinese TinyBERT, verifying every source file."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import shutil
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "huawei-noah/TinyBERT_4L_zh"
REVISION = "152bf15d86715b41dd89c1e03cf5664963d9b005"
FILES = {
    "config.json": {"size": 430, "git_blob_sha1": "fb007a408e0e1313d5eec9156514e07509c55fa9"},
    "vocab.txt": {"size": 109534, "git_blob_sha1": "66b6d20eebda7da8499e68ef9b1980990c0042cc"},
    "pytorch_model.bin": {"size": 47138484, "sha256": "b4aec782e4a56727bb6ba1dcac8e6c3319460e58e11254f7e43a2dccee0c19c1"},
}


def verify(path: Path, expected: dict) -> dict:
    payload = path.read_bytes()
    sha256 = hashlib.sha256(payload).hexdigest()
    blob_sha1 = hashlib.sha1(f"blob {len(payload)}\0".encode() + payload).hexdigest()
    if len(payload) != expected["size"]:
        raise ValueError(f"Size mismatch: {path.name}")
    if expected.get("sha256", sha256) != sha256:
        raise ValueError(f"SHA256 mismatch: {path.name}")
    if expected.get("git_blob_sha1", blob_sha1) != blob_sha1:
        raise ValueError(f"Git blob SHA1 mismatch: {path.name}")
    return {"size": len(payload), "sha256": sha256, "git_blob_sha1": blob_sha1}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "models" / "tinybert-zh-original")
    parser.add_argument("--endpoint", default="https://huggingface.co", help="An optional mirror; official hashes are always checked")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {"repository": REPOSITORY, "revision": REVISION, "verified_at": datetime.now(timezone.utc).isoformat(), "files": {}}
    for name, expected in FILES.items():
        destination = args.output / name
        url = f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/{name}"
        download_url = f"{args.endpoint.rstrip('/')}/{REPOSITORY}/resolve/{REVISION}/{name}"
        if not destination.exists():
            partial = destination.with_name(name + ".partial")
            print(f"Downloading {name} ({expected['size']:,} bytes)", flush=True)
            subprocess.run([shutil.which("curl") or "curl.exe", "--fail", "--silent", "--show-error", "--location", "--http1.1", "--continue-at", "-", "--retry", "2", "--retry-all-errors", "--connect-timeout", "20", "--max-time", "1200", "--output", str(partial), download_url], check=True)
            verify(partial, expected)
            partial.replace(destination)
        manifest["files"][name] = {"url": url, **verify(destination, expected)}
        print(f"Verified {name}", flush=True)
    (args.output / "source_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Official model downloaded and verified: {args.output}")


if __name__ == "__main__":
    main()
