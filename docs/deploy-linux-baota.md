# EventGuard 部署指南（腾讯云轻量应用服务器 + 宝塔面板）

本文面向**在本地用 IntelliJ IDEA 写代码、最终部署到腾讯云轻量应用服务器（装了宝塔 Linux 面板）**的场景，给出一条可落地的部署流程。

> 结论先行：本项目仓库已经完全容器化（`docker-compose.yml` + 三个 `Dockerfile`），**推荐用 Docker Compose 一键部署**，无需在 IDEA 里手动打包再上传。你在 IDEA 里只负责看代码/改代码，部署时把代码推到 Git 后在服务器 `git clone` 即可，jar 包和前端静态资源都会在 `docker compose build` 阶段自动构建。

---

## 1. 架构与端口速览

容器之间通过 Docker 内部网络通信，**只有 UI 需要对外暴露**。

| 服务 | 容器内端口 | 默认映射到宿主机 | 说明 |
| --- | --- | --- | --- |
| eventguard-ui（nginx） | 80 | `3000:80` | **唯一需要对外访问的端口** |
| eventguard-server（Java） | 8080 | `8080:8080` | 仅容器间调用，生产建议不对外 |
| eventguard-ai（FastAPI） | 8000 | `8000:8000` | 仅容器间调用，生产建议不对外 |
| postgres | 5432 | `5432:5432` | **数据库，严禁对外** |
| kafka | 9092 | `9092:9092` | **消息队列，严禁对外** |
| debezium | — | 无 | CDC 桥接，仅连 pg+kafka |

浏览器只访问 `http://<服务器IP>:3000`，其余路径（`/orders`、`/compensations`、`/ai`、`/anomalies`、`/ws`）由 UI 容器里的 nginx 反向代理到后端/AI 容器。

依赖链路：**订单事件 → PostgreSQL → Debezium CDC → Kafka → AI 实时检测 / 查询投影 → 前端看板**。

---

## 2. 前置条件

- 腾讯云轻量应用服务器一台（建议 **2 核 4G 起步**，若启用本地大模型 Ollama 7B 需 **4 核 8G+**）。
- 镜像：宝塔 Linux 面板（任意发行版均可，下文命令同时给 CentOS/OpenCloudOS 的 `yum/dnf` 与 Ubuntu/Debian 的 `apt`）。
- 服务器已放行 SSH（22）。
- 本地已安装 Git，且代码已推送到 GitHub / Gitee（服务器要从仓库拉代码）。

> 如果你还没把代码推到远程仓库：在 IDEA 的 Terminal 或系统终端执行 `git push`，确保服务器能 `git clone` 到。

---

## 3. 服务器基础准备

### 3.1 开放防火墙端口

需要开放**两层**防火墙，缺一不可：

1. **腾讯云控制台「防火墙」**（轻量应用服务器页 → 防火墙 → 添加规则）：
   - 放行 `3000`（TCP，HTTP 访问 UI）；若走域名 + 宝塔反代，则放行 `80`、`443`。
   - **不要**放行 `5432`、`9092`、`8080`、`8000`（这些只应容器间访问）。
2. **宝塔面板「安全」→「系统防火墙」**（若已开启）：同样放行 `3000`（或 `80`/`443`），其余端口保持封闭。

### 3.2 安装 Docker 与 Docker Compose

SSH 登录服务器后执行（二选一，按你的发行版）：

> 注意：OpenCloudOS 9（RHEL9 系）里 `yum-utils` 包已不存在，且 `download.docker.com` 在国内部署机常被墙（报 `Curl error (35): Connection reset`）。**请直接用国内镜像源**，不要加官方源、也不要用 `get.docker.com` 脚本。

**CentOS / OpenCloudOS / TencentOS（腾讯云/阿里云镜像）：**

