-- PG 初始化阶段建表，供 01-logical.sql 创建 publication 使用（与 eventguard-server 的 V1__init.sql 一致，IF NOT EXISTS 幂等）
-- 事件表（append-only）
CREATE TABLE IF NOT EXISTS domain_events (
    event_id        UUID PRIMARY KEY,
    aggregate_id    UUID NOT NULL,
    aggregate_type  VARCHAR(64) NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    event_version   INT NOT NULL,
    payload         JSONB NOT NULL,
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_events_agg_version UNIQUE (aggregate_id, event_version)
);
CREATE INDEX IF NOT EXISTS idx_events_agg_id ON domain_events (aggregate_id, event_version);

-- 命令日志表（幂等命令处理）
CREATE TABLE IF NOT EXISTS command_log (
    command_id      UUID PRIMARY KEY,
    aggregate_id    UUID NOT NULL,
    command_type    VARCHAR(128) NOT NULL,
    result          JSONB,
    executed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cmdlog_agg_id ON command_log (aggregate_id);
