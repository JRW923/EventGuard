# EventGuard 5 分钟 Demo 走查脚本（M5.5）

> 用途：面试 5 分钟现场演示的逐场景脚本。对应设计文档演示脚本与 `README.md` 功能清单。
> **重要**：mp4 录屏无法由 AI 生成，本文件是「演示脚本」而非视频。请按此顺序在本机
> `docker compose up -d --build` 起全栈后**实时走查并录屏**。每场景给出：操作（点击/命令）、
> 预期画面/返回、讲解要点（面试怎么说）。

前置：全栈已起且健康（UI `http://localhost:3000`、Java `8080`、AI `8000`）。
下列 curl 命令与 `README.md` 演示脚本一致（默认 API Key `changeme` 已在服务端校验）。

---

## 场景 1：创建订单 → 事件入库（事件溯源写入）

- **操作**：前端「订单列表」页点「新建订单」，填 `userId=u-demo`、`totalAmount=199.0`；
  或命令行：
  ```bash
  OID=$(curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
    -H "X-API-Key: changeme" \
    -d '{"userId":"u-demo","totalAmount":199.0}' \
    | python -c "import sys,json;print(json.load(sys.stdin)['orderId'])")
  echo "orderId=$OID"
  ```
- **预期**：返回 200 且带 `orderId`；订单列表出现该订单，状态 `PENDING_PAYMENT`。
- **讲解要点**：强调「每一步都是不可变事件」——`OrderCreatedEvent` 已 append 到
  PostgreSQL `domain_events`（事件溯源），而非直接 update 一张订单表；`UNIQUE(aggregate_id, event_version)`
  保证版本续接、并发安全。

## 场景 2：支付 → 状态流转（聚合根状态机）

- **操作**：点该订单「支付」；或：
  ```bash
  curl -s -X POST http://localhost:8080/orders/$OID/pay \
    -H "Content-Type: application/json" -H "X-API-Key: changeme" \
    -d '{"paymentId":"p1"}' >/dev/null
  ```
- **预期**：状态变为 `PAID`；订单列表对应行刷新。
- **讲解要点**：支付触发 `PayOrderCommand` → `PaymentCompletedEvent`，由 `OrderAggregate`
  状态机校验合法迁移（`PENDING_PAYMENT→PAID`）。非法迁移（如直接 SHIPPED）会抛异常——
  这是领域不变量在聚合根内强制的体现。

## 场景 3：注入异常 → 检测命中（规则 + ML 协同）

- **操作**：演示「异常从哪来」。最简方式：用合成异常数据驱动检测（README 已给）：
  ```bash
  cd eventguard-ai && python training/generate_data.py && cd ..
  ```
  并在前端「异常看板」说明两类命中：
  - **规则引擎**（高优先级）：金额偏离（Z-Score>3σ）、状态跳跃、5min 内重复支付等；
  - **IsolationForest**（低优先级）：无监督异常分数越界。
  也可口头补一句「也可直接 POST 一个偏离金额订单触发 R001 金额偏离规则」。
- **预期**：异常看板出现一条告警，标注命中规则/模型与 `anomaly_type`。
- **讲解要点**：检测是双通道——Java 规则引擎给可解释、高优先级判定；Python IsolationForest
  兜住规则未覆盖的未知模式。对应计划 M3.3/M3.4/M3.5。

## 场景 4：前端实时收到 WebSocket 告警

- **操作**：停留在「异常看板」，观察告警在异常注入后**实时**弹出（无需刷新）。
- **预期**：新告警以卡片/列表项形式出现，时间接近注入时刻。
- **讲解要点**：链路是 `domain_events → Debezium CDC → Kafka(domain-events) → AI 检测
  → Kafka(anomaly-alerts) → Spring 消费 → WebSocket(/ws/anomalies)` 推送前端。
  强调「事件驱动 + 实时推送」，而不是前端轮询。

## 场景 5：点开根因报告（根因分析）

- **操作**：点刚收到的告警 → 打开根因报告；或取 `anomaly_id` 后：
  ```bash
  # 取一个 anomaly_id（需装 websocket-client）：
  AID=$(python - <<'PY'
  import json, websocket
  ws = websocket.create_connection("ws://localhost:8080/ws/anomalies?api_key=changeme")
  msg = json.loads(ws.recv()); ws.close()
  print(msg.get("anomaly_id") or msg.get("anomalyId") or "")
  PY
  )
  [ -n "$AID" ] && curl -s "http://localhost:8000/anomalies/$AID/analysis" -H "X-API-Key: changeme" ; echo
  ```
- **预期**：报告含 `rootCause`（根因）、`evidence`（证据事件）、`suggestions`（建议动作，
  白名单内如 REFUND / NOTIFY_DELAY）。
- **讲解要点**：根因分析把异常相关事件 + 上下文喂给 LLM 生成结构化 JSON，建议被约束在
  白名单内（安全）。无 Ollama 时走关键词/摘要兜底，不阻断演示（ponytail：LLM 为可选增强）。

## 场景 6：NL 查询 + 事件时间线 + 执行补偿建议

- **操作 A（NL 查询）**：在「NL 查询」框输入 `订单 $OID 当前状态是什么`，回车：
  ```bash
  curl -s -X POST http://localhost:8000/ai/query -H "Content-Type: application/json" \
    -H "X-API-Key: changeme" \
    -d "{\"question\":\"订单 $OID 当前状态是什么\"}" ; echo
  ```
- **预期 A**：返回自然语言答案（意图分类 `event_lookup` → 调 `GET /orders/{id}` → 润色）。
- **操作 B（事件时间线）**：点该订单「时间线」，看到 `OrderCreatedEvent → PaymentCompletedEvent`
  的纵向时间轴（对应 `GET /orders/{id}/events`）。
- **操作 C（执行补偿建议）**：在异常根因报告的「建议」里点一个白名单动作（如 REFUND）执行：
  ```bash
  curl -s -X POST http://localhost:8080/compensations -H "Content-Type: application/json" \
    -H "X-API-Key: changeme" \
    -d "{\"actionType\":\"REFUND\",\"aggregateId\":\"$OID\"}" ; echo
  ```
- **预期 C**：返回 200，补偿命令被 dispatch（人工触发的可读描述；ponytail：当前不接真实支付网关）。
- **讲解要点**：收尾点题——NL 查询是「中文提问 → 意图分类 → 模板执行后端接口」而非裸 Text-to-SQL
  （安全沙箱）；事件时间线体现事件溯源的可回放性；补偿为人工触发白名单动作（Saga 自动编排是 V2 Roadmap）。

---

## 5 分钟节奏建议

| 时间 | 场景 |
|------|------|
| 0:00–0:50 | 场景 1 创建订单 |
| 0:50–1:30 | 场景 2 支付流转 |
| 1:30–2:30 | 场景 3 注入异常 + 检测双通道 |
| 2:30–3:20 | 场景 4 WebSocket 实时告警 |
| 3:20–4:10 | 场景 5 根因报告 |
| 4:10–5:00 | 场景 6 NL 查询 + 时间线 + 补偿 |

## 已知上限（ponytail，演示时如需诚实说明）

- AI 无 `GET /anomalies` 历史列表接口，异常仅经 WebSocket 推送，根因走 `GET /anomalies/{id}/analysis`。
- LLM 根因为可选增强，无 Ollama 时走兜底。
- 补偿为人工触发白名单动作，不接真实支付/通知网关，无 Saga 编排。
- 端点已加 `X-API-Key` 鉴权（默认 `changeme`），演示与生产务必改密钥；当前仍是单一静态密钥，
  无用户级认证/授权。
