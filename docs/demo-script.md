# EventGuard 5 分钟 Demo 走查脚本（M5.5）

> 用途：面试 5 分钟现场演示的逐场景脚本。对应设计文档演示脚本与 `README.md` 功能清单。
> **重要**：mp4 录屏无法由 AI 生成，本文件是「演示脚本」而非视频。请按此顺序在本机
> `docker compose up -d --build` 起全栈后**实时走查并录屏**。每场景给出：操作（点击/命令）、
> 预期画面/返回、讲解要点（面试怎么说）。

前置：全栈已起且健康（UI `http://localhost:3000`、Java `8080`、AI `8000`）。
下列 curl 命令先登录换取 JWT，再带 `Authorization: Bearer $TOKEN` 请求（RBAC 鉴权）。

```bash
# 登录换取 JWT（默认 OPERATOR 账号可下单/补偿；只读查询也可用 viewer）
# 注：种子账号首次登录会被强制改密，若已改过请用你自己的密码
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"operator","password":"operator123456"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
echo "token=$TOKEN"
```

---

## 场景 1：创建订单 → 事件入库（事件溯源写入）

- **操作**：前端「订单列表」页点「新建订单」，填 `userId=u-demo`、`totalAmount=199.0`；
  或命令行：
  ```bash
  OID=$(curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"userId":"u-demo","totalAmount":199.0}' \
    | python -c "import sys,json;print(json.load(sys.stdin)['orderId'])")
  echo "orderId=$OID"
  ```
- **预期**：返回 200 且带 `orderId`；订单列表出现该订单，状态 `PENDING_PAYMENT`。
- **讲解要点**：强调「每一步都是不可变事件」——`OrderCreatedEvent` 已 append 到
  PostgreSQL `domain_events`（事件溯源），而非直接 update 一张订单表；`UNIQUE(aggregate_id, event_version)`
  保证版本续接、并发安全。

## 场景 2：支付 → 异步回调 → 状态流转（聚合根状态机 + 网关异步）

- **操作**：点该订单「支付」；或：
  ```bash
  curl -s -X POST http://localhost:8080/orders/$OID/pay \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d '{"paymentId":"p1"}'
  ```
- **预期**：支付改为**异步意图+回调**——立即返回 `status=PAYMENT_REQUESTED` 与 `paymentId`，
  订单状态仍 `PENDING_PAYMENT`；mock 网关回调后订单变 `PAID`（配 `EG_GATEWAY_MOCK_PAYMENT_DELAY_MS` 可看到延迟）。
