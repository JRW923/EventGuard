# EventGuard · 事件卫士

<img src="eventguard-ui/public/brand/logo-2.png" alt="EventGuard" width="48" height="48" align="right" />

面向电商订单的**事件溯源 + 智能异常检测 + 中文自然语言查询**管理台。

订单从创建到关闭的每一步都作为不可变事件留存，可随时回放任意历史时刻的状态；
系统在这些事件上自动识别异常订单，并用中文问答的方式帮你查订单、看统计、追轨迹。

## 项目介绍

传统订单系统直接用 UPDATE 覆写状态，出现数据不一致时只能看到最终结果，无法回答「它是怎么走到这一步的」。
EventGuard 把订单的每一次变更都记录为不可变事件，当前状态由事件回放得出，因此审计链路和业务因果关系天然完整。

在这个事件流之上，系统进一步做了两件事：一是实时识别异常订单并给出根因与补偿建议，二是让运营用中文直接查询数据，不必写 SQL。

**设计取向**

- **事件是唯一事实来源**：状态不可覆写，任意历史时刻可回放；聚合每 100 个事件落一次快照，重建只回放增量。
- **变更捕获而非应用双写**：事件落库后由 Debezium 读取 PostgreSQL WAL 推入 Kafka，消息成为库变更的必然结果，避免「库写成功、消息丢失」。
- **一致性落到具体约束上**：唯一约束防版本空洞、命令日志防重复提交、消费去重表与版本单调守卫防重复投递与状态倒退、读己写轮询超时后如实提示「同步中」而不返回过期数据。
- **窄而可控的自然语言能力**：走「意图分类 + 参数抽取 + 调用既有接口」，不让模型直接生成 SQL，从架构上消除注入面，模型不可用时降级为关键词匹配与数据摘要。

**核心能力**

- **事件溯源**：订单全生命周期可回放、可审计，配合乐观锁、命令幂等与快照保证正确性与性能。
- **智能异常检测**：事件流上分层检测（业务规则 + 流程规则 + 统计模型），告警经 WebSocket 秒级推送到看板。
- **中文自然语言查询**：用中文问订单、查统计、追轨迹。
- **根因分析与补偿建议**：给出异常根因与处理建议，人工确认后执行。
- **自动补偿（Saga）**：失败类事件自动触发补偿闭环（退款 / 标记缺货 / 通知用户），高风险动作挂人工审批。
- **网关接入**：支付/库存/通知走 Ports & Adapters 抽象层，默认 Mock 即可全流程演示，可切换支付宝沙箱 / 企业微信 webhook 等真实 Provider。
- **鉴权与权限**：JWT 登录 + RBAC，覆盖前端路由/菜单/按钮与后端 REST/WebSocket/AI 接口。

**技术栈**：Spring Boot 3.3 / Java 17 / PostgreSQL / Kafka / Debezium / FastAPI / scikit-learn / Vue 3 + TypeScript / Docker Compose / Prometheus + Grafana + Loki。

## 架构概览

```
    Vue3 管理台
        │  ├─ 写命令 ─▶ 命令侧：订单事件写入事件库（PostgreSQL）
        │  ├─ 读查询 ─▶ 查询侧：事件投影为可读视图（订单列表 / 时间线 / 统计）
        │  └─ 中文提问 ─▶ AI 服务：异常检测 + 自然语言查询 + 根因分析
        │
   事件库变更经 CDC 流入 Kafka ──▶ AI 服务实时消费并检测异常
   异常告警经 WebSocket 实时推送到前端异常看板
```

核心链路：**订单事件 → 事件库（PostgreSQL）→ CDC（Debezium）→ Kafka → AI 检测 / 查询投影 → 前端看板**。
更完整的拓扑见 [`docs/架构设计/architecture.svg`](docs/架构设计/architecture.svg)。

## 运行方式

整套服务（PostgreSQL、Kafka、Debezium、后端、AI 服务、前端、监控栈）由 Docker Compose 编排，一条命令拉起：

```bash
cp .env.example .env
docker compose up -d --build
```

