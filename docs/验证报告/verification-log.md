# 全栈验证记录

> **收尾记录（2026-08-15）**：以下部署态步骤已在云服务器执行并回填；保留历史命令作为可复现实验入口。
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
- 实际结果：`.env` 已存在且未纳入版本控制，未重新生成，避免覆盖服务器现有配置。

---

## 1. 启动全栈

```bash
cd <repo-root>
docker compose up -d --build 2>&1 | tail -20
```

- 预期：各镜像构建 / 拉取成功；`eventguard-server`、`eventguard-ai`、`eventguard-ui` 由源码构建，`postgres`、`kafka`、`debezium` 使用官方镜像。
- 实际结果：全栈已运行；为修复 CDC poison event，本次只重建并替换 `eventguard-server`，未重启 PostgreSQL/Kafka，降低内存峰值。

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
- 实际结果：`postgres`、`kafka`、`debezium`、`eventguard-server`、`eventguard-ai`、`eventguard-ui` 均为 running；PostgreSQL/Kafka/Debezium 健康检查通过，Java `/health` 返回 `UP`。

---

## 2. 逐服务冒烟

### 2.1 UI `:3000/`

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3000/
```

- 预期：`200`；页面 HTML 含 `<div id="app">`（前端挂载点）。
- 实际结果：UI 根路径 HTTP `200`（生产镜像实际监听 `:80`，不是旧注释中的 `:3000`）。

### 2.2 Java `:8080/orders`

```bash
curl -s http://localhost:8080/orders ; echo
```

- 预期：`200`，返回 `OrderListResponse`（含 `orders`、`total`、`page`、`size` 字段）；首次为空时形如 `{"orders":[],"total":0,...}`。
- 实际结果：携带管理员 JWT 请求 HTTP `200`，返回字段含 `orders/page/size/total`；匿名请求按预期为 `401`。

### 2.3 AI `:8000/docs`

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/docs
```

- 预期：`200`（FastAPI 自动生成的 OpenAPI/Swagger 文档）。
- 实际结果：HTTP `200`。

### 2.4 Kafka topics

```bash
docker compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```

- 预期：包含以下 topic（依据实际配置 `debezium/conf/application.properties` 与代码）：
  - `domain-events` —— Debezium 捕获 `public.domain_events` 表后落地的 CDC topic（Server 的 `OrderViewProjection`、`DebugEventConsumer` 消费它）
  - `anomaly-alerts` —— AI 服务发布的异常告警 topic（Server 的 `AnomalyAlertConsumer` 消费它）
- 说明：需求简报中预期的 `order-commands` / `order-events` 在本仓库**实际未使用**，以 `domain-events` + `anomaly-alerts` 为准。
- 实际结果：包含 `anomaly-alerts`、`domain-events`、`domain-events.DLT`、`eventguard.public.domain_events`；未使用 `order-commands` / `order-events`。

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
  - 说明：当前实现返回 `success` / `version` / `error` / `orderId`；客户端也可在请求体显式传入 `orderId`，便于后续读己写验证。
- 预期（2 秒后 GET）：返回的 `orders` 非空，包含刚创建的 `u-smoke` / `9.9` 订单 —— 证明 `POST → domain_events 表 → Debezium → domain-events topic → OrderViewProjection 写 order_view → GET 可见` 链路通。
- 实际结果：POST 返回 `success=true,version=1,orderId=570838a1-9f21-4fec-860b-e09c2df64d33`；投影追平后 GET 返回该订单，证明 `domain_events → Debezium → domain-events → order_view` 链路可用。

---

## 4. 回填与验收

本次部署态冒烟已通过；期间发现并修复 `PaymentRetriedEvent` 历史注入数据使用 `attempt` 字段导致投影分区 poison event 的问题，修复后重启 Java 服务并确认积压追平。

---

## 5. M5.3 端到端功能验证

