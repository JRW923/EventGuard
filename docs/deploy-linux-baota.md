# EventGuard 部署指南（腾讯云轻量应用服务器 + 宝塔面板）

面向「本地用 IDEA 写代码，部署到腾讯云轻量应用服务器（装了宝塔）」的场景。项目已完全容器化（`docker-compose.yml` + 三个 `Dockerfile`），**推荐 Docker Compose 一键部署**：IDEA 只管写代码，部署时推到 Git、服务器 `git clone`/`git pull`，jar 包和前端资源在 `docker compose build` 阶段自动构建。

---

## 1. 架构与端口速览

容器间走 Docker 内部网络，**只有 UI / Grafana 需要对外**。

| 服务 | 容器内端口 | 宿主机映射 | 说明 |
| --- | --- | --- | --- |
| eventguard-ui（nginx） | 80 | `80:80` | 唯一对外入口 |
| eventguard-server（Java） | 8080 | 不映射 | 仅容器间调用 |
| eventguard-ai（FastAPI） | 8000 | 不映射 | 仅容器间调用 |
| postgres | 5432 | 不映射 | 数据库，严禁对外 |
| kafka | 9092 | 不映射 | 消息队列，严禁对外 |
| debezium | — | 无 | CDC 桥接 |
| grafana（监控） | 3000 | `3001:3000` | 指标/日志看板（admin/admin，P0-2） |
| prometheus / alertmanager / loki / promtail | — | 不映射 | 监控告警与集中日志（P0-2 / P1-13） |

浏览器只访问 `http://<服务器IP>:80`（UI）；`/orders`、`/compensations`、`/ai`、`/anomalies`、`/ws` 由 UI 的 nginx 反代到后端/AI。
监控看板 `http://<服务器IP>:3001`（Grafana，已自动配置 Prometheus + Loki 数据源）。

依赖链路：**订单事件 → PostgreSQL → Debezium CDC → Kafka → AI 实时检测 / 查询投影 → 前端看板**。

---

## 2. 部署主流程（照做即可）

### 2.1 开放防火墙端口
- **腾讯云控制台「防火墙」**：放行 `80`（UI，走域名+宝塔反代则放行 `80`/`443`）；如需访问 Grafana 监控看板再放行 `3001`。**不要**放行 `5432`、`9092`、`8080`、`8000`。
- **宝塔「安全」→「系统防火墙」**：同样放行 `80`（或 `80`/`443`）+ 可选 `3001`，其余保持封闭。

### 2.2 安装 Docker 与 Docker Compose
SSH 登录后（国内请用镜像源，勿用 `get.docker.com` 脚本，原因见 Q1）：

**CentOS / OpenCloudOS / TencentOS：**
```bash
sudo rm -f /etc/yum.repos.d/docker-ce.repo
sudo tee /etc/yum.repos.d/docker-ce.repo > /dev/null <<'EOF'
[docker-ce-stable]
name=Docker CE Stable - $basearch
baseurl=https://mirrors.cloud.tencent.com/docker-ce/linux/centos/$releasever/$basearch/stable
enabled=1
gpgcheck=1
gpgkey=https://mirrors.cloud.tencent.com/docker-ce/linux/centos/gpg
EOF
sudo yum makecache
sudo yum install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker
```

**Ubuntu / Debian：**
```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker
```
验证：`sudo docker --version` 与 `sudo docker compose version`（v2 插件，命令为 `docker compose` 无横杠）。免 `sudo`：`sudo usermod -aG docker $USER`（重连 SSH 生效）。

