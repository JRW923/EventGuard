"""LLM 响应缓存（Item 4）。纯内存 TTL + LRU，key 为 (provider, model, temperature, prompt) 哈希。

只缓存幂等读场景（generate 的意图分类 / NL 润色）；根因分析等可解释性场景默认不缓存（由调用方决定）。
进程内状态，重启即清——与全项目一致；升级路径=落 Redis 共享缓存。
"""
import threading
import time
from typing import Optional


class LLMCache:
    def __init__(self, ttl: int = 300, max_size: int = 256):
        self._ttl = ttl
        self._max = max_size
        self._lock = threading.Lock()
        # key -> (stored_at, value)
        self._data: dict[str, tuple[float, str]] = {}

    @staticmethod
    def _key(provider: str, model: str, temperature: float, prompt: str) -> str:
        return f"{provider}|{model}|{temperature}|{hash(prompt)}"

    def get(self, provider: str, model: str, temperature: float, prompt: str) -> Optional[str]:
        key = self._key(provider, model, temperature, prompt)
        with self._lock:
            item = self._data.get(key)
            if item is None:
                return None
            stored_at, value = item
            if time.time() - stored_at > self._ttl:
                del self._data[key]
                return None
            return value

    def set(self, provider: str, model: str, temperature: float, prompt: str, value: str) -> None:
        key = self._key(provider, model, temperature, prompt)
        with self._lock:
            self._data[key] = (time.time(), value)
            # LRU：超上限淘汰最旧的
            if len(self._data) > self._max:
                oldest_key = min(self._data, key=lambda k: self._data[k][0])
                del self._data[oldest_key]

    def clear(self) -> None:
        with self._lock:
            self._data.clear()

    def size(self) -> int:
        with self._lock:
            return len(self._data)


llm_cache = LLMCache()
