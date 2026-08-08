"""TraceLog 环形缓冲单元测试。"""
from app.trace.trace_log import TraceLog


def test_record_and_recent_newest_first():
    t = TraceLog(maxlen=5)
    t.record("llm_call", latency_ms=1.0, ok=True)
    t.record("nl_query", intent="event_lookup")
    entries = t.recent()
    assert len(entries) == 2
    assert entries[0]["operation"] == "nl_query"  # 最新在前
    assert entries[0]["intent"] == "event_lookup"
    assert "ts" in entries[0]


def test_ring_buffer_caps():
    t = TraceLog(maxlen=3)
    for i in range(5):
        t.record("op", idx=i)
    assert t.size() == 3
    entries = t.recent()
    assert entries[0]["idx"] == 4  # 最新在前，旧记录被挤出


def test_recent_limit():
    t = TraceLog(maxlen=10)
    for i in range(5):
        t.record("op", idx=i)
    assert len(t.recent(limit=2)) == 2


def test_clear():
    t = TraceLog(maxlen=10)
    t.record("op")
    t.clear()
    assert t.size() == 0
