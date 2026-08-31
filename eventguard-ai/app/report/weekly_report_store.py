"""运营周报持久化：JSONL 落挂载卷（/data，ai-data 卷），支持历史查询与短时缓存。

背景：周报原来每次生成都重新调 LLM，既不落库也无法查看历史（进程重启即丢）。
本存储与 AnomalyStore 同模式：内存 + 可选 JSONL 文件持久化，线程安全，损坏行跳过。
配置：
- EG_WEEKLY_REPORT_STORE_PATH：持久化路径（compose 指向 /data/weekly_reports.jsonl）
- EG_WEEKLY_REPORT_CACHE_HOURS：同周期周报免重生成的缓存窗口（默认 6h）
"""
import json
import logging
import os
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

_DEFAULT_PATH = os.environ.get("EG_WEEKLY_REPORT_STORE_PATH", "")
CACHE_FRESH_HOURS = float(os.environ.get("EG_WEEKLY_REPORT_CACHE_HOURS", "6"))
MAX_ENTRIES = int(os.environ.get("EG_WEEKLY_REPORT_MAX", "50"))


class WeeklyReportStore:
    """线程安全周报存储。persist_path 为空则不落盘（纯内存语义）。"""

    def __init__(self, persist_path: Optional[str] = None, max_entries: int = MAX_ENTRIES):
        self._reports: list[dict] = []
        self._lock = threading.Lock()
        self._max = max_entries
        self.persist_path = persist_path or _DEFAULT_PATH or None
        if self.persist_path:
            Path(self.persist_path).parent.mkdir(parents=True, exist_ok=True)
            self._load()

    def save(self, report: dict) -> dict:
        """给报告附上 generated_at（已有则保留）并保存（内存 + 追加落盘）。"""
        report = {
            **report,
            "generated_at": report.get("generated_at") or datetime.now(timezone.utc).isoformat(),
        }
        with self._lock:
            self._reports.append(report)
            self._trim()
            if self.persist_path:
                try:
                    with open(self.persist_path, "a", encoding="utf-8") as f:
                        f.write(json.dumps(report, ensure_ascii=False) + "\n")
                except OSError as e:
                    logger.warning("周报持久化失败（不影响本次返回）：%s", e)
        return report

    def find_cached(self, days: int) -> Optional[dict]:
        """最近 CACHE_FRESH_HOURS 内同 days 的报告 → 命中缓存。

        报告按时间正序追加，倒序遍历；遇到更早的同 days 报告已过期即停。
        """
        if not self._reports:
            return None
        now = datetime.now(timezone.utc)
        for r in reversed(self._reports):
            if r.get("period", {}).get("days") != days:
                continue
            try:
                generated = datetime.fromisoformat(r["generated_at"])
            except (KeyError, ValueError):
                continue
            if (now - generated).total_seconds() < CACHE_FRESH_HOURS * 3600:
                return r
            break  # 更早的同周期报告必然更旧
        return None

    def history(self, limit: int = 20) -> list[dict]:
        """最近的报告，按生成时间倒序（新在前）。"""
        with self._lock:
            return list(reversed(self._reports[-limit:]))

    def size(self) -> int:
        with self._lock:
            return len(self._reports)

    # ---------------- 持久化 ----------------

    def _load(self) -> None:
        path = Path(self.persist_path)
        if not path.exists():
            return
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                self._reports.append(json.loads(line))
            except json.JSONDecodeError:
                continue  # 单行损坏跳过，不阻断恢复

    def _trim(self) -> None:
        if len(self._reports) > self._max:
            self._reports = self._reports[-self._max:]


# 模块级单例，与 anomaly_store 同模式
weekly_report_store = WeeklyReportStore()
