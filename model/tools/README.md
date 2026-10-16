# 本地训练和导出

在仓库根目录创建 Python 虚拟环境并安装 `model/tools/requirements.txt`（PyTorch 可选择官方 CPU wheel）。Android 构建不需要 Python，也不会触发训练或模型下载。

```sh
python model/tools/download_model.py
python model/tools/prepare_model.py
python model/tools/build_joint_data.py
python model/tools/augment_joint_campus.py
python model/tools/train_joint.py --epochs 7 --output model/outputs/joint
python model/tools/export_joint.py --model model/outputs/joint
```

下载器固定官方 revision 并验证权重 SHA-256；准备工具只转换编码器，丢弃蒸馏／预训练任务头。基础权重准备后训练与推理均使用离线文件。

默认可从官方编码器重新训练，联合分类头与角色标签头从随机初始化开始。如有之前的 NER checkpoint，使用 `--init-ner <目录>`；继续联合模型训练使用 `--init <目录>`。需要新的输出目录，避免覆盖已有 checkpoint。

生成器中的模板与标签是训练资料的标注方法，不会作为 Android 筛选代码运行。合成划分按句式隔离、词汇共享，不能替代真实消息评估。三条用户原文未公开，公共重新训练默认仅使用合成资料，不能逐字节复现内置 checkpoint。相同初始 checkpoint、数据与采样种子也仍受运行库和硬件影响。

内部本次训练实际先从已有 NER 权重训练联合任务，再补充时间范围和简短校园通知；各阶段与最终量化报告在上层目录。导出器验证 ONNX 与 PyTorch、FP32 与 INT8 的合成测试差异，未通过时停止打包。