### 2.3 拉取代码并配置环境变量
```bash
cd /opt
sudo git clone <你的仓库地址> EventGuard
cd EventGuard
cp .env.example .env
vim .env      # 或用宝塔「文件」在线编辑 /opt/EventGuard/.env
```
`.env` 被 git 忽略，**保留 `.env.example` 的全部字段**（数据库名/用户、Kafka/Server 内部地址是容器间联通关键），只改以下弱口令/密钥：
```dotenv
POSTGRES_PASSWORD=换成强随机密码
DB_PASSWORD=换成同样的强随机密码          # 须与上面一致
EG_JWT_SECRET=换成强随机密钥(openssl rand -hex 32)    # 签发/校验用户登录 JWT（server 与 AI 共用）
EG_MACHINE_API_KEY=换成另一个强随机密钥                # AI→后端内部调用机器密钥
```
`EG_LLM_*` 大模型配置可不填（AI 自动降级为关键词/摘要，不影响主流程）。
`EG_PAYMENT_PROVIDER` / `EG_INVENTORY_PROVIDER` / `EG_NOTIFY_PROVIDER` 网关 Provider 默认 `mock`
（无需凭证即可全流程演示，见 README「网关接入」）；接真实支付宝/企业微信时再填 `EG_ALIPAY_*` / `EG_NOTIFY_WECOM_WEBHOOK`。

### 2.4 启动
> 不要带 `chaos` profile（随机杀容器的韧性演示，会搞挂生产）。
```bash
cd /opt/EventGuard
docker compose up -d --build
```
首次构建会拉镜像 + 编译 Java + 装 Python 依赖 + 构建前端，耗时几分钟。等待 PostgreSQL、Kafka 变 `healthy`（compose 已配健康检查与依赖顺序）。

### 2.5 访问
浏览器打开 `http://<服务器公网IP>:80`，应看到管理台。按 README「体验流程」走：订单列表 → 自然语言查询 → 异常看板 → 补偿执行。
监控看板 `http://<服务器公网IP>:3001`（Grafana，默认 admin/admin，首次登录请改密）。

**可选·域名 + HTTPS（推荐生产）**：宝塔「网站」→ 添加站点（纯静态）→「反向代理」目标 `http://127.0.0.1:80` →「SSL」申请 Let's Encrypt 强制 HTTPS。随后可把 compose 里 ui 的 `ports` 改为 `127.0.0.1:80:80`，让 UI 只监听本机。WebSocket（`/ws`）宝塔反代默认已支持 Upgrade，若告警推不动，确认站点配置含 `proxy_set_header Upgrade $http_upgrade;` 与 `Connection "upgrade";`。

---

## 3. 验证与日常运维

**状态 / 日志：**
```bash
docker compose ps
docker compose logs -f eventguard-server
docker compose logs -f eventguard-ai
```

**健康检查（容器内探活，因为 8080/8000 未对外）：**
```bash
docker compose exec eventguard-server curl -s localhost:8080/actuator/health   # 预期 {"status":"UP",...}
docker compose exec eventguard-ai      curl -s localhost:8000/health
```

**更新代码：**
```bash
git pull
docker compose up -d --build
```
**停机（保留数据）：** `docker compose down`　**彻底清理（含数据卷，慎用）：** `docker compose down -v`

数据持久化：PostgreSQL 在命名卷 `pgdata`，重建不丢；AI 模型打包进镜像，改模型需重新 build。

---

## 4. 常见问题排查（所有坑都在这）

**Q1：安装 Docker 时 `yum install yum-utils` 报 No match，或 `download.docker.com` 报 `Curl error (35)`。**
- OpenCloudOS 9（RHEL9 系）已无 `yum-utils` 包；`download.docker.com` 在国内常被墙。直接按 2.2 用**腾讯云镜像源**，不要用 `get.docker.com` 脚本，也不要自己加官方源。`yum-utils` 缺失不影响安装，可跳过（用 `dnf-plugins-core` 也可，但非必须）。

**Q2：`git pull` 提示输入密码并失败 / 报 401。**
- GitHub 已禁用账号密码走 HTTPS。改用：① **SSH（推荐）** `ssh-keygen -t ed25519`，公钥加到 GitHub → `git remote set-url origin git@github.com:JRW923/EventGuard.git` → `git pull`；② **HTTPS + PAT**：拉取让输密码时粘贴 Personal Access Token（非登录密码），并可 `git config --global credential.helper store` 缓存。
- 日常更新直接 `git pull`，不要写完整 URL（`git clone` 时已记为 `origin`）。

