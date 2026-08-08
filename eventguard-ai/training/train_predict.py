"""事件流终局预测：从前 K 个事件预测订单终局状态（CLOSED/CANCELLED/REFUNDED/STUCK）

核心思路（与推理完全对齐）：
- 生成完整合成订单序列，按终局状态打标（终局 = 序列最后一个事件的映射）
- 对每条完整序列，取其所有前缀（长度 1..min(len,5)）各生成一条训练样本，标签 = 终局状态
- 这样推理时直接喂"订单当前已有事件序列"即可，前缀分布与训练一致

标签语义：
- 以 OrderClosedEvent 结束 → CLOSED
- 以 OrderCancelledEvent 结束 → CANCELLED
- 以 OrderRefundedEvent 结束 → REFUNDED
- 其余（停滞 / 进行中）→ STUCK

产出：models/predictor.pkl（RandomForest）+ models/predictor_meta.json（vocab/特征名/准确率）
"""
import json
import math
import random
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import joblib
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import classification_report

BASE = Path(__file__).parent.parent
MODEL_DIR = BASE / "models"

K = 3
LABELS = ["CLOSED", "CANCELLED", "REFUNDED", "STUCK"]
LABEL_TO_CODE = {l: i for i, l in enumerate(LABELS)}

# 与 hmm_vocab.json 对齐的事件类型词表
EVENT_VOCAB = {
    "OrderCreatedEvent": 0,
    "PaymentCompletedEvent": 1,
    "InventoryReservedEvent": 2,
    "OrderConfirmedEvent": 3,
    "ShippedEvent": 4,
    "DeliveredEvent": 5,
    "OrderClosedEvent": 6,
    "PaymentFailedEvent": 7,
    "PaymentRetriedEvent": 8,
    "OrderCancelledEvent": 9,
    "OrderRefundRequestedEvent": 10,
    "OrderRefundedEvent": 11,
}

NORMAL_FLOW = [
    "OrderCreatedEvent", "PaymentCompletedEvent", "InventoryReservedEvent",
    "OrderConfirmedEvent", "ShippedEvent", "DeliveredEvent", "OrderClosedEvent",
]

# 前缀采样上限：完整序列按前缀切训练样本时最多取前 5 个前缀，避免长序列过度主导
PREFIX_MAX = 5


def _iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def _event(agg: str, version: int, etype: str, amount: float, ts: datetime, user: str) -> dict:
    payload = {"orderId": agg, "userId": user}
    if etype == "OrderCreatedEvent":
        payload["totalAmount"] = round(amount, 2)
    elif etype == "PaymentCompletedEvent":
        payload["amount"] = round(amount, 2)
    return {
        "event_id": str(uuid.uuid4()),
        "aggregate_id": agg,
        "aggregate_type": "Order",
        "event_type": etype,
        "event_version": version,
        "payload": payload,
        "metadata": {"userId": user, "traceId": str(uuid.uuid4())},
        "created_at": _iso(ts),
        "is_anomaly": False,
        "anomaly_type": None,
    }


def _make_sequence(kind: str, agg: str, user: str, amount: float, start: datetime) -> tuple[list, str]:
    """生成完整订单序列并返回 (events, 终局标签)。"""
    if kind == "CLOSED":
        events, ts = [], start
        for i, etype in enumerate(NORMAL_FLOW):
            events.append(_event(agg, i + 1, etype, amount, ts, user))
            ts += timedelta(minutes=random.randint(5, 30))
        return events, "CLOSED"
    if kind == "CANCELLED":
        return [
            _event(agg, 1, "OrderCreatedEvent", amount, start, user),
            _event(agg, 2, "PaymentCompletedEvent", amount, start + timedelta(minutes=10), user),
            _event(agg, 3, "OrderCancelledEvent", amount, start + timedelta(minutes=15), user),
        ], "CANCELLED"
    if kind == "REFUNDED":
        return [
            _event(agg, 1, "OrderCreatedEvent", amount, start, user),
            _event(agg, 2, "PaymentCompletedEvent", amount, start + timedelta(minutes=10), user),
            _event(agg, 3, "OrderRefundRequestedEvent", amount, start + timedelta(minutes=20), user),
            _event(agg, 4, "OrderRefundedEvent", amount, start + timedelta(minutes=25), user),
        ], "REFUNDED"
    # STUCK 两种变体：停滞（PAID 后无后续）/ 支付死循环（PaymentFailed→Retried 交替）
    if random.random() < 0.5:
        # 变体 A：停滞
        return [
            _event(agg, 1, "OrderCreatedEvent", amount, start, user),
            _event(agg, 2, "PaymentCompletedEvent", amount, start + timedelta(minutes=10), user),
        ], "STUCK"
    # 变体 B：支付死循环（特征可学性强，是 STUCK 的强信号）
    events = [_event(agg, 1, "OrderCreatedEvent", amount, start, user)]
    ts = start + timedelta(minutes=5)
    version = 2
    for i in range(3):
        events.append(_event(agg, version, "PaymentFailedEvent", amount, ts, user))
        version += 1
        ts += timedelta(minutes=2)
        events.append(_event(agg, version, "PaymentRetriedEvent", amount, ts, user))
        version += 1
        ts += timedelta(minutes=1)
    return events, "STUCK"


