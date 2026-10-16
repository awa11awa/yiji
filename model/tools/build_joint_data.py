"""Build synthetic role/classification examples. Rules here only author training labels.

No labeling rule is shipped as a runtime notification filter. Real notices are local.
"""
import json, random, re
from pathlib import Path
from common import ROOT, save_json
from joint_model import LABELS, HEADS
from synthetic_vocabulary import LOCATIONS, make_time

EVENTS = {
    "作业": ["高等数学第三次作业", "大学英语阅读作业", "数据结构实验报告", "操作系统课程设计", "物理习题", "Python编程练习", "线性代数课后习题", "数据库大作业"],
    "报名": ["学生会干部纳新", "志愿者招募报名", "社团干事招新", "奖学金申请", "交换生申请", "运动会参赛报名", "大学生职业发展协会干部纳新面试"],
    "会议": ["班级会议", "部门周例会", "项目进度会议", "研究生学术交流会", "班委工作会议", "学生代表座谈会", "新生家长会", "软件需求评审"],
    "课程": ["高等数学课", "英语听力课", "计算机实验课", "专业选修课", "课程答疑", "数据分析实训", "算法设计辅导课"],
    "活动": ["交响音乐会《山河》", "民族音乐会《春晓》", "数学建模专题赛", "大数据建模竞赛", "志愿服务培训", "校园文化艺术节", "春季运动会", "健康知识讲座", "新生见面会", "青年合唱团音乐会", "大学生电影放映", "毕业典礼", "篮球友谊赛", "红色经典诵读活动", "竞赛培训"],
    "考试": ["学生体质健康测试", "英语期末考试", "普通话水平测试", "数学期末考试", "计算机等级考试", "专业课补考", "入学测试", "论文开题答辩"],
    "材料": ["学籍信息表", "个人简历", "家庭经济情况表", "实习证明", "安全承诺书", "附件二", "学生证领取", "教材归还登记"],
    "其他": ["学费缴纳", "宿舍搬迁", "校园卡换领", "值班安排", "校园体检", "班级集体合影", "返校登记", "离校手续办理"],
}

# Template sets deliberately differ between splits; subject vocabulary is shared.
TEMPLATES = {
"train": {
"schedule": ["{event}定于{start}在{location}举行。", "请于{start}到{location}参加{event}。", "【{event}】\n开始时间：{start}\n结束时间：{end}\n地点：{location}", "{event}将于{start}在{location}开始，至{end}结束。", "{event}：{start}；地点{location}。", "{event}安排在{start}，请提前到{location}。", "{event}时间：{start}-{end}，地点：{location}。", "{start}至{end}在{location}召开{event}。", "{start}到{end}在{location}举行{event}。", "{event}开始于{start}，结束于{end}，场地是{location}。", "{event}定于{start}至{end}进行，结束后退场。", "{event}正式时间为{start}~{end}，地点{location}。"],
"deadline": ["请在{due}前提交{event}。", "{event}截止时间为{due}，请按时完成。", "{event}最迟{due}交齐，过期不候。", "请大家于{due}前完成{event}，发送给班委。", "关于{event}的通知：请在{due}前填写表格并发送邮箱。"],
"mixed": ["{event}将于{start}在{location}举行，报名截至{due}。", "有意参加{event}的同学请在{due}前填写收集表，活动时间{start}，地点{location}。", "{event}时间为{start}到{end}，请于{due}前报名。"],
"pending": ["{event}即将开展，具体时间和地点另行通知，请关注后续安排。", "现组织{event}，日程待定，请先做好准备。", "{event}报名截至{due}，面试的具体时间和地点将在群里另行通知。"],
"booking": ["{event}工作已于{reference}正式启动，尚未完成的同学请自行预约参加测试。", "{event}现已开放预约，请大家登录平台选择个人场次。启动日期为{reference}，不是个人测试时间。", "{event}自{reference}起接受预约，个人时间由系统预约确定。", "统一工作启动日期为{reference}，请为{event}选择自己合适的时段。"],
"update": ["{event}延期至{start}在{location}举行，请知晓变更。", "{event}时间调整为{start}，地点改为{location}。", "{event}提交截止时间延长到{due}。", "{event}延期至{start}至{end}，地点改为{location}。"],
"cancel": ["原定{start}的{event}取消，请勿前往{location}。", "通知：{event}不再举行，请互相转告。", "{event}取消", "本次{event}取消。"],
"ignore": ["{event}真的好难，有人会吗？", "昨天参加的{event}真精彩，已经结束了。", "收到，谢谢老师。{event}的照片真好看。", "验证码123456，五分钟内有效，请勿泄露。", "广告推广：{event}课程限时优惠，点击购买。", "今天晚饭吃什么？", "{event}已经交过了，大家辛苦了。", "比赛结果19:30，{event}已经结束。", "欢迎加入{event}交流群，文明聊天。", "这是去年{reference}的{event}照片，大家看看。", "请问{event}怎么提交？", "{event}怎么写呀？", "{event}是否已经结束？"]
},
"valid": {
"schedule": ["现将{event}日程公布如下：{start}，会场选在{location}，{end}散场。", "{location}将举办{event}，举办时刻为{start}。"],
"deadline": ["{event}请赶在{due}之前交付，望相互提醒。", "本次{event}的最后期限是{due}。"],
"mixed": ["欢迎参加{event}，我们{start}在{location}见，登记最迟{due}。"],
"pending": ["{event}已列入计划，具体日程尚未确定，届时再告知。", "请在{due}前完成{event}报名，举办时间有待后续公告。"],
"booking": ["{event}已经从{reference}开放，请未参加的同学预约自己的时间。"],
"update": ["更正：{event}改期到{start}，会场{location}。", "{event}交付时间现更改为{due}。"],
"cancel": ["现决定取消{event}，相关人员无需到场。"],
"ignore": ["上次{event}留下的回忆太美好了。", "{event}我已经全部完成啦。", "你的登录校验码为654321。", "{event}有什么经验可以分享吗？", "吃饭去了，下午再聊。"]
},
"test": {
"schedule": ["有关{event}的安排已确定：{location}，{start}开始，{end}结束。", "请大家记住{start}，到{location}集合参加{event}。"],
"deadline": ["关于{event}，收取通道开放到{due}，请及时办理。", "{event}须不晚于{due}完成。"],
"mixed": ["【{event}】举办日期及时间{start}，场地{location}，收集表于{due}关闭。"],
"pending": ["{event}的时间、地点稍后在群中发布，敬请留意。", "{event}申请截止{due}，后续场次尚待安排。"],
"booking": ["请抓紧为{event}选择预约场次，统一启动于{reference}，每人的时段以预约结果为准。"],
"update": ["请注意{event}推迟到{start}开展，举办地{location}。", "{event}的截止日期已调整至{due}。"],
"cancel": ["本次{event}临时取消，后续若恢复会重新通知。"],
"ignore": ["我觉得{event}很有意思，以前参加过。", "老师，{event}这道题怎么做？", "活动已结束，感谢大家的支持。", "优惠券现已到账，立即领取。", "{event}去年{reference}就做完了。"]
}}