**Q3：`git pull` 报 `Your local changes ... would be overwritten by merge`。**
- 服务器上对受 git 跟踪的文件有未提交改动（如 `docker-compose.yml` 的端口注释、各 `Dockerfile` 的手动改法）。git 会拒绝覆盖，报错里会列出具体文件。把报错列出的文件逐个丢弃再拉，然后按 Q4 重做端口收紧：
  ```bash
  git checkout -- eventguard-ai/Dockerfile eventguard-ui/Dockerfile   # 以报错实际列出的文件为准
  git pull
  ```
  不要用 `git stash`/`stash pop`：仓库结构经常变（构建参数、鉴权机制等），pop 多半冲突。你之前手动改过的 Dockerfile（如写死国内镜像）通常已被仓库版覆盖等价实现，丢弃本地改动不会丢东西。
- **`git pull` 前不需要先停服务**：pull 只更新磁盘上的文件（compose/Dockerfile/源码），不影响正在运行的容器，业务不会中断。pull 后让改动生效靠 `docker compose up -d --build`（compose 会自动停掉旧容器、起新的），**也不用你手动 `docker compose down`**。本次含 UI 运行时注入修复，必须 `docker compose up -d --build eventguard-ui` 重建 UI 才能消 401。

**Q4：每次 `git pull` 后端口又全暴露了 / 前端 401 又出现。**
- `git pull` 会用仓库新版覆盖服务器上的 `docker-compose.yml` 与各 Dockerfile，你之前的端口注释会丢失。每次拉取后补两件事：
  1. **重做端口收紧**（见下方「生产端口收紧」）：注释掉 `postgres`/`kafka`/`eventguard-server`/`eventguard-ai`/`grafana` 的 `ports:`，只留 UI `80`（Grafana 如需可留 `127.0.0.1:3001:3000`）。
  2. **并入 `.env` 新增字段**：`.env` 被 git 忽略、pull 不动它，但你可能缺后来加的键。
     - 手动：`diff .env.example .env`（以 `<` 开头=example 有、你缺），只把缺的键追加进 `.env`，**不要 `cp .env.example .env`**（会覆盖强密码）。
     - 更省事（一键合并缺失键，保留已有值，无需新依赖）：
       ```bash
       while IFS= read -r line; do
         case "$line" in ''|#*) continue ;; esac
         key="${line%%=*}"
         grep -q "^${key}=" .env || echo "$line" >> .env
       done < .env.example
       grep -E "EG_JWT_SECRET|EG_MACHINE_API_KEY|PIPE_INDEX_URL" .env   # 确认关键键都在
       ```
       该脚本只追加「example 有、.env 没有」的键（用 example 默认值），你已设的值不动。追加后留意新出现的密钥类键，按需改成强值。补完 `docker compose up -d --build`。

**生产端口收紧**（建议做，缩小暴露面）：
```yaml
  postgres:
    # ports: ["5432:5432"]
  kafka:
    # ports: ["9092:9092"]
  eventguard-server:
    # ports: ["8080:8080"]
  eventguard-ai:
    # ports: ["8000:8000"]
  eventguard-ui:
    ports: ["80:80"]   # 唯一对外
```

**Q5：在宿主机 `curl http://localhost:8080/actuator/health` 连不上（connection refused）。**
- **预期行为，不是故障**：端口收紧后 8080 只在 compose 内部网络可见，宿主机无监听。actuator 明明开着（`application.yml` 配了 `exposure.include: health,info,metrics,prometheus`），且 `AuthFilter` 对 `/actuator` 免鉴权。改在容器内探活：`docker compose exec eventguard-server curl -s localhost:8080/actuator/health` → `{"status":"UP",...}`。

**Q6：前端订单列表报「加载失败：401」，无数据。**
- 401 说明未带有效 JWT，按顺序排查：
  1. **未登录 / token 过期**：系统已改为**登录 + JWT**鉴权，未登录直接调 API 会 401。先到 `http://域名` 用种子账号登录（首次登录强制改密）；token 默认 12h 过期，过期后前端自动跳登录页。
  2. **nginx 丢了 `Authorization` 头**：`eventguard-ui/nginx.conf` 的代理 location 须有 `proxy_set_header Authorization $http_authorization;`（已内置）。缺了它后端收不到 Bearer token。
  3. **`EG_JWT_SECRET` 不一致**：server 与 AI 必须用同一个 `EG_JWT_SECRET` 签发/校验；`.env` 改过后需 `docker compose up -d --build eventguard-server eventguard-ai` 重建生效。
