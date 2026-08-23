<p align="center">
  <img src="eventguard-ui/public/brand/logo-2.png" width="110" alt="EventGuard">
</p>

<h1 align="center">EventGuard · 事件卫士</h1>

<p align="center">
  <b>可回放、可审计、可自动检测异常的电商订单事件溯源平台</b>
</p>

---

## 这是什么

EventGuard 不是一个把订单表加了操作日志的管理后台。它把订单的每一次变更都当作**不可变事件**存下来，当前状态由事件回放算出，因此「这笔订单是怎么走到这一步的」永远可以回答，而不是只能看到一个被覆写过的最终结果。

在这条事件流之上，系统进一步做了三件事：把库变更经 CDC 实时推成消息流、在流上分层识别异常订单并给出根因与补偿建议、让运营用中文直接查订单和统计而不必写 SQL。

| 能力 | 实现位置 |
| --- | --- |
| 事件存储与聚合重建 | 后端 `event/`：追加、乐观锁、快照、归档 |
| 命令侧幂等与写一致性 | 后端 `command/`：命令日志、重试模板、事务边界 |
| 读侧投影与最终一致性 | 后端 `query/`：异步投影、消费去重、版本守卫、读己写 |
| CDC 实时管道 | `debezium/` + Kafka：PostgreSQL WAL → `domain-events` |
| 分层异常检测 | AI 服务 `detector/`：业务规则 + 流程规则 + 统计模型 |
| 根因分析与自动补偿 | AI 服务 `analyzer/` + 后端 `compensation/`：Saga 编排与人工审批 |
| 中文自然语言查询 | AI 服务 `query/`：意图分类 + 参数抽取 + 模板执行 |
| 外部依赖抽象 | 后端 `gateway/`：支付 / 库存 / 通知的 Ports & Adapters |
| 可观测与评测 | Micrometer + Prometheus + Grafana + Loki，`eventguard-benchmark/` 一键评测 |

规模：后端 150+ 个 Java 源文件 / 45 个测试类，AI 服务 40+ 个 Python 模块 / 25 个测试模块，前端 49 个 Vue + TS 文件 / 12 个测试文件；测试数字以各模块当前运行报告为准，Docker 依赖测试在无 Docker 环境会跳过，GitHub Actions CI（`.github/workflows/ci.yml`）在每次提交跑三端测试。

## 核心特色

### 1. 事件是唯一事实来源，不是事后补的审计表

审计表是「事后记录」，状态仍靠 UPDATE 覆写，一旦漏写或写失败无从发现，因为状态和审计是两份独立数据。EventGuard 里事件**就是**状态的来源，不存在「状态对但历史丢了」。聚合每 100 个事件落一次快照，重建时读快照再回放增量，事件量增长不会线性拖慢读取。

### 2. 写侧正确性落到具体约束上

并发做了两层：`UNIQUE(aggregate_id, event_version)` 是数据库层的正确性底线，写前的 `MAX(version)` 前置校验负责快速失败与明确报错——两者不重复，因为前置校验和插入之间存在竞态窗口。冲突由重试模板线性退避重试 3 次。

幂等是另一条线：客户端生成的 `commandId` 作为主键落 `command_log`，与事件写入同事务，重复提交返回首次结果而不产生第二条事件。并发到达的相同 `commandId` 在事务内经 `pg_advisory_xact_lock` 串行化，并保存 SHA-256 请求指纹——同一编号复用于不同订单或参数会直接被拒绝。

### 3. 消息由库变更派生，而非应用双写

`eventStore.append()` 和 `kafka.send()` 两行没法做成原子的：库成功消息失败则投影永远落后，消息成功库回滚则下游收到不存在的事件。EventGuard 让 Debezium 读 PostgreSQL WAL 把变更推入 Kafka——事件表本身就是 Outbox，消息成为库变更的必然结果，无需自建轮询发送器。

### 4. 最终一致性的四道防线

