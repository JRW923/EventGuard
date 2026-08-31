"""HMM 流程检测训练：用正常订单事件序列训练 CategoricalHMM（离散符号 HMM）

仿 training/train_isolation.py 结构：读 data/normal_events.jsonl，按 aggregate_id 聚成多条
事件序列（每笔订单 = 一个序列，按 created_at 排序），用固定词汇表把 event_type 映射成整数
观测，训练 CategoricalHMM，取训练集序列 log-likelihood 的 5% 分位作为阈值。

ponytail: hmmlearn 0.3.2 中「离散符号 HMM」是 CategoricalHMM（每行一个整型符号索引，
n_features = 符号总数），老的 MultinomialHMM 已改为「多项计数向量」语义。
本数据每条订单的 event_type 序列完全确定（同一 7 步流程），标准 EM 训练出的 HMM 实际是
与顺序无关、主要反映「序列长度/符号组成」的模型：正常订单恒为 7 事件，异常长序列（如支付死循环
12 事件）log-likelihood 明显更低 → 被判异常。规则检测（P001）已覆盖乱序/非法迁移，HMM 作为
补充的「第二意见」。升级路径=引入带顺序变化/更丰富的正常样本，或改用左→右拓扑显式建模步骤。
"""

import json
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

# 允许以 `python training/train_hmm.py` 直接运行（脚本目录不在 sys.path）
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import joblib
import numpy as np
from hmmlearn.hmm import CategoricalHMM

from app.detector.process_level import EVENT_TO_STATE
from training.train_predict import _make_sequence

# 正常流程顺序（放在词汇表前 7 位，使符号索引与流程步骤对齐，便于阅读与调试）
NORMAL_FLOW_ORDER = [
    "OrderCreatedEvent",
    "PaymentCompletedEvent",
    "InventoryReservedEvent",
    "OrderConfirmedEvent",
    "ShippedEvent",
    "DeliveredEvent",
    "OrderClosedEvent",
]
N_COMPONENTS = 5
RANDOM_STATE = 42
# ponytail: 正常数据约 10 万事件（~1.4 万订单）。HMM 训练为 O(序列数×序列长度)，
# 全量训练较慢且易过拟合单批；先最多取 2000 条订单序列训练。
# 升级路径=全量训练 / 在线增量更新（hmmlearn 无原生 partial_fit，需自实现 Baum-Welch 增量）。
MAX_SEQUENCES = 2000
QUANTILE = 0.05           # 阈值取训练集序列 log-likelihood 的分位数

BASE = Path(__file__).resolve().parent.parent  # eventguard-ai 根目录


def _build_vocab(event_types: set[str]) -> dict[str, int]:
    """词汇表：流程符号排在前面（索引 0..6 对齐流程步骤），其余 event_type 随后。"""
    vocab: dict[str, int] = {}
    for et in NORMAL_FLOW_ORDER:
        vocab.setdefault(et, len(vocab))
    for et in EVENT_TO_STATE.keys():
        vocab.setdefault(et, len(vocab))
    for et in sorted(event_types):
        vocab.setdefault(et, len(vocab))
    return vocab


def _legal_branch_sequences(n: int = 600) -> list[list[str]]:
    """合法但走不到 CLOSED 的流程：用户取消 / 退款。

    这些是正常业务操作而非流程异常，必须进训练集——否则词表里 OrderCancelledEvent /
    OrderRefundedEvent 从未作为观测出现，真实订单里凡是取消或退款的都会被判低似然异常。
    复用 train_predict 的序列生成，保证两侧对"合法流程"的口径一致。
    """
    seqs = []
    for i in range(n):
        kind = "CANCELLED" if i % 2 == 0 else "REFUNDED"
        events, _ = _make_sequence(
            kind, f"hmm-legal-{i}", "user-1", 100.0,
            datetime(2026, 7, 1, tzinfo=timezone.utc),
        )
        seqs.append([e["event_type"] for e in events])
    return seqs


def _expand_prefixes(seqs: list[list[int]]) -> list[list[int]]:
    """把每条序列展开成它所有长度的前缀（含全长）。"""
    return [obs[:L] for obs in seqs for L in range(1, len(obs) + 1)]


