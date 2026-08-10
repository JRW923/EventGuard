"""s06 网关异步支付：pay→PAYMENT_REQUESTED→mock 回调→PAID；gateway_request 终态；回调往返延迟。"""
from __future__ import annotations

import time
import uuid

from ..timeutil import percentiles
from .base import Suite


class GatewayAsyncSuite(Suite):
    id = "s06_gateway_async"
    name = "网关异步支付（异步意图 + 回调）"

    def execute(self) -> None:
        run_id = self.ctx.run_id
        user = f"bench-s06-{run_id}"
        roundtrips: list[float] = []

        for i in range(5):
            oid = str(uuid.uuid4())
            self.create_order(user, 88.0 + i, oid)
            t0 = time.time() * 1000.0
            pay = self.pay_order(oid)
            intent_ok = pay.get("status") == "PAYMENT_REQUESTED"
            self.add(f"pay_intent_{i}", f"支付 {i}：返回异步意图 PAYMENT_REQUESTED", intent_ok,
                     expected="PAYMENT_REQUESTED", actual=str(pay.get("status")))
            self.wait_until(lambda o=oid: self._status(o) == "PAID", timeout=20, interval=0.5)
            roundtrips.append(time.time() * 1000.0 - t0)
            self.add(f"pay_complete_{i}", f"支付 {i}：mock 回调后 PAID", self._status(oid) == "PAID",
                     expected="PAID", actual=self._status(oid) or "timeout")
            rows = self.ctx.db.gateway_requests(oid)
            terminal = bool(rows) and rows[-1]["status"] in ("SUCCEEDED", "FAILED")
            self.add(f"gateway_terminal_{i}", f"支付 {i}：gateway_request 终态", terminal,
                     expected="SUCCEEDED/FAILED", actual=str(rows[-1]["status"]) if rows else "none")
            self.pace()

        self.result.metrics = {
            "callback_roundtrip_ms": percentiles(roundtrips),
            "delay_ms": self.ctx.mode.get("payment_delay_ms", 0),
        }
        self.result.conclusion = (
            f"异步支付往返延迟 p95={percentiles(roundtrips).get('p95_ms')}ms（mock 回调 delay="
            f"{self.ctx.mode.get('payment_delay_ms', 0)}ms 基线）；gateway_request 全部落终态。"
        )
        self.result.method_notes.append(
            f"支付为异步意图+回调形态；本套件用 mock 网关（delay={self.ctx.mode.get('payment_delay_ms', 0)}ms）确定性驱动。")

    def _status(self, order_id: str) -> str | None:
        r, _ = self.ctx.client.get(f"/orders/{order_id}", token=self.ctx.auth.token("operator"))
        if r.status_code == 200:
            return r.json().get("status")
        return None
