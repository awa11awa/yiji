"""Re-finetune the verified Chinese TinyBERT for CHAT / SCHEDULE routing only.

Training and holdout examples here are synthetic, with disjoint sentence families.
Never advertise these holdout results as real-notification accuracy.
"""
import argparse
import hashlib
import json
import random
import time
from pathlib import Path

import offline_runtime

import numpy as np
import torch
from torch import nn
from transformers import AutoTokenizer, BertModel, get_linear_schedule_with_warmup
from onnxruntime import InferenceSession
from onnxruntime.quantization import quantize_dynamic, QuantType
from common import ROOT, BASE_MODEL, save_json, file_sha256
from build_joint_data import EVENTS, TEMPLATES, render
from synthetic_vocabulary import LOCATIONS, make_time

LABELS = ["CHAT", "SCHEDULE"]
POSITIVE = {
    "train": ["{time}到{place}集合，带学生证", "请完成{event}，交给负责人", "{event}的具体时间另行通知", "{event}取消了，别白跑一趟", "{event}改到{time}，地点不变", "报名表发群文件了，{time}之前填好", "大家记一下，{time}要去{place}", "没做{event}的同学请抓紧预约", "班委提醒：{event}还没有提交的请补交", "{event}需要交纸质版，截止时间待通知", "周末有{event}，参加的同学报一下名", "通知一下：{event}提前了，请看新安排", "明天下午交报告，后天参加培训，原定班会取消", "请大家今晚填好统计表，明早交到办公室", "老师布置的：教材第{number}页，第1、3、5题", "补充：地点换成{place}，时间不变", "改到{time}，大家互相转告", "截止延长一天，还没交的抓紧", "原来的安排作废，以本次通知为准", "{event}推迟，具体安排待定"],
    "valid": ["大家留意一下{event}，请于{time}赶到{place}", "{event}的安排有变动，更新后在群里确认", "还没完成{event}的请尽快办理", "{event}登记入口将于{time}关闭", "上课前交第{number}页的练习", "刚收到更正，场地调整为{place}", "明天别忘了把表交了", "这个活动要自己预约场次"],
    "test": ["提醒各位：{event}须在{time}办妥", "{event}已改期，请重新查看通知", "{place}，{time}，参加{event}的都要来", "接老师安排，完成课本P{number}第2到6题", "原定的活动不办了，别去了", "这周要交材料，时间随后公布", "还没填收集表的同学今天务必填好", "集合地点按刚发的更正执行"],
}
NEGATIVE = {
    "train": ["晚饭吃什么", "今天食堂的饭真香", "收到，谢谢", "好的好的", "哈哈哈哈哈", "这个表情包太搞笑了", "请问{event}怎么做啊", "有人知道{event}啥意思吗", "{event}太难了，我不会", "{event}已经交了，终于做完", "昨天的{event}真好玩", "这是去年{time}的活动照片", "{event}有谁参加过吗，分享下感受", "明天下午我想睡觉，啥也不干", "我{time}去吃饭，有人一起吗", "新闻：{place}去年举办了{event}", "登录验证码{number}，请勿泄露", "【促销】{event}课程限时优惠，买一送一", "你有{number}条新消息", "[图片]", "[文件]", "分享图片", "对方撤回了一条消息", "这条消息已撤回", "已读", "明天会不会下雨", "作业好难啊", "实验报告怎么提交", "有人有{event}的答案吗", "不用交这个了，我说的是去年的{event}", "我已经报名成功了", "班会照片发出来看看", "培训结束了，大家辛苦", "今天是{time}，时间过得真快", "{place}在哪里啊", "这只是一个举例：明天开会，不是真通知", "手机通知测试，不需要参加任何活动", "今天不安排事情，大家自由聊天", "请帮我看看代码哪里错了", "有没有人一起打游戏", "谢谢提醒，我都完成了", "这个群叫{event}群，但我只是问下晚饭", "系统维护完成，现已恢复使用"],
    "valid": ["想起之前参加{event}的日子了", "{event}的问题求大佬讲讲", "终于把{event}做完了，休息", "{place}的照片谁还有", "你的校验码是{number}", "今晚吃什么好呢", "那次{event}已经过去很久啦", "谢谢，收到啦", "我在{place}散步呢", "没什么事，随便聊聊", "我明天可能去逛街，还没想好", "求问{event}以前的提交方式"],
    "test": ["{event}当时真有意思，怀念", "能解释一下{event}这道题吗", "我刚提交了{event}，不用再提醒我", "我正在{place}吃零食", "账户安全验证：{number}，勿向他人提供", "周末想宅宿舍看电影", "昨天活动结束后的合照真不错", "了解，感谢老师通知", "这些是{event}的历史记录", "有事再说，我先去吃饭", "哈哈，群名怎么改成{event}了", "有人愿意聊聊以前的{event}吗"],
}


