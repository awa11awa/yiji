"""Select a checkpoint on INT8 validation data, preserving the released threshold.

Weight averaging retains the previous classifier's conversational features
without adding another model or any runtime inference cost.
"""
import argparse
import json
import shutil
import time

import offline_runtime

import onnx
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic
from safetensors.torch import save_file
from transformers import AutoTokenizer, BertConfig, BertModel

from common import BASE_MODEL, ROOT, file_sha256, save_json
from evaluate_campus_gate import ASSET, GateRunner, load_rows, summarize
from recover_gate_weights import recover
from train_gate import Gate
from train_message_bert_v221 import concise, f2


def export(model, tokenizer, fp32, quant):
    sample = tokenizer('明天下午两点开会', return_tensors='pt')
    with torch.no_grad():
        torch.onnx.export(model, tuple(sample[k] for k in ('input_ids','attention_mask','token_type_ids')), str(fp32),
            input_names=['input_ids','attention_mask','token_type_ids'], output_names=['logits'], opset_version=17, dynamo=False,
            dynamic_axes={k:{0:'batch',1:'sequence'} for k in ('input_ids','attention_mask','token_type_ids')}|{'logits':{0:'batch'}})
    quantize_dynamic(str(fp32), str(quant), per_channel=True, weight_type=QuantType.QUInt8, op_types_to_quantize=['MatMul','Gather'])
    graph = onnx.load(quant); graph.ir_version=min(graph.ir_version,8); onnx.checker.check_model(graph); onnx.save(graph,quant)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--threads', type=int, default=6)
    args = parser.parse_args()
    torch.set_num_threads(args.threads)
    started = time.monotonic()
    output = ROOT / 'outputs/message-v2.2.1'
    report = json.loads((output/'continuation_training_report.json').read_text())
    metadata = json.loads((output/'baseline.metadata.json').read_text())
    baseline = output/'baseline.int8.onnx'
    old_runner = GateRunner(baseline, metadata, args.threads)
    folders = dict(replay='gate-v2.1.2', confusion='confusions-v2.2.1', general='general-v2.2.1', real='campus-v2.2.1')
    validation = {name:load_rows(ROOT/f'data/{folder}/valid.jsonl') for name,folder in folders.items()}
    old_validation = {name:concise(summarize(rows, [old_runner.score(r['text']) for r in rows], metadata['chat_ignore_threshold']))
                      for name,rows in validation.items()}
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL, local_files_only=True)
    config = BertConfig.from_pretrained(BASE_MODEL, local_files_only=True)
    config._attn_implementation = 'eager'
    model = Gate(BertModel(config, add_pooling_layer=False)); model.eval()
    recovery = recover(model, baseline)
    original = {k:v.clone() for k,v in model.state_dict().items()}
    checkpoints = {name:torch.load(output/path,weights_only=True) for name,path in
                   [('previous_checkpoint','resume_weights.pt'),('continued','continued_weights.pt')]}
    history, best = [], None
    for source,alpha in [('previous_checkpoint',1.),('continued',1.),('continued',.75),('continued',.5),('continued',.25)]:
        state = {k:original[k].lerp(checkpoints[source][k],alpha) for k in original}
        model.load_state_dict(state)
        candidate_fp32, candidate_quant = output/'candidate.fp32.onnx', output/'candidate.int8.onnx'
        export(model,tokenizer,candidate_fp32,candidate_quant)
        # Use the exported graph for selection: FP32 decisions can change after quantization.
        runner = GateRunner(candidate_quant,dict(metadata,chat_ignore_threshold=.5),args.threads)
        scores = {name:[runner.score(r['text']) for r in rows] for name,rows in validation.items()}
        choices = []
        for threshold in [metadata['chat_ignore_threshold']]:
            results = {name:summarize(rows, scores[name], float(threshold)) for name,rows in validation.items()}
            eligible = (results['replay']['schedule_precision'] >= old_validation['replay']['schedule_precision']-.01
                and results['replay']['schedule_recall'] >= old_validation['replay']['schedule_recall']-.01
                and results['real']['schedule_recall'] >= old_validation['real']['schedule_recall']
                and results['confusion']['accuracy'] >= old_validation['confusion']['accuracy']
                and results['general']['schedule_recall'] >= old_validation['general']['schedule_recall'])
            score = .4*results['real']['schedule_recall']+.3*f2(results['replay'])+.2*f2(results['confusion'])+.1*f2(results['general'])
            choices.append((eligible, score, -float(threshold), results))
        eligible, score, neg_threshold, results = max(choices, key=lambda x:x[:3])
        row = dict(checkpoint=source, checkpoint_weight=alpha, threshold=-neg_threshold, eligible=eligible, score=score,
                   validation={k:concise(v) for k,v in results.items()})
        history.append(row); print(json.dumps(row), flush=True)
        if eligible and (best is None or score > best['score']):
            best = row
            torch.save(state,output/'weights.pt')
            shutil.copy2(candidate_fp32,output/'model.fp32.onnx');shutil.copy2(candidate_quant,output/'model.int8.onnx')
    if best is None:
        save_json(output/'weight_selection.json',dict(baseline_validation=old_validation, history=history, selected=best))
        raise RuntimeError('Validation did not select an improved continued model')
    alpha = best['checkpoint_weight']
    model.load_state_dict(torch.load(output/'weights.pt',weights_only=True))
    save_file({k:v.contiguous() for k,v in model.state_dict().items()}, output/'weights.safetensors')
    fp32, quant = output/'model.fp32.onnx', output/'model.int8.onnx'
    new_metadata = dict(metadata, version='2.2.1', model_sha256=file_sha256(quant), chat_ignore_threshold=best['threshold'],
                        training_report='message_bert_training_report_v2.2.1.json')
    new_runner = GateRunner(quant, new_metadata, args.threads)
    report['selection'] = 'Stopped continuation after epoch 2. Selected checkpoint using exported INT8 validation scores and retained the released chat threshold. Held-out results are replacement checks, not a threshold search.'
    report['finalization'] = dict(method='int8_validation_selection', recovery=recovery, checkpoint=best['checkpoint'], checkpoint_weight=alpha,
        continued_weights_sha256=file_sha256(output/'continued_weights.pt'), baseline_validation=old_validation,
        history=history, selected=best, note='One final graph; no runtime ensemble or additional model.')
    report['chat_ignore_threshold'] = best['threshold']
    report['evaluation'] = {}
    sets = {f'real_{s}':load_rows(ROOT/f'data/campus-v2.2.1/{s}.jsonl') for s in ('train','valid','test')}
    sets.update({f'synthetic_{name}_test':load_rows(ROOT/f'data/{folders[name]}/test.jsonl') for name in ('replay','confusion','general')})
    for name,rows in sets.items():
        results = {}
        for label,runner in [('baseline',old_runner),('new',new_runner)]:
            results[label] = summarize(rows, [runner.score(r['text']) for r in rows], runner.metadata['chat_ignore_threshold'])
            if name.startswith('real_'):
                results[label]['errors'] = [{k:v for k,v in e.items() if k!='text'} for e in results[label]['errors']]
        report['evaluation'][name] = results
        print(name, json.dumps({k:concise(v) for k,v in results.items()}), flush=True)
    ev = report['evaluation']; old = ev['synthetic_replay_test']['baseline']; new = ev['synthetic_replay_test']['new']
    report['accepted'] = (ev['real_valid']['new']['schedule_recall'] >= ev['real_valid']['baseline']['schedule_recall']
        and ev['real_test']['new']['schedule_recall'] >= ev['real_test']['baseline']['schedule_recall']
        and new['schedule_precision'] >= old['schedule_precision']-.01
        and new['schedule_recall'] >= old['schedule_recall']-.01
        and ev['synthetic_confusion_test']['new']['accuracy'] >= ev['synthetic_confusion_test']['baseline']['accuracy']
        and ev['synthetic_general_test']['new']['schedule_recall'] >= ev['synthetic_general_test']['baseline']['schedule_recall'])
    report.update(model_sha256=file_sha256(quant), bytes=quant.stat().st_size, finalization_seconds=round(time.monotonic()-started,1))
    save_json(ROOT/'message_bert_training_report_v2.2.1.json', report)
    save_json(output/'training_report.json', report); save_json(output/'metadata.json', new_metadata)
    if not report['accepted']:
        raise RuntimeError('Selected model failed replacement criteria; baseline assets retained')
    shutil.copy2(quant, ASSET/'model.int8.onnx'); save_json(ASSET/'metadata.json',new_metadata)
    print(json.dumps(dict(accepted=True, checkpoint=best['checkpoint'],checkpoint_weight=alpha,sha256=report['model_sha256'])),flush=True)


if __name__ == '__main__':
    main()
