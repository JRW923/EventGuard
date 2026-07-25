#!/usr/bin/env bash
# db-kill.sh —— 模拟 PostgreSQL 进程崩溃（docker kill），验证事件溯源数据不丢。
#
# 对齐计划 M5.2：pumba kill postgres 30s，验证数据不丢。
# 这里直接用 `docker kill` + `docker start` 复现同类故障（与 pumba 的 kill 故障等效，
# 且无需额外起 pumba 容器）。数据持久化由 docker-compose 的命名卷 pgdata 保证。
#
# 前置：docker compose up -d --build 且 postgres 健康。
# ponytail: 仅验证「容器崩溃 + 卷持久化」场景；不覆盖「卷本身损坏/丢失」的极端数据丢失。

set -euo pipefail
source "$(dirname "$0")/verify.sh"

echo "== [db-kill] 故障前：记录 domain_events 行数 =="
BEFORE=$(pg_count_events)
echo "  before=$BEFORE"

CID=$(pg_cid)
echo "== [db-kill] kill postgres 容器 ($CID) 模拟进程崩溃 =="
docker kill "$CID"

echo "== [db-kill] 重新拉起容器并等待 PG 健康 =="
docker start "$CID"
wait_for "postgres" pg_health 30

echo "== [db-kill] 故障后：核对 domain_events 行数 =="
AFTER=$(pg_count_events)
echo "  after=$AFTER"

if [[ "$BEFORE" = "$AFTER" ]]; then
  echo "✓ 数据未丢失：事件溯源在 PG 崩溃重启后保持一致（before=$BEFORE / after=$AFTER）"
else
  echo "✗ 数据不一致：before=$BEFORE / after=$AFTER" >&2
  exit 1
fi

# 命令端应能继续写入
wait_for "eventguard-server" server_health 30
if command_write_ok; then
  echo "✓ 命令端在 PG 恢复后可继续写入（POST /orders → 200）"
else
  echo "✗ 命令端写入失败" >&2
  exit 1
fi
