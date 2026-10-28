"""Disable vendor telemetry before initializing local model libraries."""
import os

# The environment switch also disables ONNX Runtime's initialization event.
os.environ['ORT_DISABLE_TELEMETRY']='1'
os.environ['HF_HUB_DISABLE_TELEMETRY']='1'
os.environ['HF_HUB_OFFLINE']='1'
os.environ['TRANSFORMERS_OFFLINE']='1'
os.environ['DO_NOT_TRACK']='1'

import onnxruntime as ort

ort.disable_telemetry_events()
