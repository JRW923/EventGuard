import json
from unittest.mock import MagicMock
from types import SimpleNamespace

from app.kafka_consumer import EventKafkaConsumer


def test_consume_loop_calls_handler_for_each_message():
    """验证 consume_loop 对每条消息调用 handler"""
    handler = MagicMock()
    consumer = EventKafkaConsumer(
        handler=handler,
        topic="domain-events",
        group_id="ai-event-detector",
        bootstrap_servers="localhost:9092",
    )

    fake_msg_1 = SimpleNamespace(topic="domain-events", partition=0, offset=0, key=None,
                                 value=json.dumps(
        {"event_type": "OrderCreatedEvent", "aggregate_id": "agg-1"}
    ).encode("utf-8"))
    fake_msg_2 = SimpleNamespace(topic="domain-events", partition=0, offset=1, key=None,
                                 value=json.dumps(
        {"event_type": "PaymentCompletedEvent", "aggregate_id": "agg-1"}
    ).encode("utf-8"))

    mock_kafka = MagicMock()
    call_count = [0]

    def fake_poll(timeout_ms=500):
        call_count[0] += 1
        if call_count[0] == 1:
            return {0: [fake_msg_1, fake_msg_2]}
        consumer._running = False
        return {}

    mock_kafka.poll.side_effect = fake_poll
    consumer._consumer = mock_kafka
    consumer._running = True

    consumer._consume_loop()

    handler.assert_any_call({"event_type": "OrderCreatedEvent", "aggregate_id": "agg-1"})
    handler.assert_any_call({"event_type": "PaymentCompletedEvent", "aggregate_id": "agg-1"})
    assert handler.call_count == 2
    assert mock_kafka.commit.call_count == 2


def test_consume_loop_continues_after_handler_exception():
    """验证 handler 抛异常时 consume_loop 不崩溃"""
    handler = MagicMock(side_effect=[ValueError("boom"), None])
    consumer = EventKafkaConsumer(
        handler=handler,
        topic="domain-events",
        group_id="ai-event-detector",
        bootstrap_servers="localhost:9092",
    )

    fake_msg_1 = SimpleNamespace(topic="domain-events", partition=0, offset=0, key=None,
                                 value=json.dumps({"event_type": "OrderCreatedEvent"}).encode("utf-8"))
    fake_msg_2 = SimpleNamespace(topic="domain-events", partition=0, offset=1, key=None,
                                 value=json.dumps({"event_type": "PaymentCompletedEvent"}).encode("utf-8"))

    mock_kafka = MagicMock()
    call_count = [0]

    def fake_poll(timeout_ms=500):
        call_count[0] += 1
        if call_count[0] == 1:
            return {0: [fake_msg_1, fake_msg_2]}
        consumer._running = False
        return {}

    mock_kafka.poll.side_effect = fake_poll
    consumer._consumer = mock_kafka
    consumer._running = True

    consumer._consume_loop()

    assert handler.call_count == 2
    mock_kafka.seek.assert_called()
    # 第一条失败未提交，第二条成功才提交；失败消息仍留在原 offset 等待下一轮重试。
    assert mock_kafka.commit.call_count == 1
