"""Restore trainable floats from the shipped UINT8 graph; no original FP32 checkpoint is implied.

Uses ONNX's (q - zero_point) * scale dequantization. Dynamic activation
quantization is not part of the restored PyTorch model; report agreement separately.
"""
import numpy as np
import onnx
from onnx import numpy_helper
import torch


def recover(model, path):
    graph=onnx.load(path)
    values={x.name:numpy_helper.to_array(x) for x in graph.graph.initializer}
    state={}
    for name in model.state_dict():
        if name in values:
            state[name]=torch.from_numpy(values[name].copy())
    for node in graph.graph.node:
        if node.op_type=='Gather' and node.input[0].endswith('_quantized'):
            stem=node.input[0].removesuffix('_quantized')
            array=(values[stem+'_quantized'].astype(np.float32)-values[stem+'_zero_point'])*values[stem+'_scale']
            state[stem]=torch.from_numpy(array.copy())
        elif node.op_type=='MatMulInteger':
            stem=node.input[1].removesuffix('_quantized')
            array=(values[stem+'_quantized'].astype(np.float32)-values[stem+'_zero_point'])*values[stem+'_scale']
            if node.name.startswith('/bert/'):
                name=node.name.rsplit('/MatMul_quant',1)[0].strip('/').replace('/','.')+'.weight'
            else:
                name=stem
            state[name]=torch.from_numpy(array.T.copy())
    model.load_state_dict(state,strict=True)
    return dict(restored_parameters=len(state),method='dequantized_shipped_uint8_weights',
                note='The original FP32 fine-tuning checkpoint is unavailable; quantization rounding cannot be reversed.')
