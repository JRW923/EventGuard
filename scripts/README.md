# 开发调试：一键 SSH 转发脚本

本地电脑通过 SSH 本地端口转发访问服务器上的 Vite dev server，获得热更新（HMR）调试体验，
无需每次改代码都重建前端容器。

## 脚本

- `dev-tunnel.sh` —— Linux / macOS / WSL / Git Bash 下使用（bash）。
- `dev-tunnel.ps1` —— Windows PowerShell 下使用。

两者功能一致：建立 `localhost:3000 → 服务器:3000` 的转发，并默认自动确保服务器侧
dev server 已在运行。

## 前置条件

- 本地已配置好到开发服务器的 SSH 免密登录（密钥）。
- 服务器上 `eventguard-ui` 的依赖已安装（`node_modules` 存在），`npm run dev` 可直接启动。
- 服务器侧相关后端服务（eventguard-server、eventguard-ai 等）正常运行。
- Windows 需安装系统自带组件 **OpenSSH 客户端**（`ssh.exe`，Win10+ 可选功能中开启）。

## 用法（Linux / macOS / Git Bash）

```bash
# 方式一：直接传 用户@主机
./dev-tunnel.sh root@服务器IP

# 方式二：用环境变量（避免每次输入）
EG_SSH_HOST=服务器IP EG_SSH_USER=root ./dev-tunnel.sh

# 指定私钥文件（服务器禁用密码登录时必须，Windows 下 Git Bash 用 /c/Users/... 形式）
./dev-tunnel.sh root@服务器IP --key ~/.ssh/eventguard_key
./dev-tunnel.sh root@服务器IP --key "/c/Users/你的用户/.ssh/eventguard_key"
```

## 用法（Windows PowerShell）

> 注意：bash 版里的 `EG_SSH_HOST=... ./script.sh` 前缀语法是 bash 专属，PowerShell 不支持，
> 请用下面参数形式。

```powershell
# 方式一：直接传 用户@主机
.\dev-tunnel.ps1 root@服务器IP

# 方式二：用参数
.\dev-tunnel.ps1 -SshHost 服务器IP -SshUser root

# 指定私钥文件（服务器禁用密码登录时必须）
.\dev-tunnel.ps1 root@服务器IP -IdentityFile C:\Users\你的用户\.ssh\eventguard_key

# 只转发、不自动起服务器
.\dev-tunnel.ps1 root@服务器IP -NoStart
```

首次运行若被 PowerShell 执行策略拦截，先执行一次：

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

或直接绕过策略运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\dev-tunnel.ps1 root@服务器IP
```

运行后：

1. 脚本检查服务器侧 dev server（默认 `/opt/EventGuard/eventguard-ui`）是否在跑；
   没运行则后台拉起，已运行则跳过。
2. 建立本地转发 `localhost:3000 → 服务器:3000`。
3. 浏览器打开 `http://localhost:3000` 即可调试，保存代码即时热更新。
4. 按 `Ctrl-C` 断开转发；服务器侧 dev server 不受影响，继续后台运行。

## 选项与环境变量

| 项 | 说明 | 默认值 |
| --- | --- | --- |
| 位置参数 `user@host` | 目标服务器，优先级高于环境变量 | — |
| `--no-start` | 只做转发，不自动启动服务器侧 dev server | 默认会自动启动 |
| `EG_SSH_HOST` | 服务器地址（方式二） | — |
| `EG_SSH_USER` | 登录用户（方式二） | `root` |
| `EG_UI_DIR` | 服务器上前端目录 | `/opt/EventGuard/eventguard-ui` |
| `EG_LOCAL_PORT` | 本地监听端口 | `3000` |
| `EG_SSH_KEY` / `--key <文件>`（bash）<br>`-IdentityFile <文件>`（PowerShell） | 指定私钥文件（服务器禁用密码登录时必须） | — |

> 注：本服务器 `root` 禁止密码登录（`PermitRootLogin prohibit-password`），只能用密钥。
> 本地需持有已在服务器 `authorized_keys` 登记的公钥对应的私钥；可用 `--key ~/.ssh/事件卫士`
> 指定，或把公钥发管理员追加到 `/root/.ssh/authorized_keys`。

