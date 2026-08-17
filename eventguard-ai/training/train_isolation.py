"""Isolation Forest 训练脚本：用正常事件流训练模型并持久化"""

import json
import os
from pathlib import Path

import joblib
import numpy as np
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler

from app.detector.feature_extractor import FeatureExtractor


def train_isolation_forest(
    normal_data_path: str = "data/normal_events.jsonl",
    model_output: str = "models/isolation_forest.pkl",
    scaler_output: str = "models/scaler.pkl",
) -> None:
    """训练 Isolation Forest 并保存模型与 scaler"""
    extractor = FeatureExtractor()

    # 加载正常事件并按时间排序更新特征提取器状态
    events = []
    with open(normal_data_path, "r", encoding="utf-8") as f:
        for line in f:
            event = json.loads(line)
            events.append(event)

    # 按 created_at 排序确保时间窗口正确
    events.sort(key=lambda e: e.get("created_at", ""))

    if not events:
        raise ValueError(f"训练数据为空: {normal_data_path}")

    # 提取特征
    features_list = []
    for event in events:
        features = extractor.extract(event)
        features_list.append(features)
        extractor.update(event)  # 更新内部状态

    X = np.array(features_list)
    print(f"训练数据形状: {X.shape}")

    # 标准化
    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(X)

    # 训练 Isolation Forest（contamination 经 EG_ISOLATION_CONTAMINATION 可配）
    contamination = float(os.environ.get("EG_ISOLATION_CONTAMINATION", "0.05"))
    model = IsolationForest(
        n_estimators=100,
        contamination=contamination,
        random_state=42,
        n_jobs=-1,
    )
    model.fit(X_scaled)

    # 持久化
    Path(model_output).parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(model, model_output)
    joblib.dump(scaler, scaler_output)
    print(f"模型已保存: {model_output}, {scaler_output}")

    # 简单验证：训练集上的异常率
    preds = model.predict(X_scaled)
    anomaly_rate = (preds == -1).sum() / len(preds)
    print(f"训练集异常率: {anomaly_rate:.4f}（预期接近 contamination={contamination}）")


if __name__ == "__main__":
    train_isolation_forest()
