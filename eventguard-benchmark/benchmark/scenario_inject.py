"""确定性合成事件构建器（kafka_inject 通道）：对齐 training/generate_data.py 与规则语义。

这些事件无法经快乐路径 REST 产生（聚合状态机拦截 / 时间戳限制），评测器以
「DB 追加 + 直发 Kafka domain-events」注入，并在报告中每条断言标注 method=kafka_inject。
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone


def _iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def _base(order_id: str, event_type: str, version: int, payload: dict, created_at: str, user: str) -> dict:
    return {
        "event_id": str(uuid.uuid4()),
        "aggregate_id": order_id,
        "aggregate_type": "Order",
        "event_type": event_type,
        "event_version": version,
        "payload": payload,
        "metadata": {"userId": user},
        "created_at": created_at,
    }


def now_iso() -> str:
    return _iso(datetime.now(timezone.utc))


def duplicate_payment(order_id: str, version: int, user: str, created_at: str | None = None) -> dict:
    """R002：已 PAID 订单 5 分钟内重复 PaymentCompleted（需 DB 已有首笔支付）。"""
    return _base(order_id, "PaymentCompletedEvent", version,
                 {"orderId": order_id, "amount": 100.0}, created_at or now_iso(), user)


def state_jump_ship(order_id: str, version: int, user: str, created_at: str | None = None) -> dict:
    """R003：未确认订单直接 Shipped（前序状态 PENDING_PAYMENT ∉ {CONFIRMED}）。"""
    return _base(order_id, "ShippedEvent", version,
                 {"orderId": order_id, "trackingNo": "SF-BENCH-INJ"}, created_at or now_iso(), user)


def stale_paid(order_id: str, version: int, user: str, hours_old: float = 48.0) -> dict:
    """P002：PAID 后停滞超 24h（created_at 回拨）。"""
    ts = datetime.now(timezone.utc) - timedelta(hours=hours_old)
    return _base(order_id, "PaymentCompletedEvent", version,
                 {"orderId": order_id, "amount": 99.0}, _iso(ts), user)


def dead_loop_retries(order_id: str, start_version: int, user: str, count: int = 7) -> list[dict]:
    """P003：支付死循环 >5 次 PaymentRetried。"""
    ts = datetime.now(timezone.utc) - timedelta(minutes=10)
    events = []
    for i in range(count):
        events.append(_base(order_id, "PaymentRetriedEvent", start_version + i,
                            {"orderId": order_id, "retryCount": i + 1}, _iso(ts + timedelta(minutes=i)), user))
    return events
