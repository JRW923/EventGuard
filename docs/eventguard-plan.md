# EventGuard — MVP 实现计划文档

> 日期: 2026-07-21
> 状态: 已确认
> 关联文档: `eventguard-design.md`（设计文档）
> 定位: 任务级工程可执行计划，仅覆盖 MVP

---

## 0. 计划说明

### 0.1 范围与设计文档关系

本计划基于 `eventguard-design.md` 第 8 章 MVP 路线图展开，仅覆盖 MVP 必做项（M1-M5）。V2 能力（HMM、Text-to-SQL、ReAct Agent、Saga 自动执行、Jepsen）不在本计划内，仅在文末"V2 待办"列出。

两者关系：
- **设计文档** = "做什么、为什么这样设计"（架构与接口契约）
- **本计划** = "怎么做、做到什么程度算完成"（执行步骤与验收点）

### 0.2 任务编号约定

`M{里程碑号}.{序号}`，如 `M2.3` 表示 M2 里程碑第 3 个任务。

### 0.3 状态标记

- `[ ]` 待办
- `[~]` 进行中
- `[x]` 完成

### 0.4 里程碑依赖图

```
M1 骨架 ──► M2 事件溯源 ──► M3 AI 检测 ──► M4 NL+前端 ──► M5 验证
                │                │
                └────────────────┴──► M5 验证（一致性/对比实验依赖 M2/M3）
```

### 0.5 单任务模板

每个任务包含 6 个字段：

```
### M{X}.{Y} 任务标题
- 状态：[ ]
- 依赖：M{X}.{Z}（前置任务）
- 涉及文件：新建/修改的文件清单
- 关键接口：接口签名 / DDL / 配置（与设计文档互补）
- 步骤：可执行的操作步骤
- 验收点：可勾选的完成判据（checkbox）
```

---

## 1. M1 骨架跑通（W1-2）

**里程碑目标**：docker-compose 起全栈，创建订单 → 事件入库 → CDC → Kafka 消费到。
**验收**：`curl POST /orders` → PG 事件表有记录 → Kafka echo consumer 能消费到事件。

### M1.1 项目脚手架

- **状态**：[x]
- **依赖**：—
- **涉及文件**：
  - `pom.xml`（父 POM）
  - `eventguard-server/pom.xml`
  - `eventguard-server/src/main/java/com/eventguard/EventGuardApplication.java`
  - `eventguard-server/src/main/resources/application.yml`
  - `eventguard-ai/requirements.txt`
  - `eventguard-ai/app/main.py`
  - `eventguard-ui/package.json`
  - `eventguard-ui/src/main.ts`
- **关键接口**：无（脚手架）
- **步骤**：
  1. 创建父 POM（Maven 多模块，声明 server 子模块；ai/ui 为独立项目）
  2. Spring Boot 3.3 + JDK 17 起步，引入 `spring-boot-starter-web`、`spring-boot-starter-jdbc`、`spring-kafka`
  3. FastAPI 起步：`fastapi`、`uvicorn`、`kafka-python`、`pydantic`
  4. Vue3 + Vite + Element Plus 起步
  5. 各模块加健康检查端点（`/actuator/health`、`/health`、前端首页）
- **验收点**：
  - [ ] 三个子项目能独立启动
  - [ ] 健康检查端点返回 200

### M1.2 docker-compose 编排

- **状态**：[x]
- **依赖**：M1.1
- **涉及文件**：
  - `docker-compose.yml`
  - `eventguard-server/Dockerfile`
  - `eventguard-ai/Dockerfile`
  - `eventguard-ui/Dockerfile`
  - `debezium/conf/application.properties`（占位，M1.5 填充）
- **关键接口**：无
- **步骤**：
  1. 编写 docker-compose.yml：`postgres:16`、`cp-kafka:7.6.0`（KRaft 模式）、`debezium/server:2.6`、三个应用服务
  2. Kafka KRaft 配置（`CLUSTER_ID`、`PROCESS_ROLES=broker,controller`、`CONTROLLER_QUORUM_VOTERS`）
  3. 各服务 Dockerfile（多阶段构建：JDK 17 运行时、Python 3.11 slim、Node 构建→nginx 运行）
  4. `depends_on` 与健康检查（PG `pg_isready`、Kafka `kafka-topics --bootstrap-server`）
  5. Pumba 服务用 `profiles: ["chaos"]` 按需启动
- **验收点**：
  - [ ] `docker compose up` 全栈启动无报错
  - [ ] 各服务健康检查通过
  - [ ] `docker compose --profile chaos up pumba` 能起 Pumba

### M1.3 事件表 + 命令日志表 DDL（最小版）

- **状态**：[x]
- **依赖**：M1.2
- **涉及文件**：
  - `eventguard-server/src/main/resources/db/migration/V1__init.sql`
- **关键接口**：无
- **步骤**：
  1. `domain_events` 表（`event_id`、`aggregate_id`、`aggregate_type`、`event_type`、`event_version`、`payload JSONB`、`metadata JSONB`、`created_at`，`UNIQUE(aggregate_id, event_version)`）
  2. `command_log` 表（`command_id` PK、`aggregate_id`、`command_type`、`result JSONB`、`executed_at`）
  3. 索引：`idx_events_agg_id` on `(aggregate_id, event_version)`
  4. 启动时自动执行（`spring.sql.init.mode=always` 或 Flyway）
