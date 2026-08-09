# EventGuard — 电商订单事件溯源与 AI 异常检测平台 设计文档

> 日期: 2026-07-21
> 状态: 已确认（MVP 修订版）
> 定位: 面试作品集 — 展示后端工程能力 + AI 应用架构能力

---

## 1. 项目定位与核心价值主张

### 项目名称

**EventGuard** — 电商订单事件溯源与 AI 异常检测平台

### 为什么要做这个项目（面试叙事线）

传统微服务架构中，业务状态直接覆写到数据库，一旦出现数据不一致，无法回溯"怎么到这一步的"。事件溯源通过记录每一次状态变更事件，保留了完整的因果链——但这也带来了新的挑战：**事件洪流中如何发现异常？**

EventGuard 的核心洞察是：**事件溯源的完整事件链天然就是 AI 异常检测的最佳数据源**。传统监控只看指标（CPU、延迟），而事件溯源能看到"业务语义"——哪个订单卡住了、哪条链路在异常回退。

### 核心价值主张

1. **后端价值**：基于 CQRS + 事件溯源架构，实现订单全生命周期可回溯，保证最终一致性
2. **AI 价值**：分层 AI 能力（事件级检测 → 流程级检测 → 自然语言查询 → 根因分析建议），从被动告警到智能建议
3. **工程价值**：完整验证链路——Testcontainers 一致性测试、Pumba 可用性验证、AI vs 基线对比

### MVP 范围声明

为避免单人开发 scope 失控，明确分层交付。MVP 聚焦"最小可讲完整故事"，V2 为差异化加分项。

| 能力 | MVP（必做） | V2（可选增强） |
|------|------------|---------------|
| 事件溯源核心 | 命令端 + 事件存储 + 快照 + 乐观锁 + 幂等命令 | — |
| CQRS 读模型 | 投影器 + 读己写一致性 | 投影延迟监控告警 |
| CDC 链路 | Debezium → Kafka → 投影/AI 消费 | 死信队列自动化处理 |
| AI 第 1 层 | 规则引擎 + Isolation Forest | 自适应阈值 |
| AI 第 2 层 | 超时规则 + 状态机非法迁移校验 | HMM 序列检测（LSTM 远期探索） |
| AI 第 3 层 | 意图分类 + 模板查询（3 类意图） | 全量 Text-to-SQL |
| AI 第 4 层 | AI 根因分析报告 + 补偿建议（**不执行**） | ReAct Agent + 审批流自动补偿 |
| 验证 | Testcontainers 并发测试 + Pumba 混沌 + AI 基线对比 | Jepsen 形式化（探索性） |
| 前端 | 简化 Admin 看板（订单列表 / 异常列表 / NL 查询框） | 事件时间线编辑器、异常热力图 |

> **设计原则**：MVP 必须能跑通端到端 Demo，每一步都有可讲的技术点；V2 在时间充裕时叠加，不阻塞主链路。

### 面试差异化

| 维度 | 普通项目 | EventGuard |
|------|---------|------------|
| 数据模型 | CRUD 覆写 | 事件溯源 + 快照 |
| 异常检测 | 阈值告警 | 规则 + Isolation Forest 混合 |
| 查询方式 | SQL | 自然语言（意图分类 + 模板） |
| 故障处置 | 人工介入 | AI 根因分析 + 补偿建议 |
| 验证方式 | "能跑" | Testcontainers + Pumba + 对比实验 |
| 一致性证明 | 口头说明 | 并发测试 + 乐观锁冲突演示 |

---

## 2. 架构总览与组件职责

### 系统架构图

```
┌─────────────────────────────────────────┐
│            Frontend (Vue3)              │
│  订单管理 | 异常看板 | NL查询 | 补偿建议  │
└──────────────────┬──────────────────────┘
                   │ REST
┌──────────────────▼──────────────────────┐
│        Spring Boot 主服务 (Port 8080)    │
│  ┌──────────┐  ┌───────────┐  ┌──────┐ │
│  │命令端(C)  │  │查询端(Q)   │  │补偿端│ │
│  │聚合根     │  │读模型重建  │  │Saga  │ │
│  │事件发布   │  │投影       │  │编排  │ │
│  └─────┬────┘  └─────┬─────┘  └──┬───┘ │
└────────┼─────────────┼───────────┼─────┘
         │             │           │
   ┌─────▼─────┐  ┌────▼────┐      │
   │PostgreSQL │  │PostgreSQL│      │
   │(事件表)    │  │(读模型)  │      │
   │event_store│  │order_view│      │
   └─────┬─────┘  └─────────┘      │
         │ CDC                      │
   ┌─────▼─────┐                    │
   │ Debezium  │                    │
   └─────┬─────┘                    │
         │                          │
   ┌─────▼─────┐                    │
   │  Kafka    │◄───────────────────┘
   │(事件通道)  │  补偿命令也走Kafka
   └─────┬─────┘
         │ 消费
   ┌─────▼─────────────────────────┐
   │   Python AI 服务 (Port 8000)   │
   │  ┌────────┐ ┌──────────────┐  │
   │  │事件级   │ │流程级        │  │
   │  │异常检测 │ │异常检测      │  │
   │  │(规则+ML)│ │(规则+HMM)    │  │
   │  └────────┘ └──────────────┘  │
   │  ┌────────┐ ┌──────────────┐  │
   │  │NL查询   │ │根因分析      │  │
   │  │(意图+模板)│ │(LLM建议)    │  │
   │  └────────┘ └──────────────┘  │
   └───────────────────────────────┘
```

> 图中实线为 MVP 必做链路；HMM、Saga 自动执行为 V2，以虚线/标注形式区分。

### 组件职责

| 组件 | 职责 | 关键技术点 | 阶段 |
|------|------|-----------|------|
| **命令端** | 接收业务命令，聚合根校验，写事件 | 聚合根模式、幂等命令、乐观并发控制 | MVP |
| **事件存储** | 持久化事件流，支持回放 | PostgreSQL append-only、WAL、快照 | MVP |
| **查询端** | 从事件投影构建读模型 | CQRS 物化视图、读己写一致性 | MVP |
| **Debezium** | 捕获事件变更推入 Kafka（Transactional Outbox） | CDC、事务日志解析、单向数据流 | MVP |
| **Kafka** | 事件流总线 | 消费者组、分区策略、死信队列 | MVP |
| **AI 事件级检测** | 单事件异常判断 | 规则引擎 + Isolation Forest | MVP |
| **AI 流程级检测** | 事件序列异常模式 | 超时/状态机规则（V2: HMM） | MVP/V2 |
| **AI NL 查询** | 自然语言查询事件历史 | 意图分类 + 模板（V2: RAG + Text-to-SQL） | MVP/V2 |
| **AI 根因分析** | 异常根因解释 + 补偿建议 | LLM 总结 + 规则匹配（V2: ReAct Agent） | MVP/V2 |
| **补偿端 Saga** | 执行补偿命令编排 | Saga 状态机、审批流 | V2 |
| **前端** | 可视化看板 + 操作界面 | Vue3 + ECharts 时间线 | MVP |

### 数据流关键路径

**写路径**（命令 → 事件存储 → 读模型）：

```
用户下单 → OrderCommand → OrderAggregate → OrderCreatedEvent
→ PG 事件表（事务提交）→ Debezium CDC → Kafka → 读模型投影更新
```

> **关键决策**：应用层**只写事件表**，不直接 publish 到 Kafka。事件发布完全由 Debezium CDC 捕获，避免"双写不一致"（DB 写成功但 Kafka 失败）。这是 Transactional Outbox 模式的标准实现。

**AI 路径**（事件 → 检测 → 告警/建议）：

```
Kafka 事件 → AI 服务消费 → 事件级检测(同步) → 流程级检测(窗口)
→ 异常告警推送 → 根因分析(MVP) / 自愈 Agent(V2)
```

---

## 3. 事件溯源核心模型

### 领域事件定义

电商订单的核心事件流：

```
OrderCreated → PaymentInitiated → PaymentCompleted → InventoryReserved
→ OrderConfirmed → ShippingScheduled → Shipped → Delivered → OrderClosed
```

异常分支事件：

```
PaymentFailed → PaymentRetried → PaymentTimeout
InventoryInsufficient → OrderCancelled
ShipmentDelayed → ShipmentLost
OrderRefundRequested → OrderRefunded
```

### 事件存储表设计

```sql
-- 事件表 (append-only)
CREATE TABLE domain_events (
    event_id        UUID PRIMARY KEY,
    aggregate_id    UUID NOT NULL,           -- 聚合根ID (订单ID)
    aggregate_type  VARCHAR(64) NOT NULL,    -- 聚合根类型 (Order)
    event_type      VARCHAR(128) NOT NULL,   -- 事件类型
    event_version   INT NOT NULL,            -- 事件版本号 (乐观锁)
    payload         JSONB NOT NULL,          -- 事件负载
    metadata        JSONB,                   -- 元数据 (traceId, userId等)
    created_at      TIMESTAMPTZ NOT NULL,
    UNIQUE (aggregate_id, event_version)     -- 并发写入保护
);

-- 快照表 (加速回放)
CREATE TABLE aggregate_snapshots (
    aggregate_id    UUID PRIMARY KEY,
    aggregate_type  VARCHAR(64) NOT NULL,
    version         INT NOT NULL,
    state           JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);

-- 幂等消费记录
CREATE TABLE idempotent_consumers (
    consumer_group  VARCHAR(64) NOT NULL,
    event_id        UUID NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);

-- 命令日志（幂等命令处理）
CREATE TABLE command_log (
    command_id      UUID PRIMARY KEY,
    aggregate_id    UUID NOT NULL,
    command_type    VARCHAR(128) NOT NULL,
    result          JSONB,
    executed_at     TIMESTAMPTZ NOT NULL
);
```

### 聚合根核心逻辑

