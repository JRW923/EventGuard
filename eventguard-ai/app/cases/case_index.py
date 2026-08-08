"""相似案例检索（Item 8 · 轻量 RAG，零新依赖）。

不做向量库/embedding：相似度 = 规则 / 事件类型 / 来源 / 级别 / 时间近邻 / 同订单 的加权打分，
完全确定性与可解释。案例库直接读 anomaly_store（已支持 JSONL 持久化），不维护并行索引。

query() 额外对 top-k 命中查询其聚合根是否出现过 CompensationExecutedEvent → resolution
（"已补偿" / "未处置"），供运营参考上次处置方式。LLM 可选 few-shot 注入见 root_cause.py。
"""
import logging
import math
from datetime import datetime
from typing import Optional

from app.model.anomaly import Anomaly
from app.store.anomaly_store import anomaly_store as default_anomaly_store
from app.store.event_store_client import EventStoreClient

logger = logging.getLogger(__name__)


def _parse_iso(ts: str) -> Optional[datetime]:
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except (ValueError, TypeError):
        return None


class CaseIndex:
    """基于 anomaly_store 的轻量相似案例检索。"""

    # 相似度权重：规则同型最强，事件类型其次，来源/级别微调，时间近邻衰减，同订单加分
    W_RULE = 0.5
    W_EVENT_TYPE = 0.2
    W_SOURCE = 0.1
    W_LEVEL = 0.05
    W_TIME = 0.15
    W_SAME_AGG = 0.2
    TIME_HALF_LIFE_HOURS = 7 * 24

    def __init__(self, anomaly_store=None, event_store_client=None, scan_limit: int = 500):
        self.anomaly_store = anomaly_store or default_anomaly_store
        self.event_store_client = event_store_client or EventStoreClient()
        self.scan_limit = scan_limit

    # ---------------- 查询 ----------------

    async def query(self, anomaly_id: str, top_k: int = 5) -> dict:
        """检索与指定异常相似的近期案例，附带处置状态。"""
        target = self.anomaly_store.get(anomaly_id)
        if target is None:
            return {"anomaly_id": anomaly_id, "cases": [], "message": "异常不存在"}
        top = self.top_k_cases(target, top_k)
        cases = []
        for score, c in top:
            cases.append({
                "similarity": round(score, 3),
                "case_anomaly_id": c.anomaly_id,
                "rule_id": c.rule_id,
                "aggregate_id": c.aggregate_id,
                "event_type": c.event_type,
                "level": c.level,
                "detected_at": c.detected_at,
                "description": c.description,
                "resolution": await self._resolution(c),
            })
        return {"anomaly_id": anomaly_id, "cases": cases}

    def top_k_cases(self, target: Anomaly, top_k: int = 5) -> list[tuple[float, Anomaly]]:
        """同步版（供 root_cause few-shot 注入）：返回 (score, Anomaly) 降序，不含处置状态。"""
        scored: list[tuple[float, Anomaly]] = []
        for c in self.anomaly_store.list_recent(limit=self.scan_limit):
            if c.anomaly_id == target.anomaly_id:
                continue
            s = self.similarity(target, c)
            if s > 0:
                scored.append((s, c))
        scored.sort(key=lambda x: x[0], reverse=True)
        return scored[:top_k]

    # ---------------- 相似度 ----------------

    @staticmethod
    def similarity(a: Anomaly, b: Anomaly) -> float:
        sim = 0.0
        if a.rule_id == b.rule_id:
            sim += CaseIndex.W_RULE
        if a.event_type == b.event_type:
            sim += CaseIndex.W_EVENT_TYPE
        if a.source == b.source:
            sim += CaseIndex.W_SOURCE
        if a.level == b.level:
            sim += CaseIndex.W_LEVEL
        # 同一订单链上的异常：最相关（同 aggregate）
        if a.aggregate_id == b.aggregate_id:
            sim += CaseIndex.W_SAME_AGG
        # 时间近邻：越近越相似，指数衰减
        ta, tb = _parse_iso(a.detected_at), _parse_iso(b.detected_at)
        if ta and tb:
            hours = abs((ta - tb).total_seconds()) / 3600
            sim += CaseIndex.W_TIME * math.exp(-hours / CaseIndex.TIME_HALF_LIFE_HOURS)
        return min(sim, 1.0)

    async def _resolution(self, anomaly: Anomaly) -> str:
        try:
            events = self.event_store_client.load_events(anomaly.aggregate_id)
            if any(e.get("event_type") == "CompensationExecutedEvent" for e in events):
                return "已补偿"
            return "未处置"
        except Exception:
            return "未知"
