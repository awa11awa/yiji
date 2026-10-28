"""Fix colloquial check-in routing while freezing the accepted encoder."""
import copy
import json
import random
import time

import offline_runtime
import numpy as np
import onnx
import torch
from safetensors.torch import save_file
from transformers import AutoTokenizer, BertConfig, BertModel

from common import BASE_MODEL, ROOT, file_sha256, save_json
from evaluate_campus_gate import GateRunner, load_rows, summarize
from refine_message_bert_v221 import quality, concise
from train_gate import Gate


def main():
    torch.set_num_threads(5);torch.manual_seed(2026102805);rng=random.Random(2026102805)
    base=ROOT/'outputs/refined-v2.2.1';out=ROOT/'outputs/checkin-head-v2.2.1';out.mkdir(exist_ok=True)
    report=json.loads((base/'training_report.json').read_text());metadata=json.loads((ROOT.parent/'app/src/main/assets/tinybert/metadata.json').read_text())
    folders=dict(replay='gate-v2.1.2',confusion='confusions-v2.2.1',general='general-v2.2.1',real='campus-v2.2.1')
    data={k:{s:load_rows(ROOT/f'data/{folder}/{s}.jsonl') for s in ('train','valid','test')} for k,folder in folders.items()}
    positive=['{event}的卡还没打，记得补上，别漏了。','没打{event}卡的人尽快完成这一步哦。',
        '打{event}卡这事别忘了，还没有完成的要抓紧。','大家的{event}签到还差几个人，请没有登记的补齐。',
        '{event}打卡刚开放，今天要记得去打一下。','还没给{event}签到的，趁现在处理一下。',
        '别把{event}的打卡忘了，未完成的今天补一下。','{event}那个卡没有打完的朋友，快去把它办好。']
    negative=['{event}卡我已经打完了，这条只是说一下结果。','刚完成{event}签到，给你报个完成情况。',
        '{event}的打卡截图我已经留存好了，谢谢关心。','以前那次{event}打卡已经截止了，这只是旧记录。',
        '我昨天已经打了{event}卡，今天不用再做一次。','想问{event}怎么打卡，我只是了解流程。']
    events=['校园跑','晨跑','早操','健康申报','在线学习','课程考勤','志愿活动','实习','上班','安全培训','体质锻炼','实践课程','英语学习','体育锻炼']
    extra=[dict(text=rng.choice(templates).format(event=rng.choice(events)),label=label,source='checkin_head_training')
           for label,templates in [(0,negative),(1,positive)] for _ in range(800)]
    heldout={r['text'] for parts in data.values() for s in ('valid','test') for r in parts[s]}
    assert not ({r['text'] for r in extra} & heldout)
    source=ROOT/'data/checkin-head-v2.2.1/train.jsonl';source.parent.mkdir(exist_ok=True)
    source.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in extra))
    train=rng.sample(data['replay']['train'],4000)+rng.sample(data['confusion']['train'],1000)+data['general']['train']+data['real']['train']*8
    train+=rng.sample(load_rows(ROOT/'data/retention-v2.2.1/train.jsonl'),1600)+extra
    assert not ({r['text'] for r in train} & heldout)
    tokenizer=AutoTokenizer.from_pretrained(BASE_MODEL,local_files_only=True)
    config=BertConfig.from_pretrained(BASE_MODEL,local_files_only=True);config._attn_implementation='eager'
    model=Gate(BertModel(config,add_pooling_layer=False));model.load_state_dict(torch.load(base/'selected_weights.pt',weights_only=True));model.eval()
    for p in model.bert.parameters():p.requires_grad_(False)
    encoded=[dict(tokenizer(r['text'],truncation=True,max_length=384),label=r['label'],distill=r['source']!='checkin_head_training') for r in train]
    order=sorted(range(len(encoded)),key=lambda i:len(encoded[i]['input_ids']))
    features=torch.empty((len(train),624));teacher=torch.empty((len(train),2))
    with torch.inference_mode():
        for start in range(0,len(order),32):
            ids=order[start:start+32]
            batch=tokenizer.pad([{k:v for k,v in encoded[i].items() if k not in ('label','distill')} for i in ids],return_tensors='pt')
            hidden=model.bert(**batch).last_hidden_state;mask=batch['attention_mask'].unsqueeze(-1).to(hidden.dtype)
            pooled=torch.cat([hidden[:,0],(hidden*mask).sum(1)/mask.sum(1).clamp(min=1)],-1)
            features[ids]=pooled;teacher[ids]=model.classifier(pooled)
    labels=torch.tensor([r['label'] for r in train]);distill=torch.tensor([r['source']!='checkin_head_training' for r in train])
    torch.save(dict(features=features,teacher=teacher,labels=labels,distill=distill),out/'cached_features.pt')
    optimizer=torch.optim.AdamW(model.classifier.parameters(),lr=2e-4,weight_decay=.01)
    sample=tokenizer('明天下午两点开会',return_tensors='pt');best=None;history=[];started=time.monotonic()
    valid={k:rows['valid'] for k,rows in data.items()}
    def evaluate(path,rows):
        runner=GateRunner(path,metadata,4);scores=[runner.score(r['text']) for r in rows]
        return concise(summarize(rows,scores,.5)),scores
    for epoch in range(1,5):
        model.classifier.train();losses=[]
        for ids in torch.randperm(len(train)).split(64):
            logits=model.classifier(features[ids]);loss=torch.nn.functional.cross_entropy(logits,labels[ids],label_smoothing=.005)
            old=distill[ids]
            if old.any():
                loss+=.5*torch.nn.functional.kl_div(torch.nn.functional.log_softmax(logits[old]/2,-1),torch.nn.functional.softmax(teacher[ids][old]/2,-1),reduction='batchmean')*4
            optimizer.zero_grad(set_to_none=True);loss.backward();optimizer.step();losses.append(float(loss.detach()))
        model.eval();path=out/f'head-{epoch}.onnx'
        with torch.no_grad():
            torch.onnx.export(model,tuple(sample[k] for k in ('input_ids','attention_mask','token_type_ids')),str(path),input_names=['input_ids','attention_mask','token_type_ids'],output_names=['logits'],opset_version=17,dynamo=False,dynamic_axes={k:{0:'batch',1:'sequence'} for k in ('input_ids','attention_mask','token_type_ids')}|{'logits':{0:'batch'}})
        graph=onnx.load(path);graph.ir_version=min(graph.ir_version,8);onnx.checker.check_model(graph);onnx.save(graph,path)
        results={}
        for name,rows in valid.items():
            result,scores=evaluate(path,rows)
            if name=='general':
                indices=[i for i,r in enumerate(rows) if r['family']!='valid-0-3']
                result['annotation_uncertain_rows']=len(rows)-len(indices)
                result['unambiguous']=concise(summarize([rows[i] for i in indices],[scores[i] for i in indices],.5))
            results[name]=result
        runner=GateRunner(path,metadata,4)
        checkin_score=runner.score('还没打校园跑卡的抓紧啦，这件事不能忘。')
        photo_score=runner.score('翻相册看到9月15日在学院208室参加运动会的旧合影。')
        eligible,score=quality(results,report['baseline_validation'])
        eligible=eligible and checkin_score>.5 and photo_score<.5
        entry=dict(stage='frozen_encoder_checkin_head',epoch=epoch,export='fp32',loss=float(np.mean(losses)),validation=results,eligible=eligible,score=score,bytes=path.stat().st_size,checkin_score=checkin_score,photo_score=photo_score)
        history.append(entry);print(json.dumps(entry),flush=True)
        if best is None or (eligible,score)>(best['eligible'],best['score']):
            best=entry;(out/'selected.onnx').write_bytes(path.read_bytes());torch.save(model.state_dict(),out/'selected_weights.pt')
            save_file({k:v.contiguous() for k,v in model.state_dict().items()},out/'selected_weights.safetensors')
        if eligible and score>=report['selected']['score']:
            break
    report=copy.deepcopy(report);report['pre_head_selected']=report['selected'];report['selected']=best
    report['head_refinement']=dict(encoder_frozen=True,training_rows=len(train),new_checkin_rows=len(extra),data_sha256=file_sha256(source),history=history,selection='Validation and two documented runtime regression anchors; no test texts trained. KL replay preserves the prior head on existing training rows.')
    report['accepted']=best['eligible'];report['model_sha256']=file_sha256(out/'selected.onnx');report['bytes']=(out/'selected.onnx').stat().st_size
    if report['accepted']:
        report['evaluation']={}
        baseline=ROOT/'outputs/message-v2.2.1/baseline.int8.onnx'
        sets={**{f'real_{s}':data['real'][s] for s in ('train','valid','test')},**{f'synthetic_{k}_test':data[k]['test'] for k in ('replay','confusion','general')}}
        for name,rows in sets.items():
            report['evaluation'][name]={k:evaluate(p,rows)[0] for k,p in [('baseline',baseline),('new',out/'selected.onnx')]}
            print(name,json.dumps(report['evaluation'][name]),flush=True)
        ev=report['evaluation'];old=ev['synthetic_replay_test']['baseline'];new=ev['synthetic_replay_test']['new']
        report['accepted']=(ev['real_test']['new']['schedule_recall']>=ev['real_test']['baseline']['schedule_recall'] and new['schedule_precision']>=old['schedule_precision']-.01 and new['schedule_recall']>=old['schedule_recall']-.01 and ev['synthetic_confusion_test']['new']['accuracy']>=.9 and ev['synthetic_general_test']['new']['accuracy']>=ev['synthetic_general_test']['baseline']['accuracy'])
    report['head_refinement']['seconds']=round(time.monotonic()-started,1);save_json(out/'training_report.json',report)
    if not report['accepted']:raise SystemExit('Head candidate did not pass; prior model retained')
    for name in ['selected.onnx','selected_weights.pt','selected_weights.safetensors']:(base/name).write_bytes((out/name).read_bytes())
    (base/'latest_weights.pt').write_bytes((out/'selected_weights.pt').read_bytes());save_json(base/'training_report.json',report)
    print(json.dumps(dict(accepted=True,model_sha256=report['model_sha256'],selected=best)),flush=True)


if __name__=='__main__':main()