- **验收点**：
  - [ ] 启动后表存在
  - [ ] 唯一约束生效（重复插入报错）

### M1.4 最小命令端

- **状态**：[x]
- **依赖**：M1.3
- **涉及文件**：
  - `eventguard-server/.../command/controller/OrderCommandController.java`
  - `eventguard-server/.../command/command/CreateOrderCommand.java`
  - `eventguard-server/.../command/handler/OrderCommandHandler.java`
  - `eventguard-server/.../event/model/DomainEvent.java`
  - `eventguard-server/.../event/model/OrderCreatedEvent.java`
  - `eventguard-server/.../event/store/EventStore.java`
  - `eventguard-server/.../event/store/EventStoreJdbcImpl.java`
- **关键接口**：
  ```java
  public record CreateOrderCommand(UUID commandId, UUID orderId,
                                   List<OrderItem> items, BigDecimal totalAmount) implements Command {}

  @PostMapping("/orders")
  public ResponseEntity<CommandResult> createOrder(@RequestBody CreateOrderCommand cmd);
  ```
- **步骤**：
  1. 定义 `Command` 接口、`DomainEvent` 基类、`OrderCreatedEvent`
  2. 实现 `EventStoreJdbcImpl.append`（INSERT 到 `domain_events`）
  3. `OrderCommandHandler.handle(CreateOrderCommand)`：生成 `OrderCreatedEvent` → append
  4. REST 接口 `POST /orders`
- **验收点**：
  - [ ] `curl -X POST /orders -d '{...}'` 返回 200
  - [ ] PG `domain_events` 表有一条 `OrderCreatedEvent` 记录

### M1.5 Debezium 配置

- **状态**：[x]
- **依赖**：M1.3
- **涉及文件**：
  - `debezium/conf/application.properties`
  - `docker-compose.yml`（PG 加 `wal_level=logical`）
- **关键接口**：无（配置文件）
- **步骤**：
  1. 配置 source：`PostgresConnector`、`pgoutput`、`table.include.list=public.domain_events`
  2. 配置 transforms：`unwrap`（`ExtractNewRecordState`，`drop.tombstones=true`）
  3. 配置 sink：`kafka`、`bootstrap.servers=kafka:9092`、`topic=domain-events`
  4. `message.key columns=aggregate_id`
  5. PG 开启逻辑复制（`wal_level=logical`、创建 replication slot 与 publication）
- **验收点**：
  - [ ] Debezium 启动无报错
  - [ ] 插入 `domain_events` 后，Kafka `domain-events` topic 有消息

### M1.6 Kafka echo consumer 验证

- **状态**：[x]
- **依赖**：M1.4, M1.5
- **涉及文件**：
  - `eventguard-server/.../event/DebugEventConsumer.java`（临时，验证后可删）
- **关键接口**：无
- **步骤**：
  1. 写一个 `@KafkaListener` 监听 `domain-events`，打印收到的 payload
  2. 调用 `POST /orders` 触发事件
  3. 观察 consumer 日志是否收到事件
- **验收点**：
  - [ ] 完整链路：`POST /orders` → PG 事件表 → Debezium CDC → Kafka → consumer 收到
  - [ ] payload 是纯净的 `OrderCreatedEvent` JSON（无 Debezium 包装）

---

## 2. M2 事件溯源完整（W3-4）

**里程碑目标**：聚合根状态机 + 快照 + 乐观锁 + 读模型投影 + 读己写一致性。
**验收**：下单→支付→发货全流程；并发支付测试通过；读模型最终一致。

### M2.1 完整 DDL

- **状态**：[x]
- **依赖**：M1.3
- **涉及文件**：
  - `eventguard-server/src/main/resources/db/migration/V2__full_schema.sql`
- **关键接口**：无
- **步骤**：
  1. 补充 `aggregate_snapshots` 表（`aggregate_id` PK、`aggregate_type`、`version`、`state JSONB`、`created_at`）
  2. 补充 `idempotent_consumers` 表（`consumer_group` + `event_id` PK、`processed_at`）
  3. 补充 `order_view` 读模型表
  4. 补充索引与外键约束
- **验收点**：
  - [ ] 所有表创建成功
  - [ ] 幂等消费表复合主键生效

### M2.2 聚合根基类 + 领域事件基类

- **状态**：[x]
- **依赖**：M2.1
- **涉及文件**：
  - `eventguard-server/.../event/model/DomainEvent.java`
  - `eventguard-server/.../command/aggregate/AggregateRoot.java`
- **关键接口**：
  ```java
  public abstract class AggregateRoot {
      private int version = 0;
      private final List<DomainEvent> pendingEvents = new ArrayList<>();
      protected void raise(DomainEvent event) { pendingEvents.add(event); apply(event); }
      public List<DomainEvent> flushPendingEvents() { ... }
      public int getVersion() { return version; }
  }
  ```
- **步骤**：
  1. 完善 `DomainEvent`（`eventId`、`aggregateId`、`eventType`、`version`、`occurredAt`、`metadata`）
  2. 实现 `AggregateRoot`（`pendingEvents` 列表、`raise` 方法、`version` 管理）
  3. 抽象 `apply` 方法由子类实现
- **验收点**：
  - [ ] `raise` 后 `pendingEvents` 含该事件
  - [ ] `flushPendingEvents` 清空列表并返回

