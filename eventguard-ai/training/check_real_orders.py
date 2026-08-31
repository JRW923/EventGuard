"""真实订单预测冒烟检查：用生产订单事件流验证模型没塌向单一类别、特征没整体越界。

用法（宿主机，server 8080 已映射）：
    EG_SERVER_BASE_URL=http://localhost:8080 EG_MACHINE_API_KEY=xxx \
        python -m training.check_real_orders
容器内（server_base_url 默认即 eventguard-server:8080）：
    docker compose exec eventguard-ai python -m training.check_real_orders

为什么需要它：train_predict 与 evaluate_predict 共用同一套合成数据（generate_rows），
分布内准确率再高也发现不了「合成数据没跟上真实事件流」这类问题——它在生产只表现为
预测塌成常数，日志里没有任何报错。本脚本用真实数据做回归检查，出问题就 exit 1。
"""
import json
import sys
import urllib.request
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import numpy as np  # noqa: E402

from app.config import TERMINAL_ORDER_STATUSES, settings  # noqa: E402
from app.predictor.order_predictor import OrderPredictor  # noqa: E402
from app.store.event_store_client import EventStoreClient  # noqa: E402
from training.train_predict import generate_rows  # noqa: E402

FEATURES = ["e0_code", "e1_code", "e2_code", "amount", "gap01", "gap12", "seen", "last"]


def _get_json(url: str):
    req = urllib.request.Request(url, headers={"X-API-Key": settings.machine_api_key})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_orders(limit: int) -> list[dict]:
    """分页拉订单（后端单页上限 50，size 过大会 400）。"""
    out: list[dict] = []
    page = 0
    while len(out) < limit:
        data = _get_json(f"{settings.server_base_url}/orders?page={page}&size=50")
        batch = data.get("orders", []) if isinstance(data, dict) else []
        if not batch:
            break
        out.extend(batch)
        page += 1
    return out[:limit]


def fetch_events(order_id: str) -> list[dict]:
    data = _get_json(f"{settings.server_base_url}/orders/{order_id}/events")
    raw = data if isinstance(data, list) else data.get("events", [])
    return [EventStoreClient._normalize(e) for e in raw if isinstance(e, dict)]


def main(limit: int = 200) -> int:
    p = OrderPredictor()
    if not p.available:
        print("模型不可用，跳过检查")
        return 1

    orders = fetch_orders(limit)
    if not orders:
        print(f"未拉到订单（{settings.server_base_url}/orders），检查 EG_SERVER_BASE_URL 与 EG_MACHINE_API_KEY")
        return 1

    seqs: dict[str, list[dict]] = {}
    status: dict[str, str] = {}
    for o in orders:
        oid = o.get("orderId", "")
        if not oid:
            continue
        status[oid] = o.get("status", "")
        evs = fetch_events(oid)
        if evs:
            seqs[oid] = sorted(evs, key=lambda e: e.get("event_version", 0))
    print(f"真实订单 {len(seqs)} 单（拉取 {len(orders)}）\n")
    if not seqs:
        return 1

    # 1) 词表覆盖：未登录类型会把整条样本推出训练分布，是最常见的塌缩根因
    vocab = p._meta["event_vocab"]
    seen_types = {e.get("event_type", "") for evs in seqs.values() for e in evs}
    oov = sorted(t for t in seen_types if t not in vocab)
    print(f"词表覆盖: {len(vocab)} 类已登录，真实流出现 {len(seen_types)} 类")
    if oov:
        print(f"  [FAIL] 未登录事件类型（会被编码成 -1）: {oov}")
    else:
        print("  未登录事件类型: 无")

    # 2) 预测分布：塌成单一类别即视为失败
    dist: Counter = Counter()
    for evs in seqs.values():
        r = p.predict_events(evs)
        dist[r["outcome"] if r else "NONE"] += 1
    print(f"\n预测分布: {dict(dist)}")
    collapsed = len(dist) <= 1
    if collapsed:
        print(f"  [FAIL] 预测塌向单一类别 {next(iter(dist))}（典型症状：真实特征整体落在训练分布外）")

    # 3) 真实状态 × 预测：在途订单的预测是否合理，比总体分布更能说明问题
    pairs = [(status[o], p.predict_events(e)) for o, e in seqs.items() if p.predict_events(e)]
    print("\n真实状态 × 预测:")
    for s in sorted({s for s, _ in pairs}):
        row = Counter(pred["outcome"] for st, pred in pairs if st == s)
        n = sum(row.values())
        top = f"{row.most_common(1)[0][0]}×{row.most_common(1)[0][1]}"
        avg_conf = sum(pred["confidence"] for st, pred in pairs if st == s) / n
        print(f"  {s:10s} ({n:2d} 单) -> {dict(row)}  主判 {top} 平均置信 {avg_conf:.2f}")

    # 终态订单终局已知，预测必须命中——这是唯一有确定答案的验收点
    final = [(s, pred["outcome"]) for s, pred in pairs if s in TERMINAL_ORDER_STATUSES]
    hits = sum(1 for s, pred in final if s == pred)
    print(f"\n终态订单 {len(final)} 单，预测命中 {hits} 单"
          + ("" if hits == len(final) else "  [FAIL] 终局已知却判错"))

    # 4) 特征越界：真实 min/max 落在训练 min/max 之外的维度
    Xtr = np.array([r[0] for r in generate_rows(120, seed=42)], dtype=float)
    Xreal = np.array([p._extract_features(e) for e in seqs.values()], dtype=float)
    print("\n特征覆盖（真实 vs 训练）:")
    out_of_range = []
    for i, name in enumerate(FEATURES):
        lo_r, hi_r = Xreal[:, i].min(), Xreal[:, i].max()
        lo_t, hi_t = Xtr[:, i].min(), Xtr[:, i].max()
        outside = lo_r < lo_t or hi_r > hi_t
        if outside:
            out_of_range.append(name)
        print(f"  {name:9s} 真实[{lo_r:7.2f},{hi_r:7.2f}] 训练[{lo_t:7.2f},{hi_t:7.2f}]"
              f"{'  <== 越界' if outside else ''}")
    if out_of_range:
        print(f"  [WARN] 越界维度: {out_of_range}")

    failed = bool(oov) or collapsed or hits != len(final)
    print("\n结论: " + ("FAIL" if failed else "PASS"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
