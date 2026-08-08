"""AlertDeduper 单元测试：幂等去重 / 风暴抑制 / TTL 过期重发 / 上限淘汰。"""
from app.detector.alert_dedup import AlertDeduper


def test_duplicate_signature_within_ttl_dedups():
    d = AlertDeduper(ttl_seconds=300, storm_limit=100)
    assert d.should_publish("P001", "agg-1", "非法迁移:PAID→SHIPPED") == "publish"
    assert d.should_publish("P001", "agg-1", "非法迁移:PAID→SHIPPED") == "dup"
    assert d.should_publish("P001", "agg-1", "非法迁移:PAID→SHIPPED") == "dup"


def test_different_fingerprint_publishes():
    d = AlertDeduper(ttl_seconds=300, storm_limit=100)
    assert d.should_publish("P001", "agg-1", "迁移A") == "publish"
    assert d.should_publish("P001", "agg-1", "迁移B") == "publish"


def test_storm_suppression_limits_frequency():
    d = AlertDeduper(ttl_seconds=300, storm_limit=3)
    assert d.should_publish("P002", "agg-1", "f1") == "publish"
    assert d.should_publish("P002", "agg-1", "f2") == "publish"
    assert d.should_publish("P002", "agg-1", "f3") == "publish"
    assert d.should_publish("P002", "agg-1", "f4") == "suppressed"


def test_ttl_expiry_allows_republish(monkeypatch):
    d = AlertDeduper(ttl_seconds=10, storm_limit=100)
    clock = {"now": 1000.0}
    monkeypatch.setattr("app.detector.alert_dedup.time.time", lambda: clock["now"])
    assert d.should_publish("P002", "agg-1", "停滞") == "publish"
    clock["now"] += 11  # 超过 TTL → 签名过期，允许重发
    assert d.should_publish("P002", "agg-1", "停滞") == "publish"


def test_lru_eviction_over_cap_allows_republish():
    d = AlertDeduper(ttl_seconds=300, max_entries=3, storm_limit=100)
    d.should_publish("R1", "a", "sig-a")
    d.should_publish("R2", "b", "sig-b")
    d.should_publish("R3", "c", "sig-c")
    d.should_publish("R4", "d", "sig-d")  # 触发淘汰：最旧 sig-a 被逐出
    assert d.should_publish("R1", "a", "sig-a") == "publish"


def test_clear_resets_all_state():
    d = AlertDeduper(ttl_seconds=300, storm_limit=100)
    d.should_publish("R1", "a", "sig-a")
    d.clear()
    assert d.should_publish("R1", "a", "sig-a") == "publish"
