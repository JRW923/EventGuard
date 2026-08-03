"""s04 中文自然语言查询：curated 问题集（event_lookup / stats_aggregation / trace_replay）。

断言 intent 与 data 字段（模式无关：LLM 有无均可），并记录 llm_mode。
"""
from __future__ import annotations

import uuid

from ..timeutil import percentiles
from .base import Suite


class NlQuerySuite(Suite):
    id = "s04_nl_query"
    name = "中文自然语言查询（意图 + 数据正确性 + 延迟）"

    def execute(self) -> None:
        run_id = self.ctx.run_id
        user = f"bench-s04-{run_id}"

        # 构造已知订单：一笔走完整生命周期到 CLOSED
        closed_id = str(uuid.uuid4())
        self.create_order(user, 120.0, closed_id)
        self.pay_order(closed_id)
        self.wait_until(lambda: self._status(closed_id) == "PAID", timeout=20, interval=0.2)
        self._cmd("POST", f"/orders/{closed_id}/confirm")
        self._cmd("POST", f"/orders/{closed_id}/ship", payload={"trackingNo": "SF-NL"})
        self._cmd("POST", f"/orders/{closed_id}/deliver")
        self._cmd("POST", f"/orders/{closed_id}/close")
        self.wait_until(lambda: self._status(closed_id) == "CLOSED", timeout=20, interval=0.2)

        # 另一笔停留 PAID
        paid_id = str(uuid.uuid4())
        self.create_order(user, 80.0, paid_id)
        self.pay_order(paid_id)
        self.wait_until(lambda: self._status(paid_id) == "PAID", timeout=20, interval=0.2)

        # curated 问题集：每项 (question, expected_intent, 校验函数)
        cases = [
            (f"订单 {closed_id} 当前状态是什么", "event_lookup",
             lambda d: d and d.get("status") == "CLOSED"),
            (f"帮我查一下订单 {paid_id} 的信息", "event_lookup",
             lambda d: d and d.get("status") == "PAID"),
            (f"订单 {paid_id} 现在到哪一步了", "event_lookup",
             lambda d: d and d.get("status") == "PAID"),
            ("今天有多少订单", "stats_aggregation", lambda d: isinstance(d, list)),
            ("最近7天各状态的订单统计", "stats_aggregation", lambda d: isinstance(d, list)),
            (f"订单 {closed_id} 经历了哪些状态变更", "trace_replay",
             lambda d: isinstance(d, list) and len(d) >= 7),
            (f"订单 {closed_id} 的事件时间线", "trace_replay",
             lambda d: isinstance(d, list) and len(d) >= 7),
            (f"回放订单 {closed_id} 的历史", "trace_replay",
             lambda d: isinstance(d, list) and len(d) >= 7),
        ]

        latencies: list[float] = []
        passed = 0
        for i, (question, expected_intent, check) in enumerate(cases):
            resp, dt = self.ctx.client.post("/ai/query", token=self.ctx.auth.token("operator"),
                                            json={"question": question}, timeout=30)
            latencies.append(dt)
            if resp.status_code != 200:
                self.add(f"q{i}_{expected_intent}", f"查询 HTTP 200（{question[:20]}…）", False,
                         expected="200", actual=f"{resp.status_code}", method="rest")
                continue
            body = resp.json()
            intent = body.get("intent")
            data = body.get("data")
            intent_ok = intent == expected_intent
            data_ok = check(data)
            ok = intent_ok and data_ok
            passed += ok
            self.add(f"q{i}_{expected_intent}", f"「{question[:24]}…」intent={expected_intent} + data 校验",
                     ok, expected=f"intent={expected_intent}",
                     actual=f"intent={intent} data={_shape(data)}", method="rest")
            self.pace()

        acc = round(passed / len(cases), 4)
        self.result.metrics = {
            "accuracy": acc,
            "passed": passed,
            "total": len(cases),
            "latency_ms": percentiles(latencies),
            "llm_mode": self.ctx.mode.get("llm", "unknown"),
        }
        self.result.conclusion = (
            f"NL 查询准确率 {passed}/{len(cases)}（{acc:.1%}）；p95 延迟 "
            f"{percentiles(latencies).get('p95_ms')}ms；LLM 模式：{self.ctx.mode.get('llm', 'unknown')}"
            "（fallback 模式断言 intent+data，不依赖 LLM 措辞）。"
        )

    def _status(self, oid: str) -> str | None:
        r, _ = self.ctx.client.get(f"/orders/{oid}", token=self.ctx.auth.token("operator"))
        return r.json().get("status") if r.status_code == 200 else None


def _shape(data) -> str:
    if isinstance(data, list):
        return f"list[{len(data)}]"
    if isinstance(data, dict):
        return f"dict(status={data.get('status')})"
    return str(data)
