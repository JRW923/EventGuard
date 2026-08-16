# EventGuard：M5 验证与打磨实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 M1–M4 全部合并 main 之后，对 EventGuard 全栈做运行时端到端验证，修复验证中暴露的问题，收尾 M4 整体复审遗留的已知 Minor 打磨项，补齐生产部署配置与运行文档，最终完成全量测试，使项目达到可演示/可交付状态。

**Architecture:** 沿用 M1–M4 的分层架构（PostgreSQL + Kafka + Debezium CDC → Spring Boot 命令/查询侧 + Python AI 服务 + Vue3 管理前端）。M5 不引入新功能模块，只做三件事：(1) 用现有 `docker-compose.yml` 把全栈跑起来并逐接口/逐页面走查；(2) 修两类问题——M4 复审遗留的 Minor、以及部署配置缺口（生产 UI 镜像缺 nginx 反向代理）；(3) 补 README 与演示脚本。验证环境以云服务器为目标（用户此前决定本地 Docker 验证延后），但 docker compose 同样是本地可跑的同一套产物，步骤通用。

**Tech Stack:** Docker / docker-compose；PostgreSQL 16；Kafka 7.6 + Debezium 2.6；Spring Boot 3.3 (Java 17) + Maven；FastAPI (Python 3.11) + uvicorn + httpx + pydantic-settings；Vue3 + Vite + Element Plus + ECharts + axios + vue-router + vitest + @vue/test-utils + @vue/test-utils；nginx:alpine（UI 生产镜像）。

## Global Constraints

- 分支：`feat/m5-verification-polish`（从 main `495e596` 切出），每个任务结束 commit 一次，message 格式 `feat(m5.N): <描述>` 或 `fix(m5.N): <描述>`（中文）
- 遵循仓库 `AGENTS.md`（ponytail 懒人资深开发模式）：不写没被要求的东西；复用 M1–M4 现有代码与测试套件；不引入新依赖；MVP 已知上限加 `ponytail:` 注释并写明升级路径
- Python 模块 `app`；Java 包 `com.eventguard`；前端模块 `src`
- 接口签名严格遵循 M1–M4 设计文档，本次不改动对外契约（仅修复生产代理与内部打磨）
- 不推送、不合并到 main，除非用户明确要求
- 测试命令（三套，均需在各自模块目录运行）：
  - Python：`cd eventguard-ai && python -m pytest -v`
  - Java：`cd eventguard-server && mvn test`（4 个 Testcontainers 集成测试默认跳过，本地无 Docker 时无需理会；只跑单元测试）
  - 前端：`cd eventguard-ui && npm run test`（= `vitest run`）

## 已知问题清单（来自 M4 整体复审，M5 须处理）

1. **部署缺口（Critical for prod）**：`eventguard-ui/Dockerfile` 仅 `npm run build` + 拷 `dist` 到 nginx，但**没有 nginx.conf**，默认 nginx 不代理 `/orders`、`/ai`、`/anomalies`、`/compensations`、`/ws` → 生产 UI 所有 API/WS 请求会 404。须在 M5.1 补 nginx 反向代理。（本计划最高优先级）
2. `.env.example` 缺少 AI 服务变量（`EG_LLM_API_KEY` / `EG_LLM_BASE_URL` / `EG_SERVER_BASE_URL` 等，AI 侧 `app/config.py` 用 `EG_` 前缀）。
3. Java `OrderViewRepository.findEventsByAggregateId` 在 RowMapper lambda 内每次 `new ObjectMapper()`（M4 复审 Minor）。
4. 前端 `events: any[]` 未收敛为 `EventItem` 类型（`src/api/order.ts` 与 `OrderTimeline.vue`）。
5. `EventTimeline.vue`：空列表时 `el-empty` 与"只有表头"的空 `el-table` 同时出现；`LegendComponent` 死注册；`tooltip.formatter` 无空值守卫（M4 复审 Minor）。
6. 补偿等写操作端点无鉴权（MVP 内部 admin 可接受，记录为已知上限，V2 补齐；不在 M5 实现完整鉴权）。
7. AI 服务同步阻塞 `httpx`、补偿 handler 每次 `new`（MVP 上限，记录，不在 M5 改）。
8. 本地 Docker 验证此前延后，统一在 M5.2/M5.3 用 docker compose 走通（云服务器或本地均可）。

---

## 文件结构（M5 新增/修改）

