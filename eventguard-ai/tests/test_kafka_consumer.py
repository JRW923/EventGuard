from unittest.mock import MagicMock

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

    fake_msg_1 = MagicMock()
    fake_msg_1.value = {"event_type": "OrderCreatedEvent", "aggregate_id": "agg-1"}
    fake_msg_2 = MagicMock()
    fake_msg_2.value = {"event_type": "PaymentCompletedEvent", "aggregate_id": "agg-1"}

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


def test_consume_loop_continues_after_handler_exception():
    """验证 handler 抛异常时 consume_loop 不崩溃"""
    handler = MagicMock(side_effect=[ValueError("boom"), None])
    consumer = EventKafkaConsumer(
        handler=handler,
        topic="domain-events",
        group_id="ai-event-detector",
        bootstrap_servers="localhost:9092",
    )

    fake_msg_1 = MagicMock()
    fake_msg_1.value = {"event_type": "OrderCreatedEvent"}
    fake_msg_2 = MagicMock()
    fake_msg_2.value = {"event_type": "PaymentCompletedEvent"}

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
