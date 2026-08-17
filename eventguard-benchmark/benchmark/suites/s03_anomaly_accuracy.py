"""s03 AI 异常检测精度：确定性注入 7 类异常（R001/R002/R003/R004/R005 + P002/P003）+ 正常对照。

- R001/R004/R005 经真实 REST 命令路径（rest）；
- R002/P002/P003 经「DB 追加 + 直发 Kafka」注入（kafka_inject，聚合状态机不可达）；
- R003（状态跳跃）因 kafka_inject 会被服务端投影塌缩 order_view 版本历史、version-gated 前序状态
  读不到，改为直连规则引擎桥接（rest，AI 评估规则的同一条路径）做确定性触发。
- 每条告警带 _recv_ms；detection_latency = 提交/注入时刻 → 收到预期规则告警。
- 诚实性：注入事件会绕过聚合状态机，故状态跳跃类规则（P001）可能伴随触发，会计入 FP。
"""
from __future__ import annotations

import time
import uuid

from .. import scenario_inject as inject
from ..kafka import AlertCollector, EventProducer
from ..timeutil import percentiles
from .base import Suite


class AnomalyAccuracySuite(Suite):
    id = "s03_anomaly_accuracy"
    name = "AI 异常检测精度（R001–R005 + P002/P003）"

    def execute(self) -> None:
        run_id = self.ctx.run_id
        db = self.ctx.db
        collector = AlertCollector(self.ctx.cfg.kafka_bootstrap, f"bench-anomaly-audit-{run_id}").start()
        producer = EventProducer(self.ctx.cfg.kafka_bootstrap)
        scenarios: list[dict] = []  # {agg, expected:[rules], t0, method, desc}
        controls: list[str] = []
        observed: dict[str, dict[str, float]] = {}  # agg -> {rule: recv_ms}
        try:
            # ===== R001（rest）：同用户 10 笔基线 + 1 笔 3σ+ 偏离 =====
            user1 = f"bench-r001-{run_id}"
            for _ in range(10):
                self.create_order(user1, 100.0)
                self.pace()
            oid = str(uuid.uuid4())
            t0 = time.time() * 1000.0
            self.create_order(user1, 150.0, oid)
            scenarios.append({"agg": oid, "expected": ["R001"], "t0": t0, "method": "rest",
                              "desc": "金额偏离 150 vs 均值100+3σ"})

            # ===== R004（rest）：同用户 1 分钟内 ≥20 笔创建（第 20 笔评估时 DB 已有 20 条 → 触发） =====
            user4 = f"bench-r004-{run_id}"
            last = None
            for i in range(20):
                last = str(uuid.uuid4())
                self.create_order(user4, 5.0, last)
                self.pace()
            scenarios.append({"agg": last, "expected": ["R004"], "t0": time.time() * 1000.0,
                              "method": "rest", "desc": "≥20 笔/分钟 高频创建"})

            # ===== R005（rest）：预留超库存 =====
            oid = str(uuid.uuid4())
            self.create_order(f"bench-r005-{run_id}", 30.0, oid)
            self.pay_order(oid)
            self.wait_until(lambda: self._status(oid) == "PAID", timeout=20, interval=0.5)
            _, code = self.reserve_inventory(oid, "SKU-B", 6)
            self.add("r005_reserve", "库存不足预留命令落库（HTTP 200 带失败信息）", code == 200,
                     expected="200", actual=f"{code}", method="rest")
            scenarios.append({"agg": oid, "expected": ["R005"], "t0": time.time() * 1000.0,
                              "method": "rest", "desc": "预留 SKU-B qty6>5"})

            # ===== R002（kafka_inject）：已 PAID 订单 5 分钟内重复支付 =====
            oid = str(uuid.uuid4())
            self.create_order(f"bench-r002-{run_id}", 66.0, oid)
            self.pay_order(oid)
            self.wait_until(lambda: self._status(oid) == "PAID", timeout=20, interval=0.5)
            v = db.max_event_version(oid) + 1
            self._inject(producer, db, inject.duplicate_payment(oid, v, f"bench-r002-{run_id}"))
            scenarios.append({"agg": oid, "expected": ["R002"], "t0": time.time() * 1000.0,
                              "method": "kafka_inject", "desc": "PAID 后 5 分钟内重复 PaymentCompleted"})

            # ===== R003（rest 桥接）：未确认订单直接发货 =====
            # R003 前序状态读 order_view 的 version<当前版本；kafka_inject 会把注入事件也交给服务端
            # 投影（或塌缩 version 历史、或评估早于投影），version-gated 查询读不到前序状态而漏检。
            # 故直连规则引擎桥接（AI 评估规则的同一条 HTTP 路径）做确定性触发。
            oid = str(uuid.uuid4())
            self.create_order(f"bench-r003-{run_id}", 40.0, oid)
            self.wait_until(lambda: (db.order_view(oid) or {}).get("status") == "PENDING_PAYMENT",
                            timeout=20, interval=0.5)
            ov = db.order_view(oid)
            print(f"[s03] R003 订单投影: {ov['status']}@{ov['version']}" if ov else "[s03] R003 订单投影: 空",
                  flush=True)
            t0 = time.time() * 1000.0
            rule = self._bridge_rule(inject.state_jump_ship(oid, db.max_event_version(oid) + 1,
                                                            f"bench-r003-{run_id}"))
            if rule:
                observed[oid] = {rule: time.time() * 1000.0}
            scenarios.append({"agg": oid, "expected": ["R003"], "t0": t0,
                              "method": "rest", "desc": "PENDING_PAYMENT 直接 Shipped"})

            # ===== P002（kafka_inject）：PAID 后停滞 48h =====
            oid = str(uuid.uuid4())
            self.create_order(f"bench-p002-{run_id}", 20.0, oid)
            self._inject(producer, db, inject.stale_paid(oid, db.max_event_version(oid) + 1,
                                                         f"bench-p002-{run_id}", hours_old=48.0))
            scenarios.append({"agg": oid, "expected": ["P002_STUCK"], "t0": time.time() * 1000.0,
                              "method": "kafka_inject", "desc": "PaymentCompleted created_at 回拨 48h"})

            # ===== P003（kafka_inject）：支付死循环 >5 次 =====
            oid = str(uuid.uuid4())
            self.create_order(f"bench-p003-{run_id}", 25.0, oid)
            for ev in inject.dead_loop_retries(oid, db.max_event_version(oid) + 1, f"bench-p003-{run_id}"):
                self._inject(producer, db, ev)
            scenarios.append({"agg": oid, "expected": ["P003_DEAD_LOOP"], "t0": time.time() * 1000.0,
                              "method": "kafka_inject", "desc": "7 次 PaymentRetried"})

            # ===== 正常对照：20 笔独立用户正常订单 =====
            for i in range(20):
                oid = str(uuid.uuid4())
                self.create_order(f"bench-ctl-{run_id}-{i}", 15.0, oid)
                controls.append(oid)
                self.pace()

            # ===== 等待告警 =====
            deadline = time.time() + self.ctx.cfg.assert_timeout_seconds
            # R003 已由桥接直连观测，无需等待其 Kafka 告警
            pending = set(s["agg"] for s in scenarios) - set(observed)
            # 只统计本套件订单的告警：anomaly-alerts 是共享 topic，前序/后续套件的订单
            # 也可能在收集窗口内发布告警（实测 s03 曾混入 R005×4 等外部告警、precision 被拉低），
            # 本套件的检出口径只针对自己的 7 笔注入 + 20 笔对照。
            known = set(s["agg"] for s in scenarios) | set(controls)
            foreign: dict[str, list[str]] = {}
            while time.time() < deadline and pending:
                for alert in collector.drain():
                    agg = str(alert.get("aggregate_id") or "")
                    rule = alert.get("rule_id") or "?"
                    if agg not in known:
                        foreign.setdefault(agg[:8], []).append(rule)
                        continue
                    observed.setdefault(agg, {})[rule] = alert.get("_recv_ms") or time.time() * 1000.0
                    if agg in pending:
                        pending.discard(agg)
                time.sleep(0.5)
            if foreign:
                parts = [a + ":" + ",".join(r) for a, r in foreign.items()]
                print(f"[s03] 丢弃外部订单告警（不计 precision）: "
                      f"{sum(len(v) for v in foreign.values())} 条 / " + "; ".join(parts), flush=True)

            self._evaluate(scenarios, controls, observed)
        finally:
            collector.stop()
            producer.close()

    # —— 注入 ——
    def _inject(self, producer: EventProducer, db, event: dict) -> None:
        db.append_event(event)          # DB 追加：规则上下文（R002/R003 读 DB）
        producer.publish(event)         # Kafka 直发：AI 检测管道消费
        producer.flush()

    def _bridge_rule(self, ev: dict) -> str | None:
        """直连规则引擎桥接（AI 评估规则的同一条 HTTP 路径）：返回命中的 rule_id 或 None。"""
        import time as _t

        for attempt in range(3):
            resp, _ = self.ctx.client.post(
                "/anomaly/rules/evaluate",
                machine_key=self.ctx.cfg.machine_key,
                json={
                    "eventId": ev["event_id"],
                    "aggregateId": ev["aggregate_id"],
                    "eventType": ev["event_type"],
                    "version": ev["event_version"],
                    "occurredAt": ev["created_at"],
                    "metadata": ev.get("metadata", {}),
                    "payload": ev.get("payload", {}),
                },
                timeout=15,
            )
            if resp.status_code == 429:
                _t.sleep(1.0)  # 限流窗口复位后重试
                continue
            if resp.status_code == 200 and resp.content:
                body = resp.json()
                if isinstance(body, dict):
                    # Java 侧 Anomaly 用 camelCase（getRuleId → "ruleId"）；兼容 snake_case
                    rid = body.get("rule_id") or body.get("ruleId")
                    if rid != "R003":
                        print(f"[s03] 桥接评估 R003 返回 rule={rid!r}（非预期），body={body}", flush=True)
                    return rid
                print(f"[s03] 桥接评估 R003 返回非 dict: {resp.content[:200]!r}", flush=True)
                return None
            print(f"[s03] 桥接评估 R003 HTTP {resp.status_code}: {resp.text[:150]!r}", flush=True)
            return None
        print("[s03] 桥接评估 R003 连续 3 次 429，放弃", flush=True)
        return None

    def _status(self, oid: str) -> str | None:
        r, _ = self.ctx.client.get(f"/orders/{oid}", token=self.ctx.auth.token("operator"))
        return r.json().get("status") if r.status_code == 200 else None

    # —— 评估 ——
    def _evaluate(self, scenarios: list[dict], controls: list[str], observed: dict[str, dict[str, float]]) -> None:
        expected_pairs = {(s["agg"], r) for s in scenarios for r in s["expected"]}
        observed_pairs = {(agg, r) for agg, rules in observed.items() for r in rules}
        control_set = set(controls)

        # TP / FN / FP
        tp = len(expected_pairs & observed_pairs)
        fn = len(expected_pairs - observed_pairs)
        fp_unexpected = {(a, r) for (a, r) in observed_pairs if (a, r) not in expected_pairs}
        fp_control = {(a, r) for (a, r) in observed_pairs if a in control_set}
        fp = len(fp_unexpected | fp_control)
        precision = tp / (tp + fp) if (tp + fp) else 0.0
        recall = tp / (tp + fn) if (tp + fn) else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0

        # 每场景：是否被标记 + 预期规则是否命中
        flagged = sum(1 for s in scenarios if observed.get(s["agg"]))
        expected_hit = sum(1 for s in scenarios
                           if any(r in observed.get(s["agg"], {}) for r in s["expected"]))

        # 每规则命中
        per_rule: dict[str, dict] = {}
        for s in scenarios:
            for r in s["expected"]:
                per_rule.setdefault(r, {"expected": 0, "hit": 0})
                per_rule[r]["expected"] += 1
                if r in observed.get(s["agg"], {}):
                    per_rule[r]["hit"] += 1

        # 检测延迟：预期规则告警 recv - t0
        latencies: list[float] = []
        for s in scenarios:
            for r in s["expected"]:
                recv = observed.get(s["agg"], {}).get(r)
                if recv:
                    latencies.append(recv - s["t0"])
        lat_p = percentiles(latencies)

        # 断言
        for s in scenarios:
            hit = any(r in observed.get(s["agg"], {}) for r in s["expected"])
            observed_rules = ",".join(sorted(observed.get(s["agg"], {}))) or "无"
            self.add(f"scenario_{s['expected'][0]}", f"注入异常被检出（{s['desc']}）",
                     hit, expected=s["expected"], actual=observed_rules, method=s["method"])
        # 正常对照：IF 无监督模型可能存在少量误报（contamination≈5%），以 <30% 为诚实上界
        self.add("controls_fp", f"20 笔正常对照订单误报数 {len(fp_control)} < 6",
                 len(fp_control) < 6, expected="<6", actual=str(len(fp_control)), method="rest")

        self.result.metrics = {
            "overall": {"precision": round(precision, 3), "recall": round(recall, 3),
                        "f1": round(f1, 3), "tp": tp, "fp": fp, "fn": fn},
            "detection": {"scenarios": len(scenarios), "flagged": flagged, "expected_rule_hit": expected_hit},
            "per_rule": {r: v for r, v in per_rule.items()},
            "controls_fp": len(fp_control),
            "detection_latency_ms": lat_p,
        }
        self.result.method_notes.append(
            "R001/R004/R005 为 rest 驱动；R002/P002/P003 为 kafka_inject（合成事件绕过聚合状态机，"
            "状态跳跃类规则伴随触发属预期）；R003 因 kafka_inject 会被服务端投影塌缩 order_view 版本历史、"
            "version-gated 前序状态读不到，故改为直连规则引擎桥接（rest，AI 评估规则的同一路径）做确定性触发。"
            "HMM 已接线线上检测管道（2026-08-16 起，P004 序列级第二意见）；注入场景为规则覆盖类型，"
            "P004 命中情况以实际运行告警为准。"
        )
        self.result.conclusion = (
            f"检出 {flagged}/{len(scenarios)} 场景；预期规则命中 {expected_hit}/{len(scenarios)}；"
            f"整体 P={precision:.3f} R={recall:.3f} F1={f1:.3f}；检测延迟 p50={lat_p.get('p50_ms')}ms "
            f"p95={lat_p.get('p95_ms')}ms；正常对照 FP={len(fp_control)}。"
        )
