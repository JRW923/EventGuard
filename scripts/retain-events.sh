#!/usr/bin/env bash
# EventGuard 事件表/日志保留策略清理脚本（P1-11）。
#
# 说明：domain_events（事件溯源事实源）通常不直接删——删了历史就无法重建聚合。
# 本脚本只做「可选归档」而不是物理删除，避免破坏事件溯源的完整性：
#   1. 默认把超过保留期的事件行 COPY 到归档表 event_store_archive（同结构），
#   2. 再从 domain_events 删除已归档的旧事件。
#   3. 若表 event_store_archive 不存在则自动创建。
#
# 注意：
#   - 聚合重建依赖完整事件链 + 快照（aggregate_snapshots）。若你删除了快照之前的旧事件，
#     重建会失败。生产建议保留快照表不动，只归档 90 天前的旧事件，并确保快照间隔合理。
#   - 默认保留 90 天，可通过 EVENT_RETENTION_DAYS 覆盖。
#   - 该脚本面向「长期运行的存储治理」，默认不执行（DRY_RUN=1），确认后再真正归档。
#
# 用法：
#   EVENT_RETENTION_DAYS=90 ./retain-events.sh            # dry-run（只看数量）
#   DRY_RUN=0 EVENT_RETENTION_DAYS=90 ./retain-events.sh  # 真正归档
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-eventguard-postgres-1}"
PG_USER="${PG_USER:-eventguard}"
PG_DB="${PG_DB:-eventguard}"
RETENTION_DAYS="${EVENT_RETENTION_DAYS:-90}"
DRY_RUN="${DRY_RUN:-1}"

echo "[$(date '+%F %T')] 事件保留策略：保留 ${RETENTION_DAYS} 天，DRY_RUN=${DRY_RUN}"

# 建归档表（幂等）
docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c "
CREATE TABLE IF NOT EXISTS event_store_archive (LIKE domain_events INCLUDING ALL);
" >/dev/null

# 统计可归档行数
COUNT=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -A -c "
SELECT count(*) FROM domain_events
WHERE created_at < now() - interval '${RETENTION_DAYS} days';")
echo "[$(date '+%F %T')] 待归档事件行数：${COUNT}"

if [ "$DRY_RUN" = "1" ]; then
  echo "[$(date '+%F %T')] dry-run：未实际归档。确认后运行 DRY_RUN=0 $0"
  exit 0
fi

if [ "$COUNT" = "0" ]; then
  echo "[$(date '+%F %T')] 无可归档事件，退出"
  exit 0
fi

# 同事务：COPY 到归档表 → 删除旧事件（保证归档与删除原子，避免删了没归档）
docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 <<SQL
BEGIN;
INSERT INTO event_store_archive
SELECT * FROM domain_events
WHERE created_at < now() - interval '${RETENTION_DAYS} days';
DELETE FROM domain_events
WHERE created_at < now() - interval '${RETENTION_DAYS} days';
COMMIT;
SQL

echo "[$(date '+%F %T')] 归档完成（${COUNT} 行 → event_store_archive）"
