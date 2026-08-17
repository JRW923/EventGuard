"""Kafka 消费者：消费 domain-events topic，groupId=ai-event-detector"""

import json
import logging
import threading
import time
from typing import Callable, Optional

from kafka import KafkaConsumer, KafkaProducer

from app import metrics as egm

logger = logging.getLogger(__name__)

# 告警发布重试：3 次，退避 0.3s/0.9s（顺序与次数对齐）；真宕机不无限阻塞消费线程
PUBLISH_RETRIES = 3
PUBLISH_BACKOFF_SECONDS = (0.3, 0.9, 1.8)
MAX_MESSAGE_RETRIES = 3


def flatten_debezium_event(value):
    """Debezium CDC 消息展平为检测器可用事件字典（与 Java EventDeserializer 对齐）。

    envelope: {"schema":{...},"payload":{event_id,...}} -> {event_id,...}；
    同时把 JSONB 字符串列 payload/metadata 解串为对象。抽出为纯函数以便跨栈契约测试直接调用。
    """
    if isinstance(value, dict) and isinstance(value.get("payload"), dict) \
            and "event_id" in value["payload"]:
        value = value["payload"]
    for key in ("payload", "metadata"):
        if isinstance(value.get(key), str):
            try:
                value[key] = json.loads(value[key])
            except (ValueError, TypeError):
                pass
    return value


