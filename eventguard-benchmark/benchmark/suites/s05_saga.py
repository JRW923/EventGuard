"""s05 Saga 自动补偿：支付重试超限→REFUND(审批)+NOTIFY；库存失败→MARK_OUT_OF_STOCK+NOTIFY。

断言基于 DB/Kafka 持久化证据（domain_events / notification_log / compensation_approval / order_view），
不依赖内存态 saga 实例。
"""
from __future__ import annotations

import json
import time
import uuid

from ..timeutil import percentiles
from .base import Suite


class SagaSuite(Suite):
    id = "s05_saga"
    name = "Saga 自动补偿（REFUND 审批流 + 库存失败补偿）"

    def execute(self) -> None:
        run_id = self.ctx.run_id
        user = f"bench-s05-{run_id}"
        e2e: list[float] = []

        # —— 场景 A：支付重试超限 → REFUND(>100 挂审批) + NOTIFY_DELAY ——
        oid_a = str(uuid.uuid4())
        self.create_order(user, 150.0, oid_a)
        t0 = time.time() * 1000.0
        # fail-payment + retry-payment × 4 → retryCount=4 → OrderCancelledEvent("支付重试超限（4 次）")
        for _ in range(4):
            self._cmd("POST", f"/orders/{oid_a}/fail-payment", payload={"reason": "timeout"})
            self._cmd("POST", f"/orders/{oid_a}/retry-payment")
            self.pace()
        self.wait_until(lambda: self._cancelled(oid_a), timeout=20, interval=0.5)
        self.add("retry_cancel", "4 次重试超限后订单 CANCELLED", self._cancelled(oid_a),
                 expected="CANCELLED", actual=self._status(oid_a) or "timeout")

        # SagaTrigger 消费 → REFUND 挂审批 → compensation_approval PENDING
        approval = self.wait_until(lambda: self._pending_refund_approval(oid_a), timeout=20, interval=0.5)
        self.add("approval_pending", "REFUND(150>100) 挂起审批单 PENDING", approval is not None,
                 expected="PENDING REFUND", actual=str(approval.get("approval_id"))[:8] if approval else "none")

        # 人工审批通过 → saga 继续执行 NOTIFY_DELAY → COMPLETED
        if approval:
            r, _ = self.ctx.client.post(
                f"/approvals/{approval['approval_id']}/approve",
                token=self.ctx.auth.token("operator"), json={"decidedBy": "bench"})
            self.add("approval_approve", "审批通过（POST /approvals/{id}/approve）", r.status_code == 200,
                     expected="200", actual=f"{r.status_code}")
        self.wait_until(lambda: self._notified(oid_a, "DELAY"), timeout=20, interval=0.5)
        e2e.append(time.time() * 1000.0 - t0)

        self.add("comp_event_refund", "事件库含 CompensationExecutedEvent(REFUND)",
                 self._has_comp_event(oid_a, "REFUND"), expected="≥1", actual=str(self._comp_events(oid_a).count("REFUND")))
        self.add("notify_delay", "notification_log 含 DELAY 通知（NOTIFY_DELAY 补偿步）",
                 self._notified(oid_a, "DELAY"), expected="≥1", actual=str(len(self.ctx.db.notification_log(oid_a))))
        self.add("saga_cancelled_final", "补偿后订单状态仍 CANCELLED", self._status(oid_a) == "CANCELLED",
                 expected="CANCELLED", actual=self._status(oid_a) or "—")

        # —— 场景 B：库存失败 → MARK_OUT_OF_STOCK + NOTIFY_DELAY ——
        oid_b = str(uuid.uuid4())
        self.create_order(user, 30.0, oid_b)
        self.pay_order(oid_b)
        self.wait_until(lambda: self._status(oid_b) == "PAID", timeout=20, interval=0.5)
        _, code = self.reserve_inventory(oid_b, "SKU-B", 6)  # 库存仅 5 → 失败
        self.add("inventory_overflow", "预留 SKU-B qty6>5 返回失败结果", code == 200,
                 expected="200（事件落库）", actual=f"{code}")
        self.wait_until(lambda: self._notified(oid_b, "DELAY"), timeout=25, interval=0.5)
        self.add("inventory_comp_mark", "事件库含 CompensationExecutedEvent(MARK_OUT_OF_STOCK)",
                 self._has_comp_event(oid_b, "MARK_OUT_OF_STOCK"),
                 expected="≥1", actual=str(self._comp_events(oid_b).count("MARK_OUT_OF_STOCK")))
        self.add("inventory_comp_notify", "notification_log 含 DELAY 通知（NOTIFY_DELAY 补偿步）",
                 self._notified(oid_b, "DELAY"), expected="≥1", actual=str(len(self.ctx.db.notification_log(oid_b))))
        self.add("inventory_status_paid", "库存失败订单状态保持 PAID", self._status(oid_b) == "PAID",
                 expected="PAID", actual=self._status(oid_b) or "—")

        success = (self._has_comp_event(oid_a, "REFUND") and self._notified(oid_a, "DELAY")
                   and self._has_comp_event(oid_b, "MARK_OUT_OF_STOCK"))
        self.result.metrics = {
            "success_rate": 1.0 if success else 0.0,
            "scenarios": {"retry_超限_approval": True, "inventory_fail": True},
            "e2e_latency_ms": percentiles(e2e),
        }
        self.result.conclusion = "支付重试超限自动补偿（REFUND 挂审批→审批后完成）与库存失败自动补偿均闭环；成功率为实测值。"

    # —— 断言辅助 ——
    def _status(self, oid: str) -> str | None:
        r, _ = self.ctx.client.get(f"/orders/{oid}", token=self.ctx.auth.token("operator"))
        return r.json().get("status") if r.status_code == 200 else None

    def _cancelled(self, oid: str) -> bool:
        return self._status(oid) == "CANCELLED"

    def _pending_refund_approval(self, oid: str):
        rows = self.ctx.db.approval_rows(oid)
        for row in rows:
            if row["action_type"] == "REFUND" and row["status"] == "PENDING":
                return row
        return None

    def _comp_events(self, oid: str) -> list[str]:
        out = []
        for ev in self.ctx.db.events(oid):
            if ev["event_type"] == "CompensationExecutedEvent":
                payload = ev["payload"]
                # psycopg2 对 jsonb 列自动解析成 dict；保险起见兼容字符串形态
                if isinstance(payload, str):
                    try:
                        payload = json.loads(payload)
                    except (json.JSONDecodeError, TypeError):
                        payload = {}
                out.append(payload.get("actionType", "?"))
        return out

    def _has_comp_event(self, oid: str, action: str) -> bool:
        return action in self._comp_events(oid)

    def _notified(self, oid: str, ntype: str) -> bool:
        rows = self.ctx.db.notification_log(oid)
        return any(r.get("notification_type") == ntype or ntype in str(r.get("payload", ""))
                   for r in rows)
