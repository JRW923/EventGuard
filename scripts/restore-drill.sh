#!/usr/bin/env bash
# 真实恢复演练：对当前生产库做一份即时备份，恢复到独立的演练库 eventguard_drill，
# 校验事件/投影/命令日志等核心表零丢失且读模型一致，最后清理演练库。
#
# 不触碰生产库数据；恢复或校验失败则非零退出，可直接挂到 cron / 监控告警。
#
# 用法：
#   ./restore-drill.sh
#   DRILL_DB=eventguard_drill2 BACKUP_DIR=/data/eg-backups ./restore-drill.sh
#   DRILL_KEEP_DUMP=1 ./restore-drill.sh        # 保留演练用 dump 作为证据
#
# ponytail: 演练库建在同主机 Postgres 实例上，验证的是「恢复机制 + 数据完整性」，
#   不覆盖异地/跨机恢复（见 生产就绪缺口：异地备份恢复）。需要跨机验证时把 DUMP 拷到目标机再 pg_restore。
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-eventguard-postgres-1}"
PG_USER="${PG_USER:-eventguard}"
PG_DB="${PG_DB:-eventguard}"
DRILL_DB="${DRILL_DB:-eventguard_drill}"
BACKUP_DIR="${BACKUP_DIR:-$(cd "$(dirname "$0")/.." && pwd)/backups}"
DRILL_KEEP_DUMP="${DRILL_KEEP_DUMP:-0}"

mkdir -p "$BACKUP_DIR"
TS=$(date +%Y-%m-%dT%H%M)
DUMP="$BACKUP_DIR/${PG_DB}-drill-${TS}.dump"

log() { echo "[$(date '+%F %T')] $*"; }
count() { # $1=db $2=sql  —— 失败/缺表返回 0，交由后续比对暴露问题
  docker exec "$PG_CONTAINER" psql -U "$PG_USER" -tA -d "$1" -c "$2" 2>/dev/null || echo 0
}

# 1) 即时备份当前生产库（dump 即恢复基准）
log "演练开始：备份 ${PG_DB} → ${DUMP}"
docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" --format=custom --no-owner > "$DUMP"
if ! head -c5 "$DUMP" | grep -q "PGDMP"; then
  log "错误：演练备份文件无效（非 pg_dump custom 格式）" >&2
  rm -f "$DUMP"; exit 1
fi

# 2) 生产库行数快照（恢复零丢失对比基准）
SRC_EVENTS=$(count "$PG_DB" "SELECT count(*) FROM domain_events;")
SRC_VIEW=$(count "$PG_DB" "SELECT count(*) FROM order_view;")
SRC_CMD=$(count "$PG_DB" "SELECT count(*) FROM command_log;")
log "生产库基准：domain_events=$SRC_EVENTS order_view=$SRC_VIEW command_log=$SRC_CMD"

# 3) 准备独立演练库（与生产库同实例，但不影响生产数据）
docker exec "$PG_CONTAINER" psql -U "$PG_USER" -c "DROP DATABASE IF EXISTS \"$DRILL_DB\" WITH (FORCE);" >/dev/null 2>&1 || true
docker exec "$PG_CONTAINER" psql -U "$PG_USER" -c "CREATE DATABASE \"$DRILL_DB\" OWNER \"$PG_USER\";"

# 4) 恢复（--no-owner 避免角色权限差异；--clean --if-exists 对全新库无害）
log "恢复到演练库 ${DRILL_DB}"
docker exec -i "$PG_CONTAINER" pg_restore -U "$PG_USER" -d "$DRILL_DB" --no-owner --clean --if-exists < "$DUMP"

# 5) 校验：核心表行数一致（零丢失）
fail=0
DST_EVENTS=$(count "$DRILL_DB" "SELECT count(*) FROM domain_events;")
DST_VIEW=$(count "$DRILL_DB" "SELECT count(*) FROM order_view;")
DST_CMD=$(count "$DRILL_DB" "SELECT count(*) FROM command_log;")
for pair in "domain_events:$SRC_EVENTS:$DST_EVENTS" "order_view:$SRC_VIEW:$DST_VIEW" "command_log:$SRC_CMD:$DST_CMD"; do
  t="${pair%%:*}"; rest="${pair#*:}"; src="${rest%%:*}"; dst="${rest#*:}"
  if [ "$src" != "$dst" ]; then
    log "校验失败：$t 生产=$src 演练=$dst（行数不一致）" >&2; fail=1
  else
    log "校验通过：$t = $dst"
  fi
done

# 6) 业务可用性（读模型一致性）：order_view 行数 == OrderCreatedEvent 事件数
CREATED=$(count "$DRILL_DB" "SELECT count(*) FROM domain_events WHERE event_type='OrderCreatedEvent';")
if [ "$CREATED" != "$DST_VIEW" ]; then
  log "业务校验失败：order_view($DST_VIEW) 与 OrderCreatedEvent($CREATED) 不一致（投影可能缺漏）" >&2
  fail=1
else
  log "业务可用性校验通过：读模型 order_view 与事件源一致（$DST_VIEW 单）"
fi

# 7) 清理演练库与（可选）dump
docker exec "$PG_CONTAINER" psql -U "$PG_USER" -c "DROP DATABASE IF EXISTS \"$DRILL_DB\" WITH (FORCE);" >/dev/null 2>&1 || true
if [ "$DRILL_KEEP_DUMP" != "1" ]; then rm -f "$DUMP"; fi
log "演练库已清理"

if [ "$fail" -ne 0 ]; then
  log "=== 恢复演练未通过 ===" >&2
  exit 1
fi
log "=== 恢复演练通过：备份可成功恢复，核心数据零丢失，读模型一致 ==="
