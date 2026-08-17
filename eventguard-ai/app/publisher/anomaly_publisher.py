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
        """发布异常到 Kafka；单条等确认，超时即抛（由消费侧重试/DLT 链路兜底）。"""
        producer = self._get_producer()
        # ponytail: 同步等单条确认（2s）保证「发布成功才返回」，最坏 3 次重试阻塞 ~6s+退避；
        # 彻底解法是后台批量 flusher + 确认回调推进水位，个人项目流量下不值得。
        future = producer.send(
            "anomaly-alerts",
            key=anomaly.aggregate_id,
            value=anomaly.model_dump(),
        )
        future.get(timeout=2)
        logger.info("异常已发布: anomaly_id=%s rule_id=%s", anomaly.anomaly_id, anomaly.rule_id)

    def close(self) -> None:
        if self._producer:
            self._producer.close()