```java
public class OrderAggregate {
    private UUID orderId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private List<DomainEvent> pendingEvents;
    private int version;

    public void handle(CreateOrderCommand cmd) {
        if (status != null) throw new IllegalStateException("订单已存在");
        apply(new OrderCreatedEvent(cmd.getOrderId(), cmd.getItems(), cmd.getTotalAmount()));
    }

    public void handle(PaymentCompletedEvent event) {
        if (status != OrderStatus.PENDING_PAYMENT)
            throw new IllegalStateException("订单状态不匹配");
        apply(event);
    }

    private void apply(DomainEvent event) {
        pendingEvents.add(event);
        if (event instanceof OrderCreatedEvent) status = OrderStatus.PENDING_PAYMENT;
        else if (event instanceof PaymentCompletedEvent) status = OrderStatus.PAID;
        // ... 完整状态机
    }
}
```

### CQRS 读模型

```sql
CREATE TABLE order_view (
    order_id        UUID PRIMARY KEY,
    status          VARCHAR(32),
    total_amount    DECIMAL(12,2),
    payment_time    TIMESTAMPTZ,
    shipping_time   TIMESTAMPTZ,
    version         INT,
    updated_at      TIMESTAMPTZ
);
```

### 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 事件发布路径 | Transactional Outbox（CDC 单向） | 应用层只写事件表，避免 DB/Kafka 双写不一致 |
| 事件格式 | JSONB | PostgreSQL 原生支持索引+查询，避免引入 Schema Registry |
| 快照策略 | 每 100 个事件打一次快照 | 平衡回放性能与存储开销 |
| 并发控制 | 乐观锁 (event_version) | 事件溯源天然适合乐观锁，冲突时重放即可 |
| 事件不可变 | 事件表只 INSERT | 事件是事实记录，永远不修改/删除，修正用补偿事件 |
| 幂等消费 | 消费者去重表 | 保证 Kafka 消费端 Exactly-Once 语义 |
| 命令幂等 | command_log 表 | 同一 commandId 重复提交只执行一次 |

---

## 4. AI 异常检测能力

> MVP 聚焦"分层渐进"：先规则后 ML，先检测后建议。每层独立可用，可独立验证。

### 第 1 层：事件级异常检测（MVP 必做，实时）

**目标**：每个事件经过时立刻判断是否异常

| 检测项 | 规则/模型 | 示例 |
|--------|----------|------|
| 金额异常 | Z-Score 规则 | 订单金额偏离用户历史均值 3σ |
| 重复支付 | 幂等规则 | 同一订单短时间内 2 次 PaymentCompleted |
| 状态跳跃 | 状态机规则 | 从 CREATED 直接跳到 SHIPPED，跳过 PAID |
| 库存越界 | 阈值规则 | InventoryReserved 数量 > 实际库存 |
| 高频操作 | 滑动窗口 | 同一用户 1 分钟内创建 20 个订单 |

**检测流程**：

```
事件 → [规则引擎(Java, <1ms)] → 命中? → 告警（高优先级）
                                → 未命中 → [Isolation Forest(Python, ~5ms)] → 异常? → 告警（低优先级）
```

### 第 2 层：流程级异常检测

| 阶段 | 模式 | 检测方法 |
|------|------|---------|
| **MVP** | 状态停滞 | 超时规则：PAID 超过 24h 未进入 CONFIRMED |
| **MVP** | 异常回退 | 状态机校验：SHIPPED → PAID（非法回退） |
| **MVP** | 死循环 | 计数规则：PaymentFailed → Retried 重复 >5 次 |
| **V2** | 流程变异 | HMM 计算序列概率，低概率转移告警 |
| **远期** | 复杂序列 | LSTM 自编码器重建误差（仅作技术探索，不纳入 MVP 验证） |

> **为什么砍 LSTM**：LSTM 训练、调参、可解释性都较重，且在合成数据上效果难以令人信服。HMM + 规则已覆盖主要场景，面试可讲"为什么选 HMM 而不是 LSTM"反而是加分点。

**MVP 检测流程**：

```
Kafka 消费 → 按 aggregate_id 分组 → 维护滑动窗口(最近20个事件)
→ 状态机校验（非法迁移?）→ 超时检查（停滞?）→ 计数检查（死循环?）
```

### 第 3 层：自然语言事件查询

**MVP 策略**：意图分类 + 模板查询（不做全量 Text-to-SQL）

**查询示例**：
- "订单 #1234 经历了哪些状态变更？" → `trace_replay` 意图
- "昨天有多少订单支付失败？" → `stats_aggregation` 意图
- "订单 #abc 当前状态是什么？" → `event_lookup` 意图

**MVP 实现**：

```
用户问题 → 意图分类(LLM, 3类) → 路由
  ├─ event_lookup   → 提取 order_id → 查 order_view → LLM 润色回答
  ├─ stats_aggregation → 提取时间窗 + 状态 → 模板 SQL → 执行 → LLM 解读
  └─ trace_replay   → 提取 order_id → 回放事件链 → 时序图 + 文字解释
```

**V2 增强**：全量 Text-to-SQL（带 AST 校验、表白名单、只读连接的安全沙箱），见第 7.3.3 节。

> **为什么 MVP 收窄**：Text-to-SQL 在非预定义查询上准确率不稳定，面试官容易追问"没准备的查询怎么办"。意图分类 + 模板可控、可解释、可演示，验证时只需覆盖 3 类意图的测试集。

### 第 4 层：根因分析与补偿建议

**MVP 策略**：AI 生成根因分析报告 + 补偿建议，**不自动执行**。

**输出示例**：

```json
{
  "anomalyId": "...",
  "rootCause": "订单在 PAID 状态停滞 26h，未触发 InventoryReserved 事件。库存服务日志显示该 SKU 库存不足，未发出预留事件。",
  "evidence": [
    "事件序列: [CREATED, PAID] 后无后续",
    "库存查询: SKU=123 当前库存=0",
    "停滞时长: 26h12m"
  ],
  "suggestions": [
    {"action": "MARK_OUT_OF_STOCK", "reason": "库存为0，建议标记缺货并通知采购", "risk": "LOW"},
    {"action": "NOTIFY_DELAY", "reason": "建议向用户发送延迟通知", "risk": "LOW"}
  ]
}
```

**MVP 实现**：

```python
class RootCauseAnalyzer:
    def analyze(self, anomaly: Anomaly) -> AnalysisReport:
        events = event_store.load(anomaly.aggregate_id)
        context = context_loader.load(anomaly)  # 库存、用户、订单状态
        prompt = build_prompt(anomaly, events, context, ACTION_CATALOG)
        report = llm.generate(prompt)            # 结构化 JSON 输出
        validate_report_schema(report)           # 校验建议在白名单内
        return report
```

**V2 增强**：ReAct Agent 自动评估 + 审批流执行补偿（见第 7.3.4、7.4 节）。

> **为什么 MVP 不自动执行**：自动补偿涉及资金/库存写入，风险高；且 Agent 自主决策在面试中容易被质疑"怎么保证不误操作"。MVP 只输出建议，人工点击执行，既安全又可演示。V2 加 Agent 是差异化加分项。

### 4 层能力开发节奏

```
第 1-3 周: 第 1 层(事件级检测) — MVP 核心
第 4-5 周: 第 2 层 MVP(超时+状态机规则) — V2 HMM 视时间补
第 6-7 周: 第 3 层 MVP(意图分类+模板) — V2 Text-to-SQL 视时间补
第 8-9 周: 第 4 层 MVP(根因分析报告) — V2 Agent 视时间补
第 10-12周: 验证 + 前端完善
```

---

## 5. 验证方案

### 验证维度总览

```
验证 = 技术正确性 + AI增量价值 + 工程健壮性
       ├─ 一致性验证 (Testcontainers 并发测试 + Jepsen 探索性可选)
       ├─ 可用性验证 (Pumba 混沌)
       ├─ AI vs 基线对比 (量化指标)
       └─ 端到端演示 (可交互Demo)
```

### 5.1 一致性验证

| 验证项 | 方法 | 通过标准 |
|--------|------|---------|
| 并发写入冲突 | Testcontainers + JUnit 并发测试 | 事件版本号连续无间隔，乐观锁冲突正确回滚 |
| 事件不丢失 | Testcontainers kill PG 后重启 | 事件数与操作数一致，WAL 恢复成功 |
| 读模型最终一致 | 写入后轮询读取 | 99% 请求在 500ms 内读模型与事件一致 |
| 幂等消费 | Kafka 消费者重复消费同一条消息 | 幂等表去重，读模型不变 |
| 命令幂等 | 同一 commandId 重复提交 | 只执行一次，返回首次结果 |

**Testcontainers 测试方案**（MVP 主方案）：

```java
@Testcontainers
class OrderConsistencyTest {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Test
    void concurrent_payments_same_order_should_serialze() throws Exception {
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CommandResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return commandHandler.handle(payCommand(orderId));
            }));
        }
        start.countDown();
        long success = futures.stream().filter(f -> f.get().isSuccess()).count();
        // 只有一个命令成功，其余因乐观锁失败
        assertThat(success).isEqualTo(1);
    }
}
```

**Jepsen 探索性验证（可选，非 MVP）**：

- 仅在时间充裕时尝试，用 Clojure 编写线性一致性测试
- 不作为 MVP 通过门槛，作为"技术深度加分"在面试中提及
- 若实现不完整，**不写入简历**，避免被深挖翻车

### 5.2 可用性验证（Pumba）

> **为什么用 Pumba 而非 Chaos Mesh**：Chaos Mesh 需要 K8s 集群，与本项目 docker-compose 部署不一致；Pumba 直接作用于 Docker 容器，与现有部署匹配，学习成本低。

| 实验场景 | Pumba 命令 | 预期行为 | 记录指标 |
|---------|-----------|---------|---------|
| DB 宕机 | `pumba kill postgres` | 写入暂存本地队列，恢复后重放 | 数据丢失率 = 0 |
| Kafka 不可用 | `pumba pause kafka` | AI 检测暂停，命令端正常写入 | 业务可用率 > 99% |
| AI 服务超时 | `pumba delay --time 5000 ai` | 事件级检测走本地规则兜底 | 检测延迟 < 100ms |
| 网络分区 | `pumba netem --loss 100` | 分区侧降级为只读 | 读请求成功率 > 95% |

