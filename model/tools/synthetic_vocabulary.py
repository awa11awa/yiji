import random

LOCATIONS = ['行政楼三楼会议室', '第一教学楼201教室', '第二教学楼305室', '学校大礼堂', '体育馆', '图书馆报告厅', '大学生活动中心', '科技楼B座102室', '文体中心音乐厅', '市民艺术中心', '文化艺术馆', '南区操场', '学院一楼大厅', '东校区综合楼A203', '实验楼402室', '西校区报告厅', '腾讯会议', '线上会议室', '音乐厅', '创新楼二层多功能厅', '福建大剧院', '城市文化交流中心', '校史馆', '美术馆', '北区篮球场']

def make_time(rng: random.Random, *, deadline=False) -> str:
    month, day = (rng.randint(1, 12), rng.randint(1, 28))
    hour, minute = (rng.randint(8, 22), rng.choice([0, 10, 15, 20, 30, 45, 50]))
    date = rng.choice([f'2026年{month}月{day}日', f'{month}月{day}日', f'2026-{month:02d}-{day:02d}', '今天', '明天', '后天', '本周五', '下周一', '下周三'])
    clock = rng.choice([f'{hour:02d}:{minute:02d}', f'{hour}点{minute:02d}分', f'下午{rng.randint(1, 5)}点半', f'上午{rng.randint(8, 11)}点'])
    if deadline and rng.random() < 0.35:
        return rng.choice(['今晚23:59', '今晚23.52', '明晚22:00', '今天中午12点', '明天晚上九点', '今晚十二点'])
    if rng.random() < 0.15:
        return date
    return date + rng.choice(['', ' ']) + clock
