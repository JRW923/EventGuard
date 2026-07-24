"""异常告警发布器：将异常发到 Kafka anomaly-alerts topic"""

import json
import logging
from typing import Optional

from kafka import KafkaProducer

from app.config import settings
from app.model.anomaly import Anomaly

logger = logging.getLogger(__name__)


class AnomalyPublisher:
    """将 Anomaly 发布到 Kafka anomaly-alerts topic（key=aggregate_id）"""

    def __init__(self, bootstrap_servers: Optional[str] = None):
        self._producer: Optional[KafkaProducer] = None
        self.bootstrap_servers = bootstrap_servers or settings.kafka_bootstrap

    def _get_producer(self) -> KafkaProducer:
        if self._producer is None:
            self._producer = KafkaProducer(
                bootstrap_servers=self.bootstrap_servers,
                key_serializer=lambda k: k.encode("utf-8") if k else None,
                value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
            )
        return self._producer

    def publish(self, anomaly: Anomaly) -> None:
        # ponytail: 同步 flush 每条约 5s 阻塞,无重试,broker 不可达即抛;升级路径=异步发送+确认回调
        """发布异常到 Kafka"""
        producer = self._get_producer()
        producer.send(
            "anomaly-alerts",
            key=anomaly.aggregate_id,
            value=anomaly.model_dump(),
        )
        producer.flush(timeout=5)
        logger.info("异常已发布: anomaly_id=%s rule_id=%s", anomaly.anomaly_id, anomaly.rule_id)

    def close(self) -> None:
        if self._producer:
            self._producer.close()