```bash
# 示例：kill postgres 30秒后自动恢复
docker run -it --rm -v /var/run/docker.sock:/var/run/docker.sock gaiaadm/pumba \
  pumba --random --interval 30s kill --signal SIGTERM postgres
```

### 5.3 AI vs 基线对比

**数据集**：合成异常数据

```python
正常流量: 10万条订单事件流 (按电商真实比例)
注入异常:
  - 5% 订单金额偏离 (事件级)
  - 3% 状态停滞/回退 (流程级)
  - 2% 支付死循环 (流程级)
  - 1% 组合异常 (多事件关联)
```

**对比实验**：

| 对比维度 | Baseline（规则+阈值） | AI Enhanced | 评估指标 |
|---------|---------------------|-------------|---------|
| 事件级检测 | 固定阈值（金额>10万告警） | Isolation Forest + 自适应阈值 | F1-Score、误报率 ↓ |
| 流程级检测 | 超时规则（>24h告警） | 超时 + HMM 序列检测 | 检出率 ↑、检出提前量 |
| 查询效率 | DBA 写 SQL | NL→模板查询 | 查询时间 ↓、非技术人员可用 |
| 故障恢复 | 人工排查 | AI 根因分析报告 | MTTR ↓ |

**预期量化目标**：

- 事件级检测 F1: 0.85 → 0.92
- 流程级检测提前量: 事后发现 → 提前 15min 预警
- MTTR: 从 45min → 8min（人工 + AI 建议辅助）

> **数据集局限说明**：合成数据无法完全反映真实业务分布，面试中应主动说明"项目贡献在于验证框架与对比方法论，而非模型绝对性能"。这是诚实且加分的表述。

### 5.4 端到端 Demo

**演示脚本**（面试 5 分钟走完）：

```
1. [30s] 创建订单 → 事件时间线可视化
2. [30s] 模拟异常支付 → 事件级检测实时告警弹出
3. [60s] 制造状态停滞 → 流程级检测识别 + 告警
4. [60s] 自然语言查询"昨天有多少支付失败" → 意图分类 → 模板查询 → 结果
5. [60s] 触发根因分析 → AI 输出建议 → 人工点击执行（MVP）/ 自动补偿（V2）
6. [60s] 切到 Pumba 演示 → kill DB → 观察系统降级 + 恢复
```

### 验证成果物清单

| 成果物 | 用途 | 投入时间 |
|--------|------|---------|
| Testcontainers 测试报告 | 证明一致性 | 2 天 |
| Pumba 实验截图 + 恢复曲线 | 证明可用性 | 1 天 |
| AI vs Baseline 对比表 | 证明 AI 增量价值 | 2 天 |
| 压测 QPS 曲线 | 证明性能基线 | 1 天 |
| 5 分钟 Demo 视频 | 面试展示 | 1 天 |

---

## 6. 技术栈、目录结构与部署方案

### 技术栈明细

| 层 | 技术 | 版本 | 选型理由 | 阶段 |
|----|------|------|---------|------|
| **后端框架** | Spring Boot + JDK 17 | 3.3+ | 主流企业级框架，虚拟线程支持 | MVP |
| **事件存储** | PostgreSQL | 16 | JSONB + WAL + 事务，一站式 | MVP |
| **读模型** | PostgreSQL | 16 | 同一实例，不同库，简化运维 | MVP |
| **CDC** | Debezium Server | 2.x | 独立部署模式，无需 Kafka Connect 集群 | MVP |
| **消息总线** | Apache Kafka | 3.7 | 事件流标准，KRaft 模式免 ZK | MVP |
| **AI 服务** | FastAPI + Python 3.11 | — | 异步高性能，ML 生态最全 | MVP |
| **ML 模型** | scikit-learn + hmmlearn | — | Isolation Forest + HMM（轻量，无需 GPU） | MVP/V2 |
| **LLM** | 本地 Ollama (Qwen2.5) / 远端 API | — | 意图分类 + 根因分析 | MVP |
| **前端** | Vue3 + Element Plus + ECharts | — | 简化 Admin 看板 | MVP |
| **混沌工程** | Pumba | — | docker-compose 原生支持，轻量 | MVP |
| **一致性测试** | Testcontainers + JUnit 5 | — | Java 生态标准，可重复集成测试 | MVP |
| **压测** | Gatling | — | Scala DSL，报告直观 | MVP |
| **容器化** | Docker Compose | — | 个人开发，单机编排 | MVP |

> **变更说明**（相较初版）：
> - 删除 **Chaos Mesh**（需 K8s，与 docker-compose 不匹配）→ 改用 Pumba
> - 删除 **PyTorch / LSTM**（重，合成数据上效果难说服）→ HMM 用 hmmlearn，LSTM 仅作远期探索
> - 删除 **LangChain + ChromaDB**（MVP 不做全量 Text-to-SQL，无需向量库）→ V2 再引入
> - 简化前端，去掉事件时间线编辑器等复杂组件

### 项目目录结构

```
eventguard/
├── docker-compose.yml              # 全栈编排
├── eventguard-server/              # Spring Boot 主服务
│   ├── src/main/java/com/eventguard/
│   │   ├── command/                # 命令端
│   │   │   ├── controller/         # REST 接收命令
│   │   │   ├── aggregate/          # 聚合根 (OrderAggregate)
│   │   │   ├── command/            # 命令对象
│   │   │   └── handler/            # 命令处理器
│   │   ├── event/                  # 事件层
│   │   │   ├── model/              # 领域事件定义
│   │   │   ├── store/              # 事件存储 (PG Repository)
│   │   │   ├── snapshot/           # 快照管理
│   │   │   └── publisher/          # 事件发布 (由 Debezium CDC 承担，此类仅占位)
│   │   ├── query/                  # 查询端
│   │   │   ├── controller/         # 查询 REST
│   │   │   ├── projection/         # 事件投影 → 读模型
│   │   │   └── model/              # 读模型 DTO
│   │   ├── compensation/           # 补偿端 (Saga 已实现 2026-08)
│   │   │   ├── saga/               # Saga 编排器
│   │   │   ├── action/             # 补偿动作
│   │   │   └── approval/           # 审批流
│   │   ├── anomaly/                # Java 侧规则引擎
│   │   │   ├── rule/               # 预定义规则
│   │   │   └── engine/             # 规则执行器
│   │   └── common/                 # 共享工具
│   └── src/test/java/              # 单元测试 + Testcontainers 集成测试
├── eventguard-ai/                  # Python AI 服务
│   ├── app/
│   │   ├── main.py                 # FastAPI 入口
│   │   ├── detector/
│   │   │   ├── event_level.py      # 事件级检测 (Isolation Forest)
│   │   │   ├── process_level.py    # 流程级检测 (规则 + V2 HMM)
│   │   │   └── rule_bridge.py      # 与 Java 规则引擎协作
│   │   ├── query/
│   │   │   ├── intent_classifier.py  # 意图分类 (MVP)
│   │   │   ├── template_executor.py  # 模板查询执行 (MVP)
│   │   │   └── text_to_sql.py        # V2: 全量 Text-to-SQL
│   │   ├── analyzer/
│   │   │   └── root_cause.py       # 根因分析 (MVP)
│   │   ├── agent/                  # V2: ReAct 自愈 Agent
│   │   │   ├── healer.py
│   │   │   ├── actions.py
│   │   │   └── risk_eval.py
│   │   ├── kafka_consumer.py       # Kafka 消费
│   │   └── model/                  # ML 模型训练 & 持久化
│   ├── training/
│   │   ├── generate_data.py        # 合成数据生成
│   │   ├── train_isolation.py      # Isolation Forest 训练
│   │   └── train_hmm.py            # V2: HMM 训练
│   └── requirements.txt
├── eventguard-ui/                  # Vue3 前端
│   ├── src/views/
│   │   ├── OrderList.vue           # 订单列表 (MVP)
│   │   ├── AnomalyDashboard.vue    # 异常看板 (MVP)
│   │   ├── NLQuery.vue             # 自然语言查询 (MVP)
│   │   └── CompensationApproval.vue # 补偿审批 (V2)
│   └── ...
├── eventguard-chaos/               # Pumba 混沌实验
│   ├── experiments/
│   │   ├── db-kill.sh
│   │   ├── kafka-pause.sh
│   │   └── ai-delay.sh
│   └── verify.sh                   # 自动化验证脚本
├── eventguard-benchmark/           # 压测脚本
│   ├── gatling/                    # Gatling 场景
│   └── results/                    # 压测报告
└── docs/
    └── specs/                      # 设计文档
```

### Docker Compose 编排

```yaml
services:
  postgres:
    image: postgres:16
    ports: ["5432:5432"]
    environment:
      POSTGRES_DB: eventguard
      POSTGRES_USER: eventguard
      POSTGRES_PASSWORD: eventguard

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    environment:
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_NODE_ID: 1
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      CLUSTER_ID: mkU4ER3RzSqqe0k1lPlBOQ  # KRaft 模式，无需 Zookeeper

  debezium:
    image: debezium/server:2.6
    volumes: ["./debezium/conf:/debezium/conf"]
    depends_on: [postgres, kafka]

  eventguard-server:
    build: ./eventguard-server
    ports: ["8080:8080"]
    depends_on: [postgres, kafka]

  eventguard-ai:
    build: ./eventguard-ai
    ports: ["8000:8000"]
    depends_on: [kafka]

  eventguard-ui:
    build: ./eventguard-ui
    ports: ["3000:80"]
    depends_on: [eventguard-server]

  # Pumba 混沌注入（按需启动，非常驻）
  pumba:
    image: gaiaadm/pumba
    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
    command: --random --interval 60s kill --signal SIGTERM
    profiles: ["chaos"]   # docker compose --profile chaos up 才启动
```

### 开发环境最低要求

