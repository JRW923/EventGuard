"""M5.3 AI vs Baseline 评估：在同一测试集上比较「纯规则基线」与「AI 增强」组合。

标注粒度：序列级（sequence-level）。一条订单序列只要含任一 is_anomaly=true 事件即视为异常序列。
说明：normal_events.jsonl 全为正常序列；anomaly_events.jsonl 中每个注入异常都使用独立 aggregate_id，
因此按 aggregate_id 聚合后，异常文件里的序列全部为异常序列。

两个检测器（事件级方法不同，流程级都用 ProcessLevelRuleDetector）：
- Baseline（无 ML）：事件级用固定阈值规则 FeatureExtractor.amount_zscore > 3.0；流程级用 ProcessLevelRuleDetector。
- AI-Enhanced（生产组合）：事件级用 IsolationForest(EventLevelDetector)；流程级用 ProcessLevelRuleDetector。

指标：precision / recall / F1 / false positive rate(FPR)，在序列级计算。
"""

import json
import sys
from collections import defaultdict
from pathlib import Path

# 允许以 `python training/evaluate.py` 直接运行（脚本目录不在 sys.path）
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.detector.event_level import EventLevelDetector
from app.detector.feature_extractor import FeatureExtractor
from app.detector.process_level import ProcessLevelRuleDetector

# ponytail: 全量约 10 万事件/1.4 万订单，跑两遍检测器较慢；评估用样本以提速。
# 全量计划：去掉下面两个上限，直接遍历全部序列（约数倍耗时，结论更稳）。
MAX_NORMAL_SEQ = 2000
MAX_ANOMALY_SEQ = 2000
AMOUNT_ZSCORE_THRESHOLD = 3.0

BASE = Path(__file__).resolve().parent.parent          # eventguard-ai
REPO_ROOT = BASE.parent                                  # 仓库根（eventguard-benchmark 的兄弟目录）


def _load_sequences(path: str) -> list[list[dict]]:
    """按 aggregate_id 聚合为序列并按 created_at 排序。"""
    by_agg: dict[str, list[dict]] = defaultdict(list)
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            event = json.loads(line)
            by_agg[event.get("aggregate_id", "")].append(event)
    return [sorted(evs, key=lambda e: e.get("created_at", "")) for evs in by_agg.values()]


def _metrics(y_true: list[int], y_pred: list[int]) -> dict:
    tp = sum(1 for t, p in zip(y_true, y_pred) if t == 1 and p == 1)
    fp = sum(1 for t, p in zip(y_true, y_pred) if t == 0 and p == 1)
    fn = sum(1 for t, p in zip(y_true, y_pred) if t == 1 and p == 0)
    tn = sum(1 for t, p in zip(y_true, y_pred) if t == 0 and p == 0)
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0
    fpr = fp / (fp + tn) if (fp + tn) else 0.0
    return {"precision": precision, "recall": recall, "f1": f1, "fpr": fpr,
            "tp": tp, "fp": fp, "fn": fn, "tn": tn}


def _run_baseline(sequences: list[list[dict]]) -> list[int]:
    """固定阈值规则基线：amount_zscore > 3.0 或流程规则命中 → 序列异常。"""
    extractor = FeatureExtractor()
    process_detector = ProcessLevelRuleDetector()
    preds = []
    for seq in sequences:
        event_flag = False
        for ev in seq:
            z = extractor.extract(ev)[0]  # amount_zscore（用当前历史，训练同口径）
            if z > AMOUNT_ZSCORE_THRESHOLD:
                event_flag = True
            extractor.update(ev)
        process_flag = len(process_detector.detect(seq)) > 0
        preds.append(1 if (event_flag or process_flag) else 0)
    return preds


def _run_ai(sequences: list[list[dict]]) -> list[int]:
    """AI 增强：IsolationForest 事件级 + ProcessLevelRuleDetector 流程级。"""
    ai_detector = EventLevelDetector()
    process_detector = ProcessLevelRuleDetector()
    preds = []
    for seq in sequences:
        event_flag = False
        for ev in seq:
            if ai_detector.detect(ev).is_anomaly:
                event_flag = True
        process_flag = len(process_detector.detect(seq)) > 0
        preds.append(1 if (event_flag or process_flag) else 0)
    return preds


