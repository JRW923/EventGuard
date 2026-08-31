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

# 事件类型词表：必须覆盖 OrderAggregate 状态机能 raise 的**全部**事件类型。
# 漏登类型在推理侧会被编成 -1（order_predictor._extract_features），使整条样本成为
# 训练分布外的输入，森林外推塌向多数类（曾漏 PaymentRequestedEvent 导致 51/51 全判 CLOSED）。
# 编号按正常流程顺序排，便于人工核对。
EVENT_VOCAB = {
    "OrderCreatedEvent": 0,
    "PaymentRequestedEvent": 1,
    "PaymentCompletedEvent": 2,
    "InventoryReservedEvent": 3,
    "OrderConfirmedEvent": 4,
    "ShippedEvent": 5,
    "DeliveredEvent": 6,
    "OrderClosedEvent": 7,
    "PaymentFailedEvent": 8,
    "PaymentRetriedEvent": 9,
    "OrderCancelledEvent": 10,
    "OrderRefundRequestedEvent": 11,
    "OrderRefundedEvent": 12,
    "InventoryReservationFailedEvent": 13,
    "CompensationExecutedEvent": 14,
}

# 正常流：Pay 命令先落 PaymentRequestedEvent（支付意图，不改状态），网关回调再落
# PaymentCompletedEvent。见 OrderAggregate.handle(PayOrderCommand/CompletePaymentCommand)。
NORMAL_FLOW = [
    "OrderCreatedEvent", "PaymentRequestedEvent", "PaymentCompletedEvent",
    "InventoryReservedEvent", "OrderConfirmedEvent", "ShippedEvent",
    "DeliveredEvent", "OrderClosedEvent",
]


def _next_gap() -> timedelta:
    """相邻事件间隔：覆盖真实出现的三个量级，避免模型只见过单一节奏。

    - 30% 秒级：压测/自动化下单，事件几乎同时落库（真实库 51/51 属此类）
    - 55% 分钟级：正常用户操作节奏
    - 15% 小时级：跨环节等待（对账、人工处理）
    """
    r = random.random()
    if r < 0.30:
        return timedelta(seconds=random.uniform(0.005, 5))
    if r < 0.85:
        return timedelta(minutes=random.uniform(1, 30))
    return timedelta(hours=random.uniform(1, 24))


def _next_amount() -> float:
    """订单金额：对数均匀采样 50~20000，覆盖真实观测区间（真实 267~9600）。

    原 gauss(100,25) 使真实金额全部落在训练分布之外。
    """
    return round(math.exp(random.uniform(math.log(50), math.log(20000))), 2)

# 业务先验：正常走完的订单占绝大多数，异常终局是少数。
# 不能用均匀采样——前 3 个事件是四类的公共前缀，均匀先验会让模型对"刚支付完"这类
# 无区分度的序列平均投票，实测 32 单 PAID 里 21 单被判 CANCELLED、4 单 CONFIRMED 全判 REFUNDED。
CLASS_PRIOR = {"CLOSED": 0.70, "CANCELLED": 0.10, "REFUNDED": 0.10, "STUCK": 0.10}

# 前缀采样上限：完整序列按前缀切训练样本时最多取前 N 个前缀。
# 必须 >= NORMAL_FLOW 长度，否则 events_seen 特征的训练覆盖域小于真实订单长度，
# 走完大半流程的订单（真实最长 7 个事件）会落在分布外而被误判。
PREFIX_MAX = 8


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


# 所有订单的公共开头：Pay 命令落 PaymentRequestedEvent，网关回调落 PaymentCompletedEvent。
# 真实库 51/51 订单都是这个开头，训练侧必须与之一致，否则前 3 槽编码错位。
HEAD = ["OrderCreatedEvent", "PaymentRequestedEvent", "PaymentCompletedEvent"]


