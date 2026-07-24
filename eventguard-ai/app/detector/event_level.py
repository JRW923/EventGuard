"""事件级检测器：Isolation Forest 异常检测"""

import logging
from pathlib import Path
from typing import Optional

import joblib
import numpy as np

from app.detector.feature_extractor import FeatureExtractor
from app.model.anomaly import AnomalyResult

logger = logging.getLogger(__name__)


class EventLevelDetector:
    """Isolation Forest 事件级异常检测器"""

    def __init__(
        self,
        model=None,
        scaler=None,
        feature_extractor: Optional[FeatureExtractor] = None,
        model_path: Optional[str] = None,
        scaler_path: Optional[str] = None,
    ):
        if model is not None and scaler is not None:
            self.model = model
            self.scaler = scaler
        else:
            base = Path(__file__).parent.parent.parent
            try:
                self.model = joblib.load(model_path or str(base / "models" / "isolation_forest.pkl"))
                self.scaler = joblib.load(scaler_path or str(base / "models" / "scaler.pkl"))
            except FileNotFoundError as e:
                raise FileNotFoundError(
                    f"未找到模型文件: {e.filename}。请先运行: python -m training.train_isolation"
                ) from e
        self.feature_extractor = feature_extractor or FeatureExtractor()

    def detect(self, event: dict) -> AnomalyResult:
        """检测单事件是否异常"""
        features = self.feature_extractor.extract(event)
        self.feature_extractor.update(event)  # 推进特征提取器状态，避免推理特征塌缩成常量
        X = np.array([features])
        X_scaled = self.scaler.transform(X)

        pred = self.model.predict(X_scaled)[0]  # -1=异常, 1=正常
        score = -self.model.score_samples(X_scaled)[0]  # 越大越异常

        is_anomaly = (pred == -1)
        return AnomalyResult(
            is_anomaly=is_anomaly,
            score=float(score),
            source="IF",
            level="HIGH" if is_anomaly else "LOW",
            description=f"Isolation Forest score={score:.4f}" if is_anomaly else "",
        )
