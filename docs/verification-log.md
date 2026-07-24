# M5.2 全栈部署冒烟验证日志

> **重要**：本机（开发机）无 docker daemon，以下为「在云服务器（已装 Docker）执行」的步骤与预期结果。所有「实际结果」均为 `PENDING`，需届时在云服务器执行后回填。
> 验证目标：`docker compose up -d --build` 拉起全栈后，逐服务冒烟 + 验证 Debezium CDC 链路（POST 订单 → 投影到 `order_view` → GET 可见）。

---

## 0. 环境与前置

- 云服务器需已安装 Docker + Docker Compose（v2 插件）。
- 仓库已含 `docker-compose.yml`、`.env.example`，及 `postgres-init/`、`debezium/conf/`、`eventguard-*/` 构建上下文。
- 端口约定：UI `3000`、Java/Server `8080`、AI `8000`、Postgres `5432`、Kafka `9092`（均为容器映射端口）。

### 步骤 0.1 准备 `.env`（仅首次 / 缺失时）

```bash
cd <repo-root>
cp .env.example .env
# 按需编辑 .env：EG_LLM_API_KEY / EG_LLM_BASE_URL（无 Ollama 时 LLM 走兜底逻辑）
```

- 预期：`.env` 存在，含 `POSTGRES_*`、`KAFKA_BOOTSTRAP`、`DB_*`。
- 实际：本仓库 `.env` 已存在且被 `.gitignore` 忽略，含上述变量（`DB_PASSWORD=eventguard`），**无需重新生成**，未提交。
- 实际结果（PENDING）：________________

---

## 1. 启动全栈

```bash
cd <repo-root>
docker compose up -d --build 2>&1 | tail -20
```

- 预期：各镜像构建 / 拉取成功；`eventguard-server`、`eventguard-ai`、`eventguard-ui` 由源码构建，`postgres`、`kafka`、`debezium` 使用官方镜像。
- 实际结果（PENDING）：________________

### 健康检查

```bash
docker compose ps
```

- 预期：6 个服务均 `healthy` 或 `running`：
  - `postgres`（healthcheck：`pg_isready`）
  - `kafka`（healthcheck：`kafka-topics --list`）
  - `debezium`（`running` 即够，CDC 连接 postgres/kafka 后无报错日志）
  - `eventguard-server`、`eventguard-ai`、`eventguard-ui`（`running`）
- 若某服务未 healthy，用 `docker compose logs <svc>` 排查。
- 实际结果（PENDING）：________________

---

## 2. 逐服务冒烟

### 2.1 UI `:3000/`

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3000/
```

- 预期：`200`；页面 HTML 含 `<div id="app">`（前端挂载点）。
- 实际结果（PENDING）：________________

### 2.2 Java `:8080/orders`

```bash
curl -s http://localhost:8080/orders ; echo
```

- 预期：`200`，返回 `OrderListResponse`（含 `orders`、`total`、`page`、`size` 字段）；首次为空时形如 `{"orders":[],"total":0,...}`。
- 实际结果（PENDING）：________________

### 2.3 AI `:8000/docs`

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/docs
```

- 预期：`200`（FastAPI 自动生成的 OpenAPI/Swagger 文档）。
- 实际结果（PENDING）：________________

### 2.4 Kafka topics

```bash
docker compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```

- 预期：包含以下 topic（依据实际配置 `debezium/conf/application.properties` 与代码）：
  - `domain-events` —— Debezium 捕获 `public.domain_events` 表后落地的 CDC topic（Server 的 `OrderViewProjection`、`DebugEventConsumer` 消费它）
  - `anomaly-alerts` —— AI 服务发布的异常告警 topic（Server 的 `AnomalyAlertConsumer` 消费它）
- 说明：需求简报中预期的 `order-commands` / `order-events` 在本仓库**实际未使用**，以 `domain-events` + `anomaly-alerts` 为准。
- 实际结果（PENDING）：________________

---

## 3. CDC 链路验证（核心）

