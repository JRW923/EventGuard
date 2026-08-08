"""HealerAgent 单元测试：工具调用 → 收尾；超步数兜底；工具失败容错；报告降级。"""
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.analyzer.healer_agent import HealerAgent
from app.analyzer.root_cause import LLMResponseError
from app.model.analysis_report import AnalysisReport
from app.model.anomaly import Anomaly


def _anomaly() -> Anomaly:
    return Anomaly(
        anomaly_id="a-1", rule_id="P002_STUCK", aggregate_id="agg-1",
        event_type="PaymentCompletedEvent", level="WARN", source="PROCESS",
        priority="HIGH", detected_at="2026-07-21T00:00:00Z", description="停滞",
    )


def _report() -> AnalysisReport:
    return AnalysisReport(anomaly_id="a-1", root_cause="停滞", evidence=["e1"], suggestions=[])


def _agent(llm, backend, analyzer) -> HealerAgent:
    return HealerAgent(llm_client=llm, backend_client=backend, root_cause_analyzer=analyzer)


@pytest.mark.asyncio
async def test_agent_calls_tool_then_concludes():
    """agent 调用 query_order 收集证据 → 收尾 → 返回报告 + 分析过程。"""
    llm = AsyncMock()
    llm.generate_with_tools.side_effect = [
        ("", [{"id": "t1", "name": "query_order", "input": {"aggregate_id": "agg-1"}}]),
        ("分析完成", []),
    ]
    backend = MagicMock()
    backend.get_order = AsyncMock(return_value={"orderId": "agg-1", "status": "PAID"})
    analyzer = MagicMock()
    analyzer.analyze = AsyncMock(return_value=_report())

    result = await _agent(llm, backend, analyzer).heal(_anomaly())

    assert result["report"]["root_cause"] == "停滞"
    assert len(result["agent_trace"]) == 1
    assert result["agent_trace"][0]["tool"] == "query_order"
    assert backend.get_order.await_args.args[0] == "agg-1"
    assert analyzer.analyze.await_count == 1


@pytest.mark.asyncio
async def test_agent_max_steps_falls_back_to_deterministic():
    """agent 永不收敛（一直调工具）→ 超过 MAX_STEPS 走确定性根因分析兜底。"""
    llm = AsyncMock()
    llm.generate_with_tools.side_effect = [
        ("", [{"id": f"t{i}", "name": "query_order", "input": {"aggregate_id": "agg-1"}}])
        for i in range(20)
    ]
    backend = MagicMock()
    backend.get_order = AsyncMock(return_value={})
    analyzer = MagicMock()
    analyzer.analyze = AsyncMock(return_value=_report())

    result = await _agent(llm, backend, analyzer).heal(_anomaly())

    assert result["note"]
    assert "超过最大步数" in result["note"]
    assert len(result["agent_trace"]) >= 5  # MAX_STEPS 次工具调用后停止
    assert analyzer.analyze.await_count == 1


@pytest.mark.asyncio
async def test_tool_failure_returns_error_not_raise():
    """工具执行抛异常 → 记录错误串，不中断 agent 循环。"""
    llm = AsyncMock()
    llm.generate_with_tools.side_effect = [
        ("", [{"id": "t1", "name": "query_events", "input": {"aggregate_id": "agg-1"}}]),
        ("分析完成", []),
    ]
    backend = MagicMock()
    backend.get_events = AsyncMock(side_effect=RuntimeError("后端挂了"))
    analyzer = MagicMock()
    analyzer.analyze = AsyncMock(return_value=_report())

    result = await _agent(llm, backend, analyzer).heal(_anomaly())

    assert "error" in result["agent_trace"][0]["output"]
    assert "执行失败" in result["agent_trace"][0]["output"]


@pytest.mark.asyncio
async def test_final_report_fallback_when_analyzer_fails():
    """根因分析器失败 → 用 agent 结论文本生成降级报告，不抛异常。"""
    llm = AsyncMock()
    llm.generate_with_tools.side_effect = [("分析完成", [])]
    backend = MagicMock()
    analyzer = MagicMock()
    analyzer.analyze = AsyncMock(side_effect=LLMResponseError("LLM 挂了"))

    result = await _agent(llm, backend, analyzer).heal(_anomaly())

    assert result["report"]["root_cause"] == "分析完成"
    assert result["report"]["suggestions"] == []
    assert result["agent_trace"] == []
