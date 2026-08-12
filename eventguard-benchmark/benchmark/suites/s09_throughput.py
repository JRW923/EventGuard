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
        results: list[tuple[float, bool]] = []  # (write_lat_ms, core_ok) — 写路径
        read_results: list[bool] = []  # 读己写抽样结果（最终一致语义，独立于写吞吐）
        read_pool: list[str] = []  # 待读回己写的 orderId 池
        failures: dict[str, int] = {}  # 失败原因（状态码/异常类型）→ 次数，诊断用
        workers: list[threading.Thread] = []
        active = 0

        def reader() -> None:
            """读己写抽样：从池里取 orderId，GET 轮询到投影追上（≤2s）。"""
            client = ApiClient(self.ctx.cfg.server_base)
            try:
                while not stop.is_set():
                    oid: str | None = None
                    with lock:
                        if read_pool:
                            oid = read_pool.pop(0)
                    if oid is None:
                        time.sleep(0.02)
                        continue
                    r3, _ = client.get(f"/orders/{oid}", token=token, timeout=15)
                    ok = r3.status_code == 200
                    if not ok and r3.status_code == 404:
                        # CDC 异步投影实测滞后 ~0.4-0.6s，轮询追赶（最终一致语义）
                        deadline = time.time() + 2.0
                        while time.time() < deadline and r3.status_code == 404:
                            time.sleep(0.1)
                            r3, _ = client.get(f"/orders/{oid}", token=token, timeout=15)
                        ok = r3.status_code == 200
                    with lock:
                        read_results.append(ok)
                        if not ok:
                            failures[f"get:{r3.status_code}"] = failures.get(f"get:{r3.status_code}", 0) + 1
            finally:
                client.close()

        def worker() -> None:
            client = ApiClient(self.ctx.cfg.server_base)
            nonlocal active
            try:
                while not stop.is_set():
                    oid = str(uuid.uuid4())
                    t0 = time.time() * 1000.0
                    core_ok = False
                    try:
                        r1, _ = client.post("/orders", token=token,
                                            json={"orderId": oid, "userId": f"bench-s09-{run_id}",
                                                  "totalAmount": 99.0}, timeout=15)
                        if r1.status_code == 200:
                            r2, _ = client.post(f"/orders/{oid}/pay", token=token,
                                                json={"paymentId": f"lp-{oid[:8]}"}, timeout=15)
                            core_ok = r2.status_code == 200
                            if not core_ok:
                                with lock:
                                    failures[f"pay:{r2.status_code}"] = failures.get(f"pay:{r2.status_code}", 0) + 1
                            # 写路径延迟在 pay 完成即采样（不含读轮询），并把 orderId 交给读己写抽样
                            with lock:
                                results.append((time.time() * 1000.0 - t0, core_ok))
                                read_pool.append(oid)
                        else:
                            with lock:
                                failures[f"create:{r1.status_code}"] = failures.get(f"create:{r1.status_code}", 0) + 1
                    except Exception as e:
                        with lock:
                            key = f"exc:{type(e).__name__}"
                            failures[key] = failures.get(key, 0) + 1
            finally:
                client.close()
                active -= 1

        def spawn() -> None:
            nonlocal active
            active += 1
            t = threading.Thread(target=worker, daemon=True)
            workers.append(t)
            t.start()

        def spawn_reader() -> None:
            t = threading.Thread(target=reader, daemon=True)
            workers.append(t)
            t.start()

        try:
            # warmup：2 并发预热（结果丢弃）
            for _ in range(2):
                spawn()
            # 读己写抽样线程：独立于写吞吐，异步轮询池内 orderId 的最终一致性
            for _ in range(4):
                spawn_reader()
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
                read_results.clear()
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
        ok_core = sum(1 for _, c in results if c)
        qps = total / hold_seconds
        error_rate = 1.0 - (ok_core / total)
        lats = [lat for lat, c in results if c]
        if read_results:
            get_rate = sum(1 for g in read_results if g) / len(read_results)
        else:
            get_rate = 0.0
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
            "read_samples": len(read_results),
            "iterations": total,
            "concurrency": levels[-1],
            "hold_seconds": round(hold_seconds, 1),
            "latency_ms": lat_p,
            "latency_mean_ms": round(mean(lats) or 0, 1),
            "failures": dict(sorted(failures.items(), key=lambda kv: -kv[1])),
        }
        print(f"[s09] 失败诊断 failures={dict(sorted(failures.items(), key=lambda kv: -kv[1]))} total={total} read_samples={len(read_results)}")
        self.result.conclusion = (
            f"稳态 {levels[-1]} 并发：QPS={qps:.1f}，写路径错误率={error_rate:.2%}，"
            f"p50={lat_p.get('p50_ms')}ms p95={lat_p.get('p95_ms')}ms p99={lat_p.get('p99_ms')}ms，"
            f"读己写(异步抽样)成功率={get_rate:.1%}。"
        )
        self.result.method_notes.append(
            f"限流关闭（eg.rate-limit.enabled=false）下运行；Profile=warmup {warmup:.0f}s → 爬坡 {ramp:.0f}s → "
            f"稳态 {hold:.0f}s；写路径延迟在 pay 完成即采样；读己写由独立线程异步抽样（CDC 最终一致，≤2s 轮询），"
            f"不阻塞写吞吐。"
        )
