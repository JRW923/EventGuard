# EventGuard · 事件卫士

<img src="eventguard-ui/public/brand/logo-2.png" alt="EventGuard" width="48" height="48" align="right" />

面向电商订单的**事件溯源 + 智能异常检测 + 中文自然语言查询**管理台。

订单从创建到关闭的每一步都作为不可变事件留存，可随时回放任意历史时刻的状态；
系统在这些事件上自动识别异常订单，并用中文问答的方式帮你查订单、看统计、追轨迹。

整套服务用一条命令起在本地，开箱即可演示。

## 项目介绍

EventGuard（事件卫士）是一个面向电商订单的**事件溯源 + AI 异常检测**平台。

- **为什么需要它**：传统微服务直接覆写数据库状态，一旦数据不一致，难以回溯「是怎么走到这一步的」。EventGuard 把订单每一次状态变更都记录为不可变事件（事件溯源 + CQRS），天然形成完整的业务因果链，便于审计与回放。
- **核心能力**
  - **事件溯源**：订单全生命周期可回放、可审计，配合乐观锁与幂等命令保证最终一致性。
  - **智能异常检测**：在事件流上分层检测异常（规则引擎 + 统计模型），经 WebSocket 实时推送告警。
  - **中文自然语言查询**：用中文问订单、查统计、追轨迹，无需写 SQL。
  - **根因分析与补偿建议**：AI 给出异常根因与处理建议，人工确认后执行。
  - **自动补偿（Saga）**：失败类事件自动触发补偿闭环（退款 / 标记缺货 / 通知用户），高风险动作挂人工审批。
  - **网关接入**：支付/库存/通知走 Ports & Adapters 抽象层，默认 Mock 即可全流程演示，可切换支付宝沙箱 / 企业微信 webhook 等真实 Provider。
  - **登录与权限管理**：JWT 登录 + 用户-角色-权限（RBAC），覆盖前端路由/菜单/按钮与后端 REST/WebSocket/AI 接口的完整鉴权。
- **技术亮点**：事件溯源 + 快照、Debezium CDC → Kafka 实时管道、Testcontainers 一致性测试、Pumba 混沌验证。

## 架构概览

```
    Vue3 管理台 (localhost:3000)
        │  ├─ 写命令 ─▶ 命令侧：订单事件写入事件库（PostgreSQL）
        │  ├─ 读查询 ─▶ 查询侧：事件投影为可读视图（订单列表 / 时间线 / 统计）
        │  └─ 中文提问 ─▶ AI 服务：异常检测 + 自然语言查询 + 根因分析
        │
   事件库变更经 CDC 流入 Kafka ──▶ AI 服务实时消费并检测异常
   异常告警经 WebSocket 实时推送到前端异常看板
```

核心链路：**订单事件 → 事件库（PostgreSQL）→ CDC（Debezium）→ Kafka → AI 检测 / 查询投影 → 前端看板**。
更完整的拓扑见 [`docs/architecture.svg`](docs/architecture.svg)。

## 快速开始

```bash
cp .env.example .env
docker compose up -d --build
```

