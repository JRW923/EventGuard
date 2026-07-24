"""模板查询执行器：3 类意图对应的模板。"""
import logging
import re
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

from app.query.backend_client import BackendClient

logger = logging.getLogger(__name__)


class TemplateExecutor:
    """3 类模板查询执行器。

    每个模板从问题中提取参数（order_id / status / 时间窗），调 BackendClient 获取数据。
    """

    # 状态关键词映射
    STATUS_KEYWORDS = {
        "PENDING_PAYMENT": ("待支付", "PENDING_PAYMENT", "待付款"),
        "PAID": ("已支付", "PAID", "支付完成"),
        "CONFIRMED": ("已确认", "CONFIRMED"),
        "SHIPPED": ("已发货", "SHIPPED"),
        "DELIVERED": ("已送达", "DELIVERED"),
        "CLOSED": ("已关闭", "CLOSED"),
        "CANCELLED": ("已取消", "CANCELLED"),
        "PAYMENT_FAILED": ("支付失败", "PAYMENT_FAILED"),
        "REFUNDED": ("已退款", "REFUNDED"),
    }

    # 时间关键词映射（相对今天）
    TIME_KEYWORDS = {
        "今天": 0,
        "今日": 0,
        "昨天": -1,
        "昨日": -1,
        "前天": -2,
        "本周": -7,
        "过去7天": -7,
        "过去 7 天": -7,
        "近一周": -7,
    }

    UUID_PATTERN = re.compile(
        r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )

    def __init__(self, backend_client: Optional[BackendClient] = None):
        self.backend_client = backend_client or BackendClient()

    def execute_event_lookup(self, question: str) -> dict:
        """event_lookup 模板：提取 order_id → GET /orders/{id}。"""
        order_id = self._extract_order_id(question)
        return self.backend_client.get_order(order_id)

    def execute_stats_aggregation(self, question: str) -> list:
        """stats_aggregation 模板：提取 status + 时间窗 → GET /orders/stats。"""
        status = self._extract_status(question)
        from_, to = self._extract_time_window(question)
        return self.backend_client.get_stats(status, from_, to)

    def execute_trace_replay(self, question: str) -> list:
        """trace_replay 模板：提取 order_id → GET /orders/{id}/events。"""
        order_id = self._extract_order_id(question)
        return self.backend_client.get_events(order_id)

    def _extract_order_id(self, question: str) -> str:
        """从问题中提取 UUID 格式的 order_id。"""
        match = self.UUID_PATTERN.search(question)
        if match:
            return match.group(0)
        # 兜底：尝试校验是否为合法 UUID（无连字符也尝试）
        tokens = question.replace("#", " ").split()
        for token in tokens:
            try:
                return str(uuid.UUID(token))
            except ValueError:
                continue
        raise ValueError(f"无法从问题中提取 order_id：{question}")

    def _extract_status(self, question: str) -> Optional[str]:
        """从问题中提取订单状态关键词。"""
        q = question.upper()
        for status, keywords in self.STATUS_KEYWORDS.items():
            for kw in keywords:
                if kw.upper() in q:
                    return status
        return None

    def _extract_time_window(self, question: str) -> tuple:
        """从问题中提取时间窗，返回 (from_iso, to_iso)。"""
        now = datetime.now(timezone.utc)
        for kw, delta_days in self.TIME_KEYWORDS.items():
            if kw in question:
                start = now + timedelta(days=delta_days)
                start = start.replace(hour=0, minute=0, second=0, microsecond=0)
                return start.isoformat(), now.isoformat()
        # 默认：最近 7 天
        start = now + timedelta(days=-7)
        start = start.replace(hour=0, minute=0, second=0, microsecond=0)
        return start.isoformat(), now.isoformat()
