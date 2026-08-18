import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from pydantic import ValidationError


def test_analysis_report_validates_suggestion_whitelist():
    """AnalysisReport 校验建议动作必须在白名单内"""
    from app.model.analysis_report import AnalysisReport, Suggestion

    # 合法建议
    report = AnalysisReport(
        anomaly_id="a-1",
        root_cause="订单停滞",
        evidence=["事件序列: [CREATED, PAID]"],
        suggestions=[
            Suggestion(action="NOTIFY_DELAY", reason="通知用户", risk="LOW"),
            Suggestion(action="MARK_OUT_OF_STOCK", reason="标记缺货", risk="LOW"),
        ],
    )
    assert len(report.suggestions) == 2

    # 非法建议动作
    with pytest.raises(ValidationError):
        Suggestion(action="DELETE_DATABASE", reason="删除数据库", risk="HIGH")


def test_prompt_builder_includes_anomaly_and_action_catalog():
    """PromptBuilder 包含异常信息 + 动作白名单"""
    from app.analyzer.prompt_builder import PromptBuilder
    from app.model.anomaly import Anomaly

    anomaly = Anomaly(
        anomaly_id="a-1",
        rule_id="P002_STUCK",
        aggregate_id="agg-1",
        event_type="PaymentCompletedEvent",
        level="WARN",
        source="PROCESS",
        priority="HIGH",
        detected_at="2026-07-21T10:00:00Z",
        description="状态停滞超过 24h",
    )
    events = [
        {"event_type": "OrderCreatedEvent", "created_at": "2026-07-20T10:00:00Z"},
        {"event_type": "PaymentCompletedEvent", "created_at": "2026-07-20T10:05:00Z"},
    ]

    prompt = PromptBuilder.build(anomaly, events, context={"stock": 0})

    assert "P002_STUCK" in prompt
    assert "agg-1" in prompt
    assert "REFUND" in prompt  # 动作白名单
    assert "NOTIFY_DELAY" in prompt
    assert "MARK_OUT_OF_STOCK" in prompt
    assert "FREEZE_ORDER" in prompt
    assert "BACKOFF_AND_STOP" in prompt


@pytest.mark.asyncio
async def test_root_cause_analyzer_returns_valid_report():
    """RootCauseAnalyzer.analyze 返回合法 AnalysisReport"""
    from app.analyzer.root_cause import RootCauseAnalyzer
    from app.model.anomaly import Anomaly
    from app.model.analysis_report import AnalysisReport

    anomaly = Anomaly(
        anomaly_id="a-1",
        rule_id="P002_STUCK",
        aggregate_id="agg-1",
        event_type="PaymentCompletedEvent",
        level="WARN",
        source="PROCESS",
        priority="HIGH",
        detected_at="2026-07-21T10:00:00Z",
        description="状态停滞超过 24h",
    )

    # Mock LLM 返回合法 JSON
    llm_response = json.dumps({
        "anomaly_id": "a-1",
        "root_cause": "订单 PAID 后库存服务未发出 InventoryReserved 事件，库存为 0",
        "evidence": [
            "事件序列: [CREATED, PAID] 后无后续",
            "库存查询: SKU=123 当前库存=0",
            "停滞时长: 26h",
        ],
        "suggestions": [
            {"action": "MARK_OUT_OF_STOCK", "reason": "库存为0", "risk": "LOW"},
            {"action": "NOTIFY_DELAY", "reason": "通知用户延迟", "risk": "LOW"},
        ],
    })

    mock_llm = AsyncMock()
    mock_llm.generate_json.return_value = llm_response

    mock_event_client = MagicMock()
    mock_event_client.load_events.return_value = [
        {"event_type": "OrderCreatedEvent", "created_at": "2026-07-20T10:00:00Z"},
        {"event_type": "PaymentCompletedEvent", "created_at": "2026-07-20T10:05:00Z"},
    ]

    analyzer = RootCauseAnalyzer(llm_client=mock_llm, event_store_client=mock_event_client)
    report = await analyzer.analyze(anomaly)

    assert isinstance(report, AnalysisReport)
    assert report.anomaly_id == "a-1"
    assert "库存" in report.root_cause
    assert len(report.suggestions) == 2
    assert report.suggestions[0].action in ["MARK_OUT_OF_STOCK", "NOTIFY_DELAY"]


@pytest.mark.asyncio
async def test_root_cause_analyzer_raises_on_invalid_suggestion():
    """LLM 返回非法建议动作时抛异常"""
    from app.analyzer.root_cause import RootCauseAnalyzer, LLMResponseError
    from app.model.anomaly import Anomaly

    anomaly = Anomaly(
        anomaly_id="a-2",
        rule_id="R001",
        aggregate_id="agg-2",
        event_type="OrderCreatedEvent",
        level="WARN",
        source="RULE",
        priority="HIGH",
        detected_at="2026-07-21T10:00:00Z",
        description="金额偏离",
    )

    llm_response = json.dumps({
        "anomaly_id": "a-2",
        "root_cause": "测试",
        "evidence": [],
        "suggestions": [
            {"action": "DROP_DATABASE", "reason": "非法", "risk": "HIGH"},
        ],
    })

    mock_llm = AsyncMock()
    mock_llm.generate_json.return_value = llm_response
    mock_event_client = MagicMock()
    mock_event_client.load_events.return_value = []

    analyzer = RootCauseAnalyzer(llm_client=mock_llm, event_store_client=mock_event_client)

    with pytest.raises(LLMResponseError):
        await analyzer.analyze(anomaly)


