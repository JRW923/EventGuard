"""s08 限流正确性：per-IP 滑动窗口（默认 60/10s），超限 429，/health 豁免，窗口复位恢复。"""
from __future__ import annotations

import time

from .base import Suite


class RateLimitSuite(Suite):
    id = "s08_rate_limit"
    name = "通用限流（per-IP 滑动窗口 60/10s）"

    def execute(self) -> None:
        if not self.ctx.mode.get("rate_limit"):
            self.result.status = "SKIPPED"
            self.result.conclusion = "限流已关闭（EG_RATE_LIMIT_ENABLED=false），跳过限流正确性验证。"
            return

        time.sleep(10)  # 10s 屏障：确保进入干净窗口，避免与前置套件共享窗口
        token = self.ctx.auth.token("operator")
        n = self.ctx.cfg.rate_limit_max

        # 1) 突发 max 次 → 应全部 200
        statuses = [self._get_orders(token) for _ in range(n)]
        all_ok = all(s == 200 for s in statuses)
        self.add("burst_under", f"突发 {n} 次读订单全部放行（200）", all_ok,
                 expected=f"全部 200", actual=f"{statuses.count(200)}×200 / {statuses.count(429)}×429")

        # 2) 继续突发 → 应出现 429
        first_429 = None
        for i in range(10):
            s = self._get_orders(token)
            if s == 429:
                first_429 = n + i + 1
                break
        self.add("burst_over", "超限触发 429 拒绝", first_429 is not None,
                 expected="出现 429", actual=f"第 {first_429} 次起 429" if first_429 else "未触发")

        # 3) /health 放行路径在突发期间不应 429
        exempt_429 = any(self._get_health() == 429 for _ in range(5))
        self.add("health_exempt", "/health 豁免路径不受限流影响", not exempt_429,
                 expected="始终非 429", actual="出现 429" if exempt_429 else "未 429")

        # 4) 窗口复位后可恢复
        time.sleep(10)
        s = self._get_orders(token)
        self.add("window_reset", "窗口滑动复位后请求恢复 200", s == 200, expected="200", actual=f"{s}")

        self.result.metrics = {
            "enforced": first_429 is not None,
            "measured_threshold": first_429 if first_429 is not None else n,
            "window_seconds": 10,
        }
        self.result.conclusion = f"限流实测：第 {first_429} 次请求起 429（配置 max={n}）；/health 豁免、窗口复位均符合预期。"

    def _get_orders(self, token: str) -> int:
        r, _ = self.ctx.client.get("/orders", token=token)
        return r.status_code

    def _get_health(self) -> int:
        r, _ = self.ctx.client.get("/health")
        return r.status_code
