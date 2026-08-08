"""按 aggregate_id 维护滑动窗口（最近 N 事件）"""

from collections import defaultdict, deque
import time
from typing import Optional


class EventWindow:
    """每个 aggregate_id 维护一个最近 window_size 事件的 deque

    ponytail: 内存滑动窗口只增不减、不落盘，aggregate 数无上限(defaultdict 永不清理)；
    升级路径=LRU/滑动过期或落 Redis。
    """

    def __init__(self, window_size: int = 20, max_aggregates: int = 10_000):
        self.window_size = window_size
        self.max_aggregates = max_aggregates
        self._windows: dict[str, deque] = defaultdict(lambda: deque(maxlen=window_size))
        self._last_seen: dict[str, float] = {}

    def add(self, event: dict) -> None:
        """添加事件到对应 aggregate 的窗口"""
        agg_id = event.get("aggregate_id", "")
        self._windows[agg_id].append(event)
        self._last_seen[agg_id] = time.time()
        if len(self._windows) > self.max_aggregates:
            oldest = min(self._last_seen, key=self._last_seen.get)
            self._windows.pop(oldest, None)
            self._last_seen.pop(oldest, None)

    def get(self, aggregate_id: str) -> list[dict]:
        """获取该 aggregate 的窗口事件列表（按时间顺序）"""
        return list(self._windows.get(aggregate_id, []))

    def clear(self, aggregate_id: Optional[str] = None) -> None:
        """清除窗口"""
        if aggregate_id:
            self._windows.pop(aggregate_id, None)
            self._last_seen.pop(aggregate_id, None)
        else:
            self._windows.clear()
            self._last_seen.clear()
