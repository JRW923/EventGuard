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
