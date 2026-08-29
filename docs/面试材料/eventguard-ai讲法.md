# EventGuard AI 层讲法（面试版）

> 适用场景：后端 / AI 应用岗自我介绍后的架构追问，覆盖 `eventguard-ai`（Python FastAPI 服务）。
> 讲法原则：**先讲脊柱（Kafka 事件流 → 检测 → 发布告警，外加 LLM 分析 / NL 查询两个旁路）**，再按数据流分层次之，最后用"AI 与 server 的协作边界"收尾；把面试官引到最熟的主线上——实时异常检测闭环。
> 定位：`eventguard-server` 是**真相源与命令侧**，本层是**事件流的消费方与智能研判方**：只消费 `domain-events`、只发布 `anomaly-alerts`、只读 HTTP 拉后端数据，本身不写业务库。

---

## 一、开场一句话（定调）

> "EventGuard 的 `eventguard-ai` 是一条**独立部署的 Python 智能层**：消费 server 经 Debezium 投到 Kafka 的 `domain-events`，用『规则引擎（HTTP 调 server）+ Isolation Forest + 流程级规则/HMM』三路检测产出异常，去重后发布到 `anomaly-alerts`（前端亮灯）；同时对外提供『LLM 根因分析 / ReAct 自愈 agent / 自然语言查询 / 周报 / 订单终局预测 / 相似案例检索』等智能能力。AI 层是 server 的**只读消费方**，不反向写订单库，靠机器密钥与 JWT 复用 server 的鉴权。"

这句话把话题锚定在"检测闭环 + 智能旁路"双主线，避免先被问偏门的训练/缓存模块。

---

## 二、分层讲法（按数据流顺序，不要按字母顺序）

AI 层有两条数据流主线：

- **检测主线（Kafka 驱动）**：`server 事件库` →(Debezium CDC)→ `Kafka: domain-events` → `kafka_consumer 消费` → `detector 检测(事件级+流程级)` → `AlertDeduper 去重/风暴抑制` → `anomaly_store 落库` + `AnomalyPublisher 发布` → `Kafka: anomaly-alerts` → `前端 WebSocket 亮灯`。
- **智能主线（HTTP 触发）**：`前端/运营` →(HTTP)→ `main.py 端点` → `analyzer / query / report / predictor / cases` →(BackendClient/EventStoreClient)→ `server 拉订单与事件数据` →(按需)→ `LLMClient(用户自己的 LLM 配置)` → `返回结果`。

下面按检测主线的顺序逐层说明，智能主线在各层内一并交代。

### 1. `kafka_consumer.py` —— 入口与检测管道的装配

- **消费线程 `EventKafkaConsumer`**：后台守护线程消费 `domain-events`（groupId=`ai-event-detector`），关闭自动提交、`max_poll_records=1`，单线程逐条处理；这个单线程前提让后续有状态的 `FeatureExtractor` 不加锁也能安全推进用户基线。
- **Debezium 消息展平 `flatten_debezium_event`**：把 CDC 的 `{"payload":{event_id,...}}` 信封拆成裸事件字典，并把 `payload`/`metadata` 两个 JSONB 字符串列解析成对象。这是与 Java 侧 `EventDeserializer` 对齐的跨栈契约点。
- **毒消息处理（信任边界）**：畸形消息不中断循环，重试 `MAX_MESSAGE_RETRIES=3` 后退避发死信主题（`_retry_failed_message`、`_publish_dlt`），且只有死信发布成功后才提交原 offset，避免坏消息卡死消费组。
- **消费积压指标 `consumer_lag`**：每约 5 秒采样 `end_offsets - position`，暴露为 Prometheus gauge，给检测健康度兜底。
- **管道装配 `lifespan`**：启动时把 `EventLevelService`、`ProcessLevelRuleDetector`、`EventWindow`、`ProcessLevelHMMDetector`（可选）、`AnomalyPublisher` 注入 `DetectionHandler`，再交给消费者后台跑。关键降级：检测管道启动失败**不拖垮 API**——只关检测、保留 NL 查询与根因分析，体现"检测是增强、不是阻塞"。

### 2. `detector/` —— 检测层（AI 层的脊柱）

本层分**事件级 + 流程级**两路，每路又由"确定性规则"与"统计/ML 模型"共同组成。

#### 2.1 事件级：`EventLevelService`（规则高优 → IF 低优）

- **协同服务 `EventLevelService.detect`**：先调 `RuleBridge`（HTTP 调 server 规则引擎，命中即高优先级告警），未命中再调 `EventLevelDetector`（低优先级）。设计意图是毫秒级硬规则先拦、模型做深度补充。
- **规则桥接 `RuleBridge.evaluate`**：调 server 的 `POST /anomaly/rules/evaluate`，把 Kafka 事件翻译成 Java `EventDto`。注意 AI 侧不自己写 R001-R005，规则引擎在 server 的 `anomaly/` 包，这是"规则实时拦截、AI 深度研判"的分工。桥接硬超时 2 秒，超时即降级跳过、改走纯模型检测，不阻塞消费线程。server 端该端点现已对契约非法的事件返回 **400**（而非 500），桥接侧按失败跳过即可，不影响降级链路。
- **Isolation Forest `EventLevelDetector.detect`**：`predict=-1` 判异常，`score=-score_samples` 越大越异常；加载 `models/isolation_forest.pkl` 与 `scaler.pkl`，模型缺失会明确报错要求先训练，不静默跑空。
- **4 维特征 `FeatureExtractor`**：`amount_zscore`（金额相对用户历史均值的 Z 分数）、`time_since_last_event`、`user_order_count_1h`（1 小时下单数）、`state_transition_prob`（状态转移在合法表中的概率）。它是有状态的，`update` 推进用户基线/订单状态，依赖单消费线程才不加锁。
- **业务价值**：IF 抓 server 规则覆盖不到的"软异常"（金额离群、下单频次异常），是规则引擎的互补而非替代。

#### 2.2 流程级：`ProcessLevelRuleDetector` + `ProcessLevelHMMDetector`

- **滑动窗口 `EventWindow`**：按 `aggregate_id` 维护最近 20 个事件的 deque；每来一个事件先 `add` 再取该订单序列做流程检测。内存窗口只增不落盘（已知上限）。
- **规则检测器 `ProcessLevelRuleDetector`**，三道规则：
  - `P001_ILLEGAL_TRANSITION`（非法状态迁移）：对照 `LEGAL_TRANSITIONS` 逐事件校验；`STATE_PRESERVING_EVENTS`（如 `CompensationExecutedEvent`）不参与校验，避免误报。
  - `P002_STUCK`（状态停滞）：非终态订单停留超过 `stagnation_timeout_hours`（默认 24 小时，可配）报警。
  - `P003_DEAD_LOOP`（支付重试死循环）：`PaymentRetried` 重复超过 `dead_loop_threshold`（默认 5，可配）报警。
  - 阈值都在 `config.py` 可配（`stagnation_timeout_hours`/`dead_loop_threshold`），不改代码即可调，对齐 server 侧风格。
- **HMM 第二意见 `ProcessLevelHMMDetector` + `run_process_detectors`**：先规则后 HMM 合并结果，序列 log-likelihood 低于训练阈值报 `P004_HMM_LOW_LIKELIHOOD`。关键降级：模型/词表/阈值文件缺失时 `loaded=False`、`detect` 返回空，主流程仅依赖规则；未知事件类型超出建模符号空间时保守跳过、交回规则。

#### 2.3 去重与风暴抑制门控：`AlertDeduper`

- **三重门控 `AlertDeduper.should_publish`**：返回 `publish`/`dup`/`suppressed` 三态。
  - **幂等去重**：同一 `(rule_id, aggregate_id, fingerprint)` 在 TTL（300 秒）内只发一次。事件级用 `event_id` 做指纹，流程级用 `description`（P001 含迁移对、P002 含停滞状态，窗口内稳定）做指纹。
  - **风暴抑制**：同一 `(rule_id, aggregate_id)` 每分钟最多 `storm_limit=3` 次，防突发刷屏。
  - **只影响发布、不改检测语义**：保证按 rule/agg 的断言口径不变；TTL 过期后持续性异常会周期重发、由风暴抑制兜底（已知上限）。

### 3. `publisher/` + `store/` —— 告警落库与发布

- **发布 `AnomalyPublisher.publish`**：发到 Kafka `anomaly-alerts`（key=aggregate_id），同步等单条确认（`future.get(timeout=2)`），失败由 `DetectionHandler._publish` 退避重试 3 次；仍失败则暂存内存并计数。
- **存储 `AnomalyStore`**：线程安全内存表，可选 `EG_ANOMALY_STORE_PATH` 做 JSONL 持久化（进程重启可恢复，供周报/相似案例检索），上限 1 万按 `detected_at` 最旧淘汰。
- **事件拉取 `EventStoreClient`**：经 `GET /orders/{id}/events` 拉订单事件历史，并把 Java 的 camelCase 规范化为 AI 内部 snake_case（`_normalize`）——保证 Kafka 事件与 HTTP 事件在 AI 服务内形状一致，是跨源契约点。

### 4. `analyzer/` —— 根因分析与 ReAct 自愈（LLM 智能主线）

本层是"AI 应用"最核心的亮点，重点在**可靠性设计**。