### M2.3 OrderAggregate 状态机

- **状态**：[x]
- **依赖**：M2.2
- **涉及文件**：
  - `eventguard-server/.../command/aggregate/OrderAggregate.java`
  - `eventguard-server/.../command/aggregate/OrderStatus.java`
  - `eventguard-server/.../event/model/` 下各事件类（OrderCreatedEvent、PaymentCompletedEvent、PaymentFailedEvent、PaymentRetriedEvent、InventoryReservedEvent、OrderConfirmedEvent、ShippedEvent、DeliveredEvent、OrderClosedEvent、OrderCancelledEvent、OrderRefundedEvent）
- **关键接口**：
  ```java
  public enum OrderStatus {
      PENDING_PAYMENT, PAYMENT_FAILED, PAID, CONFIRMED, SHIPPED, DELIVERED, CLOSED, CANCELLED, REFUNDED
  }
  ```
- **步骤**：
  1. 定义 `OrderStatus` 枚举与所有事件类
  2. `OrderAggregate.handle` 各命令（CreateOrder/PayOrder/ReserveInventory/Confirm/Ship/Deliver/Close/Cancel/Refund）
  3. 每个命令校验当前状态合法性，非法迁移抛 `IllegalStateException`
  4. `apply` 方法更新 `status`
  5. `PaymentRetried` 重试计数，超 3 次转 `OrderCancelled`
- **验收点**：
  - [ ] 合法迁移正常产生事件
  - [ ] 非法迁移（如 `PENDING_PAYMENT`→`SHIPPED`）抛异常
  - [ ] 支付重试超 3 次自动取消

### M2.4 EventStore + SnapshotStore 实现

- **状态**：[x]
- **依赖**：M2.2
- **涉及文件**：
  - `eventguard-server/.../event/store/EventStore.java`
  - `eventguard-server/.../event/store/EventStoreJdbcImpl.java`
  - `eventguard-server/.../event/snapshot/SnapshotStore.java`
  - `eventguard-server/.../event/snapshot/SnapshotStoreJdbcImpl.java`
  - `eventguard-server/.../command/aggregate/AggregateRepository.java`
- **关键接口**：
  ```java
  public interface EventStore {
      void append(UUID aggregateId, List<DomainEvent> events, int expectedVersion);
      List<DomainEvent> load(UUID aggregateId);
      List<DomainEvent> loadFrom(UUID aggregateId, int fromVersion);
  }
  public interface SnapshotStore {
      Optional<Snapshot> load(UUID aggregateId);
      void save(Snapshot snapshot);
  }
  ```
- **步骤**：
  1. `append`：批量 INSERT `domain_events`，`expectedVersion` 校验（`UNIQUE` 冲突即并发冲突）
  2. `load`：先查快照，再 `loadFrom` 回放
  3. `loadFrom`：`SELECT WHERE aggregate_id=? AND event_version > ? ORDER BY event_version`
  4. `AggregateRepository.load`：快照+增量事件重建聚合根
  5. `append` 后若 `version % 100 == 0` 存快照
- **验收点**：
  - [ ] `expectedVersion` 不符时抛 `OptimisticConcurrencyException`
  - [ ] `load` 能从快照+增量事件正确重建
  - [ ] 第 100 个事件后自动存快照

### M2.5 乐观并发控制 + 重试

- **状态**：[x]
- **依赖**：M2.4
- **涉及文件**：
  - `eventguard-server/.../command/handler/CommandRetryTemplate.java`
  - `eventguard-server/.../common/exception/OptimisticConcurrencyException.java`
- **关键接口**：无
- **步骤**：
  1. 定义 `OptimisticConcurrencyException`
  2. `CommandRetryTemplate`：捕获该异常，重试最多 3 次（重新加载聚合根 → 重放命令 → 再 append）
  3. 重试间隔 10ms 线性退避
- **验收点**：
  - [ ] 并发冲突时自动重试
  - [ ] 3 次仍失败抛异常

### M2.6 幂等命令处理

- **状态**：[x]
- **依赖**：M2.4
- **涉及文件**：
  - `eventguard-server/.../command/handler/CommandLog.java`（实体）
  - `eventguard-server/.../command/handler/CommandLogRepository.java`
  - 修改各 `CommandHandler`
- **关键接口**：
  ```java
  @Transactional
  public CommandResult handle(Command cmd) {
      if (commandLog.exists(cmd.getCommandId())) return commandLog.loadResult(cmd.getCommandId());
      // ... 业务处理
      commandLog.save(cmd.getCommandId(), result);
  }
  ```
- **步骤**：
  1. `CommandLog` 实体 + Repository
  2. 各 handler 在事务内先查 `command_log`，已存在则返回
  3. 命令处理成功后写 `command_log`（同事务）
- **验收点**：
  - [ ] 同一 `commandId` 重复提交只执行一次
  - [ ] 返回首次结果

### M2.7 OrderViewProjection 读模型投影器

- **状态**：[x]
- **依赖**：M2.1, M1.5
- **涉及文件**：
  - `eventguard-server/.../query/projection/Projection.java`
  - `eventguard-server/.../query/projection/OrderViewProjection.java`
  - `eventguard-server/.../query/model/OrderView.java`
  - `eventguard-server/.../query/repository/OrderViewRepository.java`
- **关键接口**：
  ```java
  public interface Projection {
      void handle(DomainEvent event);
      void reset();
  }
  ```
