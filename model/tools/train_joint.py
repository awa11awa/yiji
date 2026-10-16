"""Train and evaluate joint TinyBERT locally, without an online inference service."""
import argparse, json, random, time
from collections import Counter
from pathlib import Path
import torch
from torch.utils.data import DataLoader, WeightedRandomSampler
from transformers import AutoTokenizer, BertConfig, BertForTokenClassification, BertModel, get_linear_schedule_with_warmup
from safetensors.torch import save_file, load_file
from common import ROOT, save_json, file_sha256
from joint_model import JointTinyBert, LABELS, HEADS
from metrics import token_spans

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--epochs',type=int,default=7);ap.add_argument('--output',type=Path,default=ROOT/'outputs/joint');ap.add_argument('--init',type=Path);ap.add_argument('--init-ner',type=Path);ap.add_argument('--base-model',type=Path,default=ROOT/'models/tinybert-zh-base');args=ap.parse_args()
    torch.set_num_threads(8);torch.manual_seed(42);random.seed(42)
    data=ROOT/'data/joint-v2';base=args.init or args.init_ner or args.base_model
    if args.output.exists():raise ValueError('Choose a new output directory')
    args.output.mkdir(parents=True)
    tokenizer=AutoTokenizer.from_pretrained(base,local_files_only=True)
    config=BertConfig.from_pretrained(base,local_files_only=True)
    model=JointTinyBert(config)
    if args.init:model.load_state_dict(load_file(str(args.init/'model.safetensors')))
    elif args.init_ner:
        previous=BertForTokenClassification.from_pretrained(base,local_files_only=True)
        model.bert.load_state_dict(previous.bert.state_dict())
        with torch.no_grad():
            for i,label in enumerate(LABELS):
                old=label if label in previous.config.label2id else label[:2]+'TIME'
                source=previous.config.label2id[old]
                model.tags.weight[i]=previous.classifier.weight[source];model.tags.bias[i]=previous.classifier.bias[source]
        del previous
    else:
        encoder=BertModel.from_pretrained(base,local_files_only=True,add_pooling_layer=False)
        model.bert.load_state_dict(encoder.state_dict())
        del encoder
    config.save_pretrained(args.output);tokenizer.save_pretrained(args.output)
    save_json(args.output/'metadata.json',dict(labels=LABELS,heads=HEADS,max_length=384,stride=96,version='2.0.0',base_model='huawei-noah/TinyBERT_4L_zh',base_revision='152bf15d86715b41dd89c1e03cf5664963d9b005'))
    def encode(row):
        batch=tokenizer(row['text'],truncation=True,max_length=384,stride=96,return_offsets_mapping=True,return_overflowing_tokens=True)
        offsets=batch.pop('offset_mapping');batch.pop('overflow_to_sample_mapping');windows=[]
        for w,off in enumerate(offsets):
            visible=[(s,e) for s,e in off if s!=e];first,last=visible[0][0],visible[-1][1];tags=[]
            for s,e in off:
                entity=next((a for a in row['entities'] if a['start']<=s and e<=a['end']),None) if s!=e else None
                tags.append(-100 if s==e or entity and (entity['start']<first or entity['end']>last) else LABELS.index(('B-' if s==entity['start'] else 'I-')+entity['label']) if entity else 0)
            item={k:v[w] for k,v in batch.items()};item['tags']=tags
            item['targets']=[HEADS[k].index(row[k]) for k in HEADS];item['weight']=40. if row['source']=='user_provided_manually_annotated' else 1.
            windows.append(item)
        return windows
    datasets={k:[v for line in (data/f'{k}.jsonl').read_text(encoding='utf-8').splitlines() for v in encode(json.loads(line))] for k in ('train','valid','test')}
    def collate(rows):
        padded=tokenizer.pad([{k:r[k] for k in ('input_ids','attention_mask','token_type_ids')} for r in rows],return_tensors='pt')
        length=padded['input_ids'].shape[1]
        padded['tags']=torch.tensor([r['tags']+[-100]*(length-len(r['tags'])) for r in rows]);padded['targets']=torch.tensor([r['targets'] for r in rows]);return padded
    class Batches:
        def __len__(self):return (len(datasets['train'])+23)//24
        def __iter__(self):
            sampled=list(WeightedRandomSampler([r['weight'] for r in datasets['train']],len(datasets['train']),replacement=True))
            batches=[]
            for start in range(0,len(sampled),960):
                group=sorted(sampled[start:start+960],key=lambda i:len(datasets['train'][i]['input_ids']))
                batches.extend(group[n:n+24] for n in range(0,len(group),24))
            random.shuffle(batches);return iter(batches)
    loaders={k:DataLoader(v,batch_size=24,collate_fn=collate) for k,v in datasets.items()}
    loaders['train']=DataLoader(datasets['train'],batch_sampler=Batches(),collate_fn=collate)
    @torch.inference_mode()
    def evaluate(loader):
        model.eval();counts=Counter(tp=0,predicted=0,expected=0);correct=Counter();total=0
        per=Counter()
        for batch in loader:
            outputs=model(*(batch[k] for k in ('input_ids','attention_mask','token_type_ids')))
            for p,g in zip(outputs[0].argmax(-1).tolist(),batch['tags'].tolist()):
                active=[i for i,v in enumerate(g) if v!=-100];ps=token_spans([LABELS[p[i]] for i in active]);gs=token_spans([LABELS[g[i]] for i in active]);counts.update(tp=len(ps&gs),predicted=len(ps),expected=len(gs))
                for kind in ('EVENT','LOCATION','START_TIME','END_TIME','DUE_TIME','REFERENCE_TIME'):
                    per[(kind,'tp')]+=sum(e[0]==kind for e in ps&gs);per[(kind,'p')]+=sum(e[0]==kind for e in ps);per[(kind,'g')]+=sum(e[0]==kind for e in gs)
            for i,k in enumerate(HEADS):correct[k]+=int((outputs[i+1].argmax(-1)==batch['targets'][:,i]).sum())
            total+=len(batch['targets'])
        p=counts['tp']/max(1,counts['predicted']);r=counts['tp']/max(1,counts['expected'])
        return dict(entity_f1=2*p*r/max(1e-9,p+r),entity_counts=dict(counts),head_accuracy={k:correct[k]/total for k in HEADS},windows=total,role_f1={k:2*per[(k,'tp')]/max(1,per[(k,'p')]+per[(k,'g')]) for k in ('EVENT','LOCATION','START_TIME','END_TIME','DUE_TIME','REFERENCE_TIME')})
    optimizer=torch.optim.AdamW(model.parameters(),lr=4e-5,weight_decay=.01);scheduler=get_linear_schedule_with_warmup(optimizer,len(loaders['train']),len(loaders['train'])*args.epochs)
    report=dict(status='experimental',real_world_accuracy='Not established; synthetic holdout scores only. Three supplied real notices are training examples.',history=[],data_sha256={k:file_sha256(data/f'{k}.jsonl') for k in datasets},train_windows=len(datasets['train']))
    best=-1;started=time.monotonic()
    for epoch in range(args.epochs):
        model.train();loss_sum=0
        for batch in loaders['train']:
            optimizer.zero_grad(set_to_none=True);out=model(*(batch[k] for k in ('input_ids','attention_mask','token_type_ids')))
            weights=torch.tensor([.35]+[2.5 if 'END_TIME' in label or 'REFERENCE_TIME' in label else 1. for label in LABELS[1:]])
            loss=torch.nn.functional.cross_entropy(out[0].reshape(-1,len(LABELS)),batch['tags'].reshape(-1),weight=weights)
            loss+=sum(torch.nn.functional.cross_entropy(out[i+1],batch['targets'][:,i]) for i in range(4))*.65
            loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),1);optimizer.step();scheduler.step();loss_sum+=float(loss.detach())
        result=evaluate(loaders['valid']);score=result['entity_f1']+sum(result['head_accuracy'].values())/4
        entry=dict(epoch=epoch+1,loss=loss_sum/len(loaders['train']),valid=result,seconds=round(time.monotonic()-started,1));report['history'].append(entry);print(json.dumps(entry,ensure_ascii=False),flush=True)
        if score>best:best=score;save_file(model.state_dict(),str(args.output/'model.safetensors'));report['best_epoch']=epoch+1
        save_json(args.output/'training_report.json',report)
    model.load_state_dict(load_file(str(args.output/'model.safetensors')));report['test']=evaluate(loaders['test']);save_json(args.output/'training_report.json',report);print(json.dumps(report['test'],ensure_ascii=False),flush=True)

if __name__=='__main__':main()
