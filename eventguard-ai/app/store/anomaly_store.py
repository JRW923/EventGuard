"""异常存储：内存表 + 可选 JSONL 文件持久化（Item 7 引入）。

- save() 追加写 JSONL（配置 EG_ANOMALY_STORE_PATH 时），进程重启可恢复历史，供周报/相似案例检索。
- list_recent(since, limit) 按 detected_at 倒序取最近异常，供运营周报聚合。
- 上限 max_entries（默认 10k）按 detected_at 最旧淘汰，防止长进程内存只增不减。
"""

import os
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from app.model.anomaly import Anomaly

_DEFAULT_PATH = os.environ.get("EG_ANOMALY_STORE_PATH", "")


class AnomalyStore:
    """线程安全异常存储。persist_path 为空则不落盘（保持 MVP 纯内存语义）。"""

    def __init__(self, persist_path: Optional[str] = None, max_entries: int = 10_000):
        self._store: dict[str, Anomaly] = {}
        self._lock = threading.Lock()
        self._max = max_entries
        self.persist_path = persist_path or _DEFAULT_PATH or None
        if self.persist_path:
            Path(self.persist_path).parent.mkdir(parents=True, exist_ok=True)
            self._load()

    def save(self, anomaly: Anomaly) -> None:
        with self._lock:
            self._store[anomaly.anomaly_id] = anomaly
            self._trim()
            if self.persist_path:
                self._append_line(anomaly)

    def get(self, anomaly_id: str) -> Optional[Anomaly]:
        with self._lock:
            return self._store.get(anomaly_id)

    def clear(self) -> None:
        with self._lock:
            self._store.clear()
            if self.persist_path:
                try:
                    Path(self.persist_path).unlink(missing_ok=True)
                except OSError:
                    pass

    def list_recent(self, since: Optional[str] = None, limit: int = 100) -> list[Anomaly]:
        """按 detected_at 倒序取最近异常。since 为 ISO 时间串（含起）。"""
        with self._lock:
            items = sorted(self._store.values(), key=lambda a: a.detected_at, reverse=True)
            if since:
                items = [a for a in items if a.detected_at >= since]
            return items[:limit]

    def size(self) -> int:
        with self._lock:
            return len(self._store)

    # ---------------- 持久化 ----------------

    def _load(self) -> None:
        path = Path(self.persist_path)
        if not path.exists():
            return
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                a = Anomaly.model_validate_json(line)
                self._store[a.anomaly_id] = a
            except Exception:
                continue  # 单行损坏跳过，不阻断恢复

    def _append_line(self, anomaly: Anomaly) -> None:
        try:
            with open(self.persist_path, "a", encoding="utf-8") as f:
                f.write(anomaly.model_dump_json() + "\n")
        except OSError:
            pass  # 持久化失败不阻断检测/发布

    def _trim(self) -> None:
        if len(self._store) <= self._max:
            return
        # 按 detected_at 最旧淘汰（ISO 串可字典序比较）
        oldest_ids = sorted(self._store, key=lambda k: self._store[k].detected_at)[
            : len(self._store) - self._max
        ]
        for aid in oldest_ids:
            del self._store[aid]


# 全局单例（默认不落盘，保持向后兼容；配置 EG_ANOMALY_STORE_PATH 后启用持久化）
anomaly_store = AnomalyStore()
