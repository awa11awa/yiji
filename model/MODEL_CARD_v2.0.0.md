# 忆迹 TinyBERT 中文通知模型 v2

## 输入与输出

基础模型为华为诺亚方舟实验室 [TinyBERT_4L_zh](https://huggingface.co/huawei-noah/TinyBERT_4L_zh)，4 层、隐藏维度 312、12 个注意力头。固定 revision `152bf15d86715b41dd89c1e03cf5664963d9b005`，官方原始权重 SHA-256 为 `b4aec782e4a56727bb6ba1dcac8e6c3319460e58e11254f7e43a2dccee0c19c1`。

共享编码器使用正文（正文为空时使用标题），同时输出：

| 输出 | 标签 |
| --- | --- |
| 通知路由／类别 | IGNORE、作业、报名、会议、课程、活动、考试、材料、其他 |
| 实体 BIO | EVENT、LOCATION、START_TIME、END_TIME、DUE_TIME、REFERENCE_TIME |
| 缺失实体时的事项类型 | SCHEDULE、DEADLINE、MIXED |
| 时间状态 | KNOWN、TO_BE_ANNOUNCED、BOOKING_REQUIRED |
| 操作意图 | NEW、UPDATE、CANCEL |

文档头使用 CLS 与掩码平均向量拼接，接两层分类网络。实体头逐 token 输出 13 类 logits。最大窗口 384 token，重叠 96 token；重叠实体 logits 按离窗口边界的距离加权，文档 logits 按窗口取平均。WordPiece 在 Kotlin 保留原始 UTF-16 偏移，使用同一官方词表。

IGNORE 概率达到 0.80 才自动进入已忽略；不够确定时保留待核对草稿。类别把握低于 0.70 会提示。上述阈值作用于模型输出，没有关键词名单或文本分类回退。日历运算、重复指纹、时间冲突、人工确认和标题相似度关联是确定性的后续操作，不决定通知是否重要或事项类别。

## 实际训练范围

从已完成的 EVENT/TIME/LOCATION NER 微调编码器继续，增加新的时间角色与文档分类头。联合训练依次进行 7、6、3 轮，每阶段按合成验证集表现保存 checkpoint，最终阶段选第 2 轮。CPU PyTorch 2.10.0，Transformers 4.57.6，种子 42，AdamW，学习率 4e-5，按长度分组的 batch 24、少量人工资料提高采样权重。每一阶段的实际日志与数据 SHA-256 均随仓库提供。

最终训练集 3,969 条（3,966 条明确生成的合成资料与用户提供的 3 条人工标注通知），验证和测试各 360 条合成资料。句式按集合隔离，事件和地点词汇共享。生成器用模板直接写入类别、角色与意图标注；这属于训练资料制作，生成规则不会进入 Android 推理路径。

三条真实资料包括干部纳新材料截止／面试待定、体测已启动但个人需预约、MathorCup 跨日赛程。原始音乐会通知没有进入训练集、验证集或合成测试集。三条新增通知参与过训练，对它们的检查仅是训练回归。用户原文及邮箱未公开；公共训练工具默认仅生成合成资料。

## 导出验证

ONNX opset 17、IR 8，使用按通道的动态 UINT8 权重量化（MatMul、Gather），Android ONNX Runtime 1.23.2；Python 导出检查使用 ONNX Runtime 1.31.0。模型文件 11,907,937 字节，SHA-256：

`88ed8dadde2a9d32a489f723751d09a25ad5ba2ebaaa8237f57a080cc150e924`

360 条合成测试上的严格实体 F1：FP32 85.76%，INT8 84.71%。INT8 文档路由／类别准确率 92.78%，事项类型头 69.44%，状态头 95.28%，操作头 98.33%。FP32 ONNX 与 PyTorch 探针最大绝对差约 5.72e-6。详细计数见 [export_report.json](export_report.json)。

这些分数只衡量共享词汇的合成句式，不能当作真实消息准确率、端到端事项正确率或生产可用性证明。缺少有代表性的真实负例和独立人工评测，仍可能出现实体边界错误、类别误判、漏识别、长文本窗口偏差及复杂多事项配对错误。时间类型头尤其需要更多真实资料。所有建议都保留原文并要求核对，不能自动修改已确认事项。

## 来源与许可证记录

官方代码来源为 [Huawei TinyBERT](https://github.com/huawei-noah/Pretrained-Language-Model/tree/master/TinyBERT)。随附 [TinyBERT-CODE-LICENSE](TinyBERT-CODE-LICENSE) 是官方代码目录 Apache License 2.0 的副本；模型文件页没有单独的权重许可证文件或许可证元数据，本项目不把代码许可证声明扩大为独立的模型权重授权声明。Android 推理依赖见 [ONNX Runtime 官方 Android 指南](https://onnxruntime.ai/docs/tutorials/mobile/deploy-android.html)。

本次发布是可安装的开发测试包。JVM 原生推理、Room 和 Compose 测试验证逻辑与 Java 调用，不能替代 Android 真机速度、耗电、通知权限及厂商后台限制验证。
