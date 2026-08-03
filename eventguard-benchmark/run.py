#!/usr/bin/env python3
"""EventGuard 评测器（bench）入口。

用法：
    python run.py [--suites functional|load|all] [--out DIR] [--dry-run]

- functional：s01–s08 + s10（韧性导入）—— 正确性/准确率/延迟
- load：s09 —— 吞吐/延迟（需限流关闭）
- all：functional + load（限流开启时 load 自动 SKIPPED 并给重启命令）
- --dry-run：仅 preflight（快速冒烟）
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from benchmark.config import Config
from benchmark.orchestrator import Context, load_chaos_results, preflight
from benchmark.report.model import Kpi, RunResult
from benchmark.report import generate as report_gen


def _load_suites(ctx: Context) -> dict[str, list]:
    from benchmark.suites.s01_event_sourcing import EventSourcingSuite
    from benchmark.suites.s02_cdc_pipeline import CdcPipelineSuite
    from benchmark.suites.s03_anomaly_accuracy import AnomalyAccuracySuite
    from benchmark.suites.s04_nl_query import NlQuerySuite
    from benchmark.suites.s05_saga import SagaSuite
    from benchmark.suites.s06_gateway_async import GatewayAsyncSuite
    from benchmark.suites.s07_auth_rbac import AuthRbacSuite
    from benchmark.suites.s08_rate_limit import RateLimitSuite
    from benchmark.suites.s09_throughput import ThroughputSuite
    from benchmark.suites.s10_resilience import ResilienceSuite

    functional = [
        EventSourcingSuite(ctx),
        CdcPipelineSuite(ctx),
        AnomalyAccuracySuite(ctx),
        NlQuerySuite(ctx),
        SagaSuite(ctx),
        GatewayAsyncSuite(ctx),
        AuthRbacSuite(ctx),
        RateLimitSuite(ctx),
        ResilienceSuite(ctx),  # 导入 out/chaos-results.json（宿主机 chaos_run.sh 产物）
    ]
    load = [ThroughputSuite(ctx)]
    return {"functional": functional, "load": load}


def main() -> int:
    parser = argparse.ArgumentParser(description="EventGuard 评测器")
    parser.add_argument("--suites", choices=["functional", "load", "all"], default=None,
                        help="评测范围（默认取 BENCH_SUITES，再默认 functional）")
    parser.add_argument("--out", default=None, help="报告输出目录（默认 BENCH_OUT 或 /out）")
    parser.add_argument("--dry-run", action="store_true", help="仅 preflight 快速冒烟")
    args = parser.parse_args()

    cfg = Config()
    if args.suites:
        cfg.suites = args.suites
    if args.out:
        cfg.out_dir = args.out

    run_id = time.strftime("run-%Y%m%d-%H%M%S")
    cfg.run_id = run_id

    ctx = Context(cfg)
    start = time.time()
    result = RunResult(timestamp=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))

    try:
        preflight(ctx)
        if args.dry_run:
            print("[dry-run] preflight 通过，跳过套件。")
            result.mode = ctx.mode
            result.git_rev = _git_rev()
            return 0

        suites_by_phase = _load_suites(ctx)
        if cfg.suites == "all":
            phases = ["functional", "load"]
        elif cfg.suites == "load":
            phases = ["load"]
        else:
            phases = ["functional"]

        for phase in phases:
            for suite in suites_by_phase[phase]:
                # 负载套件在限流开启时 SKIPPED（文档化重启命令）
                if phase == "load" and ctx.mode.get("rate_limit"):
                    suite.result.status = "SKIPPED"
                    suite.result.conclusion = (
                        "限流开启（60/10s/IP）无法做高吞吐压测。请设置 .env 中 "
                        "EG_RATE_LIMIT_ENABLED=false 并 `docker compose up -d --build eventguard-server` "
                        "重启后再跑 `--suites load`。")
                    print(f"[{phase}] {suite.id} SKIPPED（限流开启）")
                    result.features.append(suite.result)
                    continue
                print(f"[{phase}] 运行 {suite.id} {suite.name} …")
                fr = suite.run()
                result.features.append(fr)
                print(f"  → {fr.id} {fr.status}（{fr.duration_seconds:.1f}s，"
                      f"{sum(1 for a in fr.assertions if a.passed)}/{len(fr.assertions)} 断言通过）")

        result.mode = ctx.mode
        result.git_rev = _git_rev()
        result.auth_events = ctx.auth.events
        result.chaos = load_chaos_results(cfg.out_dir)
        result.headline_kpis = _collect_kpis(result)
    finally:
        ctx.close()

    result.duration_seconds = time.time() - start
    md = report_gen.write_markdown(result, cfg.out_dir)
    js = report_gen.write_json(result, cfg.out_dir)
    print(f"[report] 已生成：\n  {md}\n  {js}")

    # —— 尝试渲染 HTML（matplotlib 可用时）——
    try:
        from benchmark.report import charts, html

        chart_paths = charts.render_all(result, cfg.out_dir)
        html.write_html(result, cfg.out_dir, chart_paths)
        print(f"  {Path(cfg.out_dir) / 'benchmark-report.html'}")
    except Exception as e:  # matplotlib 缺失或绘图失败不阻断 md/json
        print(f"[report] HTML 渲染跳过：{e}")

    failed = any(f.status == "FAIL" for f in result.features)
    return 1 if failed else 0


def _collect_kpis(result: RunResult) -> list[Kpi]:
    """从各功能套件 metrics 汇总 headline KPI。"""
    kpis: list[Kpi] = []
    by_id = {f.id: f for f in result.features}

    def add(key: str, value, unit: str = "", feature: str = "", method: str = "rest") -> None:
        if value is not None:
            kpis.append(Kpi(key, value, unit, feature, method))

    s02 = by_id.get("s02_cdc_pipeline")
    if s02:
        lat = s02.metrics.get("latency", {})
        add("event_commit_to_alert_p95_ms", lat.get("p95_ms"), "ms", "s02_cdc_pipeline", "rest")

    s03 = by_id.get("s03_anomaly_accuracy")
    if s03:
        overall = s03.metrics.get("overall", {})
        add("anomaly_detection_f1", overall.get("f1"), "", "s03_anomaly_accuracy",
            "rest+kafka_inject")
        add("anomaly_detection_precision", overall.get("precision"), "", "s03_anomaly_accuracy",
            "rest+kafka_inject")
        lat = s03.metrics.get("detection_latency", {})
        add("detection_latency_p95_ms", lat.get("p95_ms"), "ms", "s03_anomaly_accuracy",
            "rest+kafka_inject")

    s04 = by_id.get("s04_nl_query")
    if s04:
        add("nl_query_accuracy", s04.metrics.get("accuracy"), "", "s04_nl_query", "rest")
        lat = s04.metrics.get("latency", {})
        add("nl_query_p95_latency_ms", lat.get("p95_ms"), "ms", "s04_nl_query", "rest")

    s05 = by_id.get("s05_saga")
    if s05:
        add("saga_compensation_success_rate", s05.metrics.get("success_rate"), "", "s05_saga", "rest")
        lat = s05.metrics.get("e2e_latency", {})
        add("saga_e2e_latency_p95_ms", lat.get("p95_ms"), "ms", "s05_saga", "rest")

    s06 = by_id.get("s06_gateway_async")
    if s06:
        lat = s06.metrics.get("callback_roundtrip", {})
        add("gateway_async_callback_roundtrip_p95_ms", lat.get("p95_ms"), "ms", "s06_gateway_async", "rest")

    s07 = by_id.get("s07_auth_rbac")
    if s07:
        add("auth_rbac_matrix_pass_rate", s07.metrics.get("pass_rate"), "", "s07_auth_rbac", "rest")

    s08 = by_id.get("s08_rate_limit")
    if s08:
        add("rate_limit_429_enforced", s08.metrics.get("enforced"), "", "s08_rate_limit", "rest")
        add("rate_limit_measured_threshold", s08.metrics.get("measured_threshold"), "", "s08_rate_limit", "rest")

    s09 = by_id.get("s09_throughput")
    if s09:
        add("command_throughput_qps", s09.metrics.get("qps"), "qps", "s09_throughput", "rest")
        lat = s09.metrics.get("latency", {})
        add("command_p95_latency_ms", lat.get("p95_ms"), "ms", "s09_throughput", "rest")
        add("load_error_rate", s09.metrics.get("error_rate"), "", "s09_throughput", "rest")

    if result.chaos:
        for sc in result.chaos.get("scenarios", []):
            add(f"chaos_{sc.get('name')}_recovery_seconds", sc.get("recovery_seconds"), "s",
                "s10_resilience", "chaos")
            add(f"chaos_{sc.get('name')}_data_loss_events", sc.get("data_loss_events"), "",
                "s10_resilience", "chaos")
    return kpis


def _git_rev() -> str:
    try:
        import subprocess

        return subprocess.run(["git", "-C", str(Path(__file__).resolve().parents[1]),
                               "rev-parse", "--short", "HEAD"],
                              capture_output=True, text=True, timeout=3).stdout.strip() or ""
    except Exception:
        return ""


if __name__ == "__main__":
    sys.exit(main())