def _seq(agg: str, user: str, amount: float, start: datetime, etypes: list[str]) -> list:
    """按事件类型列表生成事件序列，间隔由 _next_gap 采样。"""
    events, ts = [], start
    for i, etype in enumerate(etypes):
        events.append(_event(agg, i + 1, etype, amount, ts, user))
        ts += _next_gap()
    return events


def _make_sequence(kind: str, agg: str, user: str, amount: float, start: datetime) -> tuple[list, str]:
    """生成完整订单序列并返回 (events, 终局标签)。"""
    if kind == "CLOSED":
        # 库存预留是独立 saga 步骤，状态机允许 PAID 直接确认，真实流中可能被跳过
        flow = [e for e in NORMAL_FLOW if e != "InventoryReservedEvent" or random.random() < 0.5]
        return _seq(agg, user, amount, start, flow), "CLOSED"
    if kind == "CANCELLED":
        # 取消可发生在支付后或确认后（状态机：非终态皆可取消）
        etypes = HEAD + (["OrderConfirmedEvent"] if random.random() < 0.5 else [])
        return _seq(agg, user, amount, start, etypes + ["OrderCancelledEvent"]), "CANCELLED"
    if kind == "REFUNDED":
        # 状态机：退款要求 PAID 或 CONFIRMED；真实库 2/2 都发生在确认后
        etypes = HEAD + (["OrderConfirmedEvent"] if random.random() < 0.5 else [])
        return _seq(agg, user, amount, start, etypes + ["OrderRefundedEvent"]), "REFUNDED"
    # STUCK 三种变体，覆盖真实观察到的停滞形态
    r = random.random()
    if r < 0.34:
        # A：支付完成后无后续
        return _seq(agg, user, amount, start, HEAD), "STUCK"
    if r < 0.67:
        # B：库存预留失败 → 补偿。补偿不改订单状态（仍 PAID），且真实库该单到此为止
        return _seq(
            agg, user, amount, start,
            HEAD + ["InventoryReservationFailedEvent", "CompensationExecutedEvent"],
        ), "STUCK"
    # C：死循环。Retried 后状态回 PENDING_PAYMENT，再次失败需重新发起支付，
    # 故循环节是 Retried→Requested→Failed（见 OrderAggregate.handle(RetryPaymentCommand)）
    etypes = ["OrderCreatedEvent", "PaymentRequestedEvent", "PaymentFailedEvent"]
    for _ in range(3):
        etypes += ["PaymentRetriedEvent", "PaymentRequestedEvent", "PaymentFailedEvent"]
    return _seq(agg, user, amount, start, etypes), "STUCK"


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


def generate_rows(n_total: int = 2000, seed: int = 42) -> list[tuple[list, int, str]]:
    """生成训练样本：每条完整序列取所有前缀（长度 1..PREFIX_MAX）作为样本，标签 = 终局状态。

    n_total 按 CLASS_PRIOR 分配到各类（业务先验），不是每类 n_total 条。
    """
    random.seed(seed)
    base = datetime(2026, 7, 1, tzinfo=timezone.utc)
    rows: list[tuple[list, int, str]] = []
    i = 0
    for kind in LABELS:
        for _ in range(int(n_total * CLASS_PRIOR[kind])):
            agg = str(uuid.uuid4())
            user = f"user-{random.randint(1, 200)}"
            amount = _next_amount()
            start = base + timedelta(minutes=i)
            events, label = _make_sequence(kind, agg, user, amount, start)
            final_code = LABEL_TO_CODE[label]
            for L in range(1, min(len(events), PREFIX_MAX) + 1):
                rows.append((extract_features(events[:L]), final_code, label))
            i += 1
    return rows


def train_and_save(n_total: int = 2000, test_ratio: float = 0.2, seed: int = 42) -> dict:
    rows = generate_rows(n_total, seed)
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
        # ponytail: 不用 class_weight="balanced"——它会把 CLASS_PRIOR 的业务先验重新拉平，
        # 让"刚支付完"这种四类公共前缀的序列被平均投票，与真实业务（多数订单正常走完）相反。
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
