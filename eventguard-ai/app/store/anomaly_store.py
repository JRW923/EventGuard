"""异常存储（MVP 内存版）：供根因分析通过 anomaly_id 查询"""

import threading
from typing import Optional

from app.model.anomaly import Anomaly


class AnomalyStore:
    """线程安全的内存异常存储"""

    def __init__(self):
        self._store: dict[str, Anomaly] = {}
        self._lock = threading.Lock()

    def save(self, anomaly: Anomaly) -> None:
        with self._lock:
            self._store[anomaly.anomaly_id] = anomaly

    def get(self, anomaly_id: str) -> Optional[Anomaly]:
        with self._lock:
            return self._store.get(anomaly_id)

    def clear(self) -> None:
        with self._lock:
            self._store.clear()


# 全局单例
anomaly_store = AnomalyStore()