def _group_sequences(path: str) -> tuple[list[list[dict]], set[str]]:
    """按 aggregate_id 聚合并按 created_at 排序；返回 (序列列表, 全部 event_type 集合)。"""
    by_agg: dict[str, list[dict]] = defaultdict(list)
    event_types: set[str] = set()
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            event = json.loads(line)
            by_agg[event.get("aggregate_id", "")].append(event)
            event_types.add(event.get("event_type", ""))
    sequences = [sorted(evs, key=lambda e: e.get("created_at", "")) for evs in by_agg.values()]
    return sequences, event_types


def train_hmm(
    normal_data_path: str = None,
    model_output: str = None,
    vocab_output: str = None,
    threshold_output: str = None,
    max_sequences: int = MAX_SEQUENCES,
) -> None:
    """训练 HMM 并保存模型/词汇表/阈值"""
    normal_data_path = normal_data_path or str(BASE / "data" / "normal_events.jsonl")
    model_output = model_output or str(BASE / "models" / "hmm.pkl")
    vocab_output = vocab_output or str(BASE / "models" / "hmm_vocab.json")
    threshold_output = threshold_output or str(BASE / "models" / "hmm_threshold.json")

    sequences, event_types = _group_sequences(normal_data_path)
    vocab = _build_vocab(event_types)
    total_orders = len(sequences)
    print(f"正常订单序列总数: {total_orders}；训练用序列上限: {max_sequences}；词汇表大小: {len(vocab)}")

    # 取前 max_sequences 条正常订单序列，跳过含未知 event_type 的
    normal_obs = []
    for seq in sequences[:max_sequences]:
        obs = [vocab.get(e.get("event_type", ""), -1) for e in seq]
        if not any(o < 0 for o in obs):
            normal_obs.append(obs)

    legal_obs = [[vocab.get(t, -1) for t in etypes] for etypes in _legal_branch_sequences()]
    legal_obs = [o for o in legal_obs if not any(x < 0 for x in o)]

    # 每条序列展开成它的所有前缀：在途订单的序列天然是不完整的，只拿完整序列训练
    # 会让所有在途订单因"没走完"被判低似然。前缀展开后，正常进行中的任意时点都落在
    # 训练分布内，HMM 才只对乱序/支付死循环这类真异常敏感。
    obs_sequences = _expand_prefixes(normal_obs + legal_obs)
    print(f"训练序列: 正常 {len(normal_obs)} 条 + 合法分支 {len(legal_obs)} 条 "
          f"→ 前缀展开 {len(obs_sequences)} 条")

    if not obs_sequences:
        raise ValueError("无可用训练序列（词汇表可能与数据不匹配）")

    X = np.concatenate([np.array(s).reshape(-1, 1) for s in obs_sequences])
    lengths = [len(s) for s in obs_sequences]
    print(f"训练矩阵形状: {X.shape}；序列数={len(lengths)}")

    # n_features 显式取词表大小：不传时 hmmlearn 只按训练集出现过的符号推断，
    # 推理侧 process_level_hmm 遇到更大索引就整体跳过（obs >= n_symbols），
    # 结果凡是含支付失败/取消/退款的序列都检测不到，HMM 变成永不报警的摆设。
    model = CategoricalHMM(
        n_components=N_COMPONENTS, random_state=RANDOM_STATE, n_features=len(vocab)
    )
    model.fit(X, lengths)

    # 训练集各序列 log-likelihood，取分位数作阈值
    log_liks = []
    start = 0
    for L in lengths:
        log_liks.append(float(model.score(X[start:start + L], [L])))
        start += L
    threshold = float(np.quantile(log_liks, QUANTILE))
    train_flagged = sum(1 for ll in log_liks if ll < threshold)
    print(f"训练集序列 log-likelihood: min={min(log_liks):.2f} "
          f"p5={np.quantile(log_liks, 0.05):.2f} mean={float(np.mean(log_liks)):.2f}")
    print(f"阈值(ll 分位 {QUANTILE}) = {threshold:.4f}；训练集被判异常比例 = {train_flagged / len(log_liks):.4f}")

    Path(model_output).parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(model, model_output)
    with open(vocab_output, "w", encoding="utf-8") as f:
        json.dump(vocab, f, ensure_ascii=False, indent=2)
    with open(threshold_output, "w", encoding="utf-8") as f:
        json.dump({"threshold": threshold, "quantile": QUANTILE,
                   "n_train_sequences": len(lengths),
                   "n_symbols": int(model.emissionprob_.shape[1])}, f,
                  ensure_ascii=False, indent=2)
    print(f"已保存: {model_output}, {vocab_output}, {threshold_output}")


if __name__ == "__main__":
    train_hmm()