| 防线 | 挡的是哪种失败 |
| --- | --- |
| 唯一约束 + 前置版本校验 + 重试 | 并发写产生版本空洞 |
| `command_log` 主键幂等（advisory lock + 参数指纹） | 网络重试导致重复事件、并发/复用命令编号 |
| `idempotent_consumers` 原子占位 + 版本单调守卫 | at-least-once 重复投递、乱序导致状态倒退 |
| 读己写按投影版本等待（通知 + 兜底轮询，50ms / 2s 超时） | 投影滞后时把过期数据当成功返回 |

超时后抛 `ProjectionLagException` 让前端提示「数据同步中」，宁可如实告知，也不返回旧数据假装成功。等待不占 Web 线程：投影事务提交后经 `ProjectionProgressNotifier` 即时唤醒等待请求（Controller 以 `DeferredResult` 返回），50 毫秒轮询降级为共享单线程兜底。

### 5. 分层检测，规则与模型各就各位

业务规则（金额偏离 3σ / 重复支付 / 状态跳跃 / 高频下单 / 库存超卖）与流程规则（停滞 / 死循环）放在 Java 侧，因为要读事件库算用户历史基线——金额基线的均值与标准差由数据库 `stddev_pop` 在 90 天窗口内统计（带 30 秒缓存）；统计模型（IsolationForest 事件级、HMM 序列级）放 Python 侧，经 HTTP 反向调用规则接口后合并结论，HMM 以更保守的似然阈值作为序列级第二意见。告警先落 `anomaly_alerts` 表（重启可查）再经 Kafka 到 WebSocket 秒级推到看板，断线重连按告警 ID 去重补拉。

### 6. 中文查询走窄而可控的路径

意图分类只分三类（查订单 / 查统计 / 查轨迹），参数用正则抽取（订单号、状态词、时间窗），然后调用后端已有的 REST 接口，**全程不拼 SQL 字符串**，从架构上消除注入面。大模型只负责把结构化结果润色成中文回答，8s 超时降级为数据摘要；未配置模型时意图识别降级为关键词匹配，主流程不受影响。

### 7. 失败路径是设计过的

Kafka 消费失败退避重试后进死信队列而非丢弃或死循环——DLT 用纯字符串编码（与主 topic 格式无损往返），并提供 `POST /admin/dlt/{topic}/replay` 管理端受控重放（消费端幂等表兜底重复投递）；告警先落库再广播，发布失败退避重试；Saga 编排器重启后从数据库的 PENDING 审批单重放在途补偿，补偿金额缺失直接进重试/DLT 而非退化为 0 元；Debezium 有健康检查；网关凭证缺失时优雅降级返回失败原因而非崩溃。混沌 profile 会定期随机杀容器，验证故障下能恢复。

## 快速开始

整套服务（PostgreSQL、Kafka、Debezium、后端、AI 服务、前端、监控栈）由 Docker Compose 编排：

```bash
cp .env.example .env
docker compose up -d --build
```

打开 **http://localhost** 进入管理台。首次进入需登录，内置 `admin` / `operator` / `viewer` 三个账号（管理员 / 运营 / 只读），首次登录强制修改密码，初始密码可用 `.env` 中的 `EG_ADMIN_PASSWORD` 等覆盖。线上环境经 Cloudflare Tunnel 以自定义域名 + HTTPS 对外提供访问。

两个密钥务必改为强随机值：`EG_JWT_SECRET`（JWT 签发校验，后端与 AI 服务共用）、`EG_MACHINE_API_KEY`（AI → 后端的内部调用密钥，仅授读订单与规则评估权限）。

```bash
docker compose --profile chaos up -d          # 混沌注入，随机杀容器验证韧性
docker compose --profile bench run --rm bench  # 一键评测，产出量化报告
```

## 走一遍核心链路

1. **订单列表**：筛选订单，观察一笔订单从创建到关闭的状态变化。
2. **订单时间线**：展开任一订单的完整事件序列，这里体现的是事件回放而非状态快照。
3. **自然语言查询**：输入「最近 7 天有多少订单」「订单 X 经历了哪些状态变更」。
4. **异常看板**：异常订单经 WebSocket 实时推送，点开告警可看根因分析。
5. **补偿执行**：选择处理动作并确认，退款 >100 元或冻结订单等高风险动作进入审批流。

## 系统架构