启动后打开 **http://localhost** 进入管理台（生产镜像映射 80 端口）。首次进入需登录，
内置 `admin` / `operator` / `viewer` 三个默认账号（管理员 / 运营 / 只读），首次登录强制修改密码，
密码可用 `.env` 中 `EG_ADMIN_PASSWORD` 等覆盖。线上环境经 Cloudflare Tunnel 以自定义域名 + HTTPS 对外提供访问。

`EG_JWT_SECRET`（JWT 签发校验，server 与 AI 服务共用）与 `EG_MACHINE_API_KEY`（AI→后端的内部调用密钥）
务必改为强随机值。若需用 IDEA 启动 Spring Boot、单独运行 FastAPI 与 Vite 而不构建业务镜像，
见 [`docs/使用指南/local-development.md`](docs/使用指南/local-development.md)。

- 想看系统韧性？`docker compose --profile chaos up -d` 会定期随机杀掉容器，验证故障下仍能恢复。

## 体验流程

登录后按下面路径走一遍即可看到核心能力：

1. **订单列表**：查看与筛选订单，观察一笔订单从创建到关闭的状态变化。
2. **订单时间线**：展开任一订单的完整事件序列，体现事件回放能力。
3. **自然语言查询**：输入中文，例如「最近 7 天有多少订单」「订单 X 经历了哪些状态变更」。
4. **异常看板**：异常订单经 WebSocket 实时推送到看板；点开任意告警可看根因分析。
5. **补偿执行**：对异常订单选择动作（退款 / 通知 / 冻结等）并确认，高风险动作进入审批流。

> 更完整的逐场景走查见 [`docs/使用指南/demo-script.md`](docs/使用指南/demo-script.md)。

## 网关接入（支付 / 库存 / 通知）

支付/库存/通知均通过**网关抽象层**（`com.eventguard.gateway`）对接，默认 Mock 实现即可全流程演示，
切换真实 Provider 只需改 `.env` 的三个环境变量，无需改代码：

| 变量 | 可选值 | 说明 |
|---|---|---|
| `EG_PAYMENT_PROVIDER` | `mock`（默认）\| `alipay` | 支付宝沙箱网关（需 `EG_ALIPAY_APP_ID` / `EG_ALIPAY_PRIVATE_KEY`） |
| `EG_INVENTORY_PROVIDER` | `mock`（默认）\| `http` | 外部库存服务 REST API（需 `EG_INVENTORY_SERVICE_URL`） |
| `EG_NOTIFY_PROVIDER` | `mock`（默认）\| `wecom` | 企业微信群机器人 webhook（需 `EG_NOTIFY_WECOM_WEBHOOK`） |

**支付是异步意图+回调**：`POST /orders/{id}/pay` 先落 `PaymentRequestedEvent`（状态仍 `PENDING_PAYMENT`），
协调器调网关后写 `gateway_request`（PENDING），异步回调经 `POST /gateway/callback/{provider}`（机器密钥校验）
派发 `CompletePaymentCommand` → `PaymentCompletedEvent`（PAID）。库存预留经 `InventoryGateway`，
不足时产生 `InventoryReservationFailedEvent` 触发 R005 告警。

**自动补偿（Saga）**：失败类事件（支付重试超限 / 库存预留失败）经 `SagaTrigger` 自动触发补偿步骤
（REFUND / MARK_OUT_OF_STOCK / NOTIFY_DELAY）；高风险动作（退款 >100 元、冻结订单）挂起审批，
经 `POST /approvals/{id}/approve|reject` 决策。可用 `EG_SAGA_ENABLED=false` 关闭。

Mock 网关行为可配：`EG_GATEWAY_MOCK_PAYMENT_FAILURE_RATE`（支付失败率，演示异常流）、
`EG_GATEWAY_MOCK_PAYMENT_DELAY_MS`（异步回调延迟）、`EG_GATEWAY_MOCK_SKUS`（内存库存种子）。
真实 Provider 凭证未配置时优雅降级（返回失败原因），不会崩溃。

## 运维能力

