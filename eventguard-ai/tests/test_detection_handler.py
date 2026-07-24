"""DetectionHandler + AnomalyStore 最小自检（mock 外部依赖）"""

from unittest.mock import MagicMock

from app.kafka_consumer import DetectionHandler
from app.model.anomaly import Anomaly, AnomalyResult
from app.store.anomaly_store import anomaly_store, AnomalyStore


def _make_event() -> dict:
    return {"event_type": "OrderCreatedEvent", "aggregate_id": "agg-1"}


def _clear_global_store():
    anomaly_store.clear()


def test_detection_handler_rule_hit_maps_correctly():
    """RULE 命中: level→ERROR, priority→HIGH, source→RULE, rule_id→R001, details→{}"""
    _clear_global_store()
    try:
        svc = MagicMock()
        svc.detect.return_value = AnomalyResult(
            is_anomaly=True, source="RULE", level="HIGH", rule_id="R001",
            description="金额偏离",
        )
        publisher = MagicMock()
        handler = DetectionHandler(event_level_service=svc, publisher=publisher)

        handler.handle(_make_event())

        assert publisher.publish.call_count == 1
        anomaly = publisher.publish.call_args[0][0]
        assert isinstance(anomaly, Anomaly)
        assert anomaly.level == "ERROR"
        assert anomaly.priority == "HIGH"
        assert anomaly.source == "RULE"
        assert anomaly.rule_id == "R001"
        assert anomaly.details == {}
    finally:
        _clear_global_store()


def test_detection_handler_if_hit_maps_correctly():
    """IF 命中: level→WARN, priority→LOW, details→{score:0.9}"""
    _clear_global_store()
    try:
        svc = MagicMock()
        svc.detect.return_value = AnomalyResult(
            is_anomaly=True, source="IF", level="LOW", score=0.9,
            description="IF score=0.9",
        )
        publisher = MagicMock()
        handler = DetectionHandler(event_level_service=svc, publisher=publisher)

        handler.handle(_make_event())

        assert publisher.publish.call_count == 1
        anomaly = publisher.publish.call_args[0][0]
        assert anomaly.level == "WARN"
        assert anomaly.priority == "LOW"
        assert anomaly.details == {"score": 0.9}
    finally:
        _clear_global_store()


def test_detection_handler_no_anomaly_skips_publish():
    """无异常: publisher.publish 未被调用"""
    _clear_global_store()
    try:
        svc = MagicMock()
        svc.detect.return_value = AnomalyResult(is_anomaly=False, source="IF", level="LOW")
        publisher = MagicMock()
        handler = DetectionHandler(event_level_service=svc, publisher=publisher)

        handler.handle(_make_event())

        publisher.publish.assert_not_called()
    finally:
        _clear_global_store()


def test_anomaly_store_roundtrip():
    """AnomalyStore 存→取 往返一致"""
    a = Anomaly(
        anomaly_id="a-1",
        rule_id="IF",
        aggregate_id="agg-1",
        event_type="OrderCreatedEvent",
        level="WARN",
        source="IF",
        priority="LOW",
        detected_at="2026-07-21T00:00:00Z",
        description="占位异常",
    )
    store = AnomalyStore()
    store.save(a)
    assert store.get(a.anomaly_id) is a
