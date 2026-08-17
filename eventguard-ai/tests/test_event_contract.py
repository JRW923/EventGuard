"""跨栈事件契约（Java → AI 边界）：验证 Debezium CDC 发出的事件 envelope
经 flatten_debezium_event 展平后，AI 能正确取到 event_id/aggregate_id/event_type
并解析 JSONB 内的业务字段。契约字段以 contracts/domain-event.sample.json 为准。

这是历史 bug 高发点（envelope 未拆、JSONB 未解串、字段名漂移），故独立成约测试。
"""
import json

from app.kafka_consumer import flatten_debezium_event


def _envelope(event_id, aggregate_id, event_type, payload_obj, metadata=None):
    # 复刻 Java domain_events 经 Debezium CDC 发出的插入消息：
    # payload 列与 metadata 列在 CDC 中以 JSON 字符串形式承载
    return {
        "schema": {"type": "struct", "fields": []},
        "payload": {
            "event_id": event_id,
            "aggregate_id": aggregate_id,
            "aggregate_type": "Order",
            "event_type": event_type,
            "event_version": 1,
            "payload": json.dumps(payload_obj),
            "metadata": json.dumps(metadata) if metadata is not None else None,
            "created_at": 1700000000000,
        },
    }


def test_order_created_envelope_consumable_by_ai():
    env = _envelope(
        "e-1", "o-1", "OrderCreatedEvent",
        {"orderId": "o-1", "userId": "u1", "totalAmount": 99.0},
    )


    ev = flatten_debezium_event(env)

    assert ev["event_id"] == "e-1"
    assert ev["aggregate_id"] == "o-1"
    assert ev["event_type"] == "OrderCreatedEvent"
    # JSONB 字符串已被解串为对象
    assert isinstance(ev["payload"], dict)
    assert ev["payload"]["orderId"] == "o-1"
    assert ev["payload"]["userId"] == "u1"
    assert ev["payload"]["totalAmount"] == 99.0
    # metadata 为 None 时不应被解串成字符串残留
    assert ev["metadata"] is None


def test_non_envelope_dict_passes_through():
    # 已是平铺字典（无 schema 包裹）应原样返回，不破坏现有直连消费路径
    flat = {"event_id": "e-2", "aggregate_id": "o-2", "event_type": "OrderPaidEvent",
            "payload": {"orderId": "o-2"}}
    assert flatten_debezium_event(flat) is flat