- **步骤**：
  1. `Projection` 接口
  2. `OrderViewProjection` `@KafkaListener(topics="domain-events", groupId="order-view-projection")`
  3. `switch event` 类型 → INSERT/UPDATE `order_view`
  4. `OrderViewRepository` 查询接口
- **验收点**：
  - [ ] 下单后 `order_view` 有记录
  - [ ] 支付后 `order_view.status` 更新为 `PAID`

### M2.8 幂等消费

- **状态**：[x]
- **依赖**：M2.7
- **涉及文件**：
  - `eventguard-server/.../common/idempotent/IdempotentConsumer.java`
  - 修改 `OrderViewProjection`
- **关键接口**：
  ```java
  public interface IdempotentConsumer {
      boolean isProcessed(String consumerGroup, UUID eventId);
      void markProcessed(String consumerGroup, UUID eventId);
  }
  ```
- **步骤**：
  1. `IdempotentConsumer` 实现（查/写 `idempotent_consumers` 表）
  2. `OrderViewProjection.on` 开头检查幂等
  3. 处理后 `markProcessed`
- **验收点**：
  - [ ] 重复消费同一条事件，`order_view` 不变

### M2.9 读己写一致性

- **状态**：[x]
- **依赖**：M2.7
- **涉及文件**：
  - `eventguard-server/.../query/service/OrderQueryService.java`
  - `eventguard-server/.../common/exception/ProjectionLagException.java`
- **关键接口**：
  ```java
  public OrderView readAfterWrite(UUID orderId, int expectedVersion);
  ```
- **步骤**：
  1. 命令端返回 `expectedVersion`
  2. `readAfterWrite`：轮询 `order_view`，`version >= expectedVersion` 即返回，超 2s 抛 `ProjectionLagException`
  3. 查询 REST 接口支持带 `expectedVersion` 参数
- **验收点**：
  - [ ] 写后立即查能读到
  - [ ] 超时抛异常

### M2.10 Testcontainers 并发测试套件

- **状态**：[x]
- **依赖**：M2.5, M2.6
- **涉及文件**：
  - `eventguard-server/src/test/java/.../OrderConsistencyTest.java`
  - `eventguard-server/src/test/java/.../IdempotencyTest.java`
- **关键接口**：无（测试）
- **步骤**：
  1. 引入 `testcontainers` + `postgresql` 依赖
  2. `@Container PostgreSQLContainer`
  3. 并发支付测试：10 线程并发支付同一订单，断言只成功 1 个
  4. 幂等测试：重复提交同 `commandId`，断言只执行一次
  5. 事件不丢失测试：kill PG 重启后事件数一致
- **验收点**：
  - [ ] 并发测试通过
  - [ ] 幂等测试通过
  - [ ] 重启测试通过

---

## 3. M3 AI 检测 MVP（W5-7）

**里程碑目标**：事件级（规则+Isolation Forest）+ 流程级规则 + 根因分析 + WebSocket 告警。
**验收**：注入异常 → 检测命中 → 前端收到告警 → 点击查看根因报告。

### M3.1 Python AI 服务骨架

- **状态**：[x]
- **依赖**：M1.5
- **涉及文件**：
  - `eventguard-ai/app/main.py`
  - `eventguard-ai/app/kafka_consumer.py`
  - `eventguard-ai/app/config.py`
- **关键接口**：
  ```python
  @app.get("/health")
  def health(): return {"status": "ok"}
  ```
- **步骤**：
  1. FastAPI 应用 + uvicorn 启动配置
  2. `kafka_consumer`：消费 `domain-events`（`groupId=ai-event-detector`）
  3. 配置管理：`pydantic-settings` 读 `.env`
  4. 健康检查端点
- **验收点**：
  - [ ] AI 服务启动
  - [ ] 能消费到 Kafka 事件

### M3.2 合成数据生成

- **状态**：[x]
- **依赖**：M3.1
- **涉及文件**：
  - `eventguard-ai/training/generate_data.py`
  - `eventguard-ai/training/data/normal_events.jsonl`
  - `eventguard-ai/training/data/anomaly_events.jsonl`
- **关键接口**：无（数据生成脚本）
- **步骤**：
  1. 生成 10 万条正常订单事件流（按电商真实比例：CREATED→PAID→CONFIRMED→SHIPPED→DELIVERED→CLOSED）
  2. 注入异常：5% 金额偏离、3% 状态停滞/回退、2% 支付死循环、1% 组合异常
  3. 输出 JSONL 格式，标注 `is_anomaly` + `anomaly_type`
- **验收点**：
  - [ ] 正常数据无异常标注
  - [ ] 异常数据标注正确

### M3.3 Java 规则引擎

- **状态**：[x]
- **依赖**：M2.7
- **涉及文件**：
  - `eventguard-server/.../anomaly/rule/EventRule.java`
  - `eventguard-server/.../anomaly/rule/{R001AmountDeviationRule,R002DuplicatePaymentRule,R003StateJumpRule,R004HighFrequencyRule,R005InventoryOverflowRule}.java`
  - `eventguard-server/.../anomaly/engine/RuleEngine.java`
  - `eventguard-server/.../anomaly/engine/RuleContext.java`
  - `eventguard-server/.../anomaly/model/Anomaly.java`
