"""流程级规则检测：非法迁移 / 状态停滞 / 死循环"""

import logging
import uuid
from datetime import datetime, timedelta, timezone
from typing import Optional

from app.model.anomaly import Anomaly

logger = logging.getLogger(__name__)

# 合法状态迁移表：当前状态 → 允许的下一事件类型集合
LEGAL_TRANSITIONS = {
    "INIT": {"OrderCreatedEvent"},
    "PENDING_PAYMENT": {"PaymentCompletedEvent", "PaymentFailedEvent", "PaymentRequestedEvent", "OrderCancelledEvent"},
    "PAYMENT_FAILED": {"PaymentRetriedEvent", "OrderCancelledEvent"},
    "PAID": {"InventoryReservedEvent", "InventoryReservationFailedEvent", "OrderConfirmedEvent", "OrderRefundRequestedEvent", "OrderCancelledEvent"},
    "CONFIRMED": {"ShippedEvent", "OrderCancelledEvent"},
    "SHIPPED": {"DeliveredEvent"},
    "DELIVERED": {"OrderClosedEvent"},
    "CLOSED": set(),
    "CANCELLED": set(),
    "REFUNDED": {"OrderClosedEvent"},
}

# 事件 → 事件后状态
EVENT_TO_STATE = {
    "OrderCreatedEvent": "PENDING_PAYMENT",
    "PaymentRequestedEvent": "PENDING_PAYMENT",
    "PaymentCompletedEvent": "PAID",
    "PaymentFailedEvent": "PAYMENT_FAILED",
    "PaymentRetriedEvent": "PENDING_PAYMENT",
    "InventoryReservedEvent": "PAID",
    "InventoryReservationFailedEvent": "PAID",
    "OrderConfirmedEvent": "CONFIRMED",
    "ShippedEvent": "SHIPPED",
    "DeliveredEvent": "DELIVERED",
    "OrderClosedEvent": "CLOSED",
    "OrderCancelledEvent": "CANCELLED",
    "OrderRefundRequestedEvent": "PAID",
    "OrderRefundedEvent": "REFUNDED",
}

# 停滞检测的超时时间
STAGNATION_TIMEOUT = timedelta(hours=24)
# 死循环检测的阈值
DEAD_LOOP_THRESHOLD = 5
# 状态保留事件：补偿/意图类事件不改订单状态，不参与 P001 迁移校验
STATE_PRESERVING_EVENTS = {"CompensationExecutedEvent", "PaymentRequestedEvent", "InventoryReservationFailedEvent", "OrderRefundRequestedEvent"}


class ProcessLevelRuleDetector:
    """MVP 流程级检测：基于规则，无需训练

    ponytail: 每个新事件都重跑整窗、无去重，同一 P001/P002/P003 会随窗口推进被重复 save+publish；
    窗口假设 Kafka 按 event_version 顺序投递（乱序会误判迁移）。升级路径=状态快照/幂等去重。
    """

    def detect(self, event_sequence: list[dict], now: Optional[datetime] = None) -> list[Anomaly]:
        """
        检测事件序列中的流程异常。

        Args:
            event_sequence: 按 event_version 排序的事件列表
            now: 当前时间（用于停滞检测，默认用系统时间）

        Returns:
            检出的异常列表
        """
        if not event_sequence:
            return []

        if now is None:
            now = datetime.now(timezone.utc)

        anomalies: list[Anomaly] = []
        anomalies.extend(self._check_illegal_transition(event_sequence))
        anomalies.extend(self._check_stagnation(event_sequence, now))
        anomalies.extend(self._check_dead_loop(event_sequence))
        return anomalies

    def _check_illegal_transition(self, sequence: list[dict]) -> list[Anomaly]:
        """P001：状态机非法迁移检测"""
        anomalies = []
        if not sequence:
            return anomalies
        # 滑动窗口不一定含 OrderCreatedEvent；以首事件结果状态为初值，从第二个事件起校验，避免对首事件误报
        # ponytail: 窗口边界之前的非法迁移不可见(窗口外)，属已知上限
        current_state = EVENT_TO_STATE.get(sequence[0].get("event_type", ""), "INIT")
        for event in sequence[1:]:
            event_type = event.get("event_type", "")
            # 状态保留事件（补偿/意图等不改状态）不参与迁移校验，避免误报
            if event_type in STATE_PRESERVING_EVENTS:
                continue
            legal_next = LEGAL_TRANSITIONS.get(current_state, set())
            if event_type not in legal_next:
                anomalies.append(self._build_anomaly(
                    rule_id="P001_ILLEGAL_TRANSITION",
                    event=event,
                    level="ERROR",
                    description=f"非法状态迁移：{current_state} → {event_type}",
                ))
            # 更新状态
            new_state = EVENT_TO_STATE.get(event_type)
            if new_state:
                current_state = new_state

        return anomalies

    def _check_stagnation(self, sequence: list[dict], now: datetime) -> list[Anomaly]:
        """P002：状态停滞检测（PAID 后 24h 无后续）"""
        anomalies = []
        if not sequence:
            return anomalies

        last_event = sequence[-1]
        last_event_type = last_event.get("event_type", "")
        last_state = EVENT_TO_STATE.get(last_event_type, "")

        # 只检测非终态
        if last_state in ("CLOSED", "CANCELLED", "", None):
            return anomalies

        last_ts = self._parse_time(last_event.get("created_at"))
        if last_ts is None:
            return anomalies

        # 确保 timezone-aware
        if last_ts.tzinfo is None:
            last_ts = last_ts.replace(tzinfo=timezone.utc)
        if now.tzinfo is None:
            now = now.replace(tzinfo=timezone.utc)

        if (now - last_ts) > STAGNATION_TIMEOUT:
            anomalies.append(self._build_anomaly(
                rule_id="P002_STUCK",
                event=last_event,
                level="WARN",
                description=f"状态 {last_state} 停滞超过 24h（最后事件：{last_event_type}）",
            ))

        return anomalies

    def _check_dead_loop(self, sequence: list[dict]) -> list[Anomaly]:
        """P003：死循环检测（PaymentFailed→Retried 重复 >5 次）"""
        anomalies = []
        fail_retry_count = 0

        for event in sequence:
            event_type = event.get("event_type", "")
            if event_type == "PaymentRetriedEvent":
                fail_retry_count += 1

        if fail_retry_count > DEAD_LOOP_THRESHOLD:
            anomalies.append(self._build_anomaly(
                rule_id="P003_DEAD_LOOP",
                event=sequence[-1],
                level="ERROR",
                description=f"支付重试死循环：PaymentRetried 重复 {fail_retry_count} 次（阈值 {DEAD_LOOP_THRESHOLD}）",
            ))

        return anomalies

    def _build_anomaly(self, rule_id: str, event: dict, level: str, description: str) -> Anomaly:
        return Anomaly(
            anomaly_id=str(uuid.uuid4()),
            rule_id=rule_id,
            aggregate_id=event.get("aggregate_id", str(uuid.uuid4())),
            event_type=event.get("event_type", "Unknown"),
            level=level,
            source="PROCESS",
            priority="HIGH",
            detected_at=datetime.now(timezone.utc).isoformat(),
            description=description,
            details={},
        )

    def _parse_time(self, ts_str: Optional[str]) -> Optional[datetime]:
        if ts_str is None:
            return None
        try:
            return datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
        except (ValueError, TypeError):
            return None
