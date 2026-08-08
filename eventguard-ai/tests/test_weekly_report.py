"""WeeklyReportGenerator 单元测试：聚合结构 / LLM 失败降级。"""
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.model.anomaly import Anomaly
from app.report.weekly_report import WeeklyReportGenerator


def _anomaly(aid: str, rule_id: str, detected_at: str) -> Anomaly:
    return Anomaly(
        anomaly_id=aid, rule_id=rule_id, aggregate_id="agg-1", event_type="PaymentFailedEvent",
        level="WARN", source="PROCESS", priority="HIGH", detected_at=detected_at, description="d",
    )


@pytest.mark.asyncio
async def test_generate_structure():
    store = MagicMock()
    store.list_recent.return_value = [
        _anomaly("a1", "P002_STUCK", "2026-08-08T10:00:00Z"),
        _anomaly("a2", "P001_ILLEGAL_TRANSITION", "2026-08-08T11:00:00Z"),
    ]
    backend = MagicMock()
    backend.get_stats = AsyncMock(return_value=[{"status": "PAID", "orderCount": 4}])
    llm = AsyncMock()
    llm.generate_json.return_value = '{"symptoms": ["PAID 停滞"], "recommendations": ["复核停滞订单"]}'
    g = WeeklyReportGenerator(llm_client=llm, anomaly_store=store, backend_client=backend)

    r = await g.generate(days=7)

    assert r["total_anomalies"] == 2
    assert len(r["by_rule"]) == 2
    assert r["period"]["days"] == 7
    assert r["symptoms"] == ["PAID 停滞"]
    assert r["recommendations"] == ["复核停滞订单"]
    assert r["order_stats"] == [{"status": "PAID", "orderCount": 4}]
    assert len(r["top_orders"]) >= 1


@pytest.mark.asyncio
async def test_generate_fallback_when_llm_and_backend_fail():
    store = MagicMock()
    store.list_recent.return_value = []
    backend = MagicMock()
    backend.get_stats = AsyncMock(side_effect=RuntimeError("backend down"))
    llm = AsyncMock()
    llm.generate_json.side_effect = RuntimeError("llm down")
    g = WeeklyReportGenerator(llm_client=llm, anomaly_store=store, backend_client=backend)

    r = await g.generate(days=7)

    assert r["total_anomalies"] == 0
    assert r["by_rule"] == []
    assert r["order_stats"] == []
    assert len(r["symptoms"]) == 1  # 兜底症状
    assert len(r["recommendations"]) == 1
