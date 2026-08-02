package com.eventguard.event.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 退款请求事件（意图）：标记退款流程已发起，状态不立即变更（订单仍 PAID，待退款结果确认）。
 * 曾仅存在于 R003StateJumpRule 与 Python process_level 的映射表而无 Java 类，现补齐。
 */
public class OrderRefundRequestedEvent extends DomainEvent {
    private final BigDecimal refundAmount;

    public OrderRefundRequestedEvent(UUID orderId, int version, BigDecimal refundAmount, Map<String, String> metadata) {
        super(orderId, version, metadata);
        this.refundAmount = refundAmount;
    }

    public OrderRefundRequestedEvent(UUID eventId, UUID aggregateId, int version, Instant occurredAt,
                                     Map<String, String> metadata, BigDecimal refundAmount) {
        super(eventId, aggregateId, "OrderRefundRequestedEvent", version, occurredAt, metadata);
        this.refundAmount = refundAmount;
    }

    @Override public Object getPayload() {
        return Map.of("orderId", getAggregateId(), "refundAmount", refundAmount);
    }

    public BigDecimal getRefundAmount() { return refundAmount; }
}
