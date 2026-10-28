"""Training-only minimal pairs distinguish present instructions from old photos."""
import json
import random

from build_message_supplement import build_data as base_data, PLACES, SUBJECTS
from common import ROOT

PAIRS = [
 ('{date}在{place}参加{event}时留下的老照片，今天重新拿出来怀念。',
  '{date}在{place}参加{event}，请提前做好准备并按时到场。'),
 ('刚找到{date}那次{event}在{place}的合照，都是以前的留念。',
  '{event}卡还没打的朋友记得补齐，大家别落下这个步骤。'),
 ('相册中{place}的{event}影像是{date}当时拍的，现在只是叙旧。',
  '没有打{event}卡的同学请现在完成，不要忘记啦。'),
 ('这张{date}于{place}举办{event}的旧照，好多年没有翻出来看了。',
  '咱们{event}的打卡还差几个人，没完成的抓紧补上。'),
 ('朋友发来{date}在{place}体验{event}的历史照片，只聊那次经历。',
  '今天的{event}请去签到，之前漏掉登记的也记得补齐。'),
 ('在整理图库，{event}那一辑全是{date}在{place}拍的过往影像。',
  '{event}的练习请在课后做完，再预习下一部分，别忘了。'),
 ('照片里那次{event}是{date}在{place}办的，活动早就结束了。',
  '还差{event}打卡的人要抓紧处理，完成后请确认提交。'),
 ('给你分享{date}在{place}参加{event}时拍下的合影，是过去的记录。',
  '{event}的课后阅读和配套题都要完成，做好以后按要求提交。'),
]


def build_data():
    rng=random.Random(2026102803)
    rows=base_data();seen_pairs=set()
    while len(seen_pairs)<800:
        index=rng.randrange(len(PAIRS))
        values=dict(date=f'{rng.randint(1,12)}月{rng.randint(1,28)}日',place=rng.choice(PLACES),event=rng.choice(SUBJECTS))
        pair=tuple(template.format(**values) for template in PAIRS[index])
        if pair in seen_pairs:
            continue
        seen_pairs.add(pair)
        for label,text in enumerate(pair):
            rows.append(dict(text=text,label=label,source='synthetic_contrastive',family=f'contrastive-{label}-{index}'))
    rng.shuffle(rows)
    path=ROOT/'data/contrastive-v2.2.1/train.jsonl';path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
    return rows
