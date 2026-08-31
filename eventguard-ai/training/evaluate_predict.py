"""事件流终局预测评估：独立测试集 → 每类精确率/召回 → 写 eventguard-benchmark/predict-eval.md。

报告路径可用 EG_EVAL_REPORT_PATH 覆盖（容器内跑时把 eventguard-benchmark 挂载到别的目录）。
"""
import json
import os
import sys
from pathlib import Path

import joblib
import numpy as np
from sklearn.metrics import classification_report

# 使 training 包可导入（与 tests/conftest.py 一致的 sys.path 处理）
sys.path.insert(0, str(Path(__file__).parent.parent))

from training.train_predict import LABELS, generate_rows  # noqa: E402

BASE = Path(__file__).parent.parent
MODEL_DIR = BASE / "models"
REPORT_PATH = Path(os.environ.get("EG_EVAL_REPORT_PATH")) if os.environ.get("EG_EVAL_REPORT_PATH") else BASE.parent / "eventguard-benchmark" / "predict-eval.md"


def main(n_total: int = 2000, seed: int = 7) -> None:
    clf = joblib.load(MODEL_DIR / "predictor.pkl")
    meta = json.loads((MODEL_DIR / "predictor_meta.json").read_text(encoding="utf-8"))

    # ponytail: 分布内指标只能说明模型拟合得好，不能证明它适应真实事件流——
    # 后者靠 training/check_real_orders.py 对生产订单做回归检查。
    rows = generate_rows(n_total, seed)
    X = np.array([r[0] for r in rows], dtype=float)
    y = np.array([r[1] for r in rows], dtype=int)
    pred = clf.predict(X)
    acc = float((pred == y).mean())

    lines = [
        "# 事件流终局预测评估",
        "",
        f"- 模型：`models/predictor.pkl`（RandomForest，{meta.get('n_train', '?')} 训练样本）",
        f"- 独立测试集：{len(rows)} 条前缀样本（seed={seed}），总体准确率 **{acc:.4f}**",
        "",
        "| 类别 | 精确率 | 召回率 | F1 | 样本数 |",
        "|---|---|---|---|---|",
    ]
    report = classification_report(y, pred, target_names=LABELS, zero_division=0, output_dict=True)
    for label in LABELS:
        r = report[label]
        lines.append(f"| {label} | {r['precision']:.3f} | {r['recall']:.3f} | {r['f1-score']:.3f} | {int(r['support'])} |")
    lines.append("")

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text("\n".join(lines), encoding="utf-8")
    print(classification_report(y, pred, target_names=LABELS, zero_division=0))


if __name__ == "__main__":
    main()
