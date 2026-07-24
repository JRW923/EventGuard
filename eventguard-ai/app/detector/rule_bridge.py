"""规则引擎 HTTP 桥接：调用 Java 侧 POST /anomaly/rules/evaluate"""

import logging
from typing import Optional

import httpx

from app.config import settings
from app.model.anomaly import AnomalyResult

logger = logging.getLogger(__name__)


class RuleBridge:
    """通过 HTTP 调用 Java 规则引擎"""

    def __init__(self, url: Optional[str] = None):
        self.url = url or settings.rule_engine_url

    def evaluate(self, event: dict) -> Optional[AnomalyResult]:
        """调用规则引擎，命中返回 AnomalyResult，未命中返回 None"""
        request_body = self._build_request(event)
        try:
            with httpx.Client(timeout=2.0) as client:
                resp = client.post(self.url, json=request_body)
                resp.raise_for_status()
                data = resp.json()
        except httpx.HTTPError as e:
            logger.warning("规则引擎调用失败: %s", e)
            return None

        if data is None:
            return None

        return AnomalyResult(
            is_anomaly=True,
            score=0.0,
            source="RULE",
            level="HIGH",
            rule_id=data.get("ruleId"),
            description=data.get("description", ""),
        )

    def _build_request(self, event: dict) -> dict:
        """将 Kafka 事件格式转换为 Java REST 期望的 EventDto 格式"""
        return {
            "eventId": event.get("event_id"),
            "aggregateId": event.get("aggregate_id"),
            "eventType": event.get("event_type"),
            "version": event.get("event_version", 1),
            "occurredAt": event.get("created_at"),
            "metadata": event.get("metadata", {}),
            "payload": event.get("payload", {}),
        }
