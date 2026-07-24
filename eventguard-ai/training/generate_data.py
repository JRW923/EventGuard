"""合成数据生成：10 万正常 + 注入异常（金额偏离/状态停滞/支付死循环）

输出 JSONL 格式，每行一个事件 JSON：
{
  "event_id": "uuid",
  "aggregate_id": "uuid",
  "aggregate_type": "Order",
  "event_type": "OrderCreatedEvent",
  "event_version": 1,
  "payload": {...},
  "metadata": {"userId": "user-1", "traceId": "..."},
  "created_at": "2026-07-21T10:00:00Z",
  "is_anomaly": false,
  "anomaly_type": null
}
"""

import json
import random
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

# 正常订单事件流
NORMAL_FLOW = [
    "OrderCreatedEvent",
    "PaymentCompletedEvent",
    "InventoryReservedEvent",
    "OrderConfirmedEvent",
    "ShippedEvent",
    "DeliveredEvent",
    "OrderClosedEvent",
]

# 用户池
USER_POOL = [f"user-{i}" for i in range(1, 201)]


def _iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def generate_normal_event(
    aggregate_id: str,
    version: int,
    event_type: str,
    user_id: str,
    amount: float,
    timestamp: str,
) -> dict:
    """生成一个正常事件"""
    payload = {"orderId": aggregate_id, "userId": user_id}
    if event_type == "OrderCreatedEvent":
        payload["totalAmount"] = round(amount, 2)
    elif event_type == "PaymentCompletedEvent":
        payload["amount"] = round(amount, 2)
    elif event_type == "InventoryReservedEvent":
        payload["reservedQty"] = random.randint(1, 10)
        payload["skuId"] = f"sku-{random.randint(1, 50)}"
    elif event_type == "ShippedEvent":
        payload["carrier"] = "SF-Express"
    elif event_type == "OrderCancelledEvent":
        payload["reason"] = "user_cancel"

    return {
        "event_id": str(uuid.uuid4()),
        "aggregate_id": aggregate_id,
        "aggregate_type": "Order",
        "event_type": event_type,
        "event_version": version,
        "payload": payload,
        "metadata": {"userId": user_id, "traceId": str(uuid.uuid4())},
        "created_at": timestamp,
        "is_anomaly": False,
        "anomaly_type": None,
    }


def generate_normal_order(
    aggregate_id: str,
    user_id: str,
    start_time: datetime,
    base_amount: float = 100.00,
) -> list[dict]:
    """生成一笔正常订单的完整事件流（7 个事件）"""
    events = []
    ts = start_time
    amount = base_amount + random.gauss(0, 20)
    amount = max(10.0, amount)

    for i, event_type in enumerate(NORMAL_FLOW):
        event = generate_normal_event(
            aggregate_id=aggregate_id,
            version=i + 1,
            event_type=event_type,
            user_id=user_id,
            amount=amount,
            timestamp=_iso(ts),
        )
        events.append(event)
        ts += timedelta(minutes=random.randint(5, 60))

    return events


def inject_amount_deviation(
    aggregate_id: str,
    user_id: str,
    normal_mean: float,
    timestamp: str,
) -> dict:
    """注入金额偏离异常：amount 偏离用户历史均值 5σ 以上"""
    deviation_amount = normal_mean + 5 * (normal_mean * 0.1)  # 5σ 以上
    return {
        "event_id": str(uuid.uuid4()),
        "aggregate_id": aggregate_id,
        "aggregate_type": "Order",
        "event_type": "OrderCreatedEvent",
        "event_version": 1,
        "payload": {
            "orderId": aggregate_id,
            "userId": user_id,
            "totalAmount": round(deviation_amount, 2),
        },
        "metadata": {"userId": user_id, "traceId": str(uuid.uuid4())},
        "created_at": timestamp,
        "is_anomaly": True,
        "anomaly_type": "AMOUNT_DEVIATION",
    }


def inject_state_stagnation(
    aggregate_id: str,
    user_id: str,
    timestamp: str,
) -> dict:
    """注入状态停滞异常：PAID 后无后续事件（停滞 24h+）"""
    return {
        "event_id": str(uuid.uuid4()),
        "aggregate_id": aggregate_id,
        "aggregate_type": "Order",
        "event_type": "PaymentCompletedEvent",
        "event_version": 2,
        "payload": {"orderId": aggregate_id, "amount": 99.00},
        "metadata": {"userId": user_id, "traceId": str(uuid.uuid4())},
        "created_at": timestamp,
        "is_anomaly": True,
        "anomaly_type": "STATE_STAGNATION",
    }