- 快速判定：在 UI 容器内 `curl` 测后端——先登录拿 token，再带 `Authorization: Bearer` 请求 `http://localhost/orders`，返回 200 即通（`localhost` 走 nginx，能顺带验证转发头）。

**Q7：构建 AI 镜像时 `pip install` 报 `ReadTimeoutError` / `files.pythonhosted.org` 超时。**
- 国内连默认 PyPI 不稳。本仓库 `eventguard-ai/Dockerfile` 已**写死腾讯云镜像** `https://mirrors.cloud.tencent.com/pypi/simple`，正常不会再超时。若你曾手改回官方源或 `git pull` 覆盖了它，恢复写死即可；旧参数化方式（`PIP_INDEX_URL` 经 build args）不可靠，已弃用。

**Q8：构建 UI 镜像时 `npm install` 极慢/超时。**
- 本仓库 `eventguard-ui/Dockerfile` 已**写死 npmmirror** `https://registry.npmmirror.com`，正常不会再超时。

**Q9：Kafka / Debezium 一直 unhealthy。**
- `docker compose logs kafka` / `docker compose logs debezium` 看报错。常见是 PostgreSQL 未开逻辑复制——本仓库 `postgres` 已加 `wal_level=logical`，只要用 compose 里的 postgres 镜像就没问题。

**Q10：服务器内存不足 / 构建 OOM。**
- 2G 内存编 Java 可能 OOM，建议 4G；或 `docker compose build` 前临时加 swap。

---

## 附录 A：不用 Docker 的手动部署（不推荐）
仅当你坚持在服务器用系统 Java/Python/Nginx 直接跑：
1. 基础设施仍用 Docker：`docker compose up -d postgres kafka debezium`（只用服务名互访）。
2. Server：IDEA `Maven → package` 得 `eventguard-server-*.jar`，上传服务器用 JRE 17 运行：
   ```bash
   java -jar eventguard-server-*.jar \
     --DB_URL=jdbc:postgresql://localhost:5432/eventguard \
     --DB_USER=eventguard --DB_PASSWORD=你的密码 \
     --KAFKA_BOOTSTRAP=localhost:9092 --EG_JWT_SECRET=你的JWT密钥 --EG_MACHINE_API_KEY=你的机器密钥
   ```
3. AI：服务器装 Python 3.11，`pip install -r eventguard-ai/requirements.txt`，设 `EG_` 环境变量后 `uvicorn app.main:app --host 0.0.0.0 --port 8000`。
4. 前端：IDEA/`npm run build` 出 `dist/`，宝塔建静态站点指向 `dist/`，反代 `/orders`、`/compensations`、`/ai`、`/anomalies`、`/ws` 到对应后端（等价于 `eventguard-ui/nginx.conf`）。

> 这条路径要手动维护三套环境与反代，远不如 Docker Compose 省心，**强烈建议用 Docker**。

---

## 附录 B：启用本地大模型根因分析（可选）
默认 `EG_LLM_BASE_URL=http://ollama:11434/v1` 指向不存在的 Ollama，AI 降级。要真正大模型根因，在 `docker-compose.yml` 加 Ollama 服务：
```yaml
  ollama:
    image: ollama/ollama
    ports: ["11434:11434"]
    volumes: ["ollama_data:/root/.ollama"]
    deploy:
      resources:
        limits: { memory: 6G }   # 7B 模型约需 5-6G
volumes:
  pgdata:
  ollama_data:
```
```bash
docker compose up -d ollama
docker compose exec ollama ollama pull qwen2.5:7b
```
保持 `.env` 里 `EG_LLM_*` 默认即可。会显著增加内存与磁盘占用，轻量服务器请评估后再开。
