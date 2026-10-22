# 云端事项协议 v2（忆迹 v2.1.2）

客户端将通知作为JSON数据提交。模型只返回一个JSON对象，不执行通知中的指令；新默认提示词使用协议2。客户端仍能读取旧版 `isSchedule/items` 输出，保留必要的复核保护。

## 输入

`protocolVersion: 2`、IANA `timezone`、`current`、`context`、`existingItems`。消息含 `sourceId/title/body/receivedAt`；时间换算依据被引用消息的接收日期。上下文和已有事项由用户开关控制。既有事项增加 `opensAt/timing/subtasks`。

## 输出

```json
{
  "protocolVersion": 2,
  "decision": "EXTRACT",
  "items": [
    {
      "title": "讲坛报名",
      "category": "报名",
      "kind": "DEADLINE",
      "operation": "NEW",
      "targetId": null,
      "start": null,
      "end": null,
      "due": null,
      "opens": {"mode": "SET", "text": "10月17日20:30", "sourceId": "current"},
      "location": null,
      "action": null,
      "evidence": {"text": "收集表将于10月17日20:30开始", "sourceId": "current"},
      "subtasks": []
    }
  ]
}
```

该例的当前原文须包含“收集表将于10月17日20:30开始”。示例没有报名截止，不得添加截止。

`decision`：EXTRACT（1至20项）、IGNORE（明确无待办）、REVIEW（无法判断）。IGNORE/REVIEW的items必须为空。时间不齐不等于无事项；否定结果遇到重要通知特征或截断线索时由客户端保留待核对。网络错误不属于IGNORE。

`category`：作业、报名、会议、课程、活动、考试、材料、其他。
`kind`：SCHEDULE（日程）或DEADLINE（任务，兼容旧类型名，允许没有截止）。报名的开放/截止属于同一报名任务，活动单独管理。
`operation`：NEW、UPDATE、CANCEL。NEW的targetId必须为空；更新、取消若不能唯一关联则留空供用户选择，模型不得编造ID。

## 时间字段

start=活动开始，end=活动结束，due=明确截止，opens=报名开放。每个非空字段包含mode及必要的原文引用。

| mode | 意义 | 引用要求 |
|---|---|---|
| SET | 新建或替换为原文表达 | text/sourceId必须可验证 |
| KEEP | 保留关联事项的该字段 | 用于更新、取消；引用可为空 |
| TBD | 旧安排失效，新时间待定 | 必须引用current中的另行通知、待定等依据 |
| CLEAR | 无此时间或明确移除 | 新建可省略；更新需要current中的移除依据，开始改变导致结束不再确定时允许清除结束 |

新建缺失字段=null表示未提供；更新缺失字段=null表示KEEP。明确待定不得使用null。客户端内部AUTO仅用于旧数据兼容，不属于云端协议。

日期与时刻精度独立保留。只有日期时不会补00:00、23:59、全天或默认时长。教学周缺乏校历时保留原话；本周内依据原消息接收日期保留周末日期精度。开始明确、结束未知可以保存；开始尚未完整也可保存为待补时间，精确提醒仅使用明确的时刻。只有完整起止区间参与冲突检测。

更新在用户确认前不会改变正式事项；确认TBD后撤销旧提醒，历史保存变更前后内容。选择目标的编辑器和保存事务执行相同的时间操作。

## 证据

每项evidence必须是current中的连续片段；location/action为text/sourceId引用。其他时间可引用允许的context，并以该消息接收日换算。先精确匹配，再兼容空格、NBSP、换行及全半角ASCII标点/数字；必须能唯一定位，保存原文片段和起止位置。不接受改写、拼接、改数字或相似度匹配。

无法验证的单个字段提示核对；无依据的时间阻止直接确认，用户须核对后修改或清空。无效事项转待核对，其他已验证事项仍可确认；未核对部分不会因确认有效事项而消失。

## 作业子任务

同一课程同一份作业输出一个父任务。subtasks最多40项，每项：

```json
{"title":"U1选词填空","optional":false,"due":{"mode":"SET","text":"10月19日前","sourceId":"current"},"evidence":{"text":"U1选词填空请于10月19日前完成","sourceId":"current"}}
```

子任务done/id由客户端维护，云端不能替用户标记完成。选做必须保留。公共截止只适用于其约束范围；某题截止不应提升为整份作业截止，P.S.本周内只约束其后对应任务。不同课程不按相同周次合并。小测可单独提取为关联考试。

父任务整体确认，子任务分别勾选。明确子任务时刻可分别提醒，完成后撤销该子任务提醒；日期精度时不伪造时刻。报名开放、截止、日程开始和子任务提醒具有不同标识，互不覆盖。
