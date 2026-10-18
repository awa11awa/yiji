# 忆迹 TinyBERT 消息二分类模型 v2.1

本版 TinyBERT 只区分 CHAT（聊天／非待办）与 SCHEDULE（日程候选）。不再输出事项类别、时间实体、状态或新建／变更／取消意图。完整事项提取由用户选定的云端 API 模型完成。

## 来源和重新训练

沿用中文 TinyBERT 4 层、312 隐藏维度、12 注意力头架构，在已校验的官方预训练编码器上重新微调一个二分类头。没有把 v2.0 的类别头改名后直接使用。

- 基础模型：huawei-noah/TinyBERT_4L_zh
- 固定 revision：152bf15d86715b41dd89c1e03cf5664963d9b005
- 官方原始权重 SHA-256：b4aec782e4a56727bb6ba1dcac8e6c3319460e58e11254f7e43a2dccee0c19c1
- 二分类头：CLS 与掩码平均向量拼接，线性层、GELU、Dropout、二分类输出。
- 实际训练：CPU PyTorch 2.14.1+cpu，Transformers 4.57.6，种子 42，5 轮，验证集选择第4轮。
- 训练／验证／测试：5,000／600／600 条合成资料，按句式集合划分并跨集合去除完全相同文本，主题词汇共享。
- 本次没有新增私聊或真实通知训练资料，也没有独立真实消息测试集。旧版本的三条私有训练通知不在本次公开训练数据中。

训练生成器仅制作训练资料，生成规则不进入 Android 运行时。工具、逐轮损失、各划分数据 SHA-256 和评估计数见 train_gate.py 与 gate_training_report.json。

## 路由策略

输入仅为正文，正文为空时才使用标题，避免仅凭群名称把普通聊天提升为事项。最大窗口384 token，重叠96 token。长消息取各窗口的最高日程概率，防止短通知被大量聊天稀释。

CHAT 概率达到0.995才进入已忽略；其余内容保留为日程候选，再由云端复核。这个概率没有通过真实消息校准，不能显示成真实准确率。已忽略原文保留，用户可主动送云端复核。未配置或未授权云端时，候选原文等待配置，不凭空生成本地事项。

## 导出与评估

- 动态 UINT8、按通道量化 MatMul/Gather，ONNX opset17、IR8。
- 模型：11,637,318 字节。
- 模型 SHA-256：1787ba006ccbb73289267499a9f6b8ecda22594a02497782d1c497430f7392de
- 合成测试 INT8：日程召回99.0%（297/300），日程精确率86.09%，二分类准确率91.5%。
- 量化前 FP32 合成测试：日程召回99.0%，精确率85.34%，准确率91.0%。
- Android/JVM 使用 ONNX Runtime1.23.2，Python 导出验证使用1.31.0。

以上是共享词汇、合成句式上的路由指标，不能当作真实微信消息准确率、云端提取准确率或端到端效果。二分类仍可能误判，后续应补充真实、去标识、独立标注的消息评测。

## 来源记录

官方代码：https://github.com/huawei-noah/Pretrained-Language-Model/tree/master/TinyBERT
随附 TinyBERT-CODE-LICENSE 记录官方代码的 Apache2.0 许可证，不把代码许可证扩大解释为独立的权重许可声明。

旧 v2.0 联合抽取模型资料保存在 MODEL_CARD_v2.0.0.md；旧 training_report.json、initial_joint_training_report.json、time_role_training_report.json、export_report.json 均属于历史模型，不代表本版二分类模型。