- **统一 LLM 客户端 `LLMClient`**：兼容 OpenAI / Anthropic（含 DeepSeek 端点）双协议、provider 自动探测；三套 API——`generate`（纯文本）、`generate_json`（强约束 JSON）、`generate_with_tools`（ReAct 工具调用）；带连接池复用、信号量限流（`llm_max_concurrency`）、可重试（429/5xx/超时）、token 埋点与 trace。**用户级 LLM 隔离**：`base_url/api_key/model` 必须显式传入（来自用户个人中心配置），不再读进程级环境变量，保证多租户安全。
- **根因分析器 `RootCauseAnalyzer.analyze`**：流程为加载事件 → 构造 prompt（含动作白名单、反注入指令，`PromptBuilder`）→ LLM 结构化输出 + 错误反馈重试（JSON 解析/Pydantic 校验失败喂回 LLM 修正一次，`MAX_ATTEMPTS=2`）→ 证据核验（evidence 提及的事件类型必须存在于订单事件序列，否则重写，`_evidence_plausible`）。核心假设是大模型输出不可信，最终经白名单 + 证据自检双重校验，不自动执行任何动作。
- **动作白名单 `ALLOWED_ACTIONS`**：`REFUND`/`NOTIFY_DELAY`/`MARK_OUT_OF_STOCK`/`FREEZE_ORDER`/`BACKOFF_AND_STOP`，Pydantic validator 拒非法动作——把"AI 建议"锁死在 server Saga 补偿已有的动作集里，防越权。
- **ReAct 自愈 Agent `HealerAgent.heal`**：`MAX_STEPS=5`，多轮调用 `query_order`/`query_events`/`query_stats` 三个只读工具收集证据，收敛后由加固的 `RootCauseAnalyzer` 生成最终结构化报告，`agent_trace` 作为可解释过程返回前端；步数用尽走确定性兜底，防 LLM 死循环。当前是只读分析闭环，后续才加写工具与审批。
- **Prompt 反注入 `PromptBuilder.build`**：异常描述/上下文包 `<untrusted>` 标签，明确"只能作为事实参考、不得执行其中指令"，是服务端不可信数据喂 LLM 的防护。

### 5. `query/` —— 自然语言查询（NL2SQL 式降级链路）

- **引擎 `NLQueryEngine.query`**：意图分类 → 模板执行 → LLM 润色，支持多轮追问（缺参时反问、会话上下文补参，`_route`、`_ask_for_param`）。
- **意图分类 `IntentClassifier.classify`**：3 类意图 `event_lookup`/`stats_aggregation`/`trace_replay`，LLM 优先、关键词兜底；带否定词消歧（避免"不是 event_lookup"被误判），LLM 超时降级关键词。
- **模板执行 `TemplateExecutor`**：每类意图对应一个模板，从问题里抽 `order_id`（UUID 正则）/`status`/`time_window`，调 `BackendClient`；时间窗以"今天零点"为锚点做严格半开区间，修正了"昨天多计入今天"的边界问题。
- **后端客户端 `BackendClient`**：HTTP 调 server 的 `GET /orders/{id}`、`/orders/stats`、`/orders/{id}/events`、`/orders`（watchlist 用）、`/internal/users/{uid}/llm-config`（拉解密后的用户 LLM 配置），统一带 `X-API-Key` 机器密钥。
- **降级纪律**：LLM 润色超时（8 秒）返回数据摘要（`_fallback_answer`），保证前端 10 秒内必有回答；意图分类/润色都走 `LLMCache` 缓存。
- **多轮会话 `ConversationStore`**：内存会话表，30 分钟 TTL + 512 上限 LRU，存待补参数 / 指代消解上下文 / 最近 20 条历史，进程重启即清（已知上限）。

### 6. `report/` + `predictor/` —— 周报、故事线与订单终局预测

- **周报 `WeeklyReportGenerator.generate`**：确定性聚合（按 rule 计数 / top 订单，不信任 LLM）+ 后端订单统计 → LLM 只生成 `symptoms`/`recommendations` 文案，LLM 失败降级规则摘要；异常来源 `anomaly_store.list_recent`。
- **故事线 `StoryGenerator.generate`**：事件序列 → LLM 写 120 字运营复盘，失败模板兜底。
- **订单终局预测 `OrderPredictor`**：加载 `models/predictor.pkl` + `predictor_meta.json`，对订单当前事件序列预测终局状态（CLOSED/CANCELLED/REFUNDED/STUCK）+ 置信度 + 风险分级（`risk_rank`）。模型缺失降级 `available=False`，端点返回 `prediction=null` 不阻断；特征 8 维（`predict_events`）与 `training/train_predict.py` 严格对齐。
  - **watchlist `predictions_watchlist`**：遍历后端非终态订单批量预测、按风险降序返回 TopN，是 AI 层从"检测"走向"预测运营"的体现。

### 7. `cases/` —— 相似案例检索（轻量 RAG，零新依赖）

- **`CaseIndex`**：不做向量库/embedding，相似度 = 规则同型/事件类型/来源/级别/时间近邻/同订单 的加权打分（`similarity`），确定性可解释；直接读 `anomaly_store`（已支持 JSONL 持久化），不维护并行索引。
  - **处置状态**：`query` 额外查该聚合根是否出现过 `CompensationExecutedEvent` → 标"已补偿/未处置"（`_resolution`），供运营参考上次处置。
  - **few-shot 注入**：`EG_AI_RAG_FEWSHOT=true` 时，根因分析前把 top-3 相似案例并入 prompt（`RootCauseAnalyzer._maybe_add_fewshot`），提升分析质量且无需向量库。

### 8. 横切：`config.py` / `security.py` / `metrics.py` / `cache/` / `trace/`

- **`config`（`config.py`）**：`Settings` 全部 `EG_` 前缀可配，涵盖 Kafka/后端地址、LLM 并发/重试、检测阈值、各类超时（分析 45 秒、自愈 120 秒、NL 润色 8 秒）、JWT 与机器密钥——把"阈值/超时/路径"外置，体现改参数不必改代码。
- **`security`**：`require_permission` 依赖工厂，解析 `Authorization: Bearer <JWT>`，复用与 server 同一 `EG_JWT_SECRET`（HS256）校验签名/过期，再校验权限码（`ai:query`/`anomaly:view`）；不信任上游代理，独立鉴权。
- **`metrics`（`metrics.py`）**：Prometheus 指标前缀 `eventguard_ai_*`，覆盖检测、NL 查询、LLM 三类可观测数据，纯埋点不阻断业务。
- **`cache/llm_cache`**：内存 TTL+LRU，只缓存幂等读场景（意图分类/NL 润色），可解释场景不缓存；进程重启即清。
- **`trace/trace_log`**：环形缓冲存 `nl_query`/`root_cause`/`llm_call`/`llm_cache` 四类 trace，前端经 `GET /ai/traces/recent` 查看。

### 9. 训练管线 `training/`（模型从哪来）

- **`train_isolation.py`**：用 `data/normal_events.jsonl` 正常事件流训练 Isolation Forest + StandardScaler（`contamination=0.05` 可配），持久化到 `models/`。
- **`train_hmm.py`**：训练 `CategoricalHMM` 流程序列模型 + 词表 + 阈值（分位数），落地 `hmm.pkl`/`hmm_vocab.json`/`hmm_threshold.json`。
- **`train_predict.py`**：训练订单终局分类器，产出 `predictor.pkl` + `predictor_meta.json`（含 `labels`/`event_vocab`/`k`），推理特征严格对齐 `OrderPredictor._extract_features`。
- **`generate_data.py`**：合成正常/异常事件数据，供训练与评测（`evaluate*.py`）。
- **要点**：模型与推理解耦，重训后挂载卷持久化即可生效，不重建镜像。

---

## 二（续）、整体方法论拼图：AI 层与 server 层的关系

> 面试官若问"AI 层到底算什么角色"，用本节定位：**server 是真相源与命令侧，AI 是只读消费方与智能研判方，二者经 Kafka + HTTP 解耦。**

| 维度 | `eventguard-server`（Java） | `eventguard-ai`（Python） |
|---|---|---|
| 语言/形态 | Spring Boot 单体（模块化） | FastAPI 独立服务 |
| 数据角色 | 写事件库（真相源）、投影读模型 | **只读**消费 `domain-events`、拉 HTTP 数据 |
| 异常检测 | 确定性规则引擎 R001-R005（毫秒级拦截） | 规则桥接 + Isolation Forest + 流程级规则/HMM（深度研判） |
| 智能能力 | 无 LLM | 根因分析 / ReAct agent / NL 查询 / 周报 / 预测 / 案例检索 |
| 协作通道 | Kafka `domain-events` 出、`anomaly-alerts` 收；HTTP 读接口 + 规则评估接口 | Kafka 入 `domain-events`、出 `anomaly-alerts`；HTTP 调 server 读接口 |
| 鉴权 | JWT 签发 + 权限 + 机器密钥 | 复用 JWT secret 校验、机器密钥调后端 |

**一句话**：server 负责"事实怎么产生与存储"，AI 负责"事实怎么被理解和预警"——AI 不碰业务写路径，任何故障（模型缺失、LLM 超时、推理崩溃）都**降级而非阻断** server 主链路。

---

## 三、模块详解（逐个模块的正式讲法）

> 本节对关键模块做正式、自包含的讲解，可作为"分层讲法"的逐模块深化；后续模块按同格式追加为并列小节。

### kafka_consumer.py（消费线程与检测编排）

本文件是 AI 层接入事件流的入口与检测编排中枢，核心职责是持续消费后端经 Debezium 投递至 Kafka `domain-events` 主题的订单领域事件，完成消息解析、异常检测与告警发布，构成检测主线的起点。其包含两个职责分离的核心类：

1. **`EventKafkaConsumer` —— 事件消费线程**

   作为独立的后台守护线程运行，负责从 Kafka 拉取消息并做预处理，自身不包含检测逻辑：

   - **单分区串行消费**：配置 `max_poll_records=1` 并关闭自动提交，保证同一时刻仅单线程处理单条消息。该约束使下游有状态的 `FeatureExtractor` 得以在无锁前提下安全地维护用户基线。
   - **消息展平（`flatten_debezium_event`）**：将 Debezium CDC 的嵌套信封结构解析为检测器可直接消费的扁平事件字典，并与 Java 侧 `EventDeserializer` 保持跨栈契约一致。
   - **故障隔离与死信机制**：对解析失败的畸形消息不中断消费循环，按 `MAX_MESSAGE_RETRIES` 有限重试；超出阈值后投递至死信主题（DLT），且仅在死信发布成功后才提交原 offset，避免单条异常消息阻塞整个消费组。
   - **消费积压监测**：周期性采样 `end_offsets` 与 `position` 的差值，暴露为 `eventguard_ai_consumer_lag` 指标，用于评估消费健康度。

