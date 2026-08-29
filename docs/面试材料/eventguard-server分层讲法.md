# EventGuard Server 分层讲法（面试版）

> 适用场景：后端 / Java 全栈岗自我介绍后的架构追问。
> 讲法原则：**先讲脊柱（事件溯源 + CQRS），再按数据流分层次之，最后用依赖方向收尾**；把面试官引到最熟的主线上。

---

## 一、开场一句话（定调）

> "EventGuard 的 `eventguard-server` 是**事件溯源 + CQRS** 架构：写侧用聚合根产出领域事件、落事件库，读侧通过 Kafka 把事件投影成只读视图，二者靠 `version` 字段保证最终一致。其余 auth / gateway / compensation / anomaly 都建立在这条事件链路上。"

这句话把话题锚定在核心架构，避免先被问偏门的监控/压测模块。

---

## 二、分层讲法（按数据流顺序，不要按字母顺序）

### 1. `command/` —— 写侧，事实来源

- **聚合根** `OrderAggregate.java:19`：`handle(命令)` → `raise(事件)` → `apply(事件)` 三段式。
- **聚合根基类设计（事件溯源机制）**：`AggregateRoot` 抽象基类（`command/aggregate/AggregateRoot.java`）统一托管"待持久化事件队列"与"版本号"，是所有事件溯源聚合的复用底座：
  - `raise(event)` 写路径：事件入 `pendingEvents` 并立即 `apply` 更新内存状态；
  - `applyEvent(event)` 重放路径：只 `apply` 更新状态并推进 `version`，**不**入队，保证回放不产出新事件；
  - `apply(event)` 为抽象方法，由 `OrderAggregate` 实现"每种事件如何改状态"，是**唯一的状态变更入口**；
  - `flushPendingEvents()`：**"提交边界"**——把 `pendingEvents` 中**尚未落盘**的待存事件整批取出并清空队列（防止后续 `save` 重复取走、重复落库），把 `version` 刷到这批最后一个事件的版本，再返回这批事件。`flush` 本身**不碰数据库**，真正写 `domain_events` 是紧接着 `AggregateRepository.save` 里调的 `EventStore.append(newEvents)`。
  - **`flush` 把 `version` 刷到最后事件版本的作用**：`version` 字段代表"当前已持久化的最高事件版本"，刷齐后三个机制才正确——① **后续命令编号**：`raise` 新事件用 `getVersion()+1`，不刷就会和已落库事件重复撞 `UNIQUE`；② **乐观锁基准**：`save` 算 `expectedVersion = getVersion() - newEvents.size()`（即这批之前的最高版本），`EventStore.append` 据此校验并发；③ **快照与重放一致**：快照按 `getVersion()%100==0` 触发，且 `load` 重放也是逐事件把 `version` 设为 `event.getVersion()`，与 `flush` 对齐，保证内存态和事件库重建态版本一致。一句话：`flush` 把聚合版本基准推进到"刚落盘的事件链末端"，使后续编号、并发锁、快照都建立在同一基准上。
  - 状态变更只能经由事件，命令不直接改字段。
- **全系统仅一个事件溯源聚合**：目前只有 `OrderAggregate` 继承 `AggregateRoot`（grep 全工程唯一）；其余上下文（`auth` 的 User/Role、`compensation` 的 Approval、`anomaly` 告警历史）走常规 JPA/CRUD 仓储，未走事件溯源。事件库 `EventStoreJdbcImpl.java:20-22` 的 `ponytail:` 注释也明示"当前只有 Order 一个聚合，`aggregate_type` 恒为该值"，并给出升级路径（给 `DomainEvent` 加 `aggregateType()`，由事件自带类型，而非写入处硬猜）。
- **命令处理器（应用层）** `OrderCommandHandler.java`：所有订单命令统一走"幂等检查 → 事务内加载+处理+保存 → 写命令日志"模板：
  - **命令级幂等**（`command/command`+`CommandLogRepository`）：以 `commandId` 在事务内加锁并查 `command_log`，命中则返回已存结果（含 `fingerprint` 防重复请求内容不一致，`assertCompatible` 校验）；未命中才加载聚合、执行、保存事件并写日志，**同事务保证原子**。
  - **重试** `CommandRetryTemplate`：每次重试开新事务，乐观锁冲突可安全重入。
  - **网关在幂等内调用**：`ReserveInventoryCommand` 在幂等检查后先调 `InventoryGateway`（幂等键=commandId），按成功/库存不足决定 `raise` 哪个事件——外部调用与领域决策在同一幂等边界内。
- **状态机**（见 `OrderAggregate.java:13-18` 注释）：`PENDING_PAYMENT → PAID → CONFIRMED → SHIPPED → DELIVERED → CLOSED`，外加退款/取消/重试超限分支。
- **业务规则收敛**："只有待支付的订单才能发起支付"（`OrderAggregate.java:41`）这类约束只在聚合根内校验，控制器不直接改状态。
- **支付异步化**（`OrderAggregate.java:44-46`）：`PayOrderCommand` 只发"支付意图"事件、状态不变，真实结果靠网关回调的 `CompletePaymentCommand` 落库——防止支付回调与本地状态不一致。

### 2. `event/` —— 事件溯源核心

- **事件库** `EventStoreJdbcImpl.java:36`：只做三件事——`append`（带版本校验写入 `domain_events`）、`load`（按版本回放）、`loadFrom`（断点续放 / 快照）。
- **乐观锁是亮点**（`EventStoreJdbcImpl.java:42-52`）：`pg_advisory_xact_lock` 聚合级事务锁 + `expectedVersion` 校验，并发写走"乐观锁冲突可重试"，而非撞 UNIQUE 约束走异常路径（不可重试）。
- **事件结构（payload / metadata 双 jsonb）**：业务字段与穿越上下文（如 `userId`）分列，加字段不破坏表结构；`EventDeserializer` 按 `event_type` 反序列化成具体事件类。
- **快照与断点**（`event/snapshot` + `loadFrom`）：超长事件链可从最近快照 + 增量续放，避免每次从头重算（见名词表"快照"）。
- **CDC 出口**：`domain_events` 经 Debezium 监听 PostgreSQL WAL 投递到 Kafka `domain-events`，是读写解耦的物理桥梁（读侧、AI 侧均消费此流）。
- **版本即真相**：事件版本单调递增，是投影、快照、读己写全部对齐的基准。

### 3. `query/` —— 读侧，最终一致性

读侧回答"用户/大屏怎么看订单"。写侧只存事件，没人会直接拿 `domain_events` 当订单状态查——那要回放，太慢。所以 `query/` 用 Kafka 把事件**投影**成一张扁平的 `order_view`（一行 = 一个订单的当前可读状态：`order_id/status/total_amount/version/payment_time/shipping_time/updated_at`），查询直接读这张表，不必回放事件。这也是 CQRS"读写存储分离"的体现。

