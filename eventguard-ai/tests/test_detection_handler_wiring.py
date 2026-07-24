"""DetectionHandler 接线 smoke 测试：注入 EventWindow + ProcessLevelRuleDetector 后 handle() 应产出 PROCESS 告警"""

from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock

from app.detector.event_window import EventWindow
from app.detector.process_level import ProcessLevelRuleDetector
from app.kafka_consumer import DetectionHandler
from app.model.anomaly import Anomaly, AnomalyResult
from app.store.anomaly_store import anomaly_store


def _make_event(event_type: str, agg_id: str, version: int, ts: str) -> dict:
    return {
        "event_id": f"e-{version}",
        "aggregate_id": agg_id,
        "event_type": event_type,
        "event_version": version,
        "payload": {},
        "metadata": {"userId": "user-1"},
        "created_at": ts,
    }


def test_detection_handler_wires_process_level_detection():
    """注入真实 EventWindow + ProcessLevelRuleDetector 后，含非法迁移的序列经 handle() 产出 PROCESS 告警"""
    anomaly_store.clear()
    try:
        # mock 事件级服务：始终返回正常（不干扰流程级断言）
        svc = MagicMock()
        svc.detect.return_value = AnomalyResult(is_anomaly=False, source="IF", level="LOW")
        publisher = MagicMock()

        event_window = EventWindow(window_size=20)
        detector = ProcessLevelRuleDetector()
        handler = DetectionHandler(
            event_level_service=svc,
            publisher=publisher,
            process_level_detector=detector,
            event_window=event_window,
        )

        base_ts = datetime(2026, 7, 21, 10, 0, 0, tzinfo=timezone.utc)
        # 非法迁移：OrderCreatedEvent → ShippedEvent（跳过 PAID/CONFIRMED）
        events = [
            _make_event("OrderCreatedEvent", "agg-1", 1, base_ts.isoformat()),
            _make_event("ShippedEvent", "agg-1", 2, (base_ts + timedelta(minutes=1)).isoformat()),
        ]
        for ev in events:
            handler.handle(ev)

        # 至少发布过一次（非法迁移触发 PROCESS 告警）
        assert publisher.publish.called

        published = [call.args[0] for call in publisher.publish.call_args_list]
        assert all(isinstance(a, Anomaly) for a in published)
        process_anoms = [a for a in published if a.source == "PROCESS"]
        # 接线生效：至少产出一个 PROCESS 告警（此处为非法迁移 P001）
        assert len(process_anoms) >= 1
        assert "P001_ILLEGAL_TRANSITION" in [a.rule_id for a in process_anoms]
    finally:
        anomaly_store.clear()