2. **`DetectionHandler` —— 检测与发布流水线**

   消费线程每获取一条事件即交由该类处理，串联"检测 → 去重 → 发布"三个环节：

   - **事件级检测**：调用 `EventLevelService`，对单条事件进行异常判定（金额离群、频次异常等）。
   - **流程级检测**：将事件追加至按 `aggregate_id` 维护的滑动窗口，基于近期事件序列评估流程合法性（状态停滞、非法迁移、支付死循环等）。
   - **去重与风暴抑制**：经 `AlertDeduper` 门控，按指纹实现幂等去重、按阈值实现风暴抑制，仅在放行时落库并发布至 `anomaly-alerts` 主题。
   - **发布可靠性**：发布动作采用退避重试（共 3 次，约 3 秒），持续失败则暂存于内存存储并上报错误指标，不阻塞消费线程。

设计要点（可作为面试阐述重点）：① 消费与处理解耦，处理环节异常不影响消费循环连续性；② 将 Kafka 外部消息视为不可信来源，经死信主题隔离畸形数据；③ 发布失败仅触发暂存与计数，检测异常不影响后端主链路，符合"AI 层作为增强而非依赖"原则；④ 以单线程换取有状态组件免锁的简洁性，属 MVP 阶段刻意取舍，高吞吐场景应演进为异步消费。

### detector/（检测层：事件级 + 流程级）

本层是 AI 层检测主线的"脊柱"，按"事件级 + 流程级"两路组织，每路均由确定性规则与统计/机器学习模型共同构成，体现"规则先拦、模型补充"的分工。

1. **`EventLevelService` —— 事件级检测协同**

   先调规则引擎做毫秒级硬拦截，未命中再补模型深度检测：

   - **规则高优**：经 `RuleBridge` 以 HTTP 调用 server 规则引擎（R001-R005），命中即产出高优先级告警。
   - **模型补充**：未命中时转 `EventLevelDetector` 跑 Isolation Forest，覆盖规则触及不到的软异常。
   - **桥接降级**：`RuleBridge` 设硬超时（2 秒），超时即跳过规则、改走纯模型检测，不阻塞消费线程。
   - **Isolation Forest**：以 `predict=-1` 判异常，加载 `isolation_forest.pkl` 与 `scaler.pkl`；模型缺失则明确报错要求先训练，不静默跑空。

2. **`FeatureExtractor` —— 特征工程**

   将原始事件转换为模型可消费的数值向量：

   - **4 维特征**：`amount_zscore`（金额相对历史均值 Z 分数）、`time_since_last_event`、`user_order_count_1h`（1 小时下单数）、`state_transition_prob`（状态转移概率）。
   - **有状态维护**：`update` 持续推进用户基线与订单状态。
   - **免锁前提**：安全性依赖单消费线程，故无需加锁。

3. **`ProcessLevelRuleDetector` + `EventWindow` —— 流程级规则**

   基于订单近期事件序列评估流程合法性：

   - **滑动窗口**：`EventWindow` 按 `aggregate_id` 维护最近 20 个事件的 deque。
   - **`P001_ILLEGAL_TRANSITION`（非法状态迁移）**：对照 `LEGAL_TRANSITIONS` 逐事件校验；`STATE_PRESERVING_EVENTS`（如 `CompensationExecutedEvent`）不参与校验，避免误报。
   - **`P002_STUCK`（状态停滞）**：非终态订单停留超过 `stagnation_timeout_hours`（默认 24 小时，可配）报警。
   - **`P003_DEAD_LOOP`（支付重试死循环）**：`PaymentRetried` 重复超过 `dead_loop_threshold`（默认 5，可配）报警。
   - **阈值可配**：`stagnation_timeout_hours` / `dead_loop_threshold` 均在 `config` 中配置，不改代码即可调。

4. **`ProcessLevelHMMDetector` —— HMM 第二意见**

   在规则之后对事件序列做第二道流程级判定：

   - **序列似然**：log-likelihood 低于训练阈值报 `P004_HMM_LOW_LIKELIHOOD`。
   - **缺失降级**：模型/词表/阈值缺失时 `loaded=False`、返回空，主流程仅依赖规则。
   - **未知符号**：事件类型超出建模符号空间时保守跳过、交回规则。

5. **`AlertDeduper` —— 去重与风暴抑制**

   发布前的轻量门控，只影响发布、不改检测语义：

   - **三态门控**：`should_publish` 返回 `publish` / `dup` / `suppressed`。
   - **幂等去重**：同一 `(rule_id, aggregate_id, fingerprint)` 在 TTL（300 秒）内只发一次；事件级用 `event_id` 做指纹，流程级用 `description` 做指纹。
   - **风暴抑制**：同键每分钟上限 `storm_limit=3`，防突发刷屏。

设计要点：① 规则与模型分层互补，硬规则毫秒级拦截、模型覆盖软异常；② 有状态特征提取依赖单线程免锁；③ 各模型独立降级，任一缺失均不阻断规则检测主链路。

### publisher/ + store/（告警落库与发布）

本组负责将检测产出的异常持久化并对外发布，是"检测 → 亮灯"闭环的出口。

1. **`AnomalyPublisher` —— 告警发布**

   - **同步确认**：发往 Kafka `anomaly-alerts`（key=aggregate_id），同步等待单条确认（`future.get(timeout=2)`）。
   - **退避重试**：瞬时失败按 `PUBLISH_BACKOFF_SECONDS` 退避重试 3 次；持续失败则暂存内存存储并计数，不阻塞消费线程。

2. **`AnomalyStore` —— 告警存储**

   - **线程安全内存表**：默认全内存承载检测结果。
   - **可选持久化**：配置 `EG_ANOMALY_STORE_PATH` 做 JSONL 落盘，进程重启可恢复，供周报与相似案例检索。
   - **上限淘汰**：上限 1 万条，按 `detected_at` 最旧淘汰。

3. **`EventStoreClient` —— 事件拉取**

   - **HTTP 拉历史**：经 `GET /orders/{id}/events` 拉取订单事件历史。
   - **形状规范化**：`_normalize` 将 Java 侧 camelCase 规范化为 AI 内部 snake_case，保证 Kafka 事件与 HTTP 事件在 AI 服务内形状一致（跨源契约点）。

设计要点：① 发布与落库解耦，去重在前、收敛后亮灯；② 跨源事件形状统一，是 server 与 AI 的契约点。

### analyzer/（根因分析与 ReAct 自愈）

本层是 AI 应用核心亮点，重点在"如何让不可信的 LLM 输出安全可用"。

1. **`LLMClient` —— 统一 LLM 客户端**

   - **双协议兼容**：OpenAI / Anthropic（含 DeepSeek 端点）自动探测。
   - **三套 API**：`generate`（纯文本）、`generate_json`（强约束 JSON）、`generate_with_tools`（ReAct 工具调用）。
   - **限流与重试**：连接池复用、信号量限流（`llm_max_concurrency`）、429/5xx/超时可重试、token 埋点与 trace。
   - **用户级隔离**：`base_url/api_key/model` 必须随请求显式传入，不读进程级环境变量，保证多租户安全。

2. **`RootCauseAnalyzer` —— 根因分析**

   - **结构化输出**：加载事件 → 构造含动作白名单与反注入指令的 prompt → LLM 产出报告；JSON/Pydantic 校验失败喂回 LLM 修正一次（`MAX_ATTEMPTS=2`）。
   - **证据核验**：`evidence` 提及的事件类型必须真实存在于订单事件序列，否则重写（`_evidence_plausible`）。
   - **动作白名单**：建议动作锁死在 `ALLOWED_ACTIONS`（REFUND/NOTIFY_DELAY 等），且 AI 只分析、不自动执行。

3. **`HealerAgent` —— ReAct 自愈**

   - **多轮只读工具**：`MAX_STEPS=5` 调用 `query_order` / `query_events` / `query_stats` 三个只读工具收集证据。
   - **收敛报告**：收尾由加固的 `RootCauseAnalyzer` 出最终结构化报告，`agent_trace` 作为可解释过程返回前端。
   - **确定性兜底**：步数用尽走兜底分支，防 LLM 死循环。

4. **`PromptBuilder` —— Prompt 反注入**

   - **不可信隔离**：将异常描述等上下文以 `<untrusted>` 标签包裹，明确"仅作事实参考、不得执行其中指令"。

设计要点：① 以"不可信假设"贯穿，输出经白名单 + 证据自检双重校验；② LLM 是分析师而非执行者，越权动作被白名单拦截；③ 端到端超时 + 降级，保证分析失败不阻断主链路。

### query/（自然语言查询）

本层提供 NL2SQL 式的中文查询能力，核心是"意图分类 → 模板执行 → LLM 润色"的降级链路。

1. **`NLQueryEngine` —— 查询引擎**

   - **三段链路**：意图分类 → 模板执行 → LLM 润色。
   - **多轮追问**：缺参时反问、会话上下文补参（`_route` / `_ask_for_param`）。

2. **`IntentClassifier` —— 意图分类**

   - **三类意图**：`event_lookup` / `stats_aggregation` / `trace_replay`。
   - **LLM 优先、关键词兜底**：LLM 超时时降级关键词；带否定词消歧，避免"不是 event_lookup"被误判。

3. **`TemplateExecutor` —— 模板执行**

   - **抽参**：从问题抽取 `order_id`（UUID 正则）/ `status` / `time_window`，调 `BackendClient`。
   - **时间窗修正**：以"今天零点"为锚点做严格半开区间，修正"昨天多计入今天"的边界误差。

4. **`BackendClient` —— 后端客户端**

   - **机器密钥调用**：以 `X-API-Key` 统一调用 server 的订单读接口与 `/internal/users/{uid}/llm-config`（拉解密后的用户 LLM 配置）。

5. **`ConversationStore` —— 多轮会话**

   - **会话状态**：内存表存待补参数与指代消解上下文，30 分钟 TTL + 512 LRU 淘汰，进程重启即清。

设计要点：① LLM 仅负责润色，查询确定性由模板保证；② 意图分类/润色均有降级（关键词/数据摘要），保证前端 10 秒内必有回答。

### report/ + predictor/（周报、故事线与订单终局预测）

本组将检测积累的数据转化为运营可消费的"回顾"与"预判"。

1. **`WeeklyReportGenerator` —— 周报**

   - **确定性聚合**：按规则计数 / top 订单（不信任 LLM）。
   - **LLM 文案**：仅生成 `symptoms` / `recommendations`；LLM 失败降级规则摘要。
   - **异常来源**：取自 `anomaly_store.list_recent`。