- **投影器 `OrderViewProjection`**（`OrderViewProjection.java:48-74`）：`@KafkaListener` 消费 `domain-events`（`group=order-view-projection`），每条事件走四步（都在 `@Transactional("projectionTransactionManager")` 一个投影事务内，注：未用 `KafkaTransactionManager`，故 Kafka 位移提交与 DB 事务独立，靠 at-least-once + 幂等兜底）。使用独立的 `projectionTransactionManager`，与写侧事务隔离，读模型可独立故障、独立扩容。
  - **① 反序列化**：`deserializer.deserializeFromKafka(record.value())`（`:52-54`）把 Debezium 拆包后的纯 JSON 字符串按 `event_type` 还原成具体 `DomainEvent` 子类，后续才能 `instanceof` 分流。**契约不兼容/无法解析的消息（缺 `event_id`/`aggregate_id`/`event_type`、字段类型错、未知 `event_type`）不再抛异常**，而是在 `EventDeserializer` 降级为 `UnknownEvent` 占位对象、各消费者遇 `UnknownEvent` 直接跳过——这类坏消息**不进 DLT、不触发重放死循环**（曾因历史污染坏消息反复重放拖垮内存导致 OOM）。`DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 只兜底**真正处理失败**的消息（如 DB 抖动，重试 2 次仍失败进 `domain-events.DLT`），不吞不丢。
  - **② 占位去重**：`tryMarkProcessed(event.getEventId())`（`:60-63, 94-98`）`INSERT INTO idempotent_consumers ... ON CONFLICT DO NOTHING`——返回 false（主键冲突=已处理过，Kafka 重投）直接跳过；返回 true 才继续。占位 INSERT 与下一步 `UPDATE` **同处一个投影事务**（`:59`），任一失败整体回滚，保证"已去重"和"已投影"严格一致，把 Kafka 的 at-least-once 变成"实际只应用一次"。
  - **③ `handle` 按类型 UPDATE `order_view`（状态跃迁）**（`:65, 102-136`）：`if (event instanceof X)` 分流，每种事件映射不同 `UPDATE`（读模型状态机）：`OrderCreatedEvent`→`applyCreated` 建行（`INSERT ... ON CONFLICT DO NOTHING`，要求 `version==1`）；改状态的事件→`applyNext` 设 `status`/`payment_time`/`shipping_time` 等；"旁路记录"事件（`PaymentRequestedEvent` 等）→`advanceVersion` 只推 `version` 不改 `status`。统一带 `WHERE order_id = ? AND version = eventVersion - 1`（`:162`）防乱序覆盖；`updated==0` 时 `assertAlreadyAppliedOrGap`（`:168-175`）区分"无害重复（当前≥传入，放过）"与"真正缺口（当前<传入-1，抛异常等重放）"。
  - **④ 提交后通知读己写**：`notifyProgressAfterCommit(event)`（`:66, 80-92`）注册事务同步器，**仅 `afterCommit` 后**才 `progressNotifier.advance(aggregateId, version)`，唤醒 `OrderQueryService.readAfterWriteAsync` 中等待 `order_view.version` 追平 `expectedVersion` 的查询线程。必须在提交后（而非处理完）通知：否则等待方在旧事务可见性下读到未提交旧值，读己写失效；无事务上下文（测试）时降级为立即通知。
- **两个必考题**：
  - **幂等（at-least-once 兜底）**：`tryMarkProcessed`（`OrderViewProjection.java:94`）用 `idempotent_consumers` 表 `INSERT ... ON CONFLICT DO NOTHING` 占位；Kafka 重投同一事件时占位失败即跳过。占位与读模型 `UPDATE` 同处一个投影事务，任一失败整体回滚，保证"已去重"和"已投影"严格一致。
  - **顺序 / 缺口保护**：真正改状态的事件走 `applyNext`，其 `UPDATE ... WHERE order_id = ? AND version = eventVersion - 1`（`OrderViewProjection.java:162`）——只接受"紧接当前版本之后"的事件，防止乱序到达时高版本覆盖低版本。若 `updated==0`，`assertAlreadyAppliedOrGap`（`OrderViewProjection.java:168`）查当前 version：当前 ≥ 传入版本视为"已应用过的无害重复"（如重投）直接放过；当前 < 传入版本-1 才是"真正缺口"，抛异常等正确顺序重放。
  - **状态不变的事件也要推进 version**：`PaymentRequestedEvent`/`InventoryReservedEvent`/`CompensationExecutedEvent`/`OrderRefundRequestedEvent` 这些"旁路记录"在投影里走 `advanceVersion`——只把 `order_view.version` 往前推、不改 `status`。目的：让读模型 version 与事件链严格对齐，读己写才能靠 version 判断"投影追上了没有"。
- **读己写**：投影事务提交后才经 `afterCommit` 钩子 `notifyProgressAfterCommit`（`OrderViewProjection.java:80`）唤醒等待查询，确保唤醒时数据已真正可见（不会在旧事务可见性下查到未提交值）；`OrderQueryService` 据此实现"刚下单立刻查得到"——等 `order_view.version` 追上命令返回的 `expectedVersion` 才返回，超时抛 `ProjectionLagException`（宁可报错也不返回旧值）。
- **Kafka 消费组（不止投影一个）**：Kafka 的"消费组"是带独立 offset 的订阅者，同一主题可被多个组各自完整消费一遍、互不影响。本项目常驻三个组：`order-view-projection`（`OrderViewProjection.java:48`，消费 `domain-events` 建读模型）、`saga-trigger`（`SagaTrigger.java:58`，同样消费 `domain-events` 触发补偿 Saga）、`anomaly-ws`（`AnomalyAlertConsumer.java:39`，消费 `anomaly-alerts` 推 WebSocket）。其中 `domain-events` 被 `order-view-projection` 和 `saga-trigger` **两个组同时独立消费**是有意为之——投影卡住不会拖慢 Saga、Saga 重放也不影响投影，正是事件流支撑"一个写、多个读"的物理基础。另注意 `OrderViewProjection` 里的常量 `CONSUMER_GROUP = "order-view"`（`OrderViewProjection.java:28`）**不是 Kafka 组**，而是写进 `idempotent_consumers` 表的 DB 级去重键（`tryMarkProcessed` 用它判定"这条事件我投影过没有"），与 Kafka `groupId` 作用域不同，别混。
- **Kafka 分区与分配策略（两件事别混）**：
  - **分区路由（事件进哪个分区，决定保序）**：`domain-events` 某条事件落到哪个分区，由消息 key 的哈希决定。本项目经 Debezium 的 `ValueToKey`+`ExtractField` SMT（`debezium/conf/application.properties:41-44`）把 `aggregate_id` 提为 Kafka key，所以**同一订单的所有事件固定落同一分区**。这是投影保序的前提：同分区内 Kafka 按写入顺序投递、单线程顺序消费，`version` 才天然单调递增、`WHERE version = ?-1` 才恒成立。注意 ponytail 注释点明 Debezium Server 的 Kafka sink 不认 `message.key.columns`（实测 key 仍是 `event_id` 主键），故改用 SMT 实现——是一处刻意适配。
  - **分区分配策略（分区分给哪个消费者，决定并行度）**：指消费组内多个实例/线程如何瓜分 topic 的分区。本项目**未显式配置** `partition.assignment.strategy`，走 Kafka 客户端默认（现代客户端 2.4+ 默认 `CooperativeStickyAssignor`，旧版为 `RangeAssignor`）。`OrderViewProjection` 设 `concurrency = 3`（`OrderViewProjection.java:49`），即组内 3 个消费线程，`domain-events` 的各分区按分配策略分给这 3 个线程并行消费。
  - **两者如何合力**：路由按 `aggregate_id` 保证"一订单的事件在同一分区"，分配策略把"各分区分给不同线程"做并行；因每订单独占一个分区、该分区只归一个线程，订单级处理无跨线程竞争，`version` 顺序天然成立，无需跨线程协调。反例：若路由没按 `aggregate_id`（如默认按 `event_id`），同订单事件会散到多分区被多线程乱序消费，投影只能靠 `assertAlreadyAppliedOrGap` 缺口检测兜底——正确性仍保，但吞吐与延迟变差。
- **重建入口（ceiling）**：`reset()` 清空 `order_view`+`idempotent_consumers`，但未暴露生产端点、不回退 Kafka 位移——详见第八章 8.1。

### 4. `gateway/` —— 端口与适配器（外部系统隔离）

`gateway/` 是**领域核心与外部系统之间的一堵隔离墙**：订单领域逻辑（支付成没成、库存占没占到）不该直接去调支付宝、库存 HTTP 接口——否则领域代码就和外部系统的 URL/SDK/网络耦合死。它用"端口与适配器"模式把外部系统挡在外面，领域只认接口、具体调用交给适配器。

- **端口（接口）+ 适配器（实现）**：领域层只依赖三个接口 `PaymentGateway`（支付）/`InventoryGateway`（库存）/`NotificationGateway`（通知）；具体实现可替换——测试用 `Mock*`（不真调外部、结果确定），真实环境用 `AlipaySandboxPaymentGateway`（支付宝）、`HttpInventoryGateway`、`WeComNotificationGateway`（真实 HTTP）。**换渠道零改领域代码**，这就是六边形架构的价值。
- **支付异步回调收敛** `GatewayCallbackService.java`：支付是异步的——发起支付留一张 `gateway_request`（带 `external_ref` = 支付宝侧支付单号），支付宝处理完再**回调**通知成败。回调服务按 `external_ref` **反查**到 `gateway_request` 确认"哪笔订单"，已终态直接幂等跳过，否则更新状态并派发 `CompletePaymentCommand`/`FailPaymentCommand` 进聚合。**双层幂等**保回调重放不重复发事件：① 网关请求已是 SUCCEEDED/FAILED 终态 → 直接返回；② 命令侧用**确定性 `commandId`**（`callbackId|success` 算固定 UUID，`GatewayCallbackService.java:131-135`）走 `command_log` 幂等。例：order-1001 支付留 `gateway_request(external_ref=X)`，回调 `X` 成功→派发 `CompletePaymentCommand`→订单 PAID；网络重发两次，第二次因终态/commandId 命中被跳过，不会重复变 PAID。
- **失败语义翻译**：外部调用失败（如库存不足、渠道抖）被转成**领域事件**而非异常穿透核心——库存不足→`InventoryReservationFailedEvent`（状态仍 PAID，留记录供风控/补偿），支付失败→`FailPaymentCommand`→`PaymentFailedEvent`。聚合根保持纯净，只认"已发生的事实"，不关心外部为何挂。
- **编排** `PaymentCoordinator`：协调"支付→占库存→通知"正向履约的**发起顺序**；任一步失败交由 `compensation/`（下一节）补偿，如支付成功但库存不足则触发退款补偿。

### 5. `compensation/` —— Saga 长事务补偿

`compensation/` 是**"跨系统操作无法原子回滚"时的补救机制（Saga 模式）**：支付（钱）→ 占库存（货）→ 通知跨多个系统，支付宝扣了钱不可能跟着我们数据库事务一起回滚，所以哪一步失败就反过来做"补救动作"把已生效的影响抹掉，把订单"补回一致状态"。例：order-1001 支付成功（钱到了）但占库存失败（SKU-B 只剩 5 件、要 10 件），就触发"退款→标缺货→通知用户"的补偿 Saga，一步步补救到一致。

- **Saga 编排** `CompensationSaga.java`：按步骤执行补偿动作，某步失败则整个 saga 标记 FAILED；遇 `requiresApproval` 的步骤**挂起**并落 `ApprovalRepository` 审批单（大额退款这类动作不能自动做，要人审批），审批通过（`onApproved`）执行该步并继续后续；状态机 `SagaStatus`（STARTED/EXECUTING/AWAITING_APPROVAL/COMPLETED/FAILED）。
- **补偿动作可插拔** `action/`：`CompensationActionRegistry` 注册 `RefundAction`（退款）、`FreezeOrderAction`（冻结）、`MarkOutOfStockAction`（标缺货）、`NotifyDelayAction`（通知延迟）、`BackoffAndStopAction`（退避停止）等，saga 按 `actionType` 查表路由——要加一种补救动作，加个类注册即可，不碰编排器。
- **崩溃恢复** `SagaRecoveryRunner`：Saga 实例在内存，重启即丢；所以落审批单时把"剩余步骤清单"写进审批单 `params` 的**保留键 `__saga_remaining_steps`**（`CompensationSaga.java:38`，带 `__` 前缀供前端视图过滤、不与业务参数混），server 重启后 `recoverPending`（`:195-220`）从 PENDING 审批单读回这串步骤重建内存实例（index=0 指向待审批步骤），审批通过仍能续跑——解决"审批单在、实例丢，重启后审批即 FAILED"的补偿中断。
- **已知 ceiling（ponytail）**：Saga 实例存内存 `ConcurrentHashMap`（`CompensationSaga.java:43`，单实例上限），多副本各自持有不共享；升级路径=落库 Saga 状态机。这是 MVP 的有意简化，面试需主动说明。

### 6. `anomaly/` —— 规则与实时推送

`anomaly/` = **"风控 + 实时告警"**：对订单事件做异常检测（金额离谱、高频下单、库存溢出……），命中就产生告警，并实时推到前端"亮灯"。整体数据流：`事件 → 检测判定 → 命中 → 告警(Kafka anomaly-alerts) → 落库 + WebSocket 推前端`。它实际分两半——**判定能力**（规则引擎，Java）与**推送能力**（`AnomalyAlertConsumer`）。

- **规则引擎** `engine/` + `rule/`：R001 金额偏离、R002 重复支付、R003 状态跳跃、R004 高频下单、R005 库存溢出五条可插拔规则（`R001AmountDeviationRule` … `R005InventoryOverflowRule`）。`RuleEngine.evaluate(event)`（`RuleEngine.java:32-57`）遍历规则列表、**返回首个命中**的 `Anomaly`（ruleId + 级别 + 描述），未命中返回空——典型"中一条就报警"的风控语义。
- **规则上下文** `RuleContextLoader.java`：判定前先装载背景数据——R001 取该用户近 90 天 `OrderCreatedEvent` 的 `totalAmount` 均值/标准差作基线并**排除当前事件自身**（避免异常大单拉高自己的基线、规则永不触发，正是之前修过的 bug）；R002 取同单历史支付时间戳；R003 取 `version < 当前` 的前置状态（避免把合法状态迁移误报成"跳跃"）；R004 取用户近期下单；R005 经 `InventoryGateway.currentStock` 取真实库存。基线进程内 TTL 缓存，查询失败降级但不静默。
- **判定入口是 REST，不是 Kafka 消费（易误解点）**：`RuleEngineController` 经 `POST /anomaly/rules/evaluate` 暴露评估接口（`RuleEngineController.java:30-34`），注释明说"**不作为独立 Kafka 消费者，避免与 AI 侧重复告警**"，且仅机器主体（`anomaly:evaluate` 权限）可调——AI 模块消费 `domain-events` 后调此接口拿判定结果，再由 AI 侧发布 `anomaly-alerts`。别把 server 的 Java 规则引擎和 AI 检测当成重复逻辑：**Java 引擎是判定服务，AI 是编排消费 + 发布告警**。
- **告警消费与推送** `consumer/AnomalyAlertConsumer`（`AnomalyAlertConsumer.java:39-65`）：`@KafkaListener(topics="anomaly-alerts", groupId="anomaly-ws")`，处理顺序是**先落库再广播**——`historyRepository.save`（`:59`）失败抛异常走 Kafka 重试/DLT 保证告警不丢，`webSocketHandler.broadcast`（`:61`）推在线前端亮灯，离线用户重连后经 `/alerts/recent` 补拉。取舍：推送失败可补拉，但告警必须先存住。
- **真实 bug 故事素材**：规则依赖 `metadata.userId` 做用户维度聚合，但事件曾未写入该字段，导致 R004 高频规则永不触发、R001 基线算不出（数据链路断点：事件产生→落库→判定中一环漏传）；修复后才真正生效——适合作为"我修过生产问题"的例子（见第六章 `anomaly` 设计要点）。

### 7. `auth/` 与 `common/`

**auth/ —— 系统的"门口安检"**：管"谁能进、进来能干什么"——登录鉴权、接口权限、防爆破、审计，连 WebSocket 长连接也要过安检。

- **登录 → 发一张"带签名的通行证"（JWT）**：登录成功 `JwtService.issue`（`JwtService.java:42-61`）签发 HS256 签名的 JWT，之后请求带上它、服务端验签即认，无需查库。claims 装 `uid`（用户 id）/`roles`（角色）/`permissions`（权限清单）/`mcp`（强制改密）/`tv`（令牌版本），后端权限拦截、WebSocket 握手、AI 服务（PyJWT）共用同一密钥验签；默认 12h 过期。代码显式固定 HS256（`JwtService.java:45-46`）——jjwt 会按密钥长度推断算法，而 AI 侧 PyJWT 固定校验 HS256，固定住避免两端不一致。
- **令牌吊销（ponytail）**：靠 `tv` 令牌版本与 `auth_user.token_version` 比对——版本不一致即视为吊销，无需维护黑名单；但角色/权限在 claims 里，变更需重新登录才生效，且只能"用户级整体作废"、无法精确吊销单张令牌。升级路径见第八章 8.5（refresh token + `jti` 黑名单）。
- **三道"安检门"**：
  - `AuthFilter`：第一道——验签名、过期（信任边界），非法令牌 → 401；
  - `PermissionInterceptor` + `@RequirePermission`：第二道——方法/类上声明所需权限，没权限 → 403；
  - `JwtHandshakeInterceptor`：第三道——WebSocket 握手复用同一 JWT 校验，长连接不能绕开安检；
  - 另配 `LoginAttemptGuard`（防爆破：失败次数多了限流/锁定）、`AuditLogger`（审计留痕：谁在何时干了什么）。

**common/ —— 所有模块共用的"工具箱"**：纯技术能力下沉处，谁都能依赖它，但它不反向依赖任何业务模块，依赖图无环单向。

- `metrics`：`EventGuardMetrics` 打点（命令耗时/规则命中/投影进度），**可选注入**——单测 new 直构为 null 走空操作，不拖垮主链路；
- `DltReplayController` + `DltReplayScheduler`：死信消息定时/手动重放（见第六章 DLT 闭环）；
- `idempotent` / `websocket` / `scheduler` / `exception` / `config`：幂等组件、WebSocket 基础能力、定时任务、统一异常处理、Kafka 配置。
- **为什么单独一层**：① 复用不重复——订单/补偿/风控/网关都要打点、都要统一异常，下沉后各模块注入复用；② **刻意不承载订单业务规则**——保证 `common` 永不反向依赖业务包，依赖方向单向无环。

---

## 二（续）、整体方法论拼图：DDD / MVC / CQRS+ES 的分层共存

> 面试官若问"这到底是 DDD 还是 MVC"，用本节一句话定位：**内核 DDD、边缘 MVC、持久化 CQRS+事件溯源，三者分层共存、互不冲突。**

### 1. 三种方法论各管哪一层

| 方法论 | 作用层 | 在本项目的落点 |
|---|---|---|
| **MVC** | 表现层 / 最外围边界 | `command/controller`、`query/controller`、`auth/controller` 等 16 个 `@RestController`（C）；聚合与 DTO（M）；**View 不在本后端**，由独立的 `eventguard-ui`（Vue SPA）承担 |
| **DDD** | 领域核心 | 聚合根 `OrderAggregate`、仓储 `AggregateRepository`、命令处理器、限界上下文划分（`auth`/`command`/`event`/`query`/`gateway`/`compensation`/`anomaly`）、端口与适配器（`gateway/`） |
| **CQRS + 事件溯源** | 持久化 / 状态策略 | 写模型只落事件（`event/store`），读模型由 Kafka 投影（`query/projection`），版本号对齐 |

三者正交：**MVC 负责"外部怎么进来"，DDD 负责"业务怎么建模"，CQRS+ES 负责"状态怎么存与同步"**。

### 2. 它们为什么不冲突

MVC 的 Controller 本质是 DDD 语境下的**适配器（端口实现）**：它把 HTTP 请求翻译成领域能懂的 Command，再交给应用服务/聚合根。核心域不反向依赖 MVC，MVC 只在最外围做翻译——这正是六边形/DDD 推荐的分层。

```
HTTP 请求
  → MVC 的 Controller（接口层：收请求、校验、组装 Command）   ← MVC 在这一圈
  → DDD 应用服务 / Command Handler（把 Command 交给聚合）
  → DDD 聚合根（领域核心：状态机、业务规则）                  ← DDD 在内核
  → 事件溯源 / CQRS（持久化策略，与两者正交）                 ← 状态怎么存
