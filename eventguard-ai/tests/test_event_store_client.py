"""EventStoreClient 单元测试：camelCase → snake_case 规范化（后端 REST 事件源）。"""
from unittest.mock import MagicMock, patch

import httpx

from app.store.event_store_client import EventStoreClient

RAW_EVENTS = [
    {"eventId": "e1", "eventType": "OrderCreatedEvent", "version": 1,
     "createdAt": "2026-07-01T00:00:00Z", "aggregateId": "agg-1",
     "payload": {"totalAmount": 100}},
    {"eventId": "e2", "eventType": "PaymentCompletedEvent", "version": 2,
     "createdAt": "2026-07-01T00:10:00Z", "aggregateId": "agg-1", "payload": {}},
]


def test_normalize_mapping():
    out = EventStoreClient._normalize(RAW_EVENTS[0])
    assert out["event_type"] == "OrderCreatedEvent"
    assert out["event_version"] == 1
    assert out["event_id"] == "e1"
    assert out["created_at"] == "2026-07-01T00:00:00Z"
    assert out["aggregate_id"] == "agg-1"
    assert out["payload"]["totalAmount"] == 100


def test_load_events_normalizes_to_snake_case():
    ctx = MagicMock()
    ctx.__enter__.return_value.get.return_value.json.return_value = RAW_EVENTS
    with patch("app.store.event_store_client.httpx.Client", return_value=ctx):
        events = EventStoreClient().load_events("agg-1")

    assert [e["event_type"] for e in events] == ["OrderCreatedEvent", "PaymentCompletedEvent"]
    assert events[0]["event_version"] == 1
    assert events[1]["created_at"] == "2026-07-01T00:10:00Z"


def test_load_events_failure_returns_empty():
    ctx = MagicMock()
    ctx.__enter__.return_value.get.side_effect = httpx.HTTPError("network down")
    with patch("app.store.event_store_client.httpx.Client", return_value=ctx):
        events = EventStoreClient().load_events("agg-1")
    assert events == []
