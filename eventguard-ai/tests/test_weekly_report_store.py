"""WeeklyReportStore 单测：落盘/缓存命中窗口/历史倒序。测试一律用 tmp_path。"""
from datetime import datetime, timedelta, timezone

from app.report.weekly_report_store import WeeklyReportStore, CACHE_FRESH_HOURS


def _report(days: int, **kw) -> dict:
    base = {"period": {"days": days, "from": "2026-08-01T00:00:00Z", "to": "2026-08-08T00:00:00Z"},
            "total_anomalies": 0, "by_rule": [], "order_stats": [],
            "symptoms": ["s"], "recommendations": ["r"], "top_orders": []}
    base.update(kw)
    return base


def _ago(report: dict, hours: float) -> dict:
    report["generated_at"] = (datetime.now(timezone.utc) - timedelta(hours=hours)).isoformat()
    return report


def test_save_and_history_roundtrip(tmp_path):
    s = WeeklyReportStore(persist_path=str(tmp_path / "weekly.jsonl"))
    s.save(_report(7))
    s.save(_report(14))
    assert s.size() == 2
    # 新实例从文件恢复
    s2 = WeeklyReportStore(persist_path=str(tmp_path / "weekly.jsonl"))
    assert s2.size() == 2
    hist = s2.history(limit=10)
    assert [h["period"]["days"] for h in hist] == [14, 7]  # 新在前


def test_find_cached_hits_same_days_within_window(tmp_path):
    s = WeeklyReportStore(persist_path=str(tmp_path / "weekly.jsonl"))
    s.save(_ago(_report(7), hours=1))
    hit = s.find_cached(7)
    assert hit is not None
    assert hit["period"]["days"] == 7


def test_find_cached_miss_different_days(tmp_path):
    s = WeeklyReportStore(persist_path=str(tmp_path / "weekly.jsonl"))
    s.save(_ago(_report(7), hours=1))
    assert s.find_cached(30) is None


def test_find_cached_miss_stale(tmp_path):
    s = WeeklyReportStore(persist_path=str(tmp_path / "weekly.jsonl"))
    s.save(_ago(_report(7), hours=CACHE_FRESH_HOURS + 1))
    assert s.find_cached(7) is None


def test_trim_over_max(tmp_path):
    s = WeeklyReportStore(persist_path=str(tmp_path / "weekly.jsonl"), max_entries=3)
    for i in range(5):
        s.save(_report(7, total_anomalies=i))
    assert s.size() == 3
    # 保留最近 3 份
    assert [h["total_anomalies"] for h in s.history(10)] == [4, 3, 2]


def test_corrupt_line_skipped(tmp_path):
    p = tmp_path / "weekly.jsonl"
    p.write_text('{"broken":\n', encoding="utf-8")
    s = WeeklyReportStore(persist_path=str(p))
    assert s.size() == 0  # 损坏行跳过，不阻断
