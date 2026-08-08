# 本地运行与 IDEA 调试

本地调试链路不需要构建 `eventguard-server`、`eventguard-ai` 或前端镜像。Docker 生产配置保持原样；只需要准备 PostgreSQL、Kafka（以及可选的 Debezium/Ollama）等依赖服务。

## 1. 启动依赖

在项目根目录执行：

```powershell
# 当前目录为 EventGuard 仓库根目录
docker-compose up -d postgres kafka debezium
```

当前项目环境使用独立的 `docker-compose.exe`。如果你的 Docker Desktop 支持新版 Compose 子命令，也可以将命令替换为 `docker compose up -d postgres kafka debezium`。

如果只调试订单、权限和管理页面，PostgreSQL 是必需的；Kafka 用于读模型投影、Saga 和 AI 检测链路，建议一并启动。

如果后端仍然报 `Failed to obtain JDBC Connection`，先确认 `5432` 没有被 Windows 本机 PostgreSQL 占用。Docker 容器发布的端口必须由 Compose 接管，否则 IDEA 可能连接到另一个本机 PostgreSQL 实例：

```powershell
Get-NetTCPConnection -LocalPort 5432 -State Listen -ErrorAction SilentlyContinue
Get-Service *postgres* -ErrorAction SilentlyContinue
```

如果看到 `postgresql-x64-*` 服务正在监听 `5432`，请用“管理员身份”PowerShell 暂停它，再重启容器（不会修改数据库账号密码）：

```powershell
Stop-Service postgresql-x64-18
docker-compose restart postgres
docker-compose ps postgres
```

服务名以 `Get-Service *postgres*` 的实际输出为准。若本机服务不能停止，请在 Docker Desktop 中把 PostgreSQL 映射到空闲端口，并在 IDEA 的 `EG_LOCAL_DB_URL` 中填对应端口；无需修改仓库内 Docker 配置。

下面第 2～4 步建议分别使用三个 PowerShell 窗口，并且每个窗口都从仓库根目录开始执行；不要把不同服务的前台进程放在同一个窗口。

## 2. IDEA 启动 Java 后端

打开 `eventguard-server` Maven 模块，运行 Spring Boot 主类，并在 Run Configuration 中设置：

```text
Active profiles: local
```

注意：根目录 `.env` 中的 `DB_URL=jdbc:postgresql://postgres:5432/eventguard` 是容器内部地址，不能直接填到 IDEA。`local` profile 默认使用 `localhost` 和 Compose 默认的 `changeme` 数据库密码；如果你初始化数据库时改过密码，请在 IDEA 的 Environment variables 中设置 `EG_LOCAL_DB_PASSWORD`。

也可以在终端验证：

```powershell
Set-Location .\eventguard-server
$env:EG_LOCAL_KAFKA_BOOTSTRAP = "localhost:9092"
$env:EG_LOCAL_KAFKA_LISTENER_ENABLED = "false"
$env:EG_LOCAL_DB_URL = "jdbc:postgresql://localhost:5432/eventguard"
$env:EG_LOCAL_DB_USER = "eventguard"
$env:EG_LOCAL_DB_PASSWORD = "changeme"
mvn.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

后端 API 地址为 `http://localhost:8080`，不是网页入口；直接打开 `http://localhost:8080/` 返回 `401 Missing or invalid token` 属于预期行为，因为根路径受 AuthFilter 保护。浏览器使用完整界面时，请启动下面的前端并访问 `http://localhost:3000`。健康检查地址是 `http://localhost:8080/health`。默认演示账号仍为 `admin / admin123456`、`operator / operator123456`、`viewer / viewer123456`，不会因本地 profile 改变。

`local` profile 默认不自动启动 Kafka listeners，避免 Compose 的 `kafka:9092` 广播地址阻断 IDEA 启动；订单 HTTP、权限和管理页面仍可正常调试。若需要本地消费 `domain-events` 或 `anomaly-alerts`，先让 Windows 能解析 Docker 广播地址，再在 IDEA 环境变量中开启：

```powershell
# 管理员 PowerShell 执行一次；确认 9092 未被其他程序占用
$hosts = "$env:SystemRoot\System32\drivers\etc\hosts"
if (-not (Select-String -Path $hosts -Pattern '^\s*127\.0\.0\.1\s+kafka(\s|$)' -Quiet)) {
  Add-Content -Path $hosts -Value "`n127.0.0.1 kafka"
}
$env:EG_LOCAL_KAFKA_LISTENER_ENABLED = "true"
```

如果不希望修改 hosts 文件，保持 `EG_LOCAL_KAFKA_LISTENER_ENABLED=false` 即可；这只关闭本地 listener，不会改变 Docker 生产配置。

## 3. 启动 AI 服务（可选）

在 `eventguard-ai` 目录创建本地环境变量，或在 IDEA/PyCharm 的 Run Configuration 中配置 `EG_*` 变量。可参考 [`eventguard-ai/.env.local.example`](../eventguard-ai/.env.local.example)。

```powershell
Set-Location .\eventguard-ai
$env:EG_KAFKA_BOOTSTRAP = "localhost:9092"
$env:EG_SERVER_BASE_URL = "http://localhost:8080"
$env:EG_RULE_ENGINE_URL = "http://localhost:8080/anomaly/rules/evaluate"
if (-not (Test-Path .\.venv\Scripts\python.exe)) {
  python.exe -m venv .venv
  .\.venv\Scripts\python.exe -m pip install -r requirements.txt
}
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

上面的 `$env:` 配置只对当前 PowerShell 窗口有效，必须和 AI 启动命令在同一个窗口执行；IDEA/PyCharm 启动时则把这些键填到 Run Configuration 的 Environment variables 中。

AI 地址为 `http://localhost:8000`。没有 Ollama 或远程 LLM 时，系统仍可启动，NL 查询、根因分析和周报会按现有代码走降级逻辑。

## 4. 启动前端

```powershell
Set-Location .\eventguard-ui
npm.cmd install
npm.cmd run dev
```

浏览器访问 `http://localhost:3000`。`vite.config.mts` 已将 `/orders`、`/auth`、`/ai`、`/ws` 等请求代理到本地 Java/AI 服务，因此不需要修改前端 API 地址，也不会影响服务器部署构建。

## 5. 常见断点位置

- Java：命令入口 `eventguard-server/src/main/java/com/eventguard/command/controller`，读模型在 `query` 包，权限在 `auth` 包。
- AI：FastAPI 入口 `eventguard-ai/app/main.py`，LLM 适配在 `eventguard-ai/app/analyzer/llm_client.py`。
- 前端：路由守卫在 `eventguard-ui/src/router/index.ts`，控制台壳层在 `eventguard-ui/src/App.vue`。

若只想调试 HTTP 接口，可以不启动 Kafka；后端仍可处理部分同步请求，但投影、实时告警和 Saga 不会更新。