- **讲解要点**：
  - 支付触发 `PayOrderCommand` → `PaymentRequestedEvent`（意图，不改状态）→ `gateway_request` 落库(PENDING)
    → 调 `PaymentGateway` → 异步回调 `POST /gateway/callback/payment` → `CompletePaymentCommand` →
    `PaymentCompletedEvent`，由 `OrderAggregate` 状态机校验合法迁移（`PENDING_PAYMENT→PAID`）。
  - 这是**真实支付的形态**（支付宝/微信都是异步 webhook），不再是"命令即结果"。
  - 非法迁移（如直接 SHIPPED）会抛异常——领域不变量在聚合根内强制的体现。
  - 配 `EG_GATEWAY_MOCK_PAYMENT_FAILURE_RATE` 可演示支付失败 → `PAYMENT_FAILED` → Saga 自动补偿闭环。

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
  # 取一个 anomaly_id（需装 websocket-client）；WS 握手按 ?token= 校验 JWT
  AID=$(python - <<'PY'
  import json, websocket, os
  ws = websocket.create_connection("ws://localhost:8080/ws/anomalies?token=" + os.environ["TOKEN"])
  msg = json.loads(ws.recv()); ws.close()
  print(msg.get("anomaly_id") or msg.get("anomalyId") or "")
  PY
  )
  [ -n "$AID" ] && curl -s "http://localhost:8000/anomalies/$AID/analysis" -H "Authorization: Bearer $TOKEN" ; echo
  ```
- **预期**：报告含 `rootCause`（根因）、`evidence`（证据事件）、`suggestions`（建议动作，
  白名单内如 REFUND / NOTIFY_DELAY）。
- **讲解要点**：根因分析把异常相关事件 + 上下文喂给 LLM 生成结构化 JSON，建议被约束在
  白名单内（安全）。无 Ollama 时走关键词/摘要兜底，不阻断演示（ponytail：LLM 为可选增强）。

## 场景 6：NL 查询 + 事件时间线 + 执行补偿建议

- **操作 A（NL 查询）**：在「NL 查询」框输入 `订单 $OID 当前状态是什么`，回车：
  ```bash
  curl -s -X POST http://localhost:8000/ai/query -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"question\":\"订单 $OID 当前状态是什么\"}" ; echo
  ```
- **预期 A**：返回自然语言答案（意图分类 `event_lookup` → 调 `GET /orders/{id}` → 润色）。
- **操作 B（事件时间线）**：点该订单「时间线」，看到 `OrderCreatedEvent → PaymentCompletedEvent`
  的纵向时间轴（对应 `GET /orders/{id}/events`）。
- **操作 C（执行补偿建议）**：在异常根因报告的「建议」里点一个白名单动作（如 REFUND）执行：
  ```bash
  curl -s -X POST http://localhost:8080/compensations -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"actionType\":\"REFUND\",\"aggregateId\":\"$OID\"}" ; echo
  ```
- **预期 C**：返回 200，补偿命令被 dispatch；动作已接真实网关副作用（退款单号 / 通知落库 `notification_log`）。
- **讲解要点**：收尾点题——NL 查询是「中文提问 → 意图分类 → 模板执行后端接口」而非裸 Text-to-SQL
  （安全沙箱）；事件时间线体现事件溯源的可回放性；补偿为白名单动作，**高风险（退款 >100 元）自动挂审批**
  （`GET /approvals` 查待审，`POST /approvals/{id}/approve` 决策）。

## 场景 7：自动补偿（Saga 闭环，可选演示）

- **操作**：把 `.env` 的 `EG_GATEWAY_MOCK_PAYMENT_FAILURE_RATE` 调高（如 1.0）并重启 server；
  下单 → 支付 → 支付失败（`PAYMENT_FAILED`）→ 前端异常看板看到告警。
- **预期**：失败类事件（支付重试超限 / 库存预留失败）被 `SagaTrigger` 消费，自动触发
  REFUND + NOTIFY_DELAY（或 MARK_OUT_OF_STOCK），无需人工点补偿；`notification_log` 有记录。
- **讲解要点**：设计文档 7.4 的补偿编排（Saga）已落地——不再是"人工点按钮假成功"，
  而是事件驱动的自动补偿闭环，高风险步骤挂审批流。

---

## 5 分钟节奏建议

| 时间 | 场景 |
|------|------|
| 0:00–0:50 | 场景 1 创建订单 |
| 0:50–1:20 | 场景 2 支付异步回调流转 |
| 1:20–2:10 | 场景 3 注入异常 + 检测双通道 |
| 2:10–3:00 | 场景 4 WebSocket 实时告警 |
| 3:00–3:40 | 场景 5 根因报告 |
| 3:40–4:20 | 场景 6 NL 查询 + 时间线 + 补偿 |
| 4:20–5:00 | 场景 7 自动补偿（Saga 闭环） |

## 已知上限（ponytail，演示时如需诚实说明）

- AI 无 `GET /anomalies` 历史列表接口，异常仅经 WebSocket 推送，根因走 `GET /anomalies/{id}/analysis`。
- LLM 根因为可选增强，无 Ollama 时走兜底。
- 网关默认走 mock（`EG_*_PROVIDER=mock`）；支付为异步回调形态，真实 Provider（支付宝/企业微信）需在 `.env`
  配置凭证（未配置时优雅降级为失败原因）。Saga 实例为内存态，重启即清。
- 端点鉴权为登录 JWT（`Authorization: Bearer` / WS `?token=`），按角色授权（OPERATOR 可下单/补偿，
  VIEWER 只读）；演示用种子账号首次登录会强制改密。生产务必设置强随机 `EG_JWT_SECRET` 与 `EG_MACHINE_API_KEY`。
