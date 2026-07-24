"""NLQueryEngine 单元测试。

mock IntentClassifier + TemplateExecutor + LLMClient，验证路由与回答生成。
"""
from unittest.mock import MagicMock

import pytest

from app.query.nl_query_engine import NLQueryEngine
from app.query.query_result import QueryResult


class TestNLQueryEngine:
    """NL 查询引擎测试。"""

    def test_query_event_lookup_routes_to_event_lookup_template(self):
        """event_lookup 意图路由到 execute_event_lookup。"""
        mock_classifier = MagicMock()
        mock_classifier.classify.return_value = "event_lookup"
        mock_executor = MagicMock()
        mock_executor.execute_event_lookup.return_value = {"orderId": "abc", "status": "PAID"}
        mock_llm = MagicMock()
        mock_llm.generate.return_value = "订单 abc 当前状态为 PAID。"

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )

        result = engine.query("订单 abc 当前状态是什么？")

        assert isinstance(result, QueryResult)
        assert result.intent == "event_lookup"
        mock_executor.execute_event_lookup.assert_called_once()
        assert "PAID" in result.answer

    def test_query_stats_aggregation_routes_to_stats_template(self):
        """stats_aggregation 意图路由到 execute_stats_aggregation。"""
        mock_classifier = MagicMock()
        mock_classifier.classify.return_value = "stats_aggregation"
        mock_executor = MagicMock()
        mock_executor.execute_stats_aggregation.return_value = [
            {"status": "PAID", "orderCount": 5}
        ]
        mock_llm = MagicMock()
        mock_llm.generate.return_value = "昨天有 5 个 PAID 订单。"

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )

        result = engine.query("昨天有多少 PAID 订单？")

        assert result.intent == "stats_aggregation"
        mock_executor.execute_stats_aggregation.assert_called_once()
        assert "5" in result.answer

    def test_query_trace_replay_routes_to_trace_template(self):
        """trace_replay 意图路由到 execute_trace_replay。"""
        mock_classifier = MagicMock()
        mock_classifier.classify.return_value = "trace_replay"
        mock_executor = MagicMock()
        mock_executor.execute_trace_replay.return_value = [
            {"eventType": "OrderCreatedEvent", "version": 1}
        ]
        mock_llm = MagicMock()
        mock_llm.generate.return_value = "订单经历了 OrderCreatedEvent 事件。"

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )

        result = engine.query("订单 abc 经历了哪些状态变更？")

        assert result.intent == "trace_replay"
        mock_executor.execute_trace_replay.assert_called_once()

    def test_query_llm_failure_returns_data_with_raw_answer(self):
        """LLM 润色失败时仍返回 QueryResult，answer 为数据摘要。"""
        mock_classifier = MagicMock()
        mock_classifier.classify.return_value = "event_lookup"
        mock_executor = MagicMock()
        mock_executor.execute_event_lookup.return_value = {"orderId": "abc", "status": "PAID"}
        mock_llm = MagicMock()
        mock_llm.generate.side_effect = RuntimeError("llm down")

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )

        result = engine.query("订单 abc 状态？")

        assert result.intent == "event_lookup"
        # answer 应含原始数据摘要，不抛异常
        assert "abc" in result.answer or "PAID" in result.answer