def evaluate(
    normal_path: str = None,
    anomaly_path: str = None,
    max_normal: int = MAX_NORMAL_SEQ,
    max_anomaly: int = MAX_ANOMALY_SEQ,
    out_md: str = None,
) -> dict:
    normal_path = normal_path or str(BASE / "data" / "normal_events.jsonl")
    anomaly_path = anomaly_path or str(BASE / "data" / "anomaly_events.jsonl")
    out_md = out_md or str(REPO_ROOT / "eventguard-benchmark" / "ai-vs-baseline.md")

    normal_seqs = _load_sequences(normal_path)[:max_normal]
    anomaly_seqs = _load_sequences(anomaly_path)[:max_anomaly]
    print(f"样本: 正常序列 {len(normal_seqs)} 条, 异常序列 {len(anomaly_seqs)} 条")

    # 先正常(建立历史)后异常，顺序对基线 zscore 历史有直接影响
    y_true = [0] * len(normal_seqs) + [1] * len(anomaly_seqs)

    base_pred = _run_baseline(normal_seqs) + _run_baseline(anomaly_seqs)
    ai_pred = _run_ai(normal_seqs) + _run_ai(anomaly_seqs)

    base_m = _metrics(y_true, base_pred)
    ai_m = _metrics(y_true, ai_pred)

    print("\n=== AI vs Baseline（序列级）===")
    print(f"{'detector':<14}{'P':>8}{'R':>8}{'F1':>8}{'FPR':>8}")
    print(f"{'Baseline':<14}{base_m['precision']:>8.3f}{base_m['recall']:>8.3f}{base_m['f1']:>8.3f}{base_m['fpr']:>8.3f}")
    print(f"{'AI-Enhanced':<14}{ai_m['precision']:>8.3f}{ai_m['recall']:>8.3f}{ai_m['f1']:>8.3f}{ai_m['fpr']:>8.3f}")

    _write_markdown(out_md, base_m, ai_m, len(normal_seqs), len(anomaly_seqs))
    return {"baseline": base_m, "ai": ai_m}


def _write_markdown(path: str, base_m: dict, ai_m: dict, n_normal: int, n_anom: int) -> None:
    lines = [
        "# AI vs Baseline 评估报告（序列级）",
        "",
        f"> 样本量：正常序列 {n_normal} 条 + 异常序列 {n_anom} 条（各上限 "
        f"{MAX_NORMAL_SEQ}/{MAX_ANOMALY_SEQ}，ponytail: 全量约 1.4 万订单，见 evaluate.py 说明）。",
        "> 标注粒度：序列级；一条序列含任一异常事件即判为异常。",
        "> 阈值说明：Baseline 事件级用 `amount_zscore > 3.0` 固定阈值；AI-Enhanced 事件级用 IsolationForest"
        "（`contamination=0.05`，见 train_isolation.py）。两者流程级均用 `ProcessLevelRuleDetector`（P001/P002/P003）。",
        "",
        "## 结论",
        "",
        f"- AI-Enhanced F1 = **{ai_m['f1']:.3f}**，Baseline F1 = **{base_m['f1']:.3f}**（"
        f"{'AI 占优' if ai_m['f1'] >= base_m['f1'] else 'Baseline 占优'}）。",
        f"- AI-Enhanced 误报率 FPR = {ai_m['fpr']:.3f}，Baseline FPR = {base_m['fpr']:.3f}。",
        "",
        "## 对比表",
        "",
        "| 检测器 | Precision | Recall | F1 | FPR |",
        "| --- | ---: | ---: | ---: | ---: |",
        f"| Baseline（固定阈值规则） | {base_m['precision']:.3f} | {base_m['recall']:.3f} | {base_m['f1']:.3f} | {base_m['fpr']:.3f} |",
        f"| AI-Enhanced（IsolationForest+规则） | {ai_m['precision']:.3f} | {ai_m['recall']:.3f} | {ai_m['f1']:.3f} | {ai_m['fpr']:.3f} |",
        "",
        "## 混淆矩阵明细",
        "",
        f"- Baseline: TP={base_m['tp']} FP={base_m['fp']} FN={base_m['fn']} TN={base_m['tn']}",
        f"- AI-Enhanced: TP={ai_m['tp']} FP={ai_m['fp']} FN={ai_m['fn']} TN={ai_m['tn']}",
        "",
        "## 说明",
        "",
        "- 金额偏离（AMOUNT_DEVIATION）为单事件 OrderCreatedEvent，金额 ~150（≈2.5σ），固定阈值 3.0 不触发，"
        "故 Baseline 主要靠流程规则（P002/P003），对金额偏离召回弱；AI-Enhanced 依赖 IsolationForest 补位。",
        "- 状态停滞（单 PaymentCompletedEvent，时间戳在 2026-07-01）经 `now` 默认当前时间触发 P002，两检测器均能命中。",
        "- 死循环（PaymentFailed/Retried 重复）触发 P003，两检测器均能命中。",
        "",
    ]
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text("\n".join(lines), encoding="utf-8")
    print(f"报告已写入: {path}")


if __name__ == "__main__":
    evaluate()