- **监控告警**：Prometheus + Grafana（`http://<host>:3001`）+ Alertmanager，指标来自 server 的 `/actuator/prometheus`，告警规则含服务不可用与 5xx 错误率。
- **集中日志**：Loki + promtail 采集各容器日志，Grafana 已预置 Prometheus 与 Loki 数据源。
- **业务指标**：server 暴露 `eventguard.*`（命令延迟/吞吐、Saga、告警、支付回调、限流拒绝、投影计数），AI 服务暴露 `eventguard_ai_*`（检测吞吐/延迟、NL 查询）。
- **备份与保留**：`scripts/backup-db.sh`（pg_dump custom 格式，保留 14 天）、`scripts/retain-events.sh`（归档 90 天前事件，默认 dry-run）。
- **限流**：按真实用户 IP 分桶的固定窗口计数（默认 60 次/10s，超限 429），`eg.rate-limit.*` 可配。
- **审计与令牌**：登录、用户、角色操作写入 `auth_audit_log`；JWT 带 `token_version`，改密或「退出所有设备」后旧 token 立即失效。
- **韧性设计**：Kafka 消费失败退避重试 3 次后进死信队列、告警发布失败退避重试、WebSocket 断线重连按告警 ID 去重补拉最近告警、Debezium 健康检查、Saga 启动时重放在途补偿、自然语言查询 8s 超时自动降级。
- **可用性细节**：Spring 优雅停机 + compose `stop_grace_period`、页脚展示版本与后端/数据库连通状态、`GET /health` 公开探针、CORS 默认同源可经 `EG_CORS_ALLOWED_ORIGINS` 放开、前端可安装为 PWA 且适配窄屏。

## 评测模块（bench）

内置一键评测器，逐功能驱动真实运行的全栈并产出**可观测、可复现、诚实标注**的量化报告：
`docker compose --profile bench run --rm bench`。

- **覆盖**：事件溯源一致性/读己写/幂等、CDC→Kafka 管道延迟、AI 异常检测精度（R001–R005 + P002/P003 的 P/R/F1 与检测延迟）、中文 NL 查询准确率、Saga 自动补偿成功率、网关异步支付回调、RBAC 矩阵、限流正确性、50 并发负载吞吐、混沌韧性（PG 崩溃零丢失与恢复时间）。
- **产物**：`benchmark-report.md` / `.json`（canonical schema）+ 自包含 `.html`（内嵌图表）+ Grafana dashboard 导入 JSON（`eventguard-benchmark/dashboard/`）。
- **诚实性**：每条断言标注驱动方式（`rest` / `kafka_inject` / `db_assert` / `chaos`）；聚合状态机不可达的规则用合成事件注入并如实标注；HMM 未接线、LLM 缺失等运行条件写入报告。

> 详见 [`eventguard-benchmark/README.md`](eventguard-benchmark/README.md)。

## 后续计划

**检测效果**

- 先榨干规则本身的空间：把金额偏离阈值从 3σ 下调并按品类分桶，当前漏检样本大多卡在 2.5σ 附近。
- 无监督换弱监督：用规则产出的标签训练有监督模型，替代 IsolationForest 的固定 `contamination` 假设。
- 调整模型定位：不与规则并列投票，只在规则未命中的样本上以更保守的阈值兜底。
- 把序列级 HMM 真正接入线上链路，目前仅在离线评测中使用。

**一致性与可扩展性**

- Saga 实例状态整体落库 + 分布式锁，支持多实例部署下的补偿编排。
- 消费去重表按时间分区并定期淘汰老分区，避免长期无界增长。
- 事件表按 `aggregate_id` 哈希分片，读侧投影表按时间分区并对热数据加缓存。
- 快照阈值由实测的单条事件回放耗时反推，替代当前的经验值 100。

**接入与体验**

- 异常告警的完整历史检索接口，替代当前仅最近 100 条的环形缓冲。
- WebSocket 心跳与半开连接剔除，多实例广播经 Redis Pub/Sub。
- 鉴权补齐刷新令牌与主动吊销，权限变更即时生效而非重新登录。
- 自然语言查询扩展到更多意图与多轮追问，并对参数抽取做澄清反问。

## 文档

`docs/` 按用途分类整理，索引见 [`docs/README.md`](docs/README.md)。