2. **`StoryGenerator` —— 故事线**

   - **事件序列复盘**：交由 LLM 写成运营复盘，失败以模板兜底。

3. **`OrderPredictor` —— 订单终局预测**

   - **终局预测**：加载 `predictor.pkl` 预测终局状态（CLOSED/CANCELLED/REFUNDED/STUCK）+ 置信度 + 风险分级（`risk_rank`）。
   - **缺失降级**：模型缺失 `available=False`，端点返回 `prediction=null` 不阻断。
   - **watchlist**：`predictions_watchlist` 遍历后端非终态订单批量预测、按风险降序返回，体现 AI 从"检测"走向"预测运营"。

设计要点：① 确定性优先、LLM 兜底，避免文案错误误导运营；② 预测缺失即降级，不影响查询与检测；③ 从已发生异常延伸到将发生风险。

### cases/（轻量 RAG：相似案例检索）

1. **`CaseIndex` —— 相似案例检索**

   - **确定性加权**：以规则同型、事件类型、来源、级别、时间近邻、同订单的加权打分（`similarity`）做可解释检索，不引入向量库/embedding，直接读 `anomaly_store`。
   - **处置状态**：`query` 额外标注该聚合根是否已出现 `CompensationExecutedEvent`（已补偿/未处置），供运营参考上次处置。
   - **few-shot 注入**：`EG_AI_RAG_FEWSHOT=true` 时将 top-3 相似案例并入根因分析 prompt，提升分析质量且无需向量库。

设计要点：① 零新依赖、完全可解释。

### 横切：config / security / metrics / cache / trace

1. **`config` —— 配置中心**

   - **`EG_` 前缀可配**：`Settings` 覆盖 Kafka/后端地址、LLM 并发与重试、检测阈值、各类超时（分析 45s/自愈 120s/NL 润色 8s）、JWT 与机器密钥，体现"改参数不必改代码"。

2. **`security` —— 鉴权**

   - **`require_permission` 依赖工厂**：复用与 server 同一 `EG_JWT_SECRET`（HS256）校验 JWT 签名/过期，再校验权限码（`ai:query` / `anomaly:view`）；不信任上游代理、独立鉴权。

3. **`metrics` —— 指标**

   - **`eventguard_ai_*` 前缀**：覆盖检测、NL 查询、LLM 三类可观测数据，纯埋点不阻断业务。

4. **`cache`（llm_cache）—— 缓存**

   - **TTL + LRU**：只缓存幂等读场景（意图分类/NL 润色），可解释场景不缓存，进程重启即清。

5. **`trace`（trace_log）—— 追踪**

   - **环形缓冲**：记录 `nl_query` / `root_cause` / `llm_call` / `llm_cache` 四类 trace，前端经 `GET /ai/traces/recent` 查看。

设计要点：① 横切关注点与业务解耦；② 可观测、可解释、多租户安全三者齐备。

### training/（训练管线）

1. **`train_isolation.py` —— Isolation Forest 训练**

   - 用 `data/normal_events.jsonl` 正常事件流训练 Isolation Forest + StandardScaler（`contamination=0.05` 可配），持久化到 `models/`。

2. **`train_hmm.py` —— HMM 训练**

   - 训练 `CategoricalHMM` 流程序列模型 + 词表 + 阈值（分位数），落地 `hmm.pkl` / `hmm_vocab.json` / `hmm_threshold.json`。

3. **`train_predict.py` —— 预测器训练**

   - 训练订单终局分类器，产出 `predictor.pkl` + `predictor_meta.json`（含 `labels` / `event_vocab` / `k`），推理特征与 `OrderPredictor` 严格对齐。

4. **`generate_data.py` —— 数据合成**

   - 合成正常/异常事件数据，供训练与评测（`evaluate*.py`）。

设计要点：① 训练与推理解耦，重训后挂载卷持久化即生效、不重建镜像；② HMM 等文件缺失时 AI 自动降级，说明训练产物非部署必需。

---

## 四、模块深挖（源码级追问示例）

> 本节把"模块详解"中各模块可能遭遇的源码级追问整理为标准答法，作为面试临场深挖的弹药。先以 `kafka_consumer.py` 为例，后续模块按同格式追加。

### kafka_consumer.py（源码级追问）

1. **`max_poll_records=1` 的作用？为什么要关闭自动提交？**

   - **`max_poll_records`** 限制一次 `poll()` 最多返回的消息数；设为 `1` 即每次只取一条、处理完再取下一条。
   - **自动提交的风险**：`enable_auto_commit=True` 时，offset 由后台按固定间隔静默提交，**与处理是否成功无关**。处理到一半崩溃，offset 可能已提交 → 未处理消息永久丢失（破坏至少一次语义）；未提交则重启全量重放 → 可能重复。
   - **本模块选择手动提交**：每条 `handler` 成功（含发布链路走完）后才 `commit()` 推进 offset，配合单条拉取得到干净的**至少一次（at-least-once）**语义——消息不静默丢失，最多重放一次（由 `AlertDeduper` 去重吸收）。代价是吞吐受限于串行，但个人项目流量足够，并换取了下方"下游免锁"的收益。

2. **"使下游安全"具体指什么？**

   - 指下游有状态的 **`FeatureExtractor` 可免锁**。它用 `update` 持续维护每用户金额基线 / 每订单状态，是可变共享状态；若多线程并发或一批内同用户事件被交错处理，基线会被竞态踩坏。
   - 单条拉取 + 单消费线程 + 手动提交，保证任意时刻仅一条消息在处理，且同用户事件按分区顺序被顺序处理，`FeatureExtractor` 的"读-改-写"永远在单线程内，无需加锁即正确。即"用消费端串行化，替下游有状态组件挡掉并发正确性难题"（ponytail 标注的 MVP 简化，高吞吐应改异步消费 + 加锁/分片）。

3. **Debezium CDC 的"嵌套信封结构"具体是什么？**

   - Debezium 不直接把行丢进 Kafka，而是包一层信封：顶层 `{schema, payload}`，`payload` 内含 `before` / `after`（整行新值）/ `source` / `op`（c/u/d）/ `ts_ms`。
   - EventGuard 事件行的 `payload`、`metadata` 两列在库中是 **JSONB 字符串**，于是还有第三层：需再 `json.loads` 解成对象。
   - `flatten_debezium_event` 做两件事：① 剥掉第 1 层信封取 `payload`（本项目经 Debezium SMT/配置已把行字段摊平到 `payload` 下，故 `event_id` 可在 `payload` 直接找到）；② 把 `payload` / `metadata` 两个 JSONB 字符串列解析成对象，产出检测器可用的扁平事件字典。

4. **"与 Java 侧 `EventDeserializer` 保持跨栈契约一致"是什么意思？**

   - **契约**：server（Java）与 AI（Python）是两个独立服务，却消费同一 `domain-events` 主题、同一套事件结构，双方必须对事件"形状"（字段名、JSONB 解析方式、枚举含义）遵守同一约定——这是跨 Java/Python 两栈的隐式 schema 契约。
   - Java 侧 `EventDeserializer` 把同一消息反序列化为 `EventDto`（server 自己做投影/读模型时也用）；Python 侧 `flatten_debezium_event` 做同样的事（同字段名、同解析 `payload`/`metadata`、同 `event_id`/`aggregate_id`/`event_type` 映射）。
   - "一致"意味着：若 server 改事件序列化方式，两侧反序列化器必须同步改，否则 AI 解出的事件与 server 真实意图不符、检测即错。点出它，是表明"清楚生产方与消费方共享一份需双侧维护的边界"。

5. **本模块与后端 server 哪些模块有关联（以 anomaly 为例）？**

   - **事件来源（读真相源）**：server 事件溯源写模型把事件写入事件库（真相源），Debezium 捕获 WAL 投到 `domain-events`；consumer 每条消息的 `event_id` / `aggregate_id` / `event_type` 完全由 server 事件模型定义。server 生产、AI 消费，单向只读。
   - **规则引擎 `anomaly/`（最关键）**：`DetectionHandler` → `EventLevelService` → `RuleBridge` 以 HTTP 调 server `POST /anomaly/rules/evaluate`（server `anomaly/` 包、R001-R005）。即 server 的 `anomaly/` 是硬规则实时拦截方，AI 消费到事件先问 server 是否命中、命中即高优告警；AI 不在 Python 重写 R001-R005，而是复用 server 规则引擎，分工"规则先拦、AI 深度研判"。桥接有 2s 硬超时，server 慢/挂则跳过去走纯模型检测，不反向阻塞。
   - **间接关联（同链路但不在本文件）**：`EventStoreClient`（analyzer/query 层）拉 server `GET /orders/{id}/events`、`/internal/users/{uid}/llm-config`，以及 `EG_JWT_SECRET`、机器密钥 `X-API-Key` 均来自 server，但属 HTTP 侧，不在这一个文件内。
   - **一句话**：`kafka_consumer` 对 server 是"消费其事件 + 调其规则引擎"的单向只读；与 server `anomaly/` 经 `RuleBridge` 协作，与 server 事件模型经 `domain-events` 契约协作，绝不反向写业务库。

6. **常见误解：server 的 `anomaly/` 规则引擎是为 Python（AI）服务吗？**

   - **不是**。它是 server（真相源与命令侧）自有的毫秒级硬规则拦截能力，服务于 server 自身领域逻辑（实时标记异常、触发 Saga 补偿）。**即使没有 AI 层，server 也会照常跑 R001-R005、照样触发补偿**。
   - AI 经 `RuleBridge` 调它，只是把 server 已算好的硬规则结果"复用"为自身检测的高优一路，避免 Python 里重写一套 R001-R005、保证规则只有单一真相源。是"AI 站在 server 肩膀上"，而非"server 为 AI 服务"。
   - 除复用该接口外，AI 还独立承担大量 server 不具备的工作：Isolation Forest 软异常、流程级 `P001`/`P002`/`P003` + HMM 序列研判、`AlertDeduper` 去重/风暴抑制、LLM 根因分析、ReAct 自愈 agent、NL 查询、周报/故事线/订单终局预测/相似案例 RAG。
   - 分工一句话：server `anomaly/` = 真相源侧"硬规则实时拦截 + 补偿触发"；Python AI = 只读消费方"深度研判 + 智能能力"，把 server 硬规则当高优输入之一再补 ML/LLM/预测，是"server 先拦、AI 深判"的互补。