- **关键接口**：
  ```java
  public interface EventRule {
      String ruleId();
      boolean matches(DomainEvent event, RuleContext ctx);
      AnomalyLevel level();
  }
  ```
- **步骤**：
  1. `EventRule` 接口 + `RuleContext`（用户历史、聚合根状态）
  2. R001 金额偏离（Z-Score > 3σ）
  3. R002 重复支付（5min 内同订单多次 `PaymentCompleted`）
  4. R003 状态跳跃（状态机校验）
  5. R004 高频操作（1min 内同用户 >20 订单）
  6. R005 库存越界
  7. `RuleEngine.evaluate`：加载 ctx → 遍历规则 → 返回首个命中
  8. 规则引擎提供 REST 接口 `POST /anomaly/rules/evaluate`（被 AI 服务协同调用，见 M3.5；不作为独立 Kafka 消费者，避免与 AI 侧重复告警）
- **验收点**：
  - [ ] 5 条规则各自能命中对应异常
  - [ ] 正常事件不误报

### M3.4 Isolation Forest 训练 + 持久化

- **状态**：[x]
- **依赖**：M3.2
- **涉及文件**：
  - `eventguard-ai/training/train_isolation.py`
  - `eventguard-ai/app/detector/event_level.py`
  - `eventguard-ai/models/isolation_forest.pkl`
  - `eventguard-ai/models/scaler.pkl`
- **关键接口**：
  ```python
  class EventLevelDetector:
      def detect(self, event: dict) -> AnomalyResult: ...
  ```
- **步骤**：
  1. 特征工程：`amount_zscore`、`time_since_last_event`、`user_order_count_1h`、`state_transition_prob`
  2. 用正常数据训练 `IsolationForest`（`n_estimators=100`）
  3. `StandardScaler` 标准化
  4. `joblib` 持久化模型
  5. `EventLevelDetector` 加载模型，`detect` 返回 `AnomalyResult`
- **验收点**：
  - [ ] 模型训练完成并保存
  - [ ] 异常事件 `detect` 返回 `is_anomaly=True`

### M3.5 事件级检测服务（规则 + ML 协同）

- **状态**：[x]
- **依赖**：M3.3, M3.4
- **涉及文件**：
  - `eventguard-ai/app/detector/event_level.py`（整合）
  - `eventguard-ai/app/kafka_consumer.py`（调用检测）
  - `eventguard-ai/app/publisher/anomaly_publisher.py`
- **关键接口**：无
- **步骤**：
  1. Kafka 消费事件 → 先调 Java 规则引擎（HTTP）→ 命中则高优先级告警
  2. 未命中 → 调 Isolation Forest → 异常则低优先级告警
  3. 告警发到 Kafka `anomaly-alerts` topic
  4. 规则引擎与 ML 协同的优先级逻辑
- **验收点**：
  - [ ] 规则命中的异常高优先级告警
  - [ ] ML 检出的异常低优先级告警

### M3.6 流程级规则检测

- **状态**：[x]
- **依赖**：M2.7
- **涉及文件**：
  - `eventguard-ai/app/detector/process_level.py`
  - `eventguard-ai/app/detector/event_window.py`
- **关键接口**：
  ```python
  class ProcessLevelRuleDetector:
      def detect(self, event_sequence: list[Event]) -> list[Anomaly]: ...
  ```
- **步骤**：
  1. `EventWindow`：按 `aggregate_id` 维护滑动窗口（最近 20 事件）
  2. 状态机非法迁移检测
  3. 状态停滞检测（PAID 后 24h 无后续）
  4. 死循环检测（`PaymentFailed`→`Retried` 重复 >5 次）
- **验收点**：
  - [ ] 三种流程异常都能检出

### M3.7 根因分析

- **状态**：[x]
- **依赖**：M3.5
- **涉及文件**：
  - `eventguard-ai/app/analyzer/root_cause.py`
  - `eventguard-ai/app/analyzer/prompt_builder.py`
- **关键接口**：
  ```python
  class RootCauseAnalyzer:
      def analyze(self, anomaly: Anomaly) -> AnalysisReport: ...

  # REST 端点（供前端 M4.4 调用）
  @app.get("/anomalies/{anomaly_id}/analysis")
  def get_analysis(anomaly_id: str) -> AnalysisReport: ...
  ```
- **步骤**：
  1. 加载异常相关事件 + 上下文（库存、用户、订单状态）
  2. 构建 prompt（anomaly + events + context + `ACTION_CATALOG`）
  3. LLM 生成结构化 JSON（`rootCause`、`evidence`、`suggestions`）
  4. Pydantic 校验 schema，建议必须在白名单内
  5. 接入 Ollama 本地 或 远端 API
  6. 暴露 REST 接口 `GET /anomalies/{anomaly_id}/analysis`（前端 M4.4 调用）
- **验收点**：
  - [ ] 输出合法 JSON
  - [ ] 建议在白名单内（`REFUND`/`NOTIFY_DELAY`/`MARK_OUT_OF_STOCK`/`FREEZE_ORDER`/`BACKOFF_AND_STOP`）

### M3.8 异常告警 WebSocket 推送

- **状态**：[x]
- **依赖**：M3.5
- **涉及文件**：
  - `eventguard-server/.../common/websocket/AnomalyWebSocketHandler.java`
  - `eventguard-server/.../anomaly/consumer/AnomalyAlertConsumer.java`
