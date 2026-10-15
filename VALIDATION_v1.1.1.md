# v1.1.1 验证记录

日期：2026-10-15。应用包名 `cn.yiji.app`，versionName `1.1.1`，versionCode `3`，数据库仍为版本 1。

## 本地构建

使用 JDK 17、Android SDK Platform 35、Build Tools 35.0.0 和校验过发行摘要的 Gradle 8.11.1，运行 `testDebugUnitTest lintDebug assembleDebug`，最终构建成功。

## 测试

共 76 项通过，0 失败、0 错误、0 跳过。

| 测试类 | 数量 |
| --- | ---: |
| CoreTest | 19 |
| LocalRuleTest | 22 |
| RepositoryTest | 12 |
| NotificationCollectorTest | 16 |
| NotificationListenerTest | 4 |
| ReminderTest | 2 |
| ActivitySmokeTest | 1 |

新增 20 项通知回归测试包括：首条普通通知之后的合并通知、同一通知键反复更新、累积 MessagingStyle 消息逐条导入、Android 8.0 消息提取、旧消息关键词不影响新消息、重复通知与补采去重、普通聊天原文保留、仅标题与 ticker 回退、实际连接状态、断线请求重连、连接后的通知栏补采，以及服务销毁不取消已排队的应用进程任务。

## Lint 与安装包

- Android Lint：0 Error/Fatal，21 个 Warning。包括依赖版本提示 5 项、KTX 写法建议 14 项、KAPT 转 KSP 建议 1 项、旧版备份配置兼容提示 1 项。
- 调试 APK 构建成功，`apksigner verify` 校验通过。
- 使用 `aapt dump badging` 确认包名 `cn.yiji.app`、版本 `1.1.1`、versionCode `3`。
- `git diff --check` 通过。

## 验证边界

通知测试通过 Robolectric 构造系统通知和服务回调完成，覆盖 Android 8.0（SDK 26）和 Android 9（SDK 28），未在用户手机和微信版本上完成实机复测，也未验证不同厂商的持续后台策略。

补采仅覆盖通知栏中仍保留且系统提供了文字的通知。应用进程被强制停止、通知已经清除、来源未发布通知或隐藏正文时，不能承诺恢复消息。相同来源应用、标题和正文在 24 小时内仍然只保存一次。

GitHub Actions 会从发布提交独立执行测试、Lint、APK 构建、版本与签名校验。最终下载文件和校验值以对应 Release 的附件及 `SHA256SUMS.txt` 为准；CI 调试签名可能与本地或旧版不同，升级前应先导出并保存备份。
