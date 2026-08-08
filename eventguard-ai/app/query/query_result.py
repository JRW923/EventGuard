"""NL 查询结果模型。"""
from typing import Any, Optional

from pydantic import BaseModel


class QueryResult(BaseModel):
    """NL 查询引擎返回结构。

    conversation_id / needs_input 为多轮对话字段；默认值保证单轮调用（含 s04 压测）兼容。
    """
    intent: str  # event_lookup / stats_aggregation / trace_replay
    data: Any = None  # 原始查询数据
    answer: str = ""  # LLM 润色后的自然语言回答
    conversation_id: Optional[str] = None  # 会话标识：无则后端新建，前端续聊携带
    needs_input: bool = False  # 缺参追问：为 True 表示 answer 是反问，等用户补充
