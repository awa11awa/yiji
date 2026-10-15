# 忆迹 v1.1.0 验证记录

验证日期：2026-10-15。

## 自动验证

执行 `testDebugUnitTest lintDebug assembleDebug`，构建成功。

| 测试组 | 通过 | 失败/错误/跳过 |
| --- | ---: | ---: |
| 原有核心解析、关联及冲突检测 | 19 | 0 |
| 新增本地规则回归 | 22 | 0 |
| 数据库与导入确认流程 | 12 | 0 |
| 提醒流程 | 2 | 0 |
| MainActivity 启动 | 1 | 0 |
| 总计 | 56 | 0 |

新增样例覆盖校园动作、群名误判、普通聊天、已完成事项、变更通知、中文数字、全角字符、日期简写、时间区间、午夜、缺失时间、异常日期、教室编号及离线通知生成待确认建议。

Android Lint：0 错误、14 条 Warning。没有增加警告基线或关闭 Lint 检查。

## 构建产物

- 包名：`cn.yiji.app`。
- 版本：`1.1.0`，versionCode：`2`。
- minSdk：26，targetSdk/compileSdk：35。
- 构建环境：JDK 17.0.20、Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.20、Android Build Tools 35.0.0、标准 SDK 35。
- `aapt dump badging` 核实版本和 SDK；`apksigner verify --print-certs` 校验通过。
- 本次调试 APK SHA-256：`23401bbaf8eab3c3ce93945dba4ddbb4e7f489aba04bf33d7f34c2fa4390338c`。

## 边界与升级

- Android 相关测试通过 Robolectric 执行；未连接真机或启动完整 Android 模拟器。
- 本次不涉及通知监听和后台保活机制的改动，仍需真机验证通知授权、来源应用格式和厂商后台策略。
- AI 为可选功能，本次新增识别逻辑不依赖 AI；未调用真实模型服务。
- 未修改数据库结构和备份格式，已有导出/恢复流程测试通过。
- 新调试 APK 的签名与 v1.0.0 测试包不同，无法直接覆盖安装。先在旧版设置中将备份保存到应用外位置，再卸载旧版、安装新版并恢复备份。
