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
                            # 逐条反序列化，畸形消息跳过而非中断循环（ponytail: 信任边界，外部 Kafka 消息不可信；单条毒消息被跳过，升级路径：死信 topic 持久化毒消息）
                            value = msg.value
                            if isinstance(value, (bytes, bytearray)):
                                value = json.loads(value.decode("utf-8"))
                            elif isinstance(value, str):
                                value = json.loads(value)
                            self.handler(value)
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


# ======== M3.5 追加：事件级 + 流程级检测处理器 ========

import uuid
from datetime import datetime, timezone

from app.detector.event_level import EventLevelService
from app.model.anomaly import Anomaly, AnomalyResult
from app.publisher.anomaly_publisher import AnomalyPublisher
from app.store.anomaly_store import anomaly_store


class DetectionHandler:
    """Kafka 事件处理：事件级 + 流程级检测 → 发布异常"""

    def __init__(
        self,
        event_level_service: EventLevelService,
        publisher: AnomalyPublisher,
        process_level_detector=None,
        event_window=None,
    ):
        self.event_level_service = event_level_service
        self.publisher = publisher
        # M3.6 注入流程级检测；未注入时 handle() 自动跳过流程级检测
        # 运行时注入由 main.py 启动装配(后续 Task)，此处仅暴露注入点；未注入时流程级检测自动跳过
        self.process_level_detector = process_level_detector
        self.event_window = event_window

    def handle(self, event: dict) -> None:
        """处理单条事件：事件级检测 + 流程级检测"""
        # 1. 事件级检测
        result = self.event_level_service.detect(event)
        if result.is_anomaly:
            anomaly = self._build_anomaly(event, result)
            anomaly_store.save(anomaly)
            try:
                self.publisher.publish(anomaly)
            except Exception as e:  # ponytail: broker 不可达时丢告警但 store 已留痕;上限=无重试/无死信,升级路径=异步发送+失败回调
                logger.error("发布异常失败(已存内存 store): %s", e)

        # 2. 流程级检测（M3.6 接入）
        if self.process_level_detector is not None and self.event_window is not None:
            agg_id = event.get("aggregate_id", "")
            self.event_window.add(event)
            sequence = self.event_window.get(agg_id)
            process_anomalies = self.process_level_detector.detect(sequence)
            for pa in process_anomalies:
                anomaly_store.save(pa)
                try:
                    self.publisher.publish(pa)
                except Exception as e:  # ponytail: broker 不可达时丢告警但 store 已留痕;升级路径=异步发送+失败回调
                    logger.error("发布流程级异常失败(已存内存 store): %s", e)

    def _build_anomaly(self, event: dict, result: AnomalyResult) -> Anomaly:
        """从检测结果构建 Anomaly 模型"""
        event_type = event.get("event_type", "Unknown")
        agg_id = event.get("aggregate_id", str(uuid.uuid4()))
        level_map = {"HIGH": "ERROR", "LOW": "WARN"}

        return Anomaly(
            anomaly_id=str(uuid.uuid4()),
            rule_id=result.rule_id if result.rule_id is not None else result.source,
            aggregate_id=agg_id,
            event_type=event_type,
            level=level_map.get(result.level, "WARN"),
            source=result.source,
            priority=result.level,
            detected_at=datetime.now(timezone.utc).isoformat(),
            description=result.description or f"{result.source} 检出异常",
            details={"score": result.score} if result.source == "IF" else {},
        )
