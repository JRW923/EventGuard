from datetime import datetime, timedelta, timezone

from app.detector.event_window import EventWindow
from app.detector.process_level import ProcessLevelRuleDetector


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


def test_event_window_maintains_last_20_events_per_aggregate():
    """EventWindow 按 aggregate_id 维护最近 20 事件"""
    window = EventWindow(window_size=20)
    agg_id = "agg-1"
    base_ts = datetime(2026, 7, 21, 10, 0, 0, tzinfo=timezone.utc)

    for i in range(25):
        event = _make_event("OrderCreatedEvent", agg_id, i + 1, base_ts.isoformat())
        window.add(event)

    events = window.get(agg_id)
    assert len(events) == 20  # 只保留最近 20 个
    # 最早保留的应该是 version=6
    assert events[0]["event_version"] == 6
    assert events[-1]["event_version"] == 25


def test_event_window_separates_aggregates():
    """不同 aggregate_id 的窗口互相隔离"""
    window = EventWindow(window_size=20)
    window.add(_make_event("OrderCreatedEvent", "agg-1", 1, "2026-07-21T10:00:00Z"))
    window.add(_make_event("OrderCreatedEvent", "agg-2", 1, "2026-07-21T10:01:00Z"))

    assert len(window.get("agg-1")) == 1
    assert len(window.get("agg-2")) == 1


def test_detect_illegal_transition():
    """P001：非法迁移检测（PENDING_PAYMENT → SHIPPED 跳过 PAID/CONFIRMED）"""
    detector = ProcessLevelRuleDetector()
    base_ts = datetime(2026, 7, 21, 10, 0, 0, tzinfo=timezone.utc)
    sequence = [
        _make_event("OrderCreatedEvent", "agg-1", 1, base_ts.isoformat()),
        _make_event("ShippedEvent", "agg-1", 2, (base_ts + timedelta(minutes=1)).isoformat()),
    ]
    anomalies = detector.detect(sequence)
    assert len(anomalies) >= 1
    p001 = [a for a in anomalies if a.rule_id == "P001_ILLEGAL_TRANSITION"]
    assert len(p001) == 1
    assert p001[0].source == "PROCESS"


def test_detect_state_stagnation():
    """P002：状态停滞检测（PAID 后 24h+ 无后续）"""
    detector = ProcessLevelRuleDetector()
    base_ts = datetime(2026, 7, 21, 10, 0, 0, tzinfo=timezone.utc)
    # PaymentCompleted 发生在 25 小时前
    old_ts = base_ts - timedelta(hours=25)
    sequence = [
        _make_event("OrderCreatedEvent", "agg-1", 1, (old_ts - timedelta(minutes=5)).isoformat()),
        _make_event("PaymentCompletedEvent", "agg-1", 2, old_ts.isoformat()),
    ]
    anomalies = detector.detect(sequence, now=base_ts)
    p002 = [a for a in anomalies if a.rule_id == "P002_STUCK"]
    assert len(p002) == 1


def test_detect_payment_dead_loop():
    """P003：死循环检测（PaymentFailed→Retried 重复 >5 次）"""
    detector = ProcessLevelRuleDetector()
    base_ts = datetime(2026, 7, 21, 10, 0, 0, tzinfo=timezone.utc)
    sequence = [_make_event("OrderCreatedEvent", "agg-1", 1, base_ts.isoformat())]
    ts = base_ts + timedelta(minutes=1)
    for i in range(6):  # 6 轮 Failed+Retried
        sequence.append(_make_event("PaymentFailedEvent", "agg-1", 2 + i * 2, ts.isoformat()))
        ts += timedelta(seconds=30)
        sequence.append(_make_event("PaymentRetriedEvent", "agg-1", 3 + i * 2, ts.isoformat()))
        ts += timedelta(seconds=30)

    anomalies = detector.detect(sequence)
    p003 = [a for a in anomalies if a.rule_id == "P003_DEAD_LOOP"]
    assert len(p003) == 1


def test_detect_returns_empty_for_normal_sequence():
    """正常序列不报异常"""
    detector = ProcessLevelRuleDetector()
    base_ts = datetime(2026, 7, 21, 10, 0, 0, tzinfo=timezone.utc)
    sequence = [
        _make_event("OrderCreatedEvent", "agg-1", 1, base_ts.isoformat()),
        _make_event("PaymentCompletedEvent", "agg-1", 2, (base_ts + timedelta(minutes=5)).isoformat()),
        _make_event("InventoryReservedEvent", "agg-1", 3, (base_ts + timedelta(minutes=10)).isoformat()),
    ]
    anomalies = detector.detect(sequence, now=base_ts + timedelta(minutes=15))
    assert len(anomalies) == 0
