#!/usr/bin/env pwsh
# 一键建立到开发服务器的 SSH 本地端口转发（Windows / PowerShell 版）。
# 功能同 dev-tunnel.sh：确保服务器侧 Vite dev server 在跑，并建立
# localhost:3000 -> 服务器:3000 的本地转发，本地浏览器即可热更新调试。
#
# 用法:
#   .\dev-tunnel.ps1 root@服务器IP
#   .\dev-tunnel.ps1 -SshHost 服务器IP -SshUser root
#   .\dev-tunnel.ps1 root@服务器IP -NoStart        # 只转发，不自动起服务器
#
# 首次运行若被执行策略拦截，可先执行: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
# 依赖: Windows 自带 OpenSSH 客户端（ssh.exe）；以及到服务器的 SSH 密钥登录。

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Target = "",

    [string]$SshHost = "",
    [string]$SshUser = "root",
    [string]$UiDir = "/opt/EventGuard/eventguard-ui",
    [int]$LocalPort = 3000,
    [switch]$NoStart
)

# 解析目标：位置参数 user@host 优先，否则用 -SshHost/-SshUser
if (-not $Target) {
    if ($SshHost) {
        $Target = "$SshUser@$SshHost"
    } else {
        Write-Error "未指定服务器。用法: .\dev-tunnel.ps1 root@服务器IP  或 -SshHost 服务器IP"
        exit 1
    }
}

# 确认本机有 ssh
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 ssh.exe。请安装 Windows 可选功能“OpenSSH 客户端”。"
    exit 1
}

# 本地端口被占用则顺延一位，避免 Vite 自行跳端口造成困惑
$occupied = Get-NetTCPConnection -LocalPort $LocalPort -ErrorAction SilentlyContinue
if ($occupied) {
    $LocalPort = $LocalPort + 1
    Write-Host "本地 $($LocalPort - 1) 已被占用，改用 $LocalPort（转发目标仍是服务器 3000）"
}

# 服务器侧：确保 dev server 在跑（远程命令在 Linux 服务器上执行）
if (-not $NoStart) {
    Write-Host "检查服务器侧 dev server（$UiDir）..."
    $remoteCmd = "cd '$UiDir' 2>/dev/null || exit 0; " +
        "if pgrep -f vite >/dev/null 2>&1; then echo 'dev server 已在运行，跳过启动'; " +
        "else setsid nohup npm run dev >/tmp/vite-dev.log 2>&1 & sleep 3; " +
        "if pgrep -f vite >/dev/null 2>&1; then echo 'dev server 已启动'; else echo '启动失败，查看服务器 /tmp/vite-dev.log'; fi; fi"
    ssh $Target $remoteCmd
}

Write-Host ""
Write-Host "建立 SSH 转发: 本机 ${LocalPort} -> ${Target}:localhost:3000"
Write-Host "转发建立后，浏览器打开 http://localhost:${LocalPort}"
Write-Host "按 Ctrl-C 断开。"
& ssh -N -L "${LocalPort}:localhost:3000" $Target
