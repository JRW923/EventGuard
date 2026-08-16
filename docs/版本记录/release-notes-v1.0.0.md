# EventGuard v1.0.0：MVP 发布说明

首个完整可演示版本。MVP 全部任务（M1-M5 + 可选 M3.9）完成，含 V2 主线鉴权/异步化加固与局部增强。

## 核心能力
- **事件溯源 + CQRS**：Spring Boot 命令/查询分离，PostgreSQL append-only 事件表，Debezium CDC → Kafka `domain-events`。
- **AI 异常检测**：规则引擎 + IsolationForest + HMM 流程级检测（规则第二意见）+ LLM 根因分析（无 Ollama 时关键词兜底）。
- **NL 查询**：中文自然语言 → 结构化查询（IntentRouter + SQLBuilder）。
- **前端**：Vue3 + Element Plus + ECharts，订单列表 / NL 查询框 / 异常看板 / 补偿执行 / 事件时间线。
- **端点鉴权（v1.1 起为登录 + RBAC）**：REST `Authorization: Bearer <JWT>` + WS `?token=`，用户-角色-权限三级模型（`admin`/`operator`/`viewer` 种子账号，首次登录强制改密）；内部服务走 `EG_MACHINE_API_KEY` 机器密钥（受限权限）。早期 v1.0.0 为单一 `X-API-Key`，已废弃。
- **AI 服务异步化**：全链路 `httpx.AsyncClient`，不再阻塞事件循环。

## 本版本新增（相对 v0 基线）
- **M3.9** HMM 流程级异常检测（`hmmlearn` `CategoricalHMM`，`training/train_hmm.py`，`models/hmm.pkl`）。
- **M5.2** Pumba 混沌实验脚本（`db-kill` / `kafka-pause` / `ai-delay` / `verify`）。
- **M5.3** AI vs Baseline 评估（`training/evaluate.py` + `eventguard-benchmark/ai-vs-baseline.md`）。
- **M5.4** Gatling 压测仿真（P95<500ms 断言）。
- **M5.5** 5 分钟 Demo 走查脚本（`docs/使用指南/demo-script.md`；mp4 需人工录制）。
- **M5.6** 架构图改为文本 SVG（`docs/架构设计/architecture.svg`）。
- **V2 局部增强**：投影延迟 Micrometer `Timer`/`Counter`（读己写超时计入 `eventguard.projection.lag`）+ 时间线按 `upToVersion` 版本回放。

## 已知限制
- 网关默认走 mock（`EG_*_PROVIDER=mock`）；支付为异步回调形态，真实 Provider（支付宝/企业微信 webhook）需在 `.env`
  配置凭证，未配置时优雅降级为失败原因。Saga 实例为内存态，重启即清。
- 端到端 Testcontainers 测试默认跳过（需本地 Docker）。
- AI 服务无历史告警列表接口，异常仅经 WebSocket 推送。

## 未做（V2 主线进阶，MVP 有意推迟）
- Text-to-SQL 全量沙箱、ReAct Agent 自愈、Jepsen 形式化验证。
- 网关真实 Provider 对接需外部凭证（沙箱/正式商户号），当前以 mock 演示 + HTTP 适配器示例提供接缝。

## 提交区间
`8688d8d..f7e4a65`（8 个提交）：`fa0aca1` M3.9 · `8363a88` M5.3 · `c964230` M5.2 · `efc1551` M5.4 · `12aeaa0` M5.5 · `62bf239` M5.6 · `3f5eca6` V2 局部增强 · `f7e4a65` 文档状态回填。

tag: `v1.0.0` · commit: `f7e4a65`
