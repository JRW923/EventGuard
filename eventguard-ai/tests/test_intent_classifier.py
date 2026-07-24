"""IntentClassifier 单元测试。

覆盖：
- LLM 返回合法意图标签时，3 类意图正确分类
- LLM 抛异常或返回非法标签时，关键词兜底
- 关键词匹配规则
"""
from unittest.mock import AsyncMock

import pytest

from app.query.intent_classifier import IntentClassifier
from app.query.prompts import INTENT_SYSTEM_PROMPT


class TestIntentClassifier:
    """意图分类器测试。"""

    @pytest.mark.asyncio
    async def test_classify_event_lookup_when_llm_returns_event_lookup(self):
        """LLM 返回 'event_lookup' 时分类为 event_lookup。"""
        mock_llm = AsyncMock()
        mock_llm.generate.return_value = "event_lookup"
        classifier = IntentClassifier(llm_client=mock_llm)

        intent = await classifier.classify("订单 #abc 当前状态是什么？")

        assert intent == "event_lookup"
        mock_llm.generate.assert_called_once()

    @pytest.mark.asyncio
    async def test_classify_stats_aggregation_when_llm_returns_stats(self):
        """LLM 返回 'stats_aggregation' 时分类为 stats_aggregation。"""
        mock_llm = AsyncMock()
        mock_llm.generate.return_value = "stats_aggregation"
        classifier = IntentClassifier(llm_client=mock_llm)

        intent = await classifier.classify("昨天有多少订单支付失败？")

        assert intent == "stats_aggregation"

    @pytest.mark.asyncio
    async def test_classify_trace_replay_when_llm_returns_trace(self):
        """LLM 返回 'trace_replay' 时分类为 trace_replay。"""
        mock_llm = AsyncMock()
        mock_llm.generate.return_value = "trace_replay"
        classifier = IntentClassifier(llm_client=mock_llm)

        intent = await classifier.classify("订单 #1234 经历了哪些状态变更？")

        assert intent == "trace_replay"

    @pytest.mark.asyncio
    async def test_classify_falls_back_to_keyword_when_llm_raises(self):
        """LLM 抛异常时走关键词兜底。"""
        mock_llm = AsyncMock()
        mock_llm.generate.side_effect = RuntimeError("llm unavailable")
        classifier = IntentClassifier(llm_client=mock_llm)

        # 含"状态变更" → trace_replay
        assert await classifier.classify("订单 #1234 经历了哪些状态变更？") == "trace_replay"
        # 含"多少" → stats_aggregation
        assert await classifier.classify("昨天有多少支付失败？") == "stats_aggregation"
        # 含"当前" → event_lookup
        assert await classifier.classify("订单 #abc 当前状态是什么？") == "event_lookup"

    @pytest.mark.asyncio
    async def test_classify_falls_back_to_keyword_when_llm_returns_invalid_label(self):
        """LLM 返回非合法标签时走关键词兜底。"""
        mock_llm = AsyncMock()
        mock_llm.generate.return_value = "unknown_intent"
        classifier = IntentClassifier(llm_client=mock_llm)

        # 含"统计" → stats_aggregation
        assert await classifier.classify("统计昨天支付失败的订单数量") == "stats_aggregation"

    @pytest.mark.asyncio
    async def test_classify_default_fallback_is_event_lookup(self):
        """LLM 异常且无关键词命中时默认 event_lookup。"""
        mock_llm = AsyncMock()
        mock_llm.generate.side_effect = RuntimeError("llm unavailable")
        classifier = IntentClassifier(llm_client=mock_llm)

        assert await classifier.classify("hello world") == "event_lookup"

    def test_prompt_includes_three_intents(self):
        """系统 prompt 应包含 3 类意图标签。"""
        assert "event_lookup" in INTENT_SYSTEM_PROMPT
        assert "stats_aggregation" in INTENT_SYSTEM_PROMPT
        assert "trace_replay" in INTENT_SYSTEM_PROMPT
