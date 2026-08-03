"""报告数据模型：suite 输出 → RunResult → 渲染成 md/json/html。

每条断言带 method 字段（rest / kafka_inject / db_assert / chaos），
供 executive_summary 区分"纯 REST 驱动"的 headline KPI 与注入通道的结果。
"""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Assertion:
    id: str
    description: str
    passed: bool
    expected: str = ""
    actual: str = ""
    method: str = "rest"

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "description": self.description,
            "passed": self.passed,
            "expected": self.expected,
            "actual": self.actual,
            "method": self.method,
        }


@dataclass
class FeatureResult:
    id: str
    name: str
    assertions: list[Assertion] = field(default_factory=list)
    metrics: dict = field(default_factory=dict)  # 任意可 JSON 化的 key/value 树
    conclusion: str = ""
    method_notes: list[str] = field(default_factory=list)  # 诚实性注记
    status: str = "PASS"  # PASS / FAIL / SKIPPED
    duration_seconds: float = 0.0

    def add(self, aid: str, desc: str, passed: bool, expected: str = "", actual: str = "",
            method: str = "rest") -> None:
        self.assertions.append(Assertion(aid, desc, passed, expected, actual, method))
        if not passed:
            self.status = "FAIL"

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "status": self.status,
            "duration_seconds": round(self.duration_seconds, 2),
            "assertions": [a.to_dict() for a in self.assertions],
            "metrics": self.metrics,
            "conclusion": self.conclusion,
            "method_notes": self.method_notes,
        }


@dataclass
class Kpi:
    key: str
    value: object
    unit: str = ""
    feature: str = ""
    method: str = "rest"

    def to_dict(self) -> dict:
        return {"key": self.key, "value": self.value, "unit": self.unit,
                "feature": self.feature, "method": self.method}


@dataclass
class RunResult:
    timestamp: str = ""
    duration_seconds: float = 0.0
    git_rev: str = ""
    mode: dict = field(default_factory=dict)
    features: list[FeatureResult] = field(default_factory=list)
    headline_kpis: list[Kpi] = field(default_factory=list)
    auth_events: list[dict] = field(default_factory=list)
    chaos: dict = field(default_factory=dict)  # s10 导入的 chaos-results.json

    def to_dict(self) -> dict:
        return {
            "run": {
                "timestamp": self.timestamp,
                "duration_seconds": round(self.duration_seconds, 2),
                "git_rev": self.git_rev,
                "mode": self.mode,
            },
            "executive_summary": {
                "headline_kpis": [k.to_dict() for k in self.headline_kpis],
            },
            "auth_events": self.auth_events,
            "chaos": self.chaos,
            "features": [f.to_dict() for f in self.features],
        }
