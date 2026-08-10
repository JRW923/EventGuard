"""Kafka 直连：独立消费组收 anomaly-alerts / domain-events；producer 注入合成事件（kafka_inject 通道）。

消费组每次运行唯一（group_id 带 run_id），auto_offset_reset=latest，避免与既有消费组
（anomaly-ws / order-view-projection / saga-trigger / ai-event-detector）冲突。
"""
from __future__ import annotations

import json
import threading
from collections import deque

from kafka import KafkaConsumer, KafkaProducer


class AlertCollector:
    """收 anomaly-alerts：注入前启动，drain() 取走已收到的告警；每条附加 _recv_ms 接收时间戳。"""

    def __init__(self, bootstrap: str, group_id: str) -> None:
        self.consumer = KafkaConsumer(
            "anomaly-alerts",
            bootstrap_servers=bootstrap,
            group_id=group_id,
            auto_offset_reset="latest",
            enable_auto_commit=True,
            value_deserializer=lambda v: json.loads(v.decode("utf-8")),
        )
        self.alerts: deque[dict] = deque(maxlen=50000)
        self._stop = False
        self._ready = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self, wait_ready: bool = True) -> "AlertCollector":
        self._thread.start()
        if wait_ready:
            # auto_offset_reset=latest 的消费组需 join 并定位到 latest 后才开始收；
            # 不等就绪直接注入，最早产生的告警会被跳过（实测 R001 偶发漏收）。
            self._ready.wait(timeout=10)
        return self

    def _run(self) -> None:
        try:
            while not self._stop:
                records = self.consumer.poll(timeout_ms=200)
                if self.consumer.assignment():
                    self._ready.set()  # 已 join 分组并定位到 latest
                for msgs in records.values():
                    for m in msgs:
                        if m.value:
                            self.alerts.append(m.value | {"_recv_ms": _now_ms()})
        except Exception:
            # 消费循环异常不抛给主流程（评测器会通过超时断言感知告警缺失）
            pass

    def drain(self) -> list[dict]:
        out = list(self.alerts)
        self.alerts.clear()
        return out

    def stop(self) -> None:
        self._stop = True
        self._thread.join(timeout=5)
        self.consumer.close()


class EventCollector:
    """收 domain-events（s02 测 CDC 捕获/投影延迟）。

    对每条消息：展平 Debezium envelope（{schema,payload} → payload）、附加 _recv_ms 接收时间戳。
    """

    def __init__(self, bootstrap: str, group_id: str) -> None:
        self.consumer = KafkaConsumer(
            "domain-events",
            bootstrap_servers=bootstrap,
            group_id=group_id,
            auto_offset_reset="latest",
            enable_auto_commit=True,
            value_deserializer=lambda v: json.loads(v.decode("utf-8")),
        )
        self.events: deque[dict] = deque(maxlen=50000)
        self._stop = False
        self._ready = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self, wait_ready: bool = True) -> "EventCollector":
        self._thread.start()
        if wait_ready:
            self._ready.wait(timeout=10)
        return self

    def _run(self) -> None:
        try:
            while not self._stop:
                records = self.consumer.poll(timeout_ms=200)
                if self.consumer.assignment():
                    self._ready.set()
                for msgs in records.values():
                    for m in msgs:
                        if m.value:
                            self.events.append(_flatten(m.value) | {"_recv_ms": _now_ms()})
        except Exception:
            pass

    def drain(self) -> list[dict]:
        out = list(self.events)
        self.events.clear()
        return out

    def take(self, aggregate_id: str, event_type: str | None = None) -> dict | None:
        """在缓冲中找某订单的事件（保留在缓冲，供后续断言）。"""
        for ev in self.events:
            if ev.get("aggregate_id") == aggregate_id and (
                event_type is None or ev.get("event_type") == event_type
            ):
                return ev
        return None

    def stop(self) -> None:
        self._stop = True
        self._thread.join(timeout=5)
        self.consumer.close()


def _flatten(value: dict) -> dict:
    """Debezium envelope {schema,payload} → payload 展平；已是展平形态则原样。"""
    if isinstance(value, dict) and isinstance(value.get("payload"), dict) and "event_id" in value["payload"]:
        return value["payload"]
    return value


def _now_ms() -> float:
    import time

    return time.time() * 1000.0


class EventProducer:
    """向 domain-events 注入合成事件（kafka_inject 通道）。

    消息采用 Debezium 展平格式（与 EventDeserializer.deserializeFromKafka 兼容）：
    {event_id, aggregate_id, event_type, event_version, created_at, metadata, payload}
    """

    def __init__(self, bootstrap: str) -> None:
        self.producer = KafkaProducer(
            bootstrap_servers=bootstrap,
            key_serializer=lambda k: k.encode("utf-8") if isinstance(k, str) else k,
            value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
        )

    def publish(self, event: dict) -> None:
        self.producer.send("domain-events", key=event.get("aggregate_id"), value=event)

    def flush(self) -> None:
        self.producer.flush()

    def close(self) -> None:
        self.producer.close()
