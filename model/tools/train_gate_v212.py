"""Hard-negative and incomplete-notice finetuning with an untouched ONNX baseline.

All generated rows are synthetic. Private notification examples are not training data.
The old and new models are evaluated on identical deduplicated holdout rows.
"""
import argparse, hashlib, json, random, shutil, time
from pathlib import Path
import numpy as np
import torch
from transformers import AutoTokenizer, BertModel, get_linear_schedule_with_warmup
import onnx
import onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType
from train_gate import Gate, make_data, metrics
from common import ROOT, BASE_MODEL, save_json, file_sha256

POS = {
 'train': [
  '【关于开展{event}的通知】各位同学：本次面向全体同学，具体时间和地点随后公布。',
  '原定{date}的{event}需要调整，后续安排另行通知，请留意。',
  '第{week}周{course}作业：预习第{unit}单元，完成P{page}的题目；选做阅读理解。',
  '收集表将于{date}{clock}开放，限{count}人，填好后进群；活动地点为{room}。',
  '请于{date}前完成{event}，具体时刻没有另作规定。',
  '补充通知：{event}只调整到场地点至{room}，时间保持原安排。',
  '{event}新安排：{date} {clock}。结束时间尚未确定。',
  '本周内请完成线上平台第{unit}章的学习，需完成全部指定内容。',
 ],
 'valid': [
  '各位同学好，学院决定举行{event}，详见后续消息，现将事项预告如下。',
  '{event}改期了！旧日期不再适用，新的日期尚待公布。',
  '{course}课后任务清单（第{week}周）：P{page}练习、听读第{unit}单元、平台学习。',
  '报名入口从{date}{clock}起可以填写，名额仅{count}个，活动在{room}开展。',
  '大家记住把{event}在{date}之前办好。',
  '场所更正，{event}现在请前往{room}，其他要求照旧。',
  '接到学院安排：{event}，{date}{clock}开始，暂未告知何时结束。',
  '线上作业要求：这周内完成第{unit}部分并提交。',
 ],
 'test': [
  '【{event}安排预告】请相关同学关注！举办时段、场地确定后将再发消息。',
  '有变动：此前安排的{event}顺延，后续等负责老师确定再通知。',
  '本周{course}布置如下：教材P{page}第{unit}题必做，另有自选阅读两篇。',
  '{date} {clock}启动报名填报，本次只接受{count}份；{room}是活动举办地点。',
  '{event}办理的最后日期为{date}，请相互提醒。',
  '请按修订后的场地参加{event}：{room}；无需改变原来的到场时刻。',
  '关于{event}，定在{date} {clock}开场；没有公布散场时刻。',
  '还没学习的请在本星期内完成第{unit}章线上课程并提交记录。',
 ]}
NEG = {
 'train': ['随机编号{digits}，只是测试数字。','刚才的{event}已经办完啦，终于可以休息。','收到老师的消息了，感谢提醒！','我在{room}聊天，没什么别的事情。','课本P{page}的这个知识点是什么意思，求解释。','{event}这个词英语怎么说啊。','上次{event}的感想和回忆，留念。','我的程序输出了{digits}，这个结果对吗？','只是随手打字{letters}，没有安排。','我已经交过{event}，这条仅确认收到。'],
 'valid': ['此处的{digits}是调试用随机字符串。','谢谢您告知，事情已经处理妥当。','完成{event}以后整个人轻松多了。','在{room}喝水发呆中。','P{page}这道题能不能帮我讲解一下。','以前那场{event}好值得回味啊。','输入{digits}仅为了检查键盘。','这段代码为什么显示{digits}呀？','不好意思我只是乱按{letters}。','明白您的意思了，收到啦！'],
 'test': ['这不是待办，数字{digits}仅是无意义演示内容。','多谢通知，我已经全部做完。','{event}的提交记录我早就保存好了。','宿舍闲聊：刚从{room}散步回来。','有人知道第{page}页的答案为什么这样写吗。','聊聊早年参加{event}时的趣事。','测试输出字符串是{digits}，请忽略。','我想问的是代码里的{digits}为何变了。','随便敲了一串字母{letters}罢了。','知道啦，谢谢大家解答！']}