代码目录职责和推荐阅读顺序见 [docs/架构设计/代码结构.md](docs/架构设计/代码结构.md)。

```text
Vue3 管理台
  │  写命令 ──▶ CommandHandler ──▶ 事务{ EventStore.append + command_log }
  │                                   │  乐观锁：唯一约束 + MAX(version) 前置校验
  │                                   │  幂等：advisory lock + 参数指纹
  │                                   │  每 100 事件 → aggregate_snapshots
  │                                   ▼
  │                            PostgreSQL domain_events (WAL)
  │                                   │  Debezium CDC
  │                                   ▼
  │                             Kafka domain-events
  │                     ┌─────────────┼─────────────┬──────────────┐
  │                     ▼             ▼             ▼              ▼
  │            OrderViewProjection SagaTrigger  Python 检测   重试后 → DLT(可重放)
  │            (原子去重+版本守卫)  (补偿编排)  (规则+IF+HMM)
  │                     │ afterCommit 通知               │ 告警
  │  读查询 ◀── DeferredResult ◀─ 通知器 + 兜底轮询       ▼
  │            (50ms 兜底 / 2s 超时)          anomaly_alerts 表 + Kafka anomaly-alerts
  └─ WebSocket ◀── AnomalyWebSocketHandler ◀── 先落库再广播
```

```text
EventGuard/
├── eventguard-server/     # Spring Boot 3.3：事件存储、命令、投影、Saga、网关、鉴权
├── eventguard-ai/         # FastAPI：分层检测、根因分析、终局预测、中文查询
├── eventguard-ui/         # Vue 3 + TypeScript：订单、时间线、异常看板、系统管理
├── eventguard-benchmark/  # 一键评测器：驱动真实全栈并产出量化报告
├── eventguard-chaos/      # Pumba 混沌注入
├── debezium/              # CDC connector 配置
├── prometheus/            # 抓取配置、告警规则、Alertmanager
└── scripts/               # 数据库备份、事件归档
```

## 外部依赖接入

支付、库存、通知都通过网关抽象层（`com.eventguard.gateway`）对接，默认 Mock 实现即可跑通全流程，切换真实 Provider 只改环境变量，不改代码：

| 变量 | 可选值 | 说明 |
| --- | --- | --- |
| `EG_PAYMENT_PROVIDER` | `mock`（默认）\| `alipay` | 支付宝沙箱（需 `EG_ALIPAY_APP_ID` / `EG_ALIPAY_PRIVATE_KEY`） |
| `EG_INVENTORY_PROVIDER` | `mock`（默认）\| `http` | 外部库存服务 REST API（需 `EG_INVENTORY_SERVICE_URL`） |
| `EG_NOTIFY_PROVIDER` | `mock`（默认）\| `wecom` | 企业微信群机器人 webhook（需 `EG_NOTIFY_WECOM_WEBHOOK`） |

**支付是异步意图 + 回调**：`POST /orders/{id}/pay` 先落 `PaymentRequestedEvent`（状态仍 `PENDING_PAYMENT`），协调器调网关后写 `gateway_request`，异步回调经 `POST /gateway/callback/{provider}`（机器密钥校验）派发 `CompletePaymentCommand` → `PaymentCompletedEvent`。库存不足时产生 `InventoryReservationFailedEvent` 触发 R005 告警。

**自动补偿**：支付重试超限或库存预留失败经 `SagaTrigger` 触发补偿步骤（REFUND / MARK_OUT_OF_STOCK / NOTIFY_DELAY），高风险动作挂审批，经 `POST /approvals/{id}/approve|reject` 决策，可用 `EG_SAGA_ENABLED=false` 关闭。Mock 网关的失败率、回调延迟、库存种子均可配，便于演示异常流。

## 运维与可观测