```bash
# 先删掉可能误加的官方源（它指向被墙的 download.docker.com）
sudo rm -f /etc/yum.repos.d/docker-ce.repo

# 写入腾讯云镜像源（同腾讯云内网更快；如需换源把下面两处 cloud.tencent.com 改为 mirrors.aliyun.com）
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

验证：

```bash
sudo docker --version
sudo docker compose version   # 注意是 v2 插件，命令为 docker compose（无横杠）
```

为避免每次 `sudo`，把当前用户加入 docker 组（完成后重连 SSH 生效）：

```bash
sudo usermod -aG docker $USER
```

---

## 4. 获取代码

```bash
cd /opt
sudo git clone <你的仓库地址> EventGuard
cd EventGuard
```

> 用 IDEA 本地改完代码后，只需 `git push`，服务器上 `git pull` 再重新 `docker compose up -d --build` 即可更新。

### 4.0 正确的更新命令与鉴权（避免 `git pull` 报错）

**不要每次都写完整 URL**。`git clone` 时已把远程记为 `origin`，以后直接在仓库目录里：

```bash
cd /opt/EventGuard
git pull          # 无需再写 https://github.com/... 那串地址
```

若 `git pull` 提示输入密码并失败，几乎都是 **GitHub 已禁用账号密码走 HTTPS**（报 "incorrect"/401）。改用以下任一种鉴权：

1. **SSH（推荐，一劳永逸）**：在服务器生成密钥并加到 GitHub 账户（Settings → SSH keys）：
   ```bash
   ssh-keygen -t ed25519 -C "deploy@tencent"
   cat ~/.ssh/id_ed25519.pub        # 复制公钥粘贴到 GitHub
   git remote set-url origin git@github.com:JRW923/EventGuard.git
   git pull
   ```
2. **HTTPS + 个人令牌（PAT）**：`git pull` 让输密码时，**粘贴 PAT**（不是登录密码；GitHub → Settings → Developer settings → Personal access tokens，勾 `repo`）。为免每次输入可缓存：
   ```bash
   git config --global credential.helper store
   ```
3. 若在**本机**装过 `gh` 并登录过，那是另一台机器、服务器不会继承；服务器想复用需另装 `gh` 并执行 `gh auth setup-git`，一般不如直接用 SSH。

> `git pull https://github.com/...` 这种带完整 URL 的写法并非必须，只在临时从不同地址拉取时才用；常规更新用 `git pull` 即可。

### 4.1 每次 `git pull` 后需手动处理的项

`git pull` 会用仓库最新版本**覆盖服务器上的 `docker-compose.yml` 和各 Dockerfile**（你本地对这些文件的修改会丢失）。

> **若 `git pull` 报 `Your local changes ... would be overwritten by merge`**：说明服务器上的 `docker-compose.yml` 有未提交的本地改动（如之前手动注释的端口）。处理办法——丢弃本地改动再拉取，然后重做端口注释：
> ```bash
> git checkout -- docker-compose.yml
> git pull
> ```
> 不要用 `git stash`/`stash pop`：因为仓库新版文件结构已变（新增 VITE_API_KEY、PIP_INDEX_URL 构建参数），pop 多半冲突、更麻烦。

每次拉取后都要补回下面两件事：

1. **重新做端口收紧（第 6 节）**：仓库默认仍暴露 `5432/9092/8080/8000`，pull 后这些注释会被还原。需再把 `postgres`/`kafka`/`eventguard-server`/`eventguard-ai` 的 `ports:` 行注释掉，仅保留 UI 的 `3000`。
2. **把 `.env.example` 新增字段并入你的 `.env`**：`.env` 被 git 忽略、pull 不会动它，但你当初是基于旧版 `.env.example` 创建的，可能缺后来加的字段（如 `VITE_API_KEY`、`PIPE_INDEX_URL`）。每次 pull 后对比：
   ```bash
   diff .env.example .env      # 以 < 开头的行 = example 有、你的 .env 没有
   ```
   **不要直接 `cp .env.example .env`**（会覆盖你已设的强随机密码/密钥，退回 `changeme`）**。只把缺的键手动追加进 `.env`：
   ```dotenv
   # 前端构建期密钥：必须与 EG_API_KEY 完全相同
   VITE_API_KEY=这里填你 .env 里 EG_API_KEY 那个强随机值
   # pip 镜像（国内部署用腾讯云，避免 PyPI 超时）
   PIP_INDEX_URL=https://mirrors.cloud.tencent.com/pypi/simple
   ```
   确认补上：`grep -E "VITE_API_KEY|PIPE_INDEX_URL" .env`。补完后 `docker compose up -d --build` 重建（让 `VITE_API_KEY` 烤进前端、`PIPE_INDEX_URL` 用于 AI 镜像构建）。

> 注意：如果你之前在服务器上**直接把腾讯/清华镜像写死进 `eventguard-ai/Dockerfile`**（不走参数化），`git pull` 会把它覆盖回默认官方 PyPI，构建将再次超时。请改用参数化方式：在 `.env` 里加 `PIP_INDEX_URL=https://mirrors.cloud.tencent.com/pypi/simple`，由 compose 的 `args` 传入（见第 5 节 / Q6），不要再手改 Dockerfile。