| 资源 | 最低 | 推荐 |
|------|------|------|
| CPU | 4 核 | 8 核 |
| 内存 | 8 GB | 16 GB |
| 磁盘 | 20 GB | 50 GB |
| GPU | 无 | 无（MVP 全程 CPU 可跑） |

---

## 7. 核心模块详细设计

> 本章对四大核心模块给出接口契约、关键伪代码与设计权衡，作为编码落地的直接依据。
> 标注 `[MVP]` 为必做，`[V2]` 为可选增强。

### 7.1 事件溯源核心模块 `[MVP]`

#### 7.1.1 核心接口契约

```java
// —— 命令标记接口 ——
public interface Command {
    UUID getCommandId();        // 幂等键
    UUID getAggregateId();      // 聚合根ID
}

// —— 领域事件基类 ——
public abstract class DomainEvent {
    private final UUID eventId;
    private final UUID aggregateId;
    private final String eventType;
    private final int version;          // 聚合根版本号（乐观锁）
    private final Instant occurredAt;
    private final Map<String, String> metadata;  // traceId / userId / commandId
}

// —— 聚合根基类 ——
public abstract class AggregateRoot {
    private UUID aggregateId;
    private int version = 0;                    // 已持久化版本
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    protected void raise(DomainEvent event) {
        pendingEvents.add(event);
        apply(event);                           // 子类实现状态变更
    }
    public List<DomainEvent> flushPendingEvents() { ... }
}

// —— 事件存储接口 ——
public interface EventStore {
    void append(UUID aggregateId, List<DomainEvent> events, int expectedVersion);
    List<DomainEvent> load(UUID aggregateId);                    // 快照后回放
    List<DomainEvent> loadFrom(UUID aggregateId, int fromVersion);
}

// —— 快照存储接口 ——
public interface SnapshotStore {
    Optional<Snapshot> load(UUID aggregateId);
    void save(Snapshot snapshot);
}
```

#### 7.1.2 命令处理流水线

```
请求 → Controller → CommandBus → CommandHandler → AggregateRoot → EventStore
                                    ↑                  ↑
                              幂等检查           乐观并发控制
                                                       ↓
                                          事务提交 → Debezium CDC → Kafka
```

关键步骤：
1. **幂等检查**：`command_log` 表以 `command_id` 为唯一键，已处理则直接返回原结果
2. **加载聚合根**：先取快照，再回放快照版本之后的事件
3. **乐观并发控制**：`expectedVersion` 与当前版本不符抛 `OptimisticConcurrencyException`，上层重试（默认3次）
4. **事务性写入**：事件 + command_log 在同一 DB 事务（避免双写不一致）
5. **事件发布**：事务提交后由 Debezium CDC 捕获，**应用层不直接 publish 到 Kafka**，避免双写

```java
@Service
public class OrderCommandHandler {
    @Transactional
    public CommandResult handle(CreateOrderCommand cmd) {
        // 1. 幂等检查
        if (commandLog.exists(cmd.getCommandId())) {
            return commandLog.loadResult(cmd.getCommandId());
        }
        // 2. 加载聚合根
        OrderAggregate order = aggregateRepository.load(cmd.getOrderId());
        // 3. 处理命令（内部校验 + 生成事件）
        order.handle(cmd);
        // 4. 乐观并发写入（expectedVersion = order.getVersion()）
        eventStore.append(cmd.getOrderId(), order.flushPendingEvents(), order.getVersion());
        // 5. 记录命令
        commandLog.save(cmd.getCommandId(), cmd.getAggregateId());
        return CommandResult.success(order.getVersion());
    }
}
```

#### 7.1.3 订单聚合根状态机

```
                      ┌──────────────┐
                      │   (初始)      │
                      └──────┬───────┘
                             │ OrderCreated
                             ▼
                      ┌──────────────┐
               ┌──────│ PENDING_PAY  │──────┐
               │      └──────────────┘      │
               │ PaymentFailed              │ PaymentCompleted
               ▼                            ▼
         ┌──────────┐                ┌──────────┐
         │PAY_FAILED│                │   PAID   │
         └─────┬────┘                └─────┬────┘
               │ PaymentRetried           │ InventoryReserved
               └─►(回 PENDING_PAY)        ▼
                                       ┌───────────┐
                                       │ CONFIRMED │
                                       └─────┬─────┘
                                             │ Shipped
                                             ▼
                                       ┌───────────┐
                                       │  SHIPPED  │
                                       └─────┬─────┘
                                             │ Delivered
                                             ▼
                                       ┌───────────┐
                                       │  CLOSED   │
                                       └───────────┘
```

异常分支：
- `PAY_FAILED` → `PaymentRetried` → 回 `PENDING_PAY`（重试上限 3 次，超限转 `OrderCancelled`）
- `PAID` → `OrderRefundRequested` → `OrderRefunded`（退款分支，独立于主链）
- 任意非终态 → `OrderCancelled`（需校验状态机允许性）

非法迁移（由聚合根拦截并抛异常）：
- `PENDING_PAY` 直接到 `SHIPPED`（跳过 `PAID`/`CONFIRMED`）
- `CLOSED` 之后任何迁移
- `SHIPPED` 回退到 `PAID`

#### 7.1.4 快照机制

- **触发条件**：每 100 个事件打一次快照（可配置 `snapshot.interval=100`）
- **回放路径**：`loadSnapshot(aggId) → 取版本 N → loadEvents(aggId, fromVersion=N+1) → 回放剩余事件`
- **一致性**：快照写入失败不影响正确性（只影响性能），下次可重打

```java
public OrderAggregate load(UUID orderId) {
    Optional<Snapshot> snap = snapshotStore.load(orderId);
    OrderAggregate agg = snap.map(Snapshot::toAggregate).orElse(new OrderAggregate());
    int fromVersion = snap.map(s -> s.getVersion() + 1).orElse(0);
    List<DomainEvent> events = eventStore.loadFrom(orderId, fromVersion);
    events.forEach(agg::apply);
    return agg;
}
```

#### 7.1.5 并发冲突处理

| 场景 | 处理方式 |
|------|---------|
| 同一订单并发支付 | `expectedVersion` 不符 → 抛异常 → 重试加载最新版本重放命令（最多3次） |
| 重复命令提交 | `command_log` 唯一约束拦截，返回首次结果 |
| 快照写冲突 | 覆盖式更新，最后写入胜出（不影响正确性） |
| 事件版本号空洞 | `UNIQUE(aggregate_id, event_version)` 约束保证连续 |

---

### 7.2 CQRS + CDC 链路 `[MVP]`

#### 7.2.1 读模型投影器

```java
public interface Projection {
    void handle(DomainEvent event);
    void reset();                      // 重置读模型（用于重建）
}

@Component
public class OrderViewProjection implements Projection {
    private final JdbcTemplate jdbc;
    private final IdempotentConsumer idempotent;

    @KafkaListener(topics = "domain-events", groupId = "order-view-projection")
    public void on(DomainEvent event) {
        if (idempotent.isProcessed("order-view", event.getEventId())) return;

        switch (event) {
            case OrderCreatedEvent e -> jdbc.update(
                "INSERT INTO order_view(order_id, status, total_amount, version, updated_at) VALUES(?,?,?,?,now())",
                e.getAggregateId(), "PENDING_PAY", e.getTotalAmount(), e.getVersion());
            case PaymentCompletedEvent e -> jdbc.update(
                "UPDATE order_view SET status='PAID', payment_time=?, version=? WHERE order_id=?",
                e.getOccurredAt(), e.getVersion(), e.getAggregateId());
            // ... 其余事件类型
        }
        idempotent.markProcessed("order-view", event.getEventId());
    }
}
```

#### 7.2.2 Debezium 配置

```properties
# debezium/conf/application.properties
debezium.source.connector.class=io.debezium.connector.postgresql.PostgresConnector
debezium.source.database.hostname=postgres
debezium.source.database.port=5432
debezium.source.database.user=eventguard
debezium.source.database.password=***
debezium.source.database.dbname=eventguard
debezium.source.table.include.list=public.domain_events
debezium.source.plugin.name=pgoutput

# 去掉 Debezium 包装，输出纯净 payload
debezium.transforms=unwrap
debezium.transforms.unwrap.type=io.debezium.transforms.ExtractNewRecordState
debezium.transforms.unwrap.drop.tombstones=true
debezium.transforms.unwrap.delete.handling.mode=drop

# 输出到 Kafka
debezium.sink.type=kafka
debezium.sink.kafka.bootstrap.servers=kafka:9092
debezium.sink.kafka.topic=domain-events

# aggregate_id 作为消息 key（保证同聚合根事件有序到同分区）
debezium.sink.kafka.message.key columns=aggregate_id
```

设计要点：
- 仅 CDC `domain_events` 表（append-only，只捕获 INSERT）
- `ExtractNewRecordState` 转换器去掉 Debezium 元数据包装
- `aggregate_id` 作为 Kafka key → 同一订单事件落到同一分区 → 消费端保序

#### 7.2.3 Kafka Topic 设计

| Topic | Key | 分区数 | 保留策略 | 用途 |
|-------|-----|--------|---------|------|
| `domain-events` | aggregate_id | 6 | 7天 | 事件总线，多消费组订阅 |
| `anomaly-alerts` | aggregate_id | 3 | 30天 | AI 检出的异常事件 |
| `compensation-commands` | aggregate_id | 3 | 7天 | 补偿命令回送 Spring Boot（V2） |
| `dlq-domain-events` | — | 1 | 30天 | 死信队列（消费失败兜底） |

分区策略：`aggregate_id` 哈希，保证同订单事件有序到同分区。

#### 7.2.4 消费组设计

```
domain-events (topic)
  ├─ order-view-projection     → 更新 order_view 读模型      [MVP]
  ├─ ai-event-detector         → 事件级检测（实时）           [MVP]
  ├─ ai-process-detector       → 流程级检测（窗口）           [MVP]
  └─ nl-query-indexer          → 向量化事件用于 RAG 检索      [V2]
```

独立消费组 = 各消费者独立 offset，互不阻塞。

#### 7.2.5 最终一致性处理

