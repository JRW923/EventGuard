#!/usr/bin/env bash
# EventGuard 备份脚本：PostgreSQL + AI 侧异常记录。
#
# 用法：
#   ./backup-db.sh                     # 备份到默认目录（./backups，保留 14 天）
#   BACKUP_DIR=/data/eg-backups RETENTION_DAYS=30 ./backup-db.sh
#   BACKUP_UPLOAD_CMD='rclone copyto remote:eg-backups/$(basename "$1")' ./backup-db.sh
#                                      # 备份后上传远端（$1=备份文件路径；失败不影响本地备份）
#
# 定时执行（crontab，每日 03:17 避开高峰；分钟避开 :00/:30 避免与其它任务撞车）：
#   17 3 * * * /opt/EventGuard/scripts/backup-db.sh >> /var/log/eg-backup.log 2>&1
#
# 恢复方式：
#   docker exec -i eventguard-postgres-1 pg_restore -U eventguard -d eventguard \
#     --clean --if-exists < backups/eventguard-2026-08-03T0317.dump
#   （或在 postgres 容器内：pg_restore --clean --if-exists -d eventguard /backups/xxx.dump）
#
#   # AI 异常记录：直接拷回挂载卷（AI 服务下次启动或从库里重新分析即可）
#   cp backups/anomalies-2026-08-31T0317.jsonl ai-data/anomalies.jsonl
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

# AI 侧异常记录：存在挂载卷 ai-data/（被 gitignore、不在 pg_dump 范围内），必须单独备份。
# 该文件曾因在容器内跑测试（AnomalyStore 默认路径取自 EG_ANOMALY_STORE_PATH）被清空且无备份
# 不可恢复，只能从 anomaly_alerts 表部分重建。故一并纳入备份。
AI_STORE="${AI_STORE:-$(cd "$(dirname "$0")/.." && pwd)/ai-data/anomalies.jsonl}"
if [ -s "$AI_STORE" ]; then
  AI_FILE="$BACKUP_DIR/anomalies-${TS}.jsonl"
  cp "$AI_STORE" "$AI_FILE"
  echo "[$(date '+%F %T')] AI 异常记录备份完成：$(wc -l < "$AI_FILE") 条"
else
  echo "[$(date '+%F %T')] 警告：AI 异常记录缺失或为空，跳过（$AI_STORE）" >&2
fi

# 可选远程上传钩子：设置 BACKUP_UPLOAD_CMD 即启用，备份文件路径以 $1 传入。
# 失败不抹掉本地备份（exit 0 继续），只告警到日志由 cron 邮件/日志采集兜底。
# 例：BACKUP_UPLOAD_CMD='rclone copyto remote:eg-backups/$(basename "$1")'
if [ -n "${BACKUP_UPLOAD_CMD:-}" ]; then
  if bash -c "$BACKUP_UPLOAD_CMD '$FILE'"; then
    echo "[$(date '+%F %T')] 远程上传完成：$FILE"
  else
    echo "[$(date '+%F %T')] 警告：远程上传失败（本地备份仍保留）：$FILE" >&2
  fi
fi

# 清理过期备份（保留最近 RETENTION_DAYS 天）
find "$BACKUP_DIR" -name "${PG_DB}-*.dump" -mtime +"$RETENTION_DAYS" -delete
find "$BACKUP_DIR" -name "anomalies-*.jsonl" -mtime +"$RETENTION_DAYS" -delete
echo "[$(date '+%F %T')] 清理完成（保留 ${RETENTION_DAYS} 天）"
