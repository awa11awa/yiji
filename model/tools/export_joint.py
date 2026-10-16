"""Export learned heads together and verify FP32/INT8 on a synthetic holdout."""
import argparse, contextlib, json, shutil
from pathlib import Path
from collections import Counter
import numpy as np
import torch, onnx, onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType
from safetensors.torch import load_file
from transformers import AutoTokenizer, BertConfig
from common import ROOT, file_sha256, save_json
from joint_model import JointTinyBert, LABELS, HEADS
from metrics import token_spans

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--model',type=Path,default=ROOT/'outputs/joint');ap.add_argument('--output',type=Path,default=ROOT/'outputs/android-v2');args=ap.parse_args()
    torch.set_num_threads(4);args.output.mkdir(parents=True,exist_ok=True)
    model=JointTinyBert(BertConfig.from_pretrained(args.model));model.load_state_dict(load_file(str(args.model/'model.safetensors')));model.eval()
    tok=AutoTokenizer.from_pretrained(args.model);sample=tok('明天14点在301参加班级会议。',return_tensors='pt')
    inputs=['input_ids','attention_mask','token_type_ids'];outputs=['tags','route','kind','state','operation']
    torch.onnx.export(model,tuple(sample[k] for k in inputs),str(args.output/'model.onnx'),input_names=inputs,output_names=outputs,dynamic_axes={**{k:{0:'batch',1:'sequence'} for k in inputs},'tags':{0:'batch',1:'sequence'},**{k:{0:'batch'} for k in outputs[1:]}},opset_version=17,dynamo=False,external_data=False)
    scratch=ROOT/'.cache/joint-export';scratch.mkdir(parents=True,exist_ok=True);shutil.copy2(args.output/'model.onnx',scratch/'model.onnx')
    with contextlib.chdir(ROOT):quantize_dynamic('.cache/joint-export/model.onnx','.cache/joint-export/model.int8.onnx',weight_type=QuantType.QUInt8,per_channel=True,op_types_to_quantize=['MatMul','Gather'],use_external_data_format=False)
    shutil.copy2(scratch/'model.int8.onnx',args.output/'model.int8.onnx')
    for name in ['metadata.json','vocab.txt','training_report.json']:shutil.copy2(args.model/name,args.output/name)
    graph=onnx.load(str(args.output/'model.int8.onnx'));onnx.checker.check_model(graph)
    options=ort.SessionOptions();options.intra_op_num_threads=2
    sessions=[ort.InferenceSession(str(args.output/name),sess_options=options,providers=['CPUExecutionProvider']) for name in ('model.onnx','model.int8.onnx')]
    batch={k:v.numpy().astype(np.int64) for k,v in sample.items()}
    with torch.inference_mode():expected=model(**sample)
    fp=sessions[0].run(None,batch)
    error=max(float(np.abs(a-b.numpy()).max()) for a,b in zip(fp,expected));assert error<.001,error
    reports=[]
    for session in sessions:
        count=Counter(tp=0,predicted=0,expected=0);correct=Counter();total=0
        for line in (ROOT/'data/joint-v2/test.jsonl').read_text(encoding='utf-8').splitlines():
            row=json.loads(line);encoded=tok(row['text'],return_tensors='np',return_offsets_mapping=True);offsets=encoded.pop('offset_mapping')[0]
            result=session.run(None,{k:v.astype(np.int64) for k,v in encoded.items()})
            gold=[];pred=[]
            for tag,(s,e) in zip(result[0].argmax(-1)[0],offsets):
                if s==e:continue
                entity=next((a for a in row['entities'] if a['start']<=s and e<=a['end']),None)
                gold.append(('B-' if s==entity['start'] else 'I-')+entity['label'] if entity else 'O');pred.append(LABELS[int(tag)])
            p,g=token_spans(pred),token_spans(gold);count.update(tp=len(p&g),predicted=len(p),expected=len(g))
            for i,key in enumerate(HEADS):correct[key]+=int(result[i+1].argmax(-1)[0]==HEADS[key].index(row[key]))
            total+=1
        reports.append(dict(entity_f1=2*count['tp']/max(1,count['predicted']+count['expected']),counts=dict(count),head_accuracy={k:correct[k]/total for k in HEADS},rows=total))
    print(json.dumps(reports,ensure_ascii=False),flush=True)
    assert reports[1]['entity_f1']>=reports[0]['entity_f1']-.025,'INT8 regression too large'
    meta=json.loads((args.output/'metadata.json').read_text(encoding='utf-8'));meta['model_sha256']=file_sha256(args.output/'model.int8.onnx');meta['input_names']=inputs;meta['output_names']=outputs;meta['ignore_threshold']=.8
    save_json(args.output/'metadata.json',meta)
    report=dict(onnxruntime=ort.__version__,ir_version=graph.ir_version,opset=17,pytorch_fp32_max_abs_error=error,synthetic_fp32=reports[0],synthetic_int8=reports[1],bytes=(args.output/'model.int8.onnx').stat().st_size,sha256=meta['model_sha256'])
    save_json(args.output/'export_report.json',report);print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
    destination=ROOT.parent/'app/src/main/assets/tinybert';destination.mkdir(parents=True,exist_ok=True)
    for name in ['model.int8.onnx','metadata.json','vocab.txt']:shutil.copy2(args.output/name,destination/name)

if __name__=='__main__':main()
