# 本地运行与 IDEA 调试

本地调试链路不需要构建 `eventguard-server`、`eventguard-ai` 或前端镜像。Docker 生产配置保持原样；只需要准备 PostgreSQL、Kafka（以及可选的 Debezium/Ollama）等依赖服务。

## 1. 启动依赖

在项目根目录执行：

```powershell
docker compose up -d postgres kafka debezium
```

如果只调试订单、权限和管理页面，PostgreSQL 是必需的；Kafka 用于读模型投影、Saga 和 AI 检测链路，建议一并启动。

## 2. IDEA 启动 Java 后端

打开 `eventguard-server` Maven 模块，运行 Spring Boot 主类，并在 Run Configuration 中设置：

```text
Active profiles: local
```

也可以在终端验证：

```powershell
cd eventguard-server
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

后端地址为 `http://localhost:8080`。默认演示账号仍为 `admin / admin123456`、`operator / operator123456`、`viewer / viewer123456`，不会因本地 profile 改变。

## 3. 启动 AI 服务（可选）

在 `eventguard-ai` 目录创建本地环境变量，或在 IDEA/PyCharm 的 Run Configuration 中配置 `EG_*` 变量。可参考 [`eventguard-ai/.env.local.example`](../eventguard-ai/.env.local.example)。

```powershell
cd eventguard-ai
$env:EG_KAFKA_BOOTSTRAP = "localhost:9092"
$env:EG_SERVER_BASE_URL = "http://localhost:8080"
$env:EG_RULE_ENGINE_URL = "http://localhost:8080/anomaly/rules/evaluate"
python -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

AI 地址为 `http://localhost:8000`。没有 Ollama 或远程 LLM 时，系统仍可启动，NL 查询、根因分析和周报会按现有代码走降级逻辑。

## 4. 启动前端

```powershell
cd eventguard-ui
npm install
npm run dev
```

浏览器访问 `http://localhost:3000`。`vite.config.mts` 已将 `/orders`、`/auth`、`/ai`、`/ws` 等请求代理到本地 Java/AI 服务，因此不需要修改前端 API 地址，也不会影响服务器部署构建。

## 5. 常见断点位置

- Java：命令入口 `eventguard-server/src/main/java/com/eventguard/command/controller`，读模型在 `query` 包，权限在 `auth` 包。
- AI：FastAPI 入口 `eventguard-ai/app/main.py`，LLM 适配在 `eventguard-ai/app/analyzer/llm_client.py`。
- 前端：路由守卫在 `eventguard-ui/src/router/index.ts`，控制台壳层在 `eventguard-ui/src/App.vue`。

若只想调试 HTTP 接口，可以不启动 Kafka；后端仍可处理部分同步请求，但投影、实时告警和 Saga 不会更新。
