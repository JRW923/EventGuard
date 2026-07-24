"""事件存储客户端：通过 HTTP 调用 Java 后端加载聚合根事件历史"""

import logging
from typing import Optional

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class EventStoreClient:
    """调用 GET /orders/{id}/events 加载事件序列"""

    def __init__(self, base_url: Optional[str] = None):
        self.base_url = base_url or settings.server_base_url

    def load_events(self, aggregate_id: str) -> list[dict]:
        """加载指定聚合根的事件序列"""
        url = f"{self.base_url}/orders/{aggregate_id}/events"
        try:
            with httpx.Client(timeout=5.0) as client:
                resp = client.get(url)
                resp.raise_for_status()
                return resp.json()
        except httpx.HTTPError as e:
            logger.warning("加载事件历史失败 agg_id=%s: %s", aggregate_id, e)
            return []
