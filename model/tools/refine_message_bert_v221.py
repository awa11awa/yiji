"""Continue training, then select the actual deployment graph on validation only."""
import argparse
import json
import random
import re
import shutil
import time
from pathlib import Path

import offline_runtime

import numpy as np
import onnx
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic
from safetensors.torch import load_file, save_file
from transformers import AutoTokenizer, BertConfig, BertModel, get_linear_schedule_with_warmup

from build_message_supplement import build_data
from common import BASE_MODEL, ROOT, file_sha256, save_json
from evaluate_campus_gate import ASSET, GateRunner, load_rows, summarize
from train_gate import Gate


def concise(result):
    return {k:v for k,v in result.items() if k != 'errors'}


def quality(results, baseline):
    replay, confusion, general, real = (results[k] for k in ('replay','confusion','general','real'))
    reviewed_general=general.get('unambiguous',general)
    accepted = (replay['schedule_recall'] >= max(.98, baseline['replay']['schedule_recall']-.01)
        and replay['schedule_precision'] >= baseline['replay']['schedule_precision']-.01
        and confusion['accuracy'] >= .9 and confusion['schedule_recall'] >= .93
        and reviewed_general['accuracy'] >= .9 and general['schedule_recall'] >= .95
        and real['schedule_recall'] == 1.)
    def f1(m):
        p,r = m['schedule_precision'],m['schedule_recall']
        return 2*p*r/max(1e-9,p+r)
    score = .25*real['schedule_recall']+.3*f1(replay)+.25*f1(confusion)+.2*f1(reviewed_general)
    return accepted,score


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--epochs',type=int,default=3)
    parser.add_argument('--threads',type=int,default=5)
    parser.add_argument('--contrastive',action='store_true')
    parser.add_argument('--replay-rows',type=int,default=0)
    parser.add_argument('--resume',type=Path)
    parser.add_argument('--retention',action='store_true')
    parser.add_argument('--encoder-lr',type=float,default=1e-5)
    parser.add_argument('--head-lr',type=float,default=3e-5)
    args=parser.parse_args()
    seed=2026102802
    rng=random.Random(seed);torch.manual_seed(seed);torch.set_num_threads(args.threads)
    previous=ROOT/'outputs/message-v2.2.1'
    output=ROOT/'outputs/refined-v2.2.1';output.mkdir(parents=True,exist_ok=True)
    metadata=json.loads((previous/'baseline.metadata.json').read_text())
    metadata['chat_ignore_threshold']=.5
    tokenizer=AutoTokenizer.from_pretrained(BASE_MODEL,local_files_only=True)
    config=BertConfig.from_pretrained(BASE_MODEL,local_files_only=True);config._attn_implementation='eager'
    model=Gate(BertModel(config,add_pooling_layer=False))
    resume=args.resume or output/'latest_weights.pt'
    if not resume.exists() and args.resume is None:
        resume=previous/'continued_weights.pt'
    model.load_state_dict(load_file(str(resume)) if resume.suffix=='.safetensors' else torch.load(resume,weights_only=True))
    folders=dict(replay='gate-v2.1.2',confusion='confusions-v2.2.1',general='general-v2.2.1',real='campus-v2.2.1')
    data={k:{s:load_rows(ROOT/f'data/{folder}/{s}.jsonl') for s in ('train','valid','test')} for k,folder in folders.items()}
    if args.retention:
        from build_retention_messages import build_data as build_retention
        supplement=build_retention()
    elif args.contrastive:
        from build_contrastive_messages import build_data as build_contrastive
        supplement=build_contrastive()
    else:
        supplement=build_data()
    replay=rng.sample(data['replay']['train'],args.replay_rows) if args.replay_rows else data['replay']['train']
    train=replay+data['confusion']['train']+data['general']['train']+data['real']['train']*16+supplement
    heldout=[r for parts in data.values() for s in ('valid','test') for r in parts[s]]
    assert not ({r['text'] for r in train} & {r['text'] for r in heldout})
    assert not ({r['family'] for r in data['real']['train']} & {r['family'] for s in ('valid','test') for r in data['real'][s]})
    encoded=[]
    for row in train:
        pieces=tokenizer.encode(row['text'],add_special_tokens=False)
        starts=list(range(0,len(pieces),286)) if len(pieces)>382 else [0]
        for start in starts:
            ids=[101]+pieces[start:start+382]+[102]
            encoded.append(dict(input_ids=ids,attention_mask=[1]*len(ids),token_type_ids=[0]*len(ids),label=row['label']))
        # Runtime may inspect each sentence of a rejected long message. Teach
        # completed/news sentences as well as their opening window.
        if row['label']==0 and len(pieces)>384:
            for sentence in dict.fromkeys(s.strip() for s in re.split(r'(?<=[。！？!?；;])|\n+',row['text'])):
                ids=tokenizer.encode(sentence)
                if 6<=len(ids)<384:
                    encoded.append(dict(input_ids=ids,attention_mask=[1]*len(ids),token_type_ids=[0]*len(ids),label=0))
    def batches():
        rows=list(encoded);rng.shuffle(rows);groups=[]
        for pos in range(0,len(rows),512):
            block=sorted(rows[pos:pos+512],key=lambda r:len(r['input_ids']))
            groups.extend(block[i:i+24] for i in range(0,len(block),24))
        rng.shuffle(groups)
        for group in groups:
            yield tokenizer.pad([{k:v for k,v in r.items() if k!='label'} for r in group],return_tensors='pt'),torch.tensor([r['label'] for r in group])
    def evaluate(path, splits):
        runner=GateRunner(path,metadata,args.threads)
        results={}
        for k,rows in splits.items():
            scores=[runner.score(r['text']) for r in rows]
            results[k]=concise(summarize(rows,scores,.5))
            # "老师让分析…" can itself be homework. Retain its strict score,
            # but do not force those uncertain labels into CHAT for selection.
            if k=='general' and any(r.get('family')=='valid-0-3' for r in rows):
                certain=[i for i,r in enumerate(rows) if r.get('family')!='valid-0-3']
                results[k]['annotation_uncertain_rows']=len(rows)-len(certain)
                results[k]['unambiguous']=concise(summarize([rows[i] for i in certain],[scores[i] for i in certain],.5))
        return results
    valid={k:v['valid'] for k,v in data.items()}
    baseline=evaluate(previous/'baseline.int8.onnx',valid)
    report=dict(version='2.2.1',task='binary_message_routing',seed=seed,labels=['CHAT','SCHEDULE'],
        initialization_sha256=file_sha256(resume),baseline_sha256=file_sha256(previous/'baseline.int8.onnx'),
        prior_training=json.loads((previous/'continuation_training_report.json').read_text()),
        optimizer_resumed=False,training_rows={k:len(v['train']) for k,v in data.items()},
        supplementary_training_rows=len(supplement),replay_training_rows=len(replay),contrastive=args.contrastive,retention=args.retention,
        real_training_repeat=16,training_windows=len(encoded),
        selection='Deployment graph and epoch selected with fixed threshold 0.5 on validation only; unchanged previously used test sets provide development comparisons, not a new blind benchmark.',
        limitation='Real notices are only 49 positive examples, assistant-labelled and grouped 30/9/10. No real CHAT negatives. Synthetic scores do not establish real-world or cloud-extraction accuracy.',
        data_sha256={f'{k}/{s}':file_sha256(ROOT/f'data/{folders[k]}/{s}.jsonl') for k in folders for s in ('train','valid','test')},
        supplement_sha256=file_sha256(ROOT/f'data/{"retention" if args.retention else "contrastive" if args.contrastive else "supplement"}-v2.2.1/train.jsonl'),
        annotation_review='General validation family valid-0-3 says a teacher asks for grammar analysis, which can itself be an assignment. Strict scores include every row; selection additionally evaluates the unambiguous subset. No test rows relabelled or removed.',
        baseline_validation=baseline,history=[],chat_ignore_threshold=.5,
        versions=dict(torch=str(torch.__version__),onnx=onnx.__version__),learning_rates=dict(encoder=args.encoder_lr,head=args.head_lr))
    if (output/'previous_refinement_report.json').exists():
        report['previous_refinement']=json.loads((output/'previous_refinement_report.json').read_text())
    if args.retention:
        report['development_iteration']='Additional paraphrases target error categories observed in the reused development test sets. Exact validation/test texts remain excluded from training. These benchmarks are not an independent final test.'
    optimizer=torch.optim.AdamW([{'params':model.bert.parameters(),'lr':args.encoder_lr},{'params':model.classifier.parameters(),'lr':args.head_lr}],weight_decay=.01)
    steps=sum(1 for _ in batches())*args.epochs
    scheduler=get_linear_schedule_with_warmup(optimizer,max(1,steps//12),steps)
    progress=output/'retention_progress.pt'
    fingerprint=json.dumps(dict(retention=args.retention,contrastive=args.contrastive,replay=len(replay),supplement=report['supplement_sha256'],epochs=args.epochs,encoder_lr=args.encoder_lr,head_lr=args.head_lr),sort_keys=True)
    resumed=None
    if args.resume is None and progress.exists():
        candidate=torch.load(progress,weights_only=True)
        if candidate['fingerprint']==fingerprint:
            resumed=candidate
            model.load_state_dict(resumed['weights']);optimizer.load_state_dict(resumed['optimizer']);scheduler.load_state_dict(resumed['scheduler'])
            report=resumed['report'];report['optimizer_resumed']=True
            torch.set_rng_state(resumed['torch_rng'])
    def save_progress(epoch,index,epoch_rng,losses):
        state=dict(fingerprint=fingerprint,weights=model.state_dict(),optimizer=optimizer.state_dict(),scheduler=scheduler.state_dict(),
                   epoch=epoch,batch=index,epoch_rng=epoch_rng,torch_rng=torch.get_rng_state(),losses=losses,report=report)
        temporary=output/'retention_progress.tmp.pt';torch.save(state,temporary);temporary.replace(progress)
    sample=tokenizer('明天下午两点开会',return_tensors='pt')
    best=None;started=time.monotonic()
    print(json.dumps(dict(started=True,training_windows=len(encoded),baseline_validation=baseline)),flush=True)
    for epoch in range(resumed['epoch'] if resumed else 1,args.epochs+1):
        if resumed and epoch==resumed['epoch']:
            rng.setstate(resumed['epoch_rng'])
        epoch_rng=rng.getstate()
        model.train();losses=list(resumed['losses']) if resumed and epoch==resumed['epoch'] else []
        for i,(batch,target) in enumerate(batches()):
            if resumed and epoch==resumed['epoch'] and i<=resumed['batch']:
                continue
            optimizer.zero_grad(set_to_none=True)
            loss=torch.nn.functional.cross_entropy(model(**batch),target,label_smoothing=.01)
            loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),1.)
            optimizer.step();scheduler.step();losses.append(float(loss.detach()))
            if i%100==0:
                print(json.dumps(dict(epoch=epoch,batch=i,loss=round(float(np.mean(losses)),5),seconds=round(time.monotonic()-started,1))),flush=True)
            if (i+1)%100==0:
                save_progress(epoch,i,epoch_rng,losses)
        save_progress(epoch,i,epoch_rng,losses)
        resumed=None
        torch.save(model.state_dict(),output/'latest_weights.pt')
        model.eval();fp32=output/'candidate.fp32.onnx'
        with torch.no_grad():
            torch.onnx.export(model,tuple(sample[k] for k in ('input_ids','attention_mask','token_type_ids')),str(fp32),
                input_names=['input_ids','attention_mask','token_type_ids'],output_names=['logits'],opset_version=17,dynamo=False,
                dynamic_axes={k:{0:'batch',1:'sequence'} for k in ('input_ids','attention_mask','token_type_ids')}|{'logits':{0:'batch'}})
        graph=onnx.load(fp32);graph.ir_version=min(graph.ir_version,8);onnx.save(graph,fp32)
        for name,per_channel,reduce_range in [('uint8_channel',True,False),('uint8_tensor',False,False),('uint8_reduced',True,True),('fp32',None,None)]:
            path=fp32 if name=='fp32' else output/(name+'.onnx')
            if name!='fp32':
                quantize_dynamic(str(fp32),str(path),per_channel=per_channel,reduce_range=reduce_range,weight_type=QuantType.QUInt8,op_types_to_quantize=['MatMul','Gather'])
                graph=onnx.load(path);graph.ir_version=min(graph.ir_version,8);onnx.checker.check_model(graph);onnx.save(graph,path)
            results=evaluate(path,valid);eligible,score=quality(results,baseline)
            entry=dict(epoch=epoch,export=name,loss=float(np.mean(losses)),validation=results,eligible=eligible,score=score,bytes=path.stat().st_size)
            report['history'].append(entry);print(json.dumps(entry),flush=True)
            if best is None or (eligible,score)>(best['eligible'],best['score']):
                best=entry;shutil.copyfile(path,output/'selected.onnx');torch.save(model.state_dict(),output/'selected_weights.pt')
                save_file({k:v.contiguous() for k,v in model.state_dict().items()},output/'selected_weights.safetensors')
            save_json(output/'training_report.json',report)
        if best['eligible']:
            break
    report.update(selected=best,accepted=best['eligible'],model_sha256=file_sha256(output/'selected.onnx'),bytes=(output/'selected.onnx').stat().st_size)
    if report['accepted']:
        report['evaluation']={}
        for name,rows in {**{f'real_{s}':data['real'][s] for s in ('train','valid','test')},**{f'synthetic_{k}_test':data[k]['test'] for k in ('replay','confusion','general')}}.items():
            result={}
            for label,path in [('baseline',previous/'baseline.int8.onnx'),('new',output/'selected.onnx')]:
                result[label]=evaluate(path,{name:rows})[name]
            report['evaluation'][name]=result;print(name,json.dumps(result),flush=True)
        ev=report['evaluation'];old=ev['synthetic_replay_test']['baseline'];new=ev['synthetic_replay_test']['new']
        report['accepted']=(ev['real_test']['new']['schedule_recall']>=ev['real_test']['baseline']['schedule_recall']
            and new['schedule_precision']>=old['schedule_precision']-.01 and new['schedule_recall']>=old['schedule_recall']-.01
            and ev['synthetic_confusion_test']['new']['accuracy']>=ev['synthetic_confusion_test']['baseline']['accuracy']
            and ev['synthetic_general_test']['new']['accuracy']>=ev['synthetic_general_test']['baseline']['accuracy'])
    report['seconds']=round(time.monotonic()-started,1)
    save_json(output/'training_report.json',report)
    progress.unlink(missing_ok=True)
    print(json.dumps(dict(finished=True,accepted=report['accepted'],selected=best,seconds=report['seconds'])),flush=True)
    if not report['accepted']:
        raise SystemExit('No deployment candidate met quality requirements; app assets retained.')


if __name__=='__main__':
    main()
