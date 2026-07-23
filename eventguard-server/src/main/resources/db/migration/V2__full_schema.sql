-- 快照表（加速聚合根回放）
CREATE TABLE IF NOT EXISTS aggregate_snapshots (
    aggregate_id    UUID PRIMARY KEY,
    aggregate_type  VARCHAR(64) NOT NULL,
    version         INT NOT NULL,
    state           JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 幂等消费记录表
CREATE TABLE IF NOT EXISTS idempotent_consumers (
    consumer_group  VARCHAR(64) NOT NULL,
    event_id        UUID NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

-- 读模型表（CQRS 查询端）
CREATE TABLE IF NOT EXISTS order_view (
    order_id        UUID PRIMARY KEY,
    status          VARCHAR(32),
    total_amount    DECIMAL(12,2),
    payment_time    TIMESTAMPTZ,
    shipping_time   TIMESTAMPTZ,
    version         INT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_order_view_status ON order_view (status);
CREATE INDEX IF NOT EXISTS idx_order_view_version ON order_view (version);
