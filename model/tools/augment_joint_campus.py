"""Additional explicitly synthetic short-notice and notification-summary training rows."""
import json, random
from common import ROOT
from build_joint_data import render
from synthetic_vocabulary import make_time

def main():
    rng=random.Random(161026);path=ROOT/'data/joint-v2/train.jsonl'
    rows=[json.loads(l) for l in path.read_text(encoding='utf-8').splitlines()];seen={r['text'] for r in rows}
    for i in range(450):
        values=dict(start=make_time(rng),due=make_time(rng,deadline=True),location=rng.choice(['301','302','教学楼A201','图书馆301','图书馆302','行政楼201']),event=rng.choice(['集合','签到','报到']))
        mode=i%6;route='活动';kind='SCHEDULE'
        if mode==0:template='{start}到{location}{event}，带学生证'
        elif mode==1:
            values['event']=rng.choice(['班级会议','会议','开会','广告设计会议']);route='会议';values['end']=rng.choice(['15:00','17点','下午4点']);template=rng.choice(['{start}至{end}在{location}召开{event}。','{event}定于{start}在{location}举行，请勿泄露验证码。'])
        elif mode==2:
            route='报名';kind='DEADLINE';values['event']=rng.choice(['报名表','参赛登记表','竞赛报名申请']);template='{due}前提交{event}'
        elif mode==3:
            route='作业';kind='DEADLINE';values['event']=rng.choice(['高数 P38 第1-5题','C语言实验报告','数学作业','第3章第2、4题','高数课后习题第1、3、5题']);template=rng.choice(['请完成{event}','{event}，{due}前交','请于{due}前提交{event}，上传课程平台。'])
        elif mode==4:
            route='IGNORE';template=rng.choice([f'你有{rng.randint(1,99)}条新消息',f'{rng.randint(1,50)}条新消息','班级群','数学作业群','微信','登录验证码123456','验证码123456','晚饭吃什么','请问实验报告怎么提交'])
        else:
            values['event']=rng.choice(['讲座','竞赛培训','志愿服务培训']);template=rng.choice(['{start}在{location}举行{event}','{event}定于{start}在{location}开始。'])
        text,entities=render(template,values,route!='IGNORE')
        if text in seen:continue
        seen.add(text);rows.append(dict(text=text,entities=entities,route=route,kind=kind,state='KNOWN',operation='NEW',source='synthetic',template_id='train-campus-short'))
    path.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows),encoding='utf-8')
    print('Train rows',len(rows),flush=True)
if __name__=='__main__':main()
