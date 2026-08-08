"""LLMCache 单元测试：命中 / TTL 过期 / LRU 淘汰。"""
from app.cache.llm_cache import LLMCache


def test_cache_hit_and_miss():
    c = LLMCache(ttl=300, max_size=16)
    assert c.get("anthropic", "m", 0.3, "hello") is None
    c.set("anthropic", "m", 0.3, "hello", "world")
    assert c.get("anthropic", "m", 0.3, "hello") == "world"


def test_cache_ttl_expiry(monkeypatch):
    c = LLMCache(ttl=10, max_size=16)
    clock = {"now": 1000.0}
    monkeypatch.setattr("app.cache.llm_cache.time.time", lambda: clock["now"])
    c.set("p", "m", 0.3, "q", "v")
    assert c.get("p", "m", 0.3, "q") == "v"
    clock["now"] += 11  # 超过 TTL → 过期
    assert c.get("p", "m", 0.3, "q") is None


def test_cache_lru_eviction():
    c = LLMCache(ttl=300, max_size=2)
    c.set("p", "m", 0.3, "a", "1")
    c.set("p", "m", 0.3, "b", "2")
    c.set("p", "m", 0.3, "c", "3")  # 触发淘汰最旧 a
    assert c.get("p", "m", 0.3, "a") is None
    assert c.get("p", "m", 0.3, "c") == "3"
