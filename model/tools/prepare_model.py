"""Convert verified original TinyBERT weights to a local Transformers model."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil

from common import ROOT, BASE_MODEL, save_json
from download_model import FILES, verify


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT / "models" / "tinybert-zh-original")
    parser.add_argument("--output", type=Path, default=BASE_MODEL)
    args = parser.parse_args()
    for name, expected in FILES.items():
        verify(args.source / name, expected)
    import torch
    from transformers import BertConfig, BertModel, BertTokenizerFast

    config = BertConfig.from_dict(json.loads((args.source / "config.json").read_text(encoding="utf-8")))
    config.model_type = "bert"
    config.architectures = ["BertModel"]
    model = BertModel(config)
    raw = torch.load(args.source / "pytorch_model.bin", map_location="cpu", weights_only=True)
    if "state_dict" in raw:
        raw = raw["state_dict"]
    expected_keys = set(model.state_dict())
    state, unused = {}, []
    for original_key, tensor in raw.items():
        key = original_key.removeprefix("module.").removeprefix("bert.")
        key = key.replace(".gamma", ".weight").replace(".beta", ".bias")
        if key in expected_keys:
            if key in state:
                raise ValueError(f"Duplicate converted parameter: {key}")
            state[key] = tensor
        else:
            # Fit layers and pretraining heads are not part of the BERT encoder.
            if not key.startswith(("fit_dense.", "fit_denses.", "cls.", "classifier.")):
                raise ValueError(f"Unrecognized original parameter: {original_key}")
            unused.append(original_key)
    model.load_state_dict(state, strict=True)
    model.eval()
    tokenizer = BertTokenizerFast(vocab_file=str(args.source / "vocab.txt"), do_lower_case=True, model_max_length=512)
    assert len(tokenizer) == config.vocab_size == 21128
    assert [tokenizer.pad_token_id, tokenizer.unk_token_id, tokenizer.cls_token_id, tokenizer.sep_token_id, tokenizer.mask_token_id] == [0, 100, 101, 102, 103]
    args.output.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(args.output, safe_serialization=True)
    tokenizer.save_pretrained(args.output)
    shutil.copy2(args.source / "source_manifest.json", args.output / "source_manifest.json")
    save_json(args.output / "conversion_report.json", {
        "status": "pretrained_encoder_only_not_task_finetuned",
        "loaded_encoder_tensors": len(state), "missing_encoder_tensors": [],
        "unused_original_tensors": unused,
        "parameters": sum(p.numel() for p in model.parameters()),
        "tokenizer": "BertTokenizerFast, do_lower_case=True, Chinese character tokenization",
    })
    print(f"Prepared offline base model: {args.output}")
    print(f"Loaded {len(state)} encoder tensors; unused pretraining/distillation tensors: {len(unused)}")


if __name__ == "__main__":
    main()