class EventKafkaConsumer:
    """消费 domain-events 的后台线程消费者"""

    def __init__(
        self,
        handler: Callable[[dict], None],
        topic: str = "domain-events",
        group_id: str = "ai-event-detector",
        bootstrap_servers: Optional[str] = None,
        dead_letter_topic: Optional[str] = None,
    ):
        from app.config import settings

        self.handler = handler
        self.topic = topic
        self.group_id = group_id
        self.bootstrap_servers = bootstrap_servers or settings.kafka_bootstrap
        self.dead_letter_topic = dead_letter_topic or f"{topic}.DLT"
        self._consumer: Optional[KafkaConsumer] = None
        self._dlt_producer: Optional[KafkaProducer] = None
        self._thread: Optional[threading.Thread] = None
        self._running = False
        self._failures: dict[tuple[str, int, int], int] = {}

    def start(self) -> None:
        """启动后台消费线程"""
        self._consumer = KafkaConsumer(
            self.topic,
            bootstrap_servers=self.bootstrap_servers,
            group_id=self.group_id,
            auto_offset_reset="earliest",
            enable_auto_commit=False,
            max_poll_records=1,
            key_deserializer=lambda k: k.decode("utf-8") if k else None,
        )
        self._running = True
        self._thread = threading.Thread(target=self._consume_loop, daemon=True)
        self._thread.start()
        logger.info("Kafka consumer started: topic=%s group_id=%s", self.topic, self.group_id)

    def _consume_loop(self) -> None:
        """消费循环：poll 消息并调用 handler，handler 异常不中断循环"""
        try:
            poll_count = 0
            while self._running:
                records = self._consumer.poll(timeout_ms=500)
                poll_count += 1
                # 每 10 次 poll（约 5s）采样一次消费积压：end_offsets 有网络往返，逐次采样不值得
                if poll_count % 10 == 0:
                    self._update_lag()
                for msgs in records.values():
                    for msg in msgs:
                        try:
                            # 逐条反序列化，畸形消息跳过而非中断循环（ponytail: 信任边界，外部 Kafka 消息不可信；单条毒消息被跳过，升级路径：死信 topic 持久化毒消息）
                            value = msg.value
                            if isinstance(value, (bytes, bytearray)):
                                value = json.loads(value.decode("utf-8"))
                            elif isinstance(value, str):
                                value = json.loads(value)
                            value = flatten_debezium_event(value)
                            self.handler(value)
                            self._consumer.commit()
                            # 重试后成功的 offset 必须从失败计数里摘掉，否则 _failures 随运行时长无界增长
                            self._failures.pop(self._msg_key(msg), None)
                        except Exception as e:
                            logger.exception("handler error; offset will be retried: %s", e)
                            self._retry_failed_message(msg)
        except Exception as e:
            logger.exception("consume loop error: %s", e)

    def _update_lag(self) -> None:
        """采样消费积压（end_offsets - position），暴露为 eventguard_ai_consumer_lag 指标。"""
        if self._consumer is None:
            return
        try:
            from kafka import TopicPartition

            assignment = self._consumer.assignment()
            if not assignment:
                return
            ends = self._consumer.end_offsets(assignment)
            for tp in assignment:
                lag = max(0, ends.get(tp, 0) - self._consumer.position(tp))
                egm.consumer_lag.labels(topic=tp.topic, partition=str(tp.partition)).set(lag)
        except Exception:
            # 采样失败不影响消费；broker 短暂不可达时保留上一次值
            logger.debug("lag sample failed", exc_info=True)

    def _msg_key(self, msg) -> tuple[str, int, int]:
        """失败计数的键；getattr 兜底是为了兼容测试里的轻量 mock 消息。"""
        return (
            getattr(msg, "topic", self.topic),
            int(getattr(msg, "partition", 0)),
            int(getattr(msg, "offset", 0)),
        )

    def _retry_failed_message(self, msg) -> None:
        """有限重试；DLT 发布成功后才提交原 offset，避免坏消息卡死消费组。"""
        if self._consumer is None:
            return
        from kafka import TopicPartition

        topic, partition, offset = self._msg_key(msg)
        key = (topic, partition, offset)
        attempt = self._failures.get(key, 0) + 1
        self._failures[key] = attempt
        tp = TopicPartition(topic, partition)
        try:
            if attempt >= MAX_MESSAGE_RETRIES:
                self._publish_dlt(msg)
                self._consumer.commit()
                self._failures.pop(key, None)
                logger.error("message moved to DLT topic=%s partition=%s offset=%s", topic, partition, offset)
                return
            self._consumer.seek(tp, offset)
            time.sleep(PUBLISH_BACKOFF_SECONDS[min(attempt - 1, len(PUBLISH_BACKOFF_SECONDS) - 1)])
        except Exception:
            logger.exception("message retry/DLT failed topic=%s partition=%s offset=%s",
                             topic, partition, offset)
            # DLT 不可用时保持原 offset 未提交，进程重启后继续重试。
            try:
                self._consumer.seek(tp, offset)
            except Exception:
                logger.exception("failed to retain Kafka offset topic=%s partition=%s offset=%s",
                                 topic, partition, offset)

    def _publish_dlt(self, msg) -> None:
        """DLT 发布失败时不提交 offset，交给进程重启/后续重试。"""
        if self._dlt_producer is None:
            self._dlt_producer = KafkaProducer(
                bootstrap_servers=self.bootstrap_servers,
                key_serializer=lambda k: k.encode("utf-8") if k else None,
                value_serializer=lambda v: v if isinstance(v, bytes) else str(v).encode("utf-8"),
            )
        self._dlt_producer.send(self.dead_letter_topic, key=msg.key, value=msg.value).get(timeout=5)

    def stop(self) -> None:
        """停止消费并关闭 consumer"""
        self._running = False
        if self._thread:
            self._thread.join(timeout=5)
        if self._consumer:
            self._consumer.close()
        if self._dlt_producer:
            self._dlt_producer.close()
        logger.info("Kafka consumer stopped")


# ======== M3.5 追加：事件级 + 流程级检测处理器 ========

import uuid
from datetime import datetime, timezone

from app.detector.alert_dedup import AlertDeduper
from app.detector.event_level import EventLevelService
from app.detector.process_level_hmm import run_process_detectors
from app.model.anomaly import Anomaly, AnomalyResult
from app.publisher.anomaly_publisher import AnomalyPublisher
from app.store.anomaly_store import anomaly_store


