# AI vs Baseline 评估报告（序列级）

> 样本量：正常序列 2000 条 + 异常序列 1427 条（各上限 2000/2000，ponytail: 全量约 1.4 万订单，见 evaluate.py 说明）。
> 标注粒度：序列级；一条序列含任一异常事件即判为异常。
> 阈值说明：Baseline 事件级用 `amount_zscore > 3.0` 固定阈值；AI-Enhanced 事件级用 IsolationForest（`contamination=0.05`，见 train_isolation.py）。两者流程级均用 `ProcessLevelRuleDetector`（P001/P002/P003）。

## 结论

- AI-Enhanced F1 = **0.753**，Baseline F1 = **0.887**（Baseline 占优）。
- AI-Enhanced 误报率 FPR = 0.345，Baseline FPR = 0.088。

## 对比表

| 检测器 | Precision | Recall | F1 | FPR |
| --- | ---: | ---: | ---: | ---: |
| Baseline（固定阈值规则） | 0.879 | 0.896 | 0.887 | 0.088 |
| AI-Enhanced（IsolationForest+规则） | 0.649 | 0.896 | 0.753 | 0.345 |

## 混淆矩阵明细

- Baseline: TP=1278 FP=176 FN=149 TN=1824
- AI-Enhanced: TP=1279 FP=691 FN=148 TN=1309

## 说明

- 金额偏离（AMOUNT_DEVIATION）为单事件 OrderCreatedEvent，金额 ~150（≈2.5σ），固定阈值 3.0 不触发，故 Baseline 主要靠流程规则（P002/P003），对金额偏离召回弱；AI-Enhanced 依赖 IsolationForest 补位。
- 状态停滞（单 PaymentCompletedEvent，时间戳在 2026-07-01）经 `now` 默认当前时间触发 P002，两检测器均能命中。
- 死循环（PaymentFailed/Retried 重复）触发 P003，两检测器均能命中。