def inject_payment_dead_loop(
    aggregate_id: str,
    user_id: str,
    timestamp: str,
) -> list[dict]:
    """注入支付死循环：PaymentFailed→Retried 重复 6 次"""
    events = []
    ts = datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%SZ")
    version = 2  # 从 version 2 开始（version 1 是 OrderCreated）

    for i in range(6):
        events.append({
            "event_id": str(uuid.uuid4()),
            "aggregate_id": aggregate_id,
            "aggregate_type": "Order",
            "event_type": "PaymentFailedEvent",
            "event_version": version,
            "payload": {"orderId": aggregate_id, "reason": "timeout"},
            "metadata": {"userId": user_id, "traceId": str(uuid.uuid4())},
            "created_at": _iso(ts),
            "is_anomaly": True,
            "anomaly_type": "PAYMENT_DEAD_LOOP",
        })
        version += 1
        ts += timedelta(minutes=2)

        events.append({
            "event_id": str(uuid.uuid4()),
            "aggregate_id": aggregate_id,
            "aggregate_type": "Order",
            "event_type": "PaymentRetriedEvent",
            "event_version": version,
            "payload": {"orderId": aggregate_id, "attempt": i + 1},
            "metadata": {"userId": user_id, "traceId": str(uuid.uuid4())},
            "created_at": _iso(ts),
            "is_anomaly": True,
            "anomaly_type": "PAYMENT_DEAD_LOOP",
        })
        version += 1
        ts += timedelta(minutes=1)

    return events


def generate_dataset(
    normal_count: int = 100000,
    output_normal: str = "data/normal_events.jsonl",
    output_anomaly: str = "data/anomaly_events.jsonl",
    seed: int = 42,
) -> None:
    """生成完整数据集：normal_count 条正常事件 + 注入异常"""
    random.seed(seed)
    base_time = datetime(2026, 7, 1, 0, 0, 0, tzinfo=timezone.utc)

    # 生成正常事件流
    normal_events = []
    orders_to_generate = normal_count // len(NORMAL_FLOW)  # 每笔订单 7 个事件
    for i in range(orders_to_generate):
        agg_id = str(uuid.uuid4())
        user_id = random.choice(USER_POOL)
        amount = random.gauss(100, 20)
        amount = max(10.0, amount)
        start = base_time + timedelta(minutes=i * 10)
        normal_events.extend(generate_normal_order(agg_id, user_id, start, amount))

    Path(output_normal).parent.mkdir(parents=True, exist_ok=True)
    with open(output_normal, "w", encoding="utf-8") as f:
        for event in normal_events:
            f.write(json.dumps(event, ensure_ascii=False) + "\n")

    # 注入异常
    anomaly_events = []

    # 5% 金额偏离
    amount_dev_count = int(orders_to_generate * 0.05)
    for i in range(amount_dev_count):
        ts = base_time + timedelta(hours=i)
        anomaly_events.append(inject_amount_deviation(
            aggregate_id=str(uuid.uuid4()),
            user_id=random.choice(USER_POOL),
            normal_mean=100.0,
            timestamp=_iso(ts),
        ))

    # 3% 状态停滞
    stagnation_count = int(orders_to_generate * 0.03)
    for i in range(stagnation_count):
        ts = base_time + timedelta(hours=i)
        anomaly_events.append(inject_state_stagnation(
            aggregate_id=str(uuid.uuid4()),
            user_id=random.choice(USER_POOL),
            timestamp=_iso(ts),
        ))

    # 2% 支付死循环
    dead_loop_count = int(orders_to_generate * 0.02)
    for i in range(dead_loop_count):
        ts = base_time + timedelta(hours=i)
        anomaly_events.extend(inject_payment_dead_loop(
            aggregate_id=str(uuid.uuid4()),
            user_id=random.choice(USER_POOL),
            timestamp=_iso(ts),
        ))

    Path(output_anomaly).parent.mkdir(parents=True, exist_ok=True)
    with open(output_anomaly, "w", encoding="utf-8") as f:
        for event in anomaly_events:
            f.write(json.dumps(event, ensure_ascii=False) + "\n")

    print(f"生成完成: 正常 {len(normal_events)} 条, 异常 {len(anomaly_events)} 条")


if __name__ == "__main__":
    generate_dataset()
