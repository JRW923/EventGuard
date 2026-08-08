"""StoryGenerator 单元测试：LLM 故事 / LLM 失败模板兜底 / 无事件降级。"""
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.report.story_generator import StoryGenerator


@pytest.mark.asyncio
async def test_generate_with_llm():
    llm = AsyncMock()
    llm.generate.return_value = "订单创建后付款完成，但停滞在 PAID 环节。"
    events = MagicMock()
    events.load_events.return_value = [
        {"event_type": "OrderCreatedEvent", "event_version": 1},
        {"event_type": "PaymentCompletedEvent", "event_version": 2},
    ]
    g = StoryGenerator(llm_client=llm, event_store_client=events)

    r = await g.generate("agg-1")

    assert r["aggregate_id"] == "agg-1"
    assert "停滞" in r["story"]
    assert r["event_types"] == ["OrderCreatedEvent", "PaymentCompletedEvent"]
    llm.generate.assert_awaited_once()


@pytest.mark.asyncio
async def test_generate_fallback_when_llm_fails():
    llm = AsyncMock()
    llm.generate.side_effect = RuntimeError("llm down")
    events = MagicMock()
    events.load_events.return_value = [{"event_type": "OrderCreatedEvent", "event_version": 1}]
    g = StoryGenerator(llm_client=llm, event_store_client=events)

    r = await g.generate("agg-1")

    assert "OrderCreatedEvent" in r["story"]  # 模板兜底


@pytest.mark.asyncio
async def test_generate_empty_events():
    llm = AsyncMock()
    events = MagicMock()
    events.load_events.return_value = []
    g = StoryGenerator(llm_client=llm, event_store_client=events)

    r = await g.generate("agg-1")

    assert "未查询到" in r["story"]
    assert r["event_types"] == []
    llm.generate.assert_not_awaited()