```

### 3. 面试表述（避免说过头）

- ❌ "整个项目是 MVC 架构"——MVC 只覆盖接口层那一圈，View 还在别的进程。
- ❌ "纯 DDD、没有 MVC"——请求入口明确用了 Spring MVC 的 Controller 约定。
- ✅ "**以 DDD 为内核（聚合、仓储、限界上下文），MVC/REST 仅作最外层接入适配器，CQRS+事件溯源是持久化策略；三者分层共存。**"

### 4. 本项目是否"分布式"：概念澄清

> 关键纠偏：**"分布式"不等于"跑在不同机器上"**。判定标准是逻辑上的独立 + 异步协作 + 部分失败，而非物理位置。

- **分布式的定义**：多个自主组件通过网络传递消息协作，各有独立故障域。一台机器上 docker-compose 起 `server`+`kafka`+`postgres`+`ai` 四个容器，仍是独立进程、独立故障域、走网络/消息通信——依然是分布式架构。"不同机器"只是部署拓扑的一种，额外带来网络延迟与更密的故障模式，但不改变系统是否分布式这一性质。反例：单进程内全内存调用才是单体（monolith）。
- **本项目是分布式**：顶层模块 `eventguard-server` / `eventguard-ai` / `eventguard-ui` 与 `postgres` / `kafka` / `debezium` / `prometheus` 各自是独立服务/组件，只通过 **HTTP、Kafka、数据库 CDC** 三种跨进程边界协作。系统内部已落地分布式典型设计：最终一致性（读模型延迟）、Saga 跨服务补偿、`idempotent_consumers` 幂等（应对 Kafka at-least-once 重投）、Debezium CDC 跨库同步——这些"为应对网络/异步不确定性而存在的机制"本身就是分布式指纹。
- **精确分层（避免说过头）**：
  - 整个系统（server+ai+ui+基础设施）= 明确分布式系统。
  - `eventguard-server` 单一应用内部 = 一个 Spring Boot 可部署单元（偏单体结构），但内部已用 Kafka + 事件溯源实现写读分离、最终一致，具备分布式"思想"；若把 `query` 投影、`anomaly` 消费拆成独立服务，即更彻底的分布式。
- **面试答法**："架构上是分布式：多个自主服务靠 HTTP/Kafka/CDC 协作，有最终一致、Saga 补偿、幂等等典型设计。是否跨机器只是部署选择——单机多容器同样算分布式，因为组件是独立进程、独立故障域、走网络消息，而非单体内部调用。"

#### 微服务与分布式的关系（及本项目定位）

- **二者是子集关系**：微服务是分布式之上的一套**组织风格**。分布式只要求"组件在不同进程、靠网络消息协作、有独立故障域"；微服务在此之上还要满足——按业务能力（非技术层）切分、独立部署、每服务**自治数据**（不共享表）、轻量通信（HTTP/消息）。因此：**所有微服务必然分布式，但分布式不一定是微服务**。反例：单体应用 + 远程数据库、前端 + 后端两层，都是分布式却不是微服务。
- **本项目是否微服务——部分存在**：整体是分布式系统，拓扑上呈微服务式协作，但核心后端 `eventguard-server` 是**模块化单体**，不能算完整微服务架构。
  - `eventguard-server` 一个 Spring Boot 进程内包含 `auth`/`command`/`event`/`query`/`gateway`/`compensation`/`anomaly` 七个限界上下文，共用一个 PostgreSQL——逻辑上按 DDD 限界上下文划清，但物理上未拆服务、未各自拥有数据库。
  - `eventguard-ai`（Python）、`eventguard-ui`（Vue）才是独立部署服务，与 server 走 HTTP/Kafka 协作——这部分是微服务式拓扑。
  - 综上：整体更接近"**少数粗粒度服务 + 模块化单体核心**"，不是每业务域独立成服务的标准微服务。
- **为什么不直接拆微服务（演进路径）**：模块化单体是务实起点，限界上下文已划好，具备平滑演进为微服务的边界；等某上下文（如 `query` 投影、`anomaly` 消费）真正需要独立伸缩时，再抽成独立进程 + 独立库即可。这正是多数团队从单体到微服务的真实路径，也是本项目预留的演进方向。
- **面试答法**："整体是分布式系统；拓扑上 server/ai/ui 是独立服务、呈微服务式协作，但核心后端采用模块化单体，限界上下文已按 DDD 拆好，具备平滑演进为微服务的边界（把某上下文抽成独立进程+独立库即可）。所以准确说是'分布式 + 模块化单体核心'，而非一上来就全套微服务。"

---

## 二（续·代码级）、后端模块逐目录代码详解（函数 / 枚举级）

> 定位：对第二章分层讲法的逐文件下钻。面试被追问"某个类/函数做什么"时按此检索。同类代码（命令、事件、规则）合并讲解，避免逐文件罗列。

### 2.1 `command/` —— 写侧命令与聚合

> 这一层回答三个问题：订单在任意时刻"处于什么状态"（枚举）、"状态怎么变"（状态机）、"谁有权让它变"（命令 + 聚合根）。

**`aggregate/` —— 聚合根与状态机**

**`OrderStatus` 九个枚举值的含义**（每个值都对应一笔业务真实发生的节点，不是随意命名）：
- `PENDING_PAYMENT`（待支付）：订单刚创建、用户还没付款。是绝大多数流程的起点。此时订单"存在但钱没到"。
- `PAYMENT_FAILED`（支付失败）：支付尝试没成功（网关创建失败或回调失败）。注意——它**不是终态**，因为用户还可以重试，所以系统单独给它一个状态而不是直接取消。
- `PAID`（已支付）：钱已收到。这是后续所有履约动作（占库存、发货）的门槛——只有到这一步，商家才真正"吃下"这笔单。
- `CONFIRMED`（已确认）：商家已确认订单、库存已预留好、准备发货。它是"占库存"和"发货"之间的闸口，给商家一个人工/系统复核点。
- `SHIPPED`（已发货）：已出库、生成物流单号。此后商品在途。
- `DELIVERED`（已送达）：用户已签收，实物交付完成。
- `CLOSED`（已完成）：正常履约终结。终态，不可再变。
- `CANCELLED`（已取消）：被用户取消、或支付重试超限系统自动取消。终态。
- `REFUNDED`（已退款）：钱已退回用户。终态——即便货物状态可能还在途，财务上这笔单已"反向"结清。

**状态机为什么这样设计**（`OrderAggregate.java:13-18`）：
- **主链路** `null → PENDING_PAYMENT → PAID → CONFIRMED → SHIPPED → DELIVERED → CLOSED`：一条"钱到位 → 货备好 → 货发出 → 货签收 → 单完结"的自然商业流水线。每一步都是前一步成功后的必然推进，没有跨越（不能没付款就发货）。
- **支付异常支路** `PENDING_PAYMENT → PAYMENT_FAILED →`（重试 ≤3 次）`PENDING_PAYMENT`；超 3 次 → `CANCELLED`：把"失败"和"取消"分开，是为了**给用户重试机会**——支付失败常见（余额不足、网络抖），直接取消太粗暴；但放任无限重试又会卡死，所以用"3 次上限"做止损，超限才进终态 `CANCELLED`。
- **逆向支路** `PAID`/`CONFIRMED → REFUNDED`、`任意非终态 → CANCELLED`：对应真实售后。关键是 `CLOSE`/`CANCELLED` 设为终态——一旦完结或取消，任何后续命令都会被聚合根前置校验拒绝（`IllegalStateException`），杜绝"已关闭的订单又被发货"这类脏数据。
- **设计目的总结**：状态机把"业务允许哪些流转"集中在一处声明，任何非法跃迁在 `apply` 阶段就抛错，而不是靠散落各处的 if 兜错——这是聚合根存在的根本价值。

**`OrderAggregate` 关键函数（各自解决什么问题）**：
- `handle(XxxCommand)`：命令的唯一入口，职责是"**校验 + 决策**"。它先做前置状态校验（如"只有 `PAID` 才能占库存"），不合法直接拒绝；合法才 `raise` 事件。把规则收在这里，控制器层就永远不需要懂业务。
- `raise(event)`（**写路径**）：做两件事——把事件加入 `pendingEvents` 待持久化队列，**并**调用 `apply` 立即更新内存状态。语义是"刚发生一件新事，记下待存并生效"。目的是让"内存态"和"待落库事件"永远一致。
- `apply(DomainEvent)`（**状态逻辑**）：抽象方法，是"事件→状态变更"的**唯一实现处**，按事件类型 switch 改 `status`/`retryCount`/`totalAmount`，本身不碰 `pendingEvents`、只改内存状态。之所以只让它改状态，是为了保证"重放历史事件"和"实时处理命令"走同一套状态逻辑，不会出现两套口径。补偿/退款意图类事件（如 `CompensationExecutedEvent`）在此**不改状态、只留痕**，避免回放时干扰状态机。
- **`raise` 与 `apply` 的核心区别**：`raise` = 登记待存事件 + 生效（enqueue + `apply`）；`apply` = 纯粹的"事件如何改状态"逻辑。`apply` 被 `raise`（实时处理）和 `applyEvent`（重放，见基类）**共用**；重放只走 `applyEvent`→`apply`、不调用 `raise`，所以不会把历史事件再入队一份——保证回放只重建状态、不产生新事件（否则重放会变成"重新下单"）。
- `toStateMap()` / `fromStateMap()`：把聚合状态序列化成 Map（给快照用）和反序列化回来（从快照重建）。

**`AggregateRoot` 基类（为什么需要它）**：所有事件溯源聚合都要"托管待存事件 + 版本号"，这段逻辑与具体业务无关，抽成基类让 `OrderAggregate` 只关心订单规则。`applyEvent`（重放路径，不进 pending）与 `raise`（写路径，进 pending）区分，保证**重放不会产生新事件**——否则重放会变成"重新下单"，灾难。

**成员变量归属（避免混淆基类与子类）**：`AggregateRoot` 基类只持有三个与业务无关的通用字段，而 `status`/`retryCount`/`totalAmount` 属于**子类 `OrderAggregate`**——这是常被问混的点，明确如下：
- **基类 `AggregateRoot` 的字段**（事件溯源通用机制）：
  - `aggregateId`（`UUID`）：聚合标识，事件据此归并到同一订单。
  - `version`（`int`，初值 0）：已持久化版本号；新事件版本 = `version + 1`，是乐观锁与投影对齐的基准。
  - `pendingEvents`（`List<DomainEvent>`）：本次命令产生的、尚未落库的事件队列；`flushPendingEvents` 时整批交出并清空。
- **子类 `OrderAggregate` 的字段**（订单特有业务状态）：
  - `status`（`OrderStatus`）：订单当前所处的状态机节点，是全部状态跃迁的落点；`apply` 按事件类型改它。
  - `retryCount`（`int`）：支付失败重试计数；`handle(RetryPaymentCommand)` 时自增，≤3 才能回到 `PENDING_PAYMENT`，>3 转 `CANCELLED`（见 2.1 状态机）。
  - `totalAmount`（`BigDecimal`）：订单金额，由 `OrderCreatedEvent` 初始化；既是风控 R001 与用户历史金额基线（均值/标准差）比较的**当前值**，也作为**构建该基线的历史样本**（见 2.6 `RuleContextLoader`/`R001`）；同时供退款动作计算退款额。
- **这样划分的意义**：基类管"事件溯源的通用机制"，子类管"订单的领域状态"。新增第二个事件溯源聚合时，直接继承 `AggregateRoot`、只写自己的业务字段即可，基础设施零改动。

**`AggregateRepository`（连接聚合与事件库）**：`command/aggregate/AggregateRepository.java` 是聚合根与事件库之间的"装配工"，只做两件事——**加载**（快照 + 增量回放重建内存态）和**保存**（事件落库 + 触发快照）。它本身不含业务规则，只负责"聚合 ↔ 事件/快照存储"的衔接。每 100 个事件打一次快照（`SNAPSHOT_INTERVAL=100`，见顶注释）。目的：**用空间（快照）换时间（重放成本）**。快照阈值为硬编码常量、未考虑单事件大小与长尾低频订单，属可配置化优化点（ceiling，详见第八章 8.3）。

> **一句话总结（面试可先背这段，再按需要展开）**：`AggregateRepository` 对调用方屏蔽了"订单状态其实是算出来的"这一事实，对外只暴露"按 id 取聚合 / 存聚合"。
> - **加载的作用与做法**：作用是在命令处理前把某个订单的"当前内存态"重建出来。做法 = 先取最近快照跳到已知版本（无快照则从 0 起）+ 用 `loadFrom` 只重放"快照版本之后"的增量事件，快照与增量合并即得完整聚合。
> - **保存的作用与做法**：作用是把本次命令产生的事件变成永久事实、并维护快照。做法 = `flushPendingEvents` 取出待存事件 → 带乐观锁基准 `append` 落 `domain_events` → 若版本正好跨过整百节点则 `toStateMap` 存档快照。落库前后全程以 `version` 为唯一基准，保证"落库即对齐、重建即还原"。

- **加载 `load(orderId)`**（重建内存聚合，供命令处理前使用）：
  - 先 `snapshotStore.load(orderId)` 取最近快照；**命中**则 `OrderAggregate.fromStateMap(snap.getState())` 反序列化出聚合对象，并把 `fromVersion = snap.getVersion()`（快照已覆盖到的版本）；**未命中**则 `new OrderAggregate()` 空聚合、`fromVersion = 0`（从头重放）。
  - 再 `eventStore.loadFrom(orderId, fromVersion)` 取"快照之后"的增量事件，`events.forEach(agg::applyEvent)` 逐事件重放推进状态。
  - **`fromVersion` 取快照版本的边界含义**：`loadFrom` 用的是严格 `event_version > fromVersion` 条件（不含等于），所以传 `snap.getVersion()` 而非 `+1`——快照已包含该版本，下一条要重放的恰是 `version+1`，传 `+1` 会把首条增量事件漏掉（代码注释 line 41-44 专门点明这个坑）。这就是"快照续放"不丢不重的对齐点。
  - **含义/目的**：一个跑了上万事件的订单，不必每次从 `domain_events` 第 1 条重算到尾——从最近一次整百快照跳到当前态，只重放几十到上百条增量，回放成本被压平。
- **保存 `save(aggregate)`**（把内存中这次命令产生的事件落库，并择机打快照）：
  - `flushPendingEvents()`（`AggregateRoot` 基类方法，见 2.1）整批取出本次待存事件；**空则直接 `return`**——命令没产出事件（如校验失败未 `raise`）就不碰数据库，避免空写。
  - `expectedVersion = aggregate.getVersion() - newEvents.size()`：`flush` 后 `getVersion()` 已是"这批最后事件的版本"，减去批大小即"这批之前的最高版本"，作为 `EventStore.append` 乐观锁基准（与 `EventStoreJdbcImpl` 的 `expectedVersion` 校验一一对应，见 `event/` 节）。
  - `eventStore.append(aggregateId, newEvents, expectedVersion)`：**真正写 `domain_events`**（含聚合级事务锁 + 版本校验），这一步落库后事件才成为"事实"。
  - **择机打快照**：`getVersion() > 0 && getVersion() % SNAPSHOT_INTERVAL == 0` 时，`snapshotStore.save(new Snapshot(id, "Order", version, toStateMap(), now))`——把当前整状态 `toStateMap()` 存档到 `SNAPSHOT_INTERVAL` 节点（如第 100、200 个事件后）。快照类型字面写死 `"Order"`，与 8.2 提到的 `aggregate_type` 硬编码同源，是单聚合 scope 下的简化。
  - **含义/目的**：快照让后续 `load` 走"快照 + 增量"而非"全量"，把长事件链重放成本从 O(N) 降到 O(间隔)；`save` 与 `load` 共用同一套版本基准，保证"落库即对齐、重建即还原"。
- **为什么它属于 DDD 的"仓储"而非普通 DAO**：仓储对外只暴露"按 id 取聚合 / 存聚合"的语义，内部用事件 + 快照的组合实现；调用方（`OrderCommandHandler`）完全感知不到"状态其实是算出来的"，只当在存取一个普通对象——这正是事件溯源仓储与普通 JPA 仓储在接口层的一致、实现层分流之处。

**`command/` —— 命令对象（12 个，合并讲解）**

> 命令是"**用户/外部想让订单做什么**"的意图表达，和"事件"（已发生的事实）要严格区分。每个命令都是独立类，结构一致 `XxxCommand(aggregateId, commandId, 字段)`，按状态机阶段归类，每类的**目的**是：

- **建单**：`CreateOrderCommand(userId, totalAmount)` —— 用户提交下单，目的是生成一笔待支付订单，并带上 `userId` 供后续风控按用户聚合。
- **支付（异步意图）**：
  - `PayOrderCommand` —— 用户点付款。它**只表达"发起支付"意图**，状态不变（仍 `PENDING_PAYMENT`）。目的是把"请求支付"和"支付结果"解耦：真实结果要等网关回调，不能在点按钮的瞬间就改状态，否则回调失败会和本地状态打架。
  - `CompletePaymentCommand` —— 网关回调"支付成功"时进入，目的是把订单推到 `PAID`。
  - `FailPaymentCommand` —— 网关回调"支付失败"，推到 `PAYMENT_FAILED`。
  - `RetryPaymentCommand` —— 用户在 `PAYMENT_FAILED` 重试，`retryCount++`；超 3 次转 `CANCELLED`。目的是给失败一次复活机会并设上限。
- **库存**：`ReserveInventoryCommand` —— 订单 `PAID` 后占库存，成功 `raise InventoryReservedEvent`（状态不变，仅记录），失败走 `handleInventoryReservationFailed`。目的：**先确认收到钱，再占库存**，避免"库存占了钱没到"的资金占用风险。
  - **一句话对比（面试可用）**：`InventoryReservedEvent` = "占库存成功"的事实留痕，状态仍 PAID，等 `ConfirmOrderCommand` 推进；`InventoryReservationFailedEvent` = "占库存失败"被翻译成领域事件而非异常，状态仍 PAID，目的是让钱货不一致的中间态可被风控（R005）与 Saga 补偿感知并兜底。两条都**不改状态**，是事件溯源里典型的"状态保持型事件"，把"外部结果"与"状态机推进"解耦。
- **推进（正向履约）**：`ConfirmOrderCommand`(→CONFIRMED，商家确认)、`ShipOrderCommand`(→SHIPPED，发货)、`DeliverOrderCommand`(→DELIVERED，签收)、`CloseOrderCommand`(→CLOSED，完结)。每个都是把订单往前推一格，语义清晰、可独立审计。
- **逆向（售后）**：`CancelOrderCommand`（非终态→CANCELLED）、`RefundOrderCommand`（`PAID`/`CONFIRMED`→REFUNDED）。对应真实取消/退款场景。

**`handler/` —— 命令处理器（命令如何真正被执行）**

`handler/` 是应用层胶水：控制器只收请求、聚合根只管领域逻辑，**"命令分发 + 事务 + 幂等 + 日志"全部收敛到这里**。一句话定位："把一次用户意图安全地变成一条已落库的事件链"。

- **统一模板 `execute`**：12 个订单命令各自只有一个很薄的 `handle` 方法（`command/command/` 包下除 `Command` 接口外的全部命令类），统一调用 `execute(cmd, action)`，真正的处理逻辑都在这一个模板里。模板要解决两个工程问题：如何保证幂等，以及如何保证原子性。（另有一个 `CompensationCommand` 在 `compensation/model/` 包、由独立的 `CompensationCommandHandler` 处理，不计入这 12 个订单命令；全系统命令类型共 13 种。）
  - **幂等（不重复处理）**：模板用三道防线保证同一条命令不会被处理两次。第一道，在数据库事务内按 `commandId` 加行锁，让并发到达的相同命令被串行化；第二道，若 `command_log` 中已存在该命令，直接返回当初记录的结果，网络重发不会再走一遍业务；第三道，`fingerprint` 与 `assertCompatible` 校验"同一命令 id 的请求内容是否一致"，防止有人复用旧 `commandId` 偷换参数。简言之，`fingerprint` 是整条命令的 SHA-256 内容摘要，`assertCompatible` 在命中日志时比对聚合 id、命令类型与内容指纹，三者全一致才当作重复请求直接返回，否则拒绝——这把"按 id 去重"升级成了"按 id + 内容去重"，挡住了复用旧 id 篡改参数的语义攻击。
  - **原子（不丢标记）**：加载聚合、执行 `handle` 产出事件、`save` 落库、写命令日志这四步都在同一个数据库事务内完成。这样"记日志"和"落事件"要么同时成功、要么同时失败，不会出现"事件已经落库却没有记日志"的情况，也就不会导致重试时把同一条命令又处理一遍。
  - **成功与失败分离**：`CommandResult` 把"命令本身处理成功"和"业务结果失败"（例如库存不足）区分开。业务失败时返回结果仍然是 `success=true`，但带上失败原因，因为事件已经落库、命令已经正确完成，只是业务上没达成；这样既不破坏幂等、又能把原因告诉调用方。
- **`CommandRetryTemplate`（乐观锁重试）**：它包在事务的**外层**（模板里是 `retryTemplate.executeWithRetry(() -> transactionTemplate.execute(...))`），只捕获 `OptimisticConcurrencyException`（可预期、可恢复的并发冲突），最多重试 3 次（共 4 次尝试），退避为线性 `10ms × (attempt+1)`。要讲清它，先得说清楚 `EventStore.append`：
  - **`EventStore.append` 的时机与作用**：它发生在一次命令事务的"落库"阶段——`aggregateRepository.load` 重建聚合、`action.accept(order)` 让聚合 `handle` 产出事件之后，`save` 内部 `flushPendingEvents` 取出这批事件再调用 `append`，把内存中本次命令产生的领域事件写进真相源 `domain_events` 表（事件落库才成为不可变事实）。`append` 内部三件事：① 聚合级事务锁 `pg_advisory_xact_lock` 串行化同一订单的并发写；② 主动校验 `expectedVersion`（查 `MAX(event_version)`，不符抛乐观锁异常）；③ 逐条 INSERT，`UNIQUE(aggregate_id, event_version)` 留作数据库层最终底线。
  - **"撞 UNIQUE 约束"指什么方案**：`domain_events` 有唯一约束 `UNIQUE(aggregate_id, event_version)`，同一订单不能有两个相同版本的事件。朴素方案是"不设版本校验、直接 INSERT，靠唯一索引在插入时拦重"——两个并发写都以为能写 version=N，第二个触发 `DuplicateKeyException`；但失败发生在插入之后，且只拿到一个异常、**不知道当前最新版本是多少**，重试大概率再撞或产生脏数据，所以"能防损坏、不可重试"。本项目主路径用乐观锁把冲突**提前到插入前**并带上版本信息，因而可重试；UNIQUE 只是锁之外路径（无事务调用、人工直写）的兜底。
  - **为什么重试是安全的（可重入）**：`transactionTemplate.execute` 在 `action.get()` 内部，每次重试都会重新进入一个新事务，于是重新走 `load`（拿最新聚合态）→ `handle`（基于最新态重算）→ `append`。所以重试不是盲目重放旧事件，而是基于冲突后的最新状态重算，不会把陈旧版本写进去——这正是它与"撞 UNIQUE 不可重试"的根本区别。超出 3 次则抛出最后一个乐观锁异常，交上层转错误响应。
- **`ReserveInventoryCommand`（库存命令的特殊编排）**：这个命令在幂等边界内先调用 `InventoryGateway`，网关的幂等键就是 `commandId`。调用成功后 `raise InventoryReservedEvent`，库存不足时 `raise InventoryReservationFailedEvent`。因为库存调用也在幂等边界内，它的副作用同样按 `commandId` 去重，避免了网络重发时重复扣减库存。
- **`CompensationCommandHandler`（补偿命令处理器）**：专门处理 `CompensationCommand`，只留下补偿完成的记录、不改变订单状态，以此强调补偿是一条旁路记录，与正向命令分开。
- **`CommandLog` / `CommandLogRepository`**：命令日志表是命令级幂等的物理载体；`CommandRetryTemplate` 是上面提到的重试策略。

### 2.2 `event/` —— 事件溯源核心

> 这一层是"真相源"：系统不存订单当前状态，只存"发生过什么"。读懂这层就懂了为什么本项目能审计、能重建。

- **`model/DomainEvent`（事件长什么样）**：每个事件是不可变的事实记录，基类字段 `eventId/aggregateId/eventType/version/occurredAt/payload/metadata`。`payload` 装业务数据（如金额），`metadata` 装穿越上下文（如 `userId`）——两者分开，加字段不破坏表结构。`SimpleEvent` 提供 `getBigDecimal` 等便捷读 payload 的方法，规则引擎据此取数。
- **15 个具体事件类的含义（每个 = 状态机里一个已发生的节点；不含抽象基类 `DomainEvent`，`event/model/` 下共 16 个 `.java` 文件、含基类）**：
  - 主链路（每件事都把订单往前推一格）：`OrderCreatedEvent`(v1，订单诞生→PENDING)、`PaymentRequestedEvent`(仅记录"已发起支付"意图，状态不变——对应前面说的异步支付)、`PaymentCompletedEvent`(钱到→PAID)、`InventoryReservedEvent`(库存已占，状态不变，仅留记录)、`OrderConfirmedEvent`(→CONFIRMED)、`ShippedEvent`(→SHIPPED)、`DeliveredEvent`(→DELIVERED)、`OrderClosedEvent`(→CLOSED)。
  - 异常/逆向：`PaymentFailedEvent`(→PAYMENT_FAILED)、`PaymentRetriedEvent`(重试→PENDING)、`InventoryReservationFailedEvent`(占库存失败，状态仍 PAID，留记录供补偿)、`OrderCancelledEvent`(→CANCELLED)、`OrderRefundedEvent`(→REFUNDED)、`OrderRefundRequestedEvent`(退款意图，状态不变)、`CompensationExecutedEvent`(补偿完成，只留痕不改状态)。
  - **为什么事件"有的一改状态、有的不改"**：改状态的是"订单本身的生命周期事件"；不改的是"旁路记录"（支付意图、库存结果、补偿），它们要进事件流供审计/风控消费，但不参与状态机推进——否则回放会把意图当成事实。
- **`store/EventStoreJdbcImpl`（事件怎么存）**：`append` 写入 `domain_events`，写入前用聚合级 `pg_advisory_xact_lock` + `expectedVersion` 校验，保证并发写不会静默覆盖；`load`/`loadFrom` 按版本回放（断点续放供快照用）；`EventDeserializer` 按 `eventType` 把 jsonb 反序列化成具体事件类。**它是整个系统唯一的事实来源**，读模型、风控都从它的下游派生。
- **`snapshot/`**：`Snapshot`/`SnapshotStore` 每 100 个事件存一份聚合状态快照。目的见 2.1——**用空间换时间**，避免长事件链每次从头重放。

### 2.3 `query/` —— 读侧（为什么需要它）

> 事件库只适合"重放"，不适合"按用户/状态列表查询"。这层把事件翻译成好查的扁平视图，是 CQRS 的"读"一半。

- **`projection/OrderViewProjection`（事件→视图的搬运工）**：`@KafkaListener` 消费 `domain-events`，把每个事件 `UPDATE order_view` 一行。三个细节各有目的：`tryMarkProcessed` 用 `idempotent_consumers` 表去重（Kafka 会重复投递，不幂等就会重复更新）；`applyNext` 用 `WHERE version = ?-1` 防乱序（消息颠倒到达时绝不拿高版本覆盖低版本，而是等缺口补上）；`notifyProgressAfterCommit` 在事务提交后才唤醒"等结果的查询"，避免查到未提交数据。
- **`service/OrderQueryService`（读己写）**：用户刚下单立刻查，系统不能让他看到下单前的空状态。它用 `readAfterWriteAsync`——投影完成通知即时唤醒 + 单线程兜底轮询，等 `order_view` 版本追上命令返回的 `expectedVersion` 才返回，超时抛 `ProjectionLagException`（宁可报错也不返回旧值）。`getEvents(orderId, upToVersion)` 支持"时间旅行"：按版本截断重演历史事件，回答"某个时点订单长啥样"。
- **`repository/OrderViewRepository`**：读模型的查询/分页/事件查询；**`model/`** `OrderView`/`EventDto`/`OrderListResponse`；**`controller/`** 查询 REST。

### 2.4 `gateway/` —— 端口与适配器（为什么这么分层）

> 支付、库存、通知是外部系统，协议各异、还会失败/抖动。这层的目的：把"外部的不确定性"挡在领域核心之外。

- **端口接口** `PaymentGateway`/`InventoryGateway`/`NotificationGateway`：领域只声明"我要付钱/占库存/发通知"这种意图，不关心背后是谁。目的是**依赖方向 inward**——核心不依赖具体外部实现。
- **适配器** `Mock*` 三件套（测试时确定性返回，不连真实外部）、`AlipaySandboxPaymentGateway`（支付宝）、`HttpInventoryGateway`/`WeComNotificationGateway`（真实 HTTP）。切换渠道只换适配器，领域代码零改动。
- **`service/PaymentCoordinator`（发起支付）**：`initiate` 先幂等建 `gateway_request(PENDING)` 记录"我向网关发了这笔支付"，再调 `PaymentGateway.createPayment`。mock 模式延时回调、真实模式由外部 HTTP 进 `GatewayCallbackController`。目的：把"发起"和"结果"拆开，请求有迹可查。
- **`service/GatewayCallbackService`（回收结果）**：网关回调按 `external_ref` 反查 `gateway_request`，已终态直接幂等返回（防回调重放重复处理）；否则用**确定性 `commandId`**（`callbackId|success` 算 UUID）派发 `CompletePaymentCommand`/`FailPaymentCommand` 进聚合。目的：外部回调最终都变成"领域命令"，外部世界的乱序/重放不影响聚合根纯净性。
- **`model/GatewayRequest`**（支付请求轨迹）、**`repository/GatewayRequestRepository`**、**`config/GatewayProperties`**（渠道/延时配置）。

### 2.5 `compensation/` —— Saga 补偿（为什么需要它）

> 一笔订单要跨"扣钱→占库存→通知"多个外部系统，任何一步失败，前面已做的必须撤销。两阶段提交（2PC）会长期锁资源、可用性差，所以用 Saga：正向步骤 + 反向补偿，最终一致。

- **`saga/CompensationSaga`（编排器）**：内存 `ConcurrentHashMap` 维护进行中的 saga 实例。每步执行，遇到 `requiresApproval` 的高风险动作（如大额退款）就**挂起**并落 `ApprovalRepository` 审批单，等人审批；`onApproved` 通过后继续后续步骤。`recoverPending`（配 `SagaRecoveryRunner`）解决"审批单在、实例丢"：重启后从审批单里的 `__saga_remaining_steps` 重建内存实例续跑。状态机 `SagaStatus`（STARTED/EXECUTING/AWAITING_APPROVAL/COMPLETED/FAILED）记录进展。
- **`action/`**：`CompensationAction` 是补偿动作接口，`CompensationActionRegistry` 按 `actionType` 路由具体实现：`RefundAction`(退款，金额>100 需审批，经 `PaymentGateway` 真退款)、`FreezeOrderAction`(冻结)、`MarkOutOfStockAction`(标缺货)、`NotifyDelayAction`(通知延迟)、`BackoffAndStopAction`(退避停止)。每类动作声明自己 `requiresApproval` 与否（风险分级）。
- **`service/CompensationService`（执行闸门）**：先做白名单校验（不在白名单的动作直接拒，防越权），再执行动作——**只有外部动作真正成功，才写 `CompensationCommand` 完成事件**。目的：事件流的语义必须和真实世界一致，不能"动作失败却记了成功事件"。
- **`model/`** `CompensationCommand`/`Request`/`Result`、`SagaRequest`；**`repository/ApprovalRepository`**（审批单）；**`controller/`** `CompensationController`/`ApprovalController`。

### 2.6 `anomaly/` —— 规则与实时推送（为什么单列一层）

> 风控要判断"这笔订单正不正常"，它必须读全量事件历史、按用户聚合，还要实时推给运营。把它独立出来，是不让风控逻辑污染订单核心。

- **`rule/`**：5 条规则，每条 `matches(event, ctx)` 返回"命中/不命中"，并带 `ruleId`/`level` 元信息。各自目的：
  - `R001AmountDeviationRule`：**金额偏离用户历史基线**，抓异常大额/盗刷。基线 = 该用户（`metadata.userId`）近 90 天 `OrderCreatedEvent` 的 `totalAmount` 统计分布——`userMean`(均值) 与 `userStd`(总体标准差)，由 `RuleContextLoader.loadUserAmountStats` 用 SQL `avg`/`stddev_pop` 算出，并**排除当前事件自身**（否则离群值自污染基线，导致规则永不命中，即之前修过的 bug）。判定：`deviation = |amount - userMean|`，若 `deviation > N × userStd`（N 默认 3，可由 `eg.anomaly.r001.sigma` 调成 2.5）则命中，级别 `WARN`。`totalAmount` 在此既是被比较的当前值、也是构建基线的历史样本（见 2.1）。
  - `R002DuplicatePaymentRule`：短时间内重复支付，抓重复扣款。
  - `R003StateJumpRule`：非法状态跳跃（如凭空 PAID），抓状态被篡改/回放错乱。
  - `R004HighFrequencyRule`：同用户 1 分钟 >20 单，抓刷单/机器下单。
  - `R005InventoryOverflowRule`：占库存超真实库存，抓超卖。
- **`engine/`**：`RuleEngine.evaluate` 遍历规则 `findFirst` 命中即生成 `Anomaly`；`RuleContextLoader` 负责**把规则需要的历史数据捞出来**——R001 取用户 90 天金额均值/标准差并**排除当前事件自身**（否则离群值会污染自己的基线，导致规则永不命中，这正是之前修过的 bug）；R003 取 `version < 当前` 的前置状态（避免把合法迁移误判跳跃）；R004 取近期下单；R005 经 `InventoryGateway.currentStock` 取真实库存。
- **`consumer/AnomalyAlertConsumer`（推给前端）**：`@KafkaListener` 消费 `anomaly-alerts`，**先落库** `AnomalyAlertHistoryRepository` **再广播** `AnomalyWebSocketHandler`——落库失败走 Kafka 重试/DLT，断线用户可 `/alerts/recent` 补拉，保证告警不丢。
- **`model/`** `Anomaly`/`AnomalyAlert`/`AnomalyLevel`/`SimpleEvent`；**`controller/`** `RuleEngineController`/`AlertHistoryController`；**`history/`** 告警历史仓储。

### 2.7 `auth/` 与 `common/`（为什么放最外围）

**`auth/` —— 身份与权限（信任边界）**
- **`service/AuthService`（认证入口）**：`login` 串联三件事——`LoginAttemptGuard` 防爆破（失败过多锁账号）、`AuditLogger` 审计（谁何时登了）、`JwtService.issue` 发令牌（BCrypt 校验密码）。`logoutAll` 和 `changePassword` 都**递增 `token_version`**——这是令牌吊销的关键：已签发的旧 JWT 因版本不匹配立即失效，实现"踢下线/改密即失效"而不需维护黑名单。`me` 重新从库加载，反映最新角色权限。
- **`security/JwtService`**：HS256 签发/校验，claims 带 `uid/roles/permissions/mcp(强制改密)/tv(令牌版本)`。微服务间（含 AI 侧 PyJWT）共用同一 secret，免再认证。
- **`security/LoginAttemptGuard`**：同用户连续失败 5 次锁 5 分钟（内存计数，单实例生效——多实例需换 Redis，已在 ponytail 标注）。
- **`security/PermissionInterceptor`** + `@RequirePermission`：在方法/类上声明所需权限，请求进来时拦截校验，无权限回 403。`AuthFilter` 做基础鉴权，`AuditLogger` 记审计，`JwtHandshakeInterceptor` 让 WebSocket 握手也走同一套 JWT 校验。
- **`model/`** `AppUser`/`Role`/`Permission`/`UserLlmConfig`；**`repository/`** `UserRepository` 等；**`controller/`** 认证/用户/角色/LLM 配置 REST。

**`common/` —— 横切基础设施（刻意不写业务）**
- **`controller/DltReplayController`（毒消息自救）**：`POST /admin/dlt/{topic}/replay` 把 `<topic>.DLT`（消费失败死信）增量重投回主 topic，超过 `maxReplayAttempts`(默认 3) 的毒消息隔离到 `.quarantine`，避免永久循环占用带宽；需 `user:manage` 权限。
- **`scheduler/DltReplayScheduler`**：cron 每 10 分钟调用上面的重放，自动自愈临时故障导致的死信。
- **`metrics/EventGuardMetrics`**：监控指标，可选注入（null 时降级空操作，测试/未启用 actuator 不报错）。
- **`idempotent/`**、**`websocket/AnomalyWebSocketHandler`**、**`scheduler/`**、**`exception/`**、安全基础组件：所有模块共用的纯技术能力下沉到这里。**刻意不承载订单业务规则**——这样任意业务模块可依赖 `common`，而 `common` 不反向依赖任何业务，依赖图保持无环。

---

## 三、依赖方向（收尾一句话）

> "依赖单向：`HTTP / 应用层 → 领域 → 基础设施`。`query` 永不反向调 `command` 改写订单，投影只消费**已提交**事件——这样写读解耦，读模型挂了不影响写。"

### 面试实战问答（模拟演练）

> 下面是一场"自我介绍后架构追问"的完整模拟。格式：`面试官` 提问 → `你`（简洁答）→ `💡 要点`（临场注意）。整套回答控制在 3–4 分钟，先抛主干、被追再展开。

**面试官**：先概括一下你这个项目后端是怎么设计的。

`你`：EventGuard 后端是事件溯源 + CQRS 架构。写侧用聚合根产出领域事件落事件库，读侧通过 Kafka 把事件投影成只读视图，两边靠版本号保证最终一致。其余鉴权、网关、Saga、风控都建立在这条事件链路上。

`💡 要点`：一句话定调，把话题锁在核心架构，别先铺模块清单。

**面试官**：为什么不用普通 CRUD 直接存订单状态？

`你`：订单是强审计资产，事件溯源把每次状态变化当一等公民存下来，当前状态只是事件的衍生结果。好处是可审计、可回放重建、能时态查询；代价是写路径多一层事件落地，我们用 CQRS 把读写拆开，写复杂但读仍简单。

`💡 要点`：先讲收益再讲代价，体现权衡意识（对应 4.1 Q1）。

**面试官**：读模型 `order_view` 和事件库怎么保持一致？延迟了怎么办？

`你`：事件落库后经 Debezium 进 Kafka，投影器消费后更新 `order_view`，版本号对齐、靠 `idempotent_consumers` 表幂等。它是最终一致——写不受读影响，用户侧用"读己写"等投影追上再返回，抹平自身操作的可见延迟。投影挂了不影响下单。

`💡 要点`：主动点出"最终一致 + 不影响写"，这正是面试官想听的边界意识。

**面试官**：并发改同一订单怎么防乱？

`你`：事件库 `append` 带 `expectedVersion`，先校验当前最大版本，不符抛乐观锁冲突可重试；聚合级事务锁让"校验—插入"原子化。读侧投影还有 `WHERE version = ?-1` 防乱序更新。

`💡 要点`：两层防护（写侧乐观锁 + 读侧版本缺口）一起说，显深度（对应 4.1 Q4）。

**面试官**：这整套算分布式吗？上线要好多机器？

`你`：架构上是分布式——server、AI、UI 加 PG/Kafka/Debezium 是独立服务，靠 HTTP/Kafka/CDC 协作，有最终一致、Saga 补偿、幂等等典型设计。是否跨机器只是部署选择，单机多容器同样算分布式，因为组件是独立进程、独立故障域。

`💡 要点`：纠正"分布式=不同机器"的误区（对应第二章 4. 节）。

**面试官**：做这套你踩过什么坑，或哪里还能优化？

`你`：两个我主动标的点——一是事件 metadata 的 `userId` 没透传，导致按用户维度的"高频下单"风控规则永不触发，这是数据链路断点，已修；二是读模型重建目前挂在 Kafka 重消费上，缺一键从事件库重建的入口，事件库本就是真相源，下一步把重建源切到事件库本身。

`💡 要点`：用"真实 bug + 已知 ceiling"收尾，比空谈亮点更显成熟（对应第六章 anomaly、第八章 8.1/8.2）。

**面试官**：那你这个聚合根设计上有什么讲究？

`你`：`AggregateRoot` 抽象基类托管"待持久化事件"和版本号，`raise` 写路径、`applyEvent` 重放路径，状态变更只能经事件。目前全系统只有 `OrderAggregate` 一个事件溯源聚合，其他域走 CRUD，是刻意的单聚合收敛。

`💡 要点`：精准说"单聚合"范围，别让人误以为全系统都事件溯源（对应第二章 `command/` 设计要点、8.2）。

---

## 四、高频追问备战

| 追问 | 回答锚点 |
|---|---|
| 事件库和订单表为什么要分两套？ | 写模型（事件）vs 读模型（视图），CQRS 读写负载 / 模型解耦 |
| 投影挂了 / 消息丢了怎么办？ | `idempotent_consumers` 幂等 + Debezium CDC 从 WAL 重放，事件库是真相源可重建 `order_view`（`reset()` `OrderViewProjection.java:178`） |
| 并发创建同一订单？ | `expectedVersion=0` 校验 + UNIQUE 兜底（`EventStoreJdbcImpl.java:49,69`） |
| 状态机和数据库状态不一致？ | 单真相源是事件版本，DB 状态由事件投影推导，不存在"双写" |

### 4.1 事件溯源专题问答（示例）

> 事件溯源是本项目的架构中心，下面给几组"追问—答法"示例，答法分两层：**一句话定位**（先稳住）+ **可展开点**（面试官追时才说）。

**Q1：为什么用事件溯源，而不是直接更新订单行？**
- **定位**：订单是强审计、强可追溯的核心资产，事件溯源把"每一次状态变化"当作一等公民存下来，当前状态只是事件的衍生结果。
- **展开**：收益有三——① 完整审计链（任意时刻发生了什么可追溯，金融/合规刚需）；② 可回放/可重建（读模型挂了用事件重放即可恢复，见 8.1）；③ 时态查询（"昨天 10 点这单是什么状态"只需重放到那时点）。代价是写路径多一层事件落地、存储膨胀、查询需借助读模型——本项目用 CQRS 把读写拆开，写复杂但读仍简单。

**Q2：事件结构要改（加字段 / 改含义）怎么办，旧事件不就坏了？**
- **定位**：事件一经写入不可变，schema 演进靠"向后兼容 + 版本化"，绝不改历史事件。
- **展开**：`payload`/`metadata` 用 jsonb 存储，旧事件缺新字段时反序列化给默认值；语义变化不覆盖旧事件，而是新增事件类型（如 `PaymentCompletedV2`）或在事件内带 schema 版本。重放逻辑对未知/旧版事件做兼容分支，绝不抛"未知事件"中断回放。

**Q3：怎么查"某个订单的历史状态"或做审计？**
- **定位**：`AggregateRepository.load(aggregateId)` 按版本顺序重放该聚合的全部事件即得任意时点状态；审计即读 `domain_events` 表。
- **展开**：长事件链重放成本高，故配**快照**（`event/snapshot` 包）+ `loadFrom(fromVersion)` 断点续放——只从最近快照往后重放增量，避免每次从头算（见名词表"快照"）。

**Q4：事件顺序和并发写入怎么保证不乱？**
- **定位**：事件版本单调递增 + 乐观锁，乱序/冲突在写入处就被拦下，而非靠读侧猜。
- **展开**：`append` 带 `expectedVersion`，事件库先校验当前最大版本（`EventStoreJdbcImpl.java:45-52`），不符抛乐观锁冲突可重试；聚合级事务锁让"校验—插入"原子化，避免撞 UNIQUE 走不可重试路径。读侧投影还有 `WHERE version = ?-1` 的二次防错（6.3 节）。

**Q5：读模型（order_view）滞后或挂了，业务受影响吗？**
- **定位**：不影响写，读模型是派生数据、可重建；用户侧用"读己写"抹平自身操作的可见延迟。
- **展开**：写链路只依赖事件库，投影故障不阻塞下单；查询走读模型，短暂延迟后必然对齐（最终一致性）。重建机制当前是 ceiling（8.1）：清表 + Kafka 重消费，缺一键从事件库重建的入口。

**Q6：为什么只有订单用事件溯源，其他表还是 CRUD？**
- **定位**：事件溯源是"按域按需采用"，不是全局强制；订单是唯一需要强审计+跨服务编排的核心聚合。
- **展开**：单聚合让一致性边界极简、无需跨聚合事务；其余域（用户、审批、告警历史）走 JPA/CRUD，简单直接。基础设施（聚合基类、事件库、仓储）聚合无关，要扩第二个事件溯源域只需补事件与投影（8.2）。

---

## 五、关联阅读

- 顶层模块重要性分级：见 `../架构设计/代码结构.md`
- 跨服务链路与阅读顺序：同上文档
- Saga / 补偿细节：见 `compensation/` 包源码

---

## 六、面向非本领域听众的讲解（以一条订单的完整生命周期为例）

> 目标听众：未接触过事件溯源 / CQRS、也不了解本项目的面试官或转岗同学。
> 讲法策略：**先给两个核心定义（不展开类比），再用一条具体订单贯穿各层。**

### 6.1 两个核心定义

- **事件溯源（Event Sourcing）**：业务对象的当前状态不被直接持久化，系统只持久化导致状态变化的"事件"序列；当前状态 = 该序列从版本 0 开始依次重放的结果。
- **CQRS（Command Query Responsibility Segregation）**：将"改变状态的命令处理（写）"与"查询状态的读模型（读）"分离为两套独立模型，各自独立优化与伸缩。

下面用一条订单 `order-1001`（用户 `user-42`，金额 299 元，商品 `phone-case-01` × 1）走完从下单到送达的完整链路，逐层说明各模块职责。

### 6.2 写侧：`command` + `event`（事实的产生与存储）

写侧是系统唯一的事实来源，流程如下：

1. **`CreateOrderCommand`** 进入聚合根 `OrderAggregate`。聚合根先校验订单此前不存在（初始状态为 `null`），然后产生 `OrderCreatedEvent`（版本 1，状态置为 `PENDING_PAYMENT`，事件的 metadata 中带上 `userId=user-42`，供后续风控按用户聚合历史）。
2. **`PayOrderCommand`** 触发时，聚合根校验当前状态确为 `PENDING_PAYMENT`，但**支付改为异步意图**：仅产生 `PaymentRequestedEvent`，订单状态保持不变，真实结果留待外部回调。
3. 支付网关回调后，以 **`CompletePaymentCommand`** 进入聚合根，产生 `PaymentCompletedEvent`（版本 2，状态 `PAID`）。
4. 后续 `ReserveInventoryCommand` → `InventoryReservedEvent`（仍 `PAID`）、`ConfirmOrderCommand` → `OrderConfirmedEvent`（`CONFIRMED`）、`ShipOrderCommand` → `ShippedEvent`（`SHIPPED`）、`DeliverOrderCommand` → `DeliveredEvent`（`DELIVERED`）依次推进状态机。

这串事件由 `event/` 层的事件库按 `(aggregateId, eventVersion)` 顺序 `append` 落库。**事件库是唯一真相源**：任何时刻调用 `load(order-1001)` 重放这条事件序列，都能精确还原订单当前状态（含状态机每个节点）。

**并发正确性**靠乐观锁保证：每次 `append` 携带 `expectedVersion`，事件库先校验当前最大版本号与之相等，不符则抛乐观锁冲突异常（可重试），而非静默覆盖。聚合级事务锁进一步让"校验—插入"成为原子区间，避免并发写走不可重试的唯一约束异常路径。

**`command` 与 `event` 的设计要点：**

- **聚合根是唯一业务规则载体**：所有"什么状态下允许什么操作"的前置校验集中在 `OrderAggregate` 的 `handle` 方法中（如"只有 `PAID` 才能占库存"），控制器层只负责收发命令，不再散落业务判断，避免规则在多处重复导致不一致。
- **命令与事件语义分离**：命令是"意图"（可能失败、可拒绝），事件是"既成事实"（不可变、只追加）。聚合根校验通过后才 `raise` 事件，事件一旦落库即不可更改——这保证了审计与重放的可信度。
- **状态机显式化**：生命周期（`PENDING_PAYMENT → PAID → CONFIRMED → SHIPPED → DELIVERED → CLOSED`）与异常支路（支付失败重试、超次取消、退款）集中声明，任何非法跃迁在 `apply` 阶段即抛异常，而非靠散落 if 兜错。
- **事件库 schema 设计**：每行事件拆为 `payload`（业务字段，jsonb）与 `metadata`（穿越上下文，如 `userId`，jsonb）两列，业务演进加字段不破坏结构；`UNIQUE(aggregate_id, event_version)` 作为数据库层最终底线，防止应用层锁之外的直写路径产生重复版本。
- **重放与断点**：`loadFrom(aggregateId, fromVersion)` 支持从指定版本续放，配合快照（snapshot 包）可避免对超长事件链从头重算，是"事件溯源性能"问题的标准解法。
- **支付异步化动机**：若同步等待支付结果再落库，外部超时/重试会与本地状态产生双写歧义；改为"意图事件 + 回调命令"后，外部结果永远以命令形式进入聚合，状态变更仍由聚合根唯一决策。

### 6.3 读侧：`query`（事件的投影与查询）

事件落库后，通过 Debezium 将数据库日志变更捕获进 Kafka 的 `domain-events` topic；`query/` 层的投影器 `OrderViewProjection` 作为消费者，将事件转化为只读的 `order_view` 表：

- 每收到一个事件，按事件类型把 `order_view` 对应行的 `status` 与 `version` 更新（如 `PaymentCompletedEvent` → `status=PAID`）。
- **幂等**：以 `idempotent_consumers` 表记录 `(consumerGroup, eventId)`，重复投递因 `ON CONFLICT DO NOTHING` 被跳过，Kafka 的 at-least-once 语义不会造成重复更新。
- **版本缺口保护**：更新语句带 `WHERE version = eventVersion - 1` 条件；若事件乱序到达（如版本 5 先于版本 4 到达），该条件不匹配，投影器判定为版本缺口并等待正确顺序的重放，而非用高版本覆盖低版本状态。
- **读己写**：用户下单后立即查询 `OrderQueryService` 时，系统会等待投影追上该订单的版本后再返回，避免读到投影尚未刷新的旧状态。

读侧因此呈现**最终一致性**：写操作返回后，`order_view` 不会绝对实时同步，但在短暂延迟后必然对齐事件库。写与读是两套独立存储，互不影响可用性。

**`query` 的设计要点：**

- **读写存储与事务隔离**：投影使用独立的 `projectionTransactionManager`（与写侧事务管理器分离），读模型可独立故障、独立扩容；即便写库压力高，读侧大屏仍可正常服务。
- **Kafka 偏移与 DB 事务解耦**：投影未引入 `KafkaTransactionManager`，Kafka 偏移提交独立于数据库事务。系统以 at-least-once 投递 + `idempotent_consumers` 幂等表兜底，而非追求 exactly-once（后者代价高且非必要），这是经过权衡的取舍。
- **"占位—投影"同一事务**：`tryMarkProcessed`（占位去重）与读模型 `UPDATE` 处于同一投影事务，任一失败整体回滚，保证"已去重"与"已投影"状态严格一致，不会出现"标记了却没更新"或反之的中间态。
- **乱序与缺口的工程化处理**：除 `WHERE version = eventVersion - 1` 的防错更外，`assertAlreadyAppliedOrGap` 区分两种情形——版本 ≤ 当前为无害重复直接忽略；版本 > 当前 + 1 为真正缺口则抛错，交由重放补齐，而非盲目跳过。
- **读己写的实现细节**：进度通知器注册在事务 `afterCommit` 钩子上，确保唤醒查询等待者时数据已真正提交可见；无事务上下文（如测试直调）时降级为立即通知，兼顾可测试性。

### 6.4 对外协作：`gateway`（端口与适配器）

支付、库存、通知等外部系统协议各异。`gateway/` 采用端口与适配器模式：领域层只声明"意图"（如"扣减库存"），具体对接支付宝、库存服务、短信网关的实现由 `mock` / `alipay` 等适配器完成。外部回调（支付结果）被包装成内部命令进入聚合根，从而将外部不确定性隔离在领域核心之外。

**`gateway` 的设计要点：**

- **端口与适配器（六边形架构）**：领域层只依赖"端口"接口（如支付端口、库存端口），具体实现（支付宝适配器、Mock 适配器）在外部。切换支付渠道或替换为测试桩时，领域代码零改动。
- **Mock 与真实实现并存**：`mock` 包提供确定性的本地实现，使集成测试不依赖外部网络；生产通过配置切换 `alipay` 等真实适配器，兼顾可测试性与可部署性。
- **失败语义收敛**：外部调用失败被转换为领域可理解的失败事件（如 `InventoryReservationFailedEvent`），而非抛出异常穿透到核心——核心只消费"已发生的事实"，外部抖动不会污染领域模型的纯净性。

### 6.5 跨步骤一致性：`compensation`（Saga 补偿）

一笔订单涉及多个外部步骤（扣款 → 占库存 → 通知）。`compensation/` 用 Saga 编排：若某步失败，则按相反顺序执行补偿动作。例如 `ReserveInventoryCommand` 返回库存不足，产生 `InventoryReservationFailedEvent`，Saga 触发退款补偿，使整笔流程回到一致状态。这是事件溯源在跨服务场景下的自然延伸。

**`compensation` 的设计要点：**

- **Saga 而非两阶段提交（2PC）**：跨支付/库存/通知的分布式流程若用 2PC，会长期锁资源、可用性差；Saga 以"正向步骤 + 反向补偿"实现最终一致，更契合事件驱动的松耦合系统。
- **补偿动作需幂等且可重入**：补偿可能因重试被多次触发，因此补偿逻辑必须基于事件状态判定"是否已补偿"，而非假设只执行一次——否则重试会二次退款。
- **补偿事件只留痕、不改订单状态**：`CompensationExecutedEvent` 在聚合根 `apply` 中不改变订单状态（此前回放该事件会抛 `IllegalStateException`，已修复），保证事件重放时订单状态机不受补偿动作干扰，维持重放的幂等性与确定性。
- **审批流衔接**：补偿/退款类敏感操作经 `compensation` 的审批模型流转，关键动作留痕可审计，符合金融类业务的合规要求。

### 6.6 实时风控：`anomaly`

`anomaly/` 消费事件流，规则引擎（Java 规则上下文）按事件 `metadata.userId` 聚合该用户历史，识别异常模式（如金额严重偏离、同用户高频下单）。判定异常后通过 WebSocket 实时推送至前端。此处曾有一个真实缺陷：事件 metadata 的 `userId` 未正确写入，导致按用户维度的"高频下单"规则永不触发——属于典型的数据链路断点，修复后风控才真正生效。

**`anomaly` 的设计要点：**

- **规则上下文与 AI 检测分工**：`rule` 包承载确定性规则（阈值、频率），`engine` 包做规则求值，`history` 包维护用户/订单的历史窗口；AI 异常检测（位于 `eventguard-ai` 服务）负责非确定性模式，二者经事件流衔接，规则层可做毫秒级拦截、AI 层做深度研判。
- **`RuleContextLoader` 依赖 metadata 透传**：规则按 `metadata.userId` 聚合该用户历史，因此事件在产生时必须正确写入穿越上下文，否则规则"看不见"用户——前述 `userId` 缺失 bug 正是这一依赖断裂所致。
- **消费即判定、推送解耦**：`consumer` 包消费告警事件并触发规则，判定结果经 `WebSocket` 实时推前端；检测与推送分离，前端断连不影响后端判定，重连后可补拉状态。

### 6.7 横切：`auth` 与 `common`

- `auth`：负责身份认证、权限控制、操作审计与令牌吊销，是信任边界上的校验。
- `common`：承载跨模块共用的纯技术能力（配置、监控指标、统一异常、安全基础组件），**刻意不写入任何订单业务规则**，以保持依赖方向干净。

**`auth` 与 `common` 的设计要点：**

- **`auth` 的令牌吊销模型**：认证颁发令牌后，吊销不能依赖令牌自然过期，系统维护吊销列表/版本，使"已登出或被踢"的令牌立即失效，避免会话劫持后的持续可用。
- **`auth` 与领域事件的关系**：权限校验发生在信任边界（命令入口），审计动作本身也可作为事件落库，使"谁在何时做了什么"具备与业务事件同等的不可篡改性与可重放性。
- **`common` 的依赖纪律**：`common` 只放跨领域技术能力（配置、指标、异常、安全基础设施、通用幂等组件），严禁放入订单业务规则。这样任意业务模块可依赖 `common`，而 `common` 不反向依赖任何业务模块，依赖图保持无环、单向。
- **可观测性与基础设施下沉**：监控指标（`metrics`）、调度（`scheduler`）、WebSocket 基础能力都下沉到 `common`，业务模块以注解/注入方式复用，避免每个模块重复搭建同类设施。

### 6.8 依赖方向与一致性边界（收尾）

> 依赖严格单向：接口层 → 领域核心 → 基础设施。`query` 读侧绝不反向调用 `command` 改写订单，它只消费已提交的事件。由此写读彻底解耦：读模型故障不影响写链路，事件库作为真相源可随时重建读模型。

---

## 七、专业名词解释表

| 名词 | 一句话解释 |
|---|---|
| **事件溯源 Event Sourcing** | 不存"当前状态"，只存"发生过的所有事件"，当前状态由事件重放得出。 |
| **CQRS** | 命令与查询职责分离：把"写数据"和"读数据"拆成两套模型/系统。 |
| **聚合根 Aggregate Root** | 一团相关业务对象里那个"说了算"的总对象，负责执行业务规则、产出事件。 |
| **领域事件 Domain Event** | 已经发生的事实记录（如"订单已支付"），不可更改，是系统的真相单元。 |
| **命令 Command** | 一个"想做某事"的请求（如"支付此订单"），由聚合根决定是否合法。 |
| **状态机 State Machine** | 订单在不同状态间流转的规则图，规定了"什么状态下允许什么操作"。 |
| **事件库 Event Store** | 专门按序存储所有领域事件的数据库，是唯一的真相来源。 |
| **版本号 / 乐观锁** | 给每次修改编号，并发修改时核对版本，冲突则重试而非覆盖。 |
| **投影 Projection** | 把事件转换成读模型（如更新大屏）的过程，通常由消息驱动。 |
| **读模型 / 写模型** | 读模型是给查询用的扁平视图；写模型是产出事件的业务核心。 |
| **最终一致性** | 写完后读侧不会立刻看到，但经过短暂延迟后一定会看到，系统整体最终对齐。 |
| **消息队列 Kafka** | 高吞吐的发布-订阅管道，一端发事件、多端订阅消费。 |
| **幂等 Idempotency** | 同一个操作重复执行多次，结果和只执行一次相同（靠去重表实现）。 |
| **读己写 Read-Your-Writes** | 用户刚写完立刻读，系统保证能读到自己刚写的内容，而非旧值。 |
| **端口与适配器 / 六边形架构** | 核心业务只定义"要做什么"（端口），外部系统对接细节（适配器）可替换。 |
| **Saga / 补偿** | 跨多步的分布式流程编排；某步失败则反向执行补偿动作撤销前文。 |
| **WebSocket** | 服务端可主动、持续向浏览器推送数据的长连接协议。 |
| **审计 Audit** | 记录"谁在何时做了什么"，用于安全追溯与合规。 |
| **CDC / Debezium / WAL** | 变更数据捕获：监听数据库日志（WAL）把改动同步到消息队列，无需改业务代码。 |
| **元数据 Metadata** | 附加在事件上的描述信息（如用户 ID），本身不是业务字段，但供规则/统计使用。 |
| **快照 Snapshot** | 某时刻的状态存档，用于避免回放过长事件链时从头重算。 |
| **微服务 / 网关 Gateway** | 把大系统拆成独立小服务，网关负责对外统一接入与协议转换。 |

---

## 八、后续可优化方向

> 本节省份记录架构上**已经识别、尚未实现（或仅半成品）**的优化点。面试中主动抛出这些，既展示对系统边界的清醒认知，也体现"能设计、也能复盘"的工程成熟度。后续新增条目继续在此章追加。

### 8.1 投影重建机制缺失正式入口（已知 ceiling）

- **现状**：读模型重建目前由三部分拼成——`OrderViewProjection.reset()` 清空 `order_view` 与 `idempotent_consumers`、Kafka `auto-offset-reset: earliest` 重消费、DLT 毒消息定时重放（`DltReplayController` + `DltReplayScheduler`）。
- **缺口**：`reset()` 未暴露为生产端点，且**不会回退 Kafka 消费位移**——已存在的 `order-view-projection` 消费组有已提交 offset，只清表不会重放历史事件，只会接收新事件。投影接在 Kafka 上而非直接读事件库，因此"从事件溯源真相源（`domain_events`）全量重建读模型"没有封装命令。
- **当前绕过方式**：手动 `kafka-consumer-groups --reset-offsets` 回退位移 + 调 `reset()` 组合，且依赖 Kafka 从 `earliest` 仍保留全量日志（受 retention 限制）。
- **优化方向**：
  1. 把 `reset()` 封装为带权限的运维端点（如 `POST /admin/projection/reset`）。
  2. 提供"从事件库重放"路径：直接从 `domain_events` 按 `aggregate_id` 顺序读出事件喂给投影逻辑，使重建**不依赖 Kafka retention**，彻底摆脱对消息中间件的耦合（ponytail 式解耦：读模型可由真相源独立重建）。
  3. 重建时复用同一套幂等（`idempotent_consumers`）与版本缺口保护，保证中途失败可安全重入。
- **面试话术**："读模型是派生数据，理论上可由事件库随时重建。当前实现把重建挂在 Kafka 重消费上，缺少一键入口；这是我有意标记的已知 ceiling，下一步会把重建源从 Kafka 切到事件库本身。"

### 8.2 事件溯源聚合仅覆盖订单域（已知范围 / ceiling）

- **现状**：事件溯源聚合模式（`AggregateRoot` 基类 + 事件库 + 投影）目前**只应用于 `OrderAggregate` 一个聚合**。全工程仅此一个类继承 `AggregateRoot`；其余限界上下文（`auth` 的 User/Role、`compensation` 的 Approval、`anomaly` 告警历史）走常规 JPA/CRUD 仓储，状态直接持久化，不经过事件流。
- **设计意图**：单聚合让订单域的一致性边界极简，无需跨聚合事务或聚合间 Saga 协调，是刻意的 scope 收敛而非遗漏。
- **缺口 / 天花板**：
  1. 订单域与 CRUD 域"事件即真相"的语义不一致——若未来要对用户、补偿等也做事件溯源，需补齐各自的事件、投影与重放链路。
  2. 事件库 `EventStoreJdbcImpl.java:20-22` 的 `aggregate_type` 硬编码为 `"Order"`，引入第二种聚合会被错标；升级路径是给 `DomainEvent` 加 `aggregateType()`，由事件自带类型，而非在写入处猜测。
- **复用基础已就绪**：`AggregateRoot` 抽象基类与 `EventStore`/`AggregateRepository` 是聚合无关的，新增第二个事件溯源聚合时可直接继承基类，主要工作量在补事件类与投影，而非重写基础设施。
- **面试话术**："事件溯源我落地在订单这一个核心聚合上，其他域用 CRUD，是有意的单聚合收敛。基础设施（聚合基类、事件库、仓储）是聚合无关的，要扩第二个事件溯源聚合，主要补事件与投影即可——`aggregate_type` 那处硬编码我已经标了 ponytail 升级点。"

### 8.3 快照阈值（SNAPSHOT_INTERVAL=100）为硬编码常量（可优化）

- **现状**：快照触发节奏写死在 `AggregateRepository.SNAPSHOT_INTERVAL = 100`（`AggregateRepository.java:22`），每满 100 个事件打一份快照。该值与单个订单实际事件量、单事件大小无关，是全工程统一的一刀切阈值。
- **问题 / 天花板**：
  1. 阈值过大（如订单事件极少、常年 <100）：快照几乎不触发，长尾订单仍走全量重放，没吃到快照红利。
  2. 阈值过小（如单事件 payload 很大、或某聚合事件极多）：快照过密，快照存储成本与"快照 + 增量"合并开销反而上升。
  3. 常量不可配，需改代码重新部署才能调整，无法按环境/聚合类型分别调优。
- **优化方向**：
  1. 把阈值外置为配置（如 `eg.event.saga.snapshot-interval`），支持按聚合类型或事件量设不同间隔。
  2. 进阶：改用"按时间 + 按事件数"混合触发（如超过 N 事件**或**距上次快照超过 T 天任一满足即打），避免低频长生命周期订单永远攒不满 100 个事件却不打快照。
  3. 快照存储可加 retention / 仅保留最近若干份，避免 `snapshot` 表无限膨胀（与事件库 retention 策略对齐）。
- **面试话术**："快照间隔我写死成 100 了，理由是订单域事件量可预期、先求简单。但它没考虑单事件大小和长尾低频订单，属于已知的可配置化优化点；生产里我会把阈值外置并按聚合类型微调，必要时换成'事件数 + 时长'双触发。"

### 8.4 补偿 Saga 实例存内存，无多副本持久化（已知 ceiling）

- **现状**：Saga 编排器用内存 `ConcurrentHashMap` 保存 `SagaInstance`（`CompensationSaga.java:43`），步骤列表、执行指针 `index`、状态 `SagaStatus` 全在进程内；审批请求持久化到 `compensation_approval` 表（含 `__saga_remaining_steps` 剩余步骤存档），但**活跃 Saga 本身不落库**。
- **缺口 / 天花板**：
  1. **单实例上限**：多副本部署时每个进程各持一份内存 Map，互不共享——负载均衡把后续步骤/审批回调打到另一副本时，找不到 `sagaId` 对应实例（`onApproved` 里 `instances.get` 返回空，`CompensationSaga.java:164-168`），只能标记 FAILED。
  2. 重启依赖 `SagaRecoveryRunner.recoverPending` 从审批单重建，覆盖"审批挂起中"的场景；但**未挂审批、正在顺序执行中**的 Saga 重启即丢，无存档可恢复。
  3. 内存 Map 是"活跃进度"单点，实例数随并发补偿增长，无上限约束与淘汰策略。
- **优化方向**：
  1. **落库 Saga 状态机**：把实例（sagaId/aggregateId/steps/index/status）持久化到表，`onApproved` 与 `executeStep` 按版本/锁更新，像事件溯源一样让"进度"可重建、可多副本共享。
  2. 或用事件溯源思路：Saga 每步"动作完成/挂起/失败"都发一条 Saga 事件，当前进度 = 事件重放，天然可恢复、可审计。
  3. 过渡方案：仅把 `index` 写回审批单/单独进度表，`onApproved` 从库读进度而非依赖内存实例，兼容当前审批恢复链路、改动最小。
- **面试话术**："补偿 Saga 的活跃实例我放在内存 `ConcurrentHashMap` 里，审批单是持久化的，所以'审批挂起'能靠 `recoverPending` 恢复；但多副本和'执行中崩溃'这两条目前没覆盖，是我标了 ponytail 的已知 ceiling。生产化的正路是把 Saga 状态机落库（或做成事件溯源式 Saga），让进度可重建、多副本可共享。"

### 8.5 JWT 令牌吊销依赖版本号，无 refresh token / jti 黑名单（已知简化）

- **现状**：JWT 采用 HS256，角色/权限等 claims 全在令牌里；吊销靠 `tv`（令牌版本）与 `auth_user.token_version` 比对——DB 版本号 +1 后旧令牌即视为吊销（`JwtService.java:80-84`），无黑名单、无 refresh token。
- **缺口 / 天花板**：
  1. **权限/角色是令牌内的快照**：claims 里带着 `roles/permissions`，改角色、改权限后**必须重新登录**旧令牌才作废，无法即时生效。
  2. **只能"用户级整体作废"**：`token_version` 是用户维度的，踢人/改密码会把该用户所有令牌一起作废，无法精确吊销"某一台设备 / 某一手机"的那一张令牌。
  3. **无 refresh token**：12h 过期就得重新登录，移动端/长会话体验差；被偷的令牌只能靠 bump `token_version` 连带作废该用户全部令牌，没有"只废被偷那一张"的轻量手段（与缺口 2 同源）。
  4. 签发用单一共享 `EG_JWT_SECRET`，密钥轮换需全端同步（AI PyJWT 同密钥）。
- **优化方向**：
  1. 引入 **refresh token + 短生命周期 access token**：access token 10-15 分钟、refresh token 长效；access 过期用 refresh 续签，体验与安全兼顾。
  2. 引入 **`jti`（JWT ID）黑名单**：为每张令牌发唯一 `jti`，吊销时写黑名单（或 Redis），支持**精确吊销单张令牌**而不影响同用户其他会话。
  3. 权限校验从"纯 claims"改为"claims 存 `uid`，角色/权限动态查库/缓存"——权限变更即时生效，令牌吊销粒度更细。
  4. 密钥支持多版本轮换（`kid` 声明区分新旧密钥），避免单密钥全局轮换的停机窗口。
- **面试话术**："令牌吊销我用的是版本号方案——`tv` 对不上 `auth_user.token_version` 就视为吊销，零黑名单、无状态，对 MVP 够用。它有四个已知边界：权限变更要重新登录生效、只能整用户作废、没有 refresh token、单密钥轮换要全端同步。生产化的方向是 refresh token + `jti` 黑名单支持精确吊销，权限改成查库动态校验，密钥支持 `kid` 轮换。"