本地 `3000` 端口被占用时，脚本会自动顺延到 `3001` 并提示，避免 Vite 自行跳端口造成混淆。

## 常见问题

### 双击 `.ps1` 用记事本打开了
Windows 默认把 `.ps1` 关联记事本。不要用 cmd 或双击运行，必须从 **PowerShell（或终端）命令行**
执行；或者改用 Git Bash 跑 `dev-tunnel.sh`。

### `Permission denied (publickey)`
`root` 禁止密码登录，且所用私钥与服务器 `/root/.ssh/authorized_keys` 登记的公钥不是一对。
- 确认用了正确的私钥：bash 用 `--key /路径/私钥`，PowerShell 用 `-IdentityFile C:\路径\私钥`。
- 或本地生成新密钥 `ssh-keygen -t ed25519`，把 `~/.ssh/id_ed25519.pub` 发给管理员追加到服务器
  `authorized_keys`。

### `WARNING: UNPROTECTED PRIVATE KEY FILE`
私钥文件权限过宽被 ssh 拒绝。Git Bash 下执行 `chmod 600 私钥路径`；Windows 下把 `.ssh`
目录权限设为仅当前用户可读。

### 本地仍打不开 `localhost:3000`
说明隧道没真正建立——SSH 认证失败会走兜底提示但不会建隧道。确认脚本最后停在
“建立 SSH 转发”之后、且该窗口保持打开，再去浏览器访问。若 3000 被占用会自动顺延到 3001，
按提示访问对应端口即可。

## 后端热更新（Java / Python）

前端用 Vite dev server 热更新；后端（`eventguard-server` Java、`eventguard-ai` Python）也可用
独立开发配置实现**改代码即重载，无需重建镜像**。

- 配置隔离在 `docker-compose.dev.yml` + 各服务的 `Dockerfile.dev`，**不修改生产
  `docker-compose.yml` / `Dockerfile`**，生产部署（`docker compose up -d`）完全无感知。
- 服务名、端口、网络、依赖、环境变量均与生产一致，因此 Vite 代理
  （`eventguard-server:8080` / `eventguard-ai:8000`）照常工作。

### 用法

```bash
# 首次：构建薄开发镜像（仅换启动方式，不重新打应用，很快；若从未构建过生产镜像，先跑一次
# docker compose build eventguard-ai eventguard-server）
docker compose -f docker-compose.yml -f docker-compose.dev.yml build eventguard-server eventguard-ai

# 启动后端开发模式（会替换正在运行的 prod 后端容器，端口不变）
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d eventguard-server eventguard-ai
```

- **Java**：容器后台持续增量编译，Spring Boot DevTools 在类路径变化后自动重启——改 `.java` 保存即生效。
- **Python**：uvicorn `--reload`，改 `.py` 保存即重启。

### 说明

- 仅开发调试用；上线仍跑 `docker compose up -d`（不含 dev 文件），行为与之前完全一致。
- 小内存机器注意：dev 版 Java 服务内存占用高于生产 jar，如 OOM 可适当下调
  `docker-compose.dev.yml` 中 `mem_limit` / 堆。
- 后端改完**无需重建前端**；前端 dev server（见上）代理自动指向重载后的后端。

## 原理

- 开发态前端由 Vite dev server 提供（非生产 nginx 构建），`vite.config.ts` 已将
  `/orders`、`/compensations` 代理到 `localhost:8080`，`/anomalies`、`/ai` 代理到
  `localhost:8000`，`/ws` 走 WebSocket 代理。
- SSH 本地转发把这些端口映射到本地，浏览器访问 `localhost:3000` 即等同访问服务器 dev server，
  接口与 WebSocket 经转发抵达后端，热更新也在同一来源下正常工作。

## 发布到生产

调试完成后，若需让线上（nginx 生产镜像）也反映改动：

```bash
docker compose up -d --build eventguard-ui
```