- **关键接口**：无
- **步骤**：
  1. Spring WebSocket 配置（`/ws/anomalies` 端点）
  2. `AnomalyAlertConsumer` 消费 `anomaly-alerts` topic → 推送到 WebSocket
  3. 前端连接 WebSocket 接收告警
- **验收点**：
  - [ ] 异常发生时前端实时收到告警

### M3.9 [可选] HMM 训练与检测

- **状态**：[x]
- **依赖**：M3.2
- **涉及文件**：
  - `eventguard-ai/training/train_hmm.py`
  - `eventguard-ai/app/detector/process_level_hmm.py`
- **关键接口**：无
- **步骤**：
  1. 用正常序列训练 `hmmlearn` HMM
  2. 设定 log-likelihood 阈值
  3. `ProcessLevelHMMDetector` 检测低概率序列
- **验收点**：
  - [ ] HMM 能检出异常序列

---

## 4. M4 NL 查询 + 前端（W8-9）

**里程碑目标**：意图分类 + 模板查询 + Admin 看板 + Demo 走通。
**验收**：5 分钟 Demo 雏形。

### M4.1 意图分类

- **状态**：[x]
- **依赖**：M3.1
- **涉及文件**：
  - `eventguard-ai/app/query/intent_classifier.py`
- **关键接口**：
  ```python
  class IntentClassifier:
      def classify(self, question: str) -> str:  # event_lookup|stats_aggregation|trace_replay
  ```
- **步骤**：
  1. Prompt 设计（3 类意图 + 示例）
  2. LLM 调用返回意图标签
  3. 兜底：LLM 失败时关键词匹配（"状态变更"→`trace_replay`、"多少"→`stats_aggregation`）
- **验收点**：
  - [ ] 3 类意图分类准确

### M4.2 模板查询执行器

- **状态**：[x]
- **依赖**：M4.1
- **涉及文件**：
  - `eventguard-ai/app/query/template_executor.py`
  - `eventguard-ai/app/query/nl_query_engine.py`
- **关键接口**：
  ```python
  class NLQueryEngine:
      def query(self, question: str) -> QueryResult: ...
  ```
- **步骤**：
  1. `event_lookup`：提取 `order_id` → 调后端 `GET /orders/{id}`
  2. `stats_aggregation`：提取时间窗 + 状态 → 调后端 `GET /orders/stats?status=&from=&to=`（后端模板 SQL，非 LLM 生成；AI 服务不直连 DB）
  3. `trace_replay`：提取 `order_id` → 调后端 `GET /orders/{id}/events` → 时间线
  4. 后端在 `OrderQueryService` 补充 `GET /orders/stats` 聚合查询接口（按 status 分组 + 时间窗过滤）
  5. LLM 润色结果回答
  6. REST 接口 `POST /ai/query`
- **验收点**：
  - [ ] 3 类查询都能返回正确结果

### M4.3 订单列表页

- **状态**：[x]
- **依赖**：M2.7
- **涉及文件**：
  - `eventguard-ui/src/views/OrderList.vue`
  - `eventguard-ui/src/api/order.ts`
- **关键接口**：无（前端）
- **步骤**：
  1. Element Plus Table + 分页
  2. 状态筛选
  3. 调 `GET /orders`
- **验收点**：
  - [ ] 列表展示 + 分页 + 筛选可用

### M4.4 异常看板

- **状态**：[x]
- **依赖**：M3.8
- **涉及文件**：
  - `eventguard-ui/src/views/AnomalyDashboard.vue`
- **关键接口**：无
- **步骤**：
  1. WebSocket 连接 `/ws/anomalies`
  2. 实时告警列表
  3. 历史异常查询
  4. 点击异常 → 查看根因报告（调 `GET /anomalies/{id}/analysis`）
- **验收点**：
  - [ ] 实时告警弹出
  - [ ] 根因报告展示

### M4.5 NL 查询框

- **状态**：[x]
- **依赖**：M4.2
- **涉及文件**：
  - `eventguard-ui/src/views/NLQuery.vue`
- **关键接口**：无
- **步骤**：
  1. 输入框 + 提交按钮
  2. 调 `POST /ai/query`
  3. 结果展示（文字 + 图表/时间线）
- **验收点**：
  - [ ] 3 类查询都能展示

### M4.6 事件时间线可视化

- **状态**：[x]
- **依赖**：M2.7
- **涉及文件**：
  - `eventguard-ui/src/views/OrderTimeline.vue`
  - `eventguard-ui/src/components/EventTimeline.vue`
- **关键接口**：无
- **步骤**：
  1. 调 `GET /orders/{id}/events` 获取事件列表
  2. ECharts 时间线组件
  3. 每个事件节点显示类型 + 时间 + payload
- **验收点**：
  - [ ] 时间线正确渲染

### M4.7 补偿执行按钮

- **状态**：[x]
- **依赖**：M2.7
- **涉及文件**：
  - `eventguard-ui/src/views/CompensationExecute.vue`
  - `eventguard-server/.../compensation/controller/CompensationController.java`
- **关键接口**：
  ```java
  @PostMapping("/compensations")
  public ResponseEntity<CommandResult> execute(@RequestBody CompensationRequest req);
  ```
- **步骤**：
  1. 后端 `POST /compensations` 接收人工触发的补偿（`actionType` + `aggId` + `params`）
  2. 校验 `actionType` 在白名单
  3. 转换为补偿命令 → `commandBus.dispatch`
  4. 前端按钮：异常详情页"执行建议"按钮
