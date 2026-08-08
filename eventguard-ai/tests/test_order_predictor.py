"""OrderPredictor 单元测试：内联训练迷你模型验证推理形状 + 模型缺失降级。"""
import json

import joblib
import numpy as np
from sklearn.ensemble import RandomForestClassifier

from app.predictor.order_predictor import OrderPredictor
from training.train_predict import EVENT_VOCAB, LABELS, LABEL_TO_CODE, extract_features

CLOSED_PREFIX = [
    {"event_type": "OrderCreatedEvent", "created_at": "2026-07-01T00:00:00Z", "payload": {"totalAmount": 100.0}},
    {"event_type": "PaymentCompletedEvent", "created_at": "2026-07-01T00:10:00Z"},
    {"event_type": "InventoryReservedEvent", "created_at": "2026-07-01T00:20:00Z"},
]
STUCK_PREFIX = [
    {"event_type": "OrderCreatedEvent", "created_at": "2026-07-01T00:00:00Z", "payload": {"totalAmount": 100.0}},
    {"event_type": "PaymentFailedEvent", "created_at": "2026-07-01T00:10:00Z"},
]


def _train_tiny(tmp_path):
    rows = []
    for _ in range(30):
        rows.append((extract_features(CLOSED_PREFIX), LABEL_TO_CODE["CLOSED"]))
        rows.append((extract_features(STUCK_PREFIX), LABEL_TO_CODE["STUCK"]))
    X = np.array([r[0] for r in rows])
    y = np.array([r[1] for r in rows])
    clf = RandomForestClassifier(n_estimators=10, random_state=42)
    clf.fit(X, y)
    meta = {"event_vocab": EVENT_VOCAB, "labels": LABELS, "label_to_code": LABEL_TO_CODE, "k": 3}
    pkl = tmp_path / "predictor.pkl"
    mj = tmp_path / "predictor_meta.json"
    joblib.dump(clf, pkl)
    mj.write_text(json.dumps(meta), encoding="utf-8")
    return str(pkl), str(mj)


def test_predict_events_returns_shape(tmp_path):
    pkl, mj = _train_tiny(tmp_path)
    p = OrderPredictor(model_path=pkl, meta_path=mj)
    assert p.available

    pred = p.predict_events(STUCK_PREFIX)
    assert pred is not None
    assert pred["outcome"] in LABELS
    assert 0 <= pred["confidence"] <= 1
    assert pred["risk"] in ("LOW", "MEDIUM", "HIGH")
    # PaymentFailed 前缀应判为 STUCK
    assert pred["outcome"] == "STUCK"

    # CLOSED 前缀应判为 CLOSED
    pred2 = p.predict_events(CLOSED_PREFIX)
    assert pred2 is not None
    assert pred2["outcome"] == "CLOSED"


def test_empty_or_none_events_returns_none(tmp_path):
    pkl, mj = _train_tiny(tmp_path)
    p = OrderPredictor(model_path=pkl, meta_path=mj)
    assert p.predict_events([]) is None
    assert p.predict_events(None) is None


def test_degrade_when_model_missing(tmp_path):
    """模型文件缺失：available=False，predict 返回 None 且不访问后端。"""
    p = OrderPredictor(
        model_path=str(tmp_path / "none.pkl"),
        meta_path=str(tmp_path / "none.json"),
    )
    assert p.available is False
    assert p.predict_events([{"event_type": "OrderCreatedEvent"}]) is None
    assert p.predict_order("any-id") is None  # 不触发 load_events 网络调用


def test_risk_mapping():
    assert OrderPredictor._risk("CLOSED", 0.9) == "LOW"
    assert OrderPredictor._risk("STUCK", 0.9) == "HIGH"
    assert OrderPredictor._risk("STUCK", 0.3) == "LOW"  # 低置信度不下高危结论
    assert OrderPredictor._risk("CANCELLED", 0.8) == "MEDIUM"
