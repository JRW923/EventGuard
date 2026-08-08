"""订单事件故事线（Item 7）：LLM 把事件序列转成运营可读的复盘叙事；LLM 不可用时模板兜底。"""
import json
import logging
from typing import Optional

from app.analyzer.llm_client import LLMClient
from app.store.event_store_client import EventStoreClient

logger = logging.getLogger(__name__)

STORY_USER_PROMPT = """请根据以下订单事件序列，写一段不超过 120 字的运营复盘：
- 发生了什么、订单当前到哪一步
- 如流程异常（停滞/重试/取消/退款），点出可疑环节

事件序列：
{events}
"""


class StoryGenerator:
    """单订单事件链 → 运营可读故事。"""

    def __init__(
        self,
        llm_client: Optional[LLMClient] = None,
        event_store_client: Optional[EventStoreClient] = None,
    ):
        self.llm_client = llm_client or LLMClient()
        self.event_store_client = event_store_client or EventStoreClient()

    async def generate(self, aggregate_id: str) -> dict:
        events = self.event_store_client.load_events(aggregate_id)
        if not events:
            return {
                "aggregate_id": aggregate_id,
                "story": "未查询到该订单的事件",
                "event_types": [],
            }
        event_types = [
            e.get("event_type", "?")
            for e in sorted(events, key=lambda x: x.get("event_version", 0))
        ]
        try:
            prompt = STORY_USER_PROMPT.format(events=json.dumps(event_types, ensure_ascii=False))
            story = (await self.llm_client.generate(prompt, operation="story")).strip()
        except Exception as e:
            logger.warning("故事生成失败，模板兜底：%s", e)
            story = self._fallback(event_types)
        return {"aggregate_id": aggregate_id, "story": story, "event_types": event_types}

    @staticmethod
    def _fallback(event_types: list[str]) -> str:
        return "订单事件序列：" + " → ".join(event_types)