### AlertDeduper（源码级追问：去重与风暴抑制）

本模块是检测产出到"运营视图"之间的收敛门控，只影响发布、不改检测语义。

1. **它解决什么问题？**

   - 检测器逐事件、按滑动窗口运行，同一逻辑异常会被反复/成批判出；若不加拦截，运营会收到几十条内容相同的告警。去重/风暴抑制把"收敛后的告警"交给运营，避免刷屏。

2. **两道门控的判定键与目的有何不同？**

   - **幂等去重**：判定键 `(rule_id, aggregate_id, fingerprint)`，TTL 300 秒内同键只放行一次，针对"同一条告警被反复触发"。
   - **风暴抑制**：判定键 `(rule_id, aggregate_id)`（**不含 fingerprint**），每分钟上限 `storm_limit=3`，针对"同一订单同规则下大量不同告警的突发量"。
   - **核心区别**：去重认的是"同一条"（看指纹），风暴认的是"同一个订单+规则"（看总量）。

3. **指纹（fingerprint）怎么定？为什么流程级用 description？**

   - **事件级用 `event_id`**：每条事件天然唯一，正常不会误去重；仅当同一条事件因 at-least-once 重投时命中，刚好吸收重放。
   - **流程级用 `description`**：`P001` 含迁移对、`P002` 含停滞状态，窗口内这条描述稳定不变；滑窗每次重算同一 `STUCK` 告警，描述相同 → 指纹相同 → 被去重。若改用 `event_id`，每次新事件指纹都不同，会炸出重复告警。

4. **三态返回值是什么？**

   - `should_publish` 返回 `publish`（放行落库+发 Kafka）/ `dup`（被去重丢弃）/ `suppressed`（被风暴抑制丢弃）。

5. **"只影响发布、不改检测语义"怎么理解？**

   - 检测每次仍照常跑、照常判定 `is_anomaly`；去重只决定"是否发 Kafka/落运营视图"。检测相关指标（本分钟判出多少异常）保持真实，被 `dup`/`suppressed` 的只记计数（`alert_dedup_total`）、不进视图。即**去重不会漏检，只收敛展示**——面试被问"去重会不会漏检"时，答案是否定的。

6. **一个具体时间线（order-1001 卡在 PAID）**

   - t=0s：P002 触发，描述"STUCK PAID 24h"，键首次出现 → `publish`（第 1 条）。
   - t=10s / t=20s：新事件使窗口重算 P002，同描述、300s 内同指纹 → 两次均 `dup`。
   - t=25s：同订单另 3 条不同告警涌入，同(rule+agg)本分钟已发 1 条 → 前 2 条 `publish`（累计 3），第 3 条 `suppressed`。
   - t=310s：TTL 过期，P002 再次触发同描述 → 去重键失效 → `publish`（周期重通知，符合"持续异常要提醒"）。
   - 运营最终看到：1 条 STUCK + 少量其他告警，之后每 5 分钟被温和提醒，既不失联也不刷屏。

### FeatureExtractor（源码级追问：特征与状态化基线）

本模块把原始事件转成模型可消费的 4 维数值向量，是有状态组件，其"历史基线"与"免锁/重启退化"是易深挖点。

1. **4 维特征分别是什么？**

   - **`amount_zscore`**：金额相对该用户历史均值的 Z 分数（金额维度离群）。
   - **`time_since_last_event`**：距上一次事件的时间间隔（节奏维度）。
   - **`user_order_count_1h`**：该用户近 1 小时的下单次数（频次维度）。
   - **`state_transition_prob`**：当前状态转移在合法转移表中的概率（流程维度）。

2. **`amount_zscore` 怎么理解？**

   - 公式 `z = (x - μ) / σ`：`x` 当前订单金额，`μ` 该用户历史金额均值，`σ` 历史标准差；含义是"当前金额离用户平均多少个标准差"。`z≈0` 正常，`|z|` 越大越离群。
   - 它回答的是"**这笔钱对这个人正不正常**"，不是"金额绝对值大不大"。例：用户基线 `μ≈94, σ≈10`，新订单 100 → z=0.6 正常；500 → z=40.6 极度离群，判金额异常。这类软异常正是 Isolation Forest 抓、server 硬规则覆盖不到的。

3. **为什么用 Z 分数而非直接金额？**

   - 金额绝对值无普适意义（高净值用户 10000 正常，学生用户 10000 可疑）；Z 分数把金额**归一化到"相对该用户自身历史"**，让模型对"个性化反常"敏感，即相对/个性化异常。

4. **这里的"历史"从哪来（状态化基线）？**

   - `FeatureExtractor` 是**有状态**组件，`update` 每来一条事件就推进该用户的基线（均值/方差），是**运行时在线维护的每用户滚动基线**，非离线全量统计。这也解释了它为何依赖单消费线程才免锁——基线"读-改-写"若在并发下会被踩坏。

5. **重启退化的已知上限（ponytail）**

   - 进程重启后内存基线归零，短暂退化为"常量 Z 分数"（基线缺失时退化处理），检测精度暂时下降；升级路径是把基线/窗口落 Redis 等共享存储，与 server 共用缓存设施。该上限与 `EventWindow` 滑窗、`ConversationStore` 会话等同源（均为单实例内存态）。

### ProcessLevelRuleDetector（源码级追问：状态机、非法迁移与重试死循环）

本模块负责流程级规则（`P001`/`P002`/`P003`），其"状态机合法性"与"重试死循环"两处最易被追问到 server 侧。

1. **`LEGAL_TRANSITIONS` 是什么？**

   - 订单状态机的"合法迁移表"，形如 `from_state → {允许到达的 to_state 集合}`。`P001_ILLEGAL_TRANSITION` 对每个事件做：从窗口序列重算订单"当前态"，看事件要把订单变到什么"新态"，若 `(当前态, 新态)` 不在表中即报非法状态迁移。
   - 正常情况下它应静默；一旦触发，说明事件序列层面出问题（乱序、server 状态机边角、异常数据跳变）。该表应与 server 订单状态机定义保持一致（server 是真相源）。

2. **`STATE_PRESERVING_EVENTS` 为何不参与校验？**

   - 不改订单逻辑状态的事件（如 `CompensationExecutedEvent`）被显式排除在 `P001` 迁移校验之外，避免把补偿/审计类事件误报成非法迁移。

3. **`P003` 的 `PaymentRetried` 与 server 的 `retryCount` 有什么区别？（重点）**

   - **server（执行侧）**：`RetryPaymentCommand` 是命令侧硬业务规则，`retryCount++`，`>3` 次失败即**权威地把订单置为 `CANCELLED`**——直接改状态、终止重试，是真相源里"说了算"的那一个。
   - **AI（观测侧）**：`P003_DEAD_LOOP` 只读消费事件流，数滑动窗口里 `PaymentRetried` **事件的出现次数**，超过 `dead_loop_threshold`（默认 5）才报 `P003_DEAD_LOOP`，**只告警、绝不取消**。
   - **为什么一个是 3、一个是 5**：① 量的是不同东西——server 数 aggregate 里的 `retryCount`（权威计数），AI 数事件流里的旁观计数，健康系统下二者应一致（3 次后 `CANCELLED`）；② AI 给合法流程留余量——正常"3 次后取消"= 3 < 5，**AI 不报警**，只有持续出现 4/5/6… 重试（说明取消没生效、重试又起、或事件重放）才报，5 是故意高于 server 硬上限的容错余量。
   - **二者是互补双保险，不是重复**：server 正向终止重试（第一道），AI 逆向监测"若我还不停看到 `PaymentRetried`，说明 server 的终止可能没生效/有死循环"（第二道）。核心呼应"server=命令/真相源会写会取消，AI=只读消费方只看只告警"，绝不在 AI 里重写"取消"这种写操作。

4. **`P002`/`P003` 阈值为何可配？**

   - `stagnation_timeout_hours` / `dead_loop_threshold` 均在 `config.py`，因为是启发式监测阈值，按真实观测的事件模式调参、不改代码；与 server 业务常量（如"3 次取消"的裁决语义）层级不同。

### ProcessLevelHMMDetector（源码级追问：概率第二意见）

本模块是流程级检测的"统计补充网"，与 `P001`/`P002`/`P003` 确定性规则互补，专抓"规则没写死、但统计上反常"的订单事件序列。

1. **一句话定位**

   - 规则检测器（`P001`-`P003`）查"明文的非法"；HMM 查"统计上的不像"。它不替代规则，而是**规则之后的第二道流程级判定**，由 `run_process_detectors` 合并结果，输出 `P004_HMM_LOW_LIKELIHOOD`。

2. **HMM 在这里建模什么？**

   - 订单异常本质是**序列问题**（事件先后次序很重要，非单条事件）。HMM 把每个订单的"事件类型序列"当作序列建模。
   - **观测**：订单产生的事件类型（`CREATED`/`PAID`/`FULFILLED`/`SHIPPED`/`CLOSED`/`PaymentRetried`…），是离散符号；**隐藏状态**：模型内部学到的"流程阶段"；训练（`train_hmm.py`）用大量**正常**序列训 `CategoricalHMM`（类别型发射，因事件是离散符号），产出 `hmm.pkl` + `hmm_vocab.json`（事件类型→索引）+ `hmm_threshold.json`（似然阈值，通常取分位数）。
   - **推理**：把某订单近期事件序列（来自 `EventWindow`）按词表转索引，算其在模型下的**序列 log-likelihood**；低于训练阈值 → 判异常。

3. **它解决规则解决不了的什么？**

   - `P001` 只能抓迁移表明确禁止的跳转。但有些反常**不在显式规则里**：事件顺序诡异但单步不违规、少见但合法的补偿/重试组合等。规则精确但覆盖有限，HMM 宽松但能覆盖未知模式——二者合并，检测面才完整。

