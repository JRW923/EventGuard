"""TemplateExecutor 单元测试。

mock BackendClient，验证 3 类模板的路由与参数提取。
"""
from unittest.mock import MagicMock

import pytest

from app.query.template_executor import TemplateExecutor


class TestTemplateExecutor:
    """模板查询执行器测试。"""

    def test_event_lookup_extracts_order_id_and_calls_get_order(self):
        """event_lookup 从问题中提取 order_id 并调 BackendClient.get_order。"""
        mock_backend = MagicMock()
        mock_backend.get_order.return_value = {
            "orderId": "11111111-1111-1111-1111-111111111111",
            "status": "PAID",
            "totalAmount": 99.00,
            "version": 2,
        }
        executor = TemplateExecutor(backend_client=mock_backend)

        data = executor.execute_event_lookup("订单 11111111-1111-1111-1111-111111111111 当前状态是什么？")

        mock_backend.get_order.assert_called_once_with("11111111-1111-1111-1111-111111111111")
        assert data["orderId"] == "11111111-1111-1111-1111-111111111111"
        assert data["status"] == "PAID"

    def test_stats_aggregation_extracts_status_and_time_window(self):
        """stats_aggregation 从问题中提取状态关键词与时间窗。"""
        mock_backend = MagicMock()
        mock_backend.get_stats.return_value = [
            {"status": "PAID", "orderCount": 10, "totalAmount": 990.00}
        ]
        executor = TemplateExecutor(backend_client=mock_backend)

        data = executor.execute_stats_aggregation("昨天有多少 PAID 订单？")

        mock_backend.get_stats.assert_called_once()
        call_args = mock_backend.get_stats.call_args
        # 第一个位置参数是 status，应为 "PAID"
        assert call_args.args[0] == "PAID"
        # 应有 from / to 时间参数
        assert call_args.args[1] is not None  # from_
        assert call_args.args[2] is not None  # to
        assert data[0]["orderCount"] == 10

    def test_stats_aggregation_without_status_passes_none(self):
        """stats_aggregation 未匹配状态时 status=None（全状态聚合）。"""
        mock_backend = MagicMock()
        mock_backend.get_stats.return_value = []
        executor = TemplateExecutor(backend_client=mock_backend)

        executor.execute_stats_aggregation("昨天有多少订单？")

        assert mock_backend.get_stats.call_args.args[0] is None

    def test_trace_replay_extracts_order_id_and_calls_get_events(self):
        """trace_replay 从问题中提取 order_id 并调 BackendClient.get_events。"""
        mock_backend = MagicMock()
        mock_backend.get_events.return_value = [
            {"eventType": "OrderCreatedEvent", "version": 1, "createdAt": "2026-07-21T10:00:00Z"},
            {"eventType": "PaymentCompletedEvent", "version": 2, "createdAt": "2026-07-21T10:05:00Z"},
        ]
        executor = TemplateExecutor(backend_client=mock_backend)

        data = executor.execute_trace_replay("订单 22222222-2222-2222-2222-222222222222 经历了哪些状态变更？")

        mock_backend.get_events.assert_called_once_with("22222222-2222-2222-2222-222222222222")
        assert len(data) == 2
        assert data[0]["eventType"] == "OrderCreatedEvent"