| 问题 | 方案 | 阶段 |
|------|------|------|
| 投影延迟监控 | 监控 `max(event.created_at) - max(order_view.updated_at)`，超 1s 告警 | MVP |
| 投影失败积压 | 死信队列 + 报警；积压超阈值触发限流 | MVP |
| 读模型修复 | 提供 `POST /admin/projections/rebuild` 重放全量事件到新读模型 | MVP |
| 读己写一致性 | 命令端返回 `expectedVersion`，查询端带 version 等待，超时提示"处理中" | MVP |

```java
// 读己写一致性（Read Your Writes）
public OrderView readAfterWrite(UUID orderId, int expectedVersion) {
    long deadline = System.currentTimeMillis() + 2000;  // 最多等 2s
    while (System.currentTimeMillis() < deadline) {
        OrderView v = orderViewRepo.findById(orderId);
        if (v != null && v.getVersion() >= expectedVersion) return v;
        Thread.sleep(50);
    }
    throw new ProjectionLagException("读模型未追上，请稍后重试");
}
```

---

### 7.3 AI 检测 4 层详细设计

#### 7.3.1 第 1 层：事件级异常检测 `[MVP]`

**Java 规则引擎（同步，<1ms）**

```java
public interface EventRule {
    String ruleId();
    boolean matches(DomainEvent event, RuleContext ctx);
    AnomalyLevel level();   // INFO / WARN / ERROR
}

@Component
public class RuleEngine {
    private final List<EventRule> rules;
    private final ContextLoader contextLoader;

    public Optional<Anomaly> evaluate(DomainEvent event) {
        RuleContext ctx = contextLoader.load(event);  // 用户历史、聚合根状态
        return rules.stream()
            .filter(r -> r.matches(event, ctx))
            .findFirst()
            .map(r -> new Anomaly(r.ruleId(), event, r.level()));
    }
}
```

内置规则：

| 规则ID | 检测项 | 判定条件 |
|--------|--------|---------|
| R001 | 金额偏离 | `|amount - userMean| > 3 * userStd` |
| R002 | 重复支付 | `count(PaymentCompleted@orderId, window=5min) > 1` |
| R003 | 状态跳跃 | `prevState != expectedPrevState(currState)` |
| R004 | 高频操作 | `count(CreateOrder@userId, window=1min) > 20` |
| R005 | 库存越界 | `reservedQty > actualStock` |

**Python ML 检测（异步，~5ms）**

```python
# detector/event_level.py
class EventLevelDetector:
    def __init__(self):
        self.model = joblib.load("models/isolation_forest.pkl")
        self.scaler = joblib.load("models/scaler.pkl")

    def detect(self, event: dict) -> AnomalyResult:
        features = self._extract_features(event)
        X = self.scaler.transform([features])
        pred = self.model.predict(X)[0]   # -1=异常, 1=正常
        score = -self.model.score_samples(X)[0]
        return AnomalyResult(is_anomaly=(pred == -1), score=score)
```

特征工程：

| 特征 | 说明 |
|------|------|
| `amount_zscore` | 金额相对用户历史均值的 Z 分数 |
| `time_since_last_event` | 同订单距上一事件的间隔（秒） |
| `user_order_count_1h` | 用户 1h 内订单数 |
| `state_transition_prob` | 该状态转移在历史中的概率 |

**协同流程**：规则命中 → 直接告警（高优先级）；规则未命中 → 走 ML；ML 异常 → 告警（低优先级）。

#### 7.3.2 第 2 层：流程级异常检测

**MVP：规则检测（超时 + 状态机 + 死循环）**

```python
class ProcessLevelRuleDetector:
    """MVP 流程级检测：基于规则，无需训练"""
    def detect(self, event_sequence: list[Event]) -> list[Anomaly]:
        anomalies = []
        # 1. 状态机非法迁移
        if self._has_illegal_transition(event_sequence):
            anomalies.append(Anomaly("P001_ILLEGAL_TRANSITION", ...))
        # 2. 状态停滞
        if self._is_stuck(event_sequence, timeout=timedelta(hours=24)):
            anomalies.append(Anomaly("P002_STUCK", ...))
        # 3. 死循环
        if self._has_loop(event_sequence, pattern=["PAY_FAILED","RETRY"], threshold=5):
            anomalies.append(Anomaly("P003_DEAD_LOOP", ...))
        return anomalies
```

**V2：HMM 流程检测 `[可选]`**

```python
class ProcessLevelHMMDetector:
    def __init__(self):
        self.hmm = joblib.load("models/hmm.pkl")          # 训练于正常序列
        self.threshold = config.HMM_LOG_LIKELIHOOD_THRESHOLD

    def detect(self, event_sequence: list[str]) -> AnomalyResult:
        log_prob = self.hmm.score([event_sequence])
        if log_prob < self.threshold:
            return AnomalyResult(is_anomaly=True, score=log_prob,
                                 reason="序列概率低于阈值")
        return AnomalyResult(is_anomaly=False)
```

训练数据：用 10 万条正常订单事件序列训练 HMM，得到状态转移矩阵 `A` 与发射概率 `B`。

**检测场景**：

| 模式 | 检测方法 | 阶段 |
|------|---------|------|
| 状态跳跃 | HMM 低概率 | V2 |
| 死循环 | 频繁子序列挖掘 | MVP（计数规则） |
| 状态停滞 | 超时规则 | MVP |
| 异常回退 | 状态机校验 | MVP |

> LSTM 自编码器仅作为远期技术探索，不纳入 MVP/V2 交付范围。

#### 7.3.3 第 3 层：自然语言事件查询

**MVP：意图分类 + 模板查询**

```python
class NLQueryEngine:
    INTENTS = ["event_lookup", "stats_aggregation", "trace_replay"]

    def query(self, question: str) -> QueryResult:
        intent = self.classify_intent(question)  # LLM 分类，3 类

        if intent == "event_lookup":
            order_id = self.extract_order_id(question)
            return self.query_order_view(order_id)
        elif intent == "stats_aggregation":
            params = self.extract_stats_params(question)  # 时间窗 + 状态
            sql = self.build_template_sql(params)         # 模板填充，非 LLM 生成
            return self.execute_readonly(sql)
        elif intent == "trace_replay":
            agg_id = self.extract_aggregate_id(question)
            events = event_store.load(agg_id)
            return self.render_timeline(events)
```

**V2：全量 Text-to-SQL（安全沙箱） `[可选]`**

```python
def text_to_sql(self, question: str) -> str:
    examples = self.few_shot_retriever.retrieve(question, top_k=3)
    prompt = build_prompt(question, examples, SCHEMA_DOC)
    sql = llm.generate(prompt)
    validate_sql(sql)   # AST 解析，只允许 SELECT
    return sql
```

Text-to-SQL 安全保障：
- 只读连接：DB 用户仅授 `SELECT` 权限
- 表白名单：仅 `order_view`、`domain_events`
- AST 校验：解析 SQL 语法树，拒绝非 `SELECT` 语句
- Few-shot 检索：维护 10-20 个标准问题→SQL 示例

#### 7.3.4 第 4 层：根因分析与补偿建议

**MVP：根因分析报告（不自动执行）**

```python
class RootCauseAnalyzer:
    def analyze(self, anomaly: Anomaly) -> AnalysisReport:
        events = event_store.load(anomaly.aggregate_id)
        context = context_loader.load(anomaly)
        prompt = build_prompt(anomaly, events, context, ACTION_CATALOG)
        report = llm.generate(prompt)        # 结构化 JSON 输出
        validate_report_schema(report)       # 校验建议在白名单内
        return report
```

**V2：ReAct 自愈 Agent `[可选]`**

```python
class HealerAgent:
    TOOLS = [query_events, query_order, submit_compensation, request_approval]
    MAX_STEPS = 5

    def run(self, anomaly: Anomaly) -> HealResult:
        state = {"anomaly": anomaly, "history": [], "step": 0}
        while state["step"] < self.MAX_STEPS:
            observation = self.observe(state)
            thought = self.llm.reason(observation, self.TOOLS)
            action = self.parse_action(thought)

            if action.tool == "request_approval":
                return HealResult(status="pending_approval", action=action)
            if action.tool == "submit_compensation":
                result = self.execute_compensation(action)
                state["history"].append(result)
                if result.success:
                    return HealResult(status="healed")
            state["step"] += 1
        return HealResult(status="failed", reason="超过最大步数")
```

**工具定义（V2）**

| 工具 | 输入 | 输出 | 风险等级 |
|------|------|------|---------|
| `query_events` | aggregate_id | 事件列表 | 低（只读） |
| `query_order` | aggregate_id | 订单状态 | 低（只读） |
| `submit_compensation` | {action, agg_id, params} | 补偿结果 | 中/高（写入） |
| `request_approval` | {action, reason} | 审批单号 | — |

**安全边界**

- Agent 只能调用 `TOOLS` 中的工具，不能自由生成命令
- 补偿动作必须在 7.4.2 白名单内
- 高风险动作（如退款金额 > 100 元）强制走 `request_approval`
- 所有 Agent 决策记录到 `heal_log` 表，可追溯

> **MVP 边界**：根因分析只输出建议 JSON，前端展示"建议动作"按钮，由人工点击触发补偿命令；不进入 Agent 自动循环。

---

### 7.4 补偿编排 Saga `[已实现 2026-08]`

> 原 V2 项；现已落地：`compensation/saga/` 包提供 `CompensationSaga`（SagaStatus 状态机）、
> `SagaTrigger`（消费 domain-events 自动触发）、`ApprovalController`（`POST /approvals/{id}/approve|reject`）。
> 本文以下为设计蓝图，实现细节见代码与 `docs/验证报告/verification-log.md` §8。

#### 7.4.1 Saga 编排器