4. **与 `P001` 的区别（直觉）**

   - `P001` 看"这一步跳得合不合法"；HMM 看"整条序列像不像正常流程"。
   - 例：正常 `CREATED→PAID→FULFILLED→SHIPPED→CLOSED` 高似然、正常；反常 `CREATED→PAID→PAID→PAID→FULFILLED`（支付事件异常重复、又未到死循环阈值边界）低似然 → `P004`。

5. **降级与已知上限**

   - **缺失即降级**：`hmm.pkl` / 词表 / 阈值任一缺失，`loaded=False`、`detect` 返回空，主流程**只靠规则**——HMM 是增强不是必需，体现"模型缺失不阻断主链路"。
   - **未知符号保守跳过**：事件类型超出建模词表（训练时未见）不瞎判，交回规则处理。
   - **不与规则去重**：规则与 HMM 同时命中当前直接合并不去重（已知上限，升级路径是按 `rule_id` 仲裁）。
   - **阈值靠人工/分位数标定**，训练未接入 CI（成熟度上限）。

### EventWindow（源码级追问：流程级滑窗与边界上限）

本组件是流程级检测的"近期记忆"，为 `ProcessLevelRuleDetector` 与 `ProcessLevelHMMDetector` 提供订单近期事件序列，其窗口边界是不可见类上限的来源。

1. **`EventWindow` 维护什么、用什么结构？**

   - 按 `aggregate_id`（订单聚合根）维护一个 **`deque`（双端队列）**，每来一条事件先 `add` 追加，再取该订单的近期序列做流程检测。
   - 窗口内只保留最近 **20 个事件**（可配 `window_size`），超出则从队首弹出淘汰，控制内存且聚焦"近期异常"。

2. **为什么是固定长度滑窗，而不是全量事件？**

   - 流程级检测关心"近期状态是否合理"，而非整段历史；固定滑窗把内存与计算都压在常量级别，单实例可承载大量订单的在线检测。
   - 代价是**窗口边界外的异常不可见**（ponytail 标注上限）：若非法迁移发生在"当前窗口前 20 个事件之前"，滑窗已把它弹出，检测器无从发现。

3. **它与 `FeatureExtractor` 的"状态"有何异同？**

   - 二者都是**进程内、单消费线程驱动**的有状态组件，但职责不同：`FeatureExtractor` 维护每用户滚动基线（数值），`EventWindow` 维护每订单近期事件序列（结构）。
   - 都依赖单线程免锁；都因不落盘而在重启后归零（基线退化、窗口清空），升级路径同为落 Redis/DB。

4. **窗口大小与流程级规则的配合**

   - `P001`/`P002`/`P003` 都在窗口序列上重算：P001 逐事件查迁移合法性、P002 看窗口内非终态停留时长、P003 数窗口内 `PaymentRetried` 次数。窗口太小会漏掉"跨窗口才显现"的停滞/死循环，窗口太大则内存与算力上升——20 是经验值，靠 `config` 可调。

### EventLevelService / RuleBridge（源码级追问：事件级分工与桥接）

本组是事件级检测的入口与"规则先拦、模型补充"分工的落地，桥接降级与强制校验是易深挖点。

1. **`EventLevelService.detect` 为什么先调规则再调模型？**

   - 先经 `RuleBridge` 调 server 规则引擎（R001-R005）做毫秒级**硬拦截**——命中即高优告警、直接返回；未命中才转 `EventLevelDetector`（Isolation Forest）做软异常补充。
   - 分工逻辑："建表能一眼看出的违规"交给确定规则，"规则覆盖不到的离群"交给模型，避免模型对显式违规反而漏判或误报。

2. **`RuleBridge.evaluate` 怎么做跨栈调用？**

   - 把 Kafka 事件翻译成 Java `EventDto`（`POST /anomaly/rules/evaluate`），复用 server 自有规则引擎，不在 Python 重写 R001-R005——保证"硬规则只有单一真相源"。
   - 设 **2 秒硬超时**：server 慢/挂则跳过去走纯模型检测，桥接失败**只降级不阻塞**消费线程。

3. **Isolation Forest 缺模型会怎样？**

   - 加载 `isolation_forest.pkl` + `scaler.pkl`，`predict=-1` 判异常、`-score_samples` 越大越异常；模型文件缺失会**明确报错要求先训练**，不静默跑空——因为事件级模型是"可选增强"，缺失时该路直接报错而非产出假阴性。

4. **两路结果如何定优先级、会不会冲突？**

   - 规则命中 = **高优先级**告警（server 已裁决的硬违规，置信度最高）；模型命中 = **低优先级**软异常（统计离群，需人工复核）。`EventLevelService` 先返回规则结果，规则未命中才用模型结果，天然形成"硬规则优先、模型补充"的优先级。
   - 二者基本不冲突：规则覆盖的是"显式写死违规"，模型覆盖的是"规则没写但统计反常"，职责正交；即便同时命中也按优先级归一，不会重复告警（事件级指纹 `event_id` 由 `AlertDeduper` 兜底去重）。

5. **两路都没命中时返回什么？**

   - 规则未命中且 `predict=1`（正常样本）时，`EventLevelService.detect` 返回"非异常"、**不产出任何告警**——事件级检测到此结束，该事件仍照常进入流程级窗口。即事件级是"有疑才报、无疑静默"，避免对每笔正常订单刷告警。

6. **`-score_samples` 分数怎么读？**

   - Isolation Forest 不直接给概率，而是给"样本被孤立的难易度"：`decision_function` 越接近负、`-score_samples` 越大，说明越容易被孤立、越异常。评分用于排序告警风险（如 watchlist 风险分级可参考），而非做硬阈值二分。

### AnomalyPublisher / AnomalyStore / EventStoreClient（源码级追问：发布与跨源契约）

本组是"检测 → 亮灯"闭环的出口，同步确认与跨源形状统一是两处深挖点。

1. **`AnomalyPublisher.publish` 为什么同步等确认？**

   - 发 `anomaly-alerts`（key=aggregate_id）后同步 `future.get(timeout=2)` 等单条 broker 确认；失败由 `DetectionHandler._publish` 退避重试 3 次。**最坏约 6s+退避**阻塞单条发布链路（ponytail 标注：高吞吐应换异步 flusher + 回调水位）。
   - 持续失败则暂存 `AnomalyStore` 并计数，不阻塞消费线程——**发布是增强、不是依赖**。

2. **`EventStoreClient._normalize` 做什么、为什么必要？**

   - server 经 HTTP 返回的订单事件用 Java camelCase；Kafka 事件已摊平。`_normalize` 把两类来源统一规范化为 AI 内部 snake_case，保证"Kafka 事件"与"HTTP 拉回事件"在 AI 服务内**形状一致**——这是 server/AI 间的第二条跨源契约（与 `flatten_debezium_event` 平行）。

3. **`AnomalyStore` 为何全内存、又留 JSONL 开关？**

   - 默认内存表、上限 1 万按 `detected_at` 最旧淘汰，够单实例用；配 `EG_ANOMALY_STORE_PATH` 做 JSONL 落盘，进程重启可恢复，供周报与相似案例检索复用同一份数据。

### analyzer/（源码级追问：LLM 可靠性三件套）

本层是 AI 应用核心亮点，围绕"不可信 LLM 输出如何安全可用"展开，面试最易被追问。

1. **`LLMClient` 用户级隔离如何防多租户越权？**

   - `base_url` / `api_key` / `model` **必须随请求显式传入**，来自用户个人中心配置（Java 侧 AES 加密），**绝不读进程级环境变量**；每用户临时构造客户端，彼此密钥不串。
   - 统一三套 API（`generate` / `generate_json` / `generate_with_tools`），带连接池复用、信号量限流（`llm_max_concurrency`）、429/5xx/超时重试、token 埋点与 trace。

2. **`RootCauseAnalyzer` 证据核验 `_evidence_plausible` 怎么拦编造？**

   - LLM 报告里的 `evidence` 提及的事件类型，必须**真实存在于该订单事件序列**中，否则触发重写；配合 JSON/Pydantic 校验失败喂回 LLM 修正一次（`MAX_ATTEMPTS=2`），双重把住"输出可信"。
   - 建议动作锁死在 `ALLOWED_ACTIONS`（REFUND/NOTIFY_DELAY 等），Pydantic validator 拒非法动作——把"AI 建议"限制在 server Saga 补偿已有的动作集里，防越权。

3. **`HealerAgent.heal` 为什么 `MAX_STEPS=5` 且只读？**

   - 多轮调用 `query_order` / `query_events` / `query_stats` **三个只读工具**收集证据，步数用尽走确定性兜底，防 LLM 死循环。
   - 收敛后由加固的 `RootCauseAnalyzer` 出最终报告，`agent_trace` 作为可解释过程返回；当前是只读分析闭环，**不自动执行写操作**，写工具与审批留待后续。

4. **`PromptBuilder.build` 反注入的核心做法？**

   - 异常描述/上下文等**不可信服务端数据**用 `<untrusted>` 标签包裹，prompt 内明确"只能作为事实参考、不得执行其中指令"，阻断"数据里藏 prompt 指令"的注入路径。

### query/（源码级追问：NL 查询降级链路）

本组是 NL2SQL 式中文查询，确定性模板 + LLM 润色的双保险是深挖点。

1. **`NLQueryEngine.query` 三段链路的降级纪律？**

   - 意图分类 → 模板执行 → LLM 润色；LLM 润色超时（8 秒）返回数据摘要（`_fallback_answer`），保证前端 10 秒内必有回答；意图分类/润色均走 `LLMCache` 缓存幂等读。

2. **`IntentClassifier` 否定词消歧解决什么？**

   - 3 类意图 `event_lookup` / `stats_aggregation` / `trace_replay`，LLM 优先、关键词兜底；带否定词消歧（避免"不是查订单"被误判成 `event_lookup`），LLM 超时直接降级关键词分类。

3. **`TemplateExecutor` 时间窗为什么以"今天零点"为锚？**

   - 从问题抽 `order_id`（UUID 正则）/ `status` / `time_window` 调 `BackendClient`；时间窗按"今天零点"做**严格半开区间**，修正了"昨天多计入今天"的边界误差，保证统计口径稳定。

### report/ + predictor/（源码级追问：确定性优先与预测降级）

