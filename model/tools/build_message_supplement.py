"""Extra training wording for general Chinese messages; no validation/test changes."""
import json
import random

from common import ROOT

POSITIVE = [
    '麻烦{date}{clock}带着资料来{place}，我们把{subject}的事情办一下。',
    '这次{subject}还缺你的确认，{date}之前回复负责人即可。',
    '提醒还没完成{subject}的人：本周要处理完，别漏了。',
    '原计划的{subject}暂停，之后等新的通知，旧的时间不用去了。',
    '{subject}的地址换了，现在去{place}，其他事项照原来的执行。',
    '新通知：{subject}报名开始，有需要的填写登记信息，具体日期后续再说。',
    '你申请的{subject}需要补资料，{date}之前上传，上传后告诉我一声。',
    '{date}{clock}在{place}见，我们一起处理{subject}，记得空出时间。',
    '请大家检查刚发的{subject}名单，姓名或信息不对的及时找我改。',
    '明天要办理{subject}，请提前把证件准备好，到{place}集合。',
    '还没有签到的抓紧补一下，{subject}的登记表今晚就关闭了。',
    '{subject}延期了，新的日子尚未确定，这几天先不要按旧时间前往。',
    '【服务提醒】您的{subject}预约为{date}{clock}，地点{place}，请按约到场。',
    '【办理提示】{subject}尚有步骤未完成，请在{date}前完成确认。',
    '家里这次{subject}由你负责，{date}之前记得办好，有问题联系我。',
    '群里的{subject}收集表还差几个人，请没有填的同学尽快补齐。',
]
NEGATIVE = [
    '{subject}我昨天就处理完了，今天只是在聊自己的经历。',
    '之前在{place}参加{subject}时拍的照片，给你看看当时的样子。',
    '这个{subject}的例子怎么理解？我问的是教材里的知识。',
    '谢谢，你说的{subject}我已经做好了，放心吧。',
    '当年{subject}真是很难忘，和老同事聊起这件往事。',
    '【商品推介】{subject}教学视频{price}元一套，购买链接见店铺。',
    '有人买过这个{subject}辅导服务吗？只想看看大家的评价。',
    '教材例句是“{date}{clock}在{place}办理{subject}”，帮我看看怎么翻译。',
    '我在练习写通知，用{subject}作题目，这段只是课堂例文。',
    '{subject}的流程我早就走完了，发给你的只是完成记录。',
    '{place}的那场{subject}早已散场，下面分享一些当时的见闻。',
    '手机上那条{subject}是很久以前的截图，我翻相册看到的。',
    '今天随便聊聊{subject}这个词的由来，没有什么新情况。',
    '【好物推广】{subject}资料包限时售价{price}元，店内可购买。',
    '请教一下，{subject}这道题应该用什么公式？',
    '听说去年{subject}很热闹，我没去，想听大家讲讲经历。',
    '{subject}的截图里写了{date}，那只是旧版页面，不是新安排。',
    '收到啦，我的{subject}昨天已经核对无误，给你一个完成回复。',
    '“记得{date}前完成{subject}”这句是我抄的示例，不是对你的要求。',
    '周末可能到{place}转转，还没有定下来，只是在闲聊。',
]
SUBJECTS = ['班级活动','会议签到','实验报告','线上课程','信息登记','体质锻炼','讲座报名','材料提交',
            '项目汇报','报销申请','合同签署','客户回访','办证预约','维修预约','快递取件','车票改签',
            '志愿者登记','健康检查','家庭聚餐','费用缴纳','出行确认','社团招募','培训反馈']
PLACES = ['办公室','图书馆门口','服务大厅','二楼会议室','小区活动室','教学楼302室','车站入口','社区前台']


def build_data():
    rng = random.Random(2026102802)
    rows, seen = [], set()
    for label, templates in [(0, NEGATIVE), (1, POSITIVE)]:
        while sum(r['label'] == label for r in rows) < 1000:
            index = rng.randrange(len(templates))
            text = templates[index].format(subject=rng.choice(SUBJECTS), place=rng.choice(PLACES),
                date=f'{rng.randint(1,12)}月{rng.randint(1,28)}日',
                clock=f'{rng.randint(8,20)}:{rng.choice(["00","15","30","45"])}', price=rng.randint(20,599))
            if text in seen:
                continue
            seen.add(text)
            rows.append(dict(text=text,label=label,source='synthetic_supplement',family=f'supplement-{label}-{index}'))
    rng.shuffle(rows)
    path = ROOT/'data/supplement-v2.2.1/train.jsonl'
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
    return rows
