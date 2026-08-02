# EventGuard 开发调试指南

本指南覆盖**完整的前端 + 后端热更新开发流程**：改代码即时在浏览器看到效果，
无需每次重建镜像，且整套开发配置**不影响生产部署**（使用者零感知）。

工作流由三部分组成：

- **前端热更新**：Vite dev server（`npm run dev`，端口 3000）提供 HMR，保存即刷新。
- **后端热更新**：`eventguard-server`（Java）改代码自动重启、`eventguard-ai`（Python）改代码自动重载。
- **本地访问**：通过 SSH 本地端口转发，把服务器上的 dev server 映射到本机 `localhost:3000`，
  在家用电脑上像访问本地服务一样调试远程服务器。

---

## 前置条件

- 本地已配置到开发服务器的 SSH 免密登录（密钥）。
- 服务器上 `eventguard-ui` 依赖已装（`node_modules` 存在），`npm run dev` 可直启。
- 服务器已构建过生产镜像（首次用开发模式前先 `docker compose build eventguard-ai eventguard-server`）。
- Windows 需开启系统自带组件 **OpenSSH 客户端**（`ssh.exe`，Win10+ 可选功能中开启）。

> 本服务器 `root` 禁止密码登录（`PermitRootLogin prohibit-password`），只能用密钥。
> 本地需持有已在服务器 `authorized_keys` 登记的公钥对应的私钥。

---

## 完整使用流程（推荐顺序）

### 1. 启动后端热更新（替换生产后端为开发版）

开发版后端容器会占用 `8080` / `8000` 端口，先停掉生产后端避免冲突：

```bash
# 停生产后端（前端 nginx 容器可保留也可停，不影响）
docker compose down eventguard-server eventguard-ai

# 首次：构建薄开发镜像（仅换启动方式，不打应用，很快）
docker compose -f docker-compose.yml -f docker-compose.dev.yml build eventguard-server eventguard-ai

# 启动后端开发模式（端口/服务名不变，Vite 代理照常工作）
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d eventguard-server eventguard-ai
```

启动后：

- **Java**：容器后台持续增量编译，Spring Boot DevTools 在类路径变化后自动重启——改 `.java` 保存即生效。
- **Python**：uvicorn `--reload`，改 `.py` 保存即重启。

### 2. 启动前端 dev server（服务器侧）

脚本默认会自动检查并拉起服务器侧的 Vite dev server（目录 `/opt/EventGuard/eventguard-ui`）。
若想手动控制，也可直接上服务器执行：

```bash
cd /opt/EventGuard/eventguard-ui && npm run dev
```

> dev server 在服务器上以后台进程常驻，调试断开后不会自动关闭。

### 3. 本地建立 SSH 隧道

**Linux / macOS / Git Bash：**

```bash
# 直接传 用户@主机（自动确保服务器侧 dev server 已起）
./dev-tunnel.sh root@服务器IP

# 指定私钥（服务器禁用密码登录时必须）
./dev-tunnel.sh root@服务器IP --key ~/.ssh/eventguard_key

# 只转发、不自动起服务器侧 dev server
./dev-tunnel.sh root@服务器IP --no-start
```

**Windows PowerShell（`.ps1` 须从 PowerShell 命令行运行，勿双击）：**

```powershell
.\dev-tunnel.ps1 root@服务器IP
.\dev-tunnel.ps1 root@服务器IP -IdentityFile C:\Users\你的用户\.ssh\eventguard_key
.\dev-tunnel.ps1 root@服务器IP -NoStart
```

首次被执行策略拦截时：

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
# 或单次绕过：
powershell -ExecutionPolicy Bypass -File .\dev-tunnel.ps1 root@服务器IP
```

隧道建立后，脚本会停在“建立 SSH 转发”并保持窗口打开——**此窗口不可关闭**。

### 4. 浏览器调试

打开 `http://localhost:3000`（若本地 3000 被占用，脚本自动顺延到 `3001` 并提示）。
保存任意前端/后端代码，浏览器即时热更新。

### 5. 结束与恢复

- **临时离开**：直接 `Ctrl-C` 断开隧道即可。服务器侧 dev server 与开发版后端继续后台运行，
  下次重连隧道即可，无需重复启动。
- **恢复生产**：调试结束后，停掉开发版后端、用普通命令拉起生产后端：

  ```bash
  docker compose -f docker-compose.yml -f docker-compose.dev.yml down eventguard-server eventguard-ai
  docker compose up -d eventguard-server eventguard-ai
  ```

  上线前端改动（nginx 生产镜像）仍需：

  ```bash
  docker compose up -d --build eventguard-ui
  ```

---

## 脚本选项与环境变量

`dev-tunnel.sh` / `dev-tunnel.ps1` 通用：

| 项 | 说明 | 默认值 |
| --- | --- | --- |
| 位置参数 `user@host` | 目标服务器，优先级高于环境变量 | — |
| `--no-start` / `-NoStart` | 只做转发，不自动启动服务器侧 dev server | 默认自动启动 |
| `--key <文件>`（bash）<br>`-IdentityFile <文件>`（PowerShell） | 指定私钥（服务器禁用密码登录时必须） | — |
| `EG_SSH_HOST` | 服务器地址（bash 环境变量方式） | — |
| `EG_SSH_USER` | 登录用户（bash 环境变量方式） | `root` |
| `EG_UI_DIR` | 服务器上前端目录 | `/opt/EventGuard/eventguard-ui` |
| `EG_LOCAL_PORT` | 本地监听端口 | `3000` |

