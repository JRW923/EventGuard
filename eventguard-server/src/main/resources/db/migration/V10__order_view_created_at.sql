-- order_view 增加业务时间列 created_at（订单创建时间 = OrderCreatedEvent 的 occurredAt）。
-- 统计查询（GET /orders/stats）原先用 updated_at 过滤时间窗，而 updated_at 是"投影处理时刻"，
-- 语义会变成"最近 N 天被投影更新过的订单"：一旦投影停更，任何"最近 N 天"都查不到历史数据。
ALTER TABLE order_view ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

-- 回填历史行：取该聚合首个事件的写入时间；无事件记录时退回 updated_at。
UPDATE order_view ov
SET created_at = COALESCE(
        (SELECT min(de.created_at)
           FROM domain_events de
          WHERE de.aggregate_id = ov.order_id),
        ov.updated_at)
WHERE ov.created_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_order_view_created ON order_view (created_at DESC);
