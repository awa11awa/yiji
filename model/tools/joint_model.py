"""Shared TinyBERT encoder, BIO role tags and four learned document heads."""
import torch
from torch import nn
from transformers import BertModel, BertConfig

LABELS = ["O"] + [f"{p}-{k}" for k in ("EVENT", "LOCATION", "START_TIME", "END_TIME", "DUE_TIME", "REFERENCE_TIME") for p in ("B", "I")]
HEADS = {
    "route": ["IGNORE", "作业", "报名", "会议", "课程", "活动", "考试", "材料", "其他"],
    "kind": ["SCHEDULE", "DEADLINE", "MIXED"],
    "state": ["KNOWN", "TO_BE_ANNOUNCED", "BOOKING_REQUIRED"],
    "operation": ["NEW", "UPDATE", "CANCEL"],
}

class JointTinyBert(nn.Module):
    def __init__(self, config):
        super().__init__()
        config._attn_implementation = "eager"
        self.bert = BertModel(config, add_pooling_layer=False)
        self.dropout = nn.Dropout(.1)
        self.tags = nn.Linear(config.hidden_size, len(LABELS))
        self.heads = nn.ModuleDict({k: nn.Sequential(nn.Linear(config.hidden_size * 2, 128), nn.GELU(), nn.Dropout(.1), nn.Linear(128, len(v))) for k, v in HEADS.items()})

    def forward(self, input_ids, attention_mask, token_type_ids):
        hidden = self.bert(input_ids, attention_mask=attention_mask, token_type_ids=token_type_ids).last_hidden_state
        mask = attention_mask.unsqueeze(-1).to(hidden.dtype)
        mean = (hidden * mask).sum(1) / mask.sum(1).clamp(min=1)
        pooled = self.dropout(torch.cat([hidden[:, 0], mean], dim=-1))
        return (self.tags(self.dropout(hidden)), *(head(pooled) for head in self.heads.values()))