@pytest.mark.asyncio
async def test_root_cause_evidence_mismatch_retries_and_succeeds():
    """evidence 提及的事件不在序列中 → 错误反馈重试一次 → 修正后成功（Item 3）。"""
    from app.analyzer.root_cause import RootCauseAnalyzer
    from app.model.anomaly import Anomaly
    from app.model.analysis_report import AnalysisReport

    anomaly = Anomaly(
        anomaly_id="a-3", rule_id="P002_STUCK", aggregate_id="agg-3",
        event_type="PaymentCompletedEvent", level="WARN", source="PROCESS",
        priority="HIGH", detected_at="2026-07-21T10:00:00Z", description="停滞",
    )
    bad = json.dumps({
        "anomaly_id": "a-3", "root_cause": "停滞",
        "evidence": ["异常事件：PaymentTimeoutEvent"],  # 不在序列中 → 应触发重试
        "suggestions": [{"action": "NOTIFY_DELAY", "reason": "r", "risk": "LOW"}],
    })
    good = json.dumps({
        "anomaly_id": "a-3", "root_cause": "停滞",
        "evidence": ["异常事件：PaymentCompletedEvent"],  # 在序列中 → 通过
        "suggestions": [{"action": "NOTIFY_DELAY", "reason": "r", "risk": "LOW"}],
    })
    mock_llm = AsyncMock()
    mock_llm.generate_json.side_effect = [bad, good]
    mock_events = MagicMock()
    mock_events.load_events.return_value = [
        {"event_type": "OrderCreatedEvent", "created_at": "2026-07-20T10:00:00Z"},
        {"event_type": "PaymentCompletedEvent", "created_at": "2026-07-20T10:05:00Z"},
    ]

    analyzer = RootCauseAnalyzer(llm_client=mock_llm, event_store_client=mock_events)
    report = await analyzer.analyze(anomaly)

    assert isinstance(report, AnalysisReport)
    assert mock_llm.generate_json.call_count == 2


@pytest.mark.asyncio
async def test_root_cause_json_parse_failure_retries_and_succeeds():
    """首次输出非法 JSON → 反馈重试一次 → 成功（Item 3）。"""
    from app.analyzer.root_cause import RootCauseAnalyzer
    from app.model.anomaly import Anomaly
    from app.model.analysis_report import AnalysisReport

    anomaly = Anomaly(
        anomaly_id="a-4", rule_id="R001", aggregate_id="agg-4",
        event_type="OrderCreatedEvent", level="WARN", source="RULE",
        priority="HIGH", detected_at="2026-07-21T10:00:00Z", description="金额偏离",
    )
    good = json.dumps({
        "anomaly_id": "a-4", "root_cause": "偏离",
        "evidence": ["金额偏离 3σ"],
        "suggestions": [{"action": "FREEZE_ORDER", "reason": "r", "risk": "LOW"}],
    })
    mock_llm = AsyncMock()
    mock_llm.generate_json.side_effect = ["这不是 JSON", good]
    mock_events = MagicMock()
    mock_events.load_events.return_value = [
        {"event_type": "OrderCreatedEvent", "created_at": "2026-07-20T10:00:00Z"},
    ]

    analyzer = RootCauseAnalyzer(llm_client=mock_llm, event_store_client=mock_events)
    report = await analyzer.analyze(anomaly)

    assert isinstance(report, AnalysisReport)
    assert mock_llm.generate_json.call_count == 2


def test_maybe_add_fewshot_appends_when_enabled(monkeypatch):
    """EG_AI_RAG_FEWSHOT=true 且注入 case_index 时，相似案例并入 prompt（Item 8）。"""
    from app.analyzer.root_cause import RootCauseAnalyzer
    from app.model.anomaly import Anomaly

    anomaly = Anomaly(
        anomaly_id="a-5", rule_id="P002_STUCK", aggregate_id="agg-5",
        event_type="PaymentCompletedEvent", level="WARN", source="PROCESS",
        priority="HIGH", detected_at="2026-07-21T10:00:00Z", description="停滞",
    )
    monkeypatch.setattr("app.analyzer.root_cause.settings.ai_rag_fewshot", True)
    case_index = MagicMock()
    case_index.top_k_cases.return_value = [
        (0.8, Anomaly(anomaly_id="c1", rule_id="P002_STUCK", aggregate_id="agg-9",
                      event_type="PaymentCompletedEvent", level="WARN", source="PROCESS",
                      priority="HIGH", detected_at="2026-07-20T10:00:00Z", description="停滞"))
    ]
    analyzer = RootCauseAnalyzer(llm_client=MagicMock(), case_index=case_index)
    out = analyzer._maybe_add_fewshot("基础 prompt", anomaly)
    assert "相似历史案例" in out
    assert "P002_STUCK" in out


def test_maybe_add_fewshot_off_by_default():
    """默认关闭 few-shot：prompt 不变。"""
    from app.analyzer.root_cause import RootCauseAnalyzer
    from app.model.anomaly import Anomaly

    anomaly = Anomaly(
        anomaly_id="a-6", rule_id="R001", aggregate_id="agg-6",
        event_type="OrderCreatedEvent", level="WARN", source="RULE",
        priority="HIGH", detected_at="2026-07-21T10:00:00Z", description="金额偏离",
    )
    analyzer = RootCauseAnalyzer(llm_client=MagicMock(), case_index=MagicMock())
    out = analyzer._maybe_add_fewshot("基础 prompt", anomaly)
    assert out == "基础 prompt"
