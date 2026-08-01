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
```

## 用法（Windows PowerShell）

> 注意：bash 版里的 `EG_SSH_HOST=... ./script.sh` 前缀语法是 bash 专属，PowerShell 不支持，
> 请用下面参数形式。

```powershell
# 方式一：直接传 用户@主机
.\dev-tunnel.ps1 root@服务器IP

# 方式二：用参数
.\dev-tunnel.ps1 -SshHost 服务器IP -SshUser root

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

本地 `3000` 端口被占用时，脚本会自动顺延到 `3001` 并提示，避免 Vite 自行跳端口造成混淆。

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
