"""订单终局预测推理（Item 5）。

加载 training/train_predict.py 产出的 predictor.pkl + predictor_meta.json，
对订单当前事件序列预测终局状态（CLOSED/CANCELLED/REFUNDED/STUCK）+ 置信度 + 风险分级。
缺模型文件时降级：available=False，调用方返回 prediction=null，不阻断主流程。
"""
import json
import logging
import math
from datetime import datetime
from pathlib import Path
from typing import Optional

import joblib
import numpy as np

from app.store.event_store_client import EventStoreClient

logger = logging.getLogger(__name__)

RISK_BY_OUTCOME = {"CLOSED": "LOW", "CANCELLED": "MEDIUM", "REFUNDED": "MEDIUM", "STUCK": "HIGH"}
RISK_ORDER = {"HIGH": 0, "MEDIUM": 1, "LOW": 2}


class OrderPredictor:
    """从事件序列预测订单终局状态。"""

    def __init__(
        self,
        model_path: Optional[str] = None,
        meta_path: Optional[str] = None,
        event_store_client: Optional[EventStoreClient] = None,
    ):
        base = Path(__file__).parent.parent.parent  # eventguard-ai/
        self.model_path = model_path or str(base / "models" / "predictor.pkl")
        self.meta_path = meta_path or str(base / "models" / "predictor_meta.json")
        self.event_store_client = event_store_client or EventStoreClient()
        self._model = None
        self._meta = None
        self._load()

    def _load(self) -> None:
        try:
            self._model = joblib.load(self.model_path)
            self._meta = json.loads(Path(self.meta_path).read_text(encoding="utf-8"))
        except Exception as e:  # 模型/元数据缺失或损坏：降级关闭，不影响检测/查询主链路
            logger.warning("预测模型加载失败，预测能力降级关闭：%s", e)
            self._model = None
            self._meta = None

    @property
    def available(self) -> bool:
        return self._model is not None and self._meta is not None

    def predict_events(self, events: list[dict]) -> Optional[dict]:
        """输入订单当前事件序列（按 version 升序）→ {outcome, confidence, risk}。"""
        if not self.available or not events:
            return None
        X = np.array([self._extract_features(events)], dtype=float)
        probs = self._model.predict_proba(X)[0]
        # predict_proba 的列与 clf.classes_（类别编码升序）对齐，需经 classes_ 映射回标签
        best_pos = int(np.argmax(probs))
        code = int(self._model.classes_[best_pos])
        outcome = self._meta["labels"][code]
        confidence = float(probs[best_pos])
        return {
            "outcome": outcome,
            "confidence": round(confidence, 3),
            "risk": self._risk(outcome, confidence),
        }

    def predict_order(self, aggregate_id: str) -> Optional[dict]:
        """按订单号加载事件并预测。"""
        if not self.available:
            return None
        events = self.event_store_client.load_events(aggregate_id)
        if not events:
            return None
        events = sorted(events, key=lambda e: e.get("event_version", 0))
        return self.predict_events(events)

    # ---------------- 特征（与 training/train_predict.py 的 extract_features 对齐） ----------------

    def _extract_features(self, events: list[dict]) -> list[float]:
        vocab = self._meta["event_vocab"]
        k = int(self._meta.get("k", 3))
        first = events[:k]
        codes = [vocab.get(e.get("event_type", ""), -1) for e in first]
        while len(codes) < k:
            codes.append(-1)
        amount = self._amount_of(first) if first else None
        gap01 = self._gap_sec(first[0], first[1]) if len(first) >= 2 else None
        gap12 = self._gap_sec(first[1], first[2]) if len(first) >= 3 else None
        last_code = vocab.get(events[-1].get("event_type", ""), -1) if events else -1
        return [
            float(codes[0]), float(codes[1]), float(codes[2]),
            math.log1p(amount) if amount is not None else 0.0,
            math.log1p(gap01) if gap01 is not None else 0.0,
            math.log1p(gap12) if gap12 is not None else 0.0,
            float(len(events)),  # events_seen
            float(last_code),    # last_event_code
        ]

    @staticmethod
    def _amount_of(events: list[dict]) -> Optional[float]:
        payload = events[0].get("payload", {})
        for key in ("totalAmount", "amount"):
            if key in payload:
                try:
                    return float(payload[key])
                except (TypeError, ValueError):
                    return None
        return None

    @staticmethod
    def _gap_sec(a: dict, b: dict) -> Optional[float]:
        try:
            ta = datetime.fromisoformat(a["created_at"].replace("Z", "+00:00"))
            tb = datetime.fromisoformat(b["created_at"].replace("Z", "+00:00"))
            return max(0.0, (tb - ta).total_seconds())
        except (ValueError, TypeError, KeyError):
            return None

    @staticmethod
    def _risk(outcome: str, confidence: float) -> str:
        # 低置信度不下高危结论，避免误导
        if confidence < 0.45:
            return "LOW"
        return RISK_BY_OUTCOME.get(outcome, "MEDIUM")

    @staticmethod
    def risk_rank(risk: str) -> int:
        return RISK_ORDER.get(risk, 2)
