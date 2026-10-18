# 消息二分类训练与导出（v2.1）

下载官方固定revision并校验哈希，准备编码器，然后训练聊天／日程二分类并量化导出：

    python model/tools/download_model.py
    python model/tools/prepare_model.py
    python model/tools/train_gate.py --epochs 5 --threads 5

运行环境需PyTorch CPU、Transformers4.57.6、safetensors、numpy、onnx、onnxruntime。实际依赖版本见 requirements-gate.txt。Android构建只读取已导出的模型，不需要Python。

train_gate.py会生成按句式集合划分的5,000/600/600条合成资料，写入model/data/gate-v2.1；原始编码器、数据、checkpoint均不随源码公开二进制缓存，训练工具可重新生成合成资料。固定种子及数据SHA-256记录在gate_training_report.json。硬件、线程数、库版本可能影响权重逐字节复现。

默认训练不读取用户私聊、未授权数据或历史三条私有训练通知。本次没有独立真实消息评测；合成分数只用于开发验证，不能代表真实准确率。

输出为app/src/main/assets/tinybert/model.int8.onnx及metadata.json，只有CHAT/SCHEDULE两个标签。旧build_joint_data/train_joint/export_joint等工具与历史报告仅用于复现v2.0，不是本版默认运行模型。
