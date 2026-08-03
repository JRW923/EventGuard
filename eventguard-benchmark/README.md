# EventGuard 评测模块（bench）

逐功能驱动真实运行的全栈，产出**可观测、可复现、诚实标注**的量化报告，用于证明平台各功能效果。

## 快速开始（云服务器 / 有 Docker 全栈运行中）

```bash
# 前置：.env 中确保支付回调确定性（异步演示也兼容）
#   EG_GATEWAY_MOCK_PAYMENT_DELAY_MS=0   （0 时 mock 回调同步完成，延迟分位最干净）
#   EG_SAGA_ENABLED=true

docker compose up -d --build                       # 起全栈（含新指标埋点）
docker compose --profile bench run --rm bench      # 跑 functional（s01–s08 + s10）
ls eventguard-benchmark/out/                       # benchmark-report.{md,json,html}
```

- **functional**（默认）：正确性 / 准确率 / 延迟 —— s01 事件溯源、s02 CDC 管道、s03 异常检测精度、
  s04 NL 查询、s05 Saga 补偿、s06 网关异步支付、s07 鉴权 RBAC、s08 限流、s10 韧性（导入）。
- **load**：吞吐 / 延迟负载测试 —— 需限流关闭：
  ```bash
  # 1) .env 设 EG_RATE_LIMIT_ENABLED=false
  # 2) 重启 server：docker compose up -d --build eventguard-server
  # 3) 跑负载：BENCH_SUITES=load docker compose --profile bench run --rm bench
  ```
- **all**：functional + load（限流开启时 load 自动 SKIPPED 并给提示）。
- `--dry-run`：仅健康检查 + 登录 + CDC 预热（快速冒烟）。

## 韧性（混沌）评测

bench 容器**不挂 docker.sock**（免特权），混沌注入由宿主机执行：

```bash
bash eventguard-benchmark/chaos_run.sh     # db-kill / kafka-pause / ai-delay + 计时
```

产出 `eventguard-benchmark/out/chaos-results.json`；再跑一次 `bench`（functional）即把恢复时间、
数据零丢失合并进报告（s10）。`eventguard-chaos/` 原有脚本保持原样可用。

## 评测套件一览

| 套件 | 内容 | 关键指标 |
|---|---|---|
| s01 事件溯源/CQRS | 全生命周期命令、读己写、幂等重放、独立状态机回放一致性 | 收敛 p95、幂等 0 重复事件 |
| s02 CDC→Kafka | Debezium 捕获 + 投影收敛 wall-clock | CDC p95、投影收敛 p95 |
| s03 异常检测精度 | R001–R005 + P002/P003 注入 + 20 正常对照 | P/R/F1、逐规则命中、检测延迟 p95 |
| s04 NL 查询 | 8 题 curated（3 意图），断言 intent+data | 准确率、p95、llm_mode |
| s05 Saga 补偿 | 重试超限→REFUND(审批)+NOTIFY、库存失败补偿 | 成功率、e2e 延迟 p95 |
| s06 网关异步支付 | pay→回调→PAID、gateway_request 终态 | 回调往返 p95 |
| s07 鉴权/RBAC | 角色×端点矩阵、机器密钥、匿名/坏 token、WS 握手 | 通过率 |
| s08 限流 | 突发 60+ 次 429、/health 豁免、窗口复位 | 实测阈值 |
| s09 负载 | 50 并发稳态（限流关闭） | QPS、p50/p95/p99、错误率 |
| s10 韧性 | 导入 chaos_run.sh 结果 | 恢复时间、数据零丢失 |

## 诚实性约定（可观测数据的可信基础）

- 每条断言带 `method`：`rest`（真实 HTTP 命令路径）/ `kafka_inject`（合成事件：DB 追加 +
  直发 Kafka，聚合状态机不可达的规则 R002/R003/P002/P003 用此注入，已在报告标注）/
  `db_assert` / `chaos`。
- 延迟一律以 bench 自身 wall-clock 为准（Prometheus 15s 抓取仅作看板参考）。
- R002/R003/P002/P003 注入事件绕过聚合状态机，状态跳跃类规则（R003/P001）伴随触发属预期，计入 FP。
- HMM 未接线（AI `hmm_detector=None`），精度数字只反映「规则引擎 + IsolationForest + 流程规则」实际链路。
- LLM 缺失时 NL 查询走关键词兜底，报告标注 `llm_mode`（断言 intent+data，模式无关）。
- 种子账号首次运行会被**收敛密码到 `BENCH_PASSWORD`**（幂等副作用，文档化）。

## 报告产物

`eventguard-benchmark/out/`（已 gitignore）：
- `benchmark-report.md` —— 摘要 KPI + 逐功能 方法→断言→指标→结论
- `benchmark-report.json` —— 机器可读（canonical schema：run / executive_summary.headline_kpis / features[]）
- `benchmark-report.html` —— 自包含（base64 内嵌 matplotlib 图表），可离线打开贴简历/作品集
- `dashboard/eventguard-benchmark.json` —— Grafana dashboard 导入 JSON（Grafana `http://<host>:3001` → Import）

Grafana 面板数据来自新埋点：server 端 `eventguard.*`（命令延迟/吞吐、Saga、告警、支付回调、限流拒绝、
投影计数）与 AI 端 `eventguard_ai_*`（检测吞吐/延迟、NL 查询），Prometheus 已配置抓取 AI（`prometheus.yml`）。

## 本地开发 / 单测

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/pytest tests/ -q        # 纯函数单测（timeutil / state_machine / scenario_inject / report model，无需栈）
```
