import json
from pathlib import Path

from training.generate_data import (
    generate_normal_event,
    inject_amount_deviation,
    inject_state_stagnation,
    inject_payment_dead_loop,
    NORMAL_FLOW,
)


def test_generate_normal_event_has_required_fields():
    """正常事件含必填字段"""
    event = generate_normal_event(
        aggregate_id="agg-001",
        version=1,
        event_type="OrderCreatedEvent",
        user_id="user-1",
        amount=99.00,
        timestamp="2026-07-21T10:00:00Z",
    )
    assert event["event_id"] is not None
    assert event["aggregate_id"] == "agg-001"
    assert event["event_type"] == "OrderCreatedEvent"
    assert event["event_version"] == 1
    assert event["payload"]["totalAmount"] == 99.00
    assert event["payload"]["userId"] == "user-1"
    assert event["is_anomaly"] is False
    assert event["anomaly_type"] is None


def test_normal_flow_is_valid_sequence():
    """正常事件流遵循 CREATED→PAID→CONFIRMED→SHIPPED→DELIVERED→CLOSED"""
    assert NORMAL_FLOW == [
        "OrderCreatedEvent",
        "PaymentCompletedEvent",
        "InventoryReservedEvent",
        "OrderConfirmedEvent",
        "ShippedEvent",
        "DeliveredEvent",
        "OrderClosedEvent",
    ]


def test_inject_amount_deviation_marks_anomaly():
    """金额偏离注入：amount 偏离用户历史均值 5σ 以上，标注 is_anomaly=True"""
    normal_mean = 100.00
    event = inject_amount_deviation(
        aggregate_id="agg-002",
        user_id="user-2",
        normal_mean=normal_mean,
        timestamp="2026-07-21T11:00:00Z",
    )
    assert event["is_anomaly"] is True
    assert event["anomaly_type"] == "AMOUNT_DEVIATION"
    assert event["event_type"] == "OrderCreatedEvent"
    # 偏离 5σ 以上：deviation = mean + 5*(0.1*mean) = 1.5*mean（恰好等于，用 >=）
    assert event["payload"]["totalAmount"] >= normal_mean * 1.5


def test_inject_state_stagnation_marks_anomaly():
    """状态停滞注入：PAID 后 24h+ 无后续事件"""
    event = inject_state_stagnation(
        aggregate_id="agg-003",
        user_id="user-3",
        timestamp="2026-07-21T12:00:00Z",
    )
    assert event["is_anomaly"] is True
    assert event["anomaly_type"] == "STATE_STAGNATION"
    assert event["event_type"] == "PaymentCompletedEvent"


def test_inject_payment_dead_loop_marks_anomaly():
    """死循环注入：PaymentFailed→Retried 重复 >5 次"""
    events = inject_payment_dead_loop(
        aggregate_id="agg-004",
        user_id="user-4",
        timestamp="2026-07-21T13:00:00Z",
    )
    assert len(events) >= 10  # 至少 5 轮 Failed+Retried
    assert all(e["is_anomaly"] for e in events)
    assert all(e["anomaly_type"] == "PAYMENT_DEAD_LOOP" for e in events)


def test_generate_dataset_outputs_jsonl(tmp_path):
    """完整数据集生成：输出 JSONL，每行可解析"""
    from training.generate_data import generate_dataset

    normal_path = tmp_path / "normal.jsonl"
    anomaly_path = tmp_path / "anomaly.jsonl"

    generate_dataset(
        normal_count=1000,  # 测试用小规模
        output_normal=str(normal_path),
        output_anomaly=str(anomaly_path),
        seed=42,
    )

    normal_lines = normal_path.read_text().strip().split("\n")
    # generate_dataset 将 normal_count 视为订单数，每单 7 个事件：实际产出 (1000//7)*7=994
    assert len(normal_lines) == (1000 // len(NORMAL_FLOW)) * len(NORMAL_FLOW)
    for line in normal_lines:
        event = json.loads(line)
        assert event["is_anomaly"] is False

    anomaly_lines = anomaly_path.read_text().strip().split("\n")
    assert len(anomaly_lines) > 0
    for line in anomaly_lines:
        event = json.loads(line)
        assert event["is_anomaly"] is True
        assert event["anomaly_type"] in [
            "AMOUNT_DEVIATION",
            "STATE_STAGNATION",
            "PAYMENT_DEAD_LOOP",
        ]