> 以下结果为 2026-08-15 云服务器实测；支付为 Mock 异步回调，AI 无真实 LLM 时走关键词/摘要降级。
> **关键事实**：当前 `POST /orders` 返回 `CommandResult{success,version,error,orderId}`；为让后续步骤可复用同一订单，仍建议客户端显式传入 `orderId`。

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
sleep 1  # 等待 Mock 支付回调完成
curl -s -X POST http://localhost:8080/orders/$OID/confirm >/dev/null
curl -s -X POST http://localhost:8080/orders/$OID/ship -H "Content-Type: application/json" -d '{"trackingNo":"trk-e2e"}' >/dev/null
curl -s -X POST http://localhost:8080/orders/$OID/deliver >/dev/null
curl -s -X POST http://localhost:8080/orders/$OID/close >/dev/null
echo "OID=$OID"
```
- 预期：create 返回 `CommandResult{"success":true,"version":1,"error":null}`；pay/ship/deliver/close 各返回 `CommandResult` 成功且 `version` 递增（最终 `CLOSED`）。
- 实际结果：create `success=true,version=1`；pay `PAYMENT_REQUESTED` 后回调为 `PAID@3`；confirm/ship/deliver/close 依次成功到版本 7，最终 `CLOSED`；OID=`570838a1-9f21-4fec-860b-e09c2df64d33`。

### 步骤 5.2 验证查询三接口

```bash
echo "== list ==" ; curl -s "http://localhost:8080/orders?page=0&size=20" ; echo
echo "== events(timeline) ==" ; curl -s "http://localhost:8080/orders/$OID/events" ; echo
echo "== stats ==" ; curl -s "http://localhost:8080/orders/stats" ; echo
```
- 预期：list 含 `u-e2e`/`199.0` 订单；events 返回按 `event_version` 升序的事件数组（≥5 条：Created→Paid→Shipped→Delivered→Closed）；stats 返回按 status 分组的计数，该订单为 `CLOSED`。
- 实际结果：list 含 `u-e2e-close/199.00`；events=`7` 条且顺序为 Created→PaymentRequested→PaymentCompleted→Confirmed→Shipped→Delivered→Closed；stats 中 `CLOSED.orderCount=5`。

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
- 实际结果：event_lookup=`event_lookup/CLOSED`；stats_aggregation=`stats_aggregation` 返回列表；trace_replay=`trace_replay/7条事件`；三类均 HTTP 200 且有 answer。

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
- 实际结果：本次 Java 重启后 `GET /alerts/recent` 为 0，未强行伪造 WS 告警结果；异常检测与告警链路已由同轮 bench `s03` 的 9/9 断言覆盖，需再次注入异常时再补抓 WS + 根因分析样本。

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
- 实际结果：为避免对演示订单产生真实副作用，合法 REFUND 未执行；非法动作 `HACK` HTTP `400`，缺 `aggregateId` HTTP `400`；Saga 两类合法补偿由 bench `s05` 的 10/10 断言覆盖。

### 步骤 5.6 时区一致性核对

```bash
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
FROM=$(date -u -d '8 days ago' +%Y-%m-%dT%H:%M:%SZ)
curl -s "http://localhost:8080/orders/stats?from=$FROM&to=$NOW" ; echo
```
- 预期：`from/to` 由 Python 以 UTC `isoformat()` 发送，Postgres `order_view.updated_at` 同为 UTC，统计计数与直觉一致（无 ±1 天偏移）。若发现偏移，记为 M5 收尾需修项（统一时区或文档说明）。
- 实际结果：UTC 时间窗统计返回 4 个状态行，`CLOSED.orderCount=5`；与订单 `updatedAt` 的 UTC 时间一致，未发现 ±1 天偏移。

### 步骤 5.7 提交验证结论

```bash
git add docs/验证报告/verification-log.md
git commit -m "docs(m5.3): 端到端功能验证步骤与预期结果（含时区核对）"
```
- 实际结果：本次验证结果随当前代码变更一并提交，提交号见仓库日志。

---

## 6. M5.8 收尾——全量测试结论

> M5.2/M5.3 部署态结果已在云服务器回填；本节保留单元测试历史记录，最新收尾结果见 §18。

### 6.1 三套单元测试实际运行结果

**① Python（eventguard-ai，`python -m pytest -v`）**

- 汇总行：`47 passed, 219 warnings in 2.94s`
- 结论：✅ 全绿（0 failures / 0 errors）。warning 均为 kafka/pydantic 弃用告警，不影响结果。

**② Java（eventguard-server，`mvn test`）**

- 汇总行：`Tests run: 76, Failures: 0, Errors: 0, Skipped: 4` + `BUILD SUCCESS`
- 跳过的 4 个为 `IdempotencyTest`(2) 与 `OrderConsistencyTest`(2)：均标注 `@EnabledIfSystemProperty(named = "eventguard.run.integration", matches = "true")` + `@Testcontainers(disabledWithoutDocker = true)`，本机无 Docker 默认跳过，符合预期。
- 结论：✅ 全绿（BUILD SUCCESS，单元/组件测试 0 失败 0 错误）。

**③ 前端（eventguard-ui，`npm run test` = `vitest run`）**

- 汇总行：`Test Files  5 passed (5)` / `Tests  16 passed (16)`，Duration 8.41s
- 输出中 `injection "Symbol(router)" not found` 为组件测试未挂载 router 的 Vue warn，非断言失败。
- 结论：✅ 全绿（0 failures / 0 errors）。

**总体**：三套单元测试均 ✅ 全绿，无 NEEDS_CONTEXT。

### 6.2 M5 已处理清单（Task 1–7 落地，对应 commit 与测试已绿）

| 项 | 任务 | 关键 commit |
|----|------|-------------|
| nginx 反向代理（生产 UI 镜像转发 API/WS 到后端） | M5.1 | `e833f73` |
| `ObjectMapper` 复用（OrderViewRepository 类级复用，消除重复构造） | M5.4 | `4ed67fa` |
| `EventItem` 类型收敛（前端 events 去 `any` 漂移） | M5.5 | `2f5bb4a` |
| `EventTimeline` 空列表守卫（空列表不渲染空表格 + 移除 LegendComponent + tooltip 空守卫） | M5.6 | `15b709d` |
| `.env.example` 补全 AI 变量 | M5.7 | `ec58fa4` |
| `README` 补全项目说明/演示脚本 + 修正 Testcontainers 集成测试数量为 2 个 | M5.7 | `ec58fa4`、`76b2e68` |

> 上述全部改动已随 M5.1–M5.7 提交至分支 `feat/m5-verification-polish`；本节的单元测试全绿即为这些打磨项未引入回归的证据。

### 6.3 未处理但已知的 MVP 上限（V2 范围，已在 AGENTS/简报中明确不做）

- **无端点鉴权**：MVP 定位内部 admin 工具，Spring Security / 网关鉴权留 V2。
- **AI 同步阻塞**：AI 服务 `httpx` 同步调用 LLM，未异步化（V2 用 `httpx.AsyncClient`）。
- **补偿 handler 非 Spring Bean**：补偿 handler 以 `new` 实例化，未托管为 Spring Bean（V2 接管）。
- **时区**：代码层 Python 以 UTC `isoformat()` 发送；本次部署态 `/orders/stats?from=&to=` 实测未发现 ±1 天偏移。

### 6.4 验证门禁命中情况（对照 DoD）

- [x] 三套单元测试全绿（pytest / mvn test / npm run test）—— 本节已验证
- [x] Task 4/5/6 打磨项落地且对应测试绿（EventItem/EventTimeline/ObjectMapper 改动均在全绿套件内）
- [x] README + `.env.example` 齐全（M5.7）
- [x] 已知 MVP 上限已文档化（§6.3），无遗留 Critical/Important 缺陷
- [x] 全栈冒烟 + 端到端（M5.2/M5.3）—— 云服务器已回填；期间修复并验证 CDC poison event 兼容性

> 结论：M5 单元测试与部署态冒烟/端到端均已闭环；50 并发 520 次目标版本负载、逐事件故障演练和真实支付沙箱仍属于未完成的目标版本验收，不在本次冒烟结论中宣称。

---

## 18. 2026-08-15 收尾闭环记录

### 18.1 本轮实际验证

| 项目 | 结果 |
|---|---|
| bench 功能评测 | s01–s08、s10 共 8 套，`80/80` 断言通过；限流保持开启，未运行高内存负载套件 |
| bench 纯函数测试 | `7 passed`；补充混沌图表返回值回归测试 |
| Java | Maven 构建内置测试 `170` 通过、`0` 失败、`4` 跳过；新增 `EventDeserializerTest` 覆盖旧 `attempt` 字段兼容 |
| AI | `127 passed`、`0` 失败 |
| UI | `12` 个测试文件、`39` 个测试通过；`vue-tsc --noEmit` 通过 |
| 报告产物 | `benchmark-report.md/json/html` 均生成；修复混沌图表 `list.append` 参数错误 |
| 端到端订单 | 7 条事件完整回放，读模型追平到 `CLOSED@7`；三类 NL 查询均 HTTP 200 |
| 时区 | UTC 时间窗统计实测无日期偏移 |

### 18.2 本轮发现并修复的根因

评测注入器和历史训练数据把 `PaymentRetriedEvent` 的次数字段写成 `attempt`，而 Java 正式事件 schema 使用 `retryCount`。该数据进入 CDC 后会使投影消费者在 poison event 上反复重试，阻塞同一分区的后续订单事件。现已：

1. 注入器改写正式字段 `retryCount`；
2. Java `EventDeserializer` 兼容历史 `attempt`，避免已有事实数据永久阻塞；
3. 重启后确认原隔离订单从 `PAID@3` 追平到 `CLOSED@7`。

### 18.3 资源边界

本轮未执行 `docker compose up -d --build` 全量重建和 50 并发负载；只重建 bench 与 eventguard-server。观测到 Kafka 约 `480 MiB`、Java 约 `218 MiB`、Debezium 约 `208 MiB`，宿主可用内存约 `1.1 GiB`，未叠加高并发任务。

### 18.4 仍未完成的目标验收

**520 样本读己写负载（2026-08-15 实测失败）**：临时关闭限流后，以 50 写并发、16 个读线程运行 `10s` 预热、`35s` 爬坡、`30s` 稳态。写路径共 `3207` 次迭代，`QPS=70.78`、写入错误率 `0%`，但 p95 为 `924.99ms`（目标 `<500ms`）；读己写采集 `534` 个样本，仅 `238` 个达到支付命令返回的目标版本且状态/金额匹配，`296` 次返回 `409`。结论：目标「50 并发下 520 次均在 2 秒内读到目标版本」**未通过**，本轮已恢复服务端限流。

第十五节中的 520 次目标版本负载、逐事件 Kafka/数据库故障演练、真实支付沙箱、异地备份恢复和真实告警接收渠道仍未完成；当前简历继续使用保守口径，不切换到第十四节目标版本。

---

## 7. 登录 + RBAC 权限管理系统（JWT 替换静态 API Key）验证

### 7.1 改动范围

- **后端**：`com.eventguard.auth` 包（JwtService / AuthFilter / @RequirePermission 拦截器 / JwtHandshakeInterceptor /
  LoginAttemptGuard / AuditLogger / Auth·User·Role 控制器）、`V3__auth.sql`（6 张表）、`AuthDataSeeder` 种子；
  既有控制器挂权限注解；删除 `ApiKeyValidator/AuthFilter/HandshakeInterceptor` 三件套。
- **AI**：PyJWT 校验同一 `EG_JWT_SECRET`，出站改 `EG_MACHINE_API_KEY`。
- **前端**：登录页 + auth store + 路由守卫 + v-permission 指令 + 用户/角色管理页 + WS `?token=`。
- **网关/部署**：nginx 透传 `Authorization`，`.env` 拆 `EG_JWT_SECRET`/`EG_MACHINE_API_KEY`，删 `VITE_API_KEY`。

### 7.2 单元测试

| 套件 | 结果 |
|------|------|
| `mvn test`（server） | 109 通过 / 0 失败 / 4 跳过（Testcontainers 本机跳过） |
| `pytest tests/`（AI） | 59 通过 / 0 失败 |
| `npm test` + `vue-tsc`（UI） | 24 通过 / 0 失败，type-check 通过 |
| `docker compose build` | server / ai / ui 三镜像构建成功 |

### 7.3 端到端验证（docker compose 全栈）

- 无 token 调 `/orders` → 401；`/auth/login` 校验 BCrypt，错误密码 5 次后第 6 次 429 锁定。
- 三角色矩阵：admin 全接口 200；viewer 读 200、下单 403、管用户 403；operator 下单 200。
- 机器密钥：读订单 200、下单 403（受限权限）。
- JWT 固定 HS256（修：jjwt 按密钥长度推断 HS384 与 AI 侧 HS256 不一致）。
- AI 经 nginx 转发 `Authorization`：无 token 401、admin `/ai/query` 200。
- WS 握手：含 `anomaly:view` 的 token 通过、无权限 token 拒绝。
- 改密流程：改后旧密码 401、新密码 200、`mustChangePassword` 置 false；admin 可重置他人密码。
- 修复两个既存问题：`/orders/stats` 带 from/to 时 Instant 无法绑定 SQL 类型（改 Timestamp）→ 200；
  `OrderViewProjectionTest` 重载 stub 错位（`anyString()` 命中 String 重载而生产走 Object 重载，改 `any(Object.class)`）。

---

## 8. 网关接入 A→D 端到端验证（2026-08-03）

### 8.1 单元测试

| 套件 | 结果 |
|------|------|
| `mvn test`（server） | 139 通过 / 0 失败 / 4 跳过（Testcontainers 本机跳过） |
| `pytest tests/`（AI） | 59 通过 / 0 失败 |
| `npm test`（UI） | 24 通过 / 0 失败 |
| `docker compose build` | server / ai / ui 三镜像构建成功 |

### 8.2 端到端验证（docker compose 全栈，新代码 + V4__gateway.sql）

- **AI 检测管道接通**：`GET /ai/health` 返回 `detector.running=true`（domain-events → anomaly-alerts → WebSocket 闭环首次真正运行）。
- **支付异步意图+回调**：`POST /orders/{id}/pay` 返回 `status=PAYMENT_REQUESTED` + `paymentId`（订单仍 PENDING_PAYMENT），mock 回调后订单变 `PAID`（事件流 OrderCreated→PaymentRequested→PaymentCompleted）。
- **库存不足 → R005 命中 → 告警**：`SKU-B` 数量 5 请求 10 → `InventoryReservationFailedEvent` → 规则引擎 R005 命中（rule_id=R005）→ anomaly-alerts topic 可见 → server 日志「收到异常告警」→ WebSocket 广播。
- **Saga 自动补偿（重试超限）**：fail-payment×4 + retry-payment×4 → `OrderCancelledEvent`(重试超限) → Saga 自动 REFUND + NOTIFY_DELAY（事件流 v10/v11 CompensationExecutedEvent，notification_log 落库）。
- **Saga 自动补偿（库存失败）**：库存不足 → 自动 MARK_OUT_OF_STOCK + NOTIFY_DELAY。
- **审批流挂起/恢复**：金额 200>100 退款 → `GET /approvals` 见 PENDING REFUND → `POST /approvals/{id}/approve` → Saga 继续执行至 COMPLETED，approval 表 APPROVED。
- **网关回调端点**：`POST /gateway/callback/payment` 无 X-API-Key → 401；nginx `/gateway/` 透传 X-API-Key 到后端。

### 8.3 端到端暴露并修复的问题（接线时发现，非计划内）

- **AI Kafka 消息是 Debezium envelope**：`kafka_consumer.py` 只 `json.loads` 未拆 `{schema,payload}`，导致 event_id/aggregate_id 为 None → 规则引擎 `UUID.fromString(null)` 500。修复：消费循环拆 envelope + 展平 JSONB 字符串字段。
- **AnomalyAlertConsumer 消息转换失败**：全局 value-deserializer 为 String，但监听器接收 `AnomalyAlert` POJO → `MessageConversionException` 告警静默丢弃。修复：改收 String 手动反序列化。
- **SagaTrigger 读金额竞态**：从 `order_view`（读模型）读金额，saga 消费组与投影消费组独立推进可能投影未跟上 → 读 0 → `requiresApproval(0)` 不触发。修复：改从 `domain_events` 事件库读 `OrderCreatedEvent.totalAmount`（事件库必然已落库）。
- **P001 误报补偿事件**：`CompensationExecutedEvent` 未入 Python 状态表被 P001 判非法迁移。修复：`STATE_PRESERVING_EVENTS` 跳过状态保留事件。

---

## 9. 生产就绪基建 P0/P1/P2 验证（2026-08-03）

### 9.1 P0 批次

- **P0-1 备份**：`scripts/backup-db.sh` 实际执行生成 40K 备份，`pg_restore --list` 读出 80 个 TOC 条目（含全部表）；`backups/` 已 gitignore。
- **P0-2 监控**：`/actuator/prometheus` 暴露 62 个 metric family；Prometheus target `eventguard-server` health=up；Grafana 数据源自动配置 Prometheus + Loki。
- **P0-3 错误追踪**：以 Loki 集中日志替代（见 P1-13）；Sentry 精确堆栈上报留待。
- **P0-5 404 页**：未知 hash 路由命中 catch-all 渲染 404（SPA hash 模式返回 index.html 200 属正常）。
- **P0-6 密码找回**：登录页「忘记密码」引导 + 管理员重置接口（`POST /users/{id}/reset-password`）。
- **P0-7 通用限流**：连续 65 次请求 `/orders` → 前 60 次 401、第 61–65 次 429（per-IP 滑动窗口生效）。
- **P0-8 审计日志**：`GET /audit-logs` admin 返回记录、operator 403（`user:manage` 权限隔离）。

### 9.2 P1 批次

- **P1-9 安全头 + gzip**：`/` 返回 X-Frame-Options / X-Content-Type-Options / Referrer-Policy；JS 资产 `Content-Encoding: gzip`。
- **P1-10 连接池**：HikariCP max=10 / min=2 / timeout=30s 显式声明（application.yml）。
- **P1-11 数据保留**：`scripts/retain-events.sh` dry-run 输出待归档 0 行（数据新）；归档逻辑同事务 COPY→DELETE。
- **P1-12 优雅停机**：Spring `server.shutdown=graceful` + compose `stop_grace_period` 35s/15s。
- **P1-13 集中日志**：Loki `/loki/api/v1/labels` 返回 container/service 标签；promtail 已加 Docker target；Loki query_range 能查到 eventguard-server 日志流。

### 9.3 P2 批次

- **P2-15 PWA/移动端**：`/manifest.webmanifest` 经 nginx 返回合法 JSON；`/` 首页含 manifest 链接。
- **P2-16 令牌管理**：登录拿 token → `/orders` 200 → `POST /auth/logout-all` → 同 token 访问 `/orders` 立即 401（token_version 递增吊销生效）；改密亦递增版本。
- **P2-17 CORS**：未配置 `EG_CORS_ALLOWED_ORIGINS` 时预检请求不返回 allow-origin（保持同源）；配置后注册映射（构造冒烟测试 3 例）。
- **P2-18 版本/健康**：`/health` 经 nginx 返回 `{"status":"UP","version":"0.1.0-SNAPSHOT","dependencies":{"db":"UP","kafka":"kafka:9092"}}`。

---

## 10. 评测模块（bench）验证（2026-08-03）

### 10.1 单元测试

| 套件 | 结果 |
|------|------|
| `mvn test`（server，含 OrderAggregate metadata 修复） | 150 通过 / 0 失败 / 4 跳过（Testcontainers 本机跳过） |
| `pytest tests/`（AI，含 prometheus_client 埋点） | 59 通过 / 0 失败 |
| `npm run test`（UI，未改动） | 24 通过 / 0 失败 |
| `pytest eventguard-benchmark/tests/`（bench 纯函数） | 6 通过 / 0 失败 |

### 10.2 埋点（可观测数据基础）

- **server**：新增 `common/metrics/EventGuardMetrics`（null-safe MeterRegistry），13 处埋点：
  `eventguard.command.duration/total`、`eventguard.saga.started/step.duration/final_status`、
  `eventguard.anomaly.alert.received`、`eventguard.anomaly.ws.connections`、
  `eventguard.ruleengine.evaluate.duration/hit`、`eventguard.payment.initiated/callback.duration`、
  `eventguard.ratelimit.rejected`、`eventguard.projection.event.processed`。
- **AI**：新增 `app/metrics.py`（prometheus_client）+ `GET /metrics` + 8 处埋点
  （`eventguard_ai_events_consumed_total`、`detection_latency_seconds`、`anomalies_published_total`、
  `publish_errors_total`、`rule_bridge_errors_total`、`nl_query_duration/total`、`detector_running`）。
- **Prometheus**：`prometheus.yml` 增加 `eventguard-ai` 抓取 job（`/metrics`）。

### 10.3 评测模块本身

- **bench 服务**：docker-compose `profiles:["bench"]`，`docker compose --profile bench run --rm bench`，
  产出 `eventguard-benchmark/out/benchmark-report.{md,json,html}` + `dashboard/eventguard-benchmark.json`（Grafana 导入）。
- **10 个套件**：s01 事件溯源/CQRS、s02 CDC 管道、s03 异常检测精度、s04 NL 查询、s05 Saga 补偿、
  s06 网关异步支付、s07 鉴权 RBAC、s08 限流、s09 负载、s10 韧性。
- **注入诚实性**：R001/R004/R005 为 rest；R002/R003/P002/P003 为 kafka_inject（DB 追加 + Kafka 直发，
  聚合状态机不可达），每条断言带 method 字段。
- **韧性**：`eventguard-benchmark/chaos_run.sh` 宿主机执行（bench 容器无 docker.sock），
  产出 `out/chaos-results.json` 供 s10 合并；已探测 bench 收敛密码与种子密码两种登录。

### 10.4 评测模块勘察暴露并修复的问题

- **R001/R004 规则上下文失效（既存 bug）**：`OrderCreatedEvent` metadata 为 null，
  而 `RuleContextLoader` 按 `metadata->>'userId'` 聚合用户历史 → 金额偏离/高频规则永不触发。
  修复：`OrderAggregate.handle(CreateOrderCommand)` 给事件补 `metadata={"userId":...}`。
  150 个 server 测试全绿证明无回归；该问题由评测模块的 R001 rest 场景设计暴露。

## 11. 一致性/可用性/安全性/体验度强化（2026-08-04）

四维加固（非业务复杂度），针对勘察发现的 6 个真实缺口逐项落地。

### 11.1 改动清单

| 项 | 维度 | 改动 | 关键文件 |
|---|---|---|---|
| RateLimitFilter 信任 X-Real-IP | 安全/体验 | nginx 只设 `X-Real-IP`、不透传 `X-Forwarded-For` → 原实现读 XFF 落空后回退 `remoteAddr`（nginx 容器 IP），**所有走 UI 的请求共享一个 60/10s 窗口**（一人刷爆全站 429，per-IP 假隔离）。改为优先读 `X-Real-IP`。 | `RateLimitFilter.java` + 2 测试 |
| AI 发布重试退避 | 可用性 | `_publish` 失败只 `inc()` 计数即丢告警；加 3 次退避重试（0.3/0.9/1.8s），broker 真宕机时不无限阻塞消费线程。 | `kafka_consumer.py` |
| 最近告警历史 + WS 补拉 | 可用性/一致性 | server 加 `RecentAlertsBuffer`（有界 100，最新在前）+ `GET /alerts/recent`（AuthFilter 鉴权）；nginx 加 `/alerts/` 反代；前端 WS 每次 open 后按 `anomaly_id` 去重补拉断线期间错过的告警。**补掉「无历史列表、断线告警永久丢失」已知上限。** | `RecentAlertsBuffer`、`AlertHistoryController`、nginx.conf、`useAnomalyWebSocket.ts` |
| Debezium 健康检查 | 可用性 | `/proc` 扫描探测 JVM 存活（纯 sh，不依赖镜像内 pgrep）；unhealthy 配合 `restart: unless-stopped` 拉起，避免 CDC 静默停转无人知。 | docker-compose.yml |
| Saga 启动重放恢复 | 一致性 | 审批落单时把「剩余步骤」写进 params 保留键 `__saga_remaining_steps`；`SagaRecoveryRunner` 启动时从 PENDING 审批单重建内存 saga 实例 → **重启后审批通过仍继续执行，不再因实例丢失 FAILED**。 | `CompensationSaga`、`SagaRecoveryRunner`、`ApprovalController`（视图过滤 `__` 键） |
| NL 查询超时降级 | 体验 | LLM httpx 超时 30s > 前端 axios 10s → 慢 LLM 时前端先中止显示「查询失败」。NL 回答路径加 8s `asyncio.wait_for` 上界，超时自动降级为数据摘要；前端加「正在分析…（自动降级）」loading 文案 + 超时友好提示。 | `nl_query_engine.py`、`NLQuery.vue` |

### 11.2 验证

| 套件 | 结果 |
|------|------|
| `mvn test`（server，含新增 8 个测试） | **158 通过 / 0 失败** / 4 跳过 |
| `pytest tests/`（AI，新增超时降级测试） | **60 通过 / 0 失败** |
| `npm run test`（UI） | **24 通过 / 0 失败** |
| `docker compose config`（debezium healthcheck） | 通过（`$$p` 转义正确，容器内解析为 `$p`） |

新增测试：`RateLimitFilterTest` 5→7、`RecentAlertsBufferTest` 新增 3、`AnomalyAlertConsumerTest` 3→4、
`CompensationSagaTest` 5→7、`test_nl_query_engine` 4→5。

### 11.3 说明

- **限流语义**：按 `X-Real-IP` 分桶（nginx 每请求覆盖写入，反代层内不可伪造）；server:8080 未对外暴露，伪造面可控。
  直接访问 8080 的内部服务（bench 等）无 X-Real-IP 时回退 `remoteAddr`。
- **告警补拉**：`/alerts/recent` 需有效 JWT；容量默认 100，可配 `eg.alerts.recent-capacity`。
- **Saga 恢复**：只重建「PENDING 审批单」对应的在途实例——这是重启唯一会丢的状态；已完成/已拒绝不受影响。
  `__saga_remaining_steps` 为保留键，`GET /approvals` 视图已过滤，不外泄给前端。
- **NL 降级**：无 Ollama 时 LLMClient 秒失败走摘要（原本就快）；有 Ollama 但响应 >8s 也走摘要，保证 10s 内必有回答。

## 15. 资源受限负载验收（2026-08-15）

### 15.1 优化与采集

- 投影使用独立 Hikari 池及事务管理器，主池/投影池保持总连接预算 10；最终配置为 8/2。
- 读己写轮询改为 50→100→200ms 有界退避；deadline 已过时返回 409，不再将负数传给 `Thread.sleep` 产生 500。
- s09 记录 `hikaricp_connections_pending` 窗口最大值，并以资源受限档位运行：30 写并发、8 个读己写抽样线程、150 样本。

### 15.2 验收结果

来源：`eventguard-benchmark/out/benchmark-report.md`，运行时间 2026-08-15T15:57:47Z。

| 断言 | 门槛 | 实测 | 结果 |
|---|---:|---:|---|
| 写入错误率 | <5% | 0.00% | PASS |
| 写路径 p95 | <2500ms | 1262ms | PASS |
| 读己写抽样 | >=150 且成功率>=65% | 110/157（70.1%） | PASS |
| 业务迭代吞吐 | >0 | 52.39/s | PASS |

该结果是 3.6GiB 单机的资源受限验收，不外推为 50 并发或生产容量承诺；47 次 409 为读模型未在 2 秒内追平时的预期降级响应，写入未失败。

---

## 12. AI 拓展八连发：被动检测 → 主动预测 → 自主处置（2026-08-08）

在既有「检测 → 告警 → 建议」被动链路上按顺序补齐 8 项 AI 拓展（M0 前置 + Item1-8，`ca61afb`..`32e77f5` + `16400b3`），主线升级为**可对话、可预测、可自主处置、可复盘**。

### 12.1 改动清单

| 项 | 内容 | 关键文件 |
|---|---|---|
| M0 · LLM 提供商适配 | Anthropic/OpenAI 双格式（`_detect_provider` 按 base_url 含 `/anthropic` 探测，`EG_LLM_PROVIDER` 可覆盖）；`generate_json`（OpenAI `response_format` / Anthropic 强约束+抽码）；`generate_with_tools`（归一化 `{id,name,input}`）。修复「默认 system 强制 JSON」潜伏 bug——本会破坏 NL 回答。 | `llm_client.py`、`config.py` |
| 1 · 多轮对话 | 缺参反问（`needs_input` + 会话 pending 槽）+ 补参续查 + 同会话上下文复用（TTL 30min + LRU 512）。 | `conversation_store.py`(新)、`nl_query_engine.py`、`query_result.py` |
| 2 · 告警去重/风暴抑制 | `AlertDeduper` 幂等门控（TTL 5min + 10k LRU + 风暴限 3/min）；事件级/流程级 publish 前过门控；看板「聚合模式」按 (规则,订单) 聚类。 | `alert_dedup.py`(新)、`kafka_consumer.py`、`metrics.py`、`AnomalyDashboard.vue` |
| 3 · LLM 输出可靠性 | 根因分析走 `generate_json` + 错误反馈重试（MAX_ATTEMPTS=2：JSON 解析 / Pydantic / 证据核验不通过即回喂修正）。 | `root_cause.py` |
| 4 · 可观测性 + LLM 缓存 | `LLMCache`（TTL 300s + LRU 256，key 含 provider/model/temp/prompt）；`TraceLog` 环形缓冲 200 条 + `X-Trace-Id`；指标 `llm_cache_hits/misses`、`llm_tokens{model,operation}`、`llm_call{provider,operation,ok}`。 | `llm_cache.py`(新)、`trace_log.py`(新)、`metrics.py`、`main.py` |
| 5 · 事件流终局预测（flagship） | 前缀采样训练 RandomForest（8 特征、`class_weight=balanced`，准确率 0.71，pkl 1.3MB）；`GET /ai/predict/{id}` + watchlist + 订单列表预测角标。**不自动发新告警**（保 s03 FP 口径）。 | `training/train_predict.py`(新)、`predictor/order_predictor.py`(新)、`models/predictor.pkl`、`OrderList.vue` |
| 6a · ReAct 分析闭环 | `HealerAgent`（TOOLS=query_order/query_events/query_stats、MAX_STEPS=5）`POST /ai/heal/{anomaly_id}` → 报告 + 分析过程 trace；看板对话框展示工具调用链。 | `healer_agent.py`(新)、`main.py`、`AnomalyDashboard.vue` |
| 6b · 补偿审批闭环 | Java `POST /compensations/saga`（机器密钥加 `compensation:execute`，白名单动作校验）+ 审批页 `Approvals.vue`（列 PENDING + approve/reject）。**写工具人工在环**：AI 建议 → 一键发 Saga → 高风险自动落审批单 → 人工决策。 | `CompensationController.java`、`AuthPrincipal.java`、`Approvals.vue`(新)、`compensation.ts` |
| 7 · 运营周报/故事线 | anomaly JSONL 持久化（`EG_ANOMALY_STORE_PATH`，Item 8 复用）+ `POST /ai/report/weekly` + `GET /ai/orders/{id}/story`；`AiReport.vue` 周报卡片 + 订单故事。 | `anomaly_store.py`、`report/weekly_report.py`(新)、`report/story_generator.py`(新)、`AiReport.vue`(新) |
| 8 · 相似案例检索 | 零依赖加权相似度（规则 0.5 / 事件 0.2 / 来源 0.1 / 时间衰减 0.15 / 同单 0.2）+ 处置状态（后端事件解析）；`GET /ai/cases/{id}/similar`；可选 `EG_AI_RAG_FEWSHOT=true` 注入 Item 3/6 prompt。 | `cases/case_index.py`(新)、`main.py`、`AnomalyDashboard.vue` |

### 12.2 验证

| 套件 | 结果 |
|------|------|
| `pytest tests/`（AI，含新增 ~30 用例） | **123 通过 / 0 失败** |
| `vitest run`（UI，12 文件） | **39 通过 / 0 失败** |
| `mvn test`（server 补偿模块） | 补偿相关 **15 通过** |
| 真实 LLM 端到端（DeepSeek Anthropic 端点） | 多轮三回、根因分析、ReAct agent 4 步工具调用、终局预测、周报统计、相似案例排序均通 |
| `vite build`（按需引入回归） | 通过，element-plus 分块 311KB(gzip 101KB)，`el-collapse-item` 深路径解析正常 |

### 12.3 关键修复（过程中抓到）

- **`predict_proba` 列与 `classes_` 不对齐**：预测结果张冠李戴，改为按 `model.classes_` 映射回标签（Item 5 单测抓到）。
- **Anthropic 要求多个 `tool_result` 合并进紧邻的同一条 user 消息**，否则 400（6a E2E 抓到）。
- **`EventStoreClient` camelCase 集成 bug（最隐蔽）**：后端 `/orders/{id}/events` 返回 `eventType`/`createdAt`，AI 内部是 snake_case → 根因/故事/预测一直拿到「?」事件；加 `_normalize()` 映射修复（影响 Item 5/7 与既有根因分析）。
- **HealerAgent 首条消息 role=system 被 anthropic 转换跳过** → 空 messages 400；改为显式 user 任务消息 + system 走顶层参数。
- **predictor.pkl 44MB → 1.3MB**：`max_depth=12, min_samples_leaf=5`（仓库可提交）。
- **LLM 适配 3-tuple 泄漏**：`generate_with_tools` 需剥掉 usage 只返回 `(text, tool_calls)`。

### 12.4 说明

- **偏离计划一处**：6b 的「写工具」以**人工在环**实现（AI 建议 → 白名单校验 → 前端一键发 Saga → 高风险自动审批 → 审批页人工决策），而非 agent 自主写补偿。更安全，符合设计文档 MVP 边界。
- **压测约束保持**：`s04` 的 `/ai/query` 形状（新字段带默认值）、`s03` 的 rule_id 语义与控制组 FP 均未改动；预测按需查询、不自动告警。
- **部署仍落后**：运行栈（AI 8/5、server 8/6、UI 8/7 镜像）均早于本节代码，需 `docker compose up -d --build` 才可见；2026-08-08 已构建新 AI 镜像未部署。同日清理 dev/测试冗余镜像并 prune 10GB 构建缓存。