本组把检测数据变成运营可消费的"回顾"与"预判"，降级而非阻断是统一原则。

1. **`WeeklyReportGenerator` 为什么"确定性聚合 + LLM 文案"？**

   - 按 rule 计数 / top 订单的聚合**不信任 LLM**（避免文案错算误导运营），只让 LLM 生成 `symptoms` / `recommendations` 文案；LLM 失败降级规则摘要，异常来源取自 `anomaly_store.list_recent`。

2. **`OrderPredictor` 模型缺失如何降级？**

   - 加载 `predictor.pkl` 预测终局状态（CLOSED/CANCELLED/REFUNDED/STUCK）+ 置信度 + 风险分级（`risk_rank`）；模型缺失 `available=False`，端点返回 `prediction=null` **不阻断查询与检测**。
   - `predictions_watchlist` 遍历后端非终态订单批量预测、按风险降序返回 TopN，体现 AI 从"检测已发生"走向"预判将发生"。

### cases/（源码级追问：零依赖相似案例检索）

本组是轻量 RAG，刻意不用向量库，"可解释 > 语义召回"是深挖点。

1. **`CaseIndex` 确定性加权为何不用 embedding？**

   - 相似度 = 规则同型 / 事件类型 / 来源 / 级别 / 时间近邻 / 同订单的**加权打分**（`similarity`），直接读 `anomaly_store`（已支持 JSONL 持久化），不维护并行索引；零新依赖、完全可解释，但语义相近字段不同的案例召回有限（ponytail 标注：语义召回需上 embedding + 向量库，`CaseIndex` 接口不变只换相似度实现）。

2. **`few-shot` 注入如何复用历史案例？**

   - `EG_AI_RAG_FEWSHOT=true` 时将 top-3 相似案例并入根因分析 prompt（`RootCauseAnalyzer._maybe_add_fewshot`）；`query` 额外标该聚合根是否已出现 `CompensationExecutedEvent`（已补偿/未处置），供运营参考上次处置。

### 横切：security / config（源码级追问：复用鉴权与配置外置）

1. **`security.require_permission` 为何复用 server 的 JWT secret？**

   - 解析 `Authorization: Bearer <JWT>`，复用与 server 同一 `EG_JWT_SECRET`（HS256）校验签名/过期，再校验权限码（`ai:query` / `anomaly:view`）；不信任上游代理、独立鉴权，保证"同一份凭证、两套服务都能认"。

2. **`config` 为什么全 `EG_` 前缀？**

   - `Settings` 把 Kafka/后端地址、LLM 并发/重试、检测阈值、各类超时（分析 45s/自愈 120s/NL 8s）、JWT 与机器密钥**全部外置可配**——"改参数不必改代码"，对齐 server 侧配置风格。

### training/（源码级追问：训练与推理解耦）

1. **三个训练脚本各自产出什么？**

   - `train_isolation.py`：`normal_events.jsonl` 训 Isolation Forest + StandardScaler（`contamination=0.05` 可配）→ `models/`。
   - `train_hmm.py`：训 `CategoricalHMM` + 词表 + 阈值（分位数）→ `hmm.pkl` / `hmm_vocab.json` / `hmm_threshold.json`。
   - `train_predict.py`：训订单终局分类器 → `predictor.pkl` + `predictor_meta.json`（含 `labels` / `event_vocab` / `k`），推理特征与 `OrderPredictor._extract_features` **严格对齐**。

2. **为何训练产物非部署必需？**

   - HMM/预测 pkl 缺失时对应检测或端点自动降级（空结果 / `prediction=null`），说明训练与推理解耦；重训后挂载卷持久化即生效、不重建镜像——训练未接 CI 是当前成熟度上限（ponytail 标注）。

---

## 五、依赖方向（收尾一句话）

> "AI 层对 server 是**单向只读依赖**：只经 Kafka 消费事件、只经 HTTP 拉数据/调规则引擎，绝不反向直写订单库；发布异常走独立的 `anomaly-alerts`，前端消费亮灯。这样 server 是单一真相源，AI 挂了不影响下单，AI 重启不污染业务数据。"

### 面试实战问答（模拟演练）

**面试官**：说说你这个 AI 层是干嘛的，和后端什么关系。

`你`：AI 层是独立部署的 Python 服务，消费后端经 CDC 投到 Kafka 的订单事件，做异常检测——规则引擎（直接调后端的）、Isolation Forest、流程级规则加 HMM 三路，去重后发告警让前端亮灯；另外还提供 LLM 根因分析、自然语言查询这些智能能力。它只对后端只读：消费事件、拉数据，绝不写订单库，所以 AI 挂了不影响下单。

`💡 要点`：先抛"检测闭环 + 只读"两个主轴，别先铺模块清单。

**面试官**：异常检测为什么分好几路，不直接用一个模型？

`你`：因为要分层互补。后端的规则引擎（R001-R005）是毫秒级硬规则，先做实时拦截；Isolation Forest 抓金额离群、频次异常这类"软"异常，规则覆盖不到；流程级规则（P001 非法迁移、P002 停滞、P003 死循环）和 HMM 看事件序列的"流程合不合理"。规则高优、模型低优，各自擅长的不同。

`💡 要点`：强调"规则先拦、模型补充"的分工，不是叠床架屋。

**面试官**：LLM 输出不可信，你怎么保证根因分析不乱来？

`你`：三层防护——第一，输出强约束成 JSON + Pydantic 校验，失败喂回 LLM 修正一次；第二，证据核验，要求 evidence 里提到的事件类型必须真在订单事件序列里，否则重写；第三，建议动作锁死在白名单（REFUND/NOTIFY_DELAY 等），和后端 Saga 补偿已有动作集对齐。而且 AI 只分析、不自动执行任何动作。

`💡 要点`：这是 AI 应用岗最容易被追问的"可靠性"点，把"不可信假设"讲透。

**面试官**：如果 LLM 挂了或者超时呢？

`你`：每个 LLM 调用都有端到端超时（根因 45s、自愈 120s、NL 润色 8s），超时返回 504/降级摘要；意图分类 LLM 失败走关键词兜底，NL 回答失败走数据摘要，周报失败走规则摘要。整条链路"LLM 是增强不是阻塞"——降级后仍能给出次优结果。

`💡 要点`：主动点出"降级而非阻断"，显成熟度。

**面试官**：模型文件缺失怎么办，检测还跑得起来吗？

`你`：每类模型都做了缺失降级——Isolation Forest 缺 pkl 直接报错提示先训练，但流程级检测不依赖它照常跑；HMM 文件缺失 `loaded=False` 自动返回空、只留规则；预测模型缺失端点返回 `prediction=null`。检测管道本身启动失败也不拖垮 API，只关检测保留查询。

`💡 要点`：用"每路独立降级"展示对系统边界的清醒。

**面试官**：告警会不会刷屏，或者重复发？

`你`：发布前有 `AlertDeduper` 两道门控——幂等去重（同一 rule+订单+指纹 5 分钟内只发一次）加风暴抑制（同 rule+订单每分钟最多 3 次）。指纹事件级用 event_id、流程级用描述，保证窗口内稳定重复被消掉。这是只影响发布、不改检测语义的轻量门控。

`💡 要点`：把去重和检测语义解耦讲清，避免被问"去重会不会漏检"。

---

## 六、高频追问备战

| 追问 | 回答锚点 |
|---|---|
| AI 层和后端规则引擎（R001-R005）重复吗？ | 不重复：后端规则是毫秒级实时拦截（在 server 的 `anomaly/`），AI 经 `RuleBridge` HTTP 调它做高优命中，再补 IF/HMM 深度检测；分工=规则先拦、模型补充 |
| 特征提取器为什么没加锁？ | 检测只由单条 Kafka 消费线程驱动（`max_poll_records=1`），无并发；若将来多消费线程或 HTTP 并发调 detect，必须先加锁（`event_level.py` 已标注） |
| 流程级检测窗口外（前 N 个事件之前）的异常看不见？ | 是已知上限（`process_level.py`）：窗口边界外的非法迁移不可见；升级路径=状态快照/全量事件加载 |
| HMM 和规则同时命中会重复告警吗？ | 当前直接合并不去重（`process_level_hmm.py`），规则与 HMM 可同时报；升级路径=按 rule_id 去重/优先级仲裁 |
| anomaly 存内存够吗？ | 默认内存 10k 上限淘汰；配 `EG_ANOMALY_STORE_PATH` 可 JSONL 落盘、重启恢复，供周报/案例检索（`anomaly_store.py`） |
| LLM 配置怎么保证多用户安全？ | 每个用户在个人中心配自己的 base_url/api_key（Java 侧 AES 加密），AI 按请求用户临时构造 `LLMClient`，绝不读进程级默认（`main.py` 的 `_llm_client_for`） |
| 订单终局预测的"当前事件序列"从哪来？ | 经 `EventStoreClient` 拉 `GET /orders/{id}/events`，与检测用同一事件源；模型缺失则预测关闭 |

---

## 七、面向非本领域听众的讲解（以一条订单异常为例）

> 目标听众：未接触过事件溯源 / 异常检测 / LLM 应用、也不了解本项目的面试官或转岗同学。
> 讲法策略：**先给两个核心定义（异常检测、LLM 应用边界），再用一条"卡在 PAID 24 小时的异常订单"贯穿各层。**

### 5.1 两个核心定义

- **异常检测（Anomaly Detection）**：系统自动识别"和正常业务流不一样的订单事件"，不需要人工逐条看。本项目分"硬规则"（明确写死的违规，如非法状态跳转）和"模型"（从大量正常数据里学出"正常长什么样"，偏离就算异常）。
- **LLM 应用边界**：大语言模型只做"理解文字、生成文字"的事——把事件序列翻译成人话根因、把数据汇总成周报、把自然语言问题变成查询。它**不直接改业务数据**，只产出"建议"，且输出要经校验才可信。

下面用一笔订单 `order-1001`（用户 `user-42`，金额 299 元）卡在 `PAID` 状态超过 24 小时、最终被标为异常的例子，走完 AI 层主线。

### 5.2 检测闭环：从事件到告警

