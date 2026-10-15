# 忆迹 v1.1.0

《忆迹——基于多源通知语义关联与时序冲突检测的重要事项智能管理系统》Android 应用。

面向校园通知、活动安排和截止任务，提供多源通知接入、重要事项提取、关联核对、变更溯源与时间冲突检测。第一版优先支持本地使用，AI 服务为可选配置。

## 使用

支持 Android 8.0 及以上。在此工程中运行 `assembleDebug` 可生成 v1.1.0 开发测试包，未上架应用商店。历史 v1.0.0 APK 不包含本次规则更新。

1. 打开「通知箱」或首页的「粘贴通知」，导入一条通知。
2. 点击「核对确认」，补充缺失字段，选择新建、合并来源、更新或取消。
3. 在「设置」中允许提醒通知；需要更准时的提醒时，再开启「精确提醒」。
4. 使用自动通知接入时，在设置中授权通知读取、选择来源应用、开启采集。
5. 在事项详情中查看来源和历史，在首页查看时间冲突。

无需账号或模型密钥即可使用本地提取规则和全部事项管理流程。

## v1.1.0 本地识别

规则结合事项词、校园动作和时间线索进行判断，支持集合、签到、交表、填报、领取、报到、上传等表达。来源群名只提供辅助上下文，不会仅因“作业群”“考试群”就把普通聊天识别为事项。

| 原文示例 | 本地处理 |
| --- | --- |
| 明天14点到301集合，带学生证 | 提取时间和地点，提示补充结束时间后确认 |
| 明早八点半签到 | 识别明天 08:30，保留待确认状态 |
| 今晚九点前交表 | 识别为今天 21:00 截止的待办 |
| 明天下午２－３点在图书馆３０１集合 | 兼容全角数字及省略单位的时间区间 |
| 请在十月二十五日前交表 | 识别日期并要求补充具体时间，不自动假定 23:59 |
| 作业群中的“晚饭吃什么” | 保留在已忽略列表，不生成事项建议 |

时间日期的推断会提示核对；所有建议仍须用户确认后进入日程。本次更新只增强本地识别，不改变系统通知的采集边界。详见 `RELEASE_NOTES_v1.1.0.md`。

本次调试包与 v1.0.0 签名不同，不能直接覆盖安装。升级前先导出备份并保存到应用外位置，再卸载旧版、安装新版、恢复备份。验证结果见 `VALIDATION_v1.1.0.md`。

## 已实现

| 功能 | 实现 |
| --- | --- |
| 多源接入 | 系统通知监听、粘贴、Android 文本分享、手动新增 |
| 来源控制 | 应用选择、暂停采集、忽略关键词、重复通知去重 |
| 提取 | 标题、类别、日程或截止类型、时间、地点、动作 |
| 日期解析 | 完整日期、中文数字月日、斜线/点号日期、今天/明天/后天、今晚/明早、本周/下周及星期表达 |
| 缺失信息 | 提示补充日期与结束时间；不把未确认事项加入日程 |
| 关联 | 根据名称和时间提出合并建议；变更和取消均须用户确认 |
| 变更历史 | 新增、编辑、合并、更新、完成、取消、恢复的前后快照 |
| 冲突 | 检测已确认且进行中的日程重叠；截止任务不占用时间 |
| 管理 | 增删改、完成、取消、恢复、搜索、类别与状态筛选、日期分组 |
| 提醒 | 提前提醒、编辑重排、完成或取消后撤销、重启后重排 |
| 溯源 | 原通知正文、来源、接收时间、历次修改 |
| 数据 | Room 本地数据库、JSON 备份、校验后整体恢复、清空数据 |
| AI | 可选 FastAPI 服务、语义提取与关联建议、模型 JSON 及关联目标校验、不可用时回退本地规则 |

## 运行 Android 工程

用 Android Studio 打开此目录。安装 Android SDK Platform 35 与 Build Tools 35.0.0，使用 JDK 17 或 21。

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

macOS/Linux 使用 `./gradlew`。首次构建需要下载依赖。原生技术栈：Kotlin 2.1.20、Jetpack Compose、Room 2.7.1、AGP 8.9.2、Gradle 8.11.1。`local.properties` 是本机 SDK 路径，不随源码压缩包交付。

国内网络可使用附带的镜像配置：`gradlew.bat --init-script scripts/mirrors.init.gradle.kts testDebugUnitTest lintDebug assembleDebug`。Gradle Wrapper 固定版本并校验 SHA-256；如果分发下载不可达，可把 `gradle/wrapper/gradle-wrapper.properties` 的下载地址改为 `https://repo.huaweicloud.com/gradle/gradle-8.11.1-bin.zip`，保留现有校验值。

## 可选提取服务

在 `backend` 目录执行：

```powershell
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:YIJI_ACCESS_TOKEN = '你设置的服务访问令牌'
.venv\Scripts\python.exe -m uvicorn app:app --host 127.0.0.1 --port 8000
```

默认服务也使用本地规则。接入现成模型时，在服务端设置 `MODEL_BASE_URL`、`MODEL_API_KEY`、`MODEL_NAME`；服务兼容支持 JSON 输出的 Chat Completions 接口。所有模型密钥保存在服务端，手机仅填写服务地址和服务访问令牌。服务根路径包含 `/health` 与 `/v1/extract`。

模拟器服务地址为 `http://10.0.2.2:8000`。真机调试时，电脑和手机连接同一局域网，明确使用 `--host 0.0.0.0`，手机填写电脑局域网地址。调试 APK 支持 HTTP；正式构建只允许 HTTPS。开启 AI 前，设置页面会说明发送内容。

```powershell
.venv\Scripts\python.exe -m pytest tests -q
```

## 适用边界

- 自动接入读取应用发布的系统通知，不能补齐未产生通知的聊天记录。
- 本地规则擅长常见明确表达；复杂语义需要配置模型服务，结果仍须用户核对。
- 一次提取一件主要事项，包含多项安排的长通知可分段导入。
- 原文未写年份时会提示核对；缺少日期或结束时间时必须补充后确认。
- 第一版可手动录入课程作为日程；未实现循环课程导入、OCR、跨设备同步。
- 未开启精确提醒时，系统可能延迟提醒；部分手机需要允许后台运行。
- 本地文件备份包含通知原文，不包含系统权限、应用选择或服务访问令牌。
- 单个备份最大 10 MB，每类记录最多 20,000 条；超出时会提示清理后再导出。

Android 权限与构建版本依据：[通知监听](https://developer.android.com/reference/android/service/notification/NotificationListenerService)、[闹钟与提醒](https://developer.android.com/develop/background-work/services/alarms)、[AGP 8.9 兼容要求](https://developer.android.com/build/releases/agp-8-9-0-release-notes)。实际验证记录见 `VALIDATION.md`。
