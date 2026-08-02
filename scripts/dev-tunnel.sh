#!/usr/bin/env bash
# 一键建立到开发服务器的 SSH 本地端口转发，本地浏览器即可访问 Vite dev server。
#
# 它会：
#   1. 确认服务器侧的 Vite dev server（默认 /opt/EventGuard/eventguard-ui，端口 3000）已运行；
#      没运行就帮你后台拉起（幂等，已有则跳过）。
#   2. 建立本地转发  localhost:3000 -> 服务器:3000，然后你浏览器开 http://localhost:3000 即可，
#      改代码即时热更新。
#
# 用法（任选其一）：
#   ./scripts/dev-tunnel.sh user@host
#   EG_SSH_HOST=1.2.3.4 EG_SSH_USER=root ./scripts/dev-tunnel.sh
#   ./scripts/dev-tunnel.sh user@host --no-start   # 不自动起服务器，只做转发
#   ./scripts/dev-tunnel.sh user@host --key ~/.ssh/eventguard   # 指定私钥文件
#
# 按 Ctrl-C 断开转发（服务器侧的 dev server 不受影响，继续后台跑）。
set -euo pipefail

UI_DIR="${EG_UI_DIR:-/opt/EventGuard/eventguard-ui}"
LOCAL_PORT="${EG_LOCAL_PORT:-3000}"
REMOTE_ADDR="localhost:3000"
START_SERVER=1
IDENTITY="${EG_SSH_KEY:-}"

# 解析参数（--key 需带下一个参数）
TARGET=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-start) START_SERVER=0 ;;
    --key) IDENTITY="$2"; shift ;;
    --key=*) IDENTITY="${1#--key=}" ;;
    -*) echo "未知参数: $1" >&2; exit 1 ;;
    *) TARGET="$1" ;;
  esac
  shift
done

# 没给 user@host 时退回环境变量
if [[ -z "$TARGET" ]]; then
  if [[ -n "${EG_SSH_HOST:-}" ]]; then
    TARGET="${EG_SSH_USER:-root}@${EG_SSH_HOST}"
  else
    echo "未指定服务器。用法: $0 user@host  或设置环境变量 EG_SSH_HOST / EG_SSH_USER" >&2
    exit 1
  fi
fi

# 本地端口被占用则顺延一位，避免 Vite 自动跳到 3001 造成困惑
if command -v ss >/dev/null 2>&1; then
  if ss -ltn 2>/dev/null | grep -q ":${LOCAL_PORT}\b"; then
    LOCAL_PORT=$((LOCAL_PORT + 1))
    echo "本地 ${LOCAL_PORT} 已被占用，改用 ${LOCAL_PORT}（转发目标仍是服务器 3000）"
  fi
fi

# 拼装 ssh 选项（指定私钥时加 -i）
SSH_OPTS=()
if [[ -n "$IDENTITY" ]]; then SSH_OPTS+=(-i "$IDENTITY"); fi

# 服务器侧：确保 dev server 在跑（路径内联进远端命令，避免本地展开）
if [[ "$START_SERVER" -eq 1 ]]; then
  echo "检查服务器侧 dev server（${UI_DIR}）..."
  # 单条远端命令：已在跑则跳过，否则后台拉起（setsid 使其脱离 ssh 会话存活）
  ssh "${SSH_OPTS[@]}" "$TARGET" "cd '${UI_DIR}' 2>/dev/null || exit 0; \
    if pgrep -f vite >/dev/null 2>&1; then echo 'dev server 已在运行，跳过启动'; \
    else setsid nohup npm run dev >/tmp/vite-dev.log 2>&1 & sleep 3; \
      if pgrep -f vite >/dev/null 2>&1; then echo 'dev server 已启动'; else echo '启动失败，查看服务器 /tmp/vite-dev.log'; fi; \
    fi" \
    || echo "（无法自动检查/启动，请手动在服务器运行: cd ${UI_DIR} && npm run dev）"
fi

echo ""
echo "建立 SSH 转发: 本机 ${LOCAL_PORT} -> ${TARGET}:${REMOTE_ADDR}"
echo "转发建立后，浏览器打开 http://localhost:${LOCAL_PORT}"
echo "按 Ctrl-C 断开。"
exec ssh "${SSH_OPTS[@]}" -N -L "${LOCAL_PORT}:${REMOTE_ADDR}" "$TARGET"
