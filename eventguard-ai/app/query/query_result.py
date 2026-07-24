"""NL 查询结果模型。"""
from typing import Any

from pydantic import BaseModel


class QueryResult(BaseModel):
    """NL 查询引擎返回结构。"""
    intent: str  # event_lookup / stats_aggregation / trace_replay
    data: Any = None  # 原始查询数据
    answer: str = ""  # LLM 润色后的自然语言回答
