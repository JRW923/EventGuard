"""告警去重 + 风暴抑制（Item 2）。

问题背景：流程级检测（P001/P002/P003）每来一个事件就重跑整窗，同一异常会随窗口推进被重复
save+publish；事件级规则/IF 命中也可能反复。本模块在发布前做两道门控，均只影响发布、不改变
检测触发语义（保证 s03 按 rule_id/aggregate_id 的断言口径不变）：

1. 幂等去重：同一 (rule_id, aggregate_id, fingerprint) 在 TTL 内只发布一次。
   - 事件级用 event_id 做 fingerprint（不同事件永不误去重）
   - 流程级用 description（P001 描述含迁移对、P002 含停滞状态，窗口内稳定）
2. 风暴抑制：同一 (rule_id, aggregate_id) 每分钟最多发布 storm_limit 次，防突发刷屏。

已知上限（ponytail）：TTL 过期后同一持续性异常会周期性重发（由风暴抑制兜底限频）；
升级路径=更长的去重窗口 / 按异常"已处置"状态解除去重。
"""
import threading
import time
from collections import defaultdict, deque
from typing import Optional


class AlertDeduper:
    """线程安全的告警发布门控。"""

    def __init__(
        self,
        ttl_seconds: int = 300,
        max_entries: int = 10_000,
        storm_limit: int = 3,
        storm_window: int = 60,
    ):
        self._ttl = ttl_seconds
        self._max = max_entries
        self._storm_limit = storm_limit
        self._storm_window = storm_window
        self._lock = threading.Lock()
        # signature -> 最近发布时间（LRU 淘汰用）
        self._published: dict[tuple, float] = {}
        # (rule_id, aggregate_id) -> 窗口内发布时间戳队列
        self._recent_counts: dict[tuple, deque] = defaultdict(deque)

    def should_publish(self, rule_id: Optional[str], aggregate_id: Optional[str], fingerprint: str) -> str:
        """返回 'publish' / 'dup' / 'suppressed' 三态。"""
        sig = (rule_id or "?", aggregate_id or "?", fingerprint or "")
        now = time.time()
        with self._lock:
            self._evict(now)
            if sig in self._published:
                return "dup"
            key = (rule_id or "?", aggregate_id or "?")
            bucket = self._recent_counts[key]
            while bucket and now - bucket[0] > self._storm_window:
                bucket.popleft()
            if len(bucket) >= self._storm_limit:
                return "suppressed"
            bucket.append(now)
            self._published[sig] = now
            self._trim(now)
            return "publish"

    def _evict(self, now: float) -> None:
        expired = [s for s, ts in self._published.items() if now - ts > self._ttl]
        for s in expired:
            del self._published[s]
        # 清理已空的风暴桶，避免 defaultdict 无限增长
        empty = [k for k, dq in list(self._recent_counts.items()) if not dq]
        for k in empty:
            del self._recent_counts[k]

    def _trim(self, now: float) -> None:
        """超上限按最近发布时间最旧淘汰（LRU）。"""
        if len(self._published) > self._max:
            ordered = sorted(self._published.items(), key=lambda kv: kv[1])
            for s, _ in ordered[: len(self._published) - self._max]:
                del self._published[s]

    def clear(self) -> None:
        with self._lock:
            self._published.clear()
            self._recent_counts.clear()
