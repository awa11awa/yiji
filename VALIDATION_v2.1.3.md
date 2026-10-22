# v2.1.3 验证记录

日期：2026-10-22；项目：awa11awa/yiji；应用版本 2.1.3，versionCode 9。

执行 testDebugUnitTest、lintDebug、assembleDebug：178 项测试通过，失败及错误 0。Lint Error/Fatal 0、Warning 22（ApplySharedPref 1、GradleDependency 2、DataExtractionRules 1、KaptUsageInsteadOfKsp 1、UseKtx 17）。

新增 13 项测试，并修订 2 个现有 Compose 用例以明确选择“待确认”后再核对作业。

| 范围 | 结果 |
|---|---|
| GLM-5.3、5.3-Flash、5.3-FlashX | 从连接测试和事项提取入口实际捕获请求，均开启思考、使用 low 推理程度、不发送旧温度参数，测试输出预算充足 |
| 默认与未知模型 | 默认模型保留已知选项；未知模型/自定义服务不收到智谱专有选项 |
| 错误提示 | 400 参数细节及业务码可见；模型代码错误与接口错误分别提示；密钥/Bearer 脱敏；非 JSON 原始错误页不展示；不自动重试 |
| 作业分栏 | Compose 中待确认作业在待完成不可见，切换待确认才显示核对按钮；已逾期不混入待完成 |
| 红点与已读 | 待确认、已逾期、已完成各自清除；通知箱已忽略栏目在打开前保留红点；打开一栏不清其他栏 |
| 持久与变更 | 重建已读存储后仍已读；新修订、完成状态、首次逾期重新未读；提取结果到达使已读原文产生新更新 |
| 日期与子任务 | 日期当天不算逾期，次日转逾期；未完成子任务截止参与，完成子任务排除 |

沿用上一版全部模型、证据、协议、数据库迁移、队列、提醒、备份及 UI 回归测试。模型权重仍为 v2.1.2 的已验证 INT8 模型，本次未重新训练。

APK 使用 apksigner 验证固定证书 SHA-256：041f228046082db2aaf03367fc29417b0620aadc45a4c5c05d42a08c579f0fea；aapt 校验包名 cn.yiji.app、versionName 2.1.3、versionCode 9。

HTTP 400 的修复依据为官方模型参数文档及客户端契约测试，未使用用户密钥作本轮真实 API 请求。结果不证明账户权限、额度、服务可用性或所有其他模型均兼容。未完成 Android 真机覆盖安装、微信连续通知与息屏送达测试。

## 正式发布构建

执行 testReleaseUnitTest、lintRelease、assembleRelease，178 项 release 变体测试通过，失败及错误 0；Lint Error/Fatal 0、Warning 20。apksigner 校验固定证书与 v2.1.0 起的证书一致；aapt 校验包名 cn.yiji.app、versionName 2.1.3、versionCode 9，且没有 application-debuggable 标记。安装包为 yiji-v2.1.3.apk。GitHub Actions 发布前重复这些检查，正式 Release 包含 APK、源码 ZIP、更新说明和 SHA-256 文件。