> 注：bash 的 `EG_SSH_HOST=... ./script.sh` 前缀语法是 bash 专属，PowerShell 不支持，请用参数形式。
> Windows 下 Git Bash 中私钥路径用 `/c/Users/你的用户/.ssh/eventguard_key` 形式。

---

## 注意事项

1. **端口冲突是头号坑**：开发版后端占用 `8080`/`8000`，启动前务必先
   `docker compose down eventguard-server eventguard-ai` 停掉生产后端，否则容器起不来。
2. **开发配置不影响生产**：`docker-compose.dev.yml` 与 `Dockerfile.dev` 是独立覆盖文件，
   仅当显式 `-f docker-compose.dev.yml` 时才生效。普通 `docker compose up -d` 走的仍是生产配置，
   使用者完全无感知。`pom.xml` 里的 `spring-boot-devtools` 标记为 `optional`，生产 jar 自动剔除。
3. **服务名不变**：开发版沿用 `eventguard-server` / `eventguard-ai` 服务名与端口，
   因此 Vite 代理（`eventguard-server:8080` / `eventguard-ai:8000`）和生产环境一致，无需改前端代理配置。
4. **私钥权限**：私钥文件权限过宽会被 ssh 拒绝。Linux/Git Bash 执行
   `chmod 600 私钥路径`；Windows 把 `.ssh` 目录权限设为仅当前用户可读。
5. **小内存机器 OOM 风险**：默认已按下保守值（`eventguard-server` mem_limit 1g / 堆 320m，
   `eventguard-ai` mem_limit 512m）留约 300m 余量。极小内存机器仍 OOM 时再下调，并注意约束：
   Java 跑两个 JVM（编译循环 + run），上调时须保证「两 JVM 堆之和 + ~200m」< `mem_limit`，
   否则 cgroup 会 OOM-kill；堆上限以 `JAVA_TOOL_OPTIONS` 的 `-Xmx` 为准。
6. **dev server 后台常驻**：SSH 隧道断开不会杀掉服务器侧 dev server；长期不用记得
   手动停止，避免占用资源。
7. **勿误提交开发镜像**：dev 镜像在本地构建，不要 `docker compose push` 开发版镜像。
8. **WebSocket**：`/ws` 走 WebSocket 代理，热更新与实时异常推送在同一来源下正常工作，无需额外配置。

---

## 常见问题

### 双击 `.ps1` 用记事本打开了
Windows 默认把 `.ps1` 关联记事本。从 **PowerShell（或终端）命令行**执行，或改用 Git Bash 跑 `dev-tunnel.sh`。

### `Permission denied (publickey)`
`root` 禁止密码登录，所用私钥与服务器 `authorized_keys` 登记的公钥不是一对。
确认用了正确的私钥：`--key`（bash）/ `-IdentityFile`（PowerShell）。
或本地 `ssh-keygen -t ed25519`，把 `~/.ssh/id_ed25519.pub` 发给管理员追加到服务器 `authorized_keys`。

### `WARNING: UNPROTECTED PRIVATE KEY FILE`
见上方「注意事项 4」。

### 本地仍打不开 `localhost:3000`
说明隧道没真正建立——SSH 认证失败会走兜底提示但**不会建隧道**。确认脚本最后停在
“建立 SSH 转发”之后、且该窗口保持打开，再去浏览器访问。若 3000 被占用会自动顺延到 3001，按提示访问。

### 改了后端代码没生效
- 确认跑的是**开发版**后端（`docker compose ... -f docker-compose.dev.yml up`），而非生产容器。
- Java 改动需等待 DevTools 自动重启完成（容器日志可见重启日志）；Python 改 `.py` 后 uvicorn 自动重载。
- 若后端是普通 `docker compose up` 起的生产容器，改代码不会热更新，需重建镜像。

---

## 原理

- 开发态前端由 Vite dev server 提供（非生产 nginx 构建）。`vite.config.ts` 已将
  `/orders`、`/compensations` 代理到 `localhost:8080`，`/anomalies`、`/ai` 代理到
  `localhost:8000`，`/ws` 走 WebSocket 代理。Vite dev server 跑在服务器上，故 `localhost`
  指服务器本机，正是开发版后端映射出来的端口。
- 后端开发版通过 `docker-compose.dev.yml` 使用 `Dockerfile.dev`：Java 用 `mvn spring-boot:run`
  + DevTools，Python 用 `uvicorn --reload`，源码以 volume 挂载进容器，改即重载。
- SSH 本地转发把服务器 `3000` 映射到本地 `3000`，浏览器访问 `localhost:3000` 即等同访问服务器
  dev server；前端接口与 WebSocket 经转发抵达后端，热更新也在同一来源下正常工作。

---

## 发布到生产

调试完成后，若需让线上（nginx 生产镜像）反映前端改动：

```bash
docker compose up -d --build eventguard-ui
```

后端生产发布仍是常规流程（普通 `docker compose up -d`，不含 dev 文件），行为与之前完全一致。