```java
public class CompensationSaga {
    private final UUID sagaId;
    private final UUID aggregateId;
    private final String triggerAnomalyId;
    private SagaStatus status;        // STARTED / AWAITING_APPROVAL / EXECUTING / COMPLETED / FAILED
    private final List<SagaStep> steps;

    public void execute() {
        for (SagaStep step : steps) {
            if (step.requiresApproval()) {
                status = SagaStatus.AWAITING_APPROVAL;
                approvalService.request(sagaId, step);
                return;  // 挂起，等审批回调
            }
            step.execute();
        }
        status = SagaStatus.COMPLETED;
    }

    public void onApproved(ApprovalDecision decision) {
        if (decision.isApproved()) {
            status = SagaStatus.EXECUTING;
            execute();
        } else {
            status = SagaStatus.FAILED;
        }
    }
}
```

#### 7.4.2 补偿动作库

```java
public interface CompensationAction {
    String actionType();
    boolean requiresApproval(CompensationContext ctx);  // 按金额等动态判断
    void execute(CompensationContext ctx);
}

// 退款动作示例
@Component
public class RefundAction implements CompensationAction {
    public String actionType() { return "REFUND"; }

    public boolean requiresApproval(CompensationContext ctx) {
        return ctx.getAmount().compareTo(BigDecimal.valueOf(100)) > 0;
    }

    public void execute(CompensationContext ctx) {
        // 发起退款命令 → 命令端处理 → 产生 OrderRefunded 事件
        commandBus.dispatch(new IssueRefundCommand(ctx.getAggregateId(), ctx.getAmount()));
    }
}
```

**动作白名单**

| actionType | 触发异常 | 默认审批 |
|-----------|---------|---------|
| `REFUND` | 重复支付 | 金额>100 人工 |
| `NOTIFY_DELAY` | 订单停滞 | 自动 |
| `MARK_OUT_OF_STOCK` | 库存不足 | 自动 |
| `FREEZE_ORDER` | 异常回退 | 人工 |
| `BACKOFF_AND_STOP` | 死循环重试 | 自动 |

#### 7.4.3 补偿命令回环

```
人工(V1) / Agent(V2) 决策 → submit_compensation(action, aggId, params)
   → Kafka: compensation-commands
   → Spring Boot 补偿端消费
   → 校验 actionType 在白名单
   → Saga 编排执行
   → Action 发起命令（如 IssueRefundCommand）
   → 命令端走正常事件溯源链路
   → 产生 OrderRefunded 事件
   → 读模型更新 + AI 检测新事件（闭环）
```

#### 7.4.4 审批流

```sql
CREATE TABLE compensation_approval (
    approval_id     UUID PRIMARY KEY,
    saga_id         UUID NOT NULL,
    action_type     VARCHAR(64) NOT NULL,
    aggregate_id    UUID NOT NULL,
    params          JSONB,
    status          VARCHAR(32) NOT NULL,   -- PENDING / APPROVED / REJECTED
    requested_by    VARCHAR(64) NOT NULL,   -- agent / human
    requested_at    TIMESTAMPTZ NOT NULL,
    decided_at      TIMESTAMPTZ,
    decided_by      VARCHAR(64)
);
```

审批接口：
- `POST /approvals/{id}/approve` → 触发 Saga 继续执行
- `POST /approvals/{id}/reject`  → Saga 标记 `FAILED`，记录拒绝原因

---

### 7.5 模块间接口契约总览

| 调用方 → 被调用方 | 协议 | 契约 | 阶段 |
|------------------|------|------|------|
| 前端 → 命令端 | REST | `POST /orders`、`POST /orders/{id}/pay` 等命令接口 | MVP |
| 前端 → 查询端 | REST | `GET /orders/{id}`、`GET /orders?status=PAID` | MVP |
| 前端 → AI NL查询 | REST | `POST /ai/query` (body: `{question: string}`) | MVP |
| 前端 → 补偿执行 | REST | `POST /compensations` (人工触发；动作经网关抽象层执行) | MVP |
| 前端 → 补偿审批 | REST | `POST /approvals/{id}/approve\|reject`（已实现 2026-08） | MVP |
| 事件表 → AI 服务 | Kafka | topic `domain-events`，payload = DomainEvent JSON | MVP |
| 网关回调 → 命令端 | REST | `POST /gateway/callback/{provider}` (X-API-Key 校验) | MVP |
| AI 服务 → 补偿端 | Kafka | topic `compensation-commands`，payload = `{actionType, aggId, params}` | V2 |
| AI 服务 → 前端告警 | WebSocket | 推送异常告警到看板 | MVP |
| 补偿端 → 命令端 | 进程内调用 | `commandBus.dispatch(...)`（同一 Spring Boot 进程） | MVP |

---

## 7.5 鉴权与权限管理（登录 + RBAC）`[V2 后，已实现]`

早期版本全系统共用单一静态 `X-API-Key`（默认 `changeme`），无用户账号、无权限差异。已升级为**登录 + RBAC（用户-角色-权限）**，覆盖前端路由/菜单/按钮与后端 REST/WebSocket/AI 接口。

### 认证协议：JWT（无状态）

- 用户 `POST /auth/login`（用户名+密码，BCrypt 校验）→ 返回 JWT（HS256，默认 12h）+ 用户信息 + 权限码。
- JWT claims 含 `uid / username / displayName / roles / permissions / mcp(mustChangePassword)`；
  server（jjwt）与 AI 服务（PyJWT）**共用 `EG_JWT_SECRET`** 校验。
- 前端存 localStorage，REST 带 `Authorization: Bearer`，WS 带 `?token=`（浏览器 WS 无法带自定义头）。
- 双主体认证：用户主体（JWT）或**机器主体**（`X-API-Key == EG_MACHINE_API_KEY`，固定权限集
  `{order:read, anomaly:evaluate}`，供 AI→后端内部调用与运维工具，天然不可写订单/管用户）。

### 权限模型与数据表（`V3__auth.sql`）

| 表 | 用途 |
|---|---|
| `auth_user` / `auth_role` / `auth_permission` | 用户 / 角色 / 权限目录 |
| `auth_user_role` / `auth_role_permission` | 多对多关联 |
| `auth_audit_log` | 登录成败 / 登出 / 改密 / 用户管理审计 |

权限码（代码内定义）：`order:read / order:create / order:write / anomaly:view / ai:query /
compensation:execute / user:manage / role:manage / anomaly:evaluate`。

种子角色（`AuthDataSeeder` 启动幂等写入，BCrypt 运行时生成）：`ADMIN`（全部）、`OPERATOR`（下单/状态操作/
异常/补偿）、`VIEWER`（只读）；种子账号 `admin` / `operator` / `viewer`，默认密码见代码，首次登录强制改密。

### 执行链路

1. `AuthFilter`（`@Order(1)`）：放行 `/auth/login`、`/actuator`、`/health`、`/ws`；否则解析 Bearer JWT 或机器密钥 → `AuthPrincipal` 放入 request attribute；失败 401。
2. `PermissionInterceptor`：读取 Controller 方法/类上 `@RequirePermission("order:write")`，无权限返回 403。
3. WS 握手：`JwtHandshakeInterceptor` 校验 `?token=` 且权限含 `anomaly:view`。
4. AI 服务：`app/security.py` 用 PyJWT 校验同一 secret，`/ai/query` 需 `ai:query`、根因分析需 `anomaly:view`。
5. 前端：路由守卫 + 路由 `meta.permission` + `v-permission` 指令（按钮级）+ 菜单按权限过滤。

### 已知上限（ponytail）

- JWT 权限放 claims，角色/权限变更需重新登录生效；无刷新令牌/吊销机制（升级路径 = refresh token + jti 黑名单）。
- 登录防爆破为进程内计数（同一用户名 5 次失败锁 5 分钟），多实例需换 Redis。
- 前端 token 存 localStorage，XSS 风险与常见管理台同级别。

---

## 8. MVP 路线图

> 原则：**先跑通端到端最小闭环，再叠 AI 与验证**。每两周一个可演示里程碑。

### 8.1 里程碑总览

| 里程碑 | 时间 | 交付物 | 可演示 |
|--------|------|--------|--------|
| M1 骨架跑通 | 第 2 周末 | docker-compose 起全栈，创建订单→事件入库→CDC→Kafka | 命令链路通 |
| M2 事件溯源完整 | 第 4 周末 | 聚合根状态机 + 快照 + 乐观锁 + 读模型投影 | 下单→支付→发货全流程 + 读己写 |
| M3 AI 检测 MVP | 第 7 周末 | 事件级（规则+IF）+ 流程级规则 + 根因分析 | 异常注入→告警→建议 |
| M4 NL 查询 + 前端 | 第 9 周末 | 意图分类 3 类 + Admin 看板 | 5 分钟 Demo 雏形 |
| M5 验证 + 打磨 | 第 12 周末 | Testcontainers + Pumba + AI 对比 + Demo 视频 | 面试可讲完整故事 |

### 8.2 详细任务拆解

#### M1：骨架跑通（第 1-2 周）

| 任务 | 说明 | 阶段 |
|------|------|------|
| 项目脚手架 | Maven 多模块 + Spring Boot 3 + FastAPI + Vue3 | 必做 |
| docker-compose | PG + Kafka (KRaft) + Debezium + 后端 + AI + 前端 | 必做 |
| 事件表 + 命令日志表 | DDL 落地 | 必做 |
| 最小命令端 | `CreateOrderCommand` → 写事件表 | 必做 |
| Debezium 配置 | CDC 事件表 → Kafka `domain-events` topic | 必做 |
| Kafka 消费验证 | 写一个 echo consumer 验证事件能消费到 | 必做 |

**M1 验收**：`curl POST /orders` → 查 PG 事件表有记录 → Kafka consumer 能消费到事件。

#### M2：事件溯源完整（第 3-4 周）

| 任务 | 说明 | 阶段 |
|------|------|------|
| 聚合根状态机 | 完整订单状态机 + 非法迁移校验 | 必做 |
| 事件存储 + 快照 | `EventStore` / `SnapshotStore` 实现，100 事件打快照 | 必做 |
| 乐观并发控制 | `expectedVersion` + 重试 3 次 | 必做 |
| 幂等命令 | `command_log` 表 + 重复提交返回首次结果 | 必做 |
| 读模型投影器 | `OrderViewProjection` 消费 Kafka 更新 `order_view` | 必做 |
| 幂等消费 | `idempotent_consumers` 表去重 | 必做 |
| 读己写一致性 | `readAfterWrite` 版本等待 | 必做 |
| Testcontainers 并发测试 | 并发支付同一订单，断言只成功一个 | 必做 |

