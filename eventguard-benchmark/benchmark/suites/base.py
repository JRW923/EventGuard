"""套件基类：断言/计时/轮询/节奏助手 + 订单命令 REST 助手。

每个套件在 ctx 上运行；结果写入 self.result（FeatureResult）。
"""
from __future__ import annotations

import time
import uuid
from typing import Callable

from ..report.model import FeatureResult


class Suite:
    id: str = ""
    name: str = ""

    def __init__(self, ctx) -> None:
        self.ctx = ctx
        self.result = FeatureResult(id=self.id, name=self.name)

    def run(self) -> FeatureResult:
        t0 = time.time()
        try:
            self.execute()
        except Exception as e:  # 套件级兜底：不允许一个套件拖垮整次评测
            self.result.add(f"{self.id}_error", f"套件执行异常：{e}", False,
                            expected="套件正常完成", actual=str(e), method="rest")
        finally:
            self.result.duration_seconds = time.time() - t0
        return self.result

    def execute(self) -> None:
        raise NotImplementedError

    # —— 断言/测量助手 ——
    def add(self, aid: str, desc: str, passed: bool, expected: str = "", actual: str = "",
            method: str = "rest") -> None:
        self.result.add(aid, desc, passed, expected, actual, method)

    def wait_until(self, fn: Callable[[], object], timeout: float | None = None,
                   interval: float = 0.5) -> object | None:
        """轮询直到 fn() 返回真值；超时返回 None。默认 0.5s（≤2 req/s）配合 60/10s 限流。"""
        deadline = time.time() + (timeout or self.ctx.cfg.assert_timeout_seconds)
        while time.time() < deadline:
            try:
                v = fn()
                if v:
                    return v
            except Exception:
                pass
            time.sleep(interval)
        return None

    def pace(self) -> None:
        """功能套件自节奏（≤5 req/s，避免触发限流）。"""
        if self.ctx.cfg.pacing_ms > 0:
            time.sleep(self.ctx.cfg.pacing_ms / 1000.0)

    # —— REST 命令助手（返回 (json, 耗时ms)；失败抛异常由调用方决定）——
    def _cmd(self, method: str, path: str, token_role: str = "operator", payload: dict | None = None) -> dict:
        resp, dt = self.ctx.client.request(method, path, token=self.ctx.auth.token(token_role), json=payload)
        if resp.status_code >= 400:
            raise RuntimeError(f"{method} {path} → HTTP {resp.status_code}: {resp.text[:200]}")
        data = resp.json() if resp.content else {}
        data.setdefault("_latency_ms", round(dt, 1))
        return data

    def create_order(self, user: str, amount: float, order_id: str | None = None) -> dict:
        """创建订单。orderId 由调用方给定（POST /orders 不回显），固定 X-Command-Id 支持幂等重放。"""
        oid = order_id or str(uuid.uuid4())
        cmd_id = str(uuid.uuid4())
        payload = {"orderId": oid, "userId": user, "totalAmount": amount}
        resp, dt = self.ctx.client.post("/orders", token=self.ctx.auth.token("operator"),
                                        json=payload, headers={"X-Command-Id": cmd_id})
        if resp.status_code >= 400:
            raise RuntimeError(f"创建订单失败 HTTP {resp.status_code}: {resp.text[:200]}")
        return {"orderId": oid, "commandId": cmd_id, "latency_ms": round(dt, 1)}

    def pay_order(self, order_id: str) -> dict:
        resp, dt = self.ctx.client.post(f"/orders/{order_id}/pay", token=self.ctx.auth.token("operator"),
                                        json={"paymentId": f"bench-pay-{order_id[:8]}"})
        if resp.status_code >= 400:
            raise RuntimeError(f"支付失败 HTTP {resp.status_code}: {resp.text[:200]}")
        return resp.json() | {"_latency_ms": round(dt, 1)}

    def reserve_inventory(self, order_id: str, sku: str, qty: int) -> tuple[dict, int]:
        resp, dt = self.ctx.client.post(f"/orders/{order_id}/reserve-inventory",
                                        token=self.ctx.auth.token("operator"),
                                        json={"skuId": sku, "quantity": qty})
        data = resp.json() if resp.content else {}
        data.setdefault("_latency_ms", round(dt, 1))
        return data, resp.status_code
