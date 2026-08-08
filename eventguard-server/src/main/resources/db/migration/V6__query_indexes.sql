-- 查询列表与事件时间线索引，降低状态筛选和订单事件回放的深分页成本。
CREATE INDEX IF NOT EXISTS idx_order_view_status_updated
    ON order_view (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_view_updated
    ON order_view (updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_domain_events_aggregate_version
    ON domain_events (aggregate_id, event_version);
CREATE INDEX IF NOT EXISTS idx_domain_events_metadata_gin
    ON domain_events USING GIN (metadata);
CREATE INDEX IF NOT EXISTS idx_domain_events_payload_gin
    ON domain_events USING GIN (payload);