def build_data():
 rng=random.Random(20261022);data=make_data();seen={r['text'] for rows in data.values() for r in rows}
 reserved={'123','000000','20261019','收到，谢谢。','作业写完了，太开心了。','。。。。。。'}
 for split,n in [('train',2000),('valid',500),('test',500)]:
  for label,templates in [(1,POS[split]),(0,NEG[split])]:
   added=0
   while added<n:
    digits=''.join(rng.choices('0123456789',k=rng.randint(2,40)))
    vals=dict(event=rng.choice(['专题讲座','职业培训','体质测试','班级会议','课程作业','材料报送','线上学习']),course=rng.choice(['大学英语','线性代数','程序设计','大学物理']),date=f'{rng.randint(1,12)}月{rng.randint(1,28)}日',clock=f'{rng.randint(8,21)}:{rng.choice(["00","15","30","45"])}',room=f'教学楼{rng.randint(1,9)}{rng.randint(1,8):02d}室',week=rng.randint(2,18),unit=rng.randint(1,9),page=rng.randint(2,150),count=rng.randint(20,90),digits=digits,letters=''.join(rng.choices('abcdefghijklmnopqrstuvwxyz',k=rng.randint(4,30))))
    template=rng.choice(templates);text=template.format(**vals)
    family=f'{split}-hard-{label}-{templates.index(template)}'
    if label==0 and rng.random()<.35:
     # Random numeric/repetition noise uses global exact-text deduplication; its generator is shared across splits.
     text=digits if rng.random()<.7 else rng.choice(['0','9','哈','啊','。','!']) * rng.randint(3,80)
     family='random-noise-generator'
    if text in seen or text in reserved:continue
    seen.add(text);data[split].append(dict(text=text,label=label,source='synthetic',family=family));added+=1
  rng.shuffle(data[split])
  out=ROOT/'data/gate-v2.1.2';out.mkdir(parents=True,exist_ok=True)
  (out/f'{split}.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in data[split]))
 assert not any(set(r['text'] for r in data[a]) & set(r['text'] for r in data[b]) for a,b in [('train','valid'),('train','test'),('valid','test')])
 return data

def calibrate(probs,labels):
 options=[(float(t),metrics(probs,labels,float(t))) for t in np.linspace(.50,.999,251)]
 eligible=[(t,m) for t,m in options if m['schedule_recall']>=.985]
 if eligible:return max(eligible,key=lambda p:(p[1]['schedule_precision'],-p[0]))
 return max(options,key=lambda p:(p[1]['schedule_recall']*.7+p[1]['schedule_precision']*.3,-p[0]))

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--epochs',type=int,default=7);ap.add_argument('--threads',type=int,default=4);args=ap.parse_args()
 torch.set_num_threads(args.threads);torch.manual_seed(20261022);random.seed(20261022)
 output=ROOT/'outputs/gate-v2.1.2';output.mkdir(parents=True,exist_ok=True)
 asset=ROOT.parent/'app/src/main/assets/tinybert'
 shutil.copy2(asset/'model.int8.onnx',output/'baseline.int8.onnx');baseline_meta=json.loads((asset/'metadata.json').read_text())
 tokenizer=AutoTokenizer.from_pretrained(BASE_MODEL,local_files_only=True)
 encoder=BertModel.from_pretrained(BASE_MODEL,local_files_only=True,add_pooling_layer=False);encoder.config._attn_implementation='eager'
 model=Gate(encoder);data=build_data()
 encoded={k:[dict(tokenizer(r['text'],truncation=True,max_length=384),label=r['label']) for r in rows] for k,rows in data.items()}
 def batches(split,training=False):
  rows=list(encoded[split]);groups=[]
  if training:random.shuffle(rows)
  for pos in range(0,len(rows),768):
   group=sorted(rows[pos:pos+768],key=lambda r:len(r['input_ids']))
   groups += [group[n:n+32] for n in range(0,len(group),32)]
  if training:random.shuffle(groups)
  for group in groups:
   yield tokenizer.pad([{k:v for k,v in r.items() if k!='label'} for r in group],return_tensors='pt'),torch.tensor([r['label'] for r in group])
 @torch.inference_mode()
 def evaluate(split,session=None):
  model.eval();probs=[];labels=[]
  for batch,target in batches(split):
   logits=model(**batch).numpy() if session is None else session.run(None,{k:v.numpy() for k,v in batch.items()})[0]
   values=np.exp(logits-logits.max(-1,keepdims=True));probs.extend(values/values.sum(-1,keepdims=True));labels.extend(target.tolist())
  return np.asarray(probs),labels
 opts=ort.SessionOptions();opts.intra_op_num_threads=args.threads;opts.inter_op_num_threads=1
 baseline=ort.InferenceSession(str(output/'baseline.int8.onnx'),sess_options=opts,providers=['CPUExecutionProvider'])
 old_probs,test_labels=evaluate('test',baseline);old=metrics(old_probs,test_labels,baseline_meta['chat_ignore_threshold'])
 report=dict(version='2.1.2',task='binary_message_routing',seed=20261022,source='huawei-noah/TinyBERT_4L_zh',revision='152bf15d86715b41dd89c1e03cf5664963d9b005',real_training_rows=0,warning='Synthetic holdout only; this does not establish real-world accuracy. User-provided notices were not used for training.',split='Sentence templates disjoint by split; shared vocabulary and random-noise generator; exact texts globally deduplicated. Threshold chosen on validation only.',sizes={k:len(v) for k,v in data.items()},data_sha256={k:file_sha256(ROOT/'data/gate-v2.1.2'/f'{k}.jsonl') for k in data},baseline_sha256=file_sha256(output/'baseline.int8.onnx'),baseline_int8_test=old,torch_version=torch.__version__,history=[])
 optimizer=torch.optim.AdamW(model.parameters(),lr=3e-5,weight_decay=.01)
 steps=((len(data['train'])+31)//32)*args.epochs;scheduler=get_linear_schedule_with_warmup(optimizer,steps//10,steps)
 best=-1;started=time.monotonic()
 for epoch in range(args.epochs):
  model.train();losses=[]
  for batch,target in batches('train',True):
   optimizer.zero_grad(set_to_none=True);loss=torch.nn.functional.cross_entropy(model(**batch),target,label_smoothing=.02);loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),1.);optimizer.step();scheduler.step();losses.append(float(loss.detach()))
  probs,labels=evaluate('valid');threshold,result=calibrate(probs,labels)
  score=result['schedule_recall']*.7+result['schedule_precision']*.3
  row=dict(epoch=epoch+1,loss=float(np.mean(losses)),threshold=threshold,validation=result,seconds=round(time.monotonic()-started,1));report['history'].append(row);print(json.dumps(row),flush=True)
  if score>best:best=score;torch.save(model.state_dict(),output/'weights.pt');report['best_epoch']=epoch+1
  save_json(output/'training_report.json',report)
 model.load_state_dict(torch.load(output/'weights.pt',weights_only=True));model.eval();valid,labels=evaluate('valid');threshold,_=calibrate(valid,labels)
 sample=tokenizer('明天下午两点开班会',return_tensors='pt');fp32=output/'model.fp32.onnx';quant=output/'model.int8.onnx'
 with torch.no_grad():torch.onnx.export(model,tuple(sample[k] for k in ('input_ids','attention_mask','token_type_ids')),str(fp32),input_names=['input_ids','attention_mask','token_type_ids'],output_names=['logits'],opset_version=17,dynamo=False,dynamic_axes={k:{0:'batch',1:'sequence'} for k in ('input_ids','attention_mask','token_type_ids')}|{'logits':{0:'batch'}})
 quantize_dynamic(str(fp32),str(quant),per_channel=True,weight_type=QuantType.QUInt8,op_types_to_quantize=['MatMul','Gather'])
 graph=onnx.load(quant);graph.ir_version=min(graph.ir_version,8);onnx.save(graph,quant)
 current=ort.InferenceSession(str(quant),sess_options=opts,providers=['CPUExecutionProvider']);probs,labels=evaluate('test',current);result=metrics(probs,labels,threshold)
 report.update(chat_ignore_threshold=threshold,int8_test=result,model_sha256=file_sha256(quant),bytes=quant.stat().st_size,seconds=round(time.monotonic()-started,1))
 checks=[('123',0),('000000',0),('20261019',0),('收到，谢谢。',0),('作业写完了，太开心了。',0),('。。。。。。',0),('15:30',1),('改到周五，地点不变',1),('原定体测调整，具体方案另行通知',1),('关于开展专题讲坛的通知，各位同学：具体安排待公布',1)]
 diagnostic=[]
 for text,label in checks:
  batch=tokenizer(text,return_tensors='np');entry=dict(text=text,label=label)
  for name,session,t in [('old',baseline,baseline_meta['chat_ignore_threshold']),('new',current,threshold)]:
   logits=session.run(None,dict(batch))[0][0];p=np.exp(logits-logits.max());p/=p.sum();entry[name]=dict(candidate=bool(p[0]<t),schedule_score=float(p[1]))
  diagnostic.append(entry)
 report['diagnostic_examples']=diagnostic
 report['accepted']=result['schedule_recall']>=max(.96,old['schedule_recall']-.01) and result['schedule_precision']>=min(.995,old['schedule_precision']+.02)
 save_json(ROOT/'gate_training_report_v2.1.2.json',report)
 if not report['accepted']:raise RuntimeError('New weights did not improve precision without recall regression; baseline retained.')
 shutil.copy2(quant,asset/'model.int8.onnx')
 save_json(asset/'metadata.json',dict(version='2.1.2',task='binary_gate',labels=['CHAT','SCHEDULE'],max_length=384,stride=96,chat_ignore_threshold=threshold,model_sha256=report['model_sha256'],base_model=report['source'],base_revision=report['revision']))
 print(json.dumps(dict(accepted=True,baseline=old,new=result,threshold=threshold,diagnostic=diagnostic)),flush=True)

if __name__=='__main__':main()
