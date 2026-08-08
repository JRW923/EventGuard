"""AnomalyStore 单元测试：倒序检索 / since 过滤 / 文件持久化 / 上限淘汰。"""
from app.model.anomaly import Anomaly
from app.store.anomaly_store import AnomalyStore


def _anomaly(aid: str, detected_at: str) -> Anomaly:
    return Anomaly(
        anomaly_id=aid, rule_id="P001", aggregate_id="agg-1", event_type="PaymentFailedEvent",
        level="WARN", source="PROCESS", priority="HIGH", detected_at=detected_at, description="d",
    )


def test_list_recent_sorted_desc():
    s = AnomalyStore()
    s.save(_anomaly("a1", "2026-08-08T10:00:00Z"))
    s.save(_anomaly("a2", "2026-08-08T11:00:00Z"))
    s.save(_anomaly("a3", "2026-08-07T09:00:00Z"))
    assert [a.anomaly_id for a in s.list_recent(limit=10)] == ["a2", "a1", "a3"]


def test_list_recent_since_filter():
    s = AnomalyStore()
    s.save(_anomaly("a1", "2026-08-08T10:00:00Z"))
    s.save(_anomaly("a2", "2026-08-07T09:00:00Z"))
    assert [a.anomaly_id for a in s.list_recent(since="2026-08-08T00:00:00Z")] == ["a1"]


def test_persistence_roundtrip(tmp_path):
    p = str(tmp_path / "anomalies.jsonl")
    s = AnomalyStore(persist_path=p)
    s.save(_anomaly("a1", "2026-08-08T10:00:00Z"))
    # 新实例从文件恢复
    s2 = AnomalyStore(persist_path=p)
    assert s2.get("a1") is not None
    assert s2.size() == 1


def test_eviction_over_max():
    s = AnomalyStore(max_entries=3)
    for i in range(5):
        s.save(_anomaly(f"a{i}", f"2026-08-08T0{i}:00:00Z"))
    assert s.size() == 3
    assert s.get("a0") is None  # 最旧被淘汰
    assert s.get("a4") is not None
