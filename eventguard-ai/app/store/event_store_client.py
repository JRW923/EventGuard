"""事件存储客户端：通过 HTTP 调用 Java 后端加载聚合根事件历史"""

import logging
import inspect
from typing import Optional

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


async def load_events_async(client, aggregate_id: str) -> list[dict]:
    """兼容注入的同步测试 double；真实 EventStoreClient 优先走 AsyncClient。"""
    loader = getattr(client, "load_events_async", None)
    if loader is not None:
        result = loader(aggregate_id)
        if inspect.isawaitable(result):
            return await result
    return client.load_events(aggregate_id)


class EventStoreClient:
    """调用 GET /orders/{id}/events 加载事件序列"""

    def __init__(self, base_url: Optional[str] = None):
        self.base_url = base_url or settings.server_base_url
        # ponytail: 服务端 AuthFilter 对 REST 强校验；AI 用机器密钥（EG_MACHINE_API_KEY）以受限权限调用后端读接口
        self.headers = {"X-API-Key": settings.machine_api_key}

    def load_events(self, aggregate_id: str) -> list[dict]:
        """加载指定聚合根的事件序列，并规范化为内部 snake_case 形状（与 Kafka 事件一致）。

        后端 GET /orders/{id}/events 返回 camelCase（eventType/createdAt/version），
        而检测管道 / 根因 / 故事 / 预测统一用 snake_case（event_type/created_at/event_version）。
        这里做一次映射，保证两类事件源在 AI 服务内形状一致。
        """
        url = f"{self.base_url}/orders/{aggregate_id}/events"
        try:
            with httpx.Client(timeout=5.0) as client:
                resp = client.get(url, headers=self.headers)
                resp.raise_for_status()
                data = resp.json()
                events = data if isinstance(data, list) else data.get("events", [])
                return [self._normalize(e) for e in events if isinstance(e, dict)]
        except httpx.HTTPError as e:
            logger.warning("加载事件历史失败 agg_id=%s: %s", aggregate_id, e)
            return []  # ponytail: 事件加载失败静默降级为 [],分析将在无事件上下文下进行;升级路径=返回 None 由上层决定降级策略

    async def load_events_async(self, aggregate_id: str) -> list[dict]:
        """异步调用版本，避免 FastAPI 事件循环被同步 HTTP 阻塞。"""
        url = f"{self.base_url}/orders/{aggregate_id}/events"
        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                resp = await client.get(url, headers=self.headers)
                resp.raise_for_status()
                data = resp.json()
                events = data if isinstance(data, list) else data.get("events", [])
                return [self._normalize(e) for e in events if isinstance(e, dict)]
        except httpx.HTTPError as e:
            logger.warning("加载事件历史失败 agg_id=%s: %s", aggregate_id, e)
            return []

    @staticmethod
    def _normalize(event: dict) -> dict:
        """camelCase REST 事件 → 内部 snake_case（其余字段原样保留）。"""
        mapping = {
            "eventId": "event_id",
            "eventType": "event_type",
            "aggregateId": "aggregate_id",
            "version": "event_version",
            "createdAt": "created_at",
        }
        return {mapping.get(k, k): v for k, v in event.items()}
