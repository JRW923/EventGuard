"""多轮对话会话存储（内存态，仿 anomaly_store 的 MVP 模式）。

key = conversation_id（UUID）。TTL + 上限淘汰，进程重启即清——与全项目"进程内状态不落盘"的一致取舍
（已知上限，升级路径与 anomaly_store 相同：落 Redis / DB）。
"""
import threading
import time
import uuid
from typing import Any, Optional

# 会话 30 分钟无交互即过期；上限 512 条，超限按最近访问时间淘汰
CONVERSATION_TTL_SECONDS = 1800
CONVERSATION_MAX = 512


class Conversation:
    """单会话状态。"""

    def __init__(self, conversation_id: str):
        self.conversation_id = conversation_id
        # 待补充参数：{param: 参数类型}，如 {"order_id": "uuid"}
        self.pending: dict[str, str] = {}
        # 已解析参数上下文：{param: 值}，用于追问补参 / 指代消解
        self.context: dict[str, Any] = {}
        # 对话历史（最近 20 条，供前端续聊与未来 LLM 上下文注入）
        self.history: list[dict] = []
        self.created_at = time.time()
        self.updated_at = time.time()

    def touch(self) -> None:
        self.updated_at = time.time()


class ConversationStore:
    """线程安全会话表。"""

    def __init__(self, max_size: int = CONVERSATION_MAX, ttl: int = CONVERSATION_TTL_SECONDS):
        self._store: dict[str, Conversation] = {}
        self._lock = threading.Lock()
        self._max_size = max_size
        self._ttl = ttl

    def get_or_create(self, conversation_id: Optional[str]) -> Conversation:
        """取会话；conversation_id 为空或不存在则新建（UUID），始终返回有效会话。"""
        with self._lock:
            self._evict_expired()
            if conversation_id is None or conversation_id not in self._store:
                cid = conversation_id or str(uuid.uuid4())
                conv = Conversation(cid)
                if len(self._store) >= self._max_size:
                    self._evict_lru(1)  # 先腾位再插入，保持容量上限
                self._store[cid] = conv
                return conv
            conv = self._store[conversation_id]
            conv.touch()
            return conv

    def get(self, conversation_id: str) -> Optional[Conversation]:
        with self._lock:
            self._evict_expired()
            return self._store.get(conversation_id)

    def clear(self) -> None:
        with self._lock:
            self._store.clear()

    def size(self) -> int:
        with self._lock:
            return len(self._store)

    def _evict_expired(self) -> None:
        now = time.time()
        expired = [cid for cid, c in self._store.items() if now - c.updated_at > self._ttl]
        for cid in expired:
            del self._store[cid]

    def _evict_lru(self, count: int = 1) -> None:
        """按最近访问时间淘汰最旧的 count 个会话。"""
        ordered = sorted(self._store.items(), key=lambda kv: kv[1].updated_at)
        for cid, _ in ordered[:count]:
            del self._store[cid]


conversation_store = ConversationStore()