启动后打开 **http://localhost** 即可使用管理台（生产镜像映射 80 端口；本地热更新开发用 Vite dev server 的 3000 端口）。
**首次进入请先登录**，默认账号见下文「[登录与权限管理](#登录与权限管理)」。

- 想看系统韧性？`docker compose --profile chaos up -d` 会定期随机杀掉容器，验证故障下仍能恢复。

## 体验流程

服务起来后，用默认账号登录管理台（见「[登录与权限管理](#登录与权限管理)」），按下面路径走一遍即可看到核心能力：

1. **登录与权限**：首次登录会强制修改默认密码；不同角色（管理员/运营/只读）看到的菜单与按钮权限不同，可在「系统管理」中维护用户与角色。
2. **订单列表**：查看与筛选订单，观察一笔订单从创建到关闭的状态变化。
3. **自然语言查询**：在查询框输入中文，例如"最近 7 天有多少订单""订单 X 经历了哪些状态变更"，系统返回对应结果。
4. **异常看板**：异常订单经 WebSocket 实时推送到看板；点开任意告警可看根因分析。
5. **补偿执行**：对异常订单选择动作（退款 / 通知 / 冻结等）并确认，动作仅生成人工可读的处理说明。

> 更完整的逐场景走查见 [`docs/demo-script.md`](docs/demo-script.md)。

## 登录与权限管理

系统内置**登录 + RBAC（用户-角色-权限）鉴权**，覆盖前端路由/菜单/按钮与后端 REST/WebSocket/AI 接口。

**默认账号**（首次登录强制修改密码）：

| 账号 | 角色 | 权限 |
|---|---|---|
| `admin` | 管理员 | 全部权限（含用户/角色管理） |
| `operator` | 运营 | 下单、状态操作、异常处理、补偿执行 |
| `viewer` | 只读 | 查看订单、异常看板、自然语言查询 |

默认密码为 `admin123456` / `operator123456` / `viewer123456`（可用 `.env` 中 `EG_ADMIN_PASSWORD` 等覆盖），角色与权限可在控制台「系统管理」中维护。

**鉴权要点**：

- 用户经 `POST /auth/login` 获取 JWT（默认 12h 有效），前端存 localStorage，随 `Authorization: Bearer` 头发送；WebSocket 用 `?token=` 传递。
- `EG_JWT_SECRET`：签发/校验 JWT（server 与 AI 服务共用），**生产务必改为强随机值**。
- `EG_MACHINE_API_KEY`：AI→后端、压测/混沌工具的内部调用密钥，仅授受限权限（读订单、规则评估）。

## 已知限制

- **异常无历史列表**：告警仅实时推送，暂无按历史检索的接口。
- **鉴权为 JWT + localStorage**：无刷新令牌/吊销机制，角色或权限变更需重新登录生效；XSS 风险与常见管理台同级别。
- **大模型根因为可选**：未配置本地模型时自动降级为关键词 / 数据摘要，不影响主流程。
- **Saga 实例为内存态**：自动补偿编排器状态存于单实例内存（重启即清），审批单持久化到 DB；多实例/可恢复留给后续。
- **支付异步回调演示走 mock 网关**：默认 `EG_PAYMENT_PROVIDER=mock`，无需凭证即可演示「发起支付 → 回调 → PAID」全流程；真实 Provider 见下方「网关接入」。

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

## 后续计划

- 更丰富的自然语言能力与一致性验证。
- 生产就绪基建：见 [`docs/gaps-prod-readiness.md`](docs/gaps-prod-readiness.md)（P0/P1 已落地，P2 待排期）。

## 运维能力（P0/P1/P2 已落地）

- **监控告警**：Prometheus + Grafana（`http://<host>:3001`，默认 admin/admin）+ Alertmanager。
  指标来自 server 的 `/actuator/prometheus`；告警规则含 server-down / 5xx 错误率，
  转发 webhook 见 `prometheus/alertmanager.yml`。
- **集中日志**：Loki + promtail 采集各容器日志，Grafana 已自动配好 Prometheus + Loki 数据源。
- **数据备份**：`scripts/backup-db.sh`（pg_dump custom 格式，保留 14 天，含 crontab 示例）。
- **数据保留**：`scripts/retain-events.sh`（归档 90 天前事件到 `event_store_archive`，默认 dry-run）。
- **通用限流**：per-IP 滑动窗口（默认 60 次/10s，超限 429），`eg.rate-limit.*` 可配。
- **审计日志**：登录/用户/角色操作写入 `auth_audit_log`，管理员在「系统管理 → 审计日志」查看。
- **优雅停机**：Spring graceful shutdown + compose `stop_grace_period`。
- **令牌管理**：JWT 带 token_version，「退出所有设备」/改密后旧 token 立即失效（个人中心 → 安全）。
- **版本/健康**：页脚显示版本号与后端/数据库连通状态；`GET /health` 公开端点。
- **CORS**：默认同源；`EG_CORS_ALLOWED_ORIGINS`（逗号分隔）开放跨域给第三方。
- **PWA / 移动端**：可安装为 PWA（manifest.webmanifest）；窄屏自动换行、菜单横向滚动。
- **四维加固（一致性/可用性/安全性/体验度）**：限流按真实用户 IP 分桶（反代后不再全站单桶）、
  AI 告警发布失败退避重试、最近告警环形缓冲 + `GET /alerts/recent`（WS 断线补拉不丢告警）、
  Debezium 健康检查、Saga 启动重放恢复（PENDING 审批单重启不中断补偿）、NL 查询 8s 超时自动降级。
  详见 [`docs/gaps-prod-readiness.md`](docs/gaps-prod-readiness.md)「2026-08 二次加固」。

## 评测模块（bench）

内置一键评测器，逐功能驱动真实运行的全栈并产出**可观测、可复现、诚实标注**的量化报告
（适合简历引用与工程验收）：`docker compose --profile bench run --rm bench`。

- **覆盖**：事件溯源一致性/读己写/幂等、CDC→Kafka 管道延迟、AI 异常检测精度（R001–R005+P002/P003
  的 P/R/F1 与检测延迟）、中文 NL 查询准确率、Saga 自动补偿成功率、网关异步支付回调、RBAC 矩阵、
  限流正确性、50 并发负载吞吐、混沌韧性（PG 崩溃零丢失/恢复时间）。
- **产物**：`benchmark-report.md` / `.json`（canonical schema）+ 自包含 `.html`（内嵌图表）
  + Grafana dashboard 导入 JSON（`eventguard-benchmark/dashboard/`）。
- **可观测数据**：server 新增 `eventguard.*` Micrometer 指标（命令延迟/吞吐、Saga、告警、支付回调、
  限流拒绝、投影计数），AI 新增 `eventguard_ai_*` prometheus_client 指标（检测吞吐/延迟、NL 查询），
  Prometheus 已抓取两端；Grafana 一块基准看板预览全部。
- **诚实性**：每条断言标注驱动方式（`rest` / `kafka_inject` / `db_assert` / `chaos`）；聚合状态机
  不可达的规则用合成事件注入并如实标注；HMM 未接线、LLM 缺失等运行条件写入报告。

> 详见 [`eventguard-benchmark/README.md`](eventguard-benchmark/README.md)。

## 部署

- [Cloudflare Tunnel 免备案 HTTPS 访问](docs/deploy-cloudflare-tunnel.md) —— 不迁服务器、不用备案，用自定义域名 + HTTPS 访问（推荐生产方案）。
- [腾讯云轻量 + 宝塔面板部署](docs/deploy-linux-baota.md) —— 本地写码、推 Git、服务器 `docker compose` 一键起。