class DetectionHandler:
    """Kafka 事件处理：事件级 + 流程级检测 → 去重门控 → 发布异常"""

    def __init__(
        self,
        event_level_service: EventLevelService,
        publisher: AnomalyPublisher,
        process_level_detector=None,
        hmm_detector=None,
        event_window=None,
        deduper: Optional[AlertDeduper] = None,
    ):
        self.event_level_service = event_level_service
        self.publisher = publisher
        # M3.6 注入流程级检测；未注入时 handle() 自动跳过流程级检测
        # 运行时注入由 main.py 启动装配(后续 Task)，此处仅暴露注入点；未注入时流程级检测自动跳过
        self.process_level_detector = process_level_detector
        # M3.9 HMM 流程检测作为规则检测之后的第二道流程级检测（可选注入）
        self.hmm_detector = hmm_detector
        self.event_window = event_window
        # Item 2：告警去重 + 风暴抑制门控（不影响检测触发语义）
        self.deduper = deduper or AlertDeduper()

    def handle(self, event: dict) -> None:
        """处理单条事件：事件级检测 + 流程级检测"""
        egm.events_consumed.inc()
        _t0 = time.time()
        _publish_secs = 0.0
        try:
            _publish_secs = self._detect_and_publish(event)
        finally:
            # 检测耗时扣除发布耗时：detection_latency 只反映检测本身，发布单列 publish_duration
            egm.detection_latency.observe(max(0.0, time.time() - _t0 - _publish_secs))

    def _detect_and_publish(self, event: dict) -> float:
        """事件级 + 流程级检测并发布异常（均经去重门控）。返回发布总耗时。"""
        publish_secs = 0.0
        # 1. 事件级检测
        result = self.event_level_service.detect(event)
        if result.is_anomaly:
            anomaly = self._build_anomaly(event, result)
            # 事件级指纹用 event_id：不同事件永不误去重（事件级无窗口滑动重复问题）
            publish_secs += self._emit(anomaly, fingerprint=event.get("event_id") or anomaly.description)

        # 2. 流程级检测（M3.6 接入）
        if self.process_level_detector is not None and self.event_window is not None:
            agg_id = event.get("aggregate_id", "")
            self.event_window.add(event)
            sequence = self.event_window.get(agg_id)
            # 先规则检测，再 HMM 检测（第二意见），合并结果
            process_anomalies = run_process_detectors(
                sequence, self.process_level_detector, self.hmm_detector
            )
            for pa in process_anomalies:
                # 流程级指纹用描述（P001 含迁移对、P002 含停滞状态），窗口内稳定 → 消除滑动重复
                publish_secs += self._emit(pa, fingerprint=pa.description)
        return publish_secs

    def _emit(self, anomaly, fingerprint: str) -> float:
        """去重/抑制门控后 save + publish；跳过的只计指标，不落库不发 Kafka。返回发布耗时。"""
        verdict = self.deduper.should_publish(anomaly.rule_id, anomaly.aggregate_id, fingerprint)
        if verdict != "publish":
            egm.alert_dedup_total.labels(reason=verdict).inc()
            logger.info(
                "告警去重/抑制：%s（rule=%s agg=%s）", verdict, anomaly.rule_id, anomaly.aggregate_id
            )
            return 0.0
        anomaly_store.save(anomaly)
        return self._publish(anomaly)

    def _publish(self, anomaly) -> float:
        """发布异常到 Kafka；瞬时失败带退避重试，仍失败则留痕并计数。

        broker 抖动（连接瞬时不可达）是发布失败的主因，重试可消除；真宕机时
        3 次退避 ~3s 后放弃，不无限阻塞消费线程（消费背压由 Kafka 重平衡兜底）。
        返回发布总耗时（含退避），供 detection_latency 口径扣除。
        """
        _t0 = time.time()
        last_exc: Optional[Exception] = None
        for attempt in range(PUBLISH_RETRIES):
            try:
                self.publisher.publish(anomaly)
                egm.anomalies_published.labels(
                    rule_id=anomaly.rule_id or anomaly.source or "unknown",
                    source=anomaly.source or "unknown",
                    level=anomaly.level or "unknown",
                ).inc()
                egm.publish_duration.observe(time.time() - _t0)
                return time.time() - _t0
            except Exception as e:
                last_exc = e
                if attempt < PUBLISH_RETRIES - 1:
                    delay = PUBLISH_BACKOFF_SECONDS[attempt]
                    logger.warning(
                        "发布异常失败（第 %d/%d 次，%ss 后重试）: %s",
                        attempt + 1, PUBLISH_RETRIES, delay, e,
                    )
                    time.sleep(delay)
        # ponytail: 重试后仍失败——broker 持续不可达，告警暂存内存 store（升级路径：异步发送+死信）
        egm.publish_errors.inc()
        egm.publish_duration.observe(time.time() - _t0)
        logger.error(
            "发布异常失败（重试 %d 次后放弃，已存内存 store）: %s",
            PUBLISH_RETRIES - 1, last_exc,
        )
        return time.time() - _t0

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
