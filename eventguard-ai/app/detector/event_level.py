"""事件级检测器：Isolation Forest 异常检测"""

import logging
from pathlib import Path
from typing import Optional

import joblib
import numpy as np

from app.config import settings
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
            # ponytail: 模型路径可配（EG_IF_MODEL_PATH），默认回退镜像内置 /app/models。
            # 演示环境指向挂载目录 /data/models，重训后随卷持久化，无需重建镜像。
            model_path = model_path or settings.if_model_path or str(base / "models" / "isolation_forest.pkl")
            scaler_path = scaler_path or settings.if_scaler_path or str(base / "models" / "scaler.pkl")
            try:
                self.model = joblib.load(model_path)
                self.scaler = joblib.load(scaler_path)
            except FileNotFoundError as e:
                raise FileNotFoundError(
                    f"未找到模型文件: {e.filename}。请先运行: python -m training.train_isolation"
                ) from e
        self.feature_extractor = feature_extractor or FeatureExtractor()

    def detect(self, event: dict) -> AnomalyResult:
        """检测单事件是否异常。

        ponytail: feature_extractor 是有状态的（update 会推进用户历史基线），本方法未加锁。
        安全前提是检测只由单条 Kafka 消费线程驱动（KafkaEventConsumer 单线程 +
        max_poll_records=1）。若将来从 HTTP 端点并发调用 detect，或改成多消费线程，
        必须先给 feature_extractor 加锁，否则不同订单的特征会互相串味。
        """
        features = self.feature_extractor.extract(event)
        self.feature_extractor.update(event)  # 推进特征提取器状态，避免推理特征塌缩成常量
        X = np.array([features])
        X_scaled = self.scaler.transform(X)

        pred = self.model.predict(X_scaled)[0]  # -1=异常, 1=正常
        score = -self.model.score_samples(X_scaled)[0]  # 越大越异常

        is_anomaly = (pred == -1)
        # IF 异常按架构设计为低优先级；最终告警优先级由 EventLevelService 按 source 决定
        return AnomalyResult(
            is_anomaly=is_anomaly,
            score=float(score),
            source="IF",
            level="LOW",
            description=f"Isolation Forest score={score:.4f}" if is_anomaly else "",
        )


# ======== M3.5 追加：事件级检测协同服务 ========

from app.detector.rule_bridge import RuleBridge


class EventLevelService:
    """事件级检测协同服务：规则引擎（高优先级）→ Isolation Forest（低优先级）"""

    def __init__(
        self,
        rule_bridge: Optional[RuleBridge] = None,
        if_detector: Optional[EventLevelDetector] = None,
    ):
        self.rule_bridge = rule_bridge or RuleBridge()
        self.if_detector = if_detector or EventLevelDetector()

    def detect(self, event: dict) -> AnomalyResult:
        """
        协同检测流程：
        1. 先调规则引擎 HTTP → 命中则返回高优先级告警
        2. 未命中 → 调 Isolation Forest → 异常则返回低优先级告警
        3. 都未命中 → 返回正常
        """
        # 1. 规则引擎
        rule_result = self.rule_bridge.evaluate(event)
        if rule_result is not None and rule_result.is_anomaly:
            return rule_result

        # 2. Isolation Forest
        if_result = self.if_detector.detect(event)
        return if_result
