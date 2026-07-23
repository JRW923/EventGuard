package com.eventguard.event.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class PaymentRetriedEvent extends DomainEvent {
    private final int retryCount;

    public PaymentRetriedEvent(UUID orderId, int version, int retryCount, Map<String, String> metadata) {
        super(orderId, version, metadata);
        this.retryCount = retryCount;
    }

    public PaymentRetriedEvent(UUID eventId, UUID aggregateId, int version, Instant occurredAt,
                               Map<String, String> metadata, int retryCount) {
        super(eventId, aggregateId, "PaymentRetriedEvent", version, occurredAt, metadata);
        this.retryCount = retryCount;
    }

    @Override public Object getPayload() {
        return Map.of("orderId", getAggregateId(), "retryCount", retryCount);
    }

    public int getRetryCount() { return retryCount; }
}
