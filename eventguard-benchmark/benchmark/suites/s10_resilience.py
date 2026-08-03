"""s10 韧性：导入宿主机 chaos_run.sh 产出的 out/chaos-results.json（bench 容器无 docker.sock）。

文件缺失时记 NOT_RUN 并给出宿主机运行指引，不判 FAIL。
"""
from __future__ import annotations

import json
from pathlib import Path

from .base import Suite


class ResilienceSuite(Suite):
    id = "s10_resilience"
    name = "韧性（混沌：PG 崩溃 / Kafka 暂停 / AI 延迟）"

    def execute(self) -> None:
        path = Path(self.ctx.cfg.out_dir) / "chaos-results.json"
        if not path.exists():
            self.result.status = "NOT_RUN"
            self.result.conclusion = (
                "未找到 chaos-results.json。请先在宿主机运行："
                "`bash eventguard-benchmark/chaos_run.sh`（需要 Docker 访问权限），再重跑本评测以合并韧性数据。"
            )
            return
        try:
            scenarios = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError) as e:
            self.result.status = "FAIL"
            self.result.conclusion = f"chaos-results.json 解析失败：{e}"
            return

        for sc in scenarios:
            name = sc.get("name", "?")
            recovery = sc.get("recovery_seconds")
            data_loss = sc.get("data_loss_events")
            ok = sc.get("pass", False)
            note = sc.get("note", "")
            if name == "db-kill":
                self.add("chaos_db_kill", "PG 崩溃数据零丢失 + 命令端恢复", bool(ok),
                         expected="0 数据丢失", actual=f"恢复 {recovery}s / 丢失 {data_loss}",
                         method="chaos")
                self.add("chaos_db_kill_zero_loss", "domain_events 行数不变", int(data_loss or 0) == 0,
                         expected="0", actual=str(data_loss), method="chaos")
            elif name == "kafka-pause":
                self.add("chaos_kafka_pause", "Kafka 暂停期间命令端可写", bool(ok),
                         expected="POST /orders 200", actual=f"恢复 {recovery}s", method="chaos")
            elif name == "ai-delay":
                self.add("chaos_ai_delay", "AI 延迟时规则引擎兜底可用", bool(ok),
                         expected="规则评估 200", actual=note or str(ok), method="chaos")

        self.result.metrics = {
            "scenarios": [
                {"name": s.get("name"), "recovery_seconds": s.get("recovery_seconds"),
                 "data_loss_events": s.get("data_loss_events"), "pass": s.get("pass")}
                for s in scenarios
            ]
        }
        all_pass = all(s.get("pass") for s in scenarios)
        self.result.conclusion = (
            "混沌实验：PG 崩溃/Kafka 暂停/AI 延迟均符合预期降级与恢复。"
            if all_pass else "存在未通过的混沌场景，详见断言。"
        )
