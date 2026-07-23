package com.eventguard.event.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class OrderRefundedEvent extends DomainEvent {
    private final BigDecimal refundAmount;

    public OrderRefundedEvent(UUID orderId, int version, BigDecimal refundAmount, Map<String, String> metadata) {
        super(orderId, version, metadata);
        this.refundAmount = refundAmount;
    }

    public OrderRefundedEvent(UUID eventId, UUID aggregateId, int version, Instant occurredAt,
                              Map<String, String> metadata, BigDecimal refundAmount) {
        super(eventId, aggregateId, "OrderRefundedEvent", version, occurredAt, metadata);
        this.refundAmount = refundAmount;
    }

    @Override public Object getPayload() {
        return Map.of("orderId", getAggregateId(), "refundAmount", refundAmount);
    }

    public BigDecimal getRefundAmount() { return refundAmount; }
}
