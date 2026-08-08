"""AI 可观测性：轻量 trace 环形缓冲（Item 4）。

每个 AI 操作（nl_query / root_cause / llm_call / llm_cache 命中）记录一条结构化 trace，
前端/调试经 GET /ai/traces/recent 取最近 N 条。纯内存，进程重启即清。
"""
import threading
import time
from collections import deque
from typing import Any

TRACE_MAX = 200


class TraceLog:
    def __init__(self, maxlen: int = TRACE_MAX):
        self._buf: deque[dict] = deque(maxlen=maxlen)
        self._lock = threading.Lock()

    def record(self, operation: str, **fields: Any) -> None:
        entry: dict[str, Any] = {"ts": time.time(), "operation": operation}
        entry.update(fields)
        with self._lock:
            self._buf.appendleft(entry)

    def recent(self, limit: int = 100) -> list[dict]:
        with self._lock:
            return list(self._buf)[:limit]

    def clear(self) -> None:
        with self._lock:
            self._buf.clear()

    def size(self) -> int:
        with self._lock:
            return len(self._buf)


trace_log = TraceLog()
