"""异常检测结果与异常模型定义"""

from typing import Optional

from pydantic import BaseModel


class AnomalyResult(BaseModel):
    """事件级检测结果"""
    is_anomaly: bool
    score: float = 0.0
    source: str = "IF"  # RULE / IF / PROCESS
    level: str = "LOW"  # HIGH / LOW
    rule_id: Optional[str] = None
    description: str = ""


class Anomaly(BaseModel):
    """异常告警（发布到 Kafka anomaly-alerts）"""
    anomaly_id: str
    rule_id: str  # R001-R005 / IF / P001-P003
    aggregate_id: str
    event_type: str
    level: str  # INFO / WARN / ERROR
    source: str  # RULE / IF / PROCESS
    priority: str  # HIGH / LOW
    detected_at: str  # ISO 8601
    description: str
    details: dict = {}