- **验收点**：
  - [ ] 点击按钮触发补偿命令
  - [ ] 订单状态更新

---

## 5. M5 验证 + 打磨（W10-12）

**里程碑目标**：Testcontainers + Pumba + AI 对比 + 压测 + Demo 视频。
**验收**：所有成果物齐全；Demo 视频录制完成。

### M5.1 Testcontainers 一致性套件完善

- **状态**：[x]
- **依赖**：M2.10
- **涉及文件**：
  - `eventguard-server/src/test/java/.../consistency/*.java`
- **关键接口**：无
- **步骤**：
  1. 并发写入冲突测试（已在 M2.10，补充边界用例）
  2. 读模型最终一致测试（写入后轮询，99% 500ms 内一致）
  3. 幂等消费测试（重复消费不变）
  4. 事件不丢失测试（kill PG 重启）
- **验收点**：
  - [ ] 全部测试通过
  - [ ] 输出测试报告

### M5.2 Pumba 混沌实验

- **状态**：[x]
- **依赖**：M1.2
- **涉及文件**：
  - `eventguard-chaos/experiments/db-kill.sh`
  - `eventguard-chaos/experiments/kafka-pause.sh`
  - `eventguard-chaos/experiments/ai-delay.sh`
  - `eventguard-chaos/verify.sh`
- **关键接口**：无
- **步骤**：
  1. `db-kill`：`pumba kill postgres` 30s，验证数据不丢
  2. `kafka-pause`：`pumba pause kafka`，验证命令端仍可写
  3. `ai-delay`：`pumba delay --time 5000 eventguard-ai`，验证规则引擎兜底
  4. 截图 + 恢复曲线
- **验收点**：
  - [ ] 三种故障系统均能降级/恢复
  - [ ] 截图归档

### M5.3 AI vs Baseline 对比实验

- **状态**：[x]
- **依赖**：M3.5, M3.6
- **涉及文件**：
  - `eventguard-ai/training/evaluate.py`
  - `eventguard-benchmark/ai-vs-baseline.md`
- **关键接口**：无
- **步骤**：
  1. Baseline：固定阈值规则
  2. AI Enhanced：Isolation Forest + 流程规则
  3. 在同一测试集上跑，计算 F1、误报率、检出率
  4. 生成对比表
- **验收点**：
  - [ ] F1: 0.85 → 0.92
  - [ ] 对比表完成

### M5.4 Gatling 压测

- **状态**：[x]
- **依赖**：M2.7
- **涉及文件**：
  - `eventguard-benchmark/gatling/OrderSimulation.scala`
  - `eventguard-benchmark/results/`
- **关键接口**：无
- **步骤**：
  1. Gatling 场景：下单 → 支付 → 查询，递增并发
  2. 跑 1min/5min 压测
  3. 输出 QPS、P95 延迟报告
- **验收点**：
  - [ ] QPS 曲线生成
  - [ ] P95 < 500ms（基线）

### M5.5 5 分钟 Demo 视频

- **状态**：[x]
- **依赖**：M4.7
- **涉及文件**：
  - `docs/demo-script.md`
  - `docs/demo-video.mp4`
- **关键接口**：无
- **步骤**：
  1. 按设计文档 5.4 节演示脚本走一遍
  2. 录屏
  3. 配字幕
- **验收点**：
  - [ ] 5 分钟内走完 6 个场景

### M5.6 README + 架构图

- **状态**：[x]
- **依赖**：全部
- **涉及文件**：
  - `README.md`
  - `docs/architecture.png`
- **关键接口**：无
- **步骤**：
  1. README：项目简介、架构、快速启动、技术栈、验证成果
  2. 架构图：从设计文档复制 + 美化
  3. 面试讲解映射表
- **验收点**：
  - [ ] 仓库可交付

---

## 6. 跨里程碑公共约定

### 6.1 包名/命名规范

| 范围 | 规范 |
|------|------|
| Java 包 | `com.eventguard.{command|event|query|compensation|anomaly|common}` |
| Python 模块 | `app.{detector|query|analyzer|agent|kafka_consumer|config}` |
| 类名 | PascalCase |
| 方法/变量 | camelCase（Java）/ snake_case（Python） |
| 事件类型 | `{Aggregate}PastTenseEvent`，如 `OrderCreatedEvent` |
| Topic 名 | kebab-case，如 `domain-events`、`anomaly-alerts` |
| 表名 | snake_case，如 `domain_events`、`order_view` |

### 6.2 错误码与异常体系

错误码格式：`EG-{模块}-{编号}`

| 模块前缀 | 模块 |
|---------|------|
| `EG-CMD` | 命令端 |
| `EG-EVT` | 事件存储 |
| `EG-QRY` | 查询端 |
| `EG-AI` | AI 服务 |
| `EG-CMP` | 补偿端 |

```java
public abstract class ApiException extends RuntimeException {
    private final ErrorCode errorCode;
    public ApiResponse<?> toResponse() {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), MDC.get("traceId"));
    }
}
```

全局异常处理：`@ControllerAdvice` 统一返回 `{code, message, traceId}`。

### 6.3 日志格式（traceId 贯穿）

