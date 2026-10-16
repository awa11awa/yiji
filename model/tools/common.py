import hashlib, json, os
from pathlib import Path
os.environ["HF_HUB_OFFLINE"]="1"
os.environ["TRANSFORMERS_OFFLINE"]="1"
os.environ["TOKENIZERS_PARALLELISM"]="false"
ROOT=Path(__file__).resolve().parents[1]
BASE_MODEL=ROOT/"models/tinybert-zh-base"
def file_sha256(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def save_json(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