- **指标**：后端 `/actuator/prometheus` 暴露 `eventguard.*`（命令延迟与吞吐、Saga、告警、支付回调、限流拒绝、投影计数）与 Kafka 消费者指标（`kafka.consumer.fetch.manager.records.lag`），AI 服务暴露 `eventguard_ai_*`（检测吞吐与延迟、NL 查询、消费积压 `eventguard_ai_consumer_lag`）。Prometheus 抓取两端，Grafana（`:3001`）预置看板，Alertmanager 覆盖服务不可用、5xx 错误率与消费积压（lag > 1000 持续 5 分钟），webhook 地址经 `ALERT_WEBHOOK_URL` 环境变量注入（compose entrypoint sed，未设置回落本机飞书中继）。
- **日志**：Loki + promtail 采集各容器日志，Grafana 已配好数据源。
- **数据**：`scripts/backup-db.sh`（pg_dump custom 格式，保留 14 天；设置 `BACKUP_UPLOAD_CMD` 可选上传远端，如 rclone）、`scripts/retain-events.sh`（按各聚合快照水位归档旧事件——只归档 `event_version < 快照版本` 的行，保证「快照 + 后续事件」可重建，默认 dry-run）。
- **安全**：JWT + RBAC 覆盖前端路由/菜单/按钮与后端 REST/WebSocket/AI 接口；JWT 带 `token_version`，改密或「退出所有设备」后旧 token 立即失效；登录与用户角色操作写入 `auth_audit_log`；限流按真实用户 IP 分桶（默认 60 次 / 10s，超限 429）。
- **可用性**：Spring 优雅停机 + compose `stop_grace_period`、`GET /health` 公开探针、页脚展示版本与后端/数据库连通状态、CORS 默认同源可放开、前端可安装为 PWA 并适配窄屏。

## 评测

`docker compose --profile bench run --rm bench` 会逐功能驱动真实运行的全栈，覆盖事件溯源一致性 / 读己写 / 幂等、CDC→Kafka 管道延迟、异常检测精度（R001–R005 与 P002/P003 的 P/R/F1 及检测延迟）、中文查询准确率、Saga 补偿成功率、网关异步回调、RBAC 矩阵、限流正确性、资源受限负载吞吐和混沌韧性。读己写与负载抽样均携带写命令返回的 `expectedVersion` 并断言版本、状态与金额；混沌的 Kafka 暂停场景在恢复后按 expectedVersion 验证投影追平，而不止于 topic 可访问。当前负载验收口径见 [面试官视角深挖手册](docs/面试材料/面试官视角深挖手册.md)，不外推为生产容量承诺。

产物是 `benchmark-report.md` / `.json`（canonical schema）+ 自包含 HTML（内嵌图表）+ Grafana dashboard 导入 JSON。每条断言标注驱动方式（`rest` / `kafka_inject` / `db_assert` / `chaos`）；聚合状态机不可达的规则用合成事件注入并如实标注；HMM 未接线、大模型缺失等运行条件一并写进报告——**报告的价值在于能复现和敢标注，而不是数字好看**。

## 设计取舍

**为什么不用 CRUD + binlog？** binlog 记的是行变更（`status: PENDING → PAID`），丢的恰好是业务意图。同一次状态变化可能来自用户支付、运营手改或补偿回滚，事件类型能区分，binlog 不能——而异常检测需要的正是这层语义。

**前置版本校验和唯一约束功能重复吗？** 不重复。查最大版本和插入之间存在竞态窗口，两个并发请求可能都通过校验，所以唯一约束是数据库层面的正确性底线；前置校验的价值是快速失败和明确的错误信息。

**为什么不做 Text2SQL？** 按重要性：模型生成的 SQL 要么敢直接执行（注入面全开），要么得写个比模板方案复杂十倍的 SQL 校验器；生成正确率不稳定，出错时用户看到的是数据库报错；模型不可用时 Text2SQL 完全不可用，而模板方案有关键词兜底。代价是只能回答预设的三类问题，这是主动选的边界——可控的窄能力优于不可控的宽能力。

**读己写为什么只等 2 秒？** 继续阻塞会占用连接和线程，而 2 秒后大概率是投影链路故障而非单纯慢，等下去没有意义。更彻底的做法是前端订阅投影完成通知替代轮询。