- Create: `eventguard-ui/nginx.conf`（生产反向代理，修复 #1）
- Modify: `eventguard-ui/Dockerfile`（COPY nginx.conf）
- Modify: `eventguard-server/src/main/java/com/eventguard/query/repository/OrderViewRepository.java`（#3 复用 ObjectMapper）
- Create: `eventguard-ui/src/types/event.ts`（`EventItem` 导出类型，#4）
- Modify: `eventguard-ui/src/api/order.ts`（#4 `getEvents` 返回 `EventItem[]`）
- Modify: `eventguard-ui/src/components/EventTimeline.vue`（#5 UI 修复 + 复用 `EventItem`）
- Modify: `eventguard-ui/src/views/OrderTimeline.vue`（#4 `ref<EventItem[]>`）
- Modify: `.env.example`（#2 补 `EG_` 变量）
- Create: `README.md`（运行文档 + 演示步骤）
- Create: `docs/verification-log.md`（M5.2/M5.3 验证记录，可提交产物）
- Modify: `docs/superpowers/plans/.../project memory`（M5 收尾更新）

---

### Task 1: 生产 UI 反向代理（nginx.conf）

**Files:**
- Create: `eventguard-ui/nginx.conf`
- Modify: `eventguard-ui/Dockerfile`

**Interfaces:**
- Consumes: 既有后端路由约定——`GET/POST /orders`、`/compensations`（Java `eventguard-server:8080`）；`POST /ai/query`、`GET /anomalies/{id}/analysis`（Python `eventguard-ai:8000`）；`WS /ws/anomalies`（Java `eventguard-server:8080`，M3.8）
- Produces: 浏览器只访问 `:3000`（nginx），由 nginx 按路径转发到上述后端，使前端生产镜像可用

- [ ] **Step 1: 创建 `eventguard-ui/nginx.conf`**

