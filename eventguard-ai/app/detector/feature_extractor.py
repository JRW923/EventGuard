"""Isolation Forest 特征工程：4 维特征提取

特征：
1. amount_zscore — 金额相对用户历史均值的 Z 分数
2. time_since_last_event — 同订单距上一事件的间隔（秒）
3. user_order_count_1h — 用户 1h 内订单数
4. state_transition_prob — 该状态转移在历史中的概率
"""

import math
from collections import defaultdict
from datetime import datetime, timedelta
from typing import Optional


class FeatureExtractor:
    """从事件 dict 提取 4 维特征向量"""

    # 正常状态转移频次（从正常事件流统计）
    NORMAL_TRANSITIONS = {
        ("INIT", "OrderCreatedEvent"): 1.0,
        ("PENDING_PAYMENT", "PaymentCompletedEvent"): 1.0,
        ("PENDING_PAYMENT", "PaymentFailedEvent"): 0.1,
        ("PAYMENT_FAILED", "PaymentRetriedEvent"): 0.1,
        ("PAYMENT_FAILED", "PaymentCompletedEvent"): 0.1,
        ("PAID", "InventoryReservedEvent"): 1.0,
        ("PAID", "OrderConfirmedEvent"): 1.0,
        ("CONFIRMED", "ShippedEvent"): 1.0,
        ("SHIPPED", "DeliveredEvent"): 1.0,
        ("DELIVERED", "OrderClosedEvent"): 1.0,
    }

    # 事件类型 → 事件后的状态
    EVENT_TO_STATE = {
        "OrderCreatedEvent": "PENDING_PAYMENT",
        "PaymentCompletedEvent": "PAID",
        "PaymentFailedEvent": "PAYMENT_FAILED",
        "PaymentRetriedEvent": "PENDING_PAYMENT",
        "InventoryReservedEvent": "PAID",
        "OrderConfirmedEvent": "CONFIRMED",
        "ShippedEvent": "SHIPPED",
        "DeliveredEvent": "DELIVERED",
        "OrderClosedEvent": "CLOSED",
        "OrderCancelledEvent": "CANCELLED",
    }

    def __init__(self):
        # ponytail: 用户金额/下单时间列表只追加不淘汰，长进程内存只增不减；
        # 状态为进程内内存、重启即丢（非持久化）。
        # 升级路径：滑动窗口 / LRU 淘汰，或落 Redis / DB 持久化。
        # 用户历史金额统计
        self._user_amounts: dict[str, list[float]] = defaultdict(list)
        # 用户最近下单时间
        self._user_order_times: dict[str, list[datetime]] = defaultdict(list)
        # 聚合根最近事件时间
        self._agg_last_time: dict[str, datetime] = {}
        # 聚合根当前状态
        self._agg_state: dict[str, str] = {}

    def extract(self, event: dict) -> list[float]:
        """提取 4 维特征"""
        return [
            self._amount_zscore(event),
            self._time_since_last_event(event),
            self._user_order_count_1h(event),
            self._state_transition_prob(event),
        ]

    def _amount_zscore(self, event: dict) -> float:
        """金额 Z 分数"""
        amount = self._get_amount(event)
        user_id = self._get_user_id(event)
        if amount is None or user_id is None:
            return 0.0

        history = self._user_amounts.get(user_id, [])
        if len(history) < 2:
            return 0.0

        mean = sum(history) / len(history)
        variance = sum((x - mean) ** 2 for x in history) / len(history)
        std = math.sqrt(variance) if variance > 0 else 1.0
        return (amount - mean) / std

    def _time_since_last_event(self, event: dict) -> float:
        """距上一事件的秒数"""
        agg_id = event.get("aggregate_id", "")
        ts = self._parse_time(event.get("created_at"))
        if ts is None:
            return 0.0

        last = self._agg_last_time.get(agg_id)
        if last is None:
            return 0.0

        delta = (ts - last).total_seconds()
        return max(0.0, delta)

    def _user_order_count_1h(self, event: dict) -> float:
        """用户 1h 内订单数"""
        user_id = self._get_user_id(event)
        ts = self._parse_time(event.get("created_at"))
        if user_id is None or ts is None:
            return 0.0

        times = self._user_order_times.get(user_id, [])
        cutoff = ts - timedelta(hours=1)
        count = sum(1 for t in times if t >= cutoff)
        return float(count)

    def _state_transition_prob(self, event: dict) -> float:
        """状态转移概率（基于正常转移表）"""
        agg_id = event.get("aggregate_id", "")
        event_type = event.get("event_type", "")
        prev_state = self._agg_state.get(agg_id, "INIT")
        prob = self.NORMAL_TRANSITIONS.get((prev_state, event_type), 0.01)
        return prob

    def update(self, event: dict) -> None:
        """用新事件更新内部统计状态（训练时调用）"""
        amount = self._get_amount(event)
        user_id = self._get_user_id(event)
        agg_id = event.get("aggregate_id", "")
        event_type = event.get("event_type", "")
        ts = self._parse_time(event.get("created_at"))

        if amount is not None and user_id is not None:
            self._user_amounts[user_id].append(amount)
        if user_id is not None and ts is not None and event_type == "OrderCreatedEvent":
            self._user_order_times[user_id].append(ts)
        if ts is not None:
            self._agg_last_time[agg_id] = ts
        if event_type in self.EVENT_TO_STATE:
            self._agg_state[agg_id] = self.EVENT_TO_STATE[event_type]

    def _get_amount(self, event: dict) -> Optional[float]:
        payload = event.get("payload", {})
        for key in ("totalAmount", "amount"):
            if key in payload:
                try:
                    return float(payload[key])
                except (TypeError, ValueError):
                    return None
        return None

    def _get_user_id(self, event: dict) -> Optional[str]:
        metadata = event.get("metadata", {})
        return metadata.get("userId") or event.get("payload", {}).get("userId")

    def _parse_time(self, ts_str: Optional[str]) -> Optional[datetime]:
        if ts_str is None:
            return None
        try:
            # 兼容 ISO 8601 with Z
            return datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
        except (ValueError, TypeError):
            return None
