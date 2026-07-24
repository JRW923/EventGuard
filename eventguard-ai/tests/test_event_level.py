import numpy as np
import pytest


def test_feature_extractor_returns_4_dimensions():
    """FeatureExtractor 输出 4 维特征向量"""
    from app.detector.feature_extractor import FeatureExtractor

    extractor = FeatureExtractor()
    event = {
        "event_type": "OrderCreatedEvent",
        "aggregate_id": "agg-1",
        "payload": {"totalAmount": 150.0, "userId": "user-1"},
        "created_at": "2026-07-21T10:00:00Z",
        "metadata": {"userId": "user-1"},
    }
    features = extractor.extract(event)
    assert len(features) == 4
    # amount_zscore, time_since_last_event, user_order_count_1h, state_transition_prob
    assert all(isinstance(f, float) for f in features)


def test_event_level_detector_returns_anomaly_result():
    """EventLevelDetector.detect 返回 AnomalyResult"""
    from app.detector.event_level import EventLevelDetector
    from app.model.anomaly import AnomalyResult

    # 用 mock 模型避免依赖训练好的 pkl
    class MockModel:
        def predict(self, X):
            return np.array([-1])  # -1 = 异常

        def score_samples(self, X):
            return np.array([-0.8])

    class MockScaler:
        def transform(self, X):
            return X

    class MockExtractor:
        def extract(self, event):
            return [1.0, 0.5, 3.0, 0.2]

        def update(self, event):
            pass

    detector = EventLevelDetector(
        model=MockModel(),
        scaler=MockScaler(),
        feature_extractor=MockExtractor(),
    )

    event = {
        "event_type": "OrderCreatedEvent",
        "aggregate_id": "agg-1",
        "payload": {"totalAmount": 99999.0, "userId": "user-1"},
        "created_at": "2026-07-21T10:00:00Z",
        "metadata": {"userId": "user-1"},
    }
    result = detector.detect(event)

    assert isinstance(result, AnomalyResult)
    assert result.is_anomaly is True
    assert result.score > 0
    assert result.source == "IF"
    assert result.level == "HIGH"  # 锁定 M1 修复：异常应返回 HIGH


def test_event_level_detector_returns_normal_for_typical_event():
    """正常事件返回 is_anomaly=False"""
    from app.detector.event_level import EventLevelDetector
    from app.model.anomaly import AnomalyResult

    class MockModel:
        def predict(self, X):
            return np.array([1])  # 1 = 正常

        def score_samples(self, X):
            return np.array([-0.1])

    class MockScaler:
        def transform(self, X):
            return X

    class MockExtractor:
        def extract(self, event):
            return [0.1, 30.0, 1.0, 0.9]

        def update(self, event):
            pass

    detector = EventLevelDetector(
        model=MockModel(),
        scaler=MockScaler(),
        feature_extractor=MockExtractor(),
    )

    event = {
        "event_type": "OrderCreatedEvent",
        "aggregate_id": "agg-1",
        "payload": {"totalAmount": 99.0, "userId": "user-1"},
        "created_at": "2026-07-21T10:00:00Z",
        "metadata": {"userId": "user-1"},
    }
    result = detector.detect(event)

    assert result.is_anomaly is False


def test_feature_extractor_extract_boundary_cases():
    """FeatureExtractor.extract 对缺字段 / 非法时间 / 首事件 都应返回 4 维 float 不崩"""
    from app.detector.feature_extractor import FeatureExtractor

    extractor = FeatureExtractor()

    edge_events = [
        # 缺 userId
        {"event_type": "OrderCreatedEvent", "aggregate_id": "agg-b1",
         "payload": {"totalAmount": 150.0}, "created_at": "2026-07-21T10:00:00Z"},
        # 缺 totalAmount
        {"event_type": "OrderCreatedEvent", "aggregate_id": "agg-b2",
         "payload": {}, "metadata": {"userId": "user-b"}, "created_at": "2026-07-21T10:00:00Z"},
        # 非法 created_at
        {"event_type": "OrderCreatedEvent", "aggregate_id": "agg-b3",
         "payload": {"totalAmount": 150.0}, "metadata": {"userId": "user-b"}, "created_at": "not-a-time"},
        # 完全空事件
        {},
    ]

    for event in edge_events:
        features = extractor.extract(event)
        assert len(features) == 4
        assert all(isinstance(f, float) for f in features)

    # 首事件状态转移概率应为 1.0 (INIT -> OrderCreatedEvent)
    first_event = {
        "event_type": "OrderCreatedEvent", "aggregate_id": "agg-first",
        "payload": {"totalAmount": 150.0}, "metadata": {"userId": "user-first"},
        "created_at": "2026-07-21T10:00:00Z",
    }
    feats = extractor.extract(first_event)
    assert feats[3] == 1.0
