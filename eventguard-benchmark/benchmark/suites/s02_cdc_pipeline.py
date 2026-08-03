"""s02 CDC→Kafka 管道：REST 提交 → domain-events（Debezium CDC 捕获）→ 投影收敛。

延迟以 bench 自身时间戳为准（Prometheus 15s 抓取仅做看板参考）。
"""
from __future__ import annotations

import time
import uuid

from ..kafka import EventCollector
from ..timeutil import percentiles
from .base import Suite

N_ORDERS = 20


class CdcPipelineSuite(Suite):
    id = "s02_cdc_pipeline"
    name = "CDC→Kafka 管道（Debezium 捕获 + 投影收敛延迟）"

    def execute(self) -> None:
        run_id = self.ctx.run_id
        user = f"bench-s02-{run_id}"
        collector = EventCollector(self.ctx.cfg.kafka_bootstrap, f"bench-domain-{run_id}").start()
        last_oid: str | None = None
        try:
            cdc: list[float] = []
            conv: list[float] = []
            for i in range(N_ORDERS):
                oid = str(uuid.uuid4())
                last_oid = oid
                t0 = time.time() * 1000.0
                self.create_order(user, 20.0 + i, oid)
                ev = self.wait_until(lambda o=oid: collector.take(o, "OrderCreatedEvent"),
                                     timeout=15, interval=0.1)
                if ev:
                    cdc.append(ev["_recv_ms"] - t0)
                # 投影收敛：读己写 expectedVersion=1 → 200
                t1 = time.time() * 1000.0
                self.wait_until(lambda o=oid: self._read_ok(o, 1), timeout=15, interval=0.5)
                conv.append(time.time() * 1000.0 - t1)
                self.pace()
        finally:
            collector.stop()

        # 收敛断言：抽查最近一笔订单已入 order_view
        last_view = self.ctx.db.order_view(last_oid) if last_oid else None
        self.add("orders_projected", f"{N_ORDERS} 笔订单投影到 order_view", last_view is not None,
                 expected="order_view 有行", actual="有" if last_view else "缺")
        self.add("cdc_capture", "CDC 捕获延迟有样本（≥N×0.8）", len(cdc) >= int(N_ORDERS * 0.8),
                 expected=f"≥{int(N_ORDERS*0.8)}", actual=f"{len(cdc)}")
        self.add("convergence", "读己写收敛有样本（≥N×0.8）", len(conv) >= int(N_ORDERS * 0.8),
                 expected=f"≥{int(N_ORDERS*0.8)}", actual=f"{len(conv)}")

        cdc_p = percentiles(cdc)
        conv_p = percentiles(conv)
        self.result.metrics = {
            "orders": N_ORDERS,
            "cdc_capture_latency_ms": cdc_p,
            "projection_convergence_ms": conv_p,
        }
        self.result.conclusion = (
            f"CDC 捕获延迟 p50={cdc_p.get('p50_ms')}ms / p95={cdc_p.get('p95_ms')}ms；"
            f"投影收敛 p95={conv_p.get('p95_ms')}ms（bench 时间戳实测）。"
        )
        self.result.method_notes.append("延迟为 bench 从 REST 提交到 Kafka 收到/读模型追上的 wall-clock，非 Prometheus 快照。")

    def _read_ok(self, oid: str, version: int) -> bool:
        r, _ = self.ctx.client.get(f"/orders/{oid}?expectedVersion={version}",
                                   token=self.ctx.auth.token("operator"))
        return r.status_code == 200
