-- 异常告警持久化（替代进程内环形缓冲）：重启后历史可查，WS 断线补拉不丢
CREATE TABLE IF NOT EXISTS anomaly_alerts (
    anomaly_id      TEXT PRIMARY KEY,           -- 告警幂等键：at-least-once 重复投递不产生第二行
    rule_id         TEXT,
    aggregate_id    TEXT,
    level           TEXT,
    source          TEXT,
    payload         JSONB NOT NULL,             -- 原始告警 JSON，读取时原样反序列化，字段演进零迁移
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_anomaly_alerts_received ON anomaly_alerts (received_at DESC);
