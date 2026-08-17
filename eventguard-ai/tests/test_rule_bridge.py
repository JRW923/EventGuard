from unittest.mock import MagicMock, patch

import httpx
import pytest


def test_rule_bridge_returns_result_when_rule_hits():
    """规则命中时 RuleBridge 返回 AnomalyResult"""
    from app.detector.rule_bridge import RuleBridge
    from app.model.anomaly import AnomalyResult

    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {
        "anomalyId": "a-1",
        "ruleId": "R001",
        "aggregateId": "agg-1",
        "eventType": "OrderCreatedEvent",
        "level": "WARN",
        "description": "金额偏离",
        "details": {},
    }
    mock_response.raise_for_status = MagicMock()

    with patch("app.detector.rule_bridge.httpx.Client") as mock_client_cls:
        mock_client = MagicMock()
        mock_client.is_closed = False
        mock_client.post.return_value = mock_response
        mock_client_cls.return_value = mock_client

        bridge = RuleBridge(url="http://localhost:8080/anomaly/rules/evaluate")
        event = {
            "event_id": "e-1",
            "aggregate_id": "agg-1",
            "event_type": "OrderCreatedEvent",
            "event_version": 1,
            "payload": {"totalAmount": 999.0, "userId": "user-1"},
            "metadata": {"userId": "user-1"},
            "created_at": "2026-07-21T10:00:00Z",
        }
        result = bridge.evaluate(event)

    assert result is not None
    assert result.is_anomaly is True
    assert result.source == "RULE"
    assert result.level == "HIGH"
    assert result.rule_id == "R001"


def test_rule_bridge_returns_none_when_no_rule_hits():
    """规则未命中时返回 None"""
    from app.detector.rule_bridge import RuleBridge

    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = None  # 无异常
    mock_response.raise_for_status = MagicMock()

    with patch("app.detector.rule_bridge.httpx.Client") as mock_client_cls:
        mock_client = MagicMock()
        mock_client.is_closed = False
        mock_client.post.return_value = mock_response
        mock_client_cls.return_value = mock_client

        bridge = RuleBridge(url="http://localhost:8080/anomaly/rules/evaluate")
        event = {"event_type": "OrderCreatedEvent", "payload": {}, "metadata": {}}
        result = bridge.evaluate(event)

    assert result is None


def test_event_level_service_rule_hit_returns_high_priority():
    """规则命中 → 高优先级告警"""
    from app.detector.event_level import EventLevelService
    from app.model.anomaly import AnomalyResult

    mock_rule_bridge = MagicMock()
    mock_rule_bridge.evaluate.return_value = AnomalyResult(
        is_anomaly=True, score=0.0, source="RULE", level="HIGH", rule_id="R001",
        description="金额偏离",
    )
    mock_if_detector = MagicMock()
    mock_if_detector.detect.return_value = AnomalyResult(
        is_anomaly=False, score=0.1, source="IF", level="LOW",
    )

    service = EventLevelService(rule_bridge=mock_rule_bridge, if_detector=mock_if_detector)
    event = {"event_type": "OrderCreatedEvent", "aggregate_id": "agg-1"}

    result = service.detect(event)

    assert result.is_anomaly is True
    assert result.source == "RULE"
    assert result.level == "HIGH"
    mock_if_detector.detect.assert_not_called()  # 规则命中时不走 IF


def test_event_level_service_rule_miss_if_hit_returns_low_priority():
    """规则未命中 → 走 IF → IF 异常 → 低优先级告警"""
    from app.detector.event_level import EventLevelService
    from app.model.anomaly import AnomalyResult

    mock_rule_bridge = MagicMock()
    mock_rule_bridge.evaluate.return_value = None  # 规则未命中
    mock_if_detector = MagicMock()
    mock_if_detector.detect.return_value = AnomalyResult(
        is_anomaly=True, score=0.85, source="IF", level="LOW",
        description="IF score=0.85",
    )

    service = EventLevelService(rule_bridge=mock_rule_bridge, if_detector=mock_if_detector)
    event = {"event_type": "OrderCreatedEvent", "aggregate_id": "agg-1"}

    result = service.detect(event)

    assert result.is_anomaly is True
    assert result.source == "IF"
    assert result.level == "LOW"
    mock_if_detector.detect.assert_called_once()


def test_event_level_service_all_clear_returns_not_anomaly():
    """规则 + IF 都未命中 → 无异常"""
    from app.detector.event_level import EventLevelService
    from app.model.anomaly import AnomalyResult

    mock_rule_bridge = MagicMock()
    mock_rule_bridge.evaluate.return_value = None
    mock_if_detector = MagicMock()
    mock_if_detector.detect.return_value = AnomalyResult(
        is_anomaly=False, score=0.1, source="IF", level="LOW",
    )

    service = EventLevelService(rule_bridge=mock_rule_bridge, if_detector=mock_if_detector)

    result = service.detect({"event_type": "OrderCreatedEvent"})

    assert result.is_anomaly is False