**统计模型真的提升了效果吗？** 没有。在合成数据集（正常 2000 + 异常 1427，序列级标注）上做了对照实验：规则 Baseline F1 0.887（P 0.879 / R 0.896 / FPR 0.088），叠加 IsolationForest 后 F1 降到 0.753，两者召回完全相同（0.896，TP 仅从 1278 变成 1279），差异全部来自误报（FP 176 → 691）。根因是 `contamination=0.05` 强制模型按固定比例判异常，与真实分布不匹配。结论保留在评测报告里，模型的定位也因此列入了后续调整计划。

## 后续计划

**检测效果**

- 金额偏离阈值按品类分桶并下调（当前全局 3σ，漏检样本多卡在 2.5σ 附近）。
- 无监督换弱监督：用规则产出的标签训练有监督模型，替代固定 `contamination` 的分布假设。
- 调整模型定位：不与规则并列投票，只在规则未命中的样本上以更保守阈值兜底；HMM 线上效果需实跑 s03 回填数字。

**一致性与规模**

- Saga 实例状态整体落库 + 分布式锁，支持多实例部署下的补偿编排。
- 事件表按 `aggregate_id` 哈希分片，投影表按时间分区并对热数据加缓存。
- 快照阈值由实测的单条事件回放耗时反推，替代当前的经验值 100。

**接入与体验**

- WebSocket 心跳与半开连接剔除，多实例广播经 Redis Pub/Sub。
- 鉴权补齐刷新令牌与主动吊销的全链路一致性（AI 服务与 WS 握手同步校验 token_version）。
- 限流从固定窗口升级为滑动窗口，消除窗口边界的两倍突发。
- 中文查询扩展到更多意图，并对参数抽取做澄清反问。
- 事件 envelope 统一 `schemaVersion` 与 `traceId`，替代按 `event_type` 的隐式约定。
- Flyway/Liquibase 正式迁移替代 `spring.sql.init` 伪迁移与双份 schema。

## 验证

```bash
mvn -q test                                    # 后端 172 项（4 项需 Docker 跑 Testcontainers）
cd eventguard-ai && pytest -q                  # AI 服务 127 项
cd eventguard-ui && npm run test && npm run type-check   # 前端 39 项 + 类型检查
```

幂等与一致性相关的测试用 Testcontainers 真起 PostgreSQL 跑端到端，不 mock 数据库——这两项的正确性完全依赖数据库的约束与事务行为，mock 掉之后测出来的绿色是假的。

## 文档

- 文档索引：[docs/文档索引.md](docs/文档索引.md)
- 当前架构基线：[docs/架构设计/当前架构基线.md](docs/架构设计/当前架构基线.md)
- 历史完整设计文档：[docs/架构设计/系统设计【归档】.md](docs/架构设计/系统设计【归档】.md)
- 架构拓扑图：[docs/架构设计/架构图.svg](docs/架构设计/架构图.svg)
- 架构评审记录（归档）：[docs/架构设计/架构审查-2026-08【归档】.md](docs/架构设计/架构审查-2026-08【归档】.md)
- 本地开发启动顺序：[docs/使用指南/本地运行.md](docs/使用指南/本地运行.md)
- 逐场景走查脚本：[docs/使用指南/演示脚本.md](docs/使用指南/演示脚本.md)
- Cloudflare Tunnel 部署：[docs/部署运维/云端隧道部署.md](docs/部署运维/云端隧道部署.md)
- Linux 服务器部署：[docs/部署运维/服务器部署.md](docs/部署运维/服务器部署.md)
- 生产就绪缺口清单：[docs/部署运维/生产就绪缺口.md](docs/部署运维/生产就绪缺口.md)
- 上线部署记录与已知偏差（归档）：[docs/部署运维/部署记录-2026-08-09【归档】.md](docs/部署运维/部署记录-2026-08-09【归档】.md)
- 验证记录与实测结果：[docs/验证报告/验证记录.md](docs/验证报告/验证记录.md)
- 评测器说明：[eventguard-benchmark/README.md](eventguard-benchmark/README.md)
- 简历描述：[docs/面试材料/最终版简历描述.md](docs/面试材料/最终版简历描述.md)
- 面试官视角深挖手册：[docs/面试材料/面试官视角深挖手册.md](docs/面试材料/面试官视角深挖手册.md)
