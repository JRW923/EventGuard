"""报告渲染：RunResult → benchmark-report.json / benchmark-report.md（charts/html 在 charts.py/html.py）。"""
from __future__ import annotations

import json
from pathlib import Path

from .model import RunResult


def write_json(result: RunResult, out_dir: str | Path) -> Path:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    path = out / "benchmark-report.json"
    path.write_text(json.dumps(result.to_dict(), ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def _mode_line(mode: dict) -> str:
    parts = [
        f"LLM 模式：{mode.get('llm', 'unknown')}",
        f"限流：{'开启' if mode.get('rate_limit') else '关闭'}",
        f"支付回调延迟：{mode.get('payment_delay_ms', '?')}ms",
        f"Saga 自动补偿：{'开启' if mode.get('saga') else '关闭'}",
    ]
    return "，".join(parts)


def _fmt_kpi_value(value) -> str:
    if isinstance(value, float):
        return f"{value:.2f}"
    if isinstance(value, dict):
        return json.dumps(value, ensure_ascii=False)
    return str(value)


def render_markdown(result: RunResult) -> str:
    lines: list[str] = []
    lines.append("# EventGuard 评测报告（bench）")
    lines.append("")
    lines.append(f"> 运行时间：{result.timestamp}｜耗时：{result.duration_seconds:.1f}s｜git：{result.git_rev}")
    lines.append(f"> {_mode_line(result.mode)}")
    lines.append("")

    # —— 摘要 KPI ——
    lines.append("## 摘要 KPI")
    lines.append("")
    if result.headline_kpis:
        lines.append("| 指标 | 值 | 单位 | 来源 | 方法 |")
        lines.append("| --- | --- | --- | --- | --- |")
        for k in result.headline_kpis:
            lines.append(f"| {k.key} | {_fmt_kpi_value(k.value)} | {k.unit} | {k.feature} | {k.method} |")
    else:
        lines.append("（无 headline KPI）")
    lines.append("")

    # —— 混沌（若导入）——
    if result.chaos:
        lines.append("## 韧性（混沌实验）")
        lines.append("")
        for sc in result.chaos.get("scenarios", []):
            lines.append(f"- **{sc.get('name')}**：恢复 {sc.get('recovery_seconds')}s，"
                         f"数据丢失 {sc.get('data_loss_events')}，{'✅' if sc.get('pass') else '❌'}")
        lines.append("")

    # —— 逐功能 ——
    lines.append("## 逐功能评测")
    lines.append("")
    for f in result.features:
        emoji = "✅" if f.status == "PASS" else ("⏭️" if f.status == "SKIPPED" else "❌")
        lines.append(f"### {emoji} {f.id} · {f.name}（{f.status}，{f.duration_seconds:.1f}s）")
        lines.append("")
        if f.method_notes:
            lines.append("> 方法注记：" + "；".join(f.method_notes))
            lines.append("")
        if f.assertions:
            lines.append("| 断言 | 通过 | 期望 | 实际 | 方法 |")
            lines.append("| --- | --- | --- | --- | --- |")
            for a in f.assertions:
                mark = "✅" if a.passed else "❌"
                lines.append(f"| {a.id} {a.description} | {mark} | {a.expected} | {a.actual} | {a.method} |")
            lines.append("")
        if f.metrics:
            lines.append("**指标：**")
            lines.append("")
            lines.append("```json")
            lines.append(json.dumps(f.metrics, ensure_ascii=False, indent=2))
            lines.append("```")
            lines.append("")
        if f.conclusion:
            lines.append(f"**结论：** {f.conclusion}")
            lines.append("")
    lines.append("---")
    lines.append("> 报告由 `eventguard-benchmark` 评测器自动生成；每条断言的 `method` 字段标明驱动方式"
                 "（rest=真实 HTTP 命令路径 / kafka_inject=合成事件注入 Kafka / db_assert=数据库断言 / chaos=混沌实验）。")
    return "\n".join(lines)


def write_markdown(result: RunResult, out_dir: str | Path) -> Path:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    path = out / "benchmark-report.md"
    path.write_text(render_markdown(result), encoding="utf-8")
    return path
