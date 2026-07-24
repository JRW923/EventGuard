"""根因分析报告 Pydantic 模型"""

from typing import Literal

from pydantic import BaseModel, field_validator

# 建议动作白名单
ALLOWED_ACTIONS = {"REFUND", "NOTIFY_DELAY", "MARK_OUT_OF_STOCK", "FREEZE_ORDER", "BACKOFF_AND_STOP"}


class Suggestion(BaseModel):
    """补偿建议"""
    action: str
    reason: str
    risk: Literal["LOW", "MEDIUM", "HIGH"]

    @field_validator("action")
    @classmethod
    def validate_action(cls, v: str) -> str:
        if v not in ALLOWED_ACTIONS:
            raise ValueError(f"非法建议动作 {v}，必须在白名单内: {ALLOWED_ACTIONS}")
        return v


class AnalysisReport(BaseModel):
    """根因分析报告"""
    anomaly_id: str
    root_cause: str
    evidence: list[str]
    suggestions: list[Suggestion]
