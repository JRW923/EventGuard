"""ConversationStore 会话存储测试：建/取/上限淘汰。"""
from app.query.conversation_store import ConversationStore


def test_get_or_create_new_and_reuse():
    store = ConversationStore(max_size=10, ttl=3600)
    c1 = store.get_or_create(None)
    assert c1.conversation_id
    # 相同 id 复用同一会话
    c2 = store.get_or_create(c1.conversation_id)
    assert c2 is c1
    # 无 id 每次新建
    c3 = store.get_or_create(None)
    assert c3.conversation_id != c1.conversation_id
    assert store.size() == 2


def test_evict_over_max_lru():
    store = ConversationStore(max_size=2, ttl=3600)
    a = store.get_or_create("a")
    b = store.get_or_create("b")
    # 访问 b 使其更"新"
    store.get_or_create("b").touch()
    c = store.get_or_create("c")
    # 超限后淘汰最旧的 a
    assert store.size() == 2
    assert store.get("a") is None
    assert store.get("b") is not None
    assert store.get("c") is not None


def test_clear():
    store = ConversationStore(max_size=10, ttl=3600)
    store.get_or_create("a")
    store.clear()
    assert store.size() == 0