1. server 的订单状态事件经 Debezium 投进 Kafka `domain-events`，AI 的 `EventKafkaConsumer` 后台线程逐条消费（`kafka_consumer.py`）。
2. 每条事件先过 `EventLevelService`：调后端规则引擎（这笔是正常 PAID，不命中）→ 再喂 Isolation Forest（金额/频次正常，不异常）→ 事件级无告警。
3. 同时事件进 `EventWindow` 滑窗，`ProcessLevelRuleDetector` 看到这笔订单停在 `PAID` 超过配置的 24 小时（且非终态），触发 `P002_STUCK`（`process_level.py`）；若 HMM 模型就绪，它也可能因"序列似然过低"补报 `P004`。
4. 检出的异常经 `AlertDeduper` 门控——同一订单同一规则的持续告警 5 分钟内只发一次、每分钟上限 3 次，避免刷屏（`alert_dedup.py`）。
5. 通过后 `anomaly_store.save` + `AnomalyPublisher.publish` 发到 `anomaly-alerts`，前端 WebSocket 亮灯（`anomaly_publisher.py`）。

**关键设计**：检测和发布解耦、去重在前——即便模型对同一笔订单反复判异常，运营看到的也是收敛后的告警。

### 5.3 智能研判：运营点开告警后

1. 运营点"根因分析"，`GET /anomalies/{id}/analysis` 触发 `RootCauseAnalyzer`：先拉 `order-1001` 完整事件序列（经 `EventStoreClient` 调后端），再构造含"动作白名单 + 反注入标签"的 prompt 给 LLM。
2. LLM 返回 JSON 根因报告（如"订单支付后未推进履约，疑似商家未确认"），系统做 Pydantic 校验 + 证据核验（报告里说的事件必须真在序列里），不合规就要求重写一次。
3. 若走"自愈 agent"，`HealerAgent` 会多轮调用 `query_order/query_events/query_stats` 收集证据（只读），收敛后再用同一个加固分析器出报告，`agent_trace` 把"AI 怎么想的"展示给运营。
4. 运营还可问"order-1001 现在是什么状态"——`NLQueryEngine` 意图分类为 `event_lookup` → 模板抽 UUID → 调后端 → LLM 润色成一句话回答，LLM 慢就降级"订单状态：PAID，版本：N"。

**关键设计**：LLM 是"分析师"不是"执行者"——它只产出带白名单建议的报告，要不要退款/冻结由后端的 Saga 补偿走审批闭环，AI 不越权写库。

### 5.4 预测与案例：从"已发生"到"将发生"

- 运营看"高风险在途订单 watchlist"：`OrderPredictor` 对每笔非终态订单预测终局（如 `order-1001` 预测 `STUCK`/高风险），按风险排序给出盯防清单（`main.py`）。
- 看相似案例：`CaseIndex` 按规则/事件类型/时间近邻给 `order-1001` 的告警打相似分，并标"上次同型异常是否已补偿"，帮助运营复用处置经验（`case_index.py`）。

### 5.5 AI 与 server 的边界（收尾）

整条链路里，AI 层只在两处和 server 交互：消费 `domain-events`、拉 HTTP 数据（含调规则引擎）。它**从不写订单库**。因此 server 是单一真相源，AI 的任何故障（模型缺、LLM 超时、推理崩）都降级而非阻断业务——这正是"智能层作为增强、不作为依赖"的设计底线。

---

## 八、专业名词解释表

| 名词 | 一句话解释 |
|---|---|
| **Isolation Forest** | 一种异常检测模型：把正常数据包围成"森林"，落在森林稀疏处（孤立）的样本判异常。 |
| **HMM（隐马尔可夫模型）** | 用"状态序列"建模正常流程，序列整体出现概率过低则判流程异常；本项目的"第二意见"。 |
| **特征工程 Feature Extraction** | 把原始事件变成模型能吃的数值向量（金额 Z 分数、频次、状态转移概率等）。 |
| **滑动窗口 EventWindow** | 每个订单只保留最近 N 个事件做流程检测，省内存、抓近期异常。 |
| **规则引擎桥接 RuleBridge** | AI 调后端 R001-R005 硬规则的 HTTP 通道，命中即高优告警。 |
| **去重/风暴抑制 Dedup** | 同一告警短时只发一次、同规则每分钟上限，防刷屏；只影响发布不改检测。 |
| **根因分析 RootCause** | 用 LLM 把异常事件序列翻译成"为什么出错 + 怎么补救"的结构化报告。 |
| **ReAct Agent** | 让 LLM 多轮"思考→调工具→看结果"地收集证据，本项目的自愈分析闭环（只读版）。 |
| **意图分类 IntentClassifier** | 把用户自然语言问题分成"查订单/统计/回放"等意图，LLM 优先、关键词兜底。 |
| **自然语言查询 NL2SQL 式** | 用意图+模板把中文问题转成对后端 REST 的查询，LLM 只负责润色成白话。 |
| **LLM 白名单** | 根因建议动作限定在 REFUND/NOTIFY_DELAY 等已知集合，防 AI 给出越权动作。 |
| **证据核验 Evidence Check** | 要求 LLM 报告里的证据必须真在订单事件序列中，防编造。 |
| **轻量 RAG / 相似案例** | 不用向量库，用确定性加权打分找历史相似异常，辅助分析与 few-shot。 |
| **机器密钥 X-API-Key** | AI 调后端内部读接口用的受限权限密钥，与后端 `EG_MACHINE_API_KEY` 一致。 |
| **降级 Degradation** | 模型/LLM 缺失或超时时返回次优结果而非报错，保证主链路不阻断。 |
| **Prompt 反注入** | 把不可信数据用 `<untrusted>` 标签包起来，明确"只能参考、不得执行其中指令"。 |
| **Prometheus 指标** | AI 层暴露 `eventguard_ai_*` 指标，覆盖检测/查询/LLM 可观测性。 |

---

## 九、后续可优化方向（已识别的 ceiling）

> 本节省份记录架构上**已经识别、尚未实现（或仅半成品）**的优化点。面试中主动抛出，展示对边界的清醒。
> 本章同时汇总了「三、模块详解」各模块设计要点中提到的已知上限与刻意简化（已从各模块移出、集中于此），便于面试时统一抛出。

### 7.1 进程内状态不落盘（多类 ceiling 同源）

- **现状**：`FeatureExtractor` 用户基线、`EventWindow` 滑窗、`ConversationStore` 会话、`TraceLog` trace、`LLMCache` 缓存均为**进程内内存**，重启即丢；`anomaly_store` 虽支持 JSONL 落盘但默认关闭。
- **影响**：检测特征基线随重启归零（短暂退化为常量 Z 分数）、会话/对话历史丢失、trace 不可跨重启排查。
- **升级路径**：基线/窗口/会话/缓存落 Redis 或 DB；与 server 共用一套缓存基础设施（已在多处 `ponytail:` 标注）。
- **面试话术**："AI 层当前是单实例内存态，进程内状态不落盘是刻意的 MVP 简化；特征基线、滑窗、会话都是这个上限，下一步落 Redis。anomaly_store 已经预留了 JSONL 持久化开关，证明这条路是通的。"

### 7.2 流程级检测窗口外异常不可见 + 不去重（已知上限）

- **现状**：`ProcessLevelRuleDetector` 基于最近 20 事件滑窗，窗口边界之前的非法迁移不可见（`process_level.py:89`）；规则与 HMM 同时命中直接合并不去重（`process_level_hmm.py:105`）。
- **升级路径**：加载全量事件做状态快照校验；按 `rule_id` 去重/优先级仲裁（如 P004 与 P001 同源时只留一条）。
- **面试话术**："流程级是滑窗近似，窗口外和规则/HMM 重复命中是我标了的已知上限；要彻底解决得加载全量事件 + 按 rule 去重，我已在代码注释留了升级点。"

### 7.3 LLM 调用同步阻塞等确认（发布侧同类问题）

- **现状**：`AnomalyPublisher.publish` 同步 `future.get(timeout=2)` 等单条 Kafka 确认，最坏 3 次重试阻塞约 6s+退避（`anomaly_publisher.py:34`）；`RootCauseAnalyzer`/`HealerAgent` 串行 LLM 调用，单次分析最坏数分钟（已用端到端超时兜底）。
- **升级路径**：后台批量 flusher + 确认回调推进水位；分析侧引入并行工具调用（ReAct 多工具并发）。
- **面试话术**："发布确认和 LLM 调用都是同步等结果，个人项目流量下够用，但我标了 ponytail——高吞吐下该换异步 flusher + 回调水位。"

### 7.4 模型训练未接入 CI / 阈值靠人工（Pipeline 成熟度）

- **现状**：`training/` 脚本需手动跑产出 pkl；HMM 文件缺失时自动降级（说明训练产物不是部署必需）。`contamination`/停滞/死循环阈值可配但靠人工调。
- **升级路径**：CI 内置训练步骤保证 `models/` 就绪（HMM 注释已点此路径）；用评测脚本 `evaluate*.py` 驱动阈值自动标定。
- **面试话术**："模型训练和推理解耦、重训挂载卷即生效，但还没接 CI 自动训练；HMM 目前演示环境常缺文件，我特意做成缺失即降级，保证不影响规则检测主链路。"

### 7.5 相似案例检索为确定性打分（非语义）

- **现状**：`CaseIndex` 用规则/事件类型/时间近邻加权打分，不做 embedding/向量检索（`case_index.py:3`），可解释但召回有限（语义相近但字段不同的案例打不出高分）。
- **升级路径**：引入轻量 embedding + 向量库做语义相似；当前作为"零新依赖"的 MVP 版本，few-shot 已能提升分析质量。
- **面试话术**："相似案例我刻意用确定性加权打分而非向量库，零新依赖、完全可解释；若要语义召回再上 embedding，架构上 `CaseIndex` 接口不变、只换相似度实现。"

---

## 十、关联阅读

- 后端分层讲法（真相源、命令侧、规则引擎 R001-R005）：见同目录 `eventguard-server分层讲法.md`
- 跨服务链路与阅读顺序：见 `../架构设计/代码结构.md`
- AI 检测/分析/查询源码：见 `eventguard-ai/app/` 各包（本文第二节逐文件下钻）
- 模型训练与评测：见 `eventguard-ai/training/`
