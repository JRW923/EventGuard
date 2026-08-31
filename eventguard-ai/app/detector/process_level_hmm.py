"""HMM 流程级检测：对事件序列 log-likelihood 过低判为流程异常（规则检测的第二意见）

与 ProcessLevelRuleDetector.detect(event_sequence, now) 完全一致的签名与返回类型：
接收 list[dict]，返回 list[Anomaly]。复用 Anomaly 模型，_build_anomaly 风格对齐 process_level.py。
"""

import json
import logging
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import joblib
import numpy as np
from hmmlearn.hmm import CategoricalHMM

from app.model.anomaly import Anomaly

logger = logging.getLogger(__name__)

RULE_ID = "P004_HMM_LOW_LIKELIHOOD"

# ponytail: 三层 parent 才到项目根（app/detector/x.py → app/detector → app → 根）；
# 与同目录 event_level.py 保持一致。少一层会在容器内拼成 /app/app/models 而静默降级。
BASE = Path(__file__).resolve().parent.parent.parent  # eventguard-ai 根目录（容器内 /app）


class ProcessLevelHMMDetector:
    """MVP 流程级检测（ML 第二意见）：序列 log-likelihood < 阈值 → P004 流程异常。

    ponytail: 未训练（模型/词汇/阈值文件缺失）时 detect 返回 []，HMM 不生效，
    主流程仅依赖规则检测，不阻断。升级路径=CI 内置训练步骤，保证 models/ 文件就绪。
    """

    def __init__(
        self,
        model_path: Optional[str] = None,
        vocab_path: Optional[str] = None,
        threshold_path: Optional[str] = None,
    ):
        model_path = model_path or str(BASE / "models" / "hmm.pkl")
        vocab_path = vocab_path or str(BASE / "models" / "hmm_vocab.json")
        threshold_path = threshold_path or str(BASE / "models" / "hmm_threshold.json")
        try:
            self.model: CategoricalHMM = joblib.load(model_path)
            with open(vocab_path, "r", encoding="utf-8") as f:
                self.vocab: dict[str, int] = json.load(f)
            with open(threshold_path, "r", encoding="utf-8") as f:
                self.threshold: float = float(json.load(f)["threshold"])
            self.loaded = True
            # 模型实际建模的符号数（训练数据覆盖的符号空间）；超界类型交规则检测
            self.n_symbols = int(self.model.emissionprob_.shape[1])
        except FileNotFoundError as e:
            logger.warning("HMM 文件缺失(%s)，HMM 流程检测未启用，detect 返回 []", e.filename)
            self.loaded = False
            self.model = None
            self.vocab = {}
            self.threshold = float("-inf")

    def detect(self, event_sequence: list[dict], now: Optional[datetime] = None) -> list[Anomaly]:
        """序列似然低于阈值则报一条 P004 流程异常；否则返回 []。

        now 参数保留以对齐 ProcessLevelRuleDetector 签名（HMM 不依赖时间）。
        """
        if not self.loaded or not event_sequence:
            return []

        obs = np.array([[self.vocab.get(e.get("event_type", ""), -1)] for e in event_sequence])
        # 含未知事件类型，或超出 HMM 建模符号空间的类型，无法可靠打分，交规则检测
        if (obs < 0).any() or (obs >= self.n_symbols).any():
            return []

        try:
            ll = float(self.model.score(obs, [len(obs)]))
        except Exception as e:  # 模型对极端序列数值不稳定时，保守跳过
            logger.warning("HMM score 失败: %s", e)
            return []

        if ll < self.threshold:
            return [self._build_anomaly(event_sequence[-1], ll)]
        return []

    def _build_anomaly(self, event: dict, ll: float) -> Anomaly:
        return Anomaly(
            anomaly_id=str(uuid.uuid4()),
            rule_id=RULE_ID,
            aggregate_id=event.get("aggregate_id", str(uuid.uuid4())),
            event_type=event.get("event_type", "Unknown"),
            level="WARN",
            source="PROCESS",
            priority="LOW",
            detected_at=datetime.now(timezone.utc).isoformat(),
            description=f"HMM 序列似然低于阈值(ll={ll:.2f} < {self.threshold:.2f})，疑似流程异常",
            details={"log_likelihood": ll, "threshold": float(self.threshold)},
        )


def run_process_detectors(
    event_sequence: list[dict],
    rule_detector,
    hmm_detector: Optional[ProcessLevelHMMDetector] = None,
    now: Optional[datetime] = None,
) -> list[Anomaly]:
    """流程级检测编排：先规则后 HMM，合并 anomalies。

    ponytail: HMM 是规则检测的第二意见（补充规则未覆盖的"软"流程异常），阈值取自训练集分位数；
    两道检测独立，结果直接合并（不去重，规则与 HMM 可同时命中）。升级路径=按 rule_id 去重/优先级仲裁。
    """
    anomalies = list(rule_detector.detect(event_sequence, now))
    if hmm_detector is not None:
        anomalies.extend(hmm_detector.detect(event_sequence, now))
    return anomalies