- 命令入口生成 `traceId`（UUID），存入 MDC
- `traceId` 写入事件 `metadata`
- Debezium CDC 透传 metadata 到 Kafka 消息
- AI 服务从 Kafka header 取 `traceId`，继续 MDC 传递
- 日志格式：`[traceId] [module] level msg`
- 关键节点打日志：命令接收、事件写入、CDC 推送、检测命中、补偿执行

### 6.4 配置管理

| 层 | 文件 | 说明 |
|----|------|------|
| Spring Boot | `application.yml` | 分层：`application.yml`（默认）、`application-dev.yml`、`application-docker.yml` |
| 环境变量 | `DB_PASSWORD`、`LLM_API_KEY` | 敏感信息 |
| Python | `.env` + `pydantic-settings` | AI 服务配置 |
| Docker | `.env` | compose 变量 |

`application.yml` 关键结构：

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/eventguard}
    username: ${DB_USER:eventguard}
    password: ${DB_PASSWORD:eventguard}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:kafka:9092}

eventguard:
  snapshot:
    interval: 100
  concurrency:
    retry-max: 3
```

---

## 7. V2 待办（不在本计划内）

| 能力 | 对应设计文档章节 | 说明 | 状态（截至 2026-07-25） |
|------|----------------|------|------|
| HMM 流程检测 | 4.2 / 7.3.2 | M3.9 可选任务 | 已实现（2026-07-25，规则检测第二意见 + 流程级 CategoricalHMM） |
| Text-to-SQL | 4.3 / 7.3.3 | 全量 NL→SQL 安全沙箱 | 未做（MVP 有意推迟） |
| ReAct Agent 自愈 | 4.4 / 7.3.4 | 自动补偿 + 审批流 | 未做（MVP 有意推迟） |
| Saga 编排 | 7.4 | 补偿端完整实现 | 未做（MVP 有意推迟） |
| Jepsen 形式化 | 5.1 | 探索性一致性验证 | 未做 |
| 投影延迟告警 | 7.2.5 | 监控增强 | 已实现（Micrometer `Timer`/`Counter`，读己写超时计入 `eventguard.projection.lag`） |
| 事件时间线编辑器 | 6 | 前端增强 | 已实现（按版本回放 / time-travel，时间线 viewer + `upToVersion` 回放） |

> 注：V2 主线（端点鉴权 V2.1–V2.5、AI 异步化 V2.7、补偿 Bean 化 V2.6、未用导入清理 V2.8、Git LFS V2.9、WS 鉴权整合缺陷修复 V2.10）已合并 main，不在上表范围内。

---

## 8. 实现状态总览（截至 2026-07-25）

> 各任务状态标记已按代码实际实现回填：`[x]` 完成 / `[ ]` 未做 / `[~]` 部分实现。
> V2 主线（端点鉴权 V2.1–V2.5 + AI 异步化 V2.7 + 补偿 Bean 化 V2.6 + 导入清理 V2.8 + Git LFS V2.9 + WS 鉴权整合修复 V2.10）已于 2026-07-25 合并 main，详见 `docs/superpowers/plans/2026-07-24-v2-known-ceilings.md`。
> 2026-07-25 末次补做（commits `fa0aca1`…`3f5eca6`）：M3.9 HMM、M5.2 混沌、M5.3 对比、M5.4 压测、M5.5 Demo 脚本、M5.6 架构 SVG，以及 V2 局部增强（投影延迟监控 + 时间线版本回放）。**至此 MVP 全部任务完成。**

**已完成里程碑**：M1（骨架）→ M2（事件溯源完整）→ M3.1–M3.9（AI 检测主线 + 可选 HMM 流程级检测）→ M4（NL 查询 + 前端）→ M5.1–M5.6（一致性测试、混沌、对比、压测、Demo 脚本、架构 SVG）。

**未实现（V2 主线进阶，MVP 设计有意推迟）**：
- Text-to-SQL 全量 NL→SQL 安全沙箱（设计 4.3 / 7.3.3）
- ReAct Agent 自愈（自动补偿 + 审批流，设计 4.4 / 7.3.4）
- Saga 编排（跨服务自动补偿，设计 7.4）
- Jepsen 形式化一致性验证（设计 5.1）

**交付说明（非阻塞项）**：
- M5.5 Demo 视频：交付 5 分钟走查脚本 `docs/demo-script.md`（6 场景）。mp4 需人工录制（AI 无法生成视频），脚本末尾已注明。
- V2 局部增强已补齐：`eventguard.projection.lag` 计数（读己写超时时 +1）+ 时间线按 `upToVersion` 版本回放。

---

## 附录：面试讲解映射

| 面试考点 | 对应任务 | 讲解素材 |
|---------|---------|---------|
| 分布式一致性 | M2.4-M2.6 | 乐观锁、Transactional Outbox、幂等 |
| 并发编程 | M2.5, M2.10 | 并发支付测试、重试 |
| 高可用 | M5.2 | Pumba 混沌验证 |
| 消息队列 | M2.7, M2.8 | 分区、消费组、幂等消费 |
| 数据库设计 | M2.1 | JSONB、append-only、唯一约束 |
| AI 工程化 | M3.3-M3.7 | 规则+ML 协同、意图分类、根因分析 |
| 系统设计 | M1.2 | CQRS、事件溯源、CDC |
| 工程素养 | M5.1, M5.3 | Testcontainers、AI 对比 |
| 项目难点 | M1.5, M3.4 | Debezium 踩坑、IF 调优 |
