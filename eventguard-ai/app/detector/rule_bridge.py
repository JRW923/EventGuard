"""规则引擎 HTTP 桥接：调用 Java 侧 POST /anomaly/rules/evaluate"""

import json
import logging
from typing import Optional

import httpx

from app import metrics as egm
from app.config import settings
from app.model.anomaly import AnomalyResult

logger = logging.getLogger(__name__)


class RuleBridge:
    """通过 HTTP 调用 Java 规则引擎"""

    def __init__(self, url: Optional[str] = None):
        self.url = url or settings.rule_engine_url
        # 复用连接池：每事件一次评估，即建即弃的 Client 会让 TCP/TLS 握手成为检测吞吐的无谓开销
        self._client: Optional[httpx.Client] = None

    def _get_client(self) -> httpx.Client:
        if self._client is None or self._client.is_closed:
            self._client = httpx.Client(timeout=settings.rule_bridge_timeout_seconds)
        return self._client

    def evaluate(self, event: dict) -> Optional[AnomalyResult]:
        """调用规则引擎，命中返回 AnomalyResult，未命中返回 None"""
        request_body = self._build_request(event)
        try:
            # ponytail: 同步阻塞硬超时（默认 2.0s，EG_RULE_BRIDGE_TIMEOUT_SECONDS 可调）；
            # 规则引擎慢即整条事件检测被卡住，升级路径=异步/批量调用+熔断
            resp = self._get_client().post(
                self.url,
                json=request_body,
                headers={"X-API-Key": settings.machine_api_key},
            )
            resp.raise_for_status()
            data = resp.json()
        except (httpx.HTTPError, ValueError) as e:  # ValueError 覆盖 200 但 body 非合法 JSON（JSONDecodeError）
            egm.rule_bridge_errors.inc()
            logger.warning("规则引擎调用失败,降级跳过: %s", e)
            return None

        if not data:  # None 与空 dict {} 都按未命中，避免空对象构造假阳性高优告警
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
