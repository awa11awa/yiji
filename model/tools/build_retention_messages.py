"""Training paraphrases retain coursework, registration and ordinary system/chat skills."""
import json
import random

from build_contrastive_messages import build_data as contrastive_data
from common import ROOT

POSITIVE = [
    '{course}第{week}周课后任务：书本P{page}的第{unit}题需要完成，加星的阅读可自行选择。',
    '今天{course}留下的练习在教材{page}页，第{unit}题要做，做完上传。',
    '{course}学习清单：第{unit}章课文、P{page}练习和在线平台章节测验。',
    '本次{course}的必交内容是书上{page}页习题，另外附加阅读为选做内容。',
    '这周{course}的书面练习做P{page}，第{unit}题起的三道题都要完成。',
    '老师布置了{course}课后练习，先读第{unit}部分，再完成书本{page}页。',
    '参加{event}的填报通道将在{date}{clock}启用，接收人数上限{number}名，场地是{place}。',
    '{event}登记入口开放时刻是{date}{clock}，总共{number}个名额，在{place}举行。',
    '{date}{clock}起可以提交{event}报名资料，限收{number}人，参加时前往{place}。',
    '{event}在线登记从{date}{clock}开始，名额仅{number}位，地点{place}。',
    '更正一下{event}：现在换去{place}，日期和到场要求与原先相同。',
    '{event}停止举办，这次不需要去现场，原有预约也取消了。',
]
NEGATIVE = [
    '你的账户用于验证身份的数字是{digits}，保密，不要告诉陌生人。',
    '安全校验信息：{digits}，此次仅用于验证登录。',
    '正在验证账号，认证数字{digits}，不得转发给其他人。',
    '为保护账户，确认本次登录所用的安全数字{digits}，注意保密。',
    '宿舍里随口聊天，我刚从{place}走回来，挺舒服的。',
    '去{place}转悠了一圈回来了，现在躺着休息。',
    '刚在{place}散了会步，和室友聊聊天，没别的事。',
    '没有什么安排，只是说我从{place}回来了。',
    '以前参加{event}的趣事，谁愿意一起聊两句？',
    '大家有兴趣说说当年的{event}吗，就是闲聊回忆。',
    '随意敲键盘，字符{letters}完全没有实际意思。',
    '这段{letters}是乱输的字符串，用来测试键盘。',
    '{event}的东西已经全部提交，这条只是给你报一下结果。',
    '群名字怎么变成{event}了，哈哈，随便问问。',
]


def build_data():
    rng=random.Random(2026102804);rows=contrastive_data();seen={r['text'] for r in rows}
    for folder in ['gate-v2.1.2','confusions-v2.2.1','general-v2.2.1','campus-v2.2.1']:
        for split in ['valid','test']:
            seen.update(json.loads(line)['text'] for line in (ROOT/f'data/{folder}/{split}.jsonl').read_text().splitlines() if line.strip())
    for label,templates in [(0,NEGATIVE),(1,POSITIVE)]:
        added=0
        while added<1000:
            index=rng.randrange(len(templates))
            values=dict(course=rng.choice(['大学英语','高等数学','计算机基础','Python程序设计','大学物理','概率统计']),
                week=rng.randint(1,18),page=rng.randint(1,250),unit=rng.randint(1,18),
                event=rng.choice(['社团报名','志愿服务','学术讲座','培训','运动会','课程实验','工作会议']),
                date=f'{rng.randint(1,12)}月{rng.randint(1,28)}日',clock=f'{rng.randint(8,21)}:{rng.choice(["00","15","30","45"])}',
                number=rng.randint(15,180),place=rng.choice(['教学楼302室','图书馆前','办公区','活动中心','操场边','宿舍楼下']),
                digits=''.join(rng.choices('0123456789',k=rng.randint(3,12))),
                letters=''.join(rng.choices('abcdefghijklmnopqrstuvwxyz',k=rng.randint(5,35))))
            text=templates[index].format(**values)
            if label==0 and rng.random()<.2:
                text=rng.choice(['!','。','哈','啊','0','9','?','！'])*rng.randint(3,100)
            if text in seen:
                continue
            seen.add(text);added+=1
            rows.append(dict(text=text,label=label,source='synthetic_retention',family=f'retention-{label}-{index}'))
    for label in [0,1]:
        added=0
        while added<500:
            event=rng.choice(['社团活动','志愿服务','学术讲座','培训','运动会','课程实验','工作会议'])
            date=f'{rng.randint(1,12)}月{rng.randint(1,28)}日'
            place=rng.choice(['教学楼302室','图书馆前','办公区','活动中心','操场边','宿舍楼下'])
            if label==0:
                text=(rng.choice(['翻','整理','查看','浏览'])+rng.choice(['相册','影集','图库'])+
                    rng.choice(['看到','发现了','找到了'])+f'{date}在{place}参加{event}时的'+
                    rng.choice(['旧合影','老照片','留念照','过往影像'])+'，'+
                    rng.choice(['拿出来回忆一下','发给老朋友看看','叙叙当年的旧事','这个活动早已结束'])+'。')
            else:
                text=rng.choice([
                    f'刚看到{event}的安排，{date}要到{place}集合，请提前到场。',
                    f'翻日历发现{date}需要参加{event}，记得准时去{place}。',
                    f'我在安排{event}，{date}请到{place}，这次要按时参加。'])
            if text in seen:
                continue
            seen.add(text);added+=1
            rows.append(dict(text=text,label=label,source='synthetic_photo_contrast',family=f'photo-contrast-{label}'))
    positive=['15:30','会议时间另行通知','改到周五，地点不变','原来的活动取消了',
        '明天14点到301集合，带学生证','C语言实验报告，明晚20:00前提交','原定体测调整，具体方案另行通知',
        '广告设计会议定于明天14:00在图书馆举行，请勿泄露验证码。']
    negative=['123','000000','20261019','。。。。。。','收到，谢谢。','作业写完了，太开心了。',
        '晚饭吃什么','请问实验报告怎么提交','你有3条新消息','谢谢老师，我已经提交了']
    for label,texts in [(0,negative),(1,positive)]:
        rows.extend(dict(text=t,label=label,source='known_runtime_regression',family='known-regression') for t in texts for _ in range(16))
    rng.shuffle(rows)
    path=ROOT/'data/retention-v2.2.1/train.jsonl';path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
    return rows
