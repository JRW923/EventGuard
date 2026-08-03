#!/usr/bin/env bash
# EventGuard PostgreSQL 自动备份脚本。
#
# 用法：
#   ./backup-db.sh                     # 备份到默认目录（./backups，保留 14 天）
#   BACKUP_DIR=/data/eg-backups RETENTION_DAYS=30 ./backup-db.sh
#
# 定时执行（crontab，每日 03:17 避开高峰；分钟避开 :00/:30 避免与其它任务撞车）：
#   17 3 * * * /opt/EventGuard/scripts/backup-db.sh >> /var/log/eg-backup.log 2>&1
#
# 恢复方式：
#   docker exec -i eventguard-postgres-1 pg_restore -U eventguard -d eventguard \
#     --clean --if-exists < backups/eventguard-2026-08-03T0317.dump
#   （或在 postgres 容器内：pg_restore --clean --if-exists -d eventguard /backups/xxx.dump）
set -euo pipefail

# 容器名 / 库信息（与 docker-compose.yml 对齐；可被环境变量覆盖）
PG_CONTAINER="${PG_CONTAINER:-eventguard-postgres-1}"
PG_USER="${PG_USER:-eventguard}"
PG_DB="${PG_DB:-eventguard}"

BACKUP_DIR="${BACKUP_DIR:-$(cd "$(dirname "$0")/.." && pwd)/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

mkdir -p "$BACKUP_DIR"
TS=$(date +%Y-%m-%dT%H%M)
FILE="$BACKUP_DIR/${PG_DB}-${TS}.dump"

echo "[$(date '+%F %T')] 开始备份 ${PG_DB} → ${FILE}"

# --format=custom：pg_dump 原生压缩格式，pg_restore 可选表恢复
docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" --format=custom --no-owner > "$FILE"

# 校验：文件非空且以 custom 格式魔数开头（PGDMP）
if ! head -c5 "$FILE" | grep -q "PGDMP"; then
  echo "[$(date '+%F %T')] 错误：备份文件无效（非 pg_dump custom 格式）" >&2
  rm -f "$FILE"
  exit 1
fi

echo "[$(date '+%F %T')] 备份完成：$(du -h "$FILE" | cut -f1)"

# 清理过期备份（保留最近 RETENTION_DAYS 天）
find "$BACKUP_DIR" -name "${PG_DB}-*.dump" -mtime +"$RETENTION_DAYS" -delete
echo "[$(date '+%F %T')] 清理完成（保留 ${RETENTION_DAYS} 天）"
