"""Resume the saved CHAT/SCHEDULE TinyBERT checkpoint and export the selected model.

Real event groups stay in their original split. Epoch/threshold selection uses
validation only. Test data is evaluated after selection, never for tuning.
"""
import argparse
import json
import random
import re
import shutil
import time

import offline_runtime

import numpy as np
import onnx
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic
from safetensors.torch import save_file
from transformers import AutoTokenizer, BertConfig, BertModel, get_linear_schedule_with_warmup

from common import BASE_MODEL, ROOT, file_sha256, save_json
from build_campus_confusions import build_data as build_confusions
from build_general_messages import build_data as build_general
from evaluate_campus_gate import ASSET, GateRunner, load_rows, summarize
from train_gate import Gate
from train_gate_v212 import build_data as build_replay


def concise(result):
    return {k: v for k, v in result.items() if k != 'errors'}


def f2(result):
    p, r = result['schedule_precision'], result['schedule_recall']
    return 5*p*r/max(1e-9, 4*p+r)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--epochs', type=int, default=4)
    parser.add_argument('--threads', type=int, default=5)
    args = parser.parse_args()
    seed = 20261028
    random.seed(seed)
    torch.manual_seed(seed)
    torch.set_num_threads(args.threads)
    rng = random.Random(seed)
    output = ROOT / 'outputs/message-v2.2.1'
    output.mkdir(parents=True, exist_ok=True)
    baseline = output / 'baseline.int8.onnx'
    if not baseline.exists():
        shutil.copy2(ASSET / 'model.int8.onnx', baseline)
    metadata = json.loads((ASSET / 'metadata.json').read_text())
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL, local_files_only=True)
    config = BertConfig.from_pretrained(BASE_MODEL, local_files_only=True)
    config._attn_implementation = 'eager'
    model = Gate(BertModel(config, add_pooling_layer=False))
    model.load_state_dict(torch.load(output / 'resume_weights.pt', weights_only=True))
    replay, confusion, general = build_replay(), build_confusions(), build_general()
    real = {s: load_rows(ROOT / f'data/campus-v2.2.1/{s}.jsonl') for s in ('train', 'valid', 'test')}
    for a, b in [('train', 'valid'), ('train', 'test'), ('valid', 'test')]:
        assert not ({r['family'] for r in real[a]} & {r['family'] for r in real[b]})
    positive = ['15:30', '会议时间另行通知', '改到周五，地点不变', '原来的活动取消了',
                '明天14点到301集合，带学生证', 'C语言实验报告，明晚20:00前提交',
                '原定体测调整，具体方案另行通知', '广告设计会议定于明天14:00在图书馆举行，请勿泄露验证码。']
    negative = ['123', '000000', '20261019', '。。。。。。', '收到，谢谢。', '作业写完了，太开心了。',
                '晚饭吃什么', '请问实验报告怎么提交', '你有3条新消息', '谢谢老师，我已经提交了']
    anchors = [dict(text=t, label=label, source='known_runtime_regression')
               for label, texts in [(1, positive), (0, negative)] for t in texts]
    train = rng.sample(replay['train'], 5000) + confusion['train'] + general['train'] + real['train']*12 + anchors*8
    valid_sets = dict(replay=replay['valid'], confusion=confusion['valid'], general=general['valid'], real=real['valid'])
    held_out = replay['valid']+replay['test']+confusion['valid']+confusion['test']+general['valid']+general['test']+real['valid']+real['test']
    assert not ({r['text'] for r in train} & {r['text'] for r in held_out})

    def encode_training(rows):
        result = []
        for row in rows:
            pieces = tokenizer.encode(row['text'], add_special_tokens=False)
            starts = [0]
            if row['source'] == 'user_notice_redacted' and len(pieces) > 382:
                starts = list(range(0, len(pieces), 286))
            for start in starts:
                ids = [101] + pieces[start:start+382] + [102]
                result.append(dict(input_ids=ids, attention_mask=[1]*len(ids), token_type_ids=[0]*len(ids), label=row['label']))
        return result

    def batches(rows, training=False):
        rows = list(rows)
        if training:
            rng.shuffle(rows)
        groups = []
        for pos in range(0, len(rows), 512):
            group = sorted(rows[pos:pos+512], key=lambda r: len(r['input_ids']))
            groups += [group[i:i+24] for i in range(0, len(group), 24)]
        if training:
            rng.shuffle(groups)
        for group in groups:
            batch = tokenizer.pad([{k:v for k,v in r.items() if k != 'label'} for r in group], return_tensors='pt')
            yield batch, torch.tensor([r['label'] for r in group])

    @torch.inference_mode()
    def piece_scores(sequences):
        result = np.zeros(len(sequences))
        order = sorted(range(len(sequences)), key=lambda i: len(sequences[i]))
        for pos in range(0, len(order), 32):
            indices = order[pos:pos+32]
            rows = []
            for i in indices:
                ids = [101]+sequences[i]+[102]
                rows.append(dict(input_ids=ids, attention_mask=[1]*len(ids), token_type_ids=[0]*len(ids)))
            batch = tokenizer.pad(rows, return_tensors='pt')
            result[indices] = model(**batch).softmax(-1)[:,1].numpy()
        return result

    @torch.inference_mode()
    def scores(rows):
        # For all chat thresholds >= .5 this has the same decisions as the
        # shipped overlapping windows and complete-sentence fallback.
        model.eval()
        sequences, owners, long_rows = [], [], []
        for i, row in enumerate(rows):
            pieces = tokenizer.encode(row['text'], add_special_tokens=False)
            if len(pieces) > 384:
                long_rows.append(i)
            start = 0
            while start < len(pieces):
                end = min(start+382, len(pieces))
                sequences.append(pieces[start:end]); owners.append(i)
                if end == len(pieces):
                    break
                start = end-96
        result = np.zeros(len(rows))
        np.maximum.at(result, owners, piece_scores(sequences))
        fallback, fallback_owners = [], []
        for i in long_rows:
            if result[i] > .5:
                continue
            for sentence in dict.fromkeys(s.strip() for s in re.split(r'(?<=[。！？!?；;])|\n+', rows[i]['text'])):
                pieces = tokenizer.encode(sentence, add_special_tokens=False)
                if 4 <= len(pieces) < 383:
                    fallback.append(pieces); fallback_owners.append(i)
        if fallback:
            np.maximum.at(result, fallback_owners, piece_scores(fallback))
        return result.tolist()

    encoded_train = encode_training(train)
    old_runner = GateRunner(baseline, metadata, args.threads)
    report = dict(version='2.2.1', task='binary_message_routing', labels=['CHAT','SCHEDULE'], seed=seed,
        base_model=metadata['base_model'], base_revision=metadata['base_revision'],
        baseline_sha256=file_sha256(baseline), resume_sha256=file_sha256(output/'resume_weights.pt'),
        previous_training=json.loads((output/'previous_training_report.json').read_text()),
        optimizer_resumed=False, optimizer_note='Checkpoint contained weights only; AdamW and scheduler restart for continued fine-tuning.',
        real_rows={s:len(v) for s,v in real.items()}, synthetic_general_rows={s:len(v) for s,v in general.items()},
        synthetic_confusion_rows={s:len(v) for s,v in confusion.items()}, replay_training_rows=5000,
        real_training_repeat=12, known_training_anchors=len(anchors), training_rows_after_windowing=len(encoded_train),
        torch_version=torch.__version__,
        selection='Epoch and threshold selected only on original validation groups and disjoint synthetic wording; tests evaluated once after selection.',
        limitation='Real notices are positive-only, small and assistant-labelled. Synthetic scores are not real QQ/WeChat accuracy or cloud extraction accuracy.',
        data_sha256={f'{name}/{s}':file_sha256(ROOT/f'data/{folder}/{s}.jsonl')
            for name,folder in [('real','campus-v2.2.1'),('confusion','confusions-v2.2.1'),('general','general-v2.2.1'),('replay','gate-v2.1.2')]
            for s in ('train','valid','test')}, history=[])
    optimizer = torch.optim.AdamW([{'params':model.bert.parameters(),'lr':8e-6},
                                   {'params':model.classifier.parameters(),'lr':2.4e-5}], weight_decay=.01)
    steps = sum(1 for _ in batches(encoded_train))*args.epochs
    scheduler = get_linear_schedule_with_warmup(optimizer, max(1,steps//12), steps)
    started = time.monotonic()

    def validate(epoch, loss):
        values = {name:scores(rows) for name,rows in valid_sets.items()}
        options = []
        for threshold in np.linspace(.5,.96,47):
            results = {name:summarize(rows,values[name],float(threshold)) for name,rows in valid_sets.items()}
            score = .4*results['real']['schedule_recall']+.3*f2(results['replay'])+.2*f2(results['confusion'])+.1*f2(results['general'])
            options.append((score,-float(threshold),results))
        score, neg_threshold, results = max(options, key=lambda x:x[:2])
        row = dict(epoch=epoch,loss=loss,threshold=-neg_threshold,selection_score=score,
                   validation={k:concise(v) for k,v in results.items()},seconds=round(time.monotonic()-started,1))
        print(json.dumps(row),flush=True)
        report['history'].append(row)
        return row

    initial = validate(0, None)
    best = initial['selection_score']
    report.update(best_continuation_epoch=0,chat_ignore_threshold=initial['threshold'])
    torch.save(model.state_dict(),output/'weights.pt')
    for epoch in range(1,args.epochs+1):
        model.train(); losses=[]
        for batch,target in batches(encoded_train,True):
            optimizer.zero_grad(set_to_none=True)
            loss=torch.nn.functional.cross_entropy(model(**batch),target,label_smoothing=.01)
            loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),1.)
            optimizer.step();scheduler.step();losses.append(float(loss.detach()))
        row=validate(epoch,float(np.mean(losses)))
        if row['selection_score']>best:
            best=row['selection_score'];torch.save(model.state_dict(),output/'weights.pt')
            report.update(best_continuation_epoch=epoch,chat_ignore_threshold=row['threshold'])
        save_json(output/'training_report.json',report)
    model.load_state_dict(torch.load(output/'weights.pt',weights_only=True));model.eval()
    save_file({k:v.contiguous() for k,v in model.state_dict().items()},output/'weights.safetensors')
    sample=tokenizer('明天下午两点开会',return_tensors='pt')
    fp32,quant=output/'model.fp32.onnx',output/'model.int8.onnx'
    with torch.no_grad():
        torch.onnx.export(model,tuple(sample[k] for k in ('input_ids','attention_mask','token_type_ids')),str(fp32),
            input_names=['input_ids','attention_mask','token_type_ids'],output_names=['logits'],opset_version=17,dynamo=False,
            dynamic_axes={k:{0:'batch',1:'sequence'} for k in ('input_ids','attention_mask','token_type_ids')}|{'logits':{0:'batch'}})
    quantize_dynamic(str(fp32),str(quant),per_channel=True,weight_type=QuantType.QUInt8,op_types_to_quantize=['MatMul','Gather'])
    graph=onnx.load(quant);graph.ir_version=min(graph.ir_version,8);onnx.checker.check_model(graph);onnx.save(graph,quant)
    new_meta=dict(metadata,version='2.2.1',model_sha256=file_sha256(quant),
        chat_ignore_threshold=report['chat_ignore_threshold'],training_report='message_bert_training_report_v2.2.1.json')
    new_runner=GateRunner(quant,new_meta,args.threads)
    report['evaluation']={}
    evaluation_sets={f'real_{s}':rows for s,rows in real.items()}
    evaluation_sets.update(synthetic_replay_test=replay['test'],synthetic_confusion_test=confusion['test'],synthetic_general_test=general['test'])
    for name,rows in evaluation_sets.items():
        results={}
        for label,runner in [('baseline',old_runner),('new',new_runner)]:
            results[label]=summarize(rows,[runner.score(r['text']) for r in rows],runner.metadata['chat_ignore_threshold'])
            # Keep private source text out of the public report.
            if name.startswith('real_'):
                results[label]['errors']=[{k:v for k,v in e.items() if k!='text'} for e in results[label]['errors']]
        report['evaluation'][name]=results
        print(name,json.dumps({k:concise(v) for k,v in results.items()}),flush=True)
    ev=report['evaluation'];old=ev['synthetic_replay_test']['baseline'];new=ev['synthetic_replay_test']['new']
    report.update(model_sha256=file_sha256(quant),bytes=quant.stat().st_size,seconds=round(time.monotonic()-started,1))
    report['accepted']=(ev['real_valid']['new']['schedule_recall']>=ev['real_valid']['baseline']['schedule_recall']
        and ev['real_test']['new']['schedule_recall']>=ev['real_test']['baseline']['schedule_recall']
        and new['schedule_precision']>=old['schedule_precision']-.01
        and new['schedule_recall']>=old['schedule_recall']-.01
        and ev['synthetic_confusion_test']['new']['accuracy']>=ev['synthetic_confusion_test']['baseline']['accuracy']
        and ev['synthetic_general_test']['new']['schedule_recall']>=ev['synthetic_general_test']['baseline']['schedule_recall'])
    save_json(ROOT/'message_bert_training_report_v2.2.1.json',report)
    save_json(output/'training_report.json',report);save_json(output/'metadata.json',new_meta)
    if not report['accepted']:
        raise RuntimeError('Candidate failed replacement criteria; baseline assets retained. Report is complete.')
    shutil.copy2(quant,ASSET/'model.int8.onnx');save_json(ASSET/'metadata.json',new_meta)
    print(json.dumps(dict(accepted=True,sha256=report['model_sha256'],bytes=report['bytes'])),flush=True)


if __name__=='__main__':
    main()
