"""s09 吞吐 / 延迟负载测试：登录 → 下单 → 支付 → 查询。

需 `eg.rate-limit.enabled=false`（限流开启时 run.py 会 SKIPPED）。
Profile：10s warmup(2 并发) → 5→50 并发爬坡(35s) → 稳态 50 并发(30s)。
核心场景 = 下单+支付（写路径）；读己写成功率单独统计（读模型异步投影，滞后不算写失败）。
"""
from __future__ import annotations

import os
import threading
import time
import uuid

from ..client import ApiClient
from ..timeutil import mean, percentiles
from .base import Suite


def _env(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except ValueError:
        return default


class ThroughputSuite(Suite):
    id = "s09_throughput"
    name = "吞吐 / 延迟（下单 + 支付 + 查询，限流关闭）"

    def execute(self) -> None:
        warmup = _env("BENCH_LOAD_WARMUP_SECONDS", 10)
        ramp = _env("BENCH_LOAD_RAMP_SECONDS", 35)
        hold = _env("BENCH_LOAD_HOLD_SECONDS", 30)
        token = self.ctx.auth.token("operator")
        run_id = self.ctx.run_id

        stop = threading.Event()
        lock = threading.Lock()
        results: list[tuple[float, bool, bool]] = []  # (lat_ms, core_ok, get_ok)
        workers: list[threading.Thread] = []
        active = 0

        def worker() -> None:
            client = ApiClient(self.ctx.cfg.server_base)
            nonlocal active
            try:
                while not stop.is_set():
                    oid = str(uuid.uuid4())
                    t0 = time.time() * 1000.0
                    core_ok = False
                    get_ok = False
                    try:
                        r1 = client.post("/orders", token=token,
                                         json={"orderId": oid, "userId": f"bench-s09-{run_id}",
                                               "totalAmount": 99.0}, timeout=15)
                        if r1.status_code == 200:
                            r2 = client.post(f"/orders/{oid}/pay", token=token,
                                             json={"paymentId": f"lp-{oid[:8]}"}, timeout=15)
                            core_ok = r2.status_code == 200
                            r3 = client.get(f"/orders/{oid}", token=token, timeout=15)
                            if r3.status_code == 404:  # 读模型异步投影，允许一次追赶
                                time.sleep(0.1)
                                r3 = client.get(f"/orders/{oid}", token=token, timeout=15)
                            get_ok = r3.status_code == 200
                    except Exception:
                        core_ok = False
                    with lock:
                        results.append((time.time() * 1000.0 - t0, core_ok, get_ok))
            finally:
                client.close()
                active -= 1

        def spawn() -> None:
            nonlocal active
            active += 1
            t = threading.Thread(target=worker, daemon=True)
            workers.append(t)
            t.start()

        try:
            # warmup：2 并发预热（结果丢弃）
            for _ in range(2):
                spawn()
            time.sleep(warmup)

            # ramp：5 → 50 并发（每档爬坡，档位时间 = ramp/档数）
            levels = [5, 10, 15, 20, 30, 40, 50]
            step = ramp / len(levels)
            for level in levels:
                while active < level:
                    spawn()
                time.sleep(step)

            # hold：稳态 50 并发，采集结果
            with lock:
                results.clear()
            hold_start = time.time()
            time.sleep(hold)
        finally:
            stop.set()
            for t in workers:
                t.join(timeout=15)
        hold_seconds = time.time() - hold_start

        if not results:
            self.add("load_samples", "负载测试有样本", False, expected=">0", actual="0")
            self.result.metrics = {"qps": 0, "error_rate": 1.0}
            self.result.conclusion = "负载测试无有效样本（可能全失败）。"
            return

        total = len(results)
        ok_core = sum(1 for _, c, _ in results if c)
        ok_get = sum(1 for _, _, g in results if g)
        qps = total / hold_seconds
        error_rate = 1.0 - (ok_core / total)
        lats = [lat for lat, c, _ in results if c]
        get_rate = ok_get / total if total else 0.0
        lat_p = percentiles(lats)

        self.add("load_qps", f"吞吐 {qps:.1f} QPS（稳态 {hold_seconds:.0f}s）", qps > 0,
                 expected=">0 QPS", actual=f"{qps:.1f}")
        self.add("load_error_rate", f"写路径错误率 {error_rate:.2%} < 5%", error_rate < 0.05,
                 expected="<5%", actual=f"{error_rate:.2%}")
        self.add("load_p95", "写路径 p95 延迟 < 500ms", (lat_p.get("p95_ms") or 9999) < 500,
                 expected="<500ms", actual=f"{lat_p.get('p95_ms')}ms")

        self.result.metrics = {
            "qps": round(qps, 2),
            "error_rate": round(error_rate, 4),
            "get_read_your_write_rate": round(get_rate, 4),
            "iterations": total,
            "concurrency": levels[-1],
            "hold_seconds": round(hold_seconds, 1),
            "latency_ms": lat_p,
            "latency_mean_ms": round(mean(lats) or 0, 1),
        }
        self.result.conclusion = (
            f"稳态 {levels[-1]} 并发：QPS={qps:.1f}，写路径错误率={error_rate:.2%}，"
            f"p50={lat_p.get('p50_ms')}ms p95={lat_p.get('p95_ms')}ms p99={lat_p.get('p99_ms')}ms，"
            f"读己写(含一次追赶)成功率={get_rate:.1%}。"
        )
        self.result.method_notes.append(
            f"限流关闭（eg.rate-limit.enabled=false）下运行；Profile=warmup {warmup:.0f}s → 爬坡 {ramp:.0f}s → "
            f"稳态 {hold:.0f}s；并发用线程池模拟，无共享状态。"
        )