def _amount_of(events: list) -> float | None:
    e = events[0]
    p = e.get("payload", {})
    for key in ("totalAmount", "amount"):
        if key in p:
            try:
                return float(p[key])
            except (TypeError, ValueError):
                return None
    return None


def _gap_sec(a: dict, b: dict) -> float | None:
    try:
        ta = datetime.fromisoformat(a["created_at"].replace("Z", "+00:00"))
        tb = datetime.fromisoformat(b["created_at"].replace("Z", "+00:00"))
        return max(0.0, (tb - ta).total_seconds())
    except (ValueError, TypeError, KeyError):
        return None


def extract_features(events: list) -> list[float]:
    """从事件序列（当前已有事件，顺序按 version）提取固定 8 维特征。

    与推理端 app/predictor/order_predictor.py 完全一致；事件不足 3 个用 -1 补齐、间隔/金额缺失置 0。
    """
    first = events[:K]
    codes = [EVENT_VOCAB.get(e.get("event_type", ""), -1) for e in first]
    while len(codes) < K:
        codes.append(-1)
    amount = _amount_of(first) if first else None
    gap01 = _gap_sec(first[0], first[1]) if len(first) >= 2 else None
    gap12 = _gap_sec(first[1], first[2]) if len(first) >= 3 else None
    last_code = EVENT_VOCAB.get(events[-1].get("event_type", ""), -1) if events else -1
    return [
        float(codes[0]), float(codes[1]), float(codes[2]),
        math.log1p(amount) if amount is not None else 0.0,
        math.log1p(gap01) if gap01 is not None else 0.0,
        math.log1p(gap12) if gap12 is not None else 0.0,
        float(len(events)),  # events_seen：已观察事件数
        float(last_code),    # last_event_code：当前所处环节
    ]


def generate_rows(n_per_kind: int = 500, seed: int = 42) -> list[tuple[list, int, str]]:
    """生成训练样本：每条完整序列取所有前缀（长度 1..PREFIX_MAX）作为样本，标签 = 终局状态。"""
    random.seed(seed)
    base = datetime(2026, 7, 1, tzinfo=timezone.utc)
    rows: list[tuple[list, int, str]] = []
    i = 0
    for kind in LABELS:
        for _ in range(n_per_kind):
            agg = str(uuid.uuid4())
            user = f"user-{random.randint(1, 200)}"
            amount = max(10.0, random.gauss(100, 25))
            start = base + timedelta(minutes=i)
            events, label = _make_sequence(kind, agg, user, amount, start)
            final_code = LABEL_TO_CODE[label]
            for L in range(1, min(len(events), PREFIX_MAX) + 1):
                rows.append((extract_features(events[:L]), final_code, label))
            i += 1
    return rows


def train_and_save(n_per_kind: int = 500, test_ratio: float = 0.2, seed: int = 42) -> dict:
    rows = generate_rows(n_per_kind, seed)
    random.seed(seed)
    random.shuffle(rows)
    split = int(len(rows) * (1 - test_ratio))
    X = np.array([r[0] for r in rows], dtype=float)
    y = np.array([r[1] for r in rows], dtype=int)
    Xtr, Xte, ytr, yte = X[:split], X[split:], y[:split], y[split:]

    clf = RandomForestClassifier(
        n_estimators=120,
        random_state=42,
        n_jobs=-1,
        class_weight="balanced",
        max_depth=12,        # 限制树深，控制模型体积与过拟合
        min_samples_leaf=5,
    )
    clf.fit(Xtr, ytr)
    acc = float(clf.score(Xte, yte))

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    joblib.dump(clf, MODEL_DIR / "predictor.pkl")
    meta = {
        "event_vocab": EVENT_VOCAB,
        "labels": LABELS,
        "label_to_code": LABEL_TO_CODE,
        "feature_names": ["e0_code", "e1_code", "e2_code", "amount_log1p", "gap01_log1p", "gap12_log1p", "events_seen", "last_event_code"],
        "k": K,
        "accuracy": round(acc, 4),
        "n_train": int(split),
        "n_test": int(len(rows) - split),
        "created": "train_predict.py",
    }
    with open(MODEL_DIR / "predictor_meta.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)

    print(f"训练完成: 样本 {len(rows)}（train {split} / test {len(rows) - split}）, 准确率 {acc:.4f}")
    print(classification_report(yte, clf.predict(Xte), target_names=LABELS, zero_division=0))
    return meta


if __name__ == "__main__":
    train_and_save()
