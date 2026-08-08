"""NLQueryEngine 单元测试。

mock IntentClassifier + TemplateExecutor + LLMClient，验证路由、回答生成与多轮追问。
"""
import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.query.conversation_store import conversation_store
from app.query.nl_query_engine import NLQueryEngine
from app.query.query_result import QueryResult


class TestNLQueryEngine:
    """NL 查询引擎测试。"""

    @pytest.fixture(autouse=True)
    def _clear_conversations(self):
        """会话存储为进程内单例，测试间隔离。"""
        conversation_store.clear()
        yield
        conversation_store.clear()

    @pytest.mark.asyncio
    async def test_query_event_lookup_routes_to_event_lookup_template(self):
        """event_lookup 意图路由到 execute_event_lookup。"""
        mock_classifier = AsyncMock()
        mock_classifier.classify.return_value = "event_lookup"
        mock_executor = AsyncMock()
        mock_executor.execute_event_lookup.return_value = {"orderId": "abc", "status": "PAID"}
        mock_llm = AsyncMock()
        mock_llm.generate.return_value = "订单 abc 当前状态为 PAID。"

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )

        result = await engine.query("订单 abc 当前状态是什么？")

        assert isinstance(result, QueryResult)
        assert result.intent == "event_lookup"
        mock_executor.execute_event_lookup.assert_called_once()
        assert "PAID" in result.answer

    @pytest.mark.asyncio
    async def test_query_stats_aggregation_routes_to_stats_template(self):
        """stats_aggregation 意图路由到 execute_stats_aggregation。"""
        mock_classifier = AsyncMock()
        mock_classifier.classify.return_value = "stats_aggregation"
        mock_executor = AsyncMock()
        mock_executor.execute_stats_aggregation.return_value = [
            {"status": "PAID", "orderCount": 5}
        ]
        mock_llm = AsyncMock()
        mock_llm.generate.return_value = "昨天有 5 个 PAID 订单。"

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )

        result = await engine.query("昨天有多少 PAID 订单？")

        assert result.intent == "stats_aggregation"
        mock_executor.execute_stats_aggregation.assert_called_once()
        assert "5" in result.answer

    @pytest.mark.asyncio
    async def test_query_trace_replay_routes_to_trace_template(self):
        """trace_replay 意图路由到 execute_trace_replay。"""
        mock_classifier = AsyncMock()
        mock_classifier.classify.return_value = "trace_replay"
        mock_executor = AsyncMock()
        mock_executor.execute_trace_replay.return_value = [
            {"eventType": "OrderCreatedEvent", "version": 1}
        ]
        mock_llm = AsyncMock()
        mock_llm.generate.return_value = "订单经历了 OrderCreatedEvent 事件。"

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )

        result = await engine.query("订单 abc 经历了哪些状态变更？")

        assert result.intent == "trace_replay"
        mock_executor.execute_trace_replay.assert_called_once()

    @pytest.mark.asyncio
    async def test_query_llm_failure_returns_data_with_raw_answer(self):
        """LLM 润色失败时仍返回 QueryResult，answer 为数据摘要。"""
        mock_classifier = AsyncMock()
        mock_classifier.classify.return_value = "event_lookup"
        mock_executor = AsyncMock()
        mock_executor.execute_event_lookup.return_value = {"orderId": "abc", "status": "PAID"}
        mock_llm = AsyncMock()
        mock_llm.generate.side_effect = RuntimeError("llm down")

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )

        result = await engine.query("订单 abc 状态？")

        assert result.intent == "event_lookup"
        # answer 应含原始数据摘要，不抛异常
        assert "abc" in result.answer or "PAID" in result.answer

    @pytest.mark.asyncio
    async def test_query_llm_timeout_falls_back_to_summary(self, monkeypatch):
        """LLM 响应超时（超过 LLM_ANSWER_TIMEOUT_SECONDS）时降级为数据摘要，而非让前端等超时。

        对应加固项：LLM 底层 httpx 超时 30s > 前端 axios 10s，必须由 NL 路径单独 8s 上界兜底。
        """
        monkeypatch.setattr("app.query.nl_query_engine.LLM_ANSWER_TIMEOUT_SECONDS", 0.05)
        mock_classifier = AsyncMock()
        mock_classifier.classify.return_value = "event_lookup"
        mock_executor = AsyncMock()
        mock_executor.execute_event_lookup.return_value = {"orderId": "abc", "status": "PAID"}
        mock_llm = AsyncMock()

        async def slow_generate(_prompt: str):
            await asyncio.sleep(1.0)  # 远超 0.05s 上界，应被 wait_for 取消
            return "太慢的回答"

        mock_llm.generate.side_effect = slow_generate

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )

        result = await engine.query("订单 abc 状态？")

        assert result.intent == "event_lookup"
        # 返回降级摘要而非抛出超时异常
        assert "PAID" in result.answer or "abc" in result.answer

    # ---------- 多轮对话 / 追问澄清（Item 1） ----------

    @pytest.mark.asyncio
    async def test_query_missing_order_id_asks_then_resolves(self):
        """缺订单号时反问（needs_input=True）；下一轮携带 conversation_id + 订单号补查成功。"""
        mock_classifier = AsyncMock()
        mock_classifier.classify.side_effect = ["event_lookup", "event_lookup"]
        mock_executor = AsyncMock()
        mock_executor.execute_event_lookup.side_effect = [
            ValueError("无法从问题中提取 order_id"),
            {"orderId": "abc", "status": "PAID"},
        ]
        mock_llm = AsyncMock()
        mock_llm.generate.return_value = "订单 abc 当前状态为 PAID。"

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )

        r1 = await engine.query("这个订单什么状态？")
        assert r1.needs_input is True
        assert "订单号" in r1.answer
        assert r1.conversation_id is not None

        r2 = await engine.query("订单 abc 当前状态？", r1.conversation_id)
        assert r2.needs_input is False
        assert r2.data == {"orderId": "abc", "status": "PAID"}
        assert r2.conversation_id == r1.conversation_id
        assert mock_executor.execute_event_lookup.call_count == 2

    @pytest.mark.asyncio
    async def test_followup_uses_conversation_order_id_context(self):
        """同一会话内追问缺订单号时，用上一轮捕获的订单号补参重查。"""
        mock_classifier = AsyncMock()
        mock_classifier.classify.side_effect = ["event_lookup", "trace_replay"]
        mock_executor = AsyncMock()
        mock_executor.execute_event_lookup.return_value = {"orderId": "1111-2222", "status": "PAID"}
        mock_executor.execute_trace_replay.side_effect = [
            ValueError("无法从问题中提取 order_id"),
            [{"eventType": "OrderCreatedEvent", "version": 1}],
        ]
        # 真实 TemplateExecutor 的 resolve_order_id 从问题提取 UUID；mock 里固定返回订单号
        mock_executor.resolve_order_id = MagicMock(return_value="1111-2222")
        mock_llm = AsyncMock()
        mock_llm.generate.return_value = "好的"

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )

        r1 = await engine.query("订单 1111-2222 状态？")
        assert r1.data == {"orderId": "1111-2222", "status": "PAID"}

        r2 = await engine.query("它的轨迹呢", r1.conversation_id)
        assert r2.needs_input is False
        assert r2.data == [{"eventType": "OrderCreatedEvent", "version": 1}]
        # 第一次 trace 路由缺参失败后，第二次用上下文订单号补参重查
        trace_args = mock_executor.execute_trace_replay.call_args_list
        assert len(trace_args) == 2
        assert "1111-2222" in trace_args[1].args[0]

    @pytest.mark.asyncio
    async def test_query_without_order_id_still_returns_hint_not_500(self):
        """无论如何都缺订单号时，返回反问而非裸异常（多轮场景的兜底）。"""
        mock_classifier = AsyncMock()
        mock_classifier.classify.return_value = "event_lookup"
        mock_executor = AsyncMock()
        mock_executor.execute_event_lookup.side_effect = ValueError("无法从问题中提取 order_id")
        mock_llm = AsyncMock()

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )

        r = await engine.query("查看一个订单")
        assert r.needs_input is True
        assert r.data is None
        assert "订单号" in r.answer
