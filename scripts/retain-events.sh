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
#   - 聚合重建依赖完整事件链 + 快照（aggregate_snapshots）。归档边界按「各聚合最新快照版本」：
#     只归档 event_version 严格小于该聚合快照版本的事件——快照之后的事件必须留在主表，
#     否则重建会失败（回放 = 快照 + 快照后事件）。无快照的聚合整体保留。
#   - 快照水位之上再用 created_at 保留期兜底（如快照落后，水位内的旧事件也不动）。
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

# 可归档判定：超过保留期，且该聚合存在快照、事件版本严格小于快照版本
# （无快照的聚合整体保留；快照水位之上的事件保留，保证 快照+后续事件 可重建）
ARCHIVABLE_SQL="FROM domain_events e
JOIN aggregate_snapshots s ON s.aggregate_id = e.aggregate_id
WHERE e.created_at < now() - interval '${RETENTION_DAYS} days'
  AND e.event_version < s.version"

# 统计可归档行数
COUNT=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -A -c "
SELECT count(*) ${ARCHIVABLE_SQL};")
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
SELECT e.* ${ARCHIVABLE_SQL};
DELETE FROM domain_events e
USING aggregate_snapshots s
WHERE s.aggregate_id = e.aggregate_id
  AND e.created_at < now() - interval '${RETENTION_DAYS} days'
  AND e.event_version < s.version;
COMMIT;
SQL

echo "[$(date '+%F %T')] 归档完成（${COUNT} 行 → event_store_archive）"
