# v2.2.1 继续训练与部署选择

使用已保存的模型权重和原始划分，运行：

    python model/tools/refine_message_bert_v221.py --retention --encoder-lr 0.000006 --head-lr 0.000018 --epochs 2 --threads 5

需要 model/outputs/message-v2.2.1/continued_weights.pt、baseline.int8.onnx、baseline.metadata.json、continuation_training_report.json，以及 model/data 中保持原划分的数据。私有真实通知与训练权重不随公开仓库分发；需使用持有的训练检查点恢复。依赖见 requirements-gate.txt。

refine脚本补充2,000条通用资料，contrastive模式再加入800对当前任务／历史回忆对照；retention模式再补充2,000条作业、报名、验证数字和普通闲聊句式，回放288条既有回归案例。回放句式参考了旧合成开发集的错误类别，故固定留出集只作开发对照，不称作盲测；原验证／测试文本不进入训练，噪声重复串也按原文去重排除。按部署窗口处理长消息，逐轮用实际导出图的验证结果选择。通用验证中23条可能为老师布置作业的语法分析消息单独记录标注歧义，完整严格分数仍保留，测试集不重标。固定阈值0.5。输出位于 model/outputs/refined-v2.2.1，选择成功后经发布步骤写入应用资产。公开的真实通知统计不含原文。

独立部署包：

    python scripts/package_message_model.py --output release-artifacts/yiji-message-bert-v2.2.1.zip

以下保留早期训练工具说明。train_message_bert_v221.py 和 finalize_message_bert_v221.py 属于本次更早的续训／导出尝试，最终结果以 refine 的报告及模型哈希为准。

# v2.1.2训练增强

在固定官方基础模型准备完成后执行：

    python model/tools/train_gate_v212.py --epochs 7 --threads 4

该脚本训练9,000条合成资料，用1,600条验证资料选择轮次与阈值，再在1,600条独立留出资料上比较原始INT8与新INT8。词汇和随机噪声生成器共享，文本跨划分精确去重；不是独立真实消息准确率。新权重未通过替换门槛时保留旧资产。报告见gate_training_report_v2.1.2.json。用户提供的原始通知不参与训练。

以下为旧v2.1复现说明。

# 消息二分类训练与导出（v2.1）

下载官方固定revision并校验哈希，准备编码器，然后训练聊天／日程二分类并量化导出：

    python model/tools/download_model.py
    python model/tools/prepare_model.py
    python model/tools/train_gate.py --epochs 5 --threads 5

运行环境需PyTorch CPU、Transformers4.57.6、safetensors、numpy、onnx、onnxruntime。实际依赖版本见 requirements-gate.txt。Android构建只读取已导出的模型，不需要Python。

train_gate.py会生成按句式集合划分的5,000/600/600条合成资料，写入model/data/gate-v2.1；原始编码器、数据、checkpoint均不随源码公开二进制缓存，训练工具可重新生成合成资料。固定种子及数据SHA-256记录在gate_training_report.json。硬件、线程数、库版本可能影响权重逐字节复现。

默认训练不读取用户私聊、未授权数据或历史三条私有训练通知。本次没有独立真实消息评测；合成分数只用于开发验证，不能代表真实准确率。

输出为app/src/main/assets/tinybert/model.int8.onnx及metadata.json，只有CHAT/SCHEDULE两个标签。旧build_joint_data/train_joint/export_joint等工具与历史报告仅用于复现v2.0，不是本版默认运行模型。
