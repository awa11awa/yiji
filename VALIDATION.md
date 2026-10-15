# 忆迹 v1.0.0 验证记录

交付日期：2026-10-14。按原手机会话中可见的第一版规划构建，未获得原手机会话的中间源码文件。

## 已完成的验证

- Android Debug APK 构建成功，包名 `cn.yiji.app`，版本 `1.0.0`。
- Android 单元与集成测试：32 项通过，0 失败。
- 核心逻辑 19 项：日期与时间解析、截止事项、缺失字段、改期、跨夜、关联歧义与时间冲突。
- 数据库流程 10 项：显式确认、新建合并更新取消、来源与历史、重复确认、旧版本编辑、备份恢复与非法备份的原子性、通知去重、删除关联与来源筛选。
- 提醒 2 项：同一版本提醒只发送一次、改期重新安排、完成与取消撤销。
- 界面 1 项：真实 MainActivity 在 Robolectric 中启动并显示内容视图。
- Android Lint：0 错误，23 条建议性警告。未使用错误基线或关闭检查。
- FastAPI 服务测试：12 项通过，覆盖提取、令牌、参数与时区、语义更新建议、未知关联目标与低置信度拒绝。
- APK 完整性检查通过；APK Signature Scheme v2 签名验证通过，使用 Android Debug 开发证书。

## 实际构建环境

JDK 21、Gradle 8.11.1、AGP 8.9.2、Android Build Tools 35.0.0、Android SDK 35 Extension 15。源码默认支持标准 SDK 35；本机通过 `-Pyiji.sdkExtension=15` 指定已安装扩展版本，未使用扩展 API。

```powershell
gradle --init-script <本机依赖缓存配置> '-Pyiji.sdkExtension=15' '-Pyiji.testRepo=<本机依赖缓存地址>' assembleDebug testDebugUnitTest lintDebug
```

构建期间使用 HTTPS 上游的本地依赖缓存处理下载中断。公开交付源码保留官方仓库配置，并附可选 HTTPS 镜像配置，不依赖这台电脑的缓存服务。

服务验证环境为 Python 3.14，具体依赖已记录在 `backend/requirements.lock.txt`。

## 验证边界

- Android 测试由 Robolectric 执行，没有连接真机或启动完整 Android 模拟器。
- 系统通知接入授权、通知正文折叠、手机锁屏提醒与厂商后台限制须安装到真机后验证。
- 模型分支采用模拟响应验证协议、字段与关联校验；未发送真实模型请求。使用真实语义提取和关联功能需自行配置模型接口。
- 界面验证覆盖启动，没有进行逐页面像素或真机触控测试。
- 交付 APK 是可安装的开发测试包，未执行应用商店发布或生产签名。