```bash
curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"userId":"u-smoke","totalAmount":9.9}'
echo
sleep 2
curl -s "http://localhost:8080/orders" ; echo
```

- 预期（POST 返回）：`CommandResult` 记录 `{"success":true,"version":1,"error":null}`（HTTP 200）。
  - 说明：`CommandResult` 字段为 `success` / `version` / `error`，**不含 orderId**；orderId 由服务端自动生成（或请求体 `orderId` 字段传入），不回显在响应体。
- 预期（2 秒后 GET）：返回的 `orders` 非空，包含刚创建的 `u-smoke` / `9.9` 订单 —— 证明 `POST → domain_events 表 → Debezium → domain-events topic → OrderViewProjection 写 order_view → GET 可见` 链路通。
- 实际结果（PENDING）：POST 返回 = ________________；2 秒后 GET = ________________

---

## 4. 回填与验收

云服务器执行后，将各环节「实际结果（PENDING）」替换为真实输出；全部预期命中即视为 M5.2 冒烟通过。如有未命中项，附 `docker compose logs <svc>` 输出并提交排查。

---

## 5. M5.3 端到端功能验证

> 沿用 §0「本机无 docker，结果云服务器回填」约定。以下均需在云服务器已 `docker compose up -d --build` 且全栈 healthy 后执行。每步「实际结果」为 `PENDING`，执行后回填。
> **关键事实**：`POST /orders` 返回 `CommandResult{success,version,error}`，**不回显 orderId**（见 §3 说明）。为让后续步骤可复用同一订单，创建时客户端自行生成 `orderId` 并随请求体传入（控制器支持 `orderId` 字段）。

### 步骤 5.1 注入订单全生命周期（二选一）

**方式 A（合成数据，含异常序列）**
```bash
cd eventguard-ai && python training/generate_data.py
```
- 预期：向 Kafka 发送 normal + anomaly 事件序列，`/orders` 列表出现对应订单，`/ws/anomalies` 可收到告警。

**方式 B（手动驱动完整链路）**
```bash
OID=$(python3 -c "import uuid;print(uuid.uuid4())")
curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
  -d "{\"orderId\":\"$OID\",\"userId\":\"u-e2e\",\"totalAmount\":199.0}"; echo
curl -s -X POST http://localhost:8080/orders/$OID/pay -H "Content-Type: application/json" -d '{"paymentId":"p1"}' >/dev/null
curl -s -X POST http://localhost:8080/orders/$OID/ship >/dev/null
curl -s -X POST http://localhost:8080/orders/$OID/deliver >/dev/null
curl -s -X POST http://localhost:8080/orders/$OID/close >/dev/null
echo "OID=$OID"
```
- 预期：create 返回 `CommandResult{"success":true,"version":1,"error":null}`；pay/ship/deliver/close 各返回 `CommandResult` 成功且 `version` 递增（最终 `CLOSED`）。
- 实际结果（PENDING）：create=________________；生命周期=________________；OID=________________

### 步骤 5.2 验证查询三接口

```bash
echo "== list ==" ; curl -s "http://localhost:8080/orders?page=0&size=20" ; echo
echo "== events(timeline) ==" ; curl -s "http://localhost:8080/orders/$OID/events" ; echo
echo "== stats ==" ; curl -s "http://localhost:8080/orders/stats" ; echo
```
- 预期：list 含 `u-e2e`/`199.0` 订单；events 返回按 `event_version` 升序的事件数组（≥5 条：Created→Paid→Shipped→Delivered→Closed）；stats 返回按 status 分组的计数，该订单为 `CLOSED`。
- 实际结果（PENDING）：list=________________；events 条数=________________；stats=________________

### 步骤 5.3 验证自然语言查询三类（AI `:8000`）

