"""独立状态机重放：用与 OrderAggregate.apply 一致的状态映射，从 domain_events 重放订单状态。

用于 s01 的"回放一致性"断言——同一事件流，独立实现重放，结果应与读模型 order_view 一致。
"""
from __future__ import annotations

# 状态保留事件：不改变订单状态（与 OrderAggregate 的 no-op 分支一致）
_STATE_PRESERVING = {
    "PaymentRequestedEvent",
    "InventoryReservedEvent",
    "InventoryReservationFailedEvent",
    "CompensationExecutedEvent",
    "OrderRefundRequestedEvent",
}

# 事件类型 → 目标状态
_TRANSITIONS = {
    "OrderCreatedEvent": "PENDING_PAYMENT",
    "PaymentCompletedEvent": "PAID",
    "PaymentFailedEvent": "PAYMENT_FAILED",
    "PaymentRetriedEvent": "PENDING_PAYMENT",
    "OrderConfirmedEvent": "CONFIRMED",
    "ShippedEvent": "SHIPPED",
    "DeliveredEvent": "DELIVERED",
    "OrderClosedEvent": "CLOSED",
    "OrderCancelledEvent": "CANCELLED",
    "OrderRefundedEvent": "REFUNDED",
}


def replay_status(events: list[dict]) -> str | None:
    """按 event_version 升序重放事件，返回最终状态；空事件流返回 None。"""
    status: str | None = None
    ordered = sorted(events, key=lambda e: int(e.get("event_version") or 0))
    for ev in ordered:
        etype = ev.get("event_type", "")
        if etype in _STATE_PRESERVING:
            continue
        if etype in _TRANSITIONS:
            status = _TRANSITIONS[etype]
    return status


def replay_version(events: list[dict]) -> int:
    """事件流最大版本号（读模型 order_view.version 应对应已投影的最大版本）。"""
    ordered = sorted(events, key=lambda e: int(e.get("event_version") or 0))
    return int(ordered[-1].get("event_version") or 0) if ordered else 0
