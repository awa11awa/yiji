"""Additional Chinese message examples; wording templates are disjoint by split.

Labels describe whether there is an actionable arrangement, not its subject area.
These are assistant-generated examples, not an independently collected chat set.
"""
import json
import random
from common import ROOT

SUBJECTS = ['项目评审', '客户沟通', '面试', '证件办理', '家长会', '家庭聚餐', '取件', '上门维修', '社区志愿服务', '出行安排', '材料核对', '费用报销', '合同确认', '朋友聚会']
PLACES = ['公司二楼会议室', '社区服务中心', '办事大厅', '车站东门', '家里', '小区南门', '一楼前台', '线上会议室']
TEMPLATES = {
 'train': {
  1: ['{event}定在{date}{clock}，请到{place}，别迟到。',
      '麻烦你在{date}前把{event}的资料发给我，收到后我来核对。',
      '原先的{event}先取消，新的安排等我确认后告诉你。',
      '{event}改在{date}，地点还是{place}，请更新一下日程。',
      '请核对{event}的信息，有错误在{date}前反馈。',
      '明天出门前记得带身份证和{event}需要的材料。',
      '{event}可以开始登记了，想参加的请填写表格，具体时间稍后通知。',
      '我们约好{date}{clock}在{place}见面，商量{event}。'],
  0: ['我已经把{event}办好了，谢谢提醒。',
      '去年参加{event}的时候拍了这张照片，现在翻出来看看。',
      '请问{event}这个说法是什么意思？我在学习词语。',
      '“{date}{clock}到{place}集合”是翻译练习，不是给你的安排。',
      '看到一则{event}的新闻，报道的是上个月已经结束的事情。',
      '我在{place}休息，今天过得挺开心。',
      '【推广广告】{event}培训优惠{number}元起，点击购买。',
      '测试数据{number}，只是在调试，和{event}安排没有关系。']},
 'valid': {
  1: ['别忘了我们的{event}，{date}{clock}去{place}碰头。',
      '{event}的文件最晚{date}交给负责人，请提前准备。',
      '接到变更：{event}暂缓举行，之后另定日期。',
      '现请相关人员确认{event}名单，发现漏项在{date}前提出。',
      '有意参加{event}的现在登记，地点和时段待公布。'],
  0: ['有关{event}的事情我都处理妥当了，只是回复一下。',
      '那次在{place}进行的{event}已经过去好久了，聊聊往事。',
      '想请教一下教材里{event}的概念，并无办理需求。',
      '老师让分析“{date}{clock}办{event}”这句话的语法。',
      '【付费宣传】报名{event}辅导课只要{number}元。']},
 'test': {
  1: ['提醒：{date}{clock}前往{place}办理{event}，请按时到达。',
      '关于{event}，请于{date}之前提交需要的资料。',
      '原来的{event}日期作废，等确定后重新发给大家。',
      '{event}参与人员现可查阅登记结果，请在{date}前纠正错漏。',
      '想加入这次{event}的请先留下信息，具体排期我们后面再联系。'],
  0: ['{event}已经全部完成，这次仅是向你确认结果。',
      '回顾{event}：{place}的旧合影让我想起以前的时光。',
      '谁能讲解书中关于{event}的例子？我没有新安排。',
      '朋友说“{date}{clock}去{place}”，我们正在讨论这种句式怎么表达。',
      '【课程售卖】{event}速成服务售价{number}元，限时折扣。']},
}

def build_data():
    rng = random.Random(20261028)
    data, seen = {}, set()
    for split, count in [('train', 800), ('valid', 160), ('test', 160)]:
        rows = []
        while len(rows) < count:
            label = len(rows) % 2
            templates = TEMPLATES[split][label]
            index = rng.randrange(len(templates))
            text = templates[index].format(event=rng.choice(SUBJECTS), place=rng.choice(PLACES),
                date=f'{rng.randint(1, 12)}月{rng.randint(1, 28)}日',
                clock=f'{rng.randint(8, 20)}:{rng.choice(["00", "15", "30", "45"])}', number=rng.randint(50, 999))
            if text in seen:
                continue
            seen.add(text)
            rows.append(dict(text=text, label=label, source='synthetic_general', family=f'{split}-{label}-{index}'))
        rng.shuffle(rows)
        data[split] = rows
        path = ROOT / 'data/general-v2.2.1' / f'{split}.jsonl'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in rows))
    return data