补完后统一重建：

```bash
docker compose up -d --build
```

---

## 5. 配置环境变量（重要：改掉默认密钥）

`.env` 被 `.gitignore` 忽略，不会进仓库；`git clone` 后只有 `.env.example`。**请先复制成 `.env`，再改值**：

```bash
cd /opt/EventGuard
cp .env.example .env
vim .env   # 或用宝塔「文件」在线编辑 /opt/EventGuard/.env
```

> 必须保留 `.env.example` 里的**全部字段**（数据库名/用户、Kafka/Server 内部地址等是容器间联通的关键），只修改下面列出的弱口令/密钥项，其余保持原样。不要只新建那几个字段——缺字段会导致数据库起不来或后端连不上库。

需要修改的关键项：

```dotenv
# ① 数据库密码：不要用 changeme
POSTGRES_PASSWORD=换成强随机密码
DB_PASSWORD=换成强随机密码        # 须与上面一致

# ② 网关鉴权密钥：前后端必须一致，且要足够强
EG_API_KEY=换成强随机密钥(如 openssl rand -hex 32)

# ③ 前端构建期密钥：必须与 ② 完全相同，否则前端请求被后端 401 拒绝
VITE_API_KEY=与上面 EG_API_KEY 完全相同的强随机密钥

# ④（可选）大模型：不填也行，AI 会降级为关键词/摘要，不影响主流程
# EG_LLM_BASE_URL=http://ollama:11434/v1
# EG_LLM_API_KEY=ollama
# EG_LLM_MODEL=qwen2.5:7b
```

生成强随机密钥的小技巧：

```bash
openssl rand -hex 32
```

> 其余项（`POSTGRES_DB`、`POSTGRES_USER`、`KAFKA_BOOTSTRAP`、`DB_URL`、`EG_SERVER_BASE_URL` 等）保持默认值即可——它们用的是 Docker 内部服务名，在 compose 网络里能正确解析。

---

## 6. 生产安全加固（建议做）

默认 `docker-compose.yml` 把 `5432`、`9092`、`8080`、`8000` 都映射到了宿主机。在公网服务器上这属于暴露风险，建议**只保留 UI 端口对外**。用宝塔反代时甚至连 `3000` 都不用对外（见第 8 节）。

编辑 `docker-compose.yml`，把不需要对外暴露的服务端口注释掉（保留 `ports` 字段内只留 UI）：

```yaml
  postgres:
    image: postgres:16
    # ports: ["5432:5432"]      # ← 注释掉，不再映射到宿主机
    ...
  kafka:
    image: confluentinc/cp-kafka:7.6.0
    # ports: ["9092:9092"]      # ← 注释掉
    ...
  eventguard-server:
    build: ./eventguard-server
    # ports: ["8080:8080"]      # ← 注释掉（如走宝塔反代）
    ...
  eventguard-ai:
    build: ./eventguard-ai
    # ports: ["8000:8000"]      # ← 注释掉
    ...
  eventguard-ui:
    build: ./eventguard-ui
    ports: ["3000:80"]          # ← 保留，对外访问入口
    ...
```

### 6.1 前端 API Key 的注入机制（无需手改 compose）

前端在运行时从 `window.__EG_API_KEY__` 读取网关密钥，该值由 UI 容器启动时用 `EG_API_KEY` 经 nginx `envsubst` 注入到 `config.js`（见 `eventguard-ui/nginx-entrypoint.sh`）。`EG_API_KEY` 通过 `docker-compose.yml` 的 `environment` 从 `.env` 传入——**`environment` 是运行时变量，注入可靠**，不会再出现 build args 传不到导致 401 的问题。

- `.env` 里必须设置 `EG_API_KEY`（见第 5 节），它就是前后端共用的密钥；前端自动取同一个值，因此**只要 `EG_API_KEY` 存在，前端与后端天然一致**。
- `VITE_API_KEY` 仍保留为**构建期兜底**（仅当容器未注入 `EG_API_KEY` 时生效，默认 `changeme`）。正常部署不需要动它；若你之前遇过订单列表 401，根因就是 build args 没可靠传入，现已改用运行时注入解决。

