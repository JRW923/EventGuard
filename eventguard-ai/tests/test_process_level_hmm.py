"""ProcessLevelHMMDetector 最小校验：不依赖大模型文件，测试中临时训练微小 CategoricalHMM。

ponytail: 本环境 hmmlearn 0.3.2 的 CategoricalHMM.score 对顺序不敏感（forward/reversed 同分），
故 HMM 实际是「序列长度/符号组成」异常检测器（正常订单恒为 7 事件）。测试据此断言：
- 异常长序列（正常流程重复两遍 = 14 事件，真实订单不会出现）→ 判异常含 P004。
- 正常 7 事件流程 → 不报 P004。
- 含未知 event_type → 返回 []（交规则检测）。
- 模型文件缺失 → detect 返回 []（不阻断主流程）。
- run_process_detectors 合并规则与 HMM 结果（用超长 + 非法迁移序列同时触发 P001 与 P004）。
"""

import json
from pathlib import Path

import joblib
import numpy as np
import pytest
from hmmlearn.hmm import CategoricalHMM

from app.detector.process_level import ProcessLevelRuleDetector
from app.detector.process_level_hmm import ProcessLevelHMMDetector, run_process_detectors

FLOW = [
    "OrderCreatedEvent",
    "PaymentCompletedEvent",
    "InventoryReservedEvent",
    "OrderConfirmedEvent",
    "ShippedEvent",
    "DeliveredEvent",
    "OrderClosedEvent",
]
VOCAB = {et: i for i, et in enumerate(FLOW)}


def _train_temp_hmm(tmp_path: Path):
    """用正常流程序列训练一个微小 CategoricalHMM，写到临时目录并取 5% 分位阈值。"""
    sequences = [[VOCAB[t] for t in FLOW] for _ in range(60)]
    X = np.concatenate([np.array(s).reshape(-1, 1) for s in sequences])
    lengths = [len(s) for s in sequences]
    model = CategoricalHMM(n_components=5, random_state=42, n_iter=30)
    model.fit(X, lengths)

    log_liks = []
    start = 0
    for L in lengths:
        log_liks.append(model.score(X[start:start + L], [L]))
        start += L
    threshold = float(np.quantile(log_liks, 0.05))

    model_path = tmp_path / "hmm.pkl"
    vocab_path = tmp_path / "hmm_vocab.json"
    thr_path = tmp_path / "hmm_threshold.json"
    joblib.dump(model, model_path)
    vocab_path.write_text(json.dumps(VOCAB), encoding="utf-8")
    thr_path.write_text(json.dumps({"threshold": threshold}), encoding="utf-8")
    return model_path, vocab_path, thr_path


def _seq(event_types):
    return [{"event_type": et, "aggregate_id": "agg-1", "created_at": f"2026-07-21T10:0{i}:00Z"}
            for i, et in enumerate(event_types)]


@pytest.fixture
def detector(tmp_path):
    mp, vp, tp = _train_temp_hmm(tmp_path)
    return ProcessLevelHMMDetector(model_path=str(mp), vocab_path=str(vp), threshold_path=str(tp))


def test_hmm_flags_overlong_flow(detector):
    """异常长序列（正常流程重复两遍 = 14 事件）→ log-likelihood 明显更低 → 判异常含 P004"""
    anomalies = detector.detect(_seq(FLOW * 2))
    assert anomalies, "超长流程序列应被判为流程异常"
    assert any(a.rule_id == "P004_HMM_LOW_LIKELIHOOD" for a in anomalies)


def test_hmm_passes_normal_flow(detector):
    """正常 7 事件流程 → 不报 P004"""
    anomalies = detector.detect(_seq(FLOW))
    assert not any(a.rule_id == "P004_HMM_LOW_LIKELIHOOD" for a in anomalies)


def test_hmm_empty_for_unknown_event_type(detector):
    """含词汇表外 event_type → 返回 []（交规则检测）"""
    seq = _seq(FLOW[:-1] + ["UnknownWeirdEvent"])
    assert detector.detect(seq) == []


def test_hmm_missing_model_returns_empty():
    """模型文件缺失 → detect 返回 [] 且不抛异常"""
    d = ProcessLevelHMMDetector(model_path="nope.pkl", vocab_path="nope.json", threshold_path="nope.json")
    assert d.loaded is False
    assert d.detect(_seq(FLOW)) == []


def test_run_process_detectors_merges_rule_and_hmm(tmp_path):
    """run_process_detectors 合并规则与 HMM：超长且非法迁移序列同时触发 P001 与 P004"""
    mp, vp, tp = _train_temp_hmm(tmp_path)
    hmm = ProcessLevelHMMDetector(model_path=str(mp), vocab_path=str(vp), threshold_path=str(tp))
    # 倒序流程重复两遍：含非法迁移(P001) 且超长(低 ll → P004)
    seq = _seq(list(reversed(FLOW)) * 2)
    anomalies = run_process_detectors(seq, ProcessLevelRuleDetector(), hmm)
    rule_ids = {a.rule_id for a in anomalies}
    assert "P001_ILLEGAL_TRANSITION" in rule_ids
    assert "P004_HMM_LOW_LIKELIHOOD" in rule_ids