**M2 验收**：下单→支付→确认→发货→送达全流程；并发支付测试通过；读模型最终一致。

#### M3：AI 检测 MVP（第 5-7 周）

| 任务 | 说明 | 阶段 |
|------|------|------|
| Python AI 服务骨架 | FastAPI + Kafka 消费 + 健康检查 | 必做 |
| 合成数据生成 | 10 万正常 + 注入异常的事件流 | 必做 |
| Java 规则引擎 | R001-R005 五条规则 + `RuleEngine` | 必做 |
| Isolation Forest 训练 | 特征工程 + 模型训练 + 持久化 | 必做 |
| 事件级检测服务 | 规则 + ML 协同，结果推 WebSocket | 必做 |
| 流程级规则检测 | 超时 + 状态机 + 死循环 | 必做 |
| 根因分析 | LLM 生成结构化建议 JSON | 必做 |
| 异常告警推送 | WebSocket → 前端实时告警 | 必做 |
| HMM 训练与检测 | 流程级 V2 增强 | 可选 |

**M3 验收**：注入异常 → 事件级/流程级检测命中 → 前端收到告警 → 点击查看根因分析报告。

#### M4：NL 查询 + 前端（第 8-9 周）

| 任务 | 说明 | 阶段 |
|------|------|------|
| 意图分类 | LLM 3 类意图分类 | 必做 |
| 模板查询执行 | `event_lookup` / `stats_aggregation` / `trace_replay` | 必做 |
| 订单列表页 | 分页 + 状态筛选 | 必做 |
| 异常看板 | 实时告警 + 历史异常列表 | 必做 |
| NL 查询框 | 输入问题 → 展示结果 | 必做 |
| 事件时间线 | 订单事件回放可视化 | 必做 |
| 补偿执行按钮 | 人工点击触发补偿命令 | 必做 |
| Text-to-SQL（安全沙箱） | V2 增强 | 可选 |

**M4 验收**：5 分钟 Demo 雏形可走通。

#### M5：验证 + 打磨（第 10-12 周）

| 任务 | 说明 | 阶段 |
|------|------|------|
| Testcontainers 一致性套件 | 并发/幂等/最终一致/事件不丢失 | 必做 |
| Pumba 混沌实验 | DB kill / Kafka pause / AI delay | 必做 |
| AI vs Baseline 对比 | F1 / 检出率 / MTTR 指标表 | 必做 |
| Gatling 压测 | QPS / P95 延迟曲线 | 必做 |
| 5 分钟 Demo 视频 | 面试展示用 | 必做 |
| README + 架构图 | 仓库文档完善 | 必做 |
| ReAct Agent + Saga | V2 自愈闭环 | 可选 |
| Jepsen 探索性测试 | 仅时间充裕且实现完整才写入简历 | 可选 |

**M5 验收**：所有验证成果物齐全；Demo 视频录制完成；仓库可交付。

### 8.3 时间预算与风险

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| Debezium 配置踩坑 | 中 | M1 延期 | 预留 2 天调试；备选方案应用层直接 publish（牺牲一致性） |
| Isolation Forest 效果差 | 中 | M3 验收难看 | 规则引擎兜底，ML 仅作增强；调参不行就降级为纯规则 |
| LLM 本地部署慢 | 中 | Demo 卡顿 | 用远端 API（如通义千问）做 Demo，本地 Ollama 仅开发 |
| 前端耗时超预期 | 中 | M4 延期 | 砍组件，用 Element Plus 现成模板，只做列表 + 表单 + 看板 |
| Scope 蔓延 | 高 | 全局延期 | 严格遵循 MVP/V2 划分；V2 仅在 M5 前有余力才做 |

### 8.4 面试讲解映射

| 面试考点 | 对应模块 | 讲解素材 |
|---------|---------|---------|
| 分布式一致性 | 7.1 事件溯源 + 7.2 CDC | 乐观锁、Transactional Outbox、幂等消费 |
| 并发编程 | 7.1.5 冲突处理 | 并发支付测试、重试机制 |
| 高可用 | 5.2 Pumba 验证 | DB/Kafka 宕机降级 |
| 消息队列 | 7.2.3/7.2.4 Kafka 设计 | 分区、消费组、死信队列 |
| 数据库设计 | 3 事件存储表 | JSONB、 append-only、唯一约束 |
| AI 工程化 | 7.3 AI 4 层 | 规则+ML 协同、意图分类、根因分析 |
| 系统设计 | 第 2 章架构 | CQRS、事件溯源、CDC |
| 工程素养 | 第 5 章验证 | Testcontainers、Pumba、AI 对比 |
| 项目难点 | 8.3 风险表 | Debezium 踩坑、ML 效果调优 |

---

## 附录：决策记录

| 决策 | 选择 | 替代方案 | 理由 |
|------|------|---------|------|
| 事件发布 | Transactional Outbox (CDC) | 应用层双写 DB+Kafka | 避免双写不一致 |
| 混沌工具 | Pumba | Chaos Mesh | docker-compose 原生支持，免 K8s |
| 一致性测试 | Testcontainers | Jepsen | Java 生态标准，可重复集成；Jepsen 学习成本高且 Clojure 栈 |
| 流程级 ML | HMM (V2) | LSTM | HMM 可解释、训练快、合成数据上够用；LSTM 重且效果难证明 |
| NL 查询 | 意图分类 + 模板 (MVP) | 全量 Text-to-SQL | 模板可控可演示；Text-to-SQL 在非预定义查询上不稳定 |
| 自愈模式 | 根因建议 (MVP) | Agent 自动执行 | 自动写入风险高，面试易被质疑；建议模式安全且可演示 |
| 前端 | 简化 Admin | 复杂可视化 | 后端/AI 岗位不重点看前端，够用即可 |

---

## 9. 学习知识图谱

> 本章梳理项目涉及的全部知识、概念与技术栈，标注学习优先级，便于按需补课。
> 优先级：`P0` MVP 必学 | `P1` V2 按需 | `P2` 了解即可

### 9.1 后端架构与设计模式

| 知识点 | 概念 | 项目用途 | 优先级 |
|--------|------|---------|--------|
| 事件溯源 (Event Sourcing) | 以事件序列持久化状态变更，回放事件重建状态 | 核心：订单状态由事件回放得到 | P0 |
| CQRS | 读写分离，命令端写事件，查询端投影读模型 | 命令端/查询端分离 | P0 |
| 聚合根 (Aggregate Root) | DDD 战术设计，一致性边界内的实体 | OrderAggregate 封装订单业务规则 | P0 |
| 领域事件 (Domain Event) | 描述领域中已发生的事实 | OrderCreatedEvent 等 | P0 |
| Transactional Outbox | 事件与业务数据同事务写入，由 CDC 异步发布 | 解决 DB+Kafka 双写不一致 | P0 |
| 乐观并发控制 (OCC) | 基于版本号的并发控制，冲突重试 | event_version 乐观锁 | P0 |
| 幂等性 | 重复操作不产生副作用 | 命令幂等 + 消费幂等 | P0 |
| 最终一致性 | 写入后读模型异步追上 | 读模型投影 | P0 |
| 读己写一致性 (Read Your Writes) | 用户写后立即能读到自己的写 | readAfterWrite 等待 | P0 |
| 状态机 | 状态间合法迁移的有限状态自动机 | 订单状态流转校验 | P0 |
| 快照模式 (Snapshot) | 周期性持久化聚合根状态，加速回放 | 每 100 事件打快照 | P0 |
| 投影 (Projection) | 事件→读模型的转换 | OrderViewProjection | P0 |
| Saga 模式 | 分布式事务的编排/协调模式 | 补偿编排（V2） | P1 |
| DDD 战术设计 | 实体/值对象/聚合/领域事件 | 聚合根设计方法论 | P1 |

### 9.2 数据库

| 知识点 | 概念 | 项目用途 | 优先级 |
|--------|------|---------|--------|
| PostgreSQL JSONB | 二进制 JSON 类型，支持索引与查询 | 事件 payload 存储 | P0 |
| WAL (Write-Ahead Log) | 预写日志，保证事务持久性 | Debezium CDC 数据源 | P0 |
| append-only 表 | 只插入不修改的表设计 | 事件表 | P0 |
| 唯一约束 | 数据库层防重 | UNIQUE(aggregate_id, event_version) | P0 |
| 事务隔离级别 | RC/RR/Serializable | 事件写入事务 | P1 |
| pgoutput | PostgreSQL 逻辑复制插件 | Debezium 逻辑解码 | P1 |
| 索引优化 | B-tree / GIN 索引 | 事件表查询性能 | P1 |
| 事务边界 | 事务内多表写入的原子性 | 事件+命令日志同事务 | P0 |

### 9.3 消息中间件与 CDC

| 知识点 | 概念 | 项目用途 | 优先级 |
|--------|------|---------|--------|
| Apache Kafka | 分布式事件流平台 | 事件总线 | P0 |
| KRaft 模式 | Kafka 内置共识，免 Zookeeper | 简化部署 | P0 |
| 分区与消息 key | key 哈希到分区，保证同 key 有序 | aggregate_id 作 key 保序 | P0 |
| 消费者组 | 独立 offset，互不阻塞 | 多投影并行消费 | P0 |
| Exactly-Once 语义 | 消息恰好处理一次 | 幂等消费实现 | P0 |
| At-Least-Once | 至少一次，靠幂等去重 | 实际消费语义 | P0 |
| 死信队列 (DLQ) | 消费失败的消息兜底 | 异常事件归档 | P0 |
| 消息保留策略 | 按时间/大小清理 | topic 保留 7/30 天 | P1 |
| offset 管理 | 消费进度提交 | 手动提交保证不丢 | P1 |
| CDC 原理 | 捕获数据库变更数据 | 事件表→Kafka | P0 |
| Debezium Server | 独立部署的 CDC 工具 | 无需 Kafka Connect 集群 | P0 |
| ExtractNewRecordState | Debezium 转换器，去包装 | 输出纯净 payload | P0 |
| 逻辑解码 | 数据库日志解析为事件流 | pgoutput 插件 | P1 |