class Gate(nn.Module):
    def __init__(self, encoder):
        super().__init__()
        self.bert = encoder
        self.classifier = nn.Sequential(nn.Linear(encoder.config.hidden_size * 2, 96), nn.GELU(), nn.Dropout(.1), nn.Linear(96, 2))

    def forward(self, input_ids, attention_mask, token_type_ids):
        hidden = self.bert(input_ids, attention_mask=attention_mask, token_type_ids=token_type_ids).last_hidden_state
        mask = attention_mask.unsqueeze(-1).to(hidden.dtype)
        pooled = torch.cat([hidden[:, 0], (hidden * mask).sum(1) / mask.sum(1).clamp(min=1)], -1)
        return self.classifier(pooled)


def make_data():
    rng = random.Random(20261018)
    seen = set()
    datasets = {}
    for split, count in [("train", 5000), ("valid", 600), ("test", 600)]:
        rows = []
        while len(rows) < count:
            positive = len(rows) % 2 == 1
            category = rng.choice(list(EVENTS))
            event = rng.choice(EVENTS[category])
            values = dict(event=event, location=rng.choice(LOCATIONS), start=make_time(rng), end="下午5点", due=make_time(rng, deadline=True), reference=make_time(rng))
            if positive and rng.random() < .45:
                mode = rng.choice([m for m in TEMPLATES[split] if m != "ignore"])
                template = rng.choice(TEMPLATES[split][mode])
                text, _ = render(template, values, False)
                family = "joint-" + mode + "-" + str(TEMPLATES[split][mode].index(template))
            else:
                templates = POSITIVE[split] if positive else NEGATIVE[split]
                template = rng.choice(templates)
                text = template.format(event=event, time=make_time(rng), place=rng.choice(LOCATIONS), number=rng.randrange(10, 999))
                family = ("schedule-" if positive else "chat-") + str(templates.index(template))
            if positive and rng.random() < .12:
                text = ("各位同学，以下通知请仔细阅读。\n" if split == "train" else "有关事项请注意查收：\n") + text
            # Only templated strings with variables can generate many unique rows.
            # The split-specific padding is neutral chatter, not a routing trigger.
            if text in seen:
                if len(rows) % 5 == 0:
                    text = text + "\n" + rng.choice(NEGATIVE[split]).format(event=event, time=make_time(rng), place=rng.choice(LOCATIONS), number=rng.randrange(10, 999))
                else:
                    continue
            if text in seen:
                continue
            seen.add(text)
            rows.append(dict(text=text, label=int(positive), source="synthetic", family=split + "-" + family))
        rng.shuffle(rows)
        datasets[split] = rows
        path = ROOT / "data/gate-v2.1" / (split + ".jsonl")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    return datasets