def render(template, values, annotate=True):
    parts, entities, end = [], [], 0
    kinds = {"event":"EVENT", "location":"LOCATION", "start":"START_TIME", "end":"END_TIME", "due":"DUE_TIME", "reference":"REFERENCE_TIME"}
    for m in re.finditer(r"\{(\w+)\}", template):
        parts.append(template[end:m.start()]); start = sum(map(len, parts))
        value = values[m[1]]; parts.append(value)
        if annotate: entities.append(dict(start=start, end=start+len(value), label=kinds[m[1]]))
        end = m.end()
    parts.append(template[end:])
    return "".join(parts), entities

def main():
    rng, seen = random.Random(20261016), set()
    output = ROOT / "data/joint-v2"
    output.mkdir(exist_ok=True, parents=True)
    for split, count in [("train",3600),("valid",360),("test",360)]:
        rows=[]
        while len(rows)<count:
            cat=rng.choice(list(EVENTS)); mode=rng.choices(list(TEMPLATES[split]), [30,25,12,10,6,10,7,24])[0]
            if cat in ("作业","材料","报名") and mode=="schedule" and rng.random()<.6: mode="deadline"
            template=rng.choice(TEMPLATES[split][mode])
            if split=='train' and mode=='deadline' and rng.random()<.12:template=rng.choice(['请完成{event}。','{event}请大家认真填写，时间还没确定。'])
            values=dict(event=rng.choice(EVENTS[cat]),location=rng.choice(LOCATIONS+['301','图书馆','线上']),start=make_time(rng),end=rng.choice(['20:00','21点','2026年10月30日20:00','下午5点','次日凌晨1点']),due=make_time(rng,deadline=True),reference=make_time(rng))
            text,entities=render(template,values,mode!='ignore')
            if mode!='ignore' and rng.random()<.35:
                prefix=rng.choice(["各位同学：\n大家好！\n", "2026年是学院成立90周年，为丰富校园生活，现通知如下：\n", "根据本学期工作计划，提升人才培养质量，现将有关事项说明如下。\n", "【工作通知】\n"])
                text=prefix+text
                for e in entities:e['start']+=len(prefix);e['end']+=len(prefix)
            if mode!='ignore' and rng.random()<.15:
                text+="\n参与名额50人，总获奖率约50%，咨询QQ群1067495727。"
            if mode!='ignore' and rng.random()<.05:
                text+="\n请勿转发广告，报名系统验证码不要泄露。"
            if text in seen:continue
            seen.add(text)
            kind='MIXED' if mode=='mixed' or mode=='pending' and '{due}' in template else 'DEADLINE' if '{due}' in template or mode=='deadline' else 'SCHEDULE'
            rows.append(dict(text=text,entities=entities,route='IGNORE' if mode=='ignore' else cat,kind=kind,state='TO_BE_ANNOUNCED' if mode=='pending' else 'BOOKING_REQUIRED' if mode=='booking' else 'KNOWN',operation=mode.upper() if mode in ('update','cancel') else 'NEW',source='synthetic',template_id=f'{split}-{mode}'))
        if split=='train':
            for index,line in enumerate((ROOT/'data/real/user_notices.jsonl').read_text(encoding='utf-8').splitlines() if (ROOT/'data/real/user_notices.jsonl').exists() else []):
                row=json.loads(line)
                times=[e for e in row['entities'] if e['label']=='TIME']
                for n,e in enumerate(times):e['label']=['DUE_TIME','REFERENCE_TIME','START_TIME'][index] if index<2 or n==0 else 'END_TIME'
                row.update(route=['报名','考试','活动'][index],kind=['MIXED','SCHEDULE','SCHEDULE'][index],state=['TO_BE_ANNOUNCED','BOOKING_REQUIRED','KNOWN'][index],operation='NEW')
                rows.append(row)
        (output/f'{split}.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows),encoding='utf-8')
    save_json(output/'metadata.json',dict(labels=LABELS,heads=HEADS,seed=20261016,split='Disjoint synthetic sentence templates; vocabulary shared. Three real notices only in train; original concert excluded.',warning='Synthetic scores are not real-world accuracy.'))
    print(output,flush=True)

if __name__=='__main__':main()