### 9.4 AI / 机器学习

| 知识点 | 概念 | 项目用途 | 优先级 |
|--------|------|---------|--------|
| Isolation Forest | 基于隔离的异常检测算法 | 事件级 ML 检测 | P0 |
| Z-Score | 标准分数，衡量偏离均值程度 | 金额异常规则 | P0 |
| 特征工程 | 从原始数据构造模型输入特征 | 提取 4 维特征 | P0 |
| 滑动窗口 | 按时间/数量滚动的事件缓冲 | 流程级检测窗口 | P0 |
| 模型持久化 | 训练好的模型保存加载 | joblib | P0 |
| scikit-learn | Python ML 库 | Isolation Forest | P0 |
| 合成数据生成 | 模拟正常+异常流量 | 验证数据集 | P0 |
| HMM (隐马尔可夫模型) | 序列概率模型 | 流程级 V2 检测 | P1 |
| 状态转移矩阵 | 状态间转移概率 | HMM 训练 | P1 |
| 发射概率 | 观测值由隐状态产生的概率 | HMM 训练 | P1 |
| LSTM 自编码器 | 序列重建误差检测异常 | 远期探索 | P2 |
| F1-Score | 精确率与召回率的调和均值 | AI vs 基线对比 | P0 |
| 精确率 / 召回率 | 分类模型评估指标 | 检测效果评估 | P0 |
| 误报率 / 漏报率 | 异常检测特有指标 | 评估检测质量 | P0 |
| LLM 应用 | 大语言模型工程化 | 意图分类/根因分析 | P0 |
| Prompt Engineering | 提示词设计 | 结构化输出 | P0 |
| 意图分类 | NLU 任务，分类用户意图 | NL 查询路由 | P0 |
| 结构化输出 | LLM 输出 JSON 并校验 | 根因分析报告 | P0 |
| Few-shot Learning | 少量示例引导模型 | Text-to-SQL (V2) | P1 |
| Text-to-SQL | 自然语言转 SQL | V2 NL 查询 | P1 |
| RAG (检索增强生成) | 检索+生成结合 | V2 事件检索 | P1 |
| ReAct Agent | 推理+行动循环的智能体 | V2 自愈 | P1 |
| Function Calling | LLM 调用外部工具 | Agent 工具调用 | P1 |
| 向量检索 | 语义相似度搜索 | V2 RAG 索引 | P1 |
| Embedding | 文本向量化 | V2 事件向量化 | P1 |
| Ollama | 本地 LLM 运行时 | 本地推理 | P0 |
| LangChain | LLM 应用框架 | V2 | P2 |

### 9.5 验证与工程实践

| 知识点 | 概念 | 项目用途 | 优先级 |
|--------|------|---------|--------|
| Testcontainers | 测试中动态起容器的 Java 库 | 集成测试/一致性测试 | P0 |
| JUnit 5 并发测试 | 多线程模拟并发场景 | 并发支付测试 | P0 |
| CountDownLatch | 线程同步发令枪 | 并发测试同时触发 | P0 |
| 混沌工程 | 主动注入故障验证韧性 | 可用性验证 | P0 |
| Pumba | Docker 容器故障注入 | kill/pause/delay 容器 | P0 |
| Gatling | 压测工具 | QPS/P95 测试 | P0 |
| QPS / P95 延迟 | 性能指标 | 压测报告 | P0 |
| MTTR | 平均故障恢复时间 | AI 建议效果指标 | P0 |
| 线性一致性 | 并发操作可线性化的正确性 | Jepsen（探索性） | P2 |
| Jepsen | 分布式系统一致性测试框架 | 可选验证 | P2 |
| 对比实验 | AI vs 基线量化对比 | 证明 AI 增量价值 | P0 |
| A/B 对比 | 两种方案效果对比 | 检测算法选型 | P0 |

### 9.6 前端

| 知识点 | 概念 | 项目用途 | 优先级 |
|--------|------|---------|--------|
| Vue3 | 渐进式前端框架 | Admin 看板 | P0 |
| Element Plus | Vue3 UI 组件库 | 列表/表单/对话框 | P0 |
| ECharts | 数据可视化库 | 事件时间线/图表 | P1 |
| WebSocket | 全双工通信 | 实时告警推送 | P0 |
| REST / HTTP | 接口通信 | 前后端交互 | P0 |
| 单页应用 (SPA) | 前端路由与状态管理 | 看板交互 | P1 |

### 9.7 部署与运维

| 知识点 | 概念 | 项目用途 | 优先级 |
|--------|------|---------|--------|
| Docker | 容器化 | 各服务镜像 | P0 |
| Docker Compose | 多容器编排 | 全栈本地起 | P0 |
| Compose profiles | 按需启动服务组 | Pumba 混沌按需 | P1 |
| 服务依赖管理 | depends_on / 健康检查 | 启动顺序控制 | P0 |
| 环境变量配置 | 配置外部化 | DB 密码等 | P0 |

### 9.8 Java / Spring 生态

| 知识点 | 概念 | 项目用途 | 优先级 |
|--------|------|---------|--------|
| Spring Boot 3 | 主流后端框架 | 主服务 | P0 |
| JDK 17 | LTS Java 版本 | 基础运行时 | P0 |
| 虚拟线程 | JDK 21+ 轻量级线程 | 高并发（了解） | P2 |
| Spring 事务 (@Transactional) | 声明式事务管理 | 事件+命令日志同事务 | P0 |
| JdbcTemplate | Spring JDBC 简化 | 读模型投影写入 | P0 |
| Spring Kafka (@KafkaListener) | Kafka 消费注解 | 消费事件 | P0 |
| Maven 多模块 | 项目组织 | server/ai/ui 分离 | P0 |
| Spring 模块化 | controller/service/repository 分层 | 代码组织 | P0 |
| 异常处理 | @ControllerAdvice | 全局异常返回 | P1 |

### 9.9 Python 生态

| 知识点 | 概念 | 项目用途 | 优先级 |
|--------|------|---------|--------|
| FastAPI | 异步 Web 框架 | AI 服务 | P0 |
| asyncio | Python 异步编程 | Kafka 消费并发 | P0 |
| scikit-learn | ML 库 | Isolation Forest | P0 |
| hmmlearn | HMM 库 | V2 流程检测 | P1 |
| joblib | 模型持久化 | 加载训练好的模型 | P0 |
| kafka-python | Kafka 客户端 | 消费事件 | P0 |
| Pydantic | 数据模型校验 | FastAPI 请求/响应 | P0 |
| uvicorn | ASGI 服务器 | FastAPI 运行 | P0 |

### 9.10 软件工程与设计思维

| 知识点 | 概念 | 项目用途 | 优先级 |
|--------|------|---------|--------|
| 领域驱动设计 (DDD) | 以业务领域为核心的建模方法 | 聚合根/事件建模 | P1 |
| 读写分离 | 查询与命令分离优化 | CQRS | P0 |
| 防腐层 | 隔离外部系统变化 | AI 服务与后端边界 | P1 |
| 设计权衡 (Trade-off) | 一致性 vs 可用性 vs 性能 | 文档附录决策记录 | P0 |
| 可观测性 | 日志/指标/链路追踪 | 投影延迟监控 | P1 |
| 防御性编程 | 边界校验与兜底 | Agent 安全边界 | P0 |

### 9.11 学习路径建议（按 MVP 里程碑）

> 按里程碑顺序学习，学到即用，避免提前过度学习。

```
M1（W1-2）骨架跑通：
  重点学：Docker Compose / Kafka KRaft / Debezium 基础 / Spring Boot 起步 / Maven 多模块
  关键目标：跑通 CDC 链路（写事件表 → Kafka 能消费到）
  预计学习：3-4 天

M2（W3-4）事件溯源完整：
  重点学：事件溯源 / CQRS / 聚合根 / 乐观锁 / Transactional Outbox / Testcontainers / 幂等性
  关键目标：并发支付测试通过，读模型最终一致
  预计学习：5-6 天
  面试高频：乐观锁冲突处理、幂等设计、最终一致性

M3（W5-7）AI 检测 MVP：
  重点学：Isolation Forest / 特征工程 / F1-Score / FastAPI / LLM Prompt / 结构化输出
  关键目标：检测能命中注入的异常，根因分析能出报告
  预计学习：6-7 天
  面试高频：规则+ML 协同、模型评估指标、LLM 工程化

M4（W8-9）NL 查询 + 前端：
  重点学：意图分类 / Vue3 / Element Plus / WebSocket
  关键目标：Demo 能走通
  预计学习：4-5 天

M5（W10-12）验证 + 打磨：
  重点学：Pumba / Gatling / 对比实验方法论 / 混沌工程
  关键目标：成果物齐全，Demo 视频录制
  预计学习：3-4 天
```

### 9.12 推荐学习资源类型

| 知识领域 | 推荐资源 |
|---------|---------|
| 事件溯源 / CQRS / DDD | Martin Fowler 文章、Microsoft eShopOnContainers 示例、Axon 框架文档 |
| Kafka | 官方文档（KRaft 章节）、《Kafka 权威指南》 |
| Debezium | 官方 tutorial、PostgreSQL connector 文档 |
| PostgreSQL JSONB | 官方文档 JSON 函数与索引 |
| Isolation Forest | scikit-learn 文档、原论文 (Liu et al. 2008) |
| HMM | hmmlearn 文档、统计学习方法（李航）第 10 章 |
| LLM 应用 | OpenAI Cookbook、LangChain 文档（V2 用） |
| Testcontainers | 官方 quickstart、Spring Boot 集成文档 |
| Pumba | GitHub README、混沌工程入门文章 |
| Spring Boot | 官方 reference、Spring Guides |
