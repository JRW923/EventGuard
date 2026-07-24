import json
from unittest.mock import MagicMock, patch

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


def test_root_cause_analyzer_returns_valid_report():
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

    mock_llm = MagicMock()
    mock_llm.generate.return_value = llm_response

    mock_event_client = MagicMock()
    mock_event_client.load_events.return_value = [
        {"event_type": "OrderCreatedEvent", "created_at": "2026-07-20T10:00:00Z"},
        {"event_type": "PaymentCompletedEvent", "created_at": "2026-07-20T10:05:00Z"},
    ]

    analyzer = RootCauseAnalyzer(llm_client=mock_llm, event_store_client=mock_event_client)
    report = analyzer.analyze(anomaly)

    assert isinstance(report, AnalysisReport)
    assert report.anomaly_id == "a-1"
    assert "库存" in report.root_cause
    assert len(report.suggestions) == 2
    assert report.suggestions[0].action in ["MARK_OUT_OF_STOCK", "NOTIFY_DELAY"]


def test_root_cause_analyzer_raises_on_invalid_suggestion():
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

    mock_llm = MagicMock()
    mock_llm.generate.return_value = llm_response
    mock_event_client = MagicMock()
    mock_event_client.load_events.return_value = []

    analyzer = RootCauseAnalyzer(llm_client=mock_llm, event_store_client=mock_event_client)

    with pytest.raises(LLMResponseError):
        analyzer.analyze(anomaly)