```nginx
server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    # SPA history 模式回退
    location / {
        try_files $uri $uri/ /index.html;
    }

    # WebSocket 实时告警（Java 服务，M3.8）
    location /ws {
        proxy_pass http://eventguard-server:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }

    # 根因分析（Python AI 服务，M3.7）：/anomalies/{id}/analysis
    location /anomalies/ {
        proxy_pass http://eventguard-ai:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # 自然语言查询（Python AI 服务，M4.2）：/ai/query
    location /ai/ {
        proxy_pass http://eventguard-ai:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # 订单命令/查询、补偿执行（Java 服务）：/orders、/compensations
    location /orders {
        proxy_pass http://eventguard-server:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
    location /compensations {
        proxy_pass http://eventguard-server:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

注意：`/ws` 必须保留 Upgrade 头；`/anomalies/` 与 `/ai/` 转发到 AI 服务，`/orders`、`/compensations` 转发到 Java 服务；静态资源走 `try_files` + `index.html` 回退。

- [ ] **Step 2: 修改 `eventguard-ui/Dockerfile`，在 COPY dist 之后加入 nginx.conf**

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# ponytail: 覆盖默认配置，使生产镜像代理 API/WS 到后端（vite proxy 仅开发期有效）
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

- [ ] **Step 3: 构建 UI 镜像确认成功**

Run: `cd eventguard-ui && docker build -t eg-ui-test . 2>&1 | tail -5`
Expected: `Successfully tagged eg-ui-test:latest`（无 COPY 报错）

- [ ] **Step 4: Commit**

```bash
git add eventguard-ui/nginx.conf eventguard-ui/Dockerfile
git commit -m "fix(m5.1): 生产 UI 镜像补 nginx 反向代理（API/WS 转发后端）"
```

---

### Task 2: 全栈部署与冒烟验证

**Files:**
- Create: `docs/verification-log.md`
- Modify: `.env`（从 `.env.example` 复制，按需填 `EG_LLM_*`）

**Interfaces:**
- Consumes: `docker-compose.yml` 全栈（postgres/kafka/debezium/eventguard-server/eventguard-ai/eventguard-ui）
- Produces: `docs/verification-log.md` 记录各服务健康与冒烟结果，作为 M5 验收证据

- [ ] **Step 1: 准备环境文件**

Run:
```bash
cd <repo-root>
cp .env.example .env
# 按需编辑 .env：EG_LLM_API_KEY / EG_LLM_BASE_URL（无 Ollama 时 LLM 相关功能走兜底，见 Task 3 注释）
```
Expected: `.env` 存在，且包含 `POSTGRES_*`、`KAFKA_BOOTSTRAP`、`DB_*` 以及（Task 7 补全后的）`EG_*` 变量。

- [ ] **Step 2: 启动全栈**

Run: `docker compose up -d --build 2>&1 | tail -20`
Expected: 各服务 `Started`；`docker compose ps` 显示 postgres/kafka/debezium/eventguard-server/eventguard-ai/eventguard-ui 均为 `healthy`/`running`（debezium 可能 `running` 即够，CDC 连接后无报错）。

- [ ] **Step 3: 冒烟检查（逐服务）**

Run（将结果写入 `docs/verification-log.md`）：
```bash
echo "== UI ==" ; curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3000/
echo "== Java /orders ==" ; curl -s http://localhost:8080/orders ; echo
echo "== AI docs ==" ; curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/docs
echo "== Kafka topics ==" ; docker compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```
Expected:
- UI `:3000/` 返回 200 且 HTML 含 `<div id="app">`
- `:8080/orders` 返回 `{"orders":[],"total":0,...}`（200）
- `:8000/docs` 返回 200（FastAPI OpenAPI）
- Kafka topics 含 `order-commands`、`order-events`、`anomaly-alerts`（debezium 的 CDC topic）等

- [ ] **Step 4: 确认 CDC 链路通**

Run:
```bash
curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"userId":"u-smoke","totalAmount":9.9}'
sleep 2
curl -s "http://localhost:8080/orders" ; echo
```
Expected: create 返回 `CommandResult` 含 `orderId`；2 秒后 `GET /orders` 的 `orders` 非空（debezium → order_view 投影生效）。

- [ ] **Step 5: 提交验证日志**

```bash
git add docs/verification-log.md
git commit -m "docs(m5.2): 全栈部署冒烟验证记录"
```

---

### Task 3: 端到端功能验证（逐接口/页面 + 时区一致性）

**Files:**
- Modify: `docs/verification-log.md`

**Interfaces:**
- Consumes: Task 2 已起的全栈；订单注入可用 `training/generate_data.py`（向 Kafka 发合成订单/异常序列）或 HTTP 命令端点
- Produces: 验证结论（哪些通过、哪些需修），追加到 `docs/verification-log.md`；如发现阻塞性问题回到对应 Task（M5.1 代理 / Task 4–6 打磨）修复

- [ ] **Step 1: 注入一套完整订单生命周期数据**

Run（二选一）：
- 方式 A（合成数据，含异常）：`cd eventguard-ai && python training/generate_data.py`（向 Kafka 发 normal + anomaly 序列）
- 方式 B（手动驱动）：用命令端点跑完整链路
```bash
OID=$(curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{"userId":"u-e2e","totalAmount":199.0}' | python -c "import sys,json;print(json.load(sys.stdin)['orderId'])")
curl -s -X POST http://localhost:8080/orders/$OID/pay -H "Content-Type: application/json" -d '{"paymentId":"p1"}' >/dev/null
curl -s -X POST http://localhost:8080/orders/$OID/ship >/dev/null
curl -s -X POST http://localhost:8080/orders/$OID/deliver >/dev/null
curl -s -X POST http://localhost:8080/orders/$OID/close >/dev/null
echo $OID
```
Expected: 拿到 `orderId`，各生命周期命令返回 `CommandResult` 成功。

- [ ] **Step 2: 验证查询侧三接口**

Run:
```bash
echo "== list ==" ; curl -s "http://localhost:8080/orders?page=0&size=20" ; echo
echo "== events(timeline) ==" ; curl -s "http://localhost:8080/orders/$OID/events" ; echo
echo "== stats ==" ; curl -s "http://localhost:8080/orders/stats" ; echo
```
Expected: list 含该订单；events 返回按 `event_version` 升序的事件数组（≥5 条）；stats 返回按 status 分组的计数（该订单为 CLOSED）。

- [ ] **Step 3: 验证自然语言查询（AI 服务）**

Run:
```bash
echo "== event_lookup ==" ; curl -s -X POST http://localhost:8000/ai/query -H "Content-Type: application/json" -d "{\"question\":\"订单 $OID 当前状态是什么\"}" ; echo
echo "== stats_aggregation ==" ; curl -s -X POST http://localhost:8000/ai/query -H "Content-Type: application/json" -d '{"question":"最近7天有多少订单"}' ; echo
echo "== trace_replay ==" ; curl -s -X POST http://localhost:8000/ai/query -H "Content-Type: application/json" -d "{\"question\":\"订单 $OID 经历了哪些状态变更\"}" ; echo
```
Expected: 三类问题分别返回 `{intent, data, answer}`，且 `data` 取自对应后端接口（order_lookup 含订单信息、stats 含统计、trace_replay 含事件列表）。**若 Ollama 未部署**：intent 仍由关键词兜底正确分类，answer 退化为数据摘要（不 500），符合 M4.7/M4.5 设计的优雅降级。

- [ ] **Step 4: 验证异常看板链路（WS + 根因）**

> 注意：AI 服务**没有** `GET /anomalies` 列表接口，异常只经 WebSocket 推送给前端（M3.8），根因分析走 `GET /anomalies/{id}/analysis`（M3.7）。先经 WS 抓取一个 `anomaly_id`。

Run（CLI 方式，需 `websocket-client`：`pip install websocket-client`）：
```bash
AID=$(python - <<'PY'
import json, websocket
ws = websocket.create_connection("ws://localhost:8080/ws/anomalies")
msg = json.loads(ws.recv()); ws.close()
print(msg.get("anomaly_id") or msg.get("anomalyId") or "")
PY
)
[ -n "$AID" ] && curl -s "http://localhost:8000/anomalies/$AID/analysis" ; echo
```
Expected: 抓到 `anomaly_id` 后，`/anomalies/{id}/analysis` 返回 `AnalysisReport`（root_cause/evidence/suggestions）。前端 `AnomalyDashboard.vue` 连 `ws://<host>/ws/anomalies`（生产为 `wss` 当 https）能收到告警并展示；点击调用上面根因接口。**验证点**：确认浏览器控制台无 `ws://` 在 https 下被拦截（M4.7 已修为按协议推导 wss）。若无异常到达，先确认 Task 1 的 nginx `/ws` 代理与 M3 检测器是否运行（可用 `training/generate_data.py` 制造异常序列）。

- [ ] **Step 5: 验证补偿执行（白名单 + 400）**

Run:
```bash
echo "== 合法 REFUND ==" ; curl -s -X POST http://localhost:8080/compensations -H "Content-Type: application/json" -d "{\"actionType\":\"REFUND\",\"aggregateId\":\"$OID\"}" ; echo
echo "== 非法动作 ==" ; curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/compensations -H "Content-Type: application/json" -d "{\"actionType\":\"HACK\",\"aggregateId\":\"$OID\"}"
echo "== 缺 aggregateId ==" ; curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/compensations -H "Content-Type: application/json" -d '{"actionType":"REFUND"}'
```
Expected: 合法 REFUND 返回 `success:true` 并写入补偿事件；非法动作返回 `400`；缺 aggregateId 返回 `400`（M4.7 修复）。

- [ ] **Step 6: 时区一致性核对**

Run:
```bash
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
FROM=$(date -u -d '8 days ago' +%Y-%m-%dT%H:%M:%SZ)
curl -s "http://localhost:8080/orders/stats?from=$FROM&to=$NOW" ; echo
```
Expected: `GET /orders/stats` 的 `from/to` 由 Python 以 UTC `isoformat()` 发送，Postgres `order_view.updated_at` 同为 UTC，统计计数与直觉一致（无 ±1 天偏移）。将实际结果与预期写入 `docs/verification-log.md`；若发现偏移，记录为 M5 收尾需修项（统一时区或文档说明）。

- [ ] **Step 7: 提交验证结论**

```bash
git add docs/verification-log.md
git commit -m "docs(m5.3): 端到端功能验证记录（含时区核对）"
```

---

### Task 4: Java 复用 ObjectMapper（findEventsByAggregateId）

**Files:**
- Modify: `eventguard-server/src/main/java/com/eventguard/query/repository/OrderViewRepository.java:77-94`
- Test: `eventguard-server/src/test/java/com/eventguard/query/repository/OrderViewRepositoryTest.java`（如无则新建，验证映射正确）

**Interfaces:**
- Consumes: 既有 `EventDto`（`com.eventguard.query.model.EventDto`）、`JdbcTemplate`
- Produces: 行为不变（返回 `List<EventDto>`），仅去掉每次新建 `ObjectMapper`

- [ ] **Step 1: 写失败测试（验证 payload 映射）**

```java
// OrderViewRepositoryTest.java（节选）
@Test
void findEventsByAggregateId_maps_payload_to_map() {
    UUID id = UUID.randomUUID();
    // 用 @Sql 或 Testcontainers 预置一条 domain_events；若本地无 Docker 用 Mock JdbcTemplate 验证 mapper 行为
    List<EventDto> events = repository.findEventsByAggregateId(id);
    assertThat(events).isNotNull();
    // 关键：payload 能正确转为 Map（复用同一 MAPPER 行为与之前一致）
}
```
> 注：若团队无 Testcontainers 运行环境，可用 Mockito 校验 `jdbc.query` 的 RowMapper 对 `payload` 列调用 `convertValue` 且结果非空；本测试重点是"不回归"，非新增行为。

- [ ] **Step 2: 运行测试确认（红）**

Run: `cd eventguard-server && mvn test -Dtest=OrderViewRepositoryTest -DfailIfNoTests=false 2>&1 | tail -15`
Expected: 测试存在时编译/运行；若尚未有该测试文件则先建最小用例再跑。

- [ ] **Step 3: 改为复用类级 ObjectMapper**

在 `OrderViewRepository` 类内（与 `jdbc` 字段同级）加：
```java
private static final com.fasterxml.jackson.databind.ObjectMapper EVENT_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();
```
并把 `findEventsByAggregateId` 内：
```java
dto.setPayload(node != null ? new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
        node, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}) : null);
```
改为：
```java
dto.setPayload(node != null ? EVENT_MAPPER.convertValue(
        node, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}) : null);
```

- [ ] **Step 4: 运行测试确认（绿）**

Run: `cd eventguard-server && mvn test -Dtest=OrderViewRepositoryTest -DfailIfNoTests=false 2>&1 | tail -15`
Expected: `Tests run: ..., Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add eventguard-server/src/main/java/com/eventguard/query/repository/OrderViewRepository.java eventguard-server/src/test/java/com/eventguard/query/repository/OrderViewRepositoryTest.java
git commit -m "fix(m5.4): OrderViewRepository.findEventsByAggregateId 复用类级 ObjectMapper"
```

---

### Task 5: 前端 events 收敛为 EventItem 类型

**Files:**
- Create: `eventguard-ui/src/types/event.ts`
- Modify: `eventguard-ui/src/api/order.ts`
- Modify: `eventguard-ui/src/components/EventTimeline.vue`
- Modify: `eventguard-ui/src/views/OrderTimeline.vue`
- Test: `eventguard-ui/src/components/__tests__/EventTimeline.test.ts`（既有，应仍绿）

**Interfaces:**
- Consumes: 既有 `EventItem` 形状（eventId/aggregateId/eventType/version/createdAt/payload）
- Produces: 导出的 `EventItem` 类型，供 API 层与视图复用，`events` 不再为 `any[]`

- [ ] **Step 1: 写失败测试（类型导入可用）**

在 `eventguard-ui/src/components/__tests__/EventTimeline.test.ts` 顶部确认能从 `@/types/event` 导入 `EventItem`（若已有测试仅用内联对象则加一行 import 断言编译通过）：
```ts
import { EventItem } from '@/types/event'
```
> 仅验证类型可被解析，无需新增行为断言（既有 3 用例已覆盖渲染）。

- [ ] **Step 2: 运行测试确认（红：类型尚未导出）**

Run: `cd eventguard-ui && npm run test src/components/__tests__/EventTimeline.test.ts 2>&1 | tail -15`
Expected: 若 `@/types/event` 不存在则编译/类型报错。

- [ ] **Step 3: 创建 `eventguard-ui/src/types/event.ts`**

```ts
export interface EventItem {
  eventId: string
  aggregateId: string
  eventType: string
  version: number
  createdAt: string
  payload: Record<string, any>
}
```

- [ ] **Step 4: 修改 `EventTimeline.vue` 复用导出类型**

把文件内 `interface EventItem { ... }` 定义删除，改为：
```ts
import { EventItem } from '@/types/event'
```
（其余 `<script setup>` 中 `props = defineProps<{ events: EventItem[] }>()` 不变）

- [ ] **Step 5: 修改 `src/api/order.ts` 的 `getEvents`**

```ts
import { EventItem } from '@/types/event'
// ...
getEvents(orderId: string): Promise<EventItem[]> {
  return http.get<EventItem[]>(`/orders/${orderId}/events`).then((r) => r.data)
}
```

- [ ] **Step 6: 修改 `src/views/OrderTimeline.vue` 的 ref 类型**

```ts
import { EventItem } from '@/types/event'
// ...
const events = ref<EventItem[]>([])
```

- [ ] **Step 7: 运行测试确认（绿）**

Run: `cd eventguard-ui && npm run test src/components/__tests__/EventTimeline.test.ts 2>&1 | tail -15`
Expected: `Test Files 1 passed, Tests 3 passed`

- [ ] **Step 8: Commit**

```bash
git add eventguard-ui/src/types/event.ts eventguard-ui/src/api/order.ts eventguard-ui/src/components/EventTimeline.vue eventguard-ui/src/views/OrderTimeline.vue
git commit -m "fix(m5.5): 前端 events 收敛为 EventItem 类型（消除 any 漂移）"
```

---

### Task 6: EventTimeline UI 修复

**Files:**
- Modify: `eventguard-ui/src/components/EventTimeline.vue`
- Test: `eventguard-ui/src/components/__tests__/EventTimeline.test.ts`（扩充：空列表时不应渲染 el-table）

**Interfaces:**
- Consumes: Task 5 的 `EventItem`
- Produces：空列表只显示 `el-empty`；图表与表格在 `v-else` 内；tooltip 空守卫；移除死注册

- [ ] **Step 1: 写失败测试（空列表不渲染 table）**

在 `EventTimeline.test.ts` 增加一个用例：
```ts
it('empty events renders only el-empty, no table', async () => {
  const wrapper = mount(EventTimeline, { props: { events: [] } })
  expect(wrapper.find('[data-testid="timeline-empty"]').exists()).toBe(true)
  expect(wrapper.find('.el-table').exists()).toBe(false)
})
```

- [ ] **Step 2: 运行测试确认（红）**

Run: `cd eventguard-ui && npm run test src/components/__tests__/EventTimeline.test.ts 2>&1 | tail -15`
Expected: 新用例失败（当前空列表仍渲染空 `el-table`）。

- [ ] **Step 3: 修复模板——把 el-table 包进 v-else**

```vue
<template>
  <div>
    <div v-if="events.length === 0" data-testid="timeline-empty">
      <el-empty description="暂无事件" />
    </div>
    <template v-else>
      <v-chart
        class="chart"
        :option="chartOption"
        autoresize
        style="height: 400px"
      />
      <el-table :data="sortedEvents" border size="small" style="margin-top: 16px">
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="eventType" label="事件类型" width="220" />
        <el-table-column prop="createdAt" label="发生时间" width="220" />
        <el-table-column label="Payload">
          <template #default="scope">
            <pre v-if="scope && scope.row" style="margin: 0; font-size: 12px">{{ JSON.stringify(scope.row.payload, null, 2) }}</pre>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>
```

- [ ] **Step 4: 移除 LegendComponent 死注册**

把 `import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'` 改为：
```ts
import { GridComponent, TooltipComponent } from 'echarts/components'
```
并把 `use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent])` 改为：
```ts
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent])
```

- [ ] **Step 5: tooltip.formatter 加空守卫**

```ts
formatter: (params: any) => {
  // ponytail: params 可能为空（无数据点时），防空守卫避免运行时报错
  if (!params || !params.length) return ''
  const idx = params[0].dataIndex
  const ev = sorted[idx]
  if (!ev) return ''
  return `${ev.eventType}<br/>版本：${ev.version}<br/>时间：${ev.createdAt}<br/>payload：${JSON.stringify(ev.payload)}`
},
```

- [ ] **Step 6: 运行测试确认（绿）**

Run: `cd eventguard-ui && npm run test src/components/__tests__/EventTimeline.test.ts 2>&1 | tail -15`
Expected: `Tests 4 passed`

- [ ] **Step 7: Commit**

```bash
git add eventguard-ui/src/components/EventTimeline.vue eventguard-ui/src/components/__tests__/EventTimeline.test.ts
git commit -m "fix(m5.6): EventTimeline 空列表不再渲染空表格 + 移除 LegendComponent + tooltip 空守卫"
```

---

### Task 7: 运行文档与 .env.example 补全

**Files:**
- Modify: `.env.example`
- Create: `README.md`

**Interfaces:**
- Consumes: 全部既有接口与运行方式（docker-compose、各模块测试命令）
- Produces: 新人/面试官可照做的运行说明；`.env.example` 含 AI 侧变量

- [ ] **Step 1: 补全 `.env.example` 的 AI 变量**

在 `.env.example` 末尾追加（与 `app/config.py` 的 `EG_` 前缀及默认值对齐）：
```dotenv
# AI 服务（eventguard-ai，EG_ 前缀，见 app/config.py）
EG_LLM_BASE_URL=http://ollama:11434/v1
EG_LLM_API_KEY=ollama
EG_LLM_MODEL=qwen2.5:7b
EG_KAFKA_BOOTSTRAP=kafka:9092
EG_SERVER_BASE_URL=http://eventguard-server:8080
```
> 无 Ollama 时 LLM 功能走兜底（intent 关键词、answer 数据摘要、根因分析返回错误），系统仍可演示。

- [ ] **Step 2: 写 README.md**

包含章节（用中文，给面试官/新人看）：
1. 项目简介与一句话价值（电商订单事件溯源 + AI 异常检测 + NL 查询管理台）
2. 架构图（文字版：Postgres↔Debezium→Kafka→Spring Boot 命令/查询侧 + Python AI；Vue 管理台）
3. 一键部署：`cp .env.example .env && docker compose up -d --build`，访问 `http://localhost:3000`
4. 各模块测试：`eventguard-ai` pytest / `eventguard-server` mvn test / `eventguard-ui` npm run test
5. 演示脚本（对着 M5.3 的 curl 示例，给出"创建订单→驱动生命周期→NL 查询→看异常看板→触发补偿"最短路径）
6. 已知限制 / Roadmap（V2：Saga 补偿编排、端点鉴权、AI 异步化、真实支付网关；MVP 上限列表）

- [ ] **Step 3: Commit**

```bash
git add .env.example README.md
git commit -m "docs(m5.7): 补全 .env.example AI 变量 + 项目 README 与演示脚本"
```

---

### Task 8: 收尾——全量测试 + 记忆更新

**Files:**
- Modify: `docs/superpowers/plans/project memory`（更新 M5 状态）

**Interfaces:**
- Consumes: Task 1–7 全部改动
- Produces: 三套测试全绿的证据；项目记忆标记 M5 完成

- [ ] **Step 1: 跑全量测试（三套）**

Run（各自模块目录）：
```bash
cd eventguard-ai && python -m pytest -v 2>&1 | tail -8
cd eventguard-server && mvn test 2>&1 | grep -E "Tests run:|BUILD" | tail -5
cd eventguard-ui && npm run test 2>&1 | tail -8
```
Expected: 三套均 0 failures / 0 errors / BUILD SUCCESS。

- [ ] **Step 2: 写验证结论到 `docs/verification-log.md` 末尾**

记录：全量测试套件绿；M5 已处理的清单（nginx 代理、ObjectMapper、EventItem、EventTimeline、.env.example、README）；未处理但已知的 MVP 上限（无鉴权、AI 同步、补偿 handler new、时区若 Task 3 发现偏移的处理结论）。

- [ ] **Step 3: 更新项目记忆**

在 `docs/superpowers/plans/...` 或内存 `project_eventguard.md` 追加：M5 完成（日期），main tip，验证方式，遗留上限。

- [ ] **Step 4: Commit**

```bash
git add docs/verification-log.md
git commit -m "docs(m5.8): M5 收尾——全量测试通过 + 验证结论"
```

---

## 验证门禁（Definition of Done）

- [ ] `docker compose up -d --build` 全栈起得来，`/orders`、`/ai/docs` 冒烟通过
- [ ] 生产 UI（nginx）能代理 `/orders`、`/ai`、`/anomalies`、`/compensations`、`/ws`，浏览器控制台无 404 / 无 mixed-content
- [ ] 端到端：创建订单→生命周期→查询三接口→NL 查询三类→异常 WS+根因→补偿（含 400）全部按预期
- [ ] 三套单元测试全绿（pytest / mvn test / npm run test）
- [ ] Task 4/5/6 打磨项落地且对应测试绿
- [ ] README + `.env.example` 齐全，新人可照做部署与演示
- [ ] 已知 MVP 上限已文档化，无遗留 Critical/Important 缺陷

## 范围之外（明确不做，V2）

- 补偿完整 Saga 编排与真实支付网关回调
- 端点鉴权（Spring Security / 网关），MVP 内部 admin 可接受
- AI 服务异步化（httpx.AsyncClient）、补偿 handler 托管为 Spring Bean
- 大文件瘦身（`normal_events.jsonl` / `isolation_forest.pkl` 入 Git LFS）
