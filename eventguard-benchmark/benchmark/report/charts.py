"""matplotlib 图表：从 RunResult metrics 生成 PNG（HTML 报告内嵌用，Agg 无头后端）。"""
from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

from .model import RunResult

plt.rcParams["font.sans-serif"] = ["DejaVu Sans", "Noto Sans CJK SC", "WenQuanYi Micro Hei"]
plt.rcParams["axes.unicode_minus"] = False


def _lat_bar(ax, data: dict[str, dict], title: str, unit: str = "ms") -> None:
    """data = {feature_label: {p50_ms,p95_ms,p99_ms,...}} → 分组柱状。"""
    labels = list(data.keys())
    series = {p: [v.get(p) or 0 for v in data.values()] for p in ("p50_ms", "p95_ms", "p99_ms")}
    x = range(len(labels))
    width = 0.25
    colors = {"p50_ms": "#67c23a", "p95_ms": "#e6a23c", "p99_ms": "#f56c6c"}
    for i, (p, vals) in enumerate(series.items()):
        ax.bar([xi + i * width for xi in x], vals, width, label=p, color=colors[p])
    ax.set_xticks([xi + width for xi in x])
    ax.set_xticklabels(labels, rotation=15, fontsize=8)
    ax.set_ylabel(unit)
    ax.set_title(title)
    ax.legend(fontsize=8)
    ax.grid(axis="y", alpha=0.3)


def _bar(ax, labels, values, title, ylabel, colors=None) -> None:
    ax.bar(labels, values, color=colors or "#409eff")
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.grid(axis="y", alpha=0.3)


def render_all(result: RunResult, out_dir: str | Path) -> list[tuple[str, Path]]:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    charts: list[tuple[str, Path]] = []
    by_id = {f.id: f for f in result.features}

    # 1. 延迟分位对比
    lat_data: dict[str, dict] = {}
    mapping = [
        ("s02_cdc_pipeline", "CDC 捕获", "cdc_capture_latency_ms"),
        ("s03_anomaly_accuracy", "异常检测", "detection_latency_ms"),
        ("s04_nl_query", "NL 查询", "latency_ms"),
        ("s06_gateway_async", "支付回调", "callback_roundtrip_ms"),
        ("s09_throughput", "负载写路径", "latency_ms"),
    ]
    for fid, label, key in mapping:
        m = by_id.get(fid)
        if m and key in m.metrics and m.metrics[key]:
            lat_data[label] = m.metrics[key]
    if lat_data:
        fig, ax = plt.subplots(figsize=(8, 4))
        _lat_bar(ax, lat_data, "各链路延迟分位（ms）")
        fig.tight_layout()
        p = out / "latency.png"
        fig.savefig(p, dpi=120)
        plt.close(fig)
        charts.append(("各链路延迟分位（ms）", p))

    # 2. 异常检测逐规则
    s3 = by_id.get("s03_anomaly_accuracy")
    if s3 and s3.metrics.get("per_rule"):
        per = s3.metrics["per_rule"]
        rules = list(per.keys())
        hit = [per[r]["hit"] for r in rules]
        exp = [per[r]["expected"] for r in rules]
        fig, ax = plt.subplots(figsize=(8, 4))
        x = range(len(rules))
        ax.bar([i - 0.18 for i in x], exp, 0.36, label="expected", color="#c0c4cc")
        ax.bar([i + 0.18 for i in x], hit, 0.36, label="hit", color="#409eff")
        ax.set_xticks(list(x))
        ax.set_xticklabels(rules)
        ax.set_ylabel("count")
        ax.set_title("异常检测逐规则命中（expected vs hit）")
        ax.legend()
        ax.grid(axis="y", alpha=0.3)
        fig.tight_layout()
        p = out / "anomaly-per-rule.png"
        fig.savefig(p, dpi=120)
        plt.close(fig)
        charts.append(("异常检测逐规则命中", p))

    # 3. 吞吐
    s9 = by_id.get("s09_throughput")
    if s9 and s9.metrics.get("qps"):
        fig, ax = plt.subplots(figsize=(6, 3.6))
        m = s9.metrics
        _bar(ax, ["QPS", "错误率%"], [m.get("qps", 0), (m.get("error_rate") or 0) * 100],
             f"稳态 {m.get('concurrency', '?')} 并发吞吐", "值")
        fig.tight_layout()
        p = out / "throughput.png"
        fig.savefig(p, dpi=120)
        plt.close(fig)
        charts.append(("吞吐 / 错误率", p))

    # 4. 混沌恢复时间
    if result.chaos.get("scenarios"):
        sc = result.chaos["scenarios"]
        names = [s.get("name", "?") for s in sc]
        rec = [float(s.get("recovery_seconds") or 0) for s in sc]
        fig, ax = plt.subplots(figsize=(6, 3.6))
        _bar(ax, names, rec, "混沌恢复时间（s）", "seconds",
             ["#67c23a" if s.get("pass") else "#f56c6c" for s in sc])
        fig.tight_layout()
        p = out / "chaos-recovery.png"
        fig.savefig(p, dpi=120)
        plt.close(fig)
        charts.append("混沌恢复时间", p)

    return charts
