-- 网关层表：网关请求/回调关联、通知审计、补偿审批流。
-- 表结构幂等；不参与 Debezium publication（仅 domain_events），无需 CDC。

-- 网关请求/回调关联表（支付异步回调的关联依据，兼作 outbox 状态）
CREATE TABLE IF NOT EXISTS gateway_request (
    id               UUID PRIMARY KEY,
    command_id       UUID        NOT NULL,
    aggregate_id     UUID        NOT NULL,
    gateway_type     VARCHAR(32) NOT NULL,      -- PAYMENT / INVENTORY / NOTIFICATION
    request_type     VARCHAR(32) NOT NULL,      -- CREATE_PAYMENT / CAPTURE / REFUND / RESERVE / NOTIFY
    provider         VARCHAR(32) NOT NULL,      -- mock / alipay / ...
    external_ref     VARCHAR(128),              -- 网关侧支付单号，回调按它反查
    status           VARCHAR(32) NOT NULL,      -- PENDING / SUCCEEDED / FAILED
    request_payload  JSONB,
    response_payload JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_gateway_request_external_ref ON gateway_request (external_ref);
CREATE INDEX IF NOT EXISTS idx_gateway_request_aggregate   ON gateway_request (aggregate_id);

-- 通知发送审计（可追溯，AI 根因分析也能查）
CREATE TABLE IF NOT EXISTS notification_log (
    id                 UUID PRIMARY KEY,
    aggregate_id       UUID,
    notification_type  VARCHAR(32),
    recipient          VARCHAR(255),
    channel            VARCHAR(32),
    status             VARCHAR(32),
    payload            JSONB,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notification_log_aggregate ON notification_log (aggregate_id);

-- 审批流（对齐设计文档 7.4.4）
CREATE TABLE IF NOT EXISTS compensation_approval (
    approval_id    UUID PRIMARY KEY,
    saga_id        UUID NOT NULL,
    action_type    VARCHAR(64) NOT NULL,
    aggregate_id   UUID NOT NULL,
    params         JSONB,
    status         VARCHAR(32) NOT NULL,   -- PENDING / APPROVED / REJECTED
    requested_by   VARCHAR(64) NOT NULL,   -- agent / human
    requested_at   TIMESTAMPTZ NOT NULL,
    decided_at     TIMESTAMPTZ,
    decided_by     VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_compensation_approval_status ON compensation_approval (status);
