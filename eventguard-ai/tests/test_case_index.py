"""CaseIndex 单元测试：相似度打分 / 排序 / 处置状态。"""
from unittest.mock import MagicMock

import pytest

from app.cases.case_index import CaseIndex
from app.model.anomaly import Anomaly
from app.store.anomaly_store import AnomalyStore


def _anomaly(aid, rule, event_type, source, level, agg, detected_at):
    return Anomaly(
        anomaly_id=aid, rule_id=rule, aggregate_id=agg, event_type=event_type,
        level=level, source=source, priority="HIGH", detected_at=detected_at,
        description=f"{rule} 触发", details={},
    )


def test_similarity_same_rule_stronger_than_diff():
    a = _anomaly("a", "P001", "PaymentFailedEvent", "PROCESS", "ERROR", "agg-1", "2026-08-08T10:00:00Z")
    same = _anomaly("b", "P001", "PaymentFailedEvent", "PROCESS", "ERROR", "agg-2", "2026-08-08T11:00:00Z")
    diff = _anomaly("c", "R001", "OrderCreatedEvent", "RULE", "WARN", "agg-3", "2026-08-08T12:00:00Z")
    assert CaseIndex.similarity(a, same) >= 0.7  # 规则/事件/来源/级别全同 + 时间近
    assert CaseIndex.similarity(a, diff) < CaseIndex.similarity(a, same)


def test_similarity_same_aggregate_boosted():
    a = _anomaly("a", "P001", "PaymentFailedEvent", "PROCESS", "ERROR", "agg-1", "2026-08-08T10:00:00Z")
    b = _anomaly("b", "P002", "PaymentCompletedEvent", "PROCESS", "WARN", "agg-1", "2026-08-08T12:00:00Z")
    # 同订单但规则不同：同订单加分抵消部分规则差
    assert CaseIndex.similarity(a, b) > 0


def test_top_k_cases_ranks_similar_first():
    store = AnomalyStore()
    store.save(_anomaly("t", "P002", "PaymentCompletedEvent", "PROCESS", "WARN", "agg-t", "2026-08-08T10:00:00Z"))
    store.save(_anomaly("same", "P002", "PaymentCompletedEvent", "PROCESS", "WARN", "agg-s", "2026-08-08T10:30:00Z"))
    store.save(_anomaly("diff", "R001", "OrderCreatedEvent", "RULE", "WARN", "agg-d", "2026-08-08T11:00:00Z"))
    idx = CaseIndex(anomaly_store=store)

    top = idx.top_k_cases(store.get("t"), top_k=2)

    assert top[0][0] > top[1][0]
    assert top[0][1].anomaly_id == "same"


@pytest.mark.asyncio
async def test_query_includes_resolution():
    store = AnomalyStore()
    store.save(_anomaly("t", "P002", "PaymentCompletedEvent", "PROCESS", "WARN", "agg-t", "2026-08-08T10:00:00Z"))
    store.save(_anomaly("same", "P002", "PaymentCompletedEvent", "PROCESS", "WARN", "agg-s", "2026-08-08T10:30:00Z"))
    events = MagicMock()
    events.load_events.return_value = [{"event_type": "CompensationExecutedEvent"}]
    idx = CaseIndex(anomaly_store=store, event_store_client=events)

    r = await idx.query("t")

    assert r["cases"][0]["case_anomaly_id"] == "same"
    assert r["cases"][0]["resolution"] == "已补偿"
    assert r["cases"][0]["similarity"] > 0


@pytest.mark.asyncio
async def test_query_missing_target():
    store = AnomalyStore()
    idx = CaseIndex(anomaly_store=store)
    r = await idx.query("nope")
    assert r["cases"] == []
    assert "message" in r
