"""Kafka 消费者：消费 domain-events topic，groupId=ai-event-detector"""

import json
import logging
import threading
from typing import Callable, Optional

from kafka import KafkaConsumer

logger = logging.getLogger(__name__)


class EventKafkaConsumer:
    """消费 domain-events 的后台线程消费者"""

    def __init__(
        self,
        handler: Callable[[dict], None],
        topic: str = "domain-events",
        group_id: str = "ai-event-detector",
        bootstrap_servers: Optional[str] = None,
    ):
        from app.config import settings

        self.handler = handler
        self.topic = topic
        self.group_id = group_id
        self.bootstrap_servers = bootstrap_servers or settings.kafka_bootstrap
        self._consumer: Optional[KafkaConsumer] = None
        self._thread: Optional[threading.Thread] = None
        self._running = False

    def start(self) -> None:
        """启动后台消费线程"""
        self._consumer = KafkaConsumer(
            self.topic,
            bootstrap_servers=self.bootstrap_servers,
            group_id=self.group_id,
            auto_offset_reset="earliest",
            enable_auto_commit=True,
            value_deserializer=lambda v: json.loads(v.decode("utf-8")),
            key_deserializer=lambda k: k.decode("utf-8") if k else None,
        )
        self._running = True
        self._thread = threading.Thread(target=self._consume_loop, daemon=True)
        self._thread.start()
        logger.info("Kafka consumer started: topic=%s group_id=%s", self.topic, self.group_id)

    def _consume_loop(self) -> None:
        """消费循环：poll 消息并调用 handler，handler 异常不中断循环"""
        try:
            while self._running:
                records = self._consumer.poll(timeout_ms=500)
                for msgs in records.values():
                    for msg in msgs:
                        try:
                            self.handler(msg.value)
                        except Exception as e:
                            logger.exception("handler error: %s", e)
        except Exception as e:
            logger.exception("consume loop error: %s", e)

    def stop(self) -> None:
        """停止消费并关闭 consumer"""
        self._running = False
        if self._thread:
            self._thread.join(timeout=5)
        if self._consumer:
            self._consumer.close()
        logger.info("Kafka consumer stopped")
