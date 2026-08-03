"""自包含 HTML 报告：KPI/断言/指标表 + base64 内嵌图表 PNG，可离线打开（简历/作品集）。"""
from __future__ import annotations

import base64
from pathlib import Path

from .model import RunResult


def _img_tag(path: Path) -> str:
    data = base64.b64encode(path.read_bytes()).decode("ascii")
    return f'<img src="data:image/png;base64,{data}" alt="{path.stem}" style="max-width:100%;border:1px solid #e4e7ed;border-radius:6px;"/>'


def _fmt(v) -> str:
    if isinstance(v, float):
        return f"{v:.3f}" if v != int(v) else str(int(v))
    return str(v)


def render_html(result: RunResult, chart_paths: list[tuple[str, Path]]) -> str:
    rows: list[str] = []
    rows.append("<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'/>")
    rows.append("<meta name='viewport' content='width=device-width,initial-scale=1'/>")
    rows.append("<title>EventGuard 评测报告</title>")
    rows.append("<style>body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;"
                "max-width:1000px;margin:0 auto;padding:24px;background:#f5f7fa;color:#303133;}")
    rows.append("h1{font-size:24px}h2{font-size:19px;border-bottom:2px solid #409eff;padding-bottom:6px;margin-top:32px}")
    rows.append("table{border-collapse:collapse;width:100%;margin:8px 0;background:#fff;font-size:13px}")
    rows.append("th,td{border:1px solid #e4e7ed;padding:6px 10px;text-align:left}")
    rows.append("th{background:#ecf5ff}.ok{color:#67c23a;font-weight:600}.fail{color:#f56c6c;font-weight:600}")
    rows.append(".card{background:#fff;border:1px solid #e4e7ed;border-radius:8px;padding:16px;margin:12px 0}")
    rows.append("pre{background:#f8f8f8;padding:10px;border-radius:4px;overflow-x:auto;font-size:12px}")
    rows.append(".kpi{display:inline-block;background:#fff;border:1px solid #e4e7ed;border-radius:8px;"
                "padding:10px 14px;margin:6px;min-width:200px}")
    rows.append(".kpi b{display:block;font-size:20px;color:#409eff}.kpi span{font-size:12px;color:#909399}")
    rows.append("</style></head><body>")

    rows.append(f"<h1>EventGuard 评测报告</h1>")
    rows.append(f"<p>运行时间 <b>{result.timestamp}</b>｜耗时 <b>{result.duration_seconds:.1f}s</b>｜"
                f"git <b>{result.git_rev}</b>｜LLM {result.mode.get('llm')}｜限流 "
                f"{'开' if result.mode.get('rate_limit') else '关'}｜支付回调延迟 "
                f"{result.mode.get('payment_delay_ms')}ms</p>")

    # KPI 卡片
    if result.headline_kpis:
        rows.append("<h2>摘要 KPI</h2>")
        for k in result.headline_kpis:
            rows.append(f"<div class='kpi'><b>{_fmt(k.value)}{k.unit}</b><span>{k.key} · {k.feature}</span></div>")
        rows.append("<p style='clear:both'><small>方法：%s</small></p>" %
                    "；".join(f"{k.key}={k.method}" for k in result.headline_kpis[:6]))

    # 图表
    if chart_paths:
        rows.append("<h2>图表</h2>")
        for title, p in chart_paths:
            rows.append(f"<p><b>{title}</b><br/>{_img_tag(p)}</p>")

    # 逐功能
    rows.append("<h2>逐功能评测</h2>")
    for f in result.features:
        emoji = "✅" if f.status == "PASS" else ("⏭️" if f.status == "SKIPPED" else ("⏸️" if f.status == "NOT_RUN" else "❌"))
        rows.append(f"<div class='card'><h3>{emoji} {f.id} · {f.name}"
                    f" <small>({f.status}，{f.duration_seconds:.1f}s)</small></h3>")
        if f.method_notes:
            rows.append(f"<p style='color:#909399;font-size:12px'>方法注记：{'；'.join(f.method_notes)}</p>")
        if f.assertions:
            rows.append("<table><tr><th>断言</th><th>通过</th><th>期望</th><th>实际</th><th>方法</th></tr>")
            for a in f.assertions:
                cls = "ok" if a.passed else "fail"
                mark = "✅" if a.passed else "❌"
                rows.append(f"<tr><td>{a.id} {a.description}</td><td class='{cls}'>{mark}</td>"
                            f"<td>{a.expected}</td><td>{a.actual}</td><td>{a.method}</td></tr>")
            rows.append("</table>")
        if f.metrics:
            import json
            rows.append(f"<details><summary>指标（JSON）</summary><pre>{json.dumps(f.metrics, ensure_ascii=False, indent=2)}</pre></details>")
        if f.conclusion:
            rows.append(f"<p><b>结论：</b>{f.conclusion}</p>")
        rows.append("</div>")

    # 混沌
    if result.chaos.get("scenarios"):
        rows.append("<h2>韧性（混沌）</h2><table><tr><th>场景</th><th>恢复(s)</th><th>数据丢失</th><th>通过</th></tr>")
        for sc in result.chaos["scenarios"]:
            rows.append(f"<tr><td>{sc.get('name')}</td><td>{sc.get('recovery_seconds')}</td>"
                        f"<td>{sc.get('data_loss_events')}</td><td>{'✅' if sc.get('pass') else '❌'}</td></tr>")
        rows.append("</table>")

    rows.append("<p style='color:#909399;font-size:12px'>报告由 EventGuard 评测器自动生成；"
                "每条断言 method 字段标明驱动方式（rest / kafka_inject / db_assert / chaos）。</p>")
    rows.append("</body></html>")
    return "\n".join(rows)


def write_html(result: RunResult, out_dir: str | Path, chart_paths: list[tuple[str, Path]]) -> Path:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    path = out / "benchmark-report.html"
    path.write_text(render_html(result, chart_paths), encoding="utf-8")
    return path
