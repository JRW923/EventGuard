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

        # 时间戳贴近 now，间隔仅几秒：使得 P002(停滞>24h) 不触发，精准隔离 P001
        base_ts = datetime.now(timezone.utc)
        # 非法迁移：OrderCreatedEvent → ShippedEvent（跳过 PAID/CONFIRMED）
        events = [
            _make_event("OrderCreatedEvent", "agg-1", 1, base_ts.isoformat()),
            _make_event("ShippedEvent", "agg-1", 2, (base_ts + timedelta(seconds=5)).isoformat()),
        ]
        for ev in events:
            handler.handle(ev)

        # 至少发布过一次（非法迁移触发 PROCESS 告警）
        assert publisher.publish.called

        published = [call.args[0] for call in publisher.publish.call_args_list]
        assert all(isinstance(a, Anomaly) for a in published)
        process_anoms = [a for a in published if a.source == "PROCESS"]
        # 接线生效且精准隔离：产出的 PROCESS 告警就是 P001_ILLEGAL_TRANSITION（无顺带 P002）
        assert len(process_anoms) == 1, f"期望恰好 1 个 PROCESS 告警, 实际 {len(process_anoms)}: {[a.rule_id for a in process_anoms]}"
        assert process_anoms[0].rule_id == "P001_ILLEGAL_TRANSITION"
    finally:
        anomaly_store.clear()
