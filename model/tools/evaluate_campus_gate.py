"""Evaluate the shipped gate using Android's windowing and sentence fallback."""
import argparse
import json
import re
from pathlib import Path

import offline_runtime

import numpy as np
import onnxruntime as ort
from transformers import BertTokenizerFast

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT.parent / 'app/src/main/assets/tinybert'


def load_rows(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


class GateRunner:
    def __init__(self, model, metadata, threads=4):
        self.metadata = metadata
        self.tokenizer = BertTokenizerFast(vocab_file=str(ASSET / 'vocab.txt'), do_lower_case=True)
        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(model), sess_options=options, providers=['CPUExecutionProvider'])

    def window_score(self, pieces):
        ids = np.asarray([[101, *pieces, 102]], dtype=np.int64)
        logits = self.session.run(None, dict(input_ids=ids, attention_mask=np.ones_like(ids),
                                              token_type_ids=np.zeros_like(ids)))[0][0]
        probs = np.exp(logits - logits.max())
        return float(probs[1] / probs.sum())

    def score(self, text):
        pieces = self.tokenizer.encode(text, add_special_tokens=False)
        if not pieces:
            return 0.0
        length, stride = self.metadata['max_length'], self.metadata['stride']
        score = 0.0
        start = 0
        while start < len(pieces):
            end = min(start + length - 2, len(pieces))
            score = max(score, self.window_score(pieces[start:end]))
            if end == len(pieces):
                break
            start = end - stride
        threshold = 1.0 - self.metadata['chat_ignore_threshold']
        if len(pieces) > length and score <= threshold:
            for sentence in dict.fromkeys(s.strip() for s in re.split(r'(?<=[。！？!?；;])|\n+', text)):
                count = len(self.tokenizer.encode(sentence, add_special_tokens=False))
                if 4 <= count < length - 1:
                    score = max(score, self.score(sentence))
                    if score > threshold:
                        break
        return score


def summarize(rows, scores, threshold):
    labels = np.asarray([r['label'] for r in rows])
    predicted = np.asarray(scores) > 1.0 - threshold
    tp = int(((labels == 1) & predicted).sum())
    tn = int(((labels == 0) & ~predicted).sum())
    fp = int(((labels == 0) & predicted).sum())
    fn = int(((labels == 1) & ~predicted).sum())
    return dict(rows=len(rows), tp=tp, tn=tn, fp=fp, fn=fn,
                schedule_recall=tp / max(1, tp + fn), schedule_precision=tp / max(1, tp + fp),
                accuracy=(tp + tn) / max(1, len(rows)),
                errors=[dict(id=r.get('id', i), label=r['label'], probability=s, text=r['text'])
                        for i, (r, s, p) in enumerate(zip(rows, scores, predicted)) if p != r['label']])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', type=Path, default=ASSET / 'model.int8.onnx')
    parser.add_argument('--metadata', type=Path, default=ASSET / 'metadata.json')
    parser.add_argument('--output', type=Path, default=ROOT / 'campus_baseline_v2.2.1.json')
    args = parser.parse_args()
    metadata = json.loads(args.metadata.read_text())
    runner = GateRunner(args.model, metadata)
    report = dict(model=str(args.model.relative_to(ROOT.parent)) if args.model.is_relative_to(ROOT.parent) else args.model.name,
                  note='Real notices contain positives only: accuracy/precision on this subset are not population accuracy/precision.',
                  splits={})
    for split in ('train', 'valid', 'test'):
        rows = load_rows(ROOT / f'data/campus-v2.2.1/{split}.jsonl')
        scores = [runner.score(r['text']) for r in rows]
        report['splits'][split] = summarize(rows, scores, metadata['chat_ignore_threshold'])
        report['splits'][split]['predictions'] = [dict(id=r['id'], probability=s) for r, s in zip(rows, scores)]
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    for split, result in report['splits'].items():
        print(split, json.dumps({k: v for k,v in result.items() if k != 'predictions'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
