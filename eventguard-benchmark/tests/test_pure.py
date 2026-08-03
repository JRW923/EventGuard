"""bench 纯函数单测：timeutil / state_machine / scenario_inject / report model（无需运行栈）。"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmark.report.model import FeatureResult, Kpi, RunResult
from benchmark.scenario_inject import dead_loop_retries, duplicate_payment, stale_paid
from benchmark.state_machine import replay_status
from benchmark.timeutil import iso_to_epoch_ms, percentile, percentiles


def test_percentile():
    assert percentile([1, 2, 3, 4], 0.5) == 2
    assert percentile([1, 2, 3, 4], 0.99) == 4
    assert percentile([], 0.5) is None


def test_percentiles():
    p = percentiles([10, 20, 30, 40, 50, 60, 70, 80, 90, 100])
    assert p["p50_ms"] == 50
    assert p["p95_ms"] == 100


def test_iso_to_epoch_ms():
    assert iso_to_epoch_ms("1970-01-01T00:00:00Z") == 0.0
    assert iso_to_epoch_ms("garbage") is None
    assert iso_to_epoch_ms(None) is None


def test_replay_status():
    events = [
        {"event_type": "OrderCreatedEvent", "event_version": 1},
        {"event_type": "PaymentRequestedEvent", "event_version": 2},  # 状态保留
        {"event_type": "PaymentCompletedEvent", "event_version": 3},
        {"event_type": "OrderClosedEvent", "event_version": 7},
    ]
    assert replay_status(events) == "CLOSED"
    assert replay_status([]) is None


def test_scenario_injectors():
    dup = duplicate_payment("agg-1", 4, "u1")
    assert dup["event_type"] == "PaymentCompletedEvent"
    assert dup["event_version"] == 4
    assert dup["aggregate_id"] == "agg-1"

    stale = stale_paid("agg-2", 2, "u2", hours_old=48.0)
    assert stale["event_type"] == "PaymentCompletedEvent"
    assert stale["event_version"] == 2

    retries = dead_loop_retries("agg-3", 2, "u3", count=7)
    assert len(retries) == 7
    assert retries[0]["event_version"] == 2
    assert retries[6]["event_version"] == 8


def test_report_model():
    f = FeatureResult(id="s00", name="测试")
    f.add("a1", "通过断言", True, "expected", "actual")
    assert f.status == "PASS"
    f.add("a2", "失败断言", False, "expected", "actual")
    assert f.status == "FAIL"
    assert len(f.assertions) == 2

    r = RunResult(timestamp="t", features=[f], headline_kpis=[Kpi("k", 1.0, "ms", "s00")])
    d = r.to_dict()
    assert d["features"][0]["assertions"][1]["passed"] is False
    assert d["executive_summary"]["headline_kpis"][0]["value"] == 1.0