```bash
echo "== event_lookup ==" ; curl -s -X POST http://localhost:8000/ai/query -H "Content-Type: application/json" \
  -d "{\"question\":\"订单 $OID 当前状态是什么\"}" ; echo
echo "== stats_aggregation ==" ; curl -s -X POST http://localhost:8000/ai/query -H "Content-Type: application/json" \
  -d '{"question":"最近7天有多少订单"}' ; echo
echo "== trace_replay ==" ; curl -s -X POST http://localhost:8000/ai/query -H "Content-Type: application/json" \
  -d "{\"question\":\"订单 $OID 经历了哪些状态变更\"}" ; echo
```
- 预期：三类分别返回 `{intent,data,answer}`，`intent` ∈ {event_lookup, stats_aggregation, trace_replay}，`data` 取自对应后端接口（订单信息 / 统计 / 事件列表）。**无 Ollama 时**：intent 仍由关键词兜底正确分类，answer 退化为数据摘要（HTTP 200，不 500，M4.7/M4.5 优雅降级）。
- 实际结果（PENDING）：event_lookup=________________；stats_aggregation=________________；trace_replay=________________

### 步骤 5.4 验证异常看板链路（WS 抓 id + 根因）

> AI 服务**无** `GET /anomalies` 列表接口；异常仅经 WebSocket 推送（M3.8），根因走 `GET /anomalies/{id}/analysis`（M3.7）。需 `websocket-client`（`pip install websocket-client`）。若无异常到达，先跑方式 A `generate_data.py` 制造异常序列。

```bash
AID=$(python3 - <<'PY'
import json, websocket
ws = websocket.create_connection("ws://localhost:8080/ws/anomalies")
msg = json.loads(ws.recv()); ws.close()
print(msg.get("anomaly_id") or msg.get("anomalyId") or "")
PY
)
[ -n "$AID" ] && curl -s "http://localhost:8000/anomalies/$AID/analysis" ; echo
```
- 预期：从 WS 抓到 `anomaly_id`；`/anomalies/{id}/analysis` 返回 `AnalysisReport`（含 root_cause/evidence/suggestions）。前端 `AnomalyDashboard.vue` 连 `ws://<host>/ws/anomalies`（https 下推导为 `wss`，M4.7 已修），无协议被拦截。
- 实际结果（PENDING）：anomaly_id=________________；analysis=________________

### 步骤 5.5 验证补偿执行（合法 + 400）

```bash
echo "== 合法 REFUND ==" ; curl -s -X POST http://localhost:8080/compensations -H "Content-Type: application/json" \
  -d "{\"actionType\":\"REFUND\",\"aggregateId\":\"$OID\"}" ; echo
echo "== 非法动作 ==" ; curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/compensations \
  -H "Content-Type: application/json" -d "{\"actionType\":\"HACK\",\"aggregateId\":\"$OID\"}"
echo "== 缺 aggregateId ==" ; curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/compensations \
  -H "Content-Type: application/json" -d '{"actionType":"REFUND"}'
```
- 预期：合法 REFUND 返回 `success:true` 并写入补偿事件；非法动作 `HACK` → `400`；缺 `aggregateId` → `400`（白名单拒绝，控制器映射 `badRequest`，M4.7）。
- 实际结果（PENDING）：REFUND=________________；HACK http=________________；缺参 http=________________

### 步骤 5.6 时区一致性核对

```bash
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
FROM=$(date -u -d '8 days ago' +%Y-%m-%dT%H:%M:%SZ)
curl -s "http://localhost:8080/orders/stats?from=$FROM&to=$NOW" ; echo
```
- 预期：`from/to` 由 Python 以 UTC `isoformat()` 发送，Postgres `order_view.updated_at` 同为 UTC，统计计数与直觉一致（无 ±1 天偏移）。若发现偏移，记为 M5 收尾需修项（统一时区或文档说明）。
- 实际结果（PENDING）：stats(from/to)=________________；偏移=________________（无/有，描述）

### 步骤 5.7 提交验证结论

```bash
git add docs/verification-log.md
git commit -m "docs(m5.3): 端到端功能验证步骤与预期结果（含时区核对）"
```
- 实际结果（PENDING）：commit=________________