> 排查 401 时直接看容器里生成的 `config.js`：`docker compose exec eventguard-ui cat /usr/share/nginx/html/config.js`，正常应显示 `window.__EG_API_KEY__ = "你设置的EG_API_KEY值";`。若为空或 `changeme`，说明 `.env` 的 `EG_API_KEY` 没传给容器（检查 compose `environment` 与 `.env`）。

---

## 7. 启动服务

> 不要带 `chaos` profile（那是随机杀容器的韧性演示，会搞挂生产）。

```bash
cd /opt/EventGuard
docker compose up -d --build
```

首次构建会拉取镜像 + 编译 Java + 安装 Python 依赖 + 构建前端，耗时几分钟。观察日志：

```bash
docker compose ps                 # 看是否全部 healthy/running
docker compose logs -f eventguard-server
docker compose logs -f eventguard-ai
```

等待 PostgreSQL、Kafka 变为 `healthy`（compose 已配置健康检查与依赖顺序），再启动 server/ai。

### 健康检查（验证链路通了）

```bash
# 后端健康
curl http://localhost:8080/actuator/health

# AI 服务健康
curl http://localhost:8000/health        # 若未实现该端点，可 curl http://localhost:8000/docs 看是否返回 Swagger

# 数据库初始化是否执行（postgres-init 脚本）
docker compose exec postgres psql -U eventguard -d eventguard -c "\dt"
```

> 若你做了第 6 节端口收紧（注释掉 `8080`/`8000` 的宿主机映射），上面两个 `curl localhost` 会连不上——这是**预期，不是故障**，原因如下：
> - **根因**：server/ai 的 `ports:` 被注释后，容器内 8080/8000 只在 Docker 内部网络可见，宿主机 `localhost` 上无进程监听，于是 `curl` 报 `connection refused`。这跟服务本身正不正常无关（之前日志里 DispatcherServlet 能初始化，是因为请求来自 compose 内部的 ui 容器反代 `eventguard-server:8080`，不是宿主机 localhost）。
> - **actuator 是开着的**：`application.yml` 配了 `management.endpoints.web.exposure.include: health,info,metrics`，`/actuator/health` 默认可访问。
> - **不会被 API Key 拦**：`ApiKeyAuthFilter` 对 `path.startsWith("/actuator")` 免鉴权，所以容器内直连无需带 `X-API-Key` 头，不会 401。
>
> 因此改用容器内部探活（这是生产环境的标准姿势，且无需把 8080/8000 对外开放）：
> `docker compose exec eventguard-server curl -s localhost:8080/actuator/health` → 预期 `{"status":"UP",...}`
> `docker compose exec eventguard-ai curl -s localhost:8000/health`

---

## 8. 访问与（可选）域名 + HTTPS

### 8.1 直接用 IP 访问（最简）

浏览器打开 `http://<服务器公网IP>:3000`，应看到 EventGuard 管理台登录/首页。按 README 的「体验流程」走一遍：订单列表 → 自然语言查询 → 异常看板 → 补偿执行。

### 8.2 用宝塔反代 + 域名 + HTTPS（推荐生产）

如果你有域名（已解析 A 记录到服务器 IP）：

1. 宝塔「网站」→「添加站点」，域名填 `eg.example.com`，**不创建 FTP/数据库**，PHP 版本选「纯静态」或默认。
2. 进入该站点 →「反向代理」→「添加反向代理」：
   - 代理名称：`eventguard`
   - 目标 URL：`http://127.0.0.1:3000`（即 UI 容器映射的宿主机端口）
   - 发送域名：`$host`
3. 同站点 →「SSL」→ 申请 Let's Encrypt 免费证书并强制 HTTPS。
4. 此时可把 `docker-compose.yml` 里 ui 的 `ports: ["3000:80"]` 改为 `ports: ["127.0.0.1:3000:80"]`，让 UI 只监听本机，由宝塔统一对外提供 80/443，更干净。

> WebSocket（`/ws` 实时告警）的反代：宝塔反向代理默认已支持 Upgrade，若告警推不动，在站点「配置文件」里确认有 `proxy_set_header Upgrade $http_upgrade;` 与 `proxy_set_header Connection "upgrade";` 两行（大多数宝塔版本默认带）。

---

## 9. 日常运维

```bash
cd /opt/EventGuard

# 看状态
docker compose ps

# 看日志
docker compose logs -f --tail=100 eventguard-ai

# 更新代码后重新部署
git pull
docker compose up -d --build

# 停机（保留数据，pgdata 是命名卷，不会被删）
docker compose down

# 完全清理（含数据卷，慎用）
docker compose down -v
```