def metrics(probabilities, labels, threshold):
    predicted = (probabilities[:, 0] < threshold).astype(int)
    labels = np.asarray(labels)
    tp = int(((predicted == 1) & (labels == 1)).sum())
    fp = int(((predicted == 1) & (labels == 0)).sum())
    fn = int(((predicted == 0) & (labels == 1)).sum())
    return dict(schedule_precision=tp / max(1, tp + fp), schedule_recall=tp / max(1, tp + fn), accuracy=float((predicted == labels).mean()), tp=tp, fp=fp, fn=fn, rows=len(labels))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=5)
    ap.add_argument("--threads", type=int, default=6)
    args = ap.parse_args()
    torch.set_num_threads(args.threads)
    torch.manual_seed(42)
    random.seed(42)
    output = ROOT / "outputs/gate-v2.1"
    output.mkdir(parents=True, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL, local_files_only=True)
    encoder = BertModel.from_pretrained(BASE_MODEL, local_files_only=True, add_pooling_layer=False)
    encoder.config._attn_implementation = "eager"
    model = Gate(encoder)
    data = make_data()
    encoded = {k: [dict(tokenizer(r["text"], truncation=True, max_length=384), label=r["label"]) for r in rows] for k, rows in data.items()}

    def batches(split, training=False):
        rows = list(encoded[split])
        if training:
            random.shuffle(rows)
        groups = []
        for start in range(0, len(rows), 768):
            group = sorted(rows[start:start + 768], key=lambda r: len(r["input_ids"]))
            groups.extend(group[n:n + 32] for n in range(0, len(group), 32))
        if training:
            random.shuffle(groups)
        for group in groups:
            batch = tokenizer.pad([{k: v for k, v in r.items() if k != "label"} for r in group], return_tensors="pt")
            yield batch, torch.tensor([r["label"] for r in group])

    @torch.inference_mode()
    def evaluate(split):
        model.eval()
        values, labels = [], []
        for batch, target in batches(split):
            values.extend(torch.softmax(model(**batch), -1).numpy())
            labels.extend(target.tolist())
        return np.array(values), labels

    total_steps = ((len(data["train"]) + 31) // 32) * args.epochs
    optimizer = torch.optim.AdamW(model.parameters(), lr=4e-5, weight_decay=.01)
    scheduler = get_linear_schedule_with_warmup(optimizer, total_steps // 10, total_steps)
    report = dict(version="2.1.0", task="binary_message_routing", labels=LABELS, seed=42,
        source="huawei-noah/TinyBERT_4L_zh", revision="152bf15d86715b41dd89c1e03cf5664963d9b005",
        initialized_from="Verified official pretrained Chinese TinyBERT encoder; a newly trained binary head replaces all v2 extraction heads.",
        real_training_rows=0, warning="Synthetic data only. No independent real-world accuracy is established.",
        split="Disjoint sentence families between train, validation and test; shared subject vocabulary; exact text deduplicated across splits.",
        data_sha256={k:file_sha256(ROOT / "data/gate-v2.1" / (k + ".jsonl")) for k in data},
        torch_version=torch.__version__, history=[])
    best = -1.0
    started = time.monotonic()
    for epoch in range(args.epochs):
        model.train()
        losses = []
        for batch, target in batches("train", True):
            optimizer.zero_grad(set_to_none=True)
            loss = nn.functional.cross_entropy(model(**batch), target)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.)
            optimizer.step()
            scheduler.step()
            losses.append(float(loss.detach()))
        probabilities, labels = evaluate("valid")
        result = metrics(probabilities, labels, .95)
        score = result["schedule_recall"] * .7 + result["schedule_precision"] * .3
        entry = dict(epoch=epoch + 1, loss=sum(losses) / len(losses), validation=result, seconds=round(time.monotonic()-started, 1))
        report["history"].append(entry)
        print(json.dumps(entry, ensure_ascii=False), flush=True)
        if score > best:
            best = score
            torch.save(model.state_dict(), output / "weights.pt")
            report["best_epoch"] = epoch + 1
        save_json(ROOT / "gate_training_report.json", report)
    model.load_state_dict(torch.load(output / "weights.pt", weights_only=True))
    model.eval()
    valid, valid_labels = evaluate("valid")
    # Choose the smallest conservative threshold meeting the validation recall target.
    threshold = next((v for v in [.90, .92, .95, .97, .99, .995] if metrics(valid, valid_labels, v)["schedule_recall"] >= .985), .995)
    test_probabilities, test_labels = evaluate("test")
    report["chat_ignore_threshold"] = threshold
    report["fp32_test"] = metrics(test_probabilities, test_labels, threshold)
    sample = tokenizer("明天下午两点开班会", return_tensors="pt")
    fp32 = output / "model.fp32.onnx"
    with torch.no_grad():
        torch.onnx.export(model, tuple(sample[k] for k in ("input_ids", "attention_mask", "token_type_ids")), str(fp32),
            input_names=["input_ids", "attention_mask", "token_type_ids"], output_names=["logits"], opset_version=17, dynamo=False,
            dynamic_axes={k:{0:"batch",1:"sequence"} for k in ("input_ids","attention_mask","token_type_ids")}|{"logits":{0:"batch"}})
    asset = ROOT.parent / "app/src/main/assets/tinybert"
    quantize_dynamic(str(fp32), str(asset / "model.int8.onnx"), per_channel=True, weight_type=QuantType.QUInt8, op_types_to_quantize=["MatMul", "Gather"])
    import onnx
    graph = onnx.load(asset / "model.int8.onnx")
    graph.ir_version = min(graph.ir_version, 8)
    onnx.save(graph, asset / "model.int8.onnx")
    session = InferenceSession(str(asset / "model.int8.onnx"), providers=["CPUExecutionProvider"])
    probs, actual_labels = [], []
    for batch, target in batches("test"):
        logits = session.run(None, {k:v.numpy() for k,v in batch.items()})[0]
        values = np.exp(logits - logits.max(-1, keepdims=True))
        probs.extend(values / values.sum(-1, keepdims=True))
        actual_labels.extend(target.tolist())
    report["int8_test"] = metrics(np.array(probs), actual_labels, threshold)
    report["model_sha256"] = file_sha256(asset / "model.int8.onnx")
    report["bytes"] = (asset / "model.int8.onnx").stat().st_size
    if report["int8_test"]["schedule_recall"] < .96:
        raise RuntimeError("Synthetic holdout recall gate failed; do not ship these weights.")
    save_json(asset / "metadata.json", dict(version="2.1.0", task="binary_gate", labels=LABELS, max_length=384, stride=96,
        chat_ignore_threshold=threshold, model_sha256=report["model_sha256"], base_model=report["source"], base_revision=report["revision"]))
    save_json(ROOT / "gate_training_report.json", report)
    print(json.dumps(dict(int8_test=report["int8_test"], bytes=report["bytes"], sha256=report["model_sha256"]), ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
