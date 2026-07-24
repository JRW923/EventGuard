"""后端 REST 客户端：AI 服务通过 HTTP 调后端，不直连 DB。"""
import logging
from typing import Any, Optional

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class BackendClient:
    """调用 Spring Boot 后端 REST 接口。"""

    def __init__(self, base_url: Optional[str] = None):
        # 优先用显式传入；其次 backend_base_url（可由环境变量注入）；最后回退到已存在的 server_base_url。
        # config 当前只有 server_base_url，这里链式兜底避免依赖不存在的属性。
        self.base_url = (
            base_url
            or getattr(settings, "backend_base_url", None)
            or getattr(settings, "server_base_url", "http://eventguard-server:8080")
        )

    async def get_order(self, order_id: str) -> dict:
        """GET /orders/{id} — 查询订单基本信息。"""
        url = f"{self.base_url}/orders/{order_id}"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            return resp.json()

    async def get_stats(self, status: Optional[str], from_: Optional[str], to: Optional[str]) -> list:
        """GET /orders/stats?status=&from=&to= — 统计聚合。"""
        params = {}
        if status:
            params["status"] = status
        if from_:
            params["from"] = from_
        if to:
            params["to"] = to
        url = f"{self.base_url}/orders/stats"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            return resp.json()

    async def get_events(self, order_id: str) -> list:
        """GET /orders/{id}/events — 事件回放。"""
        url = f"{self.base_url}/orders/{order_id}/events"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            return resp.json()