数据持久化：PostgreSQL 数据在命名卷 `pgdata` 中，容器重建不丢。AI 的模型文件（`isolation_forest.pkl` 等）在 `eventguard-ai/models`，已打包进镜像；若要更新模型需重新 build。

---

## 10. 常见问题排查

**Q1：前端打开白屏 / 接口 401。**
- 多半是 `VITE_API_KEY` 与 `EG_API_KEY` 不一致。确认第 6.1 步两者相同，并执行 `docker compose up -d --build eventguard-ui` 重新构建前端。
- 浏览器 DevTools「网络」面板看请求是否带 `X-API-Key` 头。

**Q2：异常看板收不到实时告警（WebSocket 连不上）。**
- 确认走的是 `:3000` 入口（nginx 已配 `/ws` 升级）。若用宝塔反代，检查反代配置是否包含 WebSocket Upgrade 头（见 8.2）。

**Q3：AI 自然语言查询返回「降级/摘要」，不是真正大模型回答。**
- 正常现象：未配置 Ollama 时 LLM 自动降级。要启用见附录 B。

**Q4：Kafka / Debezium 一直 unhealthy。**
- `docker compose logs kafka`、`docker compose logs debezium` 看具体报错。常见是 PostgreSQL 未开启逻辑复制——本仓库 `postgres` 已加 `wal_level=logical`，只要用的是 compose 里的 postgres 镜像就没问题。

**Q5：服务器内存不足 / 构建 OOM。**
- 2G 内存机器编 Java 可能 OOM。建议 4G；或在 `docker compose build` 前临时加 swap。

**Q6：构建 AI 镜像时 `pip install` 报 `ReadTimeoutError` / `files.pythonhosted.org` 超时。**
- 国内网络连默认 PyPI 源不稳定。在 `.env` 里把 `PIP_INDEX_URL` 改成国内镜像再重建：
  ```dotenv
  PIP_INDEX_URL=https://mirrors.cloud.tencent.com/pypi/simple
  ```
  ```bash
  docker compose up -d --build eventguard-ai
  ```
- 该参数已内置为构建参数（默认官方 PyPI），非国内环境无需改动。

---

## 附录 A：不用 Docker 的手动部署（不推荐）

仅当你坚持要在服务器上用系统 Java/Python/Nginx 直接跑（不推荐，依赖与版本易错）：

1. **基础设施仍用 Docker**：`docker compose up -d postgres kafka debezium`（只起这三个），它们仍用 `docker-compose.yml` 的服务名互访。
2. **Server**：在 IDEA 里 `Maven → package` 得到 `eventguard-server-*.jar`，上传到服务器，用 JRE 17 运行：
   ```bash
   java -jar eventguard-server-*.jar \
     --DB_URL=jdbc:postgresql://localhost:5432/eventguard \
     --DB_USER=eventguard --DB_PASSWORD=你的密码 \
     --KAFKA_BOOTSTRAP=localhost:9092 \
     --EG_API_KEY=你的密钥
   ```
3. **AI**：服务器装 Python 3.11，`pip install -r eventguard-ai/requirements.txt`，设置 `EG_` 前缀环境变量后 `uvicorn app.main:app --host 0.0.0.0 --port 8000`。
4. **前端**：IDEA/`npm run build` 生成 `dist/`，用宝塔「网站」建一个静态站点指向 `dist/`，并配反向代理把 `/orders`、`/compensations`、`/ai`、`/anomalies`、`/ws` 转到对应后端——等价于仓库里 `eventguard-ui/nginx.conf` 的规则。

> 这条路径要手动维护三套运行环境与反向代理，远不如第 7 节的 Docker Compose 一键部署省心，**强烈建议用 Docker**。

---

## 附录 B：启用本地大模型根因分析（可选）

默认 `EG_LLM_BASE_URL=http://ollama:11434/v1` 指向一个不存在的 Ollama 服务，AI 会降级。若要真正的大模型根因，在 `docker-compose.yml` 增加 Ollama 服务：

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

启动后拉取模型（首次需下载约 5GB）：

```bash
docker compose up -d ollama
docker compose exec ollama ollama pull qwen2.5:7b
```

保持 `.env` 里 `EG_LLM_*` 默认即可（已指向 `ollama:11434`）。注意这会显著增加内存与磁盘占用，轻量服务器请评估后再开。
