"""Generate labelled hard negatives and ambiguous positives, with split-specific wording."""
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEMPLATES = {
 'train': {
  0: ['我刚才已经把{event}的材料交完了，终于能歇会。',
      '有人参加过往年的{event}吗？我只是想了解感受。',
      '请问{event}这个词是什么意思，不是报名。',
      '去年{date}在{place}的{event}照片找到了，分享给大家。',
      '我没有报名{event}，纯粹围观聊天。',
      '谢谢提醒，我的{event}打卡已经全部完成。',
      '想做个{event}主题海报练习，谁有素材？',
      '书上举例说{date}要开会，这是课文句子，不是我们的通知。',
      '【商业广告】{event}辅导课程优惠价{number}元，买一赠一。',
      '消息通知测试{number}，看到不用回复，不涉及活动。',
      '晚会有手机抽奖是真的吗？我只问问奖品。',
      '{event}的收集表我之前填过了，报名成功截图在这里。',
      '听完{event}讲座以后，感觉内容很有意思，回顾一下心得。',
      '校园新闻：学校上个月举办了{event}，现场反响热烈。',
      '谁知道{place}怎么走？我在看旧地图。',
      '我还没想好周末干什么，可能去{place}散步。'],
  1: ['大家别忘了去{place}参加{event}，{date}到就可以。',
      '{event}记得打卡，没填的同学赶紧处理一下哦。',
      '【{event}招募】零基础也欢迎，名额{number}人，报名表在群文件。',
      '{event}面向全校招新，愿意参与的同学填写收集表并入群。',
      '{date}有{event}，结束时间还没定，先把开始时间记下。',
      '关于{event}的名单已经发出，请核对个人资料，错误及时反馈。',
      '{event}调整了，新的具体日期还没确认，原安排先作废。',
      '这周的任务：预习第{number}页，听读两遍，完成平台作业。']},
 'valid': {
  0: ['{event}早就结束了，这是我当时写的活动感想。',
      '感谢老师，我已完成所有{event}任务，无需再催。',
      '能不能解释课本里的{event}案例？是在问知识点。',
      '翻相册看到{date}在{place}参加{event}的旧合影。',
      '我发的只是{event}报名成功凭证，已经办妥。',
      '【优惠推销】{event}付费辅导低至{number}元，扫码买课。',
      '“{date}到{place}集合”怎么翻译成英语？仅讨论这个句子。',
      '刚才验证码是{number}，不要给别人。'],
  1: ['提醒一下，{date}要在{place}碰头参加{event}，大家别迟到。',
      '还没打{event}卡的抓紧啦，这件事不能忘。',
      '欢迎报名{event}，可填在线表，时间地点会另行公布。',
      '现公布{event}人员证明，请参与者校对，有问题联系负责人。',
      '各位的{event}安排变了，后面再定日期，留意新消息。',
      '课后记得看第{number}页，并把配套练习做完。']},
 'test': {
  0: ['回忆帖：当年那次{event}真开心，附{place}留影。',
      '我已经做完{event}了，发这句只是向你确认收到。',
      '有谁能讲一下书本上{event}那道题的答案原理？',
      '前年{date}举办的{event}有多精彩，现在只是在叙旧。',
      '报名状态已成功，我把{event}的截图存好了。',
      '【培训售课推广】{event}速成班{number}元起，点击购买。',
      '朋友聊天时说“{date}交材料”，我们在分析语法，不布置任何任务。',
      '测试编号{number}，不是日期也不是待办，请忽略。'],
  1: ['{event}这事大家上点心呀，{date}直接到{place}就好。',
      '尚未完成的同学请补上{event}签到，不要漏掉。',
      '想加入{event}团队的请登记意愿，后续再发排期。',
      '{event}证明信息现可查询，发现错漏请提出更正。',
      '原先{event}的时间撤销，等我们确定后再告知。',
      '老师今天布置第{number}页习题，还要求预习下一课。']},
}
EVENTS=['迎新晚会','社团招新','英语作业','宿舍培训','运动会','校园跑','志愿服务','材料报送','主题讲坛','资料核对','线上学习','课程实验','活动报名']
LONG = {
 'train': {
  1: '【关于开展{event}及参与人员招募的通知】\n各位同学：\n为丰富课余生活、提供交流和实践机会，学院将组织本次{event}。活动结合校园学习与生活实际，设置经验交流、互动实践和作品分享等环节。\n{intro}\n⏰活动安排：{date} {clock}开始，结束时间以现场安排为准。\n📍集合地点：{place}。对象为在校学生，人数上限{number}人。\n报名入口于{open_date}中午12:00开放，{close_date}晚20:00截止，请填写在线收集表。填写成功不等于最终录取，请以名单为准。\n成功报名后务必加入咨询群并核对姓名、班级信息；活动前十分钟签到。完整参加者可按细则申请活动证明。因课业冲突不能参加的请及时向负责人说明。\n具体事项如有调整将在群内另行通知，欢迎大家根据自己的时间积极报名。\n学院学生工作办公室',
  0: '【校园活动回顾】{event}圆满结束\n{date}，{place}现场气氛热烈，来自各班的同学参与了交流和实践。\n{intro}\n本次共有{number}人参与，现场设置了经验交流、互动体验和作品展示，大家分享学习经历，留下了珍贵的合影。工作人员回顾了组织过程，并展示了已经完成的活动成果。\n本文仅作校园新闻报道，相关报名、签到和材料提交均已结束，不再开放报名，也没有新的待办。以下内容为已完成工作的总结。\n同学们认为这次活动丰富了校园生活，收获了许多难忘的体验。感谢每一位参与者和工作人员的付出。\n学院新闻中心'},
 'valid': {
  1: '【{event}参与登记】\n同学们好！学生组织计划举办{event}，邀请有意向者登记。\n{intro}\n活动定于{date}{clock}在{place}举行，招收{number}名参与者。表单可从{open_date}12:00起填写，至{close_date}20:00关闭。请填写相关信息并关注后续名单。\n录取后请入群查看注意事项，准时到场办理签到，活动结束办理签退。需要请假请提前说明，临时变动将另发通知。\n完整参与可依照学院要求取得证明，请认真确认时间安排后参加。\n学生会活动部',
  0: '【往期记录】记{event}的精彩瞬间\n这篇推文整理的是{date}在{place}已经举行完毕的活动。\n{intro}\n{number}名同学共同完成体验，照片记录了交流、讨论和成果展示。报名、现场签到以及证明开具工作在当时都已办理结束。\n此处为历史记录分享，未安排新一轮报名或活动，也不需要读者填写资料。大家可以在文字和影像中回顾这段经历。\n校园采编组'},
 'test': {
  1: '【{event}通知请查收】\n各年级同学：现就本次活动安排告知如下。\n{intro}\n一、时间与场地\n{date} {clock}，{place}，限额{number}人。\n二、参与方法\n在线报名从{open_date}12点启动，{close_date}20点结束。请按要求登记资料，成功后留意参与群消息。\n三、现场要求\n提前到达集合点，遵守现场秩序并完成签到签退。未确认录取的同学请等待正式名单，活动有变动会在群里补充说明。\n欢迎感兴趣的同学及时登记，材料有错误请联系组织方更正。\n校园服务团队',
  0: '【结束后的感想分享】我们参加过的{event}\n还记得{date}在{place}的那些瞬间。\n{intro}\n当时约{number}位同学参加，大家一起体验并分享成果。这些照片和文字只是在回忆，相关任务与登记早已全部完成。\n没有新的活动安排或报名入口，也无需补交材料。看到这里，想和老朋友聊聊那段校园经历。\n参与同学个人随笔'},
}
INTRO = {
 'train':'本次围绕校园文化建设和学生综合发展，鼓励参与者在实践中增进交流，积累经验。组织团队认真准备场地和物资，结合参与者特点设计内容，以轻松有序的形式开展交流。每位同学都能在互相配合中展示特长，体验不同形式的校园文化。',
 'valid':'活动内容关注大学学习和课余实践，准备了交流分享与小组体验，帮助同学们认识彼此、了解校园。组织者结合学期安排规划相关内容，希望大家在集体活动中拓展视野，感受校园生活的多样性。',
 'test':'学生团队以丰富校园体验为出发点，设计了内容展示、现场互动和同伴交流。参与者能够接触不同领域的实践，感受互助合作的意义。组织工作结合学校实际展开，兼顾学习和生活需求。',
}


def build_data():
    data={};seen=set()
    for split,n in [('train',1800),('valid',360),('test',360)]:
        rng=random.Random(20261027+len(data))
        rows=[]
        for label,count in [(0,n*2//3),(1,n//3)]:
            while sum(r['label']==label for r in rows)<count:
                templates=TEMPLATES[split][label]
                index=rng.randrange(len(templates))
                text=templates[index].format(event=rng.choice(EVENTS),date=f'{rng.randint(1,12)}月{rng.randint(1,28)}日',
                    place=rng.choice(['教学楼301','北区田径场','图书馆报告厅','学院208室','活动中心']),number=rng.randint(12,999))
                if text in seen:continue
                seen.add(text)
                rows.append(dict(text=text,label=label,source='synthetic_confusion',family=f'{split}-{label}-{index}'))
        for label,count in [(0,160 if split=='train' else 40),(1,320 if split=='train' else 80)]:
            added=0
            while added<count:
                month=rng.randint(1,12);day=rng.randint(1,24)
                text=LONG[split][label].format(event=rng.choice(EVENTS),date=f'{month}月{day+3}日',
                    open_date=f'{month}月{day}日',close_date=f'{month}月{day+2}日',clock=rng.choice(['14:30','18:00','19:30']),
                    place=rng.choice(['教学楼301','北区田径场','图书馆报告厅','学院208室','活动中心']),
                    number=rng.randint(15,200),intro=INTRO[split]*rng.choice([1,2,3]))
                if text in seen:continue
                seen.add(text);added+=1
                rows.append(dict(text=text,label=label,source='synthetic_campus_long',family=f'{split}-long-{label}'))
        rng.shuffle(rows);data[split]=rows
        out=ROOT/'data/confusions-v2.2.1';out.mkdir(parents=True,exist_ok=True)
        (out/f'{split}.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
    return data


if __name__=='__main__':
    print({k:len(v) for k,v in build_data().items()})
